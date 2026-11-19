package com.smath.app.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.smath.app.data.AgentPreset
import com.smath.app.data.ApiErrorInfo
import com.smath.app.data.ChatMessage
import com.smath.app.data.Citation
import com.smath.app.data.ContextReference
import com.smath.app.data.Conversation
import com.smath.app.data.Stage
import com.smath.app.data.providerKindColor
import com.smath.app.export.ConversationExport
import com.smath.app.util.ImageUtils
import com.smath.app.util.TimeUtils
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val STREAMING_THINKING_KEY = "streaming"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    dark: Boolean,
    onMenu: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestCrop: (sourcePath: String, onDone: (String) -> Unit) -> Unit,
    externalDraftImage: String? = null,
    onExternalDraftImageConsumed: () -> Unit = {}
) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    val active by viewModel.activeConversation.collectAsStateWithLifecycle()
    val streamingAll by viewModel.streaming.collectAsStateWithLifecycle()
    val selectedPresetId by viewModel.selectedPresetId.collectAsStateWithLifecycle()
    val streaming = streamingAll?.takeIf { it.conversationId == active?.id }
    val settings = data.settings

    var input by remember(active?.id) { mutableStateOf("") }
    var draftImages by remember(active?.id) { mutableStateOf(emptyList<String>()) }
    var draftContexts by remember(active?.id) { mutableStateOf(emptyList<ContextReference>()) }
    var showModelSheet by remember { mutableStateOf(false) }
    var presetSheetTarget by remember { mutableStateOf<AgentPreset?>(null) }
    var presetSheetIsNew by remember { mutableStateOf(false) }
    var showAttachSheet by remember { mutableStateOf(false) }
    var showContextSheet by remember { mutableStateOf(false) }
    var previewImage by remember { mutableStateOf<String?>(null) }
    var showJumpSheet by remember { mutableStateOf(false) }
    // 每条思考单独展开；底部按钮反映“是否有任一思考展开”，
    // 点击底部按钮则统一展开/收起全部。
    val thinkingStates = remember(active?.id) { mutableStateMapOf<String, Boolean>() }
    // 拍照期间 Activity 可能被系统回收；rememberSaveable 保证返回后还能找回输出文件。
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraStartedAt by rememberSaveable { mutableLongStateOf(0L) }
    var exporting by remember { mutableStateOf(false) }
    var pendingExportKind by remember { mutableStateOf<String?>(null) }
    var exportTarget by remember { mutableStateOf<Conversation?>(null) }

    LaunchedEffect(externalDraftImage) {
        val path = externalDraftImage
        if (!path.isNullOrBlank()) {
            draftImages = draftImages + path
            onExternalDraftImageConsumed()
        }
    }

    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var autoScroll by remember { mutableStateOf(true) }
    val canScrollForward by remember { derivedStateOf { listState.canScrollForward } }

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val stored = cameraPath
        val kept = stored?.takeIf { File(it).let { file -> file.exists() && file.length() > 0 } }
        // 某些相机会返回 resultCode=CANCEL 但照片其实已经写好；只要文件
        // 存在就继续裁剪。若进程重建导致 path 丢失，则按拍摄开始时间找回。
        val fallback = if (kept == null) {
            val since = if (cameraStartedAt > 0L) {
                cameraStartedAt - 2000L
            } else {
                System.currentTimeMillis() - 10 * 60 * 1000L
            }
            ImageUtils.latestCameraFile(context, since)?.absolutePath
        } else null
        val path = kept ?: fallback
        cameraPath = null
        cameraStartedAt = 0L
        if (path != null) {
            onRequestCrop(path) { cropped -> draftImages = draftImages + cropped }
        } else if (success) {
            Toast.makeText(context, "拍照完成但找不到照片文件", Toast.LENGTH_SHORT).show()
        }
    }
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val path = ImageUtils.copyToCache(context, uri)
            if (path == null) {
                Toast.makeText(context, "无法读取这张图片", Toast.LENGTH_SHORT).show()
            } else {
                onRequestCrop(path) { cropped -> draftImages = draftImages + cropped }
            }
        }
    }

    fun runExport(uri: Uri?, kind: String) {
        val conversation = exportTarget
        val requested = pendingExportKind
        exportTarget = null
        pendingExportKind = null
        if (uri == null || requested == null) return
        scope.launch {
            exporting = true
            try {
                when (kind) {
                    "markdown" -> {
                        val target = conversation ?: return@launch
                        ConversationExport.writeMarkdown(context, uri, target)
                        Toast.makeText(context, "已导出 Markdown", Toast.LENGTH_SHORT).show()
                    }
                    "zip" -> {
                        val count = ConversationExport.writeAllZip(
                            context,
                            uri,
                            viewModel.data.value
                        )
                        Toast.makeText(context, "已导出 $count 个对话", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (t: Throwable) {
                Toast.makeText(
                    context,
                    "导出失败：${t.message ?: "未知错误"}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                exporting = false
            }
        }
    }

    val createMarkdownExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri -> runExport(uri, "markdown") }
    val createZipExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> runExport(uri, "zip") }

    val launchCamera: () -> Unit = {
        try {
            val file = ImageUtils.cameraFile(context)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            cameraPath = file.absolutePath
            cameraStartedAt = System.currentTimeMillis()
            takePicture.launch(uri)
        } catch (t: Throwable) {
            Toast.makeText(context, "无法打开相机：${t.message}", Toast.LENGTH_SHORT).show()
        }
    }
    val launchPicker: () -> Unit = {
        try {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (t: Throwable) {
            Toast.makeText(context, "无法打开相册：${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(active?.id) {
        autoScroll = true
        delay(80)
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) {
            listState.scrollToItem(if (active == null) 0 else total - 1)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlowCompat(listState).collect { scrolling ->
            if (!scrolling) {
                autoScroll = !listState.canScrollForward
            }
        }
    }
    LaunchedEffect(active?.messages?.size, streaming?.content?.length, streaming?.reasoning?.length) {
        if (autoScroll) {
            val total = listState.layoutInfo.totalItemsCount
            if (total > 0) listState.scrollToItem(total - 1)
        }
    }

    val displayPresetId = active?.presetId?.takeIf { it.isNotBlank() } ?: selectedPresetId
    val activePreset = settings.presets.firstOrNull { it.id == displayPresetId }
        ?: settings.presets.firstOrNull()
    val exportableConversation = active?.takeIf { it.messages.isNotEmpty() && streaming == null }

    val chatMessages = active?.messages.orEmpty()
    val reasoningIds = chatMessages.filter { it.reasoning.isNotBlank() }.map { it.id }
    val streamingHasReasoning = !streaming?.reasoning.isNullOrBlank()
    val hasReasoning = reasoningIds.isNotEmpty() || streamingHasReasoning
    val anyThinkingExpanded = reasoningIds.any { thinkingStates[it] == true } ||
        (streamingHasReasoning && thinkingStates[STREAMING_THINKING_KEY] == true)
    val lastAssistantIndex = chatMessages.indexOfLast { it.isAssistant }
    val lastAnswerIndex = when {
        streaming != null -> chatMessages.size
        lastAssistantIndex >= 0 -> lastAssistantIndex
        else -> -1
    }
    val canJump = chatMessages.isNotEmpty()

    fun jumpToMessage(index: Int) {
        if (index < 0) return
        autoScroll = false
        scope.launch {
            // 没有消息时会先显示 welcome item，需要多偏移一格。
            val welcomeOffset = if (chatMessages.isEmpty()) 1 else 0
            listState.animateScrollToItem(index + welcomeOffset)
        }
    }

    fun toggleAllThinking() {
        if (anyThinkingExpanded) {
            thinkingStates.keys.toList().forEach { thinkingStates[it] = false }
        } else {
            reasoningIds.forEach { thinkingStates[it] = true }
            if (streamingHasReasoning) thinkingStates[STREAMING_THINKING_KEY] = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ChatTopBar(
            providerName = settings.providers.firstOrNull { it.id == settings.activeProviderId }?.name
                ?: "选择模型",
            modelName = settings.activeModel,
            providerColor = providerKindColor(
                settings.providers.firstOrNull { it.id == settings.activeProviderId }?.kind ?: "custom"
            ),
            onMenu = onMenu,
            onNewChat = {
                viewModel.newChat()
                autoScroll = true
            },
            onPickModel = { showModelSheet = true },
            onExportMarkdown = exportableConversation?.let { conversation ->
                {
                    pendingExportKind = "markdown"
                    exportTarget = conversation
                    createMarkdownExport.launch(
                        ConversationExport.suggestedMarkdownName(conversation)
                    )
                }
            },
            onExportAll = if (data.conversations.isNotEmpty()) {
                {
                    pendingExportKind = "zip"
                    exportTarget = null
                    createZipExport.launch(ConversationExport.suggestedZipName())
                }
            } else null
        )

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 10.dp,
                    bottom = 18.dp
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (chatMessages.isEmpty()) {
                    item(key = "welcome") {
                        WelcomePane(
                            presets = settings.presets,
                            selectedPreset = activePreset,
                            onSelectPreset = viewModel::selectPreset,
                            onTunePreset = {
                                presetSheetIsNew = false
                                presetSheetTarget = activePreset
                            },
                            onAddPreset = {
                                presetSheetIsNew = true
                                presetSheetTarget = AgentPreset(
                                    id = "",
                                    name = "新预设",
                                    builtIn = false,
                                    icon = "custom"
                                )
                            }
                        )
                    }
                }
                items(chatMessages, key = { it.id }) { message ->
                    if (message.isUser) {
                        UserMessage(message, onImageClick = { previewImage = it })
                    } else {
                        val isLast = chatMessages.lastOrNull()?.id == message.id
                        AssistantMessage(
                            message = message,
                            dark = dark,
                            thinkingExpanded = thinkingStates[message.id] == true,
                            onToggleThinking = {
                                thinkingStates[message.id] =
                                    !(thinkingStates[message.id] ?: false)
                            },
                            drawingAgentEnabled = settings.drawingAgentEnabled,
                            calculatorAgentEnabled = settings.calculatorAgentEnabled,
                            drawingCanvasSize = settings.drawingCanvasSize,
                            canRetry = isLast && message.error != null,
                            onRetry = viewModel::retryLast,
                            onCopy = {
                                copyText(context, message.content)
                            }
                        )
                    }
                }
                if (streaming != null) {
                    item(key = "streaming") {
                        StreamingBubble(
                            streaming = streaming,
                            dark = dark,
                            thinkingExpanded = thinkingStates[STREAMING_THINKING_KEY] == true,
                            onToggleThinking = {
                                thinkingStates[STREAMING_THINKING_KEY] =
                                    !(thinkingStates[STREAMING_THINKING_KEY] ?: false)
                            },
                            drawingAgentEnabled = settings.drawingAgentEnabled,
                            calculatorAgentEnabled = settings.calculatorAgentEnabled,
                            drawingCanvasSize = settings.drawingCanvasSize
                        )
                    }
                }
                item(key = "disclaimer") {
                    Text(
                        text = "内容由 AI 生成，请仔细甄别",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            }

        Composer(
            input = input,
            onInputChange = { input = it },
            images = draftImages,
            onRemoveImage = { draftImages = draftImages - it },
            onAddImage = { showAttachSheet = true },
            contexts = draftContexts,
            onRemoveContext = { ref -> draftContexts = draftContexts.filterNot { it.id == ref.id } },
            onAddContext = { showContextSheet = true },
            webSearch = settings.webSearchByDefault,
            onToggleSearch = { viewModel.setWebSearch(it) },
            hasReasoning = hasReasoning,
            thinkingExpanded = anyThinkingExpanded,
            onToggleThinking = { toggleAllThinking() },
            canViewAnswer = lastAnswerIndex >= 0,
            onViewAnswer = { jumpToMessage(lastAnswerIndex) },
            canJump = canJump,
            onOpenJump = { showJumpSheet = true },
            canJumpBottom = canScrollForward,
            onJumpBottom = {
                autoScroll = true
                scope.launch {
                    val total = listState.layoutInfo.totalItemsCount
                    if (total > 0) listState.animateScrollToItem(total - 1)
                }
            },
            generating = streaming != null,
            onSend = {
                val text = input
                val images = draftImages
                val contexts = draftContexts
                if (text.isNotBlank() || images.isNotEmpty()) {
                    input = ""
                    draftImages = emptyList()
                    draftContexts = emptyList()
                    keyboard?.hide()
                    viewModel.send(text, images, contexts)
                    autoScroll = true
                }
            },
            onStop = viewModel::stopGenerating
        )
    }

    if (showJumpSheet) {
        ConversationJumpSheet(
            messages = chatMessages,
            streaming = streaming,
            onDismiss = { showJumpSheet = false },
            onJump = { index ->
                showJumpSheet = false
                jumpToMessage(index)
            }
        )
    }

    if (showModelSheet) {
        ModelPickerSheet(
            settings = settings,
            onDismiss = { showModelSheet = false },
            onSelect = { providerId, model ->
                viewModel.setActiveProvider(providerId)
                viewModel.setActiveModel(model)
            },
            onThinkingLevel = viewModel::setThinkingLevel,
            onManage = onOpenSettings
        )
    }

    presetSheetTarget?.let { target ->
        PresetConfigSheet(
            preset = target,
            locked = !presetSheetIsNew && active != null,
            isNew = presetSheetIsNew,
            onDismiss = {
                presetSheetTarget = null
                presetSheetIsNew = false
            },
            onSave = {
                if (presetSheetIsNew) viewModel.addPreset(it) else viewModel.updatePreset(it)
            },
            onReset = {
                if (!presetSheetIsNew) viewModel.resetPresetToDefault(target.id)
            },
            onDelete = if (!presetSheetIsNew && !target.builtIn) {
                { viewModel.deletePreset(target.id) }
            } else {
                null
            }
        )
    }

    if (showContextSheet) {
        ContextPickerSheet(
            active = active,
            conversations = data.conversations,
            selected = draftContexts,
            onDismiss = { showContextSheet = false },
            onToggle = { ref ->
                draftContexts = if (draftContexts.any { it.id == ref.id }) {
                    draftContexts.filterNot { it.id == ref.id }
                } else {
                    draftContexts + ref
                }
            }
        )
    }

    if (showAttachSheet) {
        AttachmentSheet(
            onDismiss = { showAttachSheet = false },
            onCamera = {
                showAttachSheet = false
                launchCamera()
            },
            onGallery = {
                showAttachSheet = false
                launchPicker()
            }
        )
    }

    previewImage?.let { path ->
        ImagePreviewDialog(path = path, onDismiss = { previewImage = null })
    }

    if (exporting) {
        ExportProgressDialog()
    }
}

@Composable
private fun ExportProgressDialog() {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    text = "正在导出，请稍候…",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun ChatTopBar(
    providerName: String,
    modelName: String,
    providerColor: Color,
    onMenu: () -> Unit,
    onNewChat: () -> Unit,
    onPickModel: () -> Unit,
    onExportMarkdown: (() -> Unit)?,
    onExportAll: (() -> Unit)?
) {
    var exportMenuOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onMenu) {
                Icon(Icons.Rounded.Menu, contentDescription = "会话列表")
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Surface(
                    onClick = onPickModel,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SmallDot(providerColor, size = 7)
                        Spacer(Modifier.width(7.dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = modelName.ifBlank { "选择模型" },
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                text = providerName,
                                style = MaterialTheme.typography.labelMedium,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        Spacer(Modifier.width(2.dp))
                        Icon(
                            Icons.Rounded.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { exportMenuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "导出对话")
                }
                DropdownMenu(
                    expanded = exportMenuOpen,
                    onDismissRequest = { exportMenuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("导出当前对话为 Markdown") },
                        enabled = onExportMarkdown != null,
                        onClick = {
                            exportMenuOpen = false
                            onExportMarkdown?.invoke()
                        }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("导出全部对话（ZIP）") },
                        enabled = onExportAll != null,
                        onClick = {
                            exportMenuOpen = false
                            onExportAll?.invoke()
                        }
                    )
                }
            }
            IconButton(onClick = onNewChat) {
                Icon(Icons.Rounded.Add, contentDescription = "新对话")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    }
}

@Composable
private fun WelcomePane(
    presets: List<AgentPreset>,
    selectedPreset: AgentPreset?,
    onSelectPreset: (String) -> Unit,
    onTunePreset: () -> Unit,
    onAddPreset: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 36.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SmathLogo(size = 56)
        Spacer(Modifier.height(22.dp))
        Text(
            text = "今天有什么可以帮你的吗？",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "数学、英语、知识点梳理，或任何你想弄清楚的问题",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(28.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Agent 预设",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Surface(
                onClick = onTunePreset,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Icon(
                    Icons.Rounded.Settings,
                    contentDescription = "配置当前预设",
                    modifier = Modifier.padding(7.dp).size(17.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            presets.chunked(2).forEach { rowPresets ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    rowPresets.forEach { preset ->
                        PresetCard(
                            preset = preset,
                            selected = preset.id == selectedPreset?.id,
                            modifier = Modifier.weight(1f),
                            onClick = { onSelectPreset(preset.id) }
                        )
                    }
                    if (rowPresets.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(9.dp))
            Surface(
                onClick = onAddPreset,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "新建预设",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetCard(
    preset: AgentPreset,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
        else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    presetIcon(preset),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (selected) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = "已选择",
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = preset.summary(),
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f)
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SuggestionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun UserMessage(message: ChatMessage, onImageClick: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Column(
            modifier = Modifier.widthIn(max = 340.dp),
            horizontalAlignment = Alignment.End
        ) {
            if (message.contexts.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    Text(
                        text = message.contexts.joinToString("  ") {
                            "@${it.title.ifBlank { "上下文" }}"
                        },
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (message.imagePaths.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    items(message.imagePaths) { path ->
                        AsyncImage(
                            model = File(path),
                            contentDescription = "上传的图片",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(132.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onImageClick(path) }
                        )
                    }
                }
            }
            if (message.content.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(
                        topStart = 20.dp,
                        topEnd = 20.dp,
                        bottomStart = 20.dp,
                        bottomEnd = 6.dp
                    )
                ) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantMessage(
    message: ChatMessage,
    dark: Boolean,
    thinkingExpanded: Boolean,
    onToggleThinking: () -> Unit,
    drawingAgentEnabled: Boolean,
    calculatorAgentEnabled: Boolean,
    drawingCanvasSize: Int,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onCopy: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (message.reasoning.isNotBlank()) {
            ReasoningBlock(
                reasoning = message.reasoning,
                finishedMs = message.thinkingMs,
                streaming = false,
                dark = dark,
                expanded = thinkingExpanded,
                onToggleExpanded = onToggleThinking
            )
                Spacer(Modifier.height(10.dp))
            }
            message.notice?.let { NoticeChip(it) }
            if (message.content.isNotBlank()) {
                SpecialAgentContent(
                    markdown = message.content,
                    dark = dark,
                    drawingEnabled = drawingAgentEnabled,
                    calculatorEnabled = calculatorAgentEnabled,
                    drawingCanvasSize = drawingCanvasSize,
                    streaming = false,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (message.citations.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                CitationsBlock(message.citations)
            }
            if (message.error != null) {
                Spacer(Modifier.height(10.dp))
                ApiErrorCard(message.error, canRetry = canRetry, onRetry = onRetry)
            }
            if (message.content.isNotBlank()) {
                Row(
                    modifier = Modifier.padding(top = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onCopy,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Icon(
                            Icons.Rounded.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "复制",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

@Composable
private fun StreamingBubble(
    streaming: StreamingMessage,
    dark: Boolean,
    thinkingExpanded: Boolean,
    onToggleThinking: () -> Unit,
    drawingAgentEnabled: Boolean,
    calculatorAgentEnabled: Boolean,
    drawingCanvasSize: Int
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (streaming.reasoning.isNotBlank()) {
            ReasoningBlock(
                reasoning = streaming.reasoning,
                finishedMs = 0,
                streaming = streaming.content.isBlank(),
                startedAt = streaming.startedAt,
                dark = dark,
                expanded = thinkingExpanded,
                onToggleExpanded = onToggleThinking
            )
                Spacer(Modifier.height(10.dp))
            }
            streaming.searchNotice?.let {
                NoticeChip(it)
                Spacer(Modifier.height(7.dp))
            }
            if (streaming.content.isBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.8.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = streaming.phase,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                SpecialAgentContent(
                    markdown = streaming.content,
                    dark = dark,
                    drawingEnabled = drawingAgentEnabled,
                    calculatorEnabled = calculatorAgentEnabled,
                    drawingCanvasSize = drawingCanvasSize,
                    streaming = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "正在生成…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationJumpSheet(
    messages: List<ChatMessage>,
    streaming: StreamingMessage?,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 2.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "跳转到会话位置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "选择一条消息，滚动到它的开头",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(messages.size) { index ->
                    val message = messages[index]
                    val snippet = message.jumpSnippet()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onJump(index) }
                            .padding(horizontal = 20.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (message.isUser) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = if (message.isUser) "你" else "AI",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (message.isUser) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (message.isUser) "你的提问" else "AI 回答",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = "#${index + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = snippet,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            Text(
                                text = TimeUtils.formatRelative(message.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = 60.dp)
                    )
                }
                if (streaming != null) {
                    item(key = "jump-streaming") {
                        val snippet = streaming.content.ifBlank { streaming.reasoning }
                            .lineSequence()
                            .map { it.trim() }
                            .firstOrNull { it.isNotBlank() }
                            .orEmpty()
                            .take(100)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onJump(messages.size) }
                                .padding(horizontal = 20.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(30.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "AI",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (streaming.content.isBlank()) "AI 正在思考" else "AI 正在回答",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = snippet.ifBlank { "正在生成…" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ChatMessage.jumpSnippet(): String {
    val raw = content.ifBlank {
        reasoning.ifBlank {
            if (imagePaths.isNotEmpty()) "图片消息" else "（无内容）"
        }
    }
    return raw.lineSequence()
        .map { it.trim().trimStart('#', '>', '*', '-', ' ') }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
        .take(100)
}

@Composable
private fun ReasoningBlock(
    reasoning: String,
    finishedMs: Long,
    streaming: Boolean,
    dark: Boolean,
    startedAt: Long = 0L,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    val lastLine = reasoning.lineSequence()
        .map { it.trim().trimStart('>', '#', '*', '-', ' ').trim() }
        .lastOrNull { it.isNotBlank() }
        .orEmpty()
    var seconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(streaming, startedAt) {
        if (streaming && startedAt > 0) {
            while (true) {
                seconds = ((android.os.SystemClock.elapsedRealtime() - startedAt) / 1000)
                delay(500)
            }
        }
    }
    val elapsed = if (streaming) seconds else (finishedMs / 1000).coerceAtLeast(1)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpanded() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (streaming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 1.6.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        Icons.Rounded.Psychology,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(7.dp))
                Text(
                    text = if (streaming) "正在深度思考 · ${elapsed}s"
                    else "已深度思考（用时 ${elapsed} 秒）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!expanded && lastLine.isNotBlank()) {
                Text(
                    text = lastLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 9.dp)
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                ) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    MarkdownView(
                        markdown = reasoning,
                        dark = dark,
                        fontSizeSp = MaterialTheme.typography.bodySmall.fontSize.value,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun NoticeChip(notice: String) {
    val isFailure = notice.startsWith("联网搜索未完成")
    val isSearch = notice.startsWith("已联网搜索")
    val background = when {
        isFailure -> MaterialTheme.colorScheme.errorContainer
        isSearch -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = when {
        isFailure -> MaterialTheme.colorScheme.onErrorContainer
        isSearch -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = CircleShape,
        color = background,
        modifier = Modifier.padding(bottom = 7.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isFailure) Icons.Rounded.ErrorOutline else Icons.Rounded.Public,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = foreground
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = notice,
                style = MaterialTheme.typography.labelMedium,
                color = foreground
            )
        }
    }
}

@Composable
private fun CitationsBlock(citations: List<Citation>) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "参考来源 · ${citations.size}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        citations.forEachIndexed { index, citation ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant
                ),
                onClick = {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(citation.url))
                        )
                    } catch (_: Throwable) {
                    }
                }
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = citation.title.ifBlank { citation.url },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (citation.site.isNotBlank()) {
                                Text(
                                    text = citation.site,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            if (citation.date.isNotBlank()) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = citation.date,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApiErrorCard(error: ApiErrorInfo, canRetry: Boolean, onRetry: () -> Unit) {
    var showRaw by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = error.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
                if (error.code > 0) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = "HTTP ${error.code}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(
                text = error.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            if (error.detail.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = error.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.82f)
                )
            }
            if (error.raw.isNotBlank()) {
                TextButton(
                    onClick = { showRaw = !showRaw },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                ) {
                    Text(
                        if (showRaw) "收起原始错误" else "查看原始错误信息",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (showRaw) {
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
                    ) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(
                                text = error.raw,
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }
            if (canRetry) {
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = onRetry,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "重试",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun Composer(
    input: String,
    onInputChange: (String) -> Unit,
    images: List<String>,
    onRemoveImage: (String) -> Unit,
    onAddImage: () -> Unit,
    contexts: List<ContextReference>,
    onRemoveContext: (ContextReference) -> Unit,
    onAddContext: () -> Unit,
    webSearch: Boolean,
    onToggleSearch: (Boolean) -> Unit,
    hasReasoning: Boolean,
    thinkingExpanded: Boolean,
    onToggleThinking: () -> Unit,
    canViewAnswer: Boolean,
    onViewAnswer: () -> Unit,
    canJump: Boolean,
    onOpenJump: () -> Unit,
    canJumpBottom: Boolean,
    onJumpBottom: () -> Unit,
    generating: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp)
            .padding(top = 5.dp, bottom = 7.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = { onToggleSearch(!webSearch) },
                shape = CircleShape,
                color = if (webSearch) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
                border = if (webSearch) null else androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Public,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = if (webSearch) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "联网",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (webSearch) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (hasReasoning) {
                Spacer(Modifier.width(7.dp))
                Surface(
                    onClick = onToggleThinking,
                    shape = CircleShape,
                    color = if (thinkingExpanded) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
                    border = if (thinkingExpanded) null else androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (thinkingExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (thinkingExpanded) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = if (thinkingExpanded) "收起思考" else "展开思考",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (thinkingExpanded) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (canViewAnswer) {
                Spacer(Modifier.width(7.dp))
                Surface(
                    onClick = onViewAnswer,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.ArrowDownward,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "看回答",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (canJump) {
                Spacer(Modifier.width(7.dp))
                Surface(
                    onClick = onOpenJump,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.FormatListBulleted,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "跳转",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (canJumpBottom) {
                Spacer(Modifier.width(7.dp))
                Surface(
                    onClick = onJumpBottom,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.ArrowDownward,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "到底部",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (generating) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.outline
            ),
            shadowElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp)) {
                if (contexts.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(start = 4.dp, top = 3.dp, bottom = 6.dp)
                    ) {
                        items(contexts, key = { it.id }) { ref ->
                            Surface(
                                shape = RoundedCornerShape(9.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "@",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        ref.title.ifBlank { "上下文" },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 150.dp)
                                    )
                                    Spacer(Modifier.width(2.dp))
                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = "移除引用",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .clickable { onRemoveContext(ref) }
                                            .padding(2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                if (images.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        modifier = Modifier.padding(start = 4.dp, top = 3.dp, bottom = 6.dp)
                    ) {
                        items(images) { path ->
                            Box {
                                AsyncImage(
                                    model = File(path),
                                    contentDescription = "待发送图片",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(66.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(2.dp)
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.55f))
                                        .clickable { onRemoveImage(path) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = "移除图片",
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onAddContext,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Text(
                            "@",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (contexts.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = onAddImage,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = "添加图片",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = onInputChange,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp, vertical = 6.dp)
                            .heightIn(min = 22.dp, max = 160.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Default
                        ),
                        decorationBox = { innerTextField ->
                            Box {
                                if (input.isEmpty()) {
                                    Text(
                                        text = "给 Smath 发送消息",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                    if (generating) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable(onClick = onStop),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Stop,
                                contentDescription = "停止生成",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    } else {
                        val enabled = input.isNotBlank() || images.isNotEmpty()
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(
                                    if (enabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable(enabled = enabled, onClick = onSend),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.ArrowUpward,
                                contentDescription = "发送",
                                tint = if (enabled) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetChip(
    preset: AgentPreset,
    selected: Boolean,
    locked: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        border = if (selected) null else androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                presetIcon(preset),
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = preset.name,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (locked && selected) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Rounded.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(11.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                )
            }
        }
    }
}

private fun presetIcon(preset: AgentPreset): ImageVector = when (preset.icon) {
    "math" -> Icons.Rounded.Calculate
    "english" -> Icons.Rounded.Translate
    "knowledge" -> Icons.Rounded.MenuBook
    "socratic" -> Icons.Rounded.Lightbulb
    else -> Icons.Rounded.Tune
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentSheet(
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 18.dp)
        ) {
            Text(
                "添加图片",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "拍照或选择图片后，会先进入裁剪界面；默认保留完整图片。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            AttachRow(
                icon = Icons.Rounded.PhotoCamera,
                title = "拍照",
                subtitle = "使用相机拍摄，然后裁剪",
                onClick = onCamera
            )
            AttachRow(
                icon = Icons.Rounded.Image,
                title = "从相册选择",
                subtitle = "选择已有图片，然后裁剪",
                onClick = onGallery
            )
        }
    }
}

@Composable
private fun AttachRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ImagePreviewDialog(path: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AsyncImage(
                model = File(path),
                contentDescription = "图片预览",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = Color.White)
            }
        }
    }
}

private fun copyText(context: android.content.Context, text: String) {
    if (text.isBlank()) return
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
        as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Smath", text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}

private fun snapshotFlowCompat(listState: androidx.compose.foundation.lazy.LazyListState) =
    androidx.compose.runtime.snapshotFlow { listState.isScrollInProgress }