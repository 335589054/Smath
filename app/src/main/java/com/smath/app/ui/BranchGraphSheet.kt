package com.smath.app.ui

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUp
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
import androidx.compose.material.icons.rounded.AutoFixHigh
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

/** 画布缩放范围。 */
private const val MIN_ZOOM = 0.35f
private const val MAX_ZOOM = 2.5f

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
    onJumpToNode: (String) -> Unit,
    onTidy: () -> Unit = {}
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

    /** 画布缩放（双指捏合调整，「归位」会重算成合适大小）。 */
    var zoom by remember { mutableStateOf(1f) }

    /** 节点实测高度（px），连线用它算「父节点框底部中心」。 */
    val nodeHeights = remember { mutableStateMapOf<String, Int>() }

    /** 命中测试：屏幕坐标 → 节点 id（卡片按 zoom 缩放，坐标换算到屏幕空间后比较）。 */
    fun nodeAt(position: Offset): String? {
        var hit: String? = null
        nodes.forEach { item ->
            val x = item.x ?: return@forEach
            val y = item.y ?: return@forEach
            val left = (x * density + pan.x) * zoom
            val top = (y * density + pan.y) * zoom
            val width = NODE_WIDTH_DP * density * zoom
            val height = (nodeHeights[item.id] ?: 0).let {
                if (it > 0) it.toFloat() else NODE_ESTIMATED_HEIGHT_DP * density
            } * zoom
            if (position.x in left..(left + width) && position.y in top..(top + height)) hit = item.id
        }
        return hit
    }

    /** 多选集合。 */
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    /** 详情弹窗对应的节点。 */
    var detailNodeId by remember { mutableStateOf<String?>(null) }

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

    /** 一键整理后自动适配视野。 */
    var pendingFit by remember { mutableStateOf(false) }

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
            zoom = 1f
            return
        }
        val centerX = ((minX + maxX) / 2f) * density
        val centerY = ((minY + maxY) / 2f) * density
        val contentW = (maxX - minX) * density
        val contentH = (maxY - minY) * density
        val margin = 28f
        val availableW = (viewport.width - margin * 2f).coerceAtLeast(1f)
        val availableH = (viewport.height - margin * 2f).coerceAtLeast(1f)
        // 归位：把整张图缩放到正好装进可视区（最多放大到 1.25 倍，避免两个节点被放得过夸张）。
        val fitted = if (contentW <= 1f || contentH <= 1f) {
            MAX_ZOOM.coerceAtMost(1.25f)
        } else {
            min(availableW / contentW, availableH / contentH).coerceIn(MIN_ZOOM, 1.25f)
        }
        zoom = fitted
        // 内容坐标 → 屏幕：(content + pan) * zoom，所以要居中就是把内容中心移到可视区中心。
        val panX = viewport.width / (2f * fitted) - centerX
        val panY = viewport.height / (2f * fitted) - centerY
        pan = Offset(
            panX.coerceIn(-panLimitPx, panLimitPx),
            panY.coerceIn(-panLimitPx, panLimitPx)
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

    // 一键整理后重新居中（节点位置由 ViewModel 重排，等一帧再算包围盒）。
    LaunchedEffect(nodes) {
        if (pendingFit) {
            pendingFit = false
            fitContent()
            // 卡片高度要等一帧才量准，量准后再居中一次，避免整理后图偏到左上角。
            kotlinx.coroutines.delay(90)
            fitContent()
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
                        // 画布级统一处理：命中节点就拖节点（绝对跟踪，完全跟手），否则平移；双指缩放。
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var last = down.position
                            val startPos = down.position
                            val startPan = pan
                            val startZoom = zoom
                            var nodeId = nodeAt(down.position)
                            var moved = false
                            var up = false
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.size > 1) {
                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()
                                    zoom = (startZoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                    pan = Offset(
                                        (startPan.x + panChange.x).coerceIn(-panLimitPx, panLimitPx),
                                        (startPan.y + panChange.y).coerceIn(-panLimitPx, panLimitPx)
                                    )
                                    event.changes.forEach { it.consume() }
                                    moved = true
                                    continue
                                }
                                val change = event.changes.first()
                                if (change.changedToUp()) {
                                    change.consume()
                                    up = true
                                    break
                                }
                                val delta = change.position - last
                                last = change.position
                                if (delta != Offset.Zero) moved = true
                                if (nodeId != null) {
                                    change.consume()
                                    // 绝对跟踪：节点位移 = 手指相对按下点的位移，不做增量累加，避免丢 slop 造成「不跟手」
                                    draggingNodeId = nodeId
                                    dragPx[nodeId!!] = (change.position - startPos) / zoom
                                } else if ((change.position - startPos).getDistance() > viewConfiguration.touchSlop) {
                                    change.consume()
                                    pan = Offset(
                                        (startPan.x + (change.position.x - startPos.x)).coerceIn(-panLimitPx, panLimitPx),
                                        (startPan.y + (change.position.y - startPos.y)).coerceIn(-panLimitPx, panLimitPx)
                                    )
                                }
                            }
                            val dragged = nodeId
                            if (dragged != null) {
                                val total = dragPx[dragged] ?: Offset.Zero
                                dragPx.clear()
                                draggingNodeId = null
                                if (moved && (total.x != 0f || total.y != 0f)) {
                                    val node = nodes.firstOrNull { it.id == dragged }
                                    if (node != null) {
                                        onMoveNode(
                                            dragged,
                                            (node.x ?: 0f) + total.x / density,
                                            (node.y ?: 0f) + total.y / density
                                        )
                                    }
                                } else if (up) {
                                    val held = SystemClock.uptimeMillis() - down.uptimeMillis
                                    if (held >= viewConfiguration.longPressTimeoutMillis) {
                                        detailNodeId = dragged
                                    } else {
                                        selected = if (dragged in selected) selected - dragged else selected + dragged
                                    }
                                }
                            }
                        }
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            transformOrigin = TransformOrigin(0f, 0f)
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
                        onMeasured = { height -> nodeHeights[node.id] = height }
                    )
                }

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

                // 一键整理：清空手动位置，按树形重新分层排布，再自动适配视野
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 44.dp, top = 8.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(surfaceColor.copy(alpha = 0.94f))
                        .clickable {
                            pendingFit = true
                            onTidy()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoFixHigh,
                        contentDescription = "一键整理画布",
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
    onMeasured: (Int) -> Unit
) {
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