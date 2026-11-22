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
import android.util.DisplayMetrics
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.smath.app.MainActivity
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 常驻的截屏前台服务。
 *
 * 打开「截屏悬浮按钮」时就用 [ACTION_ARM] 建立一次 MediaProjection 并保持，
 * 之后每次点悬浮球只发 [ACTION_CAPTURE] 抓一帧——这样授权弹窗只在设置里出现一次，
 * 也不会重复调用 createVirtualDisplay（Android 14 起同一个 MediaProjection 只允许建一次虚拟屏）。
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val pendingCapture = AtomicBoolean(false)

    /** 这次截图完成后要不要直接把 Smath 拉起来并跳到裁剪页（悬浮球直接截图走这条路）。 */
    private var pendingLaunchApp = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ARM -> arm(intent, startId)
            ACTION_CAPTURE -> {
                pendingLaunchApp = intent.getBooleanExtra(EXTRA_LAUNCH_APP, false)
                captureFrame()
            }
            ACTION_DISARM -> {
                releaseProjection()
                stopSelf()
            }
            else -> stopSelf()
        }
        return START_STICKY
    }

    private fun arm(intent: Intent, startId: Int) {
        if (projection != null) {
            announceArmed()
            return
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            broadcast(ACTION_CAPTURE_ERROR).putExtra(EXTRA_ERROR, "没有拿到录屏授权")
            stopSelf(startId)
            return
        }
        startInForeground()

        val manager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = runCatching { manager?.getMediaProjection(resultCode, resultData) }
            .getOrNull()
        if (mediaProjection == null) {
            broadcast(ACTION_CAPTURE_ERROR).putExtra(EXTRA_ERROR, "无法建立录屏会话")
            stopSelf(startId)
            return
        }
        projection = mediaProjection
        armed = true

        val thread = HandlerThread("smath-screen-capture").also { it.start() }
        this.thread = thread
        val handler = Handler(thread.looper).also { this.handler = it }

        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                releaseProjection()
                armed = false
                broadcast(ACTION_DISARMED)
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
            val image = available.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (!pendingCapture.compareAndSet(true, false)) {
                // 没人在等截图，丢掉这一帧即可。
                image.close()
                return@setOnImageAvailableListener
            }
            try {
                val path = saveImage(image)
                if (path != null) {
                    broadcast(ACTION_CAPTURED).putExtra(EXTRA_SCREENSHOT_PATH, path)
                    if (pendingLaunchApp) {
                        pendingLaunchApp = false
                        // 悬浮球直接截图时没有 Activity 在等这个结果，服务自己把应用叫起来。
                        launchAppWithCrop(path)
                    }
                } else {
                    pendingLaunchApp = false
                    broadcast(ACTION_CAPTURE_ERROR).putExtra(EXTRA_ERROR, "截图保存失败")
                }
            } finally {
                image.close()
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

        announceArmed()
        updateNotification(armedState = true)
    }

    private fun captureFrame() {
        if (projection == null) {
            armed = false
            toastMessage("截屏还没有就绪，请到设置里重新打开「截屏悬浮按钮」")
            broadcast(ACTION_CAPTURE_ERROR)
                .putExtra(EXTRA_ERROR, "截屏还没有就绪，请到设置里重新打开「截屏悬浮按钮」")
            return
        }
        pendingCapture.set(true)
        // 悬浮球刚被隐藏，屏幕会重绘并送来新帧；2.5 秒还没帧就放弃这一次。
        handler?.postDelayed({
            if (pendingCapture.compareAndSet(true, false)) {
                pendingLaunchApp = false
                toastMessage("截图超时，请再点一次")
                broadcast(ACTION_CAPTURE_ERROR).putExtra(EXTRA_ERROR, "截图超时，请再点一次")
            }
        }, 2500)
    }

    /** 后台服务里失败时给个明确反馈，避免点了悬浮球什么都没发生。 */
    private fun toastMessage(text: String) {
        runCatching {
            Handler(mainLooper).post {
                runCatching { android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show() }
            }
        }
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

    private fun broadcast(action: String): Intent {
        val intent = Intent(action).setPackage(packageName)
        sendBroadcast(intent)
        return intent
    }

    /** 截完图直接把 Smath 拉起来，并带上截图路径让主界面进入裁剪页。 */
    private fun launchAppWithCrop(path: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(MainActivity.EXTRA_CROP_PATH, path)
        }
        runCatching { startActivity(intent) }
            .onFailure { broadcast(ACTION_CAPTURE_ERROR).putExtra(EXTRA_ERROR, "截图已保存，但打不开主界面") }
    }

    private fun announceArmed() {
        sendBroadcast(Intent(ACTION_ARMED).setPackage(packageName))
    }

    private fun startInForeground() {
        val notification = buildNotification(armedState = false)
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

    private fun updateNotification(armedState: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        runCatching { manager?.notify(NOTIFICATION_ID, buildNotification(armedState)) }
    }

    private fun releaseProjection() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { projection?.stop() }
        projection = null
        thread?.quitSafely()
        thread = null
        handler = null
        pendingCapture.set(false)
        armed = false
    }

    override fun onDestroy() {
        releaseProjection()
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

    private fun buildNotification(armedState: Boolean): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(if (armedState) "截屏已就绪" else "正在准备截屏")
            .setContentText(
                if (armedState) "点一下蓝色悬浮按钮即可截图"
                else "已获得录屏授权，可以随时截图"
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_ARM = "com.smath.app.screenshot.ARM"
        const val ACTION_CAPTURE = "com.smath.app.screenshot.CAPTURE"
        const val ACTION_DISARM = "com.smath.app.screenshot.DISARM"
        const val ACTION_ARMED = "com.smath.app.screenshot.ARMED"
        const val ACTION_DISARMED = "com.smath.app.screenshot.DISARMED"
        const val ACTION_CAPTURED = "com.smath.app.screenshot.CAPTURED"
        const val ACTION_CAPTURE_ERROR = "com.smath.app.screenshot.ERROR"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SCREENSHOT_PATH = "screenshot_path"
        const val EXTRA_ERROR = "error"
        const val EXTRA_LAUNCH_APP = "launch_app"
        private const val CHANNEL_ID = "smath_capture"
        private const val NOTIFICATION_ID = 4102

        /** 录屏授权是否仍然有效；进程重启后自然回到 false。 */
        @Volatile
        var armed: Boolean = false
            private set

        fun arm(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_ARM
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun capture(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_CAPTURE
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /** 悬浮球用这个：抓完一帧后服务自己把应用叫起来进裁剪页。 */
        fun captureAndOpen(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_CAPTURE
                putExtra(EXTRA_LAUNCH_APP, true)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun disarm(context: Context) {
            armed = false
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_DISARM
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) = disarm(context)
    }
}