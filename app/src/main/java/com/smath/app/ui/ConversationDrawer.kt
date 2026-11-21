package com.smath.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smath.app.data.Conversation
import com.smath.app.data.ConversationFolder
import com.smath.app.data.ListMode
import com.smath.app.util.TimeUtils
import kotlinx.coroutines.delay

@Composable
fun ConversationDrawer(
    viewModel: ChatViewModel,
    activeId: String?,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    val organize by viewModel.organize.collectAsStateWithLifecycle()
    val settings = data.settings
    val conversations = data.conversations
    val folders = data.folders.sortedBy { it.sortOrder }

    var collapsedFolders by remember { mutableStateOf(setOf<String>()) }
    var showNewFolder by remember { mutableStateOf(false) }
    var folderRenameTarget by remember { mutableStateOf<ConversationFolder?>(null) }
    var folderDeleteTarget by remember { mutableStateOf<ConversationFolder?>(null) }
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }
    var moveTarget by remember { mutableStateOf<Conversation?>(null) }

    LaunchedEffect(organize.result) {
        if (organize.result != null) {
            delay(3500)
            viewModel.clearOrganizeState()
        }
    }

    ModalDrawerSheet(
        modifier = Modifier
            .fillMaxHeight()
            .width(322.dp),
        drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        windowInsets = WindowInsets(0, 0, 0, 0)
    ) {
        Column(modifier = Modifier.fillMaxHeight()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmathLogo(size = 34)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Smath",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "深度求索 · 对话 Agent",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Rounded.Settings, contentDescription = "设置")
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭")
                }
            }

            Surface(
                onClick = {
                    viewModel.newChat()
                    onClose()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primary
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "新对话",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModePill(
                    text = "文件夹",
                    selected = settings.listMode == ListMode.FOLDERS,
                    onClick = { viewModel.setListMode(ListMode.FOLDERS) }
                )
                Spacer(Modifier.width(6.dp))
                ModePill(
                    text = "日期",
                    selected = settings.listMode == ListMode.DATE,
                    onClick = { viewModel.setListMode(ListMode.DATE) }
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { showNewFolder = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Rounded.CreateNewFolder,
                        contentDescription = "新建文件夹",
                        modifier = Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    onClick = { viewModel.organizeWithAi() },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "AI 整理",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            OrganizeBanner(
                running = organize.running,
                error = organize.error,
                result = organize.result,
                onDismiss = viewModel::clearOrganizeState
            )

            HorizontalDivider(
                modifier = Modifier.padding(top = 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            val pinned = conversations.filter { it.pinned }.sortedByDescending { it.updatedAt }
            val unpinned = conversations.filterNot { it.pinned }.sortedByDescending { it.updatedAt }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                if (conversations.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 36.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Rounded.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(34.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(Modifier.height(9.dp))
                            Text(
                                "还没有对话",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "点击「新对话」开始",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                if (pinned.isNotEmpty()) {
                    item(key = "pinned_header") {
                        DrawerSectionHeader("置顶", pinned.size)
                    }
                    items(pinned, key = { "pin_${it.id}" }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            selected = conversation.id == activeId,
                            onClick = {
                                viewModel.openConversation(conversation.id)
                                onClose()
                            },
                            onRename = { renameTarget = conversation },
                            onPin = { viewModel.togglePinned(conversation.id) },
                            onMove = { moveTarget = conversation },
                            onDelete = { deleteTarget = conversation }
                        )
                    }
                }

                if (settings.listMode == ListMode.FOLDERS) {
                    folders.forEach { folder ->
                        val expanded = folder.id !in collapsedFolders
                        val children = unpinned.filter { it.folderId == folder.id }
                        item(key = "folder_${folder.id}") {
                            FolderHeader(
                                folder = folder,
                                count = conversations.count { it.folderId == folder.id },
                                expanded = expanded,
                                onToggle = {
                                    collapsedFolders = if (expanded) collapsedFolders + folder.id
                                    else collapsedFolders - folder.id
                                },
                                onRename = { folderRenameTarget = folder },
                                onDelete = { folderDeleteTarget = folder }
                            )
                        }
                        if (expanded) {
                            items(children, key = { "conv_${it.id}" }) { conversation ->
                                ConversationRow(
                                    conversation = conversation,
                                    selected = conversation.id == activeId,
                                    indented = true,
                                    onClick = {
                                        viewModel.openConversation(conversation.id)
                                        onClose()
                                    },
                                    onRename = { renameTarget = conversation },
                                    onPin = { viewModel.togglePinned(conversation.id) },
                                    onMove = { moveTarget = conversation },
                                    onDelete = { deleteTarget = conversation }
                                )
                            }
                        }
                    }
                    val ungrouped = unpinned.filter { conversation ->
                        conversation.folderId == null || folders.none { it.id == conversation.folderId }
                    }
                    if (ungrouped.isNotEmpty() || folders.isNotEmpty()) {
                        item(key = "ungrouped_header") {
                            DrawerSectionHeader("未分类", ungrouped.size)
                        }
                        items(ungrouped, key = { "ung_${it.id}" }) { conversation ->
                            ConversationRow(
                                conversation = conversation,
                                selected = conversation.id == activeId,
                                onClick = {
                                    viewModel.openConversation(conversation.id)
                                    onClose()
                                },
                                onRename = { renameTarget = conversation },
                                onPin = { viewModel.togglePinned(conversation.id) },
                                onMove = { moveTarget = conversation },
                                onDelete = { deleteTarget = conversation }
                            )
                        }
                    }
                } else {
                    val groups = listOf("今天", "昨天", "本周", "本月", "更早")
                    groups.forEach { group ->
                        val items = unpinned.filter { TimeUtils.dateGroup(it.updatedAt) == group }
                        if (items.isNotEmpty()) {
                            item(key = "date_${group}") {
                                DrawerSectionHeader(group, items.size)
                            }
                            items(items, key = { "date_${it.id}" }) { conversation ->
                                ConversationRow(
                                    conversation = conversation,
                                    selected = conversation.id == activeId,
                                    onClick = {
                                        viewModel.openConversation(conversation.id)
                                        onClose()
                                    },
                                    onRename = { renameTarget = conversation },
                                    onPin = { viewModel.togglePinned(conversation.id) },
                                    onMove = { moveTarget = conversation },
                                    onDelete = { deleteTarget = conversation }
                                )
                            }
                        }
                    }
                }

                item(key = "footer") {
                    Text(
                        text = "按 Windows 风格按日期整理 · 长按菜单可手动归类",
                        style = MaterialTheme.typography.labelMedium,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 14.dp)
                    )
                }
            }
        }
    }

    if (showNewFolder) {
        TextInputDialog(
            title = "新建文件夹",
            label = "文件夹名称",
            initial = "",
            placeholder = "例如：微积分、英语语法",
            confirmLabel = "创建",
            onDismiss = { showNewFolder = false },
            onConfirm = { name ->
                viewModel.createFolder(name)
                showNewFolder = false
            }
        )
    }
    folderRenameTarget?.let { folder ->
        TextInputDialog(
            title = "重命名文件夹",
            label = "文件夹名称",
            initial = folder.name,
            confirmLabel = "保存",
            onDismiss = { folderRenameTarget = null },
            onConfirm = { name ->
                viewModel.renameFolder(folder.id, name)
                folderRenameTarget = null
            }
        )
    }
    folderDeleteTarget?.let { folder ->
        ConfirmDialog(
            title = "删除文件夹？",
            message = "「${folder.name}」中的会话会移到「未分类」，不会被删除。",
            confirmLabel = "删除",
            destructive = true,
            onDismiss = { folderDeleteTarget = null },
            onConfirm = {
                viewModel.deleteFolder(folder.id)
                folderDeleteTarget = null
            }
        )
    }
    renameTarget?.let { conversation ->
        TextInputDialog(
            title = "重命名对话",
            label = "对话标题",
            initial = conversation.title,
            confirmLabel = "保存",
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                viewModel.renameConversation(conversation.id, name)
                renameTarget = null
            }
        )
    }
    deleteTarget?.let { conversation ->
        ConfirmDialog(
            title = "删除这个对话？",
            message = "「${conversation.title}」及其消息将被永久删除。",
            confirmLabel = "删除",
            destructive = true,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                viewModel.deleteConversation(conversation.id)
                deleteTarget = null
            }
        )
    }
    moveTarget?.let { conversation ->
        MoveFolderDialog(
            folders = folders,
            selectedFolderId = conversation.folderId,
            onDismiss = { moveTarget = null },
            onSelect = { folderId ->
                viewModel.moveConversation(conversation.id, folderId)
                moveTarget = null
            }
        )
    }
}

@Composable
private fun ModePill(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else Color.Transparent
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun OrganizeBanner(
    running: Boolean,
    error: String?,
    result: String?,
    onDismiss: () -> Unit
) {
    if (!running && error == null && result == null) return
    val isError = error != null
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (isError) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.7.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(7.dp))
            }
            Text(
                text = when {
                    running -> "正在让 AI 阅读标题并整理…"
                    error != null -> error
                    else -> result.orEmpty()
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            if (!running) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "关闭提示",
                        modifier = Modifier.size(14.dp),
                        tint = if (isError) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerSectionHeader(text: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 13.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        )
    }
}

@Composable
private fun FolderHeader(
    folder: ConversationFolder,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(start = 7.dp, end = 0.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.ExpandMore,
            contentDescription = null,
            modifier = Modifier
                .size(16.dp)
                .rotate(if (expanded) 0f else -90f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            if (expanded) Icons.Rounded.FolderOpen else Icons.Rounded.Folder,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = folder.name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        )
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = "文件夹操作",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SmathMenu(expanded = menu, onDismissRequest = { menu = false }) {
                SmathMenuItem(
                    text = "重命名",
                    icon = Icons.Rounded.Edit,
                    onClick = {
                        menu = false
                        onRename()
                    }
                )
                SmathMenuItem(
                    text = "删除文件夹",
                    icon = Icons.Rounded.Delete,
                    destructive = true,
                    onClick = {
                        menu = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    selected: Boolean,
    indented: Boolean = false,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (indented) 22.dp else 0.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
        else Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(start = 9.dp, end = 0.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (conversation.pinned) {
                        Icon(
                            Icons.Rounded.PushPin,
                            contentDescription = null,
                            modifier = Modifier.size(11.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(3.dp))
                    }
                    Text(
                        text = conversation.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = TimeUtils.formatRelative(conversation.updatedAt),
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = "对话操作",
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SmathMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    SmathMenuItem(
                        text = "重命名",
                        icon = Icons.Rounded.Edit,
                        onClick = {
                            menu = false
                            onRename()
                        }
                    )
                    SmathMenuItem(
                        text = if (conversation.pinned) "取消置顶" else "置顶",
                        icon = Icons.Rounded.PushPin,
                        onClick = {
                            menu = false
                            onPin()
                        }
                    )
                    SmathMenuItem(
                        text = "移动到文件夹",
                        icon = Icons.Rounded.DriveFileMove,
                        onClick = {
                            menu = false
                            onMove()
                        }
                    )
                    SmathMenuItem(
                        text = "删除",
                        icon = Icons.Rounded.Delete,
                        destructive = true,
                        onClick = {
                            menu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    placeholder: String = "",
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    SmathTextFieldDialog(
        title = title,
        label = label,
        initial = initial,
        placeholder = placeholder,
        confirmLabel = confirmLabel,
        icon = Icons.Rounded.Edit,
        onDismiss = onDismiss,
        onConfirm = onConfirm
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    SmathConfirmDialog(
        title = title,
        message = message,
        confirmLabel = confirmLabel,
        destructive = destructive,
        icon = Icons.Rounded.Delete,
        onDismiss = onDismiss,
        onConfirm = onConfirm
    )
}

@Composable
private fun MoveFolderDialog(
    folders: List<ConversationFolder>,
    selectedFolderId: String?,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(vertical = 18.dp)) {
                Text(
                    "移动到文件夹",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 22.dp)
                )
                Text(
                    "选择目标文件夹，或移到未分类",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp)
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                ) {
                    item {
                        MoveRow(
                            name = "未分类",
                            selected = selectedFolderId == null,
                            onClick = { onSelect(null) }
                        )
                    }
                    items(folders, key = { it.id }) { folder ->
                        MoveRow(
                            name = folder.name,
                            selected = selectedFolderId == folder.id,
                            onClick = { onSelect(folder.id) }
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            }
        }
    }
}

@Composable
private fun MoveRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.Folder,
            contentDescription = null,
            modifier = Modifier.size(19.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(11.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = "已选择",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}