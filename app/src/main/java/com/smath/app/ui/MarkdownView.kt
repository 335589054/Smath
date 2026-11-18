package com.smath.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.gson.Gson
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MarkdownView(
    markdown: String,
    dark: Boolean,
    modifier: Modifier = Modifier,
    fontSizeSp: Float? = null
) {
    val context = LocalContext.current
    var heightPx by remember { mutableIntStateOf(0) }
    var ready by remember { mutableStateOf(false) }
    val webViewRef = remember { arrayOfNulls<WebView>(1) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val gson = remember { Gson() }

    val bridge = remember {
        object {
            @JavascriptInterface
            fun onHeight(value: Int) {
                if (value > 0) {
                    mainHandler.post {
                        heightPx = value
                    }
                }
            }

            @JavascriptInterface
            fun onFormulaLock(locked: Boolean) {
                mainHandler.post {
                    webViewRef[0]?.parent?.requestDisallowInterceptTouchEvent(locked)
                }
            }
        }
    }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(max(20f, heightPx + 2f).dp),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = false
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
                settings.loadsImagesAutomatically = true
                settings.blockNetworkImage = true
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.textZoom = ((resources.displayMetrics.scaledDensity /
                    resources.displayMetrics.density) * 100).roundToInt()
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                isNestedScrollingEnabled = false
                setOnTouchListener { view, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }
                addJavascriptInterface(bridge, "SmathBridge")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        ready = true
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } catch (_: Throwable) {
                            }
                            return true
                        }
                        return !url.startsWith("file:///android_asset/")
                    }
                }
                loadUrl("file:///android_asset/markdown.html")
                webViewRef[0] = this
            }
        },
        update = { view ->
            webViewRef[0] = view
        }
    )

    LaunchedEffect(markdown, dark, ready, fontSizeSp) {
        if (!ready) return@LaunchedEffect
        val view = webViewRef[0] ?: return@LaunchedEffect
        delay(24)
        val md = gson.toJson(markdown)
        val sizeScript = fontSizeSp?.let {
            "var c=document.getElementById('content'); if(c){c.style.fontSize='${it}px';}"
        }.orEmpty()
        val script = "$sizeScript window.renderMarkdown($md, $dark);"
        view.evaluateJavascript(script, null)
    }

    DisposableEffect(Unit) {
        onDispose {
            val view = webViewRef[0]
            webViewRef[0] = null
            view?.let {
                it.removeJavascriptInterface("SmathBridge")
                it.stopLoading()
                it.loadUrl("about:blank")
                it.destroy()
            }
        }
    }
}