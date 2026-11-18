package com.smath.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smath.app.data.ContextReference
import com.smath.app.data.Conversation
import com.smath.app.util.TimeUtils

/**
 * DeepSeek Harness 的输入框支持用 @ 引用文件/会话。Smath 是纯对话应用，
 * 因此这里的 @ 用来引用当前会话里的消息，或另一个会话的最近上下文。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextPickerSheet(
    active: Conversation?,
    conversations: List<Conversation>,
    selected: List<ContextReference>,
    onDismiss: () -> Unit,
    onToggle: (ContextReference) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val selectedIds = remember(selected) { selected.map { it.id }.toSet() }
    val localRefs = remember(active?.id, active?.messages) {
        active?.messages
            ?.filter { it.content.isNotBlank() && it.error == null }
            ?.takeLast(20)
            ?.reversed()
            ?.map { message ->
                ContextReference(
                    id = "message:${message.id}",
                    title = if (message.isUser) "你" else "Smath",
                    detail = TimeUtils.formatRelative(message.createdAt),
                    content = message.content.take(2200),
                    kind = "message"
                )
            }
            .orEmpty()
    }
    val otherRefs = remember(conversations, active?.id) {
        conversations
            .asSequence()
            .filter { it.id != active?.id && it.messages.isNotEmpty() }
            .sortedByDescending { it.updatedAt }
            .take(24)
            .mapNotNull { conversation ->
                val useful = conversation.messages
                    .filter { it.content.isNotBlank() && it.error == null }
                    .takeLast(4)
                if (useful.isEmpty()) return@mapNotNull null
                ContextReference(
                    id = "conversation:${conversation.id}",
                    title = conversation.title,
                    detail = "其他会话 · ${TimeUtils.formatRelative(conversation.updatedAt)}",
                    content = useful.joinToString("\n\n") { message ->
                        val speaker = if (message.isUser) "用户" else "助手"
                        "$speaker：${message.content.take(900)}"
                    }.take(3000),
                    kind = "conversation"
                )
            }
            .toList()
    }

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
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    "添加上下文",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "用 @ 引用已有消息或其他会话。引用只作为本轮资料，不会改写历史记录。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 18.dp
                ),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                if (localRefs.isNotEmpty()) {
                    item(key = "local-header") {
                        ContextSectionHeader("当前会话")
                    }
                    items(localRefs, key = { it.id }) { ref ->
                        ContextRow(
                            ref = ref,
                            selected = ref.id in selectedIds,
                            onClick = { onToggle(ref) }
                        )
                    }
                }
                if (otherRefs.isNotEmpty()) {
                    item(key = "other-header") {
                        ContextSectionHeader("其他会话")
                    }
                    items(otherRefs, key = { it.id }) { ref ->
                        ContextRow(
                            ref = ref,
                            selected = ref.id in selectedIds,
                            onClick = { onToggle(ref) }
                        )
                    }
                }
                if (localRefs.isEmpty() && otherRefs.isEmpty()) {
                    item {
                        Text(
                            "还没有可引用的历史消息。先聊几轮，或打开另一个已有会话。",
                            modifier = Modifier.padding(vertical = 28.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextSectionHeader(title: String) {
    Text(
        title,
        modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ContextRow(
    ref: ContextReference,
    selected: Boolean,
    onClick: () -> Unit
) {
    val icon = if (ref.kind == "conversation") Icons.Rounded.Description
    else Icons.Rounded.ChatBubbleOutline
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        else null
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    ref.title.ifBlank { "未命名上下文" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    ref.preview.ifBlank { "（空内容）" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (ref.detail.isNotBlank()) {
                    Text(
                        ref.detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(22.dp),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = "已引用",
                        modifier = Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .padding(1.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Spacer(Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}