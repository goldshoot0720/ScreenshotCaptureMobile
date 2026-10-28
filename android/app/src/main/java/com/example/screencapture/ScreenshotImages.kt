package com.example.screencapture

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.Image
import android.provider.MediaStore
import kotlin.math.abs
import kotlin.math.min

internal object ScreenshotImages {
    const val MAX_STITCH_HEIGHT = 16000
    private const val SAME_FRAME = 14
    private const val MATCH_LIMIT = 72

    data class Join(val shift: Int, val footer: Int)

    fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val padding = plane.rowStride - plane.pixelStride * image.width
        val padded = Bitmap.createBitmap(image.width + padding / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
        val buffer = plane.buffer.duplicate()
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        val bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        if (padded !== bitmap) padded.recycle()
        return bitmap
    }

    fun save(resolver: ContentResolver, bitmap: Bitmap, displayName: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshot Capture")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("無法建立圖片檔案")
        try {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: error("無法寫入圖片檔案")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (failure: Throwable) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }

    /** Mean channel difference over the middle of the screen. Higher means the picture changed. */
    fun regionDiff(previous: Bitmap, next: Bitmap): Int {
        if (previous.width != next.width || previous.height != next.height) return Int.MAX_VALUE
        val width = previous.width
        val height = previous.height
        val y0 = height / 8
        val y1 = height * 7 / 8
        var err = 0L
        var count = 0
        val xStep = (width / 16).coerceAtLeast(8)
        val yStep = ((y1 - y0) / 24).coerceAtLeast(8)
        var y = y0
        while (y < y1) {
            var x = width / 8
            while (x < width * 7 / 8) {
                err += channelDiff(previous.getPixel(x, y), next.getPixel(x, y))
                count++
                x += xStep
            }
            y += yStep
        }
        return if (count == 0) Int.MAX_VALUE else (err / count).toInt()
    }

    /**
     * How many pixels the page moved upward, and how tall a fixed footer is.
     * A shift of 0 means the page did not scroll. Status and navigation bars are ignored,
     * because they stay put and would otherwise look like a perfect match at every offset.
     */
    fun upwardShift(previous: Bitmap, next: Bitmap, topInset: Int, systemBottom: Int, knownFooter: Int?): Join {
        if (previous.width != next.width || previous.height != next.height) return Join(0, 0)
        val width = previous.width
        val height = previous.height
        val top = topInset.coerceIn(0, height / 4)
        val bottom = systemBottom.coerceIn(0, height / 4)
        val scanBottom = height - bottom
        val a = IntArray(width * height)
        val b = IntArray(width * height)
        previous.getPixels(a, 0, width, 0, 0, width, height)
        next.getPixels(b, 0, width, 0, 0, width, height)
        if (meanDiff(a, b, width, top, scanBottom) < SAME_FRAME) return Join(0, 0)
        val footer = (knownFooter ?: fixedFooter(a, b, width, top, scanBottom)).coerceIn(0, (scanBottom - top) / 5)
        val contentBottom = scanBottom - footer
        val contentTop = top
        val contentHeight = contentBottom - contentTop
        if (contentHeight < 80) return Join(0, footer)
        val templateHeight = (contentHeight * 0.12f).toInt().coerceIn(28, 140)
        val margin = (contentHeight * 0.05f).toInt().coerceAtLeast(6)
        val templateTop = contentBottom - margin - templateHeight
        val minShift = 24
        val maxShift = min((height * 0.70f).toInt(), templateTop - contentTop)
        if (templateTop <= contentTop || maxShift <= minShift) return Join(0, footer)
        var bestScore = Int.MAX_VALUE
        var bestShift = 0
        val good = ArrayList<Pair<Int, Int>>()
        for (shift in minShift..maxShift) {
            val score = score(a, b, width, shift, templateTop, templateHeight, contentTop)
            if (score < bestScore) {
                bestScore = score
                bestShift = shift
            }
            if (score <= 80) good += shift to score
        }
        if (bestScore > MATCH_LIMIT) return Join(0, footer)
        val local = good.filter { abs(it.first - bestShift) <= 20 && it.second <= bestScore + 6 }
        val distant = good.filter { abs(it.first - bestShift) > 48 && it.second <= bestScore + 6 }
        val chosen = if (distant.isNotEmpty()) {
            val expected = (height * 0.44f).toInt()
            (local + distant).minBy { abs(it.first - expected) }.first
        } else {
            val plateau = local.filter { it.second <= bestScore + 2 }.sortedBy { it.first }
            if (plateau.isEmpty()) bestShift else plateau[plateau.size / 2].first
        }
        return Join(chosen, footer)
    }

    private fun score(a: IntArray, b: IntArray, width: Int, shift: Int, templateTop: Int, templateHeight: Int, contentTop: Int): Int {
        var err = 0L
        var count = 0
        val xStep = (width / 18).coerceAtLeast(6)
        val xStart = width / 12
        val xEnd = width * 11 / 12
        var y = templateTop
        val yEnd = templateTop + templateHeight
        while (y < yEnd) {
            val ny = y - shift
            if (ny >= contentTop) {
                var x = xStart
                while (x < xEnd) {
                    err += channelDiff(a[y * width + x], b[ny * width + x])
                    count++
                    x += xStep
                    if (count >= 24 && err / count > 96) return (err / count).toInt()
                }
            }
            y += 2
        }
        return if (count == 0) Int.MAX_VALUE else (err / count).toInt()
    }

    private fun meanDiff(a: IntArray, b: IntArray, width: Int, y0: Int, y1: Int): Int {
        var err = 0L
        var count = 0
        val xStep = (width / 24).coerceAtLeast(4)
        val yStep = ((y1 - y0) / 24).coerceAtLeast(6)
        var y = y0
        while (y < y1) {
            var x = width / 10
            while (x < width * 9 / 10) {
                err += channelDiff(a[y * width + x], b[y * width + x])
                count++
                x += xStep
            }
            y += yStep
        }
        return if (count == 0) Int.MAX_VALUE else (err / count).toInt()
    }

    /** Bottom rows that stay identical after a scroll are a fixed bar, not new page content. */
    private fun fixedFooter(a: IntArray, b: IntArray, width: Int, top: Int, scanBottom: Int): Int {
        val maxFooter = ((scanBottom - top) * 0.22f).toInt()
        var count = 0
        var y = scanBottom - 1
        while (y > top && count < maxFooter) {
            if (rowDiff(a, b, width, y) >= 18) break
            count++
            y--
        }
        return if (count >= 18) count else 0
    }

    private fun rowDiff(a: IntArray, b: IntArray, width: Int, y: Int): Int {
        var err = 0L
        var count = 0
        val xStep = (width / 16).coerceAtLeast(4)
        var x = width / 12
        while (x < width * 11 / 12) {
            err += channelDiff(a[y * width + x], b[y * width + x])
            count++
            x += xStep
        }
        return if (count == 0) Int.MAX_VALUE else (err / count).toInt()
    }

    private fun channelDiff(a: Int, b: Int): Int {
        val dr = abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF))
        val dg = abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return dr + dg + db
    }

    /**
     * Builds one tall image. The first frame keeps the status bar; later frames contribute
     * only the newly revealed strip. A fixed footer and the navigation bar are appended once, from the last frame.
     */
    class Stitcher(private val width: Int, private val systemBottom: Int, private val footer: Int) {
        private val paint = Paint().apply { isFilterBitmap = false }
        private var result: Bitmap? = null
        private var contentBottom = 0

        fun append(current: Bitmap, next: Bitmap, shift: Int): Boolean {
            if (shift <= 0 || current.width != width || next.width != width || current.height != next.height) return false
            val chrome = footer + systemBottom
            val frameBottom = current.height - chrome
            if (frameBottom <= shift) return false
            val srcTop = frameBottom - shift
            if (srcTop < 0) return false
            val base = result
            if (base == null) {
                contentBottom = frameBottom
                val height = contentBottom + shift
                if (height + chrome > MAX_STITCH_HEIGHT) return false
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawBitmap(current, Rect(0, 0, width, contentBottom), Rect(0, 0, width, contentBottom), paint)
                canvas.drawBitmap(next, Rect(0, srcTop, width, contentBottom), Rect(0, contentBottom, width, height), paint)
                result = bitmap
                return true
            }
            if (base.height + shift + chrome > MAX_STITCH_HEIGHT) return false
            val grown = Bitmap.createBitmap(width, base.height + shift, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(grown)
            canvas.drawBitmap(base, 0f, 0f, paint)
            canvas.drawBitmap(next, Rect(0, srcTop, width, contentBottom), Rect(0, base.height, width, base.height + shift), paint)
            base.recycle()
            result = grown
            return true
        }

        fun finish(last: Bitmap): Bitmap {
            val base = result ?: return last
            result = null
            val chrome = footer + systemBottom
            if (chrome <= 0 || last.height < chrome) return base
            val output = Bitmap.createBitmap(width, base.height + chrome, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            canvas.drawBitmap(base, 0f, 0f, paint)
            canvas.drawBitmap(last, Rect(0, last.height - chrome, width, last.height), Rect(0, base.height, width, base.height + chrome), paint)
            base.recycle()
            return output
        }

        fun discard() {
            result?.recycle()
            result = null
        }
    }
}
