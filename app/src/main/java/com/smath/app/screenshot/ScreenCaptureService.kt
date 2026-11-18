package com.smath.app.screenshot

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 14 起 MediaProjection 必须在前台服务里使用。只抓取一帧，保存到
 * cache/screenshots，然后通过应用内广播把路径交回 ScreenCaptureActivity。
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val captured = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (intent?.action != ACTION_CAPTURE || resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startInForeground()
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = runCatching {
            manager?.getMediaProjection(resultCode, resultData)
        }.getOrNull()
        if (mediaProjection == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        projection = mediaProjection

        val thread = HandlerThread("smath-screen-capture").also { it.start() }
        this.thread = thread
        val handler = Handler(thread.looper).also { this.handler = it }

        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                cleanup()
                stopSelf()
            }
        }, handler)

        val metrics = screenMetrics()
        val reader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            2
        )
        imageReader = reader
        reader.setOnImageAvailableListener({ available ->
            if (!captured.compareAndSet(false, true)) {
                available.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            val image = available.acquireLatestImage()
            if (image == null) {
                captured.set(false)
                return@setOnImageAvailableListener
            }
            try {
                val path = saveImage(image)
                if (path != null) {
                    sendBroadcast(
                        Intent(ACTION_CAPTURED)
                            .setPackage(packageName)
                            .putExtra(EXTRA_SCREENSHOT_PATH, path)
                    )
                }
            } finally {
                image.close()
                cleanup()
                stopSelf()
            }
        }, handler)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "SmathScreenshot",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )

        handler.postDelayed({
            if (!captured.get()) {
                cleanup()
                stopSelf()
            }
        }, 7000)
        return START_NOT_STICKY
    }

    private fun saveImage(image: android.media.Image): String? {
        return try {
            val plane = image.planes[0]
            plane.buffer.rewind()
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val paddedWidth = image.width + rowPadding / pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            if (cropped !== padded) padded.recycle()

            val dir = File(cacheDir, "screenshots")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "shot_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { output ->
                cropped.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            cropped.recycle()
            file.absolutePath
        } catch (_: Throwable) {
            null
        }
    }

    private fun screenMetrics(): DisplayMetrics {
        return if (Build.VERSION.SDK_INT >= 30) {
            val bounds = (getSystemService(WINDOW_SERVICE) as android.view.WindowManager)
                .maximumWindowMetrics.bounds
            DisplayMetrics().apply {
                widthPixels = bounds.width()
                heightPixels = bounds.height()
                densityDpi = resources.displayMetrics.densityDpi
                density = resources.displayMetrics.density
            }
        } else {
            @Suppress("DEPRECATION")
            resources.displayMetrics
        }
    }

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun cleanup() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { projection?.stop() }
        projection = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "屏幕截图",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("正在截取屏幕")
            .setContentText("截屏完成后会自动进入裁剪页")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_CAPTURE = "com.smath.app.screenshot.CAPTURE"
        const val ACTION_CAPTURED = "com.smath.app.screenshot.CAPTURED"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SCREENSHOT_PATH = "screenshot_path"
        private const val CHANNEL_ID = "smath_capture"
        private const val NOTIFICATION_ID = 4102

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ScreenCaptureService::class.java)) }
        }
    }
}