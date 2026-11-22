package com.smath.app.screenshot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.smath.app.MainActivity
import com.smath.app.R
import kotlin.math.abs

/**
 * 平板上可拖动的截屏悬浮按钮。点击后交给 ScreenCaptureActivity 申请一次
 * MediaProjection 授权并抓取一帧，随后带着截图回到应用进入裁剪页。
 */
class ScreenshotOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubble: View? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        showBubble()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        bubble?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        bubble = null
        super.onDestroy()
    }

    private fun showBubble() {
        if (bubble != null) return
        val size = (52 * resources.displayMetrics.density).toInt()
        val screen = screenSize()
        val layoutParams = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screen.first - size - (18 * resources.displayMetrics.density).toInt())
                .coerceAtLeast(0)
            y = (screen.second / 3).coerceAtLeast(size)
        }
        params = layoutParams

        val container = FrameLayout(this).apply {
            elevation = 8f * resources.displayMetrics.density
            contentDescription = "截屏"
        }
        container.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#3448C7"))
            setStroke((2 * resources.displayMetrics.density).toInt(), Color.WHITE)
        }
        val label = TextView(this).apply {
            text = "截"
            setTextColor(Color.WHITE)
            textSize = 19f
            gravity = Gravity.CENTER
        }
        container.addView(
            label,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        container.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = layoutParams.x
                    startY = layoutParams.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > 12f || abs(dy) > 12f) dragging = true
                    val maxX = (screen.first - size).coerceAtLeast(0)
                    val maxY = (screen.second - size).coerceAtLeast(0)
                    layoutParams.x = (startX + dx.toInt()).coerceIn(0, maxX)
                    layoutParams.y = (startY + dy.toInt()).coerceIn(0, maxY)
                    runCatching { windowManager.updateViewLayout(view, layoutParams) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        view.performClick()
                        openCapture()
                    }
                    true
                }
                else -> false
            }
        }

        runCatching { windowManager.addView(container, layoutParams) }
            .onSuccess { bubble = container }
            .onFailure { stopSelf() }
    }

    private fun openCapture() {
        // 先把气泡藏起来，避免截进图里；同时这一步会触发屏幕重绘，
        // 常驻的 MediaProjection 才会送来新的一帧。
        hideBubbleTemporarily()
        if (ScreenCaptureService.armed) {
            ScreenCaptureService.capture(this)
        } else {
            val intent = Intent(this, ScreenCaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
            runCatching { startActivity(intent) }
        }
    }

    private fun hideBubbleTemporarily() {
        val view = bubble ?: return
        runCatching { windowManager.removeView(view) }
        bubble = null
        handler.postDelayed({ showBubble() }, 1500)
    }

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val metrics = resources.displayMetrics
            metrics.widthPixels to metrics.heightPixels
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "截屏悬浮按钮",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "在平板上显示可拖动的截屏按钮"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("截屏按钮已开启")
            .setContentText("拖动蓝色按钮调整位置，单击截取屏幕")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "smath_screenshot"
        private const val NOTIFICATION_ID = 4101
        const val ACTION_START = "com.smath.app.screenshot.START"
        const val ACTION_STOP = "com.smath.app.screenshot.STOP"

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, ScreenshotOverlayService::class.java)
                .setAction(ACTION_START)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ScreenshotOverlayService::class.java)) }
        }
    }
}