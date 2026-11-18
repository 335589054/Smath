package com.smath.app

import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smath.app.screenshot.ScreenshotOverlayService
import com.smath.app.ui.ChatScreen
import com.smath.app.ui.ChatViewModel
import com.smath.app.ui.ConversationDrawer
import com.smath.app.ui.CropScreen
import com.smath.app.ui.SettingsScreen
import com.smath.app.ui.theme.SmathTheme
import kotlinx.coroutines.launch

private data class CropRequest(
    val sourcePath: String,
    val onDone: (String) -> Unit
)

@Composable
fun SmathApp(
    viewModel: ChatViewModel = viewModel(),
    initialCropPath: String? = null,
    onInitialCropConsumed: () -> Unit = {}
) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    val activeId by viewModel.activeId.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val dark = when (data.settings.darkMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(data.settings.screenshotEnabled) {
        if (data.settings.screenshotEnabled && Settings.canDrawOverlays(context)) {
            ScreenshotOverlayService.start(context)
        } else if (!data.settings.screenshotEnabled) {
            ScreenshotOverlayService.stop(context)
        }
    }

    SmathTheme(darkTheme = dark) {
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        var showSettings by remember { mutableStateOf(false) }
        var cropRequest by remember { mutableStateOf<CropRequest?>(null) }
        var chatDraftImage by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(initialCropPath) {
            val path = initialCropPath
            if (!path.isNullOrBlank()) {
                onInitialCropConsumed()
                cropRequest = CropRequest(path) { cropped -> chatDraftImage = cropped }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                gesturesEnabled = activeId == null && !showSettings && cropRequest == null,
                drawerContent = {
                    ConversationDrawer(
                        viewModel = viewModel,
                        activeId = activeId,
                        onClose = { scope.launch { drawerState.close() } },
                        onOpenSettings = {
                            scope.launch { drawerState.close() }
                            showSettings = true
                        }
                    )
                }
            ) {
                ChatScreen(
                    viewModel = viewModel,
                    dark = dark,
                    onMenu = { scope.launch { drawerState.open() } },
                    onOpenSettings = {
                        scope.launch { drawerState.close() }
                        showSettings = true
                    },
                    onRequestCrop = { path, onDone ->
                        cropRequest = CropRequest(path, onDone)
                    },
                    externalDraftImage = chatDraftImage,
                    onExternalDraftImageConsumed = { chatDraftImage = null }
                )
            }

            if (showSettings) {
                SettingsScreen(
                    viewModel = viewModel,
                    dark = dark,
                    onClose = { showSettings = false }
                )
            }

            cropRequest?.let { request ->
                CropScreen(
                    sourcePath = request.sourcePath,
                    dark = dark,
                    onCancel = { cropRequest = null },
                    onDone = { croppedPath ->
                        request.onDone(croppedPath)
                        cropRequest = null
                    }
                )
            }
        }
    }
}