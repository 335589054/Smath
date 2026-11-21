package com.smath.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.SettingsBrightness
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smath.app.data.AgentPreset
import com.smath.app.data.BASE_AGENT_PROMPT
import com.smath.app.data.ListMode
import com.smath.app.data.ProviderConfig
import com.smath.app.data.PresetTransfer
import com.smath.app.data.providerKindColor
import com.smath.app.screenshot.ScreenshotOverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    viewModel: ChatViewModel,
    dark: Boolean,
    onClose: () -> Unit
) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    val settings = data.settings
    var expandedProviderId by remember { mutableStateOf(settings.activeProviderId) }
    var deleteTarget by remember { mutableStateOf<ProviderConfig?>(null) }
    var editingPreset by remember { mutableStateOf<AgentPreset?>(null) }
    var editingPresetIsNew by remember { mutableStateOf(false) }
    var editingGlobalPrompt by remember { mutableStateOf(false) }
    var selectingPresets by remember { mutableStateOf(false) }
    var selectedPresetIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var presetTransferBusy by remember { mutableStateOf(false) }
    var pendingPresetExport by remember { mutableStateOf<List<AgentPreset>>(emptyList()) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val createPresetZip = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val presets = pendingPresetExport
        pendingPresetExport = emptyList()
        if (uri == null || presets.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            presetTransferBusy = true
            try {
                val count = withContext(Dispatchers.IO) {
                    PresetTransfer.write(context, uri, presets)
                }
                Toast.makeText(context, "已导出 $count 个预设", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Toast.makeText(
                    context,
                    "导出失败：${t.message ?: "未知错误"}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                presetTransferBusy = false
            }
        }
    }
    val openPresetZip = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            presetTransferBusy = true
            try {
                val imported = withContext(Dispatchers.IO) {
                    PresetTransfer.read(context, uri)
                }
                if (imported.isEmpty()) {
                    Toast.makeText(context, "没有找到可导入的预设", Toast.LENGTH_LONG).show()
                } else {
                    viewModel.importPresets(imported)
                    selectingPresets = false
                    selectedPresetIds = emptySet()
                }
            } catch (t: Throwable) {
                Toast.makeText(
                    context,
                    "导入失败：${t.message ?: "文件格式不正确"}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                presetTransferBusy = false
            }
        }
    }
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(context)) {
            viewModel.setScreenshotEnabled(true)
            ScreenshotOverlayService.start(context)
        } else {
            viewModel.setScreenshotEnabled(false)
            Toast.makeText(context, "需要“显示在其他应用上层”权限才能使用截屏按钮", Toast.LENGTH_LONG)
                .show()
        }
    }

    BackHandler(onBack = onClose)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(60.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "设置",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "模型和 API Key",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SmathLogo(size = 28)
            Spacer(Modifier.width(10.dp))
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                SectionLabel("模型和 API Key")
                Text(
                    "预设了 DeepSeek 官方与 ChatGPT，也支持添加任意 OpenAI 兼容服务商。Key 只保存在本机。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
                Spacer(Modifier.height(8.dp))
            }

            items(settings.providers, key = { it.id }) { provider ->
                ProviderCard(
                    provider = provider,
                    active = provider.id == settings.activeProviderId,
                    activeModel = settings.activeModel,
                    expanded = expandedProviderId == provider.id,
                    canDelete = settings.providers.size > 1,
                    onToggle = {
                        expandedProviderId = if (expandedProviderId == provider.id) "" else provider.id
                    },
                    onUse = { viewModel.setActiveProvider(provider.id) },
                    onSelectModel = { model ->
                        viewModel.setActiveProvider(provider.id)
                        viewModel.setActiveModel(model)
                    },
                    onUpdate = viewModel::updateProvider,
                    onDelete = { deleteTarget = provider }
                )
            }

            item {
                Surface(
                    onClick = { viewModel.addProvider() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            "添加自定义服务商",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
            item { SectionLabel("联网搜索") }
            item {
                SearchSettingsCard(
                    searchApiKey = settings.searchApiKey,
                    searchCount = settings.searchCount,
                    webSearchByDefault = settings.webSearchByDefault,
                    onSearchKeyChange = viewModel::setSearchKey,
                    onSearchCountChange = viewModel::setSearchCount,
                    onToggleDefault = viewModel::setWebSearch
                )
            }

            item { Spacer(Modifier.height(12.dp)) }
            item { SectionLabel("Agent 预设") }
            item {
                Text(
                    "预设会在新对话开始时注入一次学段与解答风格提示词；对话开始后不可更改。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
            items(settings.presets, key = { "preset_${it.id}" }) { preset ->
                val index = settings.presets.indexOfFirst { it.id == preset.id }
                PresetRow(
                    preset = preset,
                    selecting = selectingPresets,
                    selected = preset.id in selectedPresetIds,
                    canMoveUp = index > 0,
                    canMoveDown = index in 0 until settings.presets.lastIndex,
                    onMoveUp = { viewModel.movePreset(preset.id, -1) },
                    onMoveDown = { viewModel.movePreset(preset.id, 1) },
                    onClick = {
                        if (selectingPresets) {
                            selectedPresetIds = if (preset.id in selectedPresetIds) {
                                selectedPresetIds - preset.id
                            } else {
                                selectedPresetIds + preset.id
                            }
                        } else {
                            editingPresetIsNew = false
                            editingPreset = preset
                        }
                    }
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        openPresetZip.launch(arrayOf("application/zip", "application/json"))
                    }, enabled = !presetTransferBusy) {
                        Text("导入")
                    }
                    TextButton(
                        onClick = {
                            val targets = settings.presets.filter { it.id in selectedPresetIds }
                            if (targets.isEmpty()) return@TextButton
                            pendingPresetExport = targets
                            createPresetZip.launch(PresetTransfer.suggestedZipName())
                        },
                        enabled = !presetTransferBusy && selectedPresetIds.isNotEmpty()
                    ) {
                        Text(
                            if (selectedPresetIds.isEmpty()) {
                                "导出所选"
                            } else {
                                "导出所选 (${selectedPresetIds.size})"
                            }
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (selectingPresets) {
                        TextButton(onClick = {
                            selectedPresetIds = if (selectedPresetIds.size == settings.presets.size) {
                                emptySet()
                            } else {
                                settings.presets.map { it.id }.toSet()
                            }
                        }) {
                            Text(
                                if (selectedPresetIds.size == settings.presets.size) {
                                    "取消全选"
                                } else {
                                    "全选"
                                }
                            )
                        }
                    }
                    TextButton(onClick = {
                        selectingPresets = !selectingPresets
                        selectedPresetIds = if (selectingPresets) {
                            settings.presets.map { it.id }.toSet()
                        } else {
                            emptySet()
                        }
                    }) {
                        Text(if (selectingPresets) "完成" else "多选")
                    }
                }
            }
            item {
                TextButton(
                    onClick = {
                        editingPresetIsNew = true
                        editingPreset = AgentPreset(
                            id = "",
                            name = "新预设",
                            builtIn = false,
                            icon = "custom"
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("新建预设")
                }
            }
            if (presetTransferBusy) {
                item { LoadingRow("正在处理预设文件…") }
            }

            item { Spacer(Modifier.height(12.dp)) }
            item { SectionLabel("全局提示词") }
            item {
                GlobalPromptCard(
                    prompt = settings.globalPrompt,
                    onEdit = { editingGlobalPrompt = true }
                )
            }

            item { Spacer(Modifier.height(12.dp)) }
            item { SectionLabel("特殊 Agent") }
            item {
                SettingsCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "绘图 Agent",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "让模型用一组绘图命令生成可缩放、可编辑的函数图像。只在新对话开始时注入使用说明。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = settings.drawingAgentEnabled,
                                onCheckedChange = viewModel::setDrawingAgentEnabled
                            )
                        }
                        if (settings.drawingAgentEnabled) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "初始视野尺寸",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "画布没有边界，这里只决定第一次打开时的显示范围；坐标可以无限延伸。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(480, 720, 960).forEach { size ->
                                    FilterChip(
                                        selected = settings.drawingCanvasSize == size,
                                        onClick = { viewModel.setDrawingCanvasSize(size) },
                                        label = { Text("$size px") }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Calculate,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "计算器 Agent",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "让模型在回答中调用本地计算器，支持复数与常见数值函数，结果显示为可复制的卡片。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = settings.calculatorAgentEnabled,
                                onCheckedChange = viewModel::setCalculatorAgentEnabled
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
            item { SectionLabel("截屏") }
            item {
                SettingsCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.PhotoCamera,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "截屏悬浮按钮",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "适合平板：开启后在屏幕上显示可拖动的截屏按钮，单击即可截屏并进入裁剪页。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = settings.screenshotEnabled,
                                onCheckedChange = { enabled ->
                                    if (!enabled) {
                                        viewModel.setScreenshotEnabled(false)
                                        ScreenshotOverlayService.stop(context)
                                    } else if (Settings.canDrawOverlays(context)) {
                                        viewModel.setScreenshotEnabled(true)
                                        ScreenshotOverlayService.start(context)
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "请允许 Smath 显示在其他应用上层",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        runCatching {
                                            overlayPermissionLauncher.launch(
                                                Intent(
                                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                    Uri.parse("package:${context.packageName}")
                                                )
                                            )
                                        }
                                    }
                                }
                            )
                        }
                        if (settings.screenshotEnabled) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "已开启。蓝色“截”按钮可拖动；截屏时会请求一次系统录屏授权。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
            item { SectionLabel("对话与外观") }
            item {
                SettingsCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "外观",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DarkModeChip(
                                text = "跟随系统",
                                icon = Icons.Rounded.SettingsBrightness,
                                selected = settings.darkMode == "system",
                                onClick = { viewModel.setDarkMode("system") }
                            )
                            DarkModeChip(
                                text = "浅色",
                                icon = Icons.Rounded.LightMode,
                                selected = settings.darkMode == "light",
                                onClick = { viewModel.setDarkMode("light") }
                            )
                            DarkModeChip(
                                text = "深色",
                                icon = Icons.Rounded.DarkMode,
                                selected = settings.darkMode == "dark",
                                onClick = { viewModel.setDarkMode("dark") }
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "会话列表默认方式",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "文件夹可手动整理或让 AI 整理；日期模式按 Windows 风格分组。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DarkModeChip(
                                text = "文件夹",
                                icon = Icons.Rounded.Folder,
                                selected = settings.listMode == ListMode.FOLDERS,
                                onClick = { viewModel.setListMode(ListMode.FOLDERS) }
                            )
                            DarkModeChip(
                                text = "按日期",
                                icon = Icons.Rounded.Language,
                                selected = settings.listMode == ListMode.DATE,
                                onClick = { viewModel.setListMode(ListMode.DATE) }
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
            item { SectionLabel("关于") }
            item {
                SettingsCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SmathLogo(size = 38)
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Smath",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "Android 原生 · Kotlin / Compose",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                "1.0.0",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "仅支持文字对话与图片输入。回答由所选模型生成，AI 整理只会读取会话标题和更新时间，不会上传完整聊天内容。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    deleteTarget?.let { provider ->
        SmathConfirmDialog(
            title = "删除服务商？",
            message = "将移除「${provider.name}」及其本地 API Key 配置。",
            confirmLabel = "删除",
            destructive = true,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                viewModel.deleteProvider(provider.id)
                deleteTarget = null
            }
        )
    }

    editingPreset?.let { preset ->
        PresetConfigSheet(
            preset = preset,
            locked = false,
            isNew = editingPresetIsNew,
            onDismiss = {
                editingPreset = null
                editingPresetIsNew = false
            },
            onSave = {
                if (editingPresetIsNew) viewModel.addPreset(it) else viewModel.updatePreset(it)
            },
            onReset = {
                if (!editingPresetIsNew) {
                    viewModel.resetPresetToDefault(preset.id)
                    editingPreset = null
                    editingPresetIsNew = false
                }
            },
            onDelete = if (!editingPresetIsNew && !preset.builtIn) {
                { viewModel.deletePreset(preset.id) }
            } else {
                null
            },
            drawingEnabled = settings.drawingAgentEnabled
        )
    }

    if (editingGlobalPrompt) {
        GlobalPromptDialog(
            initial = settings.globalPrompt,
            onDismiss = { editingGlobalPrompt = false },
            onSave = {
                viewModel.setGlobalPrompt(it)
                editingGlobalPrompt = false
            }
        )
    }
}

@Composable
private fun GlobalPromptCard(prompt: String, onEdit: () -> Unit) {
    SettingsCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "默认全局提示词",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "新对话开始时注入一次，修改后只影响之后创建的新对话。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onEdit) { Text("查看/修改") }
            }
            Spacer(Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            ) {
                Text(
                    prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

@Composable
private fun GlobalPromptDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember(initial) { mutableStateOf(initial) }
    SmathDialog(
        title = "全局提示词",
        subtitle = "会作为每个新对话系统提示词的第一部分；清空后恢复默认内容。",
        icon = Icons.Rounded.SettingsBrightness,
        onDismiss = onDismiss,
        footer = {
            TextButton(onClick = { text = BASE_AGENT_PROMPT }) { Text("恢复默认") }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onDismiss) { Text("取消") }
            Spacer(Modifier.width(10.dp))
            Button(onClick = { onSave(text) }) { Text("保存") }
        }
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 8,
            maxLines = 14,
            shape = RoundedCornerShape(16.dp),
            textStyle = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderConfig,
    active: Boolean,
    activeModel: String,
    expanded: Boolean,
    canDelete: Boolean,
    onToggle: () -> Unit,
    onUse: () -> Unit,
    onSelectModel: (String) -> Unit,
    onUpdate: (ProviderConfig) -> Unit,
    onDelete: () -> Unit
) {
    var revealKey by remember(provider.id) { mutableStateOf(false) }
    var modelsText by remember(provider.id) {
        mutableStateOf(provider.models.joinToString("\n"))
    }
    val color = providerKindColor(provider.kind)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            if (active) 1.5.dp else 1.dp,
            if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
            else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 15.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(color.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            provider.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (active) {
                            Spacer(Modifier.width(7.dp))
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    "使用中",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = if (provider.hasKey) {
                            "${providerKindLabel(provider.kind)} · 已配置 Key"
                        } else {
                            "${providerKindLabel(provider.kind)} · 未配置 Key"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (provider.hasKey) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error
                    )
                }
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(if (expanded) 90f else 0f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(modifier = Modifier.padding(15.dp)) {
                    if (!provider.hasKey) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.errorContainer,
                                    RoundedCornerShape(10.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "填入 API Key 后即可使用此服务商。",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    OutlinedTextField(
                        value = provider.apiKey,
                        onValueChange = { onUpdate(provider.copy(apiKey = it.trim())) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("API Key") },
                        placeholder = { Text("sk-...") },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Key,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = {
                            IconButton(onClick = { revealKey = !revealKey }) {
                                Icon(
                                    if (revealKey) Icons.Rounded.VisibilityOff
                                    else Icons.Rounded.Visibility,
                                    contentDescription = if (revealKey) "隐藏 Key" else "显示 Key",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        },
                        visualTransformation = if (revealKey) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(13.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(11.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Key 仅保存在本机应用私有目录；发起请求时只会发送给你选择的模型服务商。",
                            style = MaterialTheme.typography.labelMedium,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = provider.baseUrl,
                        onValueChange = { onUpdate(provider.copy(baseUrl = it.trim())) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Base URL") },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Language,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        shape = RoundedCornerShape(13.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    )

                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = modelsText,
                        onValueChange = { text ->
                            modelsText = text
                            val models = parseModels(text)
                            onUpdate(
                                provider.copy(
                                    models = models,
                                    defaultModel = when {
                                        models.isEmpty() -> ""
                                        models.contains(provider.defaultModel) -> provider.defaultModel
                                        else -> models.first()
                                    }
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("模型列表") },
                        placeholder = { Text("每行一个，或用逗号分隔") },
                        minLines = 2,
                        maxLines = 5,
                        shape = RoundedCornerShape(13.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                    )

                    val models = provider.models
                    if (models.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "选择模型",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        models.forEach { model ->
                            val chosen = active && activeModel == model
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectModel(model) }
                                    .padding(vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(17.dp)
                                        .background(
                                            if (chosen) MaterialTheme.colorScheme.primary
                                            else Color.Transparent,
                                            CircleShape
                                        )
                                        .then(
                                            if (!chosen) Modifier.border(
                                                1.5.dp,
                                                MaterialTheme.colorScheme.outline,
                                                CircleShape
                                            ) else Modifier
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (chosen) {
                                        Icon(
                                            Icons.Rounded.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(11.dp),
                                            tint = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                }
                                Spacer(Modifier.width(9.dp))
                                Text(
                                    model,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (chosen) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (canDelete) {
                            TextButton(
                                onClick = onDelete,
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "删除",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (!active) {
                            Surface(
                                onClick = onUse,
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    "使用此服务商",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp)
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
private fun SearchSettingsCard(
    searchApiKey: String,
    searchCount: Int,
    webSearchByDefault: Boolean,
    onSearchKeyChange: (String) -> Unit,
    onSearchCountChange: (Int) -> Unit,
    onToggleDefault: (Boolean) -> Unit
) {
    var reveal by remember { mutableStateOf(false) }
    SettingsCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(11.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Public,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "DeepSeek 官方联网搜索",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "由 DeepSeek 的 /v1/tools/search 提供",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "搜索会先读取标题、摘要与链接，再把结果作为上下文交给当前模型；搜索结果被视为不可信内容。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = searchApiKey,
                onValueChange = onSearchKeyChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("搜索 API Key（可选）") },
                placeholder = { Text("留空则复用 DeepSeek 官方 Key") },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Key,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    IconButton(onClick = { reveal = !reveal }) {
                        Icon(
                            if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                visualTransformation = if (reveal) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = RoundedCornerShape(13.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "按 DeepSeek 官方搜索接口计费，仅成功请求计数；失败或无结果不产生费用。",
                style = MaterialTheme.typography.labelMedium,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("每次搜索返回条数", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "1 - 20 条",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { onSearchCountChange(searchCount - 1) },
                    enabled = searchCount > 1
                ) {
                    Icon(Icons.Rounded.Remove, contentDescription = "减少")
                }
                Text(
                    searchCount.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.width(28.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                IconButton(
                    onClick = { onSearchCountChange(searchCount + 1) },
                    enabled = searchCount < 20
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "增加")
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("新对话默认开启联网", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "可在输入框上方的「联网」按钮临时开关",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = webSearchByDefault, onCheckedChange = onToggleDefault)
            }
        }
    }
}

@Composable
private fun PresetRow(
    preset: AgentPreset,
    selecting: Boolean,
    selected: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        color = if (selected && selecting) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected && selecting) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(start = if (selecting) 4.dp else 14.dp, end = 6.dp)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
            }
            Column(Modifier.weight(1f)) {
                Text(
                    preset.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    preset.summary(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onMoveUp,
                enabled = canMoveUp,
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    Icons.Rounded.KeyboardArrowUp,
                    contentDescription = "上移",
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(
                onClick = onMoveDown,
                enabled = canMoveDown,
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "下移",
                    modifier = Modifier.size(18.dp)
                )
            }
            if (!selecting) {
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DarkModeChip(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        leadingIcon = {
            Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
        },
        label = { Text(text, fontSize = 12.sp) }
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        content()
    }
}

private fun providerKindLabel(kind: String): String = when (kind) {
    "deepseek" -> "DeepSeek 官方"
    "chatgpt" -> "OpenAI 官方"
    else -> "自定义 · OpenAI 兼容"
}

private fun parseModels(text: String): List<String> =
    text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()