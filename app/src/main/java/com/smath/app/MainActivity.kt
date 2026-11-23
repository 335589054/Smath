package com.smath.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private var incomingCropPath by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SmathAppContext.attach(this)
        enableEdgeToEdge()
        incomingCropPath = intent?.getStringExtra(EXTRA_CROP_PATH)
        setContent {
            SmathApp(
                initialCropPath = incomingCropPath,
                onInitialCropConsumed = { incomingCropPath = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val path = intent.getStringExtra(EXTRA_CROP_PATH)
        if (!path.isNullOrBlank()) incomingCropPath = path
    }

    companion object {
        const val EXTRA_CROP_PATH = "com.smath.app.extra.CROP_PATH"
    }
}