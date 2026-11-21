package com.smath.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smath.app.agent.ComplexNumber
import com.smath.app.agent.PaintCommand
import com.smath.app.agent.PaintDocument
import com.smath.app.agent.PaintError
import com.smath.app.agent.evalMath
import com.smath.app.agent.formatComplex
import com.smath.app.agent.parsePaint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private val specialBlockRegex = Regex(
    """\[\$\$(paint|calc)_start\$\$\]([\s\S]*?)(?:\[\$\$\1_end\$\$\]|$)"""
)

@Composable
fun SpecialAgentContent(
    markdown: String,
    dark: Boolean,
    drawingCanvasSize: Int,
    streaming: Boolean,
    modifier: Modifier = Modifier
) {
    // 绘图/计算器的开关只决定是否向模型注入协议提示词；
    // 解析本身始终开启，否则关闭开关后历史会话里的图表会突然消失。
    if (markdown.isBlank()) {
        MarkdownView(markdown = markdown, dark = dark, modifier = modifier)
        return
    }
    val matches = specialBlockRegex.findAll(markdown).toList()
    if (matches.isEmpty()) {
        MarkdownView(markdown = markdown, dark = dark, modifier = modifier)
        return
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        var cursor = 0
        matches.forEach { match ->
            if (match.range.first > cursor) {
                val plain = markdown.substring(cursor, match.range.first).trim()
                if (plain.isNotBlank()) MarkdownView(plain, dark, Modifier.fillMaxWidth())
            }
            val kind = match.groupValues[1]
            val body = match.groupValues[2]
            val closingMarker = "[${'$'}${'$'}${kind}_end${'$'}${'$'}]"
            val closed = match.value.trimEnd().endsWith(closingMarker)
            if (kind == "paint") {
                PaintBlock(
                    source = body,
                    defaultSize = drawingCanvasSize,
                    dark = dark,
                    complete = closed && !streaming,
                    streaming = streaming
                )
            } else {
                if (!closed) {
                    PendingBlock("正在计算…")
                } else {
                    CalculatorBlock(expression = body.trim())
                }
            }
            cursor = match.range.last + 1
        }
        if (cursor < markdown.length) {
            val tail = markdown.substring(cursor).trim()
            if (tail.isNotBlank()) MarkdownView(tail, dark, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PendingBlock(label: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(9.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PaintBlock(
    source: String,
    defaultSize: Int,
    dark: Boolean,
    complete: Boolean,
    streaming: Boolean
) {
    var editedSource by remember(source) { mutableStateOf(source) }
    var editorOpen by remember { mutableStateOf(false) }
    val document = remember(editedSource, defaultSize) {
        parsePaint(editedSource, defaultSize, complete)
    }
    var zoom by remember(editedSource, document.width, document.height) { mutableFloatStateOf(1f) }
    var pan by remember(editedSource, document.width, document.height) {
        mutableStateOf(Offset.Zero)
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (dark) Color(0xFF111722) else Color(0xFFF7F9FC),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 6.dp, top = 7.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "绘图",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                if (complete) {
                    Text(
                        text = "拖动画布 · 双指缩放",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = {
                    zoom = 1f
                    pan = Offset.Zero
                }) {
                    Icon(
                        Icons.Rounded.CenterFocusStrong,
                        contentDescription = "恢复默认位置",
                        modifier = Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { editorOpen = true }) {
                    Icon(
                        Icons.Rounded.Code,
                        contentDescription = "查看或修改绘图命令",
                        modifier = Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (!complete) {
                PendingBlock("正在绘制图像…")
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                        .heightIn(min = 120.dp)
                ) {
                    PaintCanvas(
                        document = document,
                        dark = dark,
                        zoom = zoom,
                        pan = pan,
                        onTransform = { newZoom, newPan ->
                            zoom = newZoom
                            pan = newPan
                        }
                    )
                }
                if (document.errors.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        document.errors.take(4).forEach { PaintErrorText(it) }
                        if (document.errors.size > 4) {
                            Text(
                                "还有 ${document.errors.size - 4} 个绘图错误",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (document.warnings.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        document.warnings.take(3).forEach { warning ->
                            Text(
                                text = "第${warning.line}行第${warning.column}列：${warning.message}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        if (document.warnings.size > 3) {
                            Text(
                                "还有 ${document.warnings.size - 3} 个绘图警告",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            if (streaming && !complete) {
                Text(
                    text = "完整绘图命令到达后会自动渲染",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 14.dp, bottom = 8.dp)
                )
            }
        }
    }

    if (editorOpen) {
        PaintEditorDialog(
            initial = editedSource,
            onDismiss = { editorOpen = false },
            onApply = {
                editedSource = it
                zoom = 1f
                pan = Offset.Zero
                editorOpen = false
            }
        )
    }
}

@Composable
private fun PaintErrorText(error: PaintError) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = "第${error.line}行第${error.column}列：${error.message}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun PaintCanvas(
    document: PaintDocument,
    dark: Boolean,
    zoom: Float,
    pan: Offset,
    onTransform: (Float, Offset) -> Unit
) {
    val gridColor = if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.10f)
    val axisColor = if (dark) Color.White.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.55f)
    val textMeasurer = rememberTextMeasurer()
    // pointerInput 的闭包只在 key 变化时重建；用最新值避免每次手势都从旧
    // zoom/pan 重新计算，造成松手后画面跳回初始位置。
    val currentZoom by rememberUpdatedState(zoom)
    val currentPan by rememberUpdatedState(pan)
    val currentOnTransform by rememberUpdatedState(onTransform)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(document.width.toFloat() / document.height.toFloat())
            .background(if (dark) Color(0xFF0B111B) else Color.White)
            .pointerInput(document.width, document.height) {
                detectTransformGestures { _, panChange, zoomChange, _ ->
                    val nextZoom = (currentZoom * zoomChange).coerceIn(0.25f, 8f)
                    if (!nextZoom.isFinite()) return@detectTransformGestures
                    // 无限画布：允许自由平移，只保留一个防止数值失控的上限。
                    currentOnTransform(
                        nextZoom,
                        Offset(
                            (currentPan.x + panChange.x).coerceIn(-100000f, 100000f),
                            (currentPan.y + panChange.y).coerceIn(-100000f, 100000f)
                        )
                    )
                }
            }
    ) {
        val halfW = size.width / 2f
        val halfH = size.height / 2f
        val docW = document.width.coerceAtLeast(1)
        val docH = document.height.coerceAtLeast(1)
        val fit = min(size.width / docW, size.height / docH).coerceAtLeast(0.0001f)
        val drawScale = (zoom * fit).coerceAtLeast(0.0001f)
        // 无限画布：只在当前可见的文档范围内采样，平移到哪里都能继续绘制。
        val docLeft = (-halfW - pan.x) / drawScale
        val docRight = (halfW - pan.x) / drawScale
        val docTop = (halfH + pan.y) / drawScale
        val docBottom = (pan.y - halfH) / drawScale

        // 网格在屏幕坐标里画，避免平移后仍被初始画布边界截断。
        document.commands.forEach { command ->
            if (command is PaintCommand.Axes) {
                drawPaintGrid(
                    scale = command.scale.toFloat(),
                    step = command.gridStep.toFloat(),
                    zoomScale = drawScale,
                    pan = pan,
                    viewport = size,
                    gridColor = gridColor,
                    axisColor = axisColor
                )
            }
        }

        clipRect {
            withTransform({
                translate(halfW + pan.x, halfH + pan.y)
                scale(drawScale, drawScale, pivot = Offset.Zero)
            }) {
                var currentColor = if (dark) Color.White else Color.Black
                var currentStroke = 2f
                document.commands.forEach { command ->
                    when (command) {
                        is PaintCommand.Axes -> Unit
                        is PaintCommand.Color -> currentColor = Color(
                            command.red,
                            command.green,
                            command.blue
                        )
                        is PaintCommand.StrokeWidth -> {
                            currentStroke = command.width.toFloat()
                        }
                        is PaintCommand.Text -> {
                            val style = TextStyle(
                                color = currentColor,
                                fontSize = (10f * command.scale.toFloat()).sp
                            )
                            val layout = textMeasurer.measure(
                                AnnotatedString(command.text),
                                style = style
                            )
                            val baseline = if (layout.lineCount > 0) {
                                layout.getLineBaseline(0)
                            } else {
                                0f
                            }
                            drawText(
                                textLayoutResult = layout,
                                topLeft = Offset(
                                    command.x.toFloat(),
                                    (-command.y).toFloat() - baseline
                                )
                            )
                        }
                        is PaintCommand.FunctionPlot -> {
                            val path = Path()
                            var started = false
                            var px = docLeft
                            val step = (2f / drawScale).coerceAtLeast(0.02f)
                            var guard = 0
                            while (px <= docRight && guard < 6000) {
                                val xUnit = px / command.scale
                                val y = runCatching { evalMath(command.expression, xUnit) }
                                    .getOrNull()
                                if (y == null || !y.isReal() || !y.re.isFinite()) {
                                    started = false
                                } else {
                                    val sy = (-y.re * command.scale).toFloat()
                                    if (!sy.isFinite()) {
                                        started = false
                                    } else if (!started) {
                                        path.moveTo(px, sy)
                                        started = true
                                    } else {
                                        path.lineTo(px, sy)
                                    }
                                }
                                px += step
                                guard++
                            }
                            drawPath(path, currentColor, style = Stroke(width = currentStroke / drawScale))
                        }
                        is PaintCommand.Line -> drawLine(
                            color = currentColor,
                            start = Offset(command.x1.toFloat(), (-command.y1).toFloat()),
                            end = Offset(command.x2.toFloat(), (-command.y2).toFloat()),
                            strokeWidth = currentStroke / drawScale
                        )
                        is PaintCommand.Point -> drawCircle(
                            color = currentColor,
                            radius = (currentStroke * 1.8f) / drawScale,
                            center = Offset(command.x.toFloat(), (-command.y).toFloat())
                        )
                    }
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPaintGrid(
    scale: Float,
    step: Float,
    zoomScale: Float,
    pan: Offset,
    viewport: androidx.compose.ui.geometry.Size,
    gridColor: Color,
    axisColor: Color
) {
    val unit = scale * step * zoomScale
    if (!unit.isFinite() || unit <= 0f) return
    if (!viewport.width.isFinite() || !viewport.height.isFinite() ||
        viewport.width <= 0f || viewport.height <= 0f
    ) return

    // 缩得很小时网格线会变成密密麻麻的实心块；抽稀到最多约 400 条。
    val stride = max(1.0, ceil(max(viewport.width, viewport.height) / unit / 400.0))
    val stepPx = (unit * stride).toFloat()
    if (!stepPx.isFinite() || stepPx <= 0f) return

    val originX = viewport.width / 2f + pan.x
    val originY = viewport.height / 2f + pan.y
    if (!originX.isFinite() || !originY.isFinite()) return

    // 用屏幕坐标绘制，网格完整覆盖当前视口，不会在初始画布边缘断掉。
    var x = originX - floor(originX / stepPx) * stepPx
    if (x < 0f) x += stepPx
    var guard = 0
    while (x <= viewport.width && guard < 500) {
        drawLine(
            color = gridColor,
            start = Offset(x, 0f),
            end = Offset(x, viewport.height),
            strokeWidth = 1f
        )
        x += stepPx
        guard++
    }

    var y = originY - floor(originY / stepPx) * stepPx
    if (y < 0f) y += stepPx
    guard = 0
    while (y <= viewport.height && guard < 500) {
        drawLine(
            color = gridColor,
            start = Offset(0f, y),
            end = Offset(viewport.width, y),
            strokeWidth = 1f
        )
        y += stepPx
        guard++
    }

    if (originX in 0f..viewport.width) {
        drawLine(
            color = axisColor,
            start = Offset(originX, 0f),
            end = Offset(originX, viewport.height),
            strokeWidth = 1.6f
        )
    }
    if (originY in 0f..viewport.height) {
        drawLine(
            color = axisColor,
            start = Offset(0f, originY),
            end = Offset(viewport.width, originY),
            strokeWidth = 1.6f
        )
    }
}

@Composable
private fun PaintEditorDialog(
    initial: String,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    SmathDialog(
        title = "编辑绘图命令",
        subtitle = "修改后点击执行，画布会立即按新命令重绘",
        icon = Icons.Rounded.Code,
        onDismiss = onDismiss,
        footer = {
            OutlinedButton(onClick = onDismiss, shape = CircleShape) { Text("取消") }
            Button(onClick = { onApply(text) }, shape = CircleShape) { Text("执行") }
        }
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 200.dp, max = 380.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            ),
            shape = RoundedCornerShape(16.dp),
            singleLine = false,
            maxLines = 18
        )
    }
}

@Composable
private fun CalculatorBlock(expression: String) {
    val result = remember(expression) {
        runCatching { evalMath(expression) }
    }
    val clipboard = LocalClipboardManager.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Calculate,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    "计算器",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = {
                        result.getOrNull()?.let {
                            clipboard.setText(AnnotatedString(formatComplex(it)))
                        }
                    },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = "复制结果",
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(top = 5.dp, bottom = 7.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            )
            Text(
                text = expression,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.padding(top = 3.dp))
            result.fold(
                onSuccess = { value ->
                    Text(
                        text = formatComplex(value),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                onFailure = { error ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = error.message ?: "无法计算",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            )
        }
    }
}