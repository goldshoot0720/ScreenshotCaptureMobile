package com.example.screencapture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
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
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer

class FloatingCaptureService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var bubble: ImageView? = null
    private var returnBubble: ImageView? = null
    private var closeBubble: ImageView? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var audio: AudioManager? = null
    private var originalVolume: Int? = null
    private var capturing = false
    private var stopping = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { shutdown(); return START_NOT_STICKY }
        val code = intent?.getIntExtra(EXTRA_RESULT, 0) ?: return START_NOT_STICKY
        val data = intent.parcelableIntent<Intent>(EXTRA_DATA) ?: return START_NOT_STICKY
        startForeground(NOTIFICATION_ID, notification())
        projection = (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() { override fun onStop() = shutdown() }, handler)
        val metrics = resources.displayMetrics
        reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 3)
        display = projection?.createVirtualDisplay("FloatingCapture", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
        showBubble()
        return START_STICKY
    }

    private fun showBubble() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        bubble = iconBubble(R.drawable.ic_capture, PINK, PURPLE, dp(19), "擷取整個螢幕") { capture() }
        returnBubble = iconBubble(R.drawable.ic_return, PURPLE, INDIGO, dp(15), "返回螢幕擷取 App") {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        closeBubble = iconBubble(R.drawable.ic_close, SLATE, SLATE_DARK, dp(16), "關閉懸浮擷取按鈕") { shutdown() }
        windowManager.addView(bubble, bubbleParams(dp(64), dp(176)))
        windowManager.addView(returnBubble, bubbleParams(dp(52), dp(250)))
        windowManager.addView(closeBubble, bubbleParams(dp(52), dp(312)))
    }

    private fun iconBubble(icon: Int, from: Int, to: Int, inset: Int, description: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageDrawable(ContextCompat.getDrawable(this@FloatingCaptureService, icon))
        setColorFilter(Color.WHITE); scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = description
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(from, to)).apply { shape = GradientDrawable.OVAL; setStroke(dp(2), Color.argb(235, 255, 255, 255)) }
        setPadding(inset, inset, inset, inset); elevation = dp(10).toFloat()
        setOnClickListener { onClick() }
    }

    private fun bubbleParams(size: Int, top: Int) = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        .apply { gravity = Gravity.TOP or Gravity.END; x = dp(18); y = top }

    private fun capture() {
        if (capturing) return
        capturing = true; bubble?.visibility = View.INVISIBLE; returnBubble?.visibility = View.INVISIBLE; closeBubble?.visibility = View.INVISIBLE
        handler.postDelayed({
            val image = reader?.acquireLatestImage()
            if (image == null) { restoreBubble(); return@postDelayed }
            mute()
            try { save(image) } finally { image.close(); unmute(); restoreBubble() }
        }, HIDE_MILLIS)
    }
    private fun restoreBubble() { bubble?.visibility = View.VISIBLE; returnBubble?.visibility = View.VISIBLE; closeBubble?.visibility = View.VISIBLE; capturing = false }
    private fun mute() { audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager; originalVolume = audio?.getStreamVolume(AudioManager.STREAM_SYSTEM); audio?.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0) }
    private fun unmute() { originalVolume?.let { audio?.setStreamVolume(AudioManager.STREAM_SYSTEM, it, 0) }; originalVolume = null }

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
        unmute(); bubble?.let { if (::windowManager.isInitialized) windowManager.removeView(it) }; returnBubble?.let { if (::windowManager.isInitialized) windowManager.removeView(it) }; closeBubble?.let { if (::windowManager.isInitialized) windowManager.removeView(it) }; bubble = null; returnBubble = null; closeBubble = null
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
        private const val EXTRA_RESULT = "result"; private const val EXTRA_DATA = "data"; private const val ACTION_STOP = "stop"
        fun startIntent(context: Context, result: Int, data: Intent) = Intent(context, FloatingCaptureService::class.java).apply { putExtra(EXTRA_RESULT, result); putExtra(EXTRA_DATA, data) }
        fun stopIntent(context: Context) = Intent(context, FloatingCaptureService::class.java).setAction(ACTION_STOP)
    }
}
private inline fun <reified T> Intent.parcelableIntent(key: String): T? = if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java) else @Suppress("DEPRECATION") getParcelableExtra(key)