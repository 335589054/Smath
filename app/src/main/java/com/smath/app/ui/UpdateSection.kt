package com.smath.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.smath.app.update.UpdateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** 卡片内部的状态机。对设置页只暴露 [UpdateCard]，其余保持私有。 */
private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val latest: String) : UpdateUiState
    data class Available(val info: UpdateChecker.ReleaseInfo) : UpdateUiState
    data class Downloading(val info: UpdateChecker.ReleaseInfo, val progress: Int) : UpdateUiState
    data class ReadyToInstall(val info: UpdateChecker.ReleaseInfo, val file: File) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
}

private val UPDATE_SOURCE_OPTIONS = listOf(
    UpdateChecker.SOURCE_OFFICIAL to "官方源",
    UpdateChecker.SOURCE_GH_PROXY_COM to "gh-proxy.com",
    UpdateChecker.SOURCE_GH_PROXY_ORG to "gh-proxy.org"
)

/**
 * 设置页「关于」附近的检查更新卡片：
 * 检查更新 → 发现新版本 → 下载（带进度/取消）→ 拉起系统安装器，
 * 以及「安装未知应用」授权引导。
 */
@Composable
fun UpdateCard(
    currentVersion: String,
    source: String,
    onSourceChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var uiState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    var needsPermission by remember { mutableStateOf(false) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    val version = currentVersion.ifBlank { UpdateChecker.currentVersionName(context) }
    val busy = uiState is UpdateUiState.Checking || uiState is UpdateUiState.Downloading

    fun refreshPermission() {
        needsPermission = !canInstallPackages(context)
    }

    fun startCheck() {
        if (busy) return
        uiState = UpdateUiState.Checking
        scope.launch {
            val result = UpdateChecker.check(context, source)
            val info = result.info
            uiState = when {
                info == null -> UpdateUiState.Failed(result.error ?: "检查更新失败，请稍后重试")
                UpdateChecker.isNewer(info.versionName, version) -> UpdateUiState.Available(info)
                else -> UpdateUiState.UpToDate(info.versionName)
            }
        }
    }

    fun launchInstaller(info: UpdateChecker.ReleaseInfo, file: File) {
        val error = UpdateChecker.install(context, file)
        if (error == null) {
            needsPermission = false
            uiState = UpdateUiState.ReadyToInstall(info, file)
        } else {
            needsPermission = !canInstallPackages(context) || error.contains("未知来源")
            uiState = UpdateUiState.Failed(error)
        }
    }

    fun startDownload(info: UpdateChecker.ReleaseInfo) {
        if (downloadJob?.isActive == true) return
        uiState = UpdateUiState.Downloading(info, 0)
        downloadJob = scope.launch {
            try {
                val file = UpdateChecker.download(context, info, source) { percent ->
                    // UpdateChecker 保证回调在主线程
                    val current = uiState
                    if (current is UpdateUiState.Downloading) {
                        uiState = current.copy(progress = percent)
                    }
                }
                if (file == null) {
                    uiState = UpdateUiState.Failed("下载失败，请检查网络，或换一个源后重试")
                    return@launch
                }
                uiState = UpdateUiState.ReadyToInstall(info, file)
                launchInstaller(info, file)
            } catch (cancellation: CancellationException) {
                uiState = UpdateUiState.Available(info)
                throw cancellation
            } catch (error: Throwable) {
                uiState = UpdateUiState.Failed("下载失败：${error.message ?: "未知错误"}")
            } finally {
                downloadJob = null
            }
        }
    }

    fun cancelDownload() {
        val current = uiState
        downloadJob?.cancel()
        downloadJob = null
        if (current is UpdateUiState.Downloading) uiState = UpdateUiState.Available(current.info)
    }

    // 从「安装未知应用」授权页返回时刷新授权状态
    LifecycleResumeEffect(Unit) {
        refreshPermission()
        onPauseOrDispose { }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
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
                        Icons.Rounded.SystemUpdate,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "检查更新",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "当前版本 $version",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { startCheck() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uiState is UpdateUiState.Checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("检查中…")
                } else {
                    Text("检查更新")
                }
            }

            // ---------------- 状态区 ----------------
            when (val state = uiState) {
                is UpdateUiState.Available -> {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "发现新版本 ${displayTag(state.info)}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    val preview = notePreview(state.info.notes)
                    if (preview.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            preview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "安装包 ${formatSize(state.info.apkSize)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { startDownload(state.info) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("下载并安装")
                        }
                        TextButton(onClick = { openReleasePage(context, state.info.pageUrl) }) {
                            Text("发布页", fontSize = 12.sp)
                        }
                    }
                }

                is UpdateUiState.Downloading -> {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "正在下载 ${displayTag(state.info)}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${state.progress}%",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                    TextButton(onClick = { cancelDownload() }) {
                        Text("取消")
                    }
                }

                is UpdateUiState.ReadyToInstall -> {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "安装包已下载完成",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { launchInstaller(state.info, state.file) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("立即安装")
                        }
                        TextButton(onClick = { startDownload(state.info) }) {
                            Text("重新下载", fontSize = 12.sp)
                        }
                    }
                }

                is UpdateUiState.UpToDate -> {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "已是最新版本",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { startCheck() }) {
                            Text("重新检查", fontSize = 12.sp)
                        }
                    }
                }

                is UpdateUiState.Failed -> {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { startCheck() }) {
                            Text("重试", fontSize = 12.sp)
                        }
                    }
                }

                UpdateUiState.Idle, UpdateUiState.Checking -> Unit
            }

            if (needsPermission) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "系统要求先允许 Smath 安装未知来源应用，授权后回来再点安装。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        val opened = UpdateChecker.openInstallPermissionSettings(context)
                        if (!opened) {
                            Toast.makeText(context, "无法打开授权页，请手动到系统设置中开启", Toast.LENGTH_SHORT)
                                .show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("去授权")
                }
            }

            // ---------------- 更新源 ----------------
            Spacer(Modifier.height(14.dp))
            Text(
                "更新源",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (option in UPDATE_SOURCE_OPTIONS) {
                    SourceChip(
                        label = option.second,
                        selected = option.first == source,
                        onClick = { onSourceChange(option.first) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "默认走镜像，国内更快；镜像不可用时可切回官方源",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SourceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(50)
    val background = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Surface(
        modifier = modifier.clip(shape).clickable(onClick = onClick),
        shape = shape,
        color = background,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = contentColor
            )
        }
    }
}

private fun canInstallPackages(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
    return runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
}

private fun displayTag(info: UpdateChecker.ReleaseInfo): String =
    info.tag.ifBlank { "v" + info.versionName }

/** release notes 只留前两行非空内容，避免把整篇 CHANGELOG 铺满设置页。 */
private fun notePreview(notes: String, maxLines: Int = 2): String =
    notes.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .take(maxLines)
        .joinToString("\n")

private fun formatSize(bytes: Long): String {
    if (bytes <= 0L) return "大小未知"
    val megabytes = bytes / 1048576.0
    return if (megabytes >= 1.0) {
        String.format(Locale.US, "%.1f MB", megabytes)
    } else {
        String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }
}

private fun openReleasePage(context: Context, url: String) {
    if (url.isBlank()) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, "无法打开浏览器，请手动访问发布页", Toast.LENGTH_SHORT).show()
    }
}