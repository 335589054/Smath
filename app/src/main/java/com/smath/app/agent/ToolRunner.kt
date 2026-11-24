package com.smath.app.agent

/**
 * 多步调用里的“工具执行器”。
 *
 * 模型在正文里写出 `[$$calc_start$$] … [$$calc_end$$]` 之类的工具块后就会结束这一轮；
 * 这里扫描出**已经闭合**的工具块、在本地真正执行一遍，再把结果整理成一条消息回灌给模型，
 * 于是下一轮请求里模型能看到“自己算出来的东西”，从而继续推理而不是猜测。
 *
 * 只执行闭合块：正在流式输出的半截块留到下一轮再说，避免把没写完的命令当成错误回灌。
 *
 * 工具分两类：
 * - `calc` / `paint` / `linalg`：纯本地计算，[scan] 就能直接跑；
 * - `lean`：要交给电脑上的 lean_server.py，得用 suspend 的 [scanAsync] 并传入 [LeanClient.LeanConfig]。
 */
object ToolRunner {

    /** 一次对话里最多允许的工具往返次数，防止模型陷入无限调用。 */
    const val MAX_TOOL_ROUNDS = 8

    const val KIND_PAINT = "paint"
    const val KIND_CALC = "calc"
    const val KIND_LINALG = "linalg"
    const val KIND_LEAN = "lean"

    /** 同步入口没有网络能力，遇到 lean 块只给这个占位结论。 */
    private const val LEAN_NOT_CONFIGURED = "lean 编译工具尚未配置，本次无法使用"

    /** 有 lean 块因为“没配置”失败时追加的提示，去掉它就无法引导用户去设置页。 */
    private const val LEAN_SETUP_HINT =
        "（提示：如果用户希望你使用 lean，请提醒他在设置里配置 lean 编译工具的地址和 Key。）"

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
        """\[\$\$(paint|calc|linalg|lean)_start\$\$\]([\s\S]*?)\[\$\$\1_end\$\$\]"""
    )

    /** 扫描出来的一个工具块；body 只去掉首尾空行，每行的缩进原样保留（lean 对缩进敏感）。 */
    private data class RawBlock(val kind: String, val body: String, val endIndex: Int)

    /** 纯本地执行（不含 lean），模型流式输出结束时用这个。 */
    fun scan(
        content: String,
        fromIndex: Int,
        drawingCanvasSize: Int
    ): ScanResult {
        val (blocks, nextIndex) = findBlocks(content, fromIndex)
        val executions = blocks.mapIndexed { offset, block ->
            executeLocal(offset + 1, block, drawingCanvasSize)
        }
        return ScanResult(executions, nextIndex)
    }

    /**
     * 带 lean 的异步执行入口：顺序与文本中出现顺序一致。
     * `lean` 为 null 或未配置时，lean 块会得到“尚未配置”的失败结论，不影响其它工具。
     */
    suspend fun scanAsync(
        content: String,
        fromIndex: Int,
        drawingCanvasSize: Int,
        lean: LeanClient.LeanConfig?
    ): ScanResult {
        val (blocks, nextIndex) = findBlocks(content, fromIndex)
        val config = lean ?: LeanClient.LeanConfig("", "")
        val executions = blocks.mapIndexed { offset, block ->
            val order = offset + 1
            if (block.kind != KIND_LEAN) {
                executeLocal(order, block, drawingCanvasSize)
            } else {
                val result = LeanClient.check(config, block.body)
                Execution(
                    index = order,
                    kind = KIND_LEAN,
                    source = block.body,
                    summary = result.summary,
                    ok = result.ok
                )
            }
        }
        return ScanResult(executions, nextIndex)
    }

    /**
     * 找出所有闭合工具块。返回 (块列表, 下一次的起点)；
     * 空 body 的块会被跳过，但仍然推进游标，避免同一位置被反复扫描。
     */
    private fun findBlocks(content: String, fromIndex: Int): Pair<List<RawBlock>, Int> {
        if (fromIndex >= content.length) return emptyList<RawBlock>() to fromIndex
        val start = fromIndex.coerceAtLeast(0)
        val region = content.substring(start)
        val blocks = mutableListOf<RawBlock>()
        var end = start
        blockRegex.findAll(region).forEach { match ->
            end = start + match.range.last + 1
            val body = trimBlankLines(match.groupValues[2])
            if (body.isNotBlank()) {
                blocks += RawBlock(match.groupValues[1], body, end)
            }
        }
        return blocks to maxOf(end, start)
    }

    /**
     * 只去掉首尾的空行，保留每行原来的缩进。
     * 之前这里用的是 trim()，它会把第一行的缩进也吃掉；lean 靠缩进表达结构，所以单独处理。
     */
    private fun trimBlankLines(text: String): String {
        val lines = text.split('\n')
        var first = 0
        var last = lines.size
        while (first < last && lines[first].isBlank()) first++
        while (last > first && lines[last - 1].isBlank()) last--
        return lines.subList(first, last).joinToString("\n")
    }

    private fun executeLocal(order: Int, block: RawBlock, drawingCanvasSize: Int): Execution =
        when (block.kind) {
            KIND_CALC -> runCalculator(order, block.body)
            KIND_PAINT -> runPainter(order, block.body.trim(), drawingCanvasSize)
            KIND_LINALG -> runLinearAlgebra(order, block.body.trim())
            KIND_LEAN -> Execution(order, KIND_LEAN, block.body, LEAN_NOT_CONFIGURED, false)
            else -> Execution(order, block.kind, block.body, "未知工具：${block.kind}", false)
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
                KIND_LINALG -> "线性代数计算器"
                KIND_LEAN -> "lean 编译器"
                else -> item.kind
            }
            val status = if (item.ok) "成功" else "失败"
            "$head（$status）：\n${item.summary}"
        }
        // 只要有一个 lean 块是因为“没配置”失败的，就提醒模型去引导用户配置。
        val leanMissing = executions.any { item ->
            item.kind == KIND_LEAN && !item.ok && item.summary.contains("尚未配置")
        }
        return buildString {
            append("应用已执行你刚才调用的工具，结果如下（这是本机的真实执行结果，请以此为准）：\n\n")
            append(body)
            append("\n\n请基于上面的结果继续完成回答：")
            append("不要重复已经输出过的内容，也不要再把工具命令贴一遍；")
            append("如果有工具失败，请修正后重新调用，或换一种方法并说明。")
            if (leanMissing) {
                append("\n")
                append(LEAN_SETUP_HINT)
            }
        }
    }
}