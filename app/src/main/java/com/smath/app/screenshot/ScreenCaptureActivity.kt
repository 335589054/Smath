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
 * 透明授权页：请求一次 MediaProjection 权限，然后把抓屏工作交给前台服务。
 * 抓屏完成后回到应用并直接打开裁剪页（平板使用较小的启动窗口）。
 */
class ScreenCaptureActivity : ComponentActivity() {

    private var receiver: BroadcastReceiver? = null
    private val handler = Handler(Looper.getMainLooper())
    private var finished = false

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
                Toast.makeText(this, "无法请求截屏权限", Toast.LENGTH_SHORT).show()
                finish()
            }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != Activity.RESULT_OK || data == null) {
            finish()
            return
        }
        registerResultReceiver()
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_CAPTURE
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }
        runCatching { ContextCompat.startForegroundService(this, intent) }
            .onFailure { finish() }
        handler.postDelayed({
            if (!finished) {
                Toast.makeText(this, "截屏超时，请重试", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, 9000)
    }

    private fun registerResultReceiver() {
        if (receiver != null) return
        val resultReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val path = intent?.getStringExtra(ScreenCaptureService.EXTRA_SCREENSHOT_PATH)
                if (!path.isNullOrBlank()) launchAppWithCrop(path)
            }
        }
        receiver = resultReceiver
        val filter = IntentFilter(ScreenCaptureService.ACTION_CAPTURED)
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
    }
}