package com.example.screencapture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import kotlin.math.hypot

class FloatingCaptureService : Service() {
    private class Bubble(val view: ImageView, val params: WindowManager.LayoutParams, val offsetX: Int, val offsetY: Int)

    private val handler = Handler(Looper.getMainLooper())
    private val bubbles = mutableListOf<Bubble>()
    private lateinit var windowManager: WindowManager
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var audio: AudioManager? = null
    private var originalVolume: Int? = null
    private var capturing = false
    private var stopping = false
    private var clusterX = 0
    private var clusterY = 0
    private var dragStartX = 0
    private var dragStartY = 0
    private var downRawX = 0f
    private var downRawY = 0f
    private var dragging = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { shutdown(); return START_NOT_STICKY }
        val code = intent?.getIntExtra(EXTRA_RESULT, 0) ?: return START_NOT_STICKY
        val data = intent.parcelableIntent<Intent>(EXTRA_DATA) ?: return START_NOT_STICKY
        startForeground(NOTIFICATION_ID, notification())
        projection = (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() { override fun onStop() = shutdown() }, handler)
        openPipeline()
        showBubbles()
        return START_STICKY
    }

    /** Mirrors the display at its current size; a no-op when that size has not changed. */
    private fun openPipeline() {
        val metrics = resources.displayMetrics
        if (reader?.width == metrics.widthPixels && reader?.height == metrics.heightPixels) return
        display?.release(); reader?.close()
        reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 3)
        display = projection?.createVirtualDisplay("FloatingCapture", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (stopping) return
        handler.postDelayed({ if (!stopping) { openPipeline(); moveCluster(clusterX, clusterY) } }, ROTATE_MILLIS)
    }

    private fun showBubbles() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        clusterX = dp(18); clusterY = dp(176)
        addBubble(R.drawable.ic_capture, PINK, PURPLE, dp(19), dp(64), 0, 0, "擷取整個螢幕") { capture() }
        addBubble(R.drawable.ic_return, PURPLE, INDIGO, dp(15), dp(52), dp(6), dp(74), "返回螢幕擷取 App") {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        addBubble(R.drawable.ic_close, SLATE, SLATE_DARK, dp(16), dp(52), dp(6), dp(136), "關閉懸浮擷取按鈕") { shutdown() }
    }

    private fun addBubble(icon: Int, from: Int, to: Int, inset: Int, size: Int, offsetX: Int, offsetY: Int, description: String, onClick: () -> Unit) {
        val view = ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@FloatingCaptureService, icon))
            setColorFilter(Color.WHITE); scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = description
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(from, to)).apply { shape = GradientDrawable.OVAL; setStroke(dp(2), Color.argb(235, 255, 255, 255)) }
            setPadding(inset, inset, inset, inset); elevation = dp(10).toFloat()
            setOnClickListener { onClick() }
            setOnTouchListener(dragListener())
        }
        val params = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.END; x = clusterX + offsetX; y = clusterY + offsetY }
        windowManager.addView(view, params)
        bubbles += Bubble(view, params, offsetX, offsetY)
    }

    /** Dragging any bubble moves the whole cluster; a touch that never passes the slop stays a click. */
    private fun dragListener() = View.OnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX; downRawY = event.rawY
                dragStartX = clusterX; dragStartY = clusterY; dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && hypot(dx, dy) > ViewConfiguration.get(this).scaledTouchSlop) dragging = true
                if (dragging) moveCluster(dragStartX - dx.toInt(), dragStartY + dy.toInt())
            }
            MotionEvent.ACTION_UP -> if (!dragging) view.performClick()
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        true
    }

    private fun moveCluster(x: Int, y: Int) {
        if (!::windowManager.isInitialized || bubbles.isEmpty()) return
        val metrics = resources.displayMetrics
        clusterX = x.coerceIn(0, (metrics.widthPixels - dp(CLUSTER_WIDTH_DP)).coerceAtLeast(0))
        clusterY = y.coerceIn(0, (metrics.heightPixels - dp(CLUSTER_HEIGHT_DP)).coerceAtLeast(0))
        bubbles.forEach {
            it.params.x = clusterX + it.offsetX
            it.params.y = clusterY + it.offsetY
            windowManager.updateViewLayout(it.view, it.params)
        }
    }

    private fun capture() {
        if (capturing) return
        capturing = true
        setBubblesVisible(false)
        handler.postDelayed({ grab(0) }, HIDE_MILLIS)
    }

    /** The mirror only emits a frame when the screen changes, so a still screen can need another pass. */
    private fun grab(attempt: Int) {
        if (stopping) return
        val image = reader?.acquireLatestImage()
        if (image == null) {
            if (attempt < MAX_ATTEMPTS) { handler.postDelayed({ grab(attempt + 1) }, RETRY_MILLIS); return }
            toast("畫面尚未就緒，請再試一次")
            finishCapture()
            return
        }
        mute()
        try {
            save(image)
            toast("已儲存至 Pictures/Screenshot Capture")
        } catch (failure: Exception) {
            toast("擷取失敗：${failure.message ?: "未知錯誤"}")
        } finally {
            image.close(); unmute(); finishCapture()
        }
    }

    private fun finishCapture() { setBubblesVisible(true); capturing = false }
    private fun setBubblesVisible(visible: Boolean) {
        val state = if (visible) View.VISIBLE else View.INVISIBLE
        bubbles.forEach { it.view.visibility = state }
    }

    private fun mute() { audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager; originalVolume = audio?.getStreamVolume(AudioManager.STREAM_SYSTEM); audio?.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0) }
    private fun unmute() { originalVolume?.let { audio?.setStreamVolume(AudioManager.STREAM_SYSTEM, it, 0) }; originalVolume = null }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun save(image: android.media.Image) {
        val plane = image.planes[0]; val padding = plane.rowStride - plane.pixelStride * image.width
        val padded = Bitmap.createBitmap(image.width + padding / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer as ByteBuffer)
        val bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        val values = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "Screenshot_${System.currentTimeMillis()}.png"); put(MediaStore.Images.Media.MIME_TYPE, "image/png"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshot Capture") }
        val uri: Uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("無法建立圖片檔案")
        contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: error("無法寫入圖片檔案")
        padded.recycle(); bitmap.recycle()
    }

    private fun shutdown() {
        if (stopping) return
        stopping = true
        handler.removeCallbacksAndMessages(null)
        unmute()
        if (::windowManager.isInitialized) bubbles.forEach { windowManager.removeView(it.view) }
        bubbles.clear()
        display?.release(); reader?.close(); projection?.stop(); display = null; reader = null; projection = null
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { shutdown(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun notification(): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.capture_channel), NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_capture).setContentTitle(getString(R.string.app_name)).setContentText("懸浮擷取按鈕已啟用").setOngoing(true).build()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        private val PINK = Color.rgb(236, 72, 153)
        private val PURPLE = Color.rgb(124, 58, 237)
        private val INDIGO = Color.rgb(79, 70, 229)
        private val SLATE = Color.rgb(100, 116, 139)
        private val SLATE_DARK = Color.rgb(51, 65, 85)
        private const val CHANNEL_ID = "floating_capture"; private const val NOTIFICATION_ID = 102; private const val HIDE_MILLIS = 250L
        private const val RETRY_MILLIS = 120L; private const val MAX_ATTEMPTS = 4; private const val ROTATE_MILLIS = 400L
        private const val CLUSTER_WIDTH_DP = 64; private const val CLUSTER_HEIGHT_DP = 188
        private const val EXTRA_RESULT = "result"; private const val EXTRA_DATA = "data"; private const val ACTION_STOP = "stop"
        fun startIntent(context: Context, result: Int, data: Intent) = Intent(context, FloatingCaptureService::class.java).apply { putExtra(EXTRA_RESULT, result); putExtra(EXTRA_DATA, data) }
        fun stopIntent(context: Context) = Intent(context, FloatingCaptureService::class.java).setAction(ACTION_STOP)
    }
}
private inline fun <reified T> Intent.parcelableIntent(key: String): T? = if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java) else @Suppress("DEPRECATION") getParcelableExtra(key)
