package com.example.screencapture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
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
import androidx.core.app.NotificationCompat
import java.nio.ByteBuffer

class CaptureService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var originalSystemVolume: Int? = null
    private var audio: AudioManager? = null
    private var isFinishingCapture = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT, 0) ?: return START_NOT_STICKY
        val resultData = intent.parcelableIntent<Intent>(EXTRA_DATA) ?: return START_NOT_STICKY
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        projection = (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
            .getMediaProjection(resultCode, resultData)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = finish()
        }, handler)
        handler.postDelayed({ captureOnce() }, CAPTURE_DELAY_MS)
        return START_NOT_STICKY
    }

    private fun captureOnce() {
        val metrics = resources.displayMetrics
        reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        display = projection?.createVirtualDisplay(
            "ScreenshotCapture", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler
        )
        reader?.setOnImageAvailableListener({ imageReader ->
            val image = imageReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            muteSystemSounds()
            try { save(image) } finally { image.close(); restoreSystemSounds(); finish() }
        }, handler)
    }

    private fun muteSystemSounds() {
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        originalSystemVolume = audio?.getStreamVolume(AudioManager.STREAM_SYSTEM)
        audio?.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
    }

    private fun restoreSystemSounds() {
        originalSystemVolume?.let { audio?.setStreamVolume(AudioManager.STREAM_SYSTEM, it, 0) }
        originalSystemVolume = null
    }

    private fun save(image: android.media.Image) {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val padded = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        val bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        val name = "Screenshot_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshot Capture")
        }
        val uri: Uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("無法建立圖片檔案")
        contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            ?: error("無法寫入圖片檔案")
        padded.recycle(); bitmap.recycle()
    }

    private fun finish() {
        if (isFinishingCapture) return
        isFinishingCapture = true
        restoreSystemSounds()
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.stop(); projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { finish(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification() : android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.capture_channel), NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.capture_notification))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 101
        private const val CAPTURE_DELAY_MS = 900L
        private const val EXTRA_RESULT = "result"
        private const val EXTRA_DATA = "data"
        fun intent(context: Context, result: Int, data: Intent) = Intent(context, CaptureService::class.java).apply {
            putExtra(EXTRA_RESULT, result); putExtra(EXTRA_DATA, data)
        }
    }
}

private inline fun <reified T> Intent.parcelableIntent(key: String): T? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java) else @Suppress("DEPRECATION") getParcelableExtra(key)
