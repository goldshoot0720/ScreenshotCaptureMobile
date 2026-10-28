package com.example.screencapture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

class FloatingCaptureService : Service() {
    private enum class Mode { SCREEN, LONG_ONE, LONG_MANY }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var panel: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var audio: AudioManager? = null
    private var originalVolume: Int? = null
    @Volatile private var capturing = false
    @Volatile private var stopping = false
    @Volatile private var captureGen = 0
    private var clusterX = 0
    private var clusterY = 0
    private var dragStartX = 0
    private var dragStartY = 0
    private var downRawX = 0f
    private var downRawY = 0f
    private var dragging = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (panel != null) {
            toast("懸浮按鈕已在畫面上")
            return START_STICKY
        }
        val code = intent?.getIntExtra(EXTRA_RESULT, 0) ?: return START_NOT_STICKY
        val data = intent.parcelableIntent<Intent>(EXTRA_DATA) ?: return START_NOT_STICKY
        startForeground(NOTIFICATION_ID, notification("懸浮擷取按鈕已啟用"))
        projection = (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = shutdown()
        }, handler)
        openPipeline()
        showPanel()
        return START_STICKY
    }

    /** Mirrors the display at its current size; a no-op when that size has not changed. */
    private fun openPipeline() {
        val metrics = resources.displayMetrics
        if (reader?.width == metrics.widthPixels && reader?.height == metrics.heightPixels) return
        display?.release()
        reader?.close()
        reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 3)
        display = projection?.createVirtualDisplay(
            "FloatingCapture",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface,
            null,
            handler
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (stopping) return
        if (capturing) {
            captureGen++
            handler.post {
                toast("螢幕方向改變，已取消這次擷取")
                if (!stopping) {
                    setPanelHidden(false)
                    capturing = false
                    notify("懸浮擷取按鈕已啟用")
                }
            }
            return
        }
        handler.postDelayed({
            if (!stopping && !capturing) {
                openPipeline()
                movePanel(clusterX, clusterY)
            }
        }, ROTATE_MILLIS)
    }

    private fun showPanel() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val actions = listOf(
            Action(R.drawable.ic_capture_shutter, "截圖") { startCapture(Mode.SCREEN) },
            Action(R.drawable.ic_long_single, "長截圖（一張圖）") { startCapture(Mode.LONG_ONE) },
            Action(R.drawable.ic_long_multi, "長截圖（多張圖）") { startCapture(Mode.LONG_MANY) },
            Action(R.drawable.ic_settings, "設定") { openSettings() },
            Action(R.drawable.ic_close_app, "關閉APP") { closeApp() }
        )
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.argb(242, 255, 247, 252))
                cornerRadius = dp(22).toFloat()
                setStroke(dp(2), Color.WHITE)
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            elevation = dp(10).toFloat()
        }
        actions.forEachIndexed { index, action ->
            view.addView(actionRow(action), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
                if (index > 0) topMargin = dp(2)
            })
        }
        val layout = WindowManager.LayoutParams(
            panelWidth(actions.map { it.label }),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END }
        panel = view
        params = layout
        windowManager.addView(view, layout)
        clusterX = dp(8)
        clusterY = dp(88)
        movePanel(clusterX, clusterY)
        view.post { if (!stopping && panel === view) movePanel(clusterX, clusterY) }
    }

    private fun panelWidth(labels: List<String>): Int {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 13f * resources.displayMetrics.density * resources.configuration.fontScale
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val widest = labels.maxOf { paint.measureText(it) }
        return (widest + dp(8 + 8 + 10 + 32 + 8 + 12)).toInt()
    }

    private fun actionRow(action: Action): View {
        val icon = ImageView(this).apply {
            setImageResource(action.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val label = TextView(this).apply {
            text = action.label
            setTextColor(INK)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            includeFontPadding = false
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(12), 0)
            contentDescription = action.label
            isClickable = true
            background = ripple()
            addView(icon, LinearLayout.LayoutParams(dp(32), dp(32)))
            addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            })
            setOnClickListener { action.onClick() }
            setOnTouchListener(dragListener())
        }
    }

    private fun ripple(): android.graphics.drawable.Drawable? {
        val attrs = intArrayOf(android.R.attr.selectableItemBackground)
        val typed = obtainStyledAttributes(attrs)
        val drawable = typed.getDrawable(0)
        typed.recycle()
        return drawable
    }

    /** Dragging any row moves the whole panel; a touch that stays inside the slop remains a click. */
    private fun dragListener() = View.OnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                dragStartX = clusterX
                dragStartY = clusterY
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && hypot(dx.toDouble(), dy.toDouble()) > ViewConfiguration.get(this).scaledTouchSlop) dragging = true
                if (dragging) movePanel(dragStartX - dx.toInt(), dragStartY + dy.toInt())
            }
            MotionEvent.ACTION_UP -> if (!dragging) view.performClick()
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        true
    }

    private fun movePanel(x: Int, y: Int) {
        val view = panel ?: return
        val layout = params ?: return
        if (!::windowManager.isInitialized) return
        val metrics = resources.displayMetrics
        val width = view.width.takeIf { it > 0 } ?: layout.width
        val height = view.height.takeIf { it > 0 } ?: dp(260)
        clusterX = x.coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
        clusterY = y.coerceIn(0, (metrics.heightPixels - height).coerceAtLeast(0))
        layout.x = clusterX
        layout.y = clusterY
        windowManager.updateViewLayout(view, layout)
    }

    private fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun closeApp() {
        startActivity(Intent(this, ExitActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        shutdown()
    }

    private fun startCapture(mode: Mode) {
        if (capturing || stopping) return
        if (mode != Mode.SCREEN && !scrollAvailable()) return
        capturing = true
        val gen = ++captureGen
        setPanelHidden(true)
        val (top, bottom) = systemBars()
        handler.postDelayed({
            if (!active(gen)) return@postDelayed
            when (mode) {
                Mode.SCREEN -> grabSingle(gen)
                Mode.LONG_ONE -> Thread({ longCapture(gen, stitch = true, top, bottom) }, "long-capture").start()
                Mode.LONG_MANY -> Thread({ longCapture(gen, stitch = false, top, bottom) }, "long-capture").start()
            }
        }, HIDE_MILLIS)
    }

    private fun scrollAvailable(): Boolean {
        if (!ScrollAccessibilityService.isEnabled(this)) {
            toast("長截圖需要開啟無障礙服務，才能自動捲動")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        if (ScrollAccessibilityService.instance == null) {
            toast("無障礙服務尚未連線，請再開啟一次")
            return false
        }
        return true
    }

    private fun grabSingle(gen: Int, attempt: Int = 0) {
        if (!active(gen)) return
        val image = reader?.acquireLatestImage()
        if (image == null) {
            if (attempt < MAX_ATTEMPTS) {
                handler.postDelayed({ grabSingle(gen, attempt + 1) }, RETRY_MILLIS)
                return
            }
            toast("畫面尚未就緒，請再試一次")
            finishCapture(gen)
            return
        }
        mute()
        try {
            val bitmap = ScreenshotImages.imageToBitmap(image)
            ScreenshotImages.save(contentResolver, bitmap, "Screenshot_${System.currentTimeMillis()}")
            bitmap.recycle()
            toast("已儲存至 Pictures/Screenshot Capture")
        } catch (failure: Exception) {
            toast("擷取失敗：${failure.message ?: "未知錯誤"}")
        } finally {
            image.close()
            unmute()
            finishCapture(gen)
        }
    }

    private fun longCapture(gen: Int, stitch: Boolean, topInset: Int, bottomInset: Int) {
        var previous: Bitmap? = null
        var stitcher: ScreenshotImages.Stitcher? = null
        var pages = 1
        var capped = false
        var blocked = false
        var knownFooter: Int? = null
        var manySaved = 0
        var previousSaved = false
        val manyStamp = System.currentTimeMillis()
        fun savePage(bitmap: Bitmap) {
            manySaved++
            mute()
            ScreenshotImages.save(contentResolver, bitmap, "LongShot_${manyStamp}_%02d".format(manySaved))
        }
        try {
            handler.post {
                notify(if (stitch) "長截圖（一張圖）捲動中" else "長截圖（多張圖）捲動中")
                toast("開始自動捲動，請先不要滑動畫面")
            }
            val first = awaitFrame(gen, null) ?: run {
                if (active(gen)) handler.post { toast("畫面尚未就緒，請再試一次") }
                return
            }
            previous = first
            while (active(gen) && pages < MAX_PAGES) {
                if (ScrollAccessibilityService.instance == null) {
                    blocked = true
                    break
                }
                if (!scrollOnce(gen)) break
                val next = awaitFrame(gen, previous) ?: break
                if (!active(gen)) {
                    next.recycle()
                    break
                }
                val join = ScreenshotImages.upwardShift(previous!!, next, topInset, bottomInset, knownFooter)
                if (join.shift < 24) {
                    next.recycle()
                    break
                }
                if (knownFooter == null) knownFooter = join.footer
                val current = previous
                if (stitch) {
                    val footer = knownFooter
                    val built = stitcher ?: ScreenshotImages.Stitcher(current.width, bottomInset, footer).also { stitcher = it }
                    if (!built.append(current, next, join.shift)) {
                        next.recycle()
                        capped = true
                        break
                    }
                    previous = next
                    if (current !== previous && !current.isRecycled) current.recycle()
                } else {
                    if (!previousSaved) {
                        savePage(current)
                        previousSaved = true
                    }
                    savePage(next)
                    previous = next
                    if (current !== previous && !current.isRecycled) current.recycle()
                }
                pages++
                val shown = pages
                handler.post { notify(if (stitch) "長截圖（一張圖）已接上 $shown 段" else "長截圖（多張圖）已擷取 $shown 張") }
            }
            if (!active(gen)) return
            mute()
            if (stitch) {
                val last = previous ?: return
                val output = stitcher?.finish(last) ?: last
                ScreenshotImages.save(contentResolver, output, "LongShot_${System.currentTimeMillis()}")
                if (output !== last) output.recycle()
                handler.post {
                    toast(when {
                        blocked -> "無法自動捲動，已儲存目前畫面"
                        capped -> "已到長度上限，長截圖已儲存"
                        pages == 1 -> "這個畫面沒有繼續捲動，已儲存目前畫面"
                        else -> "已儲存長截圖至 Pictures/Screenshot Capture"
                    })
                }
            } else if (previous != null) {
                if (!previousSaved) {
                    mute()
                    ScreenshotImages.save(contentResolver, previous, "Screenshot_$manyStamp")
                }
                handler.post {
                    toast(when {
                        blocked -> "無法自動捲動，已儲存目前畫面"
                        manySaved <= 1 -> "這個畫面沒有繼續捲動，已儲存目前畫面"
                        else -> "已儲存 $manySaved 張至 Pictures/Screenshot Capture"
                    })
                }
            }
        } catch (oom: OutOfMemoryError) {
            if (active(gen)) handler.post { toast("長截圖太長，記憶體不足") }
        } catch (failure: Exception) {
            if (active(gen)) handler.post { toast("擷取失敗：${failure.message ?: "未知錯誤"}") }
        } finally {
            unmute()
            previous?.let { if (!it.isRecycled) it.recycle() }
            stitcher?.discard()
            finishCapture(gen)
        }
    }

    private fun awaitFrame(gen: Int, previous: Bitmap?): Bitmap? {
        val attempts = if (previous == null) 8 else 7
        val pause = if (previous == null) 80L else 130L
        var last: Bitmap? = null
        repeat(attempts) {
            if (!active(gen)) {
                last?.recycle()
                return null
            }
            val image = reader?.acquireLatestImage()
            if (image != null) {
                val bitmap = ScreenshotImages.imageToBitmap(image)
                image.close()
                val prior = last
                last = bitmap
                if (previous == null || ScreenshotImages.regionDiff(previous, bitmap) > 12) {
                    prior?.recycle()
                    return bitmap
                }
                prior?.recycle()
            }
            SystemClock.sleep(pause)
        }
        return last
    }

    private fun scrollOnce(gen: Int): Boolean {
        val service = ScrollAccessibilityService.instance ?: return false
        val latch = CountDownLatch(1)
        var ok = false
        handler.post {
            if (!active(gen)) {
                latch.countDown()
                return@post
            }
            service.scrollDown { done ->
                ok = done
                latch.countDown()
            }
        }
        if (!latch.await(2, TimeUnit.SECONDS) || !ok || !active(gen)) return false
        SystemClock.sleep(320)
        return active(gen)
    }

    private fun finishCapture(gen: Int) {
        handler.post {
            if (gen != captureGen || stopping) return@post
            setPanelHidden(false)
            capturing = false
            notify("懸浮擷取按鈕已啟用")
        }
    }

    /** An invisible overlay still receives touches, so the auto-scroll gesture would hit this panel. */
    private fun setPanelHidden(hidden: Boolean) {
        val view = panel ?: return
        val layout = params ?: return
        view.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        layout.flags = if (hidden) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (::windowManager.isInitialized) windowManager.updateViewLayout(view, layout)
    }

    private fun systemBars(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30 && ::windowManager.isInitialized) {
            val bars = windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            return bars.top to bars.bottom
        }
        val statusId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val navId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val top = if (statusId > 0) resources.getDimensionPixelSize(statusId) else dp(24)
        val bottom = if (navId > 0) resources.getDimensionPixelSize(navId) else dp(48)
        return top to bottom
    }

    private fun active(gen: Int) = gen == captureGen && !stopping

    private fun mute() {
        synchronized(this) {
            if (originalVolume != null) return
            audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            originalVolume = audio?.getStreamVolume(AudioManager.STREAM_SYSTEM)
            audio?.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
        }
    }

    private fun unmute() {
        synchronized(this) {
            val volume = originalVolume ?: return
            originalVolume = null
            audio?.setStreamVolume(AudioManager.STREAM_SYSTEM, volume, 0)
        }
    }

    private fun toast(text: String) {
        handler.post { if (!stopping) Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }

    private fun notify(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, notification(text))
    }

    private fun shutdown() {
        if (stopping) return
        stopping = true
        captureGen++
        handler.removeCallbacksAndMessages(null)
        unmute()
        val view = panel
        if (view != null && ::windowManager.isInitialized) windowManager.removeView(view)
        panel = null
        params = null
        display?.release()
        reader?.close()
        projection?.stop()
        display = null
        reader = null
        projection = null
        capturing = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(text: String): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.capture_channel), NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private data class Action(val icon: Int, val label: String, val onClick: () -> Unit)

    companion object {
        private val INK = Color.rgb(46, 16, 101)
        private const val CHANNEL_ID = "floating_capture"
        private const val NOTIFICATION_ID = 102
        private const val HIDE_MILLIS = 320L
        private const val RETRY_MILLIS = 120L
        private const val MAX_ATTEMPTS = 4
        private const val MAX_PAGES = 15
        private const val ROTATE_MILLIS = 400L
        private const val EXTRA_RESULT = "result"
        private const val EXTRA_DATA = "data"
        private const val ACTION_STOP = "stop"

        fun startIntent(context: Context, result: Int, data: Intent) = Intent(context, FloatingCaptureService::class.java).apply {
            putExtra(EXTRA_RESULT, result)
            putExtra(EXTRA_DATA, data)
        }

        fun stopIntent(context: Context) = Intent(context, FloatingCaptureService::class.java).setAction(ACTION_STOP)
    }
}

private inline fun <reified T> Intent.parcelableIntent(key: String): T? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java) else @Suppress("DEPRECATION") getParcelableExtra(key)
