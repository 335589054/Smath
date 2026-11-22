package com.smath.app.screenshot

import android.app.Activity
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.smath.app.MainActivity

/**
 * 透明授权页，两种用途：
 * 1. [EXTRA_ARM_ONLY] = true：设置里打开「截屏悬浮按钮」时立即申请一次录屏授权，
 *    拿到授权后马上结束，之后点悬浮球不再弹窗。
 * 2. 否则：悬浮球被点击但授权失效时，先补授权再截图，然后带图回到应用裁剪。
 */
class ScreenCaptureActivity : ComponentActivity() {

    private var receiver: BroadcastReceiver? = null
    private val handler = Handler(Looper.getMainLooper())
    private var finished = false
    private var armedByUs = false

    private val armedOnly: Boolean
        get() = intent?.getBooleanExtra(EXTRA_ARM_ONLY, false) == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finish()
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            Toast.makeText(this, "当前设备不支持截屏", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        runCatching { startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE) }
            .onFailure {
                Toast.makeText(this, "无法请求录屏授权", Toast.LENGTH_SHORT).show()
                finish()
            }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != Activity.RESULT_OK || data == null) {
            if (!armedOnly) Toast.makeText(this, "没有获得录屏授权", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (armedOnly) {
            ScreenCaptureService.arm(this, resultCode, data)
            Toast.makeText(this, "截屏已就绪，点悬浮按钮即可截图", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        registerReceivers()
        if (ScreenCaptureService.armed) {
            ScreenCaptureService.capture(this)
        } else {
            armedByUs = true
            ScreenCaptureService.arm(this, resultCode, data)
        }
        handler.postDelayed({
            if (!finished) {
                Toast.makeText(this, "截屏超时，请重试", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, 12000)
    }

    private fun registerReceivers() {
        if (receiver != null) return
        val resultReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ScreenCaptureService.ACTION_ARMED -> {
                        if (armedByUs && !finished) {
                            armedByUs = false
                            ScreenCaptureService.capture(this@ScreenCaptureActivity)
                        }
                    }
                    ScreenCaptureService.ACTION_CAPTURED -> {
                        val path = intent.getStringExtra(ScreenCaptureService.EXTRA_SCREENSHOT_PATH)
                        if (!path.isNullOrBlank()) launchAppWithCrop(path)
                    }
                    ScreenCaptureService.ACTION_CAPTURE_ERROR -> {
                        val message = intent.getStringExtra(ScreenCaptureService.EXTRA_ERROR)
                        if (!finished) {
                            Toast.makeText(
                                this@ScreenCaptureActivity,
                                message ?: "截图失败，请重试",
                                Toast.LENGTH_SHORT
                            ).show()
                            finish()
                        }
                    }
                }
            }
        }
        receiver = resultReceiver
        val filter = IntentFilter().apply {
            addAction(ScreenCaptureService.ACTION_ARMED)
            addAction(ScreenCaptureService.ACTION_CAPTURED)
            addAction(ScreenCaptureService.ACTION_CAPTURE_ERROR)
        }
        ContextCompat.registerReceiver(
            this,
            resultReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun launchAppWithCrop(path: String) {
        if (finished) return
        finished = true
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(MainActivity.EXTRA_CROP_PATH, path)
        }
        val options = ActivityOptions.makeBasic()
        if (resources.configuration.smallestScreenWidthDp >= 600) {
            val bounds = if (Build.VERSION.SDK_INT >= 30) {
                windowManager.maximumWindowMetrics.bounds
            } else {
                @Suppress("DEPRECATION")
                Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
            }
            val width = (bounds.width() * 0.64f).toInt().coerceAtLeast(420)
            val height = (bounds.height() * 0.84f).toInt().coerceAtLeast(560)
            val left = bounds.left + (bounds.width() - width) / 2
            val top = bounds.top + (bounds.height() - height) / 2
            runCatching {
                options.setLaunchBounds(Rect(left, top, left + width, top + height))
            }
        }
        runCatching { startActivity(intent, options.toBundle()) }
            .onFailure { startActivity(intent) }
        finish()
    }

    override fun onDestroy() {
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_CAPTURE = 7102
        const val EXTRA_ARM_ONLY = "arm_only"

        /** 设置页打开截屏开关时立刻请求一次录屏授权。 */
        fun requestArm(context: Context) {
            val intent = Intent(context, ScreenCaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                putExtra(EXTRA_ARM_ONLY, true)
            }
            runCatching { context.startActivity(intent) }
        }
    }
}