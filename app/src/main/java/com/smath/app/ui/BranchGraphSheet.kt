package com.smath.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.smath.app.data.BranchNode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 节点框宽度（dp）。 */
private const val NODE_WIDTH_DP = 132f

/** 节点高度兜底估值（dp）：未测量到真实高度前用它画连线。 */
private const val NODE_ESTIMATED_HEIGHT_DP = 60f

/** 拖动判定阈值（dp）：超过它算「拖动」，不超过算「长按查看详情」。 */
private const val DRAG_THRESHOLD_DP = 8f

/** 画布平移限制（dp），避免把内容拖丢。 */
private const val PAN_LIMIT_DP = 1200f

/** 长按触发后多久以内的单击视为长按手势的一部分，直接忽略（毫秒）。 */
private const val TAP_SUPPRESS_WINDOW_MS = 600L

/**
 * 复杂分支对话的「对话拓扑」面板。
 *
 * 坐标约定：`BranchNode.x / y` 是节点框**左上角**在画布内容坐标系里的位置，单位 dp，
 * 原点在画布内容左上角（与 ChatViewModel.layoutNodes() 的自动布局一致：
 * x = 叶子序号 * 158dp，y = 层深 * 126dp）。画布平移量单独记录，不写回数据。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BranchGraphSheet(
    nodes: List<BranchNode>,
    currentNodeId: String?,
    pendingParentIds: List<String>,
    onDismiss: () -> Unit,
    onCreateBranch: (List<String>) -> Unit,
    onDeleteNode: (String) -> Unit,
    onMoveNode: (String, Float, Float) -> Unit,
    onJumpToNode: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val density = LocalDensity.current.density

    /**
     * 平移上限跟着内容范围走：节点多时图可能宽到几千 dp，
     * 固定上限会导致远端节点永远拖不到。至少保留 PAN_LIMIT_DP 的余量。
     */
    val panLimitPx = remember(nodes, density) {
        var extentDp = PAN_LIMIT_DP
        nodes.forEach { node ->
            val x = node.x ?: return@forEach
            val y = node.y ?: return@forEach
            extentDp = max(extentDp, max(x + NODE_WIDTH_DP, y + NODE_ESTIMATED_HEIGHT_DP))
        }
        extentDp * density
    }

    val surfaceColor = MaterialTheme.colorScheme.surface
    val variantColor = MaterialTheme.colorScheme.surfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary
    val outlineColor = MaterialTheme.colorScheme.outline
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant

    /** 画布平移量（px，仅界面状态）。 */
    var pan by remember { mutableStateOf(Offset.Zero) }

    /** 拖动中的临时位移（px，key = 节点 id）；松手才写回 onMoveNode。 */
    val dragPx = remember { mutableStateMapOf<String, Offset>() }

    /** 正在被拖动的节点，用于提高绘制层级。 */
    var draggingNodeId by remember { mutableStateOf<String?>(null) }

    /** 多选集合。 */
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    /** 详情弹窗对应的节点。 */
    var detailNodeId by remember { mutableStateOf<String?>(null) }

    /** 节点实测高度（px），连线用它算「父节点框底部中心」。 */
    val nodeHeights = remember { mutableStateMapOf<String, Int>() }

    /** 画布可视区尺寸（px）。 */
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    /** 当前分支路径上的节点 id（含 pendingParentIds 各自回溯的并集）。 */
    val pathNodeIds: Set<String> = remember(nodes, currentNodeId, pendingParentIds) {
        val byId = nodes.associateBy { it.id }
        val roots = if (pendingParentIds.isNotEmpty()) pendingParentIds else listOfNotNull(currentNodeId)
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        roots.forEach { queue.addLast(it) }
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!seen.add(id)) continue
            byId[id]?.parentIds?.forEach { parentId -> queue.addLast(parentId) }
        }
        seen.filter { byId.containsKey(it) }.toSet()
    }

    /** 把内容包围盒居中到可视区（同时把平移量重置为居中所需的量）。 */
    fun fitContent() {
        if (nodes.isEmpty() || viewport == IntSize.Zero) {
            pan = Offset.Zero
            return
        }
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var found = false
        nodes.forEach { node ->
            val x = node.x ?: return@forEach
            val y = node.y ?: return@forEach
            found = true
            val heightDp = (nodeHeights[node.id] ?: 0).let { if (it > 0) it / density else NODE_ESTIMATED_HEIGHT_DP }
            minX = min(minX, x)
            maxX = max(maxX, x + NODE_WIDTH_DP)
            minY = min(minY, y)
            maxY = max(maxY, y + heightDp)
        }
        if (!found) {
            pan = Offset.Zero
            return
        }
        val centerX = ((minX + maxX) / 2f) * density
        val centerY = ((minY + maxY) / 2f) * density
        pan = Offset(
            (viewport.width / 2f - centerX).coerceIn(-panLimitPx, panLimitPx),
            (viewport.height / 2f - centerY).coerceIn(-panLimitPx, panLimitPx)
        )
    }

    // 节点被删除后清理选中/详情/拖动态，避免残留 id 影响按钮可用性。
    LaunchedEffect(nodes) {
        val ids = nodes.map { it.id }.toSet()
        if (selected.any { it !in ids }) selected = selected.filter { it in ids }.toSet()
        val openDetailId = detailNodeId
        if (openDetailId != null && openDetailId !in ids) detailNodeId = null
        dragPx.keys.filter { it !in ids }.forEach { dragPx.remove(it) }
    }

    // 首次拿到节点与尺寸后自动居中一次。
    var didInitialFit by remember { mutableStateOf(false) }
    LaunchedEffect(viewport, nodes.size) {
        if (!didInitialFit && nodes.isNotEmpty() && viewport != IntSize.Zero) {
            fitContent()
            didInitialFit = true
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = surfaceColor,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.74f)
                .navigationBarsPadding()
        ) {
            // 头部
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "对话拓扑",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "长按查看详情 · 拖动调整位置 · 点击多选后合并建分支",
                        style = MaterialTheme.typography.labelMedium,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = onSurfaceVariantColor
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "关闭",
                        modifier = Modifier.size(20.dp),
                        tint = onSurfaceVariantColor
                    )
                }
            }

            // 画布：单 Canvas 画线 + 绝对定位的节点框
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(variantColor.copy(alpha = 0.45f))
                    .onSizeChanged { viewport = it }
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            // 拖节点时由节点手势接管，画布不平移
                            if (draggingNodeId == null) {
                                pan = Offset(
                                    (pan.x + dragAmount.x).coerceIn(-panLimitPx, panLimitPx),
                                    (pan.y + dragAmount.y).coerceIn(-panLimitPx, panLimitPx)
                                )
                            }
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    if (nodes.isNotEmpty()) {
                        val widthPx = NODE_WIDTH_DP * density
                        val fallbackHeightPx = NODE_ESTIMATED_HEIGHT_DP * density
                        val positions = HashMap<String, Offset>(nodes.size)
                        nodes.forEach { node ->
                            val nx = node.x ?: return@forEach
                            val ny = node.y ?: return@forEach
                            val delta = dragPx[node.id] ?: Offset.Zero
                            positions[node.id] = Offset(nx * density + delta.x, ny * density + delta.y)
                        }
                        translate(pan.x, pan.y) {
                            nodes.forEach node@{ child ->
                                val childPos = positions[child.id] ?: return@node
                                if (child.parentIds.isEmpty()) return@node
                                child.parentIds.forEach edge@{ parentId ->
                                    val parentPos = positions[parentId] ?: return@edge
                                    val parentHeight =
                                        (nodeHeights[parentId] ?: fallbackHeightPx.roundToInt()).toFloat()
                                    // 父节点框底部中心 -> 子节点框顶部中心
                                    val start = Offset(parentPos.x + widthPx / 2f, parentPos.y + parentHeight)
                                    val end = Offset(childPos.x + widthPx / 2f, childPos.y)
                                    val onCurrentPath =
                                        child.id in pathNodeIds && parentId in pathNodeIds
                                    val strokeColor = if (onCurrentPath) primaryColor else outlineColor
                                    val strokeWidth = if (onCurrentPath) 2.dp.toPx() else 1.5.dp.toPx()
                                    val path = Path()
                                    path.moveTo(start.x, start.y)
                                    if (end.y > start.y + 1f) {
                                        // 控制点在父节点正下方 45% 处，分叉看起来自然
                                        path.quadraticBezierTo(
                                            start.x,
                                            start.y + (end.y - start.y) * 0.45f,
                                            end.x,
                                            end.y
                                        )
                                    } else {
                                        path.quadraticBezierTo(
                                            (start.x + end.x) / 2f,
                                            (start.y + end.y) / 2f,
                                            end.x,
                                            end.y
                                        )
                                    }
                                    drawPath(
                                        path = path,
                                        color = strokeColor,
                                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                    )
                                }
                            }
                        }
                    }
                }

                nodes.forEachIndexed { index, node ->
                    val isDragging = draggingNodeId == node.id
                    BranchNodeCard(
                        node = node,
                        index = index + 1,
                        isEditing = node.id == currentNodeId,
                        isSelected = node.id in selected,
                        isPendingParent = pendingParentIds.contains(node.id),
                        modifier = Modifier
                            .offset {
                                val delta = dragPx[node.id] ?: Offset.Zero
                                IntOffset(
                                    ((node.x ?: 0f) * density + pan.x + delta.x).roundToInt(),
                                    ((node.y ?: 0f) * density + pan.y + delta.y).roundToInt()
                                )
                            }
                            .zIndex(if (isDragging) 2f else 1f),
                        onMeasured = { height -> nodeHeights[node.id] = height },
                        onTap = {
                            selected = if (node.id in selected) selected - node.id else selected + node.id
                        },
                        onDragStart = {
                            draggingNodeId = node.id
                            dragPx[node.id] = Offset.Zero
                        },
                        onDrag = { delta ->
                            dragPx[node.id] = (dragPx[node.id] ?: Offset.Zero) + delta
                        },
                        onDragEnd = {
                            val total = dragPx[node.id] ?: Offset.Zero
                            dragPx.remove(node.id)
                            draggingNodeId = null
                            if (total.getDistance() / density > DRAG_THRESHOLD_DP) {
                                onMoveNode(
                                    node.id,
                                    (node.x ?: 0f) + total.x / density,
                                    (node.y ?: 0f) + total.y / density
                                )
                            } else {
                                // 长按后没有移动 = 查看详情
                                detailNodeId = node.id
                            }
                        },
                        onDragCancel = {
                            dragPx.remove(node.id)
                            draggingNodeId = null
                        }
                    )
                }

                if (nodes.isEmpty()) {
                    Text(
                        text = "还没有节点，先问一个问题吧",
                        style = MaterialTheme.typography.bodyMedium,
                        color = onSurfaceVariantColor,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                // 适配按钮
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(surfaceColor.copy(alpha = 0.94f))
                        .clickable { fitContent() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CenterFocusStrong,
                        contentDescription = "适配画布",
                        modifier = Modifier.size(17.dp),
                        tint = onSurfaceVariantColor
                    )
                }
            }

            // 底部固定操作栏
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when {
                        selected.isEmpty() -> "点节点选中 · 选 1 个新建分支，选多个合并建分支"
                        selected.size == 1 -> "已选 1 个节点，可从这里新建分支"
                        else -> "已选 ${selected.size} 个节点，将合并上游上下文"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = if (selected.isEmpty()) onSurfaceVariantColor else primaryColor,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = { selected.firstOrNull()?.let { onDeleteNode(it) } },
                    enabled = selected.size == 1,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(
                        text = "删除节点",
                        fontSize = 12.sp,
                        color = if (selected.size == 1) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.38f)
                        }
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        onCreateBranch(selected.toList())
                        selected = emptySet()
                        onDismiss()
                    },
                    enabled = selected.size >= 1,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(text = if (selected.size > 1) "合并建分支" else "新建分支", fontSize = 12.sp)
                }
            }
        }
    }

    val detailNode = nodes.firstOrNull { it.id == detailNodeId }
    if (detailNode != null) {
        BranchNodeDetailDialog(
            node = detailNode,
            index = nodes.indexOfFirst { it.id == detailNode.id } + 1,
            onDismiss = { detailNodeId = null },
            onJump = {
                onJumpToNode(detailNode.id)
                detailNodeId = null
            }
        )
    }
}

/**
 * 单个节点框：单击多选、长按拖动 / 长按看详情。
 * 两个 gesture detector 共存：长按触发后 600ms 内的 onTap 会被忽略，
 * 避免「长按松手」被 detectTapGestures 误判成单击（移动时位移会被 consume，本就不会触发）。
 */
@Composable
private fun BranchNodeCard(
    node: BranchNode,
    index: Int,
    isEditing: Boolean,
    isSelected: Boolean,
    isPendingParent: Boolean,
    modifier: Modifier = Modifier,
    onMeasured: (Int) -> Unit,
    onTap: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    val longPressMark = remember(node.id) { longArrayOf(0L) }
    val shape = RoundedCornerShape(12.dp)
    val primary = MaterialTheme.colorScheme.primary
    val borderColor = if (isEditing || isSelected) primary else MaterialTheme.colorScheme.outlineVariant
    val borderWidth = if (isEditing || isSelected) 2.dp else 1.dp
    // 注意：容器色必须不透明——shadow 画在卡片下方，半透明填充会把阴影透出来变成一团灰。
    // 「正在编辑」用 2dp 主色边框 + 右上圆点表达，不再用 alpha 填充。
    val containerColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        isEditing -> MaterialTheme.colorScheme.surfaceContainerLow
        else -> MaterialTheme.colorScheme.surface
    }
    val label = node.snippet.take(24).ifBlank { "（图片/空问题）" }

    Surface(
        shape = shape,
        color = containerColor,
        border = BorderStroke(borderWidth, borderColor),
        modifier = modifier
            .width(NODE_WIDTH_DP.dp)
            .shadow(3.dp, shape, clip = false)
            .onGloballyPositioned { coordinates -> onMeasured(coordinates.size.height) }
            .pointerInput(node.id) {
                detectTapGestures(onTap = {
                    val now = System.currentTimeMillis()
                    if (now - longPressMark[0] > TAP_SUPPRESS_WINDOW_MS) onTap()
                })
            }
            .pointerInput(node.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        longPressMark[0] = System.currentTimeMillis()
                        onDragStart()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragCancel() }
                )
            }
    ) {
        Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "你 #$index",
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (node.parentIds.size >= 2) {
                    Spacer(Modifier.width(3.dp))
                    Icon(
                        imageVector = Icons.Rounded.CallMerge,
                        contentDescription = "合并节点",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                if (isPendingParent) {
                    Icon(
                        imageVector = Icons.Rounded.CallSplit,
                        contentDescription = "待建分支父节点",
                        modifier = Modifier.size(12.dp),
                        tint = primary
                    )
                }
                if (isSelected) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "已选中",
                        modifier = Modifier.size(14.dp),
                        tint = primary
                    )
                } else if (isEditing) {
                    Spacer(Modifier.width(3.dp))
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(primary)
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.heightIn(min = 28.dp)
            )
        }
    }
}

/** 节点详情：自绘 Dialog（不用 AlertDialog，保持项目自己的面板风格）。 */
@Composable
private fun BranchNodeDetailDialog(
    node: BranchNode,
    index: Int,
    onDismiss: () -> Unit,
    onJump: () -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Surface(
                shape = shape,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .shadow(10.dp, shape, clip = false)
            ) {
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "节点 #$index · 详情",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "关闭",
                                modifier = Modifier.size(17.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Column(
                        modifier = Modifier
                            .heightIn(max = 460.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = "你的问题",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        SelectionContainer {
                            Text(
                                text = node.question.ifBlank { "（图片/空问题）" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (node.question.isBlank()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "AI 的回答",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        SelectionContainer {
                            Text(
                                text = node.answer.ifBlank { "（该节点还没有回答）" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (node.answer.isBlank()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) { Text("关闭") }
                        TextButton(onClick = onJump) { Text("跳到该节点") }
                    }
                }
            }
        }
    }
}