package com.smath.app.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.smath.app.util.ImageUtils
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A deliberately simple, direct-manipulation crop screen.
 *
 * The selection starts at the full image. Drag inside to move it, drag an edge
 * or corner to resize. Nothing is uploaded: the result is re-encoded into the
 * app's private images directory.
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
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val bitmap by produceState<Bitmap?>(initialValue = null, sourcePath) {
        value = withContext(Dispatchers.IO) { ImageUtils.loadBitmap(sourcePath, 2600) }
    }

    var crop by remember(sourcePath) { mutableStateOf(Rect(0f, 0f, 1f, 1f)) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var dragging by remember { mutableStateOf<CropHandle?>(null) }
    var saving by remember { mutableStateOf(false) }

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
                    "裁剪图片",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
                val size = bitmap
                if (size != null) {
                    Text(
                        "${(crop.width * size.width).roundToInt()} × " +
                            "${(crop.height * size.height).roundToInt()} px",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.55f)
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
                        .pointerInput(bmp, canvasSize) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    val image = imageBounds(canvasSize, bmp)
                                    if (image.width <= 0f || image.height <= 0f) {
                                        dragging = null
                                    } else {
                                        dragging = hitTest(
                                            start = start,
                                            image = image,
                                            cropPx = toPixels(crop, image),
                                            cornerRadius = 32.dp.toPx(),
                                            edgeRadius = 18.dp.toPx()
                                        )
                                    }
                                },
                                onDragEnd = { dragging = null },
                                onDragCancel = { dragging = null },
                                onDrag = { change, amount ->
                                    change.consume()
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
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.1f)) {
                    Icon(
                        Icons.Rounded.Crop,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier
                            .padding(7.dp)
                            .size(15.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "默认保留整张图片。拖动边缘裁剪，拖动内部移动选区。",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.55f),
                    fontFamily = FontFamily.Default
                )
            }
            Spacer(Modifier.size(12.dp))
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
                        scope.launch {
                            val path = withContext(Dispatchers.IO) {
                                ImageUtils.saveCropped(context, sourcePath, crop)
                            }
                            saving = false
                            if (path == null) {
                                Toast.makeText(context, "裁剪失败，请重试", Toast.LENGTH_SHORT).show()
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

private enum class CropHandle {
    MOVE, LEFT, TOP, RIGHT, BOTTOM,
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
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