package com.smath.app.agent

import kotlin.math.max

data class PaintError(val line: Int, val column: Int, val message: String)

sealed interface PaintCommand {
    data class Axes(val axisId: Int, val scale: Double, val gridStep: Double) : PaintCommand
    data class FunctionPlot(
        val expression: String,
        val axisId: Int,
        val scale: Double
    ) : PaintCommand
    data class Line(
        val x1: Double,
        val y1: Double,
        val x2: Double,
        val y2: Double
    ) : PaintCommand
    data class Point(val x: Double, val y: Double) : PaintCommand
    data class Color(val red: Int, val green: Int, val blue: Int) : PaintCommand
    data class StrokeWidth(val width: Double) : PaintCommand
    data class Text(
        val text: String,
        val x: Double,
        val y: Double,
        val scale: Double
    ) : PaintCommand
}

data class PaintDocument(
    val width: Int,
    val height: Int,
    val commands: List<PaintCommand>,
    val errors: List<PaintError>,
    val warnings: List<PaintError> = emptyList()
) {
    val hasAxes: Boolean get() = commands.any { it is PaintCommand.Axes }
    val isEmpty: Boolean get() = commands.none { it !is PaintCommand.Axes } && errors.isEmpty()
}

/**
 * Parses the line-oriented paint language on an unbounded canvas. `canvas_init`
 * is accepted as a legacy no-op, but new output should not emit it.
 */
fun parsePaint(
    source: String,
    defaultSize: Int,
    complete: Boolean = true
): PaintDocument {
    val clean = stripComments(source)
    val statements = splitStatements(clean)
    val width = defaultSize.coerceIn(200, 2000)
    val height = defaultSize.coerceIn(200, 2000)
    var currentAxisId = 0
    val axes = mutableListOf<Pair<Double, Double>>()
    val commands = mutableListOf<PaintCommand>()
    val errors = mutableListOf<PaintError>()
    val warnings = mutableListOf<PaintError>()

    fun fail(statement: Statement, message: String) {
        val (line, column) = lineColumn(clean, statement.start)
        errors += PaintError(line, column, message)
    }

    statements.forEach { statement ->
        if (statement.text.isBlank()) return@forEach
        val name = statement.text.substringBefore('(').trim()
        if (!statement.text.contains('(') || !statement.text.trimEnd().endsWith(')')) {
            fail(statement, "命令格式应为 name(参数)")
            return@forEach
        }
        val argsStart = statement.text.indexOf('(')
        val argsEnd = statement.text.lastIndexOf(')')
        if (argsEnd < argsStart) {
            fail(statement, "缺少右括号")
            return@forEach
        }
        val args = splitArguments(statement.text.substring(argsStart + 1, argsEnd))
        when (name.lowercase()) {
            // 兼容旧对话里的 canvas_init；新画布无边界，该命令不再生效。
            "canvas_init" -> Unit
            else -> {
                when (name.lowercase()) {
                    "color" -> {
                        if (args.size != 3) {
                            fail(statement, "color 需要 r,g,b 三个 0-255 的整数")
                            return@forEach
                        }
                        val rgb = args.map { it.toDoubleOrNull()?.toInt() }
                        if (rgb.any { it == null || it !in 0..255 }) {
                            fail(statement, "颜色分量必须是 0 到 255 的整数")
                            return@forEach
                        }
                        commands += PaintCommand.Color(rgb[0]!!, rgb[1]!!, rgb[2]!!)
                    }
                    "stroke_width", "strokewidth", "stroke", "linewidth", "width", "粗细" -> {
                        if (args.size != 1) {
                            fail(statement, "stroke_width 需要一个正数参数")
                            return@forEach
                        }
                        val w = args[0].toDoubleOrNull()
                        if (w == null || !w.isFinite() || w <= 0) {
                            fail(statement, "stroke_width 必须是大于 0 的数字")
                            return@forEach
                        }
                        commands += PaintCommand.StrokeWidth(w.coerceIn(0.5, 20.0))
                    }
                    "axes" -> {
                        if (args.size != 2) {
                            fail(statement, "axes 需要 scale,gridStep 两个参数")
                            return@forEach
                        }
                        val scale = args[0].toDoubleOrNull()
                        val grid = args[1].toDoubleOrNull()
                        if (scale == null || grid == null || !scale.isFinite() || !grid.isFinite() ||
                            scale <= 0 || grid <= 0
                        ) {
                            fail(statement, "axes 参数必须是大于 0 的有限数字")
                            return@forEach
                        }
                        currentAxisId = axes.size
                        axes += scale to grid
                        commands += PaintCommand.Axes(currentAxisId, scale, grid)
                    }
                    "function", "func", "plot" -> {
                        if (args.isEmpty() || args.size > 2) {
                            fail(statement, "function 需要 \"表达式\"[,axisId]")
                            return@forEach
                        }
                        val expression = unquote(args[0])
                        if (expression.isBlank()) {
                            fail(statement, "函数表达式不能为空")
                            return@forEach
                        }
                        val axisId = if (args.size == 2) {
                            args[1].toDoubleOrNull()?.toInt()
                                ?: run {
                                    fail(statement, "axisId 必须是整数")
                                    return@forEach
                                }
                        } else {
                            currentAxisId
                        }
                        if (axisId !in axes.indices) {
                            fail(statement, "axisId $axisId 不存在")
                            return@forEach
                        }
                        // 只把真正的语法错误视为无效；定义域问题（如 ln(x-1) 在 x=1）留给逐点绘制处理。
                        val preview = runCatching { evalMath(expression, 1.0) }
                        val previewError = preview.exceptionOrNull()
                        if (previewError is MathEvalException && previewError.position >= 0) {
                            fail(statement, "函数表达式无效：${previewError.message}")
                            return@forEach
                        }
                        commands += PaintCommand.FunctionPlot(
                            expression = expression,
                            axisId = axisId,
                            scale = axes[axisId].first
                        )
                    }
                    "line" -> {
                        if (args.size != 4) {
                            fail(statement, "line 需要 x1,y1,x2,y2")
                            return@forEach
                        }
                        val values = args.map { it.toDoubleOrNull() }
                        if (values.any { it == null || !it.isFinite() }) {
                            fail(statement, "line 坐标必须是数字")
                            return@forEach
                        }
                        val x1 = values[0]!!.coerceIn(-1_000_000.0, 1_000_000.0)
                        val y1 = values[1]!!.coerceIn(-1_000_000.0, 1_000_000.0)
                        val x2 = values[2]!!.coerceIn(-1_000_000.0, 1_000_000.0)
                        val y2 = values[3]!!.coerceIn(-1_000_000.0, 1_000_000.0)
                        commands += PaintCommand.Line(x1, y1, x2, y2)
                    }
                    "point" -> {
                        if (args.size != 2) {
                            fail(statement, "point 需要 x,y")
                            return@forEach
                        }
                        val values = args.map { it.toDoubleOrNull() }
                        if (values.any { it == null || !it.isFinite() }) {
                            fail(statement, "point 坐标必须是数字")
                            return@forEach
                        }
                        val x = values[0]!!.coerceIn(-1_000_000.0, 1_000_000.0)
                        val y = values[1]!!.coerceIn(-1_000_000.0, 1_000_000.0)
                        commands += PaintCommand.Point(x, y)
                    }
                    "string", "text" -> {
                        if (args.size != 4) {
                            fail(statement, "string 需要 \"文本\",x,y,scale 四个参数")
                            return@forEach
                        }
                        val text = unquote(args[0])
                        if (text.isEmpty()) {
                            fail(statement, "string 的文本不能为空")
                            return@forEach
                        }
                        val x = args[1].toDoubleOrNull()
                        val y = args[2].toDoubleOrNull()
                        val scale = args[3].toDoubleOrNull()
                        if (x == null || y == null || scale == null ||
                            !x.isFinite() || !y.isFinite() || !scale.isFinite() || scale <= 0
                        ) {
                            fail(statement, "string 的坐标必须是有限数字，scale 必须大于 0")
                            return@forEach
                        }
                        commands += PaintCommand.Text(
                            text = text,
                            x = x.coerceIn(-1_000_000.0, 1_000_000.0),
                            y = y.coerceIn(-1_000_000.0, 1_000_000.0),
                            scale = scale.coerceIn(0.1, 10.0)
                        )
                    }
                    else -> fail(statement, "未知命令“$name”")
                }
            }
        }
    }
    return PaintDocument(
        width = width,
        height = height,
        commands = commands,
        errors = errors,
        warnings = warnings
    )
}

private data class Statement(val text: String, val start: Int)

/** Replaces comments with spaces while preserving newlines, so positions stay meaningful. */
private fun stripComments(source: String): String {
    val out = StringBuilder(source.length)
    var i = 0
    while (i < source.length) {
        if (i + 1 < source.length && source[i] == '/' && source[i + 1] == '*') {
            out.append("  ")
            i += 2
            while (i < source.length) {
                if (i + 1 < source.length && source[i] == '*' && source[i + 1] == '/') {
                    out.append("  ")
                    i += 2
                    break
                }
                out.append(if (source[i] == '\n') '\n' else ' ')
                i++
            }
        } else {
            out.append(source[i])
            i++
        }
    }
    return out.toString()
}

private fun splitStatements(source: String): List<Statement> {
    val result = mutableListOf<Statement>()
    var start = 0
    var depth = 0
    var inString = false
    var escaped = false
    var i = 0
    while (i < source.length) {
        val ch = source[i]
        if (inString) {
            if (escaped) {
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == '"') {
                inString = false
            }
        } else {
            when (ch) {
                '"' -> inString = true
                '(' -> depth++
                ')' -> depth = max(0, depth - 1)
                ';' -> {
                    if (depth == 0) {
                        result += Statement(source.substring(start, i).trim(), start)
                        start = i + 1
                    }
                }
            }
        }
        i++
    }
    if (start < source.length) {
        result += Statement(source.substring(start).trim(), start)
    }
    return result
}

private fun splitArguments(text: String): List<String> {
    val result = mutableListOf<String>()
    var start = 0
    var depth = 0
    var inString = false
    var escaped = false
    text.forEachIndexed { index, ch ->
        if (inString) {
            if (escaped) escaped = false
            else if (ch == '\\') escaped = true
            else if (ch == '"') inString = false
        } else {
            when (ch) {
                '"' -> inString = true
                '(' -> depth++
                ')' -> depth = max(0, depth - 1)
                ',' -> if (depth == 0) {
                    result += text.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
    }
    result += text.substring(start).trim()
    return if (result.size == 1 && result[0].isEmpty()) emptyList() else result
}

private fun unquote(value: String): String {
    val trimmed = value.trim()
    if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
        return trimmed.substring(1, trimmed.length - 1)
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }
    return trimmed
}

private fun lineColumn(source: String, index: Int): Pair<Int, Int> {
    var line = 1
    var column = 1
    var i = 0
    while (i < index.coerceIn(0, source.length)) {
        if (source[i] == '\n') {
            line++
            column = 1
        } else {
            column++
        }
        i++
    }
    return line to column
}