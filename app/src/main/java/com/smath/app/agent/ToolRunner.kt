package com.smath.app.agent

/**
 * 多步调用里的“工具执行器”。
 *
 * 模型在正文里写出 `[$$calc_start$$] … [$$calc_end$$]` 之类的工具块后就会结束这一轮；
 * 这里扫描出**已经闭合**的工具块、在本地真正执行一遍，再把结果整理成一条消息回灌给模型，
 * 于是下一轮请求里模型能看到“自己算出来的东西”，从而继续推理而不是猜测。
 *
 * 只执行闭合块：正在流式输出的半截块留到下一轮再说，避免把没写完的命令当成错误回灌。
 */
object ToolRunner {

    /** 一次对话里最多允许的工具往返次数，防止模型陷入无限调用。 */
    const val MAX_TOOL_ROUNDS = 4

    const val KIND_PAINT = "paint"
    const val KIND_CALC = "calc"
    const val KIND_LINALG = "linalg"

    data class Execution(
        val index: Int,
        val kind: String,
        val source: String,
        val summary: String,
        val ok: Boolean
    )

    data class ScanResult(
        val executions: List<Execution>,
        /** 下一次扫描的起点：已执行到的位置，防止同一个块被重复执行。 */
        val nextIndex: Int
    )

    private val blockRegex = Regex(
        """\[\$\$(paint|calc|linalg)_start\$\$\]([\s\S]*?)\[\$\$\1_end\$\$\]"""
    )

    fun scan(
        content: String,
        fromIndex: Int,
        drawingCanvasSize: Int
    ): ScanResult {
        if (fromIndex >= content.length) return ScanResult(emptyList(), fromIndex)
        val region = content.substring(fromIndex.coerceAtLeast(0))
        val executions = mutableListOf<Execution>()
        var end = fromIndex.coerceAtLeast(0)
        var order = 0
        blockRegex.findAll(region).forEach { match ->
            val kind = match.groupValues[1]
            val body = match.groupValues[2].trim()
            end = fromIndex + match.range.last + 1
            if (body.isEmpty()) return@forEach
            order += 1
            executions += when (kind) {
                KIND_CALC -> runCalculator(order, body)
                KIND_PAINT -> runPainter(order, body, drawingCanvasSize)
                else -> runLinearAlgebra(order, body)
            }
        }
        return ScanResult(executions, maxOf(end, fromIndex))
    }

    private fun runCalculator(order: Int, body: String): Execution {
        val expression = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return try {
            val value = evalMath(expression)
            Execution(
                index = order,
                kind = KIND_CALC,
                source = expression,
                summary = formatComplex(value),
                ok = true
            )
        } catch (e: MathEvalException) {
            Execution(order, KIND_CALC, expression, "计算出错：${e.message}", false)
        } catch (t: Throwable) {
            Execution(order, KIND_CALC, expression, "计算出错：${t.message ?: "未知错误"}", false)
        }
    }

    private fun runPainter(order: Int, body: String, drawingCanvasSize: Int): Execution {
        val document = parsePaint(body, drawingCanvasSize, complete = true)
        if (document.errors.isNotEmpty()) {
            val first = document.errors.first()
            return Execution(
                index = order,
                kind = KIND_PAINT,
                source = body,
                summary = "绘图有 ${document.errors.size} 处错误，首条在第 ${first.line} 行第 ${first.column} 列：${first.message}",
                ok = false
            )
        }
        val functionCount = document.commands.count { it is PaintCommand.FunctionPlot }
        val detail = buildString {
            append("绘图已渲染，共 ${document.commands.size} 条命令")
            if (functionCount > 0) append("（其中 $functionCount 条函数曲线）")
            if (!document.hasAxes) append("；注意：没有声明 axes，画布上不会出现坐标轴")
        }
        return Execution(order, KIND_PAINT, body, detail, true)
    }

    private fun runLinearAlgebra(order: Int, body: String): Execution {
        val results = LinearAlgebraTool.runBlock(body)
        if (results.isEmpty()) {
            return Execution(order, KIND_LINALG, body, "线性代数计算器没有读到有效命令", false)
        }
        val failed = results.count { it.error != null }
        val summary = results.joinToString("\n") { item ->
            if (item.error != null) {
                "${item.command} → 错误：${item.error}"
            } else {
                "${item.command} → ${item.plain}"
            }
        }
        return Execution(order, KIND_LINALG, body, summary, failed == 0)
    }

    /**
     * 把执行结果写成回灌给模型的消息。
     * 明确告诉模型“这些是真实的执行结果”，并要求它接着写，避免重复输出。
     */
    fun resultMessage(executions: List<Execution>): String {
        val body = executions.joinToString("\n") { item ->
            val head = when (item.kind) {
                KIND_CALC -> "计算器"
                KIND_PAINT -> "绘图工具"
                else -> "线性代数计算器"
            }
            val status = if (item.ok) "成功" else "失败"
            "$head（$status）：\n${item.summary}"
        }
        return buildString {
            append("应用已执行你刚才调用的工具，结果如下（这是本机的真实执行结果，请以此为准）：\n\n")
            append(body)
            append("\n\n请基于上面的结果继续完成回答：")
            append("不要重复已经输出过的内容，也不要再把工具命令贴一遍；")
            append("如果有工具失败，请修正后重新调用，或换一种方法并说明。")
        }
    }
}