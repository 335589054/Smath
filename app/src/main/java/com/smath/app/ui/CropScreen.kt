package com.smath.app.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.smath.app.util.BrushStroke
import com.smath.app.util.ImageUtils
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 图片编辑器：裁剪 + 画笔双工具。
 *
 * 裁剪框用源图归一化坐标（0..1）保存；笔迹同样存在源图归一化坐标系里，
 * 所以两种工具可以任意来回切换而不会丢结果。最终产物 = 按当前裁剪框裁出的区域
 * + 画在该区域上的笔迹（框外部分裁掉）。
 */
@Composable
fun CropScreen(
    sourcePath: String,
    dark: Boolean,
    onCancel: () -> Unit,
    onDone: (String) -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    // 预览与保存都用 2400px 上限，这样顶部显示的像素尺寸与最终产物完全一致。
    val bitmap by produceState<Bitmap?>(initialValue = null, sourcePath) {
        value = withContext(Dispatchers.IO) { ImageUtils.loadBitmap(sourcePath, 2400) }
    }

    var crop by remember(sourcePath) { mutableStateOf(Rect(0f, 0f, 1f, 1f)) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var dragging by remember { mutableStateOf<CropHandle?>(null) }
    var saving by remember { mutableStateOf(false) }

    // 工具状态
    var tool by remember(sourcePath) { mutableStateOf(EditorTool.CROP) }
    var strokes by remember(sourcePath) { mutableStateOf<List<BrushStroke>>(emptyList()) }
    var brushColor by remember { mutableStateOf(BrushColor.WHITE) }
    var brushWidth by remember { mutableStateOf(8f) }

    // 正在画的这一笔（源图归一化坐标）
    var activePoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var activeColor by remember { mutableStateOf(Color.White) }
    var activeWidthRatio by remember { mutableStateOf(0.004f) }

    fun commitActiveStroke() {
        val points = activePoints
        if (points.isNotEmpty()) {
            strokes = strokes + BrushStroke(
                colorArgb = activeColor.toArgb(),
                widthRatio = activeWidthRatio,
                points = points
            )
        }
        activePoints = emptyList()
    }

    BackHandler(enabled = !saving, onBack = onCancel)

    LaunchedEffect(Unit) {
        focusManager.clearFocus()
        keyboard?.hide()
        // 相机返回时窗口焦点恢复可能会让 IME 再弹一次，补一次延迟收起。
        view.postDelayed({ keyboard?.hide() }, 160L)
    }

    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.hide(WindowInsetsCompat.Type.ime())
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            controller?.isAppearanceLightStatusBars = !dark
            controller?.isAppearanceLightNavigationBars = !dark
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel, enabled = !saving) {
                Icon(Icons.Rounded.Close, contentDescription = "取消", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "编辑图片",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                val size = bitmap
                if (size != null) {
                    val cropText = "${(crop.width * size.width).roundToInt()} × " +
                        "${(crop.height * size.height).roundToInt()} px"
                    Text(
                        if (strokes.isEmpty()) cropText else "$cropText · ${strokes.size} 笔",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 12.sp
                    )
                }
            }
            IconButton(
                onClick = { crop = Rect(0f, 0f, 1f, 1f) },
                enabled = !saving
            ) {
                Icon(Icons.Rounded.Restore, contentDescription = "全选", tint = Color.White)
            }
        }

        // 工具区：分段切换 + 画笔面板
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 2.dp)
        ) {
            ToolSwitcher(
                tool = tool,
                enabled = !saving,
                onSelect = { tool = it }
            )
            if (tool == EditorTool.BRUSH) {
                Spacer(Modifier.height(8.dp))
                BrushPanel(
                    selected = brushColor,
                    width = brushWidth,
                    enabled = !saving,
                    canUndo = strokes.isNotEmpty(),
                    onSelect = { picked ->
                        brushColor = picked
                        // 换色后粗细不得超过该色上限（白色上限更大）。
                        brushWidth = brushWidth.coerceIn(BRUSH_MIN_WIDTH, picked.maxWidth)
                    },
                    onWidth = { brushWidth = it },
                    onUndo = { if (strokes.isNotEmpty()) strokes = strokes.dropLast(1) }
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // 给左右系统手势留出安全边距，避免拖动裁剪框边缘时触发返回手势。
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .onSizeChanged {
                    canvasSize = Size(it.width.toFloat(), it.height.toFloat())
                }
        ) {
            val bmp = bitmap
            if (bmp == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
            } else {
                val imageBitmap = remember(bmp) { bmp.asImageBitmap() }
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(bmp, canvasSize, tool) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    val image = imageBounds(canvasSize, bmp)
                                    if (image.width <= 0f || image.height <= 0f) {
                                        dragging = null
                                        activePoints = emptyList()
                                    } else if (tool == EditorTool.BRUSH) {
                                        dragging = null
                                        activeColor = brushColor.color
                                        activeWidthRatio =
                                            (with(density) { brushWidth.dp.toPx() } / image.width)
                                                .coerceAtLeast(0.0005f)
                                        activePoints = listOf(toNormalized(start, image))
                                    } else {
                                        activePoints = emptyList()
                                        dragging = hitTest(
                                            start = start,
                                            image = image,
                                            cropPx = toPixels(crop, image),
                                            cornerRadius = 32.dp.toPx(),
                                            edgeRadius = 18.dp.toPx()
                                        )
                                    }
                                },
                                onDragEnd = {
                                    if (tool == EditorTool.BRUSH) commitActiveStroke()
                                    dragging = null
                                },
                                onDragCancel = {
                                    if (tool == EditorTool.BRUSH) commitActiveStroke()
                                    dragging = null
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    if (tool == EditorTool.BRUSH) {
                                        val image = imageBounds(canvasSize, bmp)
                                        if (image.width > 0f && image.height > 0f) {
                                            val point = toNormalized(change.position, image)
                                            val last = activePoints.lastOrNull()
                                            // 采点抽稀，避免笔迹点列表无限增长。
                                            if (last == null ||
                                                (point - last).getDistance() > 0.0018f
                                            ) {
                                                activePoints = activePoints + point
                                            }
                                        }
                                        return@detectDragGestures
                                    }
                                    val handle = dragging ?: return@detectDragGestures
                                    val image = imageBounds(canvasSize, bmp)
                                    if (image.width <= 0f || image.height <= 0f) {
                                        return@detectDragGestures
                                    }
                                    crop = applyDrag(
                                        crop = crop,
                                        handle = handle,
                                        dx = amount.x / image.width,
                                        dy = amount.y / image.height,
                                        minSize = (72.dp.toPx() / min(image.width, image.height))
                                            .coerceAtMost(0.3f)
                                    )
                                }
                            )
                        }
                ) {
                    val image = imageBounds(size, bmp)
                    if (image.width <= 0f || image.height <= 0f) return@Canvas

                    drawImage(
                        image = imageBitmap,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(bmp.width, bmp.height),
                        dstOffset = IntOffset(image.left.roundToInt(), image.top.roundToInt()),
                        dstSize = IntSize(image.width.roundToInt(), image.height.roundToInt()),
                        filterQuality = FilterQuality.High
                    )

                    val cropPx = toPixels(crop, image)

                    // 笔迹：一律按当前裁剪框裁掉框外部分 —— 所见即保存结果。
                    clipRect(cropPx.left, cropPx.top, cropPx.right, cropPx.bottom) {
                        strokes.forEach { stroke ->
                            drawBrushStroke(
                                points = stroke.points,
                                image = image,
                                color = Color(stroke.colorArgb),
                                widthPx = stroke.widthRatio * image.width
                            )
                        }
                        if (tool == EditorTool.BRUSH && activePoints.isNotEmpty()) {
                            drawBrushStroke(
                                points = activePoints,
                                image = image,
                                color = activeColor,
                                widthPx = activeWidthRatio * image.width
                            )
                        }
                    }

                    if (tool == EditorTool.CROP) {
                        val scrim = Color.Black.copy(alpha = 0.58f)
                        drawRect(scrim, Offset.Zero, Size(size.width, cropPx.top))
                        drawRect(
                            scrim,
                            Offset(0f, cropPx.bottom),
                            Size(size.width, (size.height - cropPx.bottom).coerceAtLeast(0f))
                        )
                        drawRect(
                            scrim,
                            Offset(0f, cropPx.top),
                            Size(cropPx.left, cropPx.height)
                        )
                        drawRect(
                            scrim,
                            Offset(cropPx.right, cropPx.top),
                            Size((size.width - cropPx.right).coerceAtLeast(0f), cropPx.height)
                        )

                        val line = Color.White.copy(alpha = 0.35f)
                        for (i in 1..2) {
                            val x = cropPx.left + cropPx.width * i / 3f
                            drawLine(line, Offset(x, cropPx.top), Offset(x, cropPx.bottom), 1.dp.toPx())
                            val y = cropPx.top + cropPx.height * i / 3f
                            drawLine(line, Offset(cropPx.left, y), Offset(cropPx.right, y), 1.dp.toPx())
                        }

                        drawRect(
                            color = Color.White,
                            topLeft = cropPx.topLeft,
                            size = cropPx.size,
                            style = Stroke(width = 1.8.dp.toPx())
                        )

                        val handleRadius = 5.5.dp.toPx()
                        listOf(
                            cropPx.topLeft,
                            cropPx.topRight,
                            cropPx.bottomLeft,
                            cropPx.bottomRight,
                            Offset(cropPx.center.x, cropPx.top),
                            Offset(cropPx.center.x, cropPx.bottom),
                            Offset(cropPx.left, cropPx.center.y),
                            Offset(cropPx.right, cropPx.center.y)
                        ).forEach { point ->
                            drawCircle(Color.White, handleRadius, point)
                            drawCircle(
                                Color.Black.copy(alpha = 0.35f),
                                handleRadius,
                                point,
                                style = Stroke(1.dp.toPx())
                            )
                        }
                    } else {
                        // 画笔工具：裁剪框淡显提示「只有框内会被保存」，不显示把手。
                        val scrim = Color.Black.copy(alpha = 0.34f)
                        drawRect(scrim, Offset.Zero, Size(size.width, cropPx.top))
                        drawRect(
                            scrim,
                            Offset(0f, cropPx.bottom),
                            Size(size.width, (size.height - cropPx.bottom).coerceAtLeast(0f))
                        )
                        drawRect(
                            scrim,
                            Offset(0f, cropPx.top),
                            Size(cropPx.left, cropPx.height)
                        )
                        drawRect(
                            scrim,
                            Offset(cropPx.right, cropPx.top),
                            Size((size.width - cropPx.right).coerceAtLeast(0f), cropPx.height)
                        )
                        drawRect(
                            color = Color.White.copy(alpha = 0.5f),
                            topLeft = cropPx.topLeft,
                            size = cropPx.size,
                            style = Stroke(width = 1.2.dp.toPx())
                        )
                    }
                }
            }

            if (saving) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.1f)) {
                    Icon(
                        if (tool == EditorTool.CROP) Icons.Rounded.Crop else Icons.Rounded.Brush,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier
                            .padding(7.dp)
                            .size(15.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (tool == EditorTool.CROP) {
                        "拖动边缘裁剪，拖动内部移动选区。"
                    } else {
                        "在画面内涂抹，白色可覆盖不需要的内容。"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Default
                )
            }
            Spacer(Modifier.size(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { onCancel() },
                    enabled = !saving,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.7f))
                ) {
                    Text("取消")
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        if (saving) return@Button
                        saving = true
                        val snapshot = strokes
                        val cropSnapshot = crop
                        scope.launch {
                            val path = withContext(Dispatchers.IO) {
                                ImageUtils.saveEdited(context, sourcePath, cropSnapshot, snapshot)
                            }
                            saving = false
                            if (path == null) {
                                Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_SHORT).show()
                            } else {
                                onDone(path)
                            }
                        }
                    },
                    enabled = bitmap != null && !saving,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    )
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("完成", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
        }
    }
}

private const val BRUSH_MIN_WIDTH = 1f

private enum class EditorTool { CROP, BRUSH }

/** 画笔颜色预设；白色用于覆盖不需要的内容，因此允许更大的粗细上限。 */
private enum class BrushColor(
    val argb: Int,
    val label: String,
    val maxWidth: Float
) {
    RED(0xFFEF4B4B.toInt(), "红", 40f),
    WHITE(0xFFFFFFFF.toInt(), "白", 96f),
    BLUE(0xFF4D6BFE.toInt(), "蓝", 40f),
    BLACK(0xFF15171A.toInt(), "黑", 40f);

    val color: Color get() = Color(argb)
}

@Composable
private fun ToolSwitcher(
    tool: EditorTool,
    enabled: Boolean,
    onSelect: (EditorTool) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.07f)
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            ToolChip(
                label = "裁剪",
                selected = tool == EditorTool.CROP,
                enabled = enabled,
                icon = { tint ->
                    Icon(
                        Icons.Rounded.Crop,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(15.dp)
                    )
                },
                onClick = { onSelect(EditorTool.CROP) }
            )
            ToolChip(
                label = "画笔",
                selected = tool == EditorTool.BRUSH,
                enabled = enabled,
                icon = { tint ->
                    Icon(
                        Icons.Rounded.Brush,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(15.dp)
                    )
                },
                onClick = { onSelect(EditorTool.BRUSH) }
            )
        }
    }
}

@Composable
private fun ToolChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    icon: @Composable (Color) -> Unit,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(11.dp)
    val content = if (selected) Color.Black else Color.White.copy(alpha = 0.72f)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) Color.White else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon(content)
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = content
        )
    }
}

@Composable
private fun BrushPanel(
    selected: BrushColor,
    width: Float,
    enabled: Boolean,
    canUndo: Boolean,
    onSelect: (BrushColor) -> Unit,
    onWidth: (Float) -> Unit,
    onUndo: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.07f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrushColor.values().forEach { entry ->
                    BrushSwatch(
                        entry = entry,
                        selected = entry == selected,
                        enabled = enabled,
                        onClick = { onSelect(entry) }
                    )
                    Spacer(Modifier.width(2.dp))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onUndo, enabled = enabled && canUndo) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Undo,
                        contentDescription = "撤销",
                        tint = if (canUndo && enabled) Color.White.copy(alpha = 0.85f)
                        else Color.White.copy(alpha = 0.25f),
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "粗细",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.55f)
                )
                Slider(
                    value = width.coerceIn(BRUSH_MIN_WIDTH, selected.maxWidth),
                    onValueChange = onWidth,
                    valueRange = BRUSH_MIN_WIDTH..selected.maxWidth,
                    enabled = enabled,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = selected.color,
                        inactiveTrackColor = Color.White.copy(alpha = 0.18f)
                    )
                )
                // 粗细视觉预览：白/黑在深色面板上需要描边才看得见；超过 30dp 只显示数值。
                Box(
                    modifier = Modifier.size(34.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val diameter = width.coerceIn(BRUSH_MIN_WIDTH, 30f)
                    Box(
                        modifier = Modifier
                            .size(diameter.dp)
                            .clip(CircleShape)
                            .background(selected.color)
                            .border(
                                width = 1.dp,
                                color = Color.White.copy(
                                    alpha = if (selected == BrushColor.BLACK || selected == BrushColor.WHITE) 0.35f else 0f
                                ),
                                shape = CircleShape
                            )
                    )
                }
                Text(
                    "${width.roundToInt()}",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(24.dp)
                )
            }
        }
    }
}

@Composable
private fun BrushSwatch(
    entry: BrushColor,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (selected) Color.White.copy(alpha = 0.2f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(entry.color)
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = if (entry == BrushColor.BLACK) 0.4f else 0.15f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = entry.label,
                    tint = if (entry == BrushColor.WHITE) Color.Black else Color.White,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

private enum class CropHandle {
    MOVE, LEFT, TOP, RIGHT, BOTTOM,
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
}

/** 一笔笔迹：点与点之间连线，圆头 cap，单点时画圆点。内部按 [image] 就地换算，避免每帧分配列表。 */
private fun DrawScope.drawBrushStroke(
    points: List<Offset>,
    image: Rect,
    color: Color,
    widthPx: Float
) {
    if (points.isEmpty() || image.width <= 0f || image.height <= 0f) return
    val width = widthPx.coerceAtLeast(1f)
    if (points.size == 1) {
        drawCircle(color, width / 2f, toDisplay(points[0], image))
        return
    }
    var previous = toDisplay(points[0], image)
    for (i in 1 until points.size) {
        val current = toDisplay(points[i], image)
        drawLine(
            color = color,
            start = previous,
            end = current,
            strokeWidth = width,
            cap = StrokeCap.Round
        )
        previous = current
    }
}

private fun imageBounds(canvas: Size, bitmap: Bitmap): Rect {
    if (canvas.width <= 0f || canvas.height <= 0f) return Rect.Zero
    val scale = min(canvas.width / bitmap.width, canvas.height / bitmap.height)
    val width = bitmap.width * scale
    val height = bitmap.height * scale
    val left = (canvas.width - width) / 2f
    val top = (canvas.height - height) / 2f
    return Rect(left, top, left + width, top + height)
}

private fun toPixels(crop: Rect, image: Rect): Rect = Rect(
    image.left + crop.left * image.width,
    image.top + crop.top * image.height,
    image.left + crop.right * image.width,
    image.top + crop.bottom * image.height
)

/** 画布坐标 → 源图归一化坐标（拖出画面时夹到边缘）。 */
private fun toNormalized(point: Offset, image: Rect): Offset = Offset(
    ((point.x - image.left) / image.width).coerceIn(0f, 1f),
    ((point.y - image.top) / image.height).coerceIn(0f, 1f)
)

/** 源图归一化坐标 → 画布坐标。 */
private fun toDisplay(point: Offset, image: Rect): Offset = Offset(
    image.left + point.x * image.width,
    image.top + point.y * image.height
)

private fun hitTest(
    start: Offset,
    image: Rect,
    cropPx: Rect,
    cornerRadius: Float,
    edgeRadius: Float
): CropHandle? {
    if (!image.contains(start)) return null
    val nearLeft = abs(start.x - cropPx.left) <= cornerRadius
    val nearRight = abs(start.x - cropPx.right) <= cornerRadius
    val nearTop = abs(start.y - cropPx.top) <= cornerRadius
    val nearBottom = abs(start.y - cropPx.bottom) <= cornerRadius
    val withinX = start.x >= cropPx.left - edgeRadius && start.x <= cropPx.right + edgeRadius
    val withinY = start.y >= cropPx.top - edgeRadius && start.y <= cropPx.bottom + edgeRadius

    return when {
        nearLeft && nearTop -> CropHandle.TOP_LEFT
        nearRight && nearTop -> CropHandle.TOP_RIGHT
        nearLeft && nearBottom -> CropHandle.BOTTOM_LEFT
        nearRight && nearBottom -> CropHandle.BOTTOM_RIGHT
        nearLeft && withinY -> CropHandle.LEFT
        nearRight && withinY -> CropHandle.RIGHT
        nearTop && withinX -> CropHandle.TOP
        nearBottom && withinX -> CropHandle.BOTTOM
        start.x > cropPx.left && start.x < cropPx.right &&
            start.y > cropPx.top && start.y < cropPx.bottom -> CropHandle.MOVE
        else -> null
    }
}

private fun applyDrag(
    crop: Rect,
    handle: CropHandle,
    dx: Float,
    dy: Float,
    minSize: Float
): Rect {
    if (handle == CropHandle.MOVE) {
        val newLeft = (crop.left + dx).coerceIn(0f, 1f - crop.width)
        val newTop = (crop.top + dy).coerceIn(0f, 1f - crop.height)
        return Rect(newLeft, newTop, newLeft + crop.width, newTop + crop.height)
    }

    var left = crop.left
    var top = crop.top
    var right = crop.right
    var bottom = crop.bottom

    when (handle) {
        CropHandle.LEFT, CropHandle.TOP_LEFT, CropHandle.BOTTOM_LEFT ->
            left = (crop.left + dx).coerceIn(0f, right - minSize)
        else -> Unit
    }
    when (handle) {
        CropHandle.RIGHT, CropHandle.TOP_RIGHT, CropHandle.BOTTOM_RIGHT ->
            right = (crop.right + dx).coerceIn(left + minSize, 1f)
        else -> Unit
    }
    when (handle) {
        CropHandle.TOP, CropHandle.TOP_LEFT, CropHandle.TOP_RIGHT ->
            top = (crop.top + dy).coerceIn(0f, bottom - minSize)
        else -> Unit
    }
    when (handle) {
        CropHandle.BOTTOM, CropHandle.BOTTOM_LEFT, CropHandle.BOTTOM_RIGHT ->
            bottom = (crop.bottom + dy).coerceIn(top + minSize, 1f)
        else -> Unit
    }
    return Rect(left, top, right, bottom)
}