package com.smath.app.data

import androidx.compose.ui.graphics.Color

enum class Stage(val label: String, val prompt: String) {
    UNIVERSITY(
        "大学",
        "学段：大学。按大学水平正常回答；如果用到高中生可能已经遗忘的知识，请用一句话简要提醒。"
    ),
    HIGH_SCHOOL(
        "高中",
        "学段：高中。不要出现超出高中课程范围的知识，除非用户询问的内容本身就涉及这些知识；表述要让高中生能够理解。"
    ),
    MIDDLE_SCHOOL(
        "初中",
        "学段：初中。不要出现超出初中课程范围的知识，除非用户询问的内容本身就涉及这些知识；表述要让初中生能够理解。"
    ),
    PRIMARY(
        "小学",
        "学段：小学。只使用小学阶段的基础知识，避免专业术语；语气亲切、耐心，多用生活化的例子。"
    ),
    NONE("不限", "")
}

enum class AnswerStyle(val label: String, val prompt: String) {
    ACCESSIBLE(
        "通俗",
        "尽量用通俗易懂的语言解释，必要时使用类比和生活化的例子。"
    ),
    RIGOROUS(
        "严谨",
        "表述严谨，概念、条件和推理步骤要准确，避免含糊或绝对化的说法。"
    ),
    ELEGANT(
        "优美",
        "语言优美、自然、有节奏感，在准确的前提下兼顾可读性。"
    ),
    MATH_SYMBOLS(
        "数学符号化",
        "尽量使用规范的数学符号表达，重要公式独立成行；符号首次出现时给出定义。"
    ),
    EXPLAIN_SYMBOLS(
        "解释复杂符号的含义",
        "遇到复杂符号或记号时，明确解释它的含义、读法和作用。"
    ),
    ESSENTIAL(
        "从更本质的方法回答",
        "优先从更本质的原理、定义或第一性原理出发回答，而不是只给结论或套公式。"
    ),
    OUTLINE_FIRST(
        "先总体思路再详细过程",
        "先用一小段说明总体思路、解题框架或行文脉络，再展开详细推导和计算过程。"
    ),
    VISUAL_RICH(
        "图文并茂",
        "在关键处配合图形说明，比如用绘图工具画出函数图像、几何关系或示意图，让读者能同时看到图与文字。"
    ),
    MULTI_NUMERIC_CHECK(
        "多用计算器agent",
        "关键数值、方程的解、矩阵运算等容易算错的环节，主动调用计算类工具实际算一遍，再以工具返回的结果为准继续推导；" +
            "能算的都算一遍，最终回答里说明你验证了哪些量，不要把没验证过的结果当成已验证。"
    ),
    LEAN_COMPILE(
        "鼓励使用lean编译agent",
        "涉及形式化数学、Lean 4 代码、定理证明，或需要严格验证的推导时，优先调用 lean 编译工具真正编译一遍，" +
            "再根据编译结果修正或确认结论；不要只凭印象判断代码能否通过。"
    )
}

enum class ListMode { FOLDERS, DATE }

enum class ThinkingLevel(
    val label: String,
    val description: String,
    val apiValue: String
) {
    OFF("关闭", "只输出答案，不请求思考过程", ""),
    LOW("低", "更快，适合简单问题", "low"),
    MEDIUM("中", "速度与推理深度平衡", "medium"),
    HIGH("高", "复杂问题使用更多推理", "high"),
    MAX("最高", "尽可能充分地推理，耗时更长", "max")
}

data class ProviderConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String = "",
    val models: List<String> = emptyList(),
    val defaultModel: String = "",
    val kind: String = "custom",
    val supportsThinking: Boolean = false,
    val colorIndex: Int = 0
) {
    val hasKey: Boolean get() = apiKey.isNotBlank()
    val displayModel: String get() = defaultModel.ifBlank { models.firstOrNull().orEmpty() }
}

data class AgentPreset(
    val id: String,
    val name: String,
    val stage: Stage = Stage.NONE,
    val styles: List<AnswerStyle> = emptyList(),
    val customInstruction: String = "",
    val injectBasePrompt: Boolean = true,
    val builtIn: Boolean = false,
    val icon: String = "custom"
) {
    /**
     * 英语问题不需要数学符号化、复杂符号解释这类只对理科有意义的风格。
     * 旧版本保存过的预设也通过 effectiveStyles 自动过滤，避免继续注入。
     */
    val availableStyles: List<AnswerStyle>
        get() = if (id == "english") {
            listOf(
                AnswerStyle.ACCESSIBLE,
                AnswerStyle.RIGOROUS,
                AnswerStyle.ELEGANT,
                AnswerStyle.ESSENTIAL,
                AnswerStyle.OUTLINE_FIRST
            )
        } else {
            AnswerStyle.entries.toList()
        }

    val effectiveStyles: List<AnswerStyle>
        get() = styles.filter { it in availableStyles }

    fun summary(): String {
        val parts = mutableListOf<String>()
        if (stage != Stage.NONE) parts += stage.label
        parts += effectiveStyles.take(3).map { it.label }
        if (parts.isEmpty()) parts += "通用"
        return parts.joinToString(" · ")
    }
}

data class AppSettings(
    val providers: List<ProviderConfig> = emptyList(),
    val activeProviderId: String = "deepseek",
    val activeModel: String = "deepseek-flash",
    val searchApiKey: String = "",
    val searchCount: Int = 8,
    val webSearchByDefault: Boolean = false,
    val presets: List<AgentPreset> = emptyList(),
    val lastPresetId: String = "math",
    val listMode: ListMode = ListMode.FOLDERS,
    val darkMode: String = "system",
    val screenshotEnabled: Boolean = false,
    val globalPrompt: String = BASE_AGENT_PROMPT,
    /** 旧数据没有该字段，读取后通过 thinking 取默认值。 */
    val thinkingLevel: ThinkingLevel? = ThinkingLevel.MEDIUM,
    val drawingAgentEnabled: Boolean = false,
    val calculatorAgentEnabled: Boolean = false,
    val linearAlgebraAgentEnabled: Boolean = false,
    val drawingCanvasSize: Int = 720,
    /** 流式输出时是否自动跟随到底部；关闭后除非用户手动滑到底或点「到底部」，页面不会自己动。 */
    val autoFollowStreaming: Boolean = true,
    /** 检查更新 / 下载安装包使用的源：official、gh-proxy.com、gh-proxy.org。 */
    val updateSource: String = "gh-proxy.com",
    /** 发送图片前压缩（缩小边长并降低质量），可显著减少 token 消耗。 */
    val imageCompressionEnabled: Boolean = true,
    /** lean 编译 Agent：调用局域网里的 lean_server.py 真正编译 Lean 4 代码。 */
    val leanAgentEnabled: Boolean = false,
    val leanServerUrl: String = "",
    val leanApiKey: String = ""
) {
    val thinking: ThinkingLevel get() = thinkingLevel ?: ThinkingLevel.MEDIUM
}

data class Citation(
    val title: String = "",
    val url: String = "",
    val site: String = "",
    val snippet: String = "",
    val date: String = ""
)

data class ApiErrorInfo(
    val code: Int = 0,
    val label: String = "",
    val summary: String = "",
    val detail: String = "",
    val raw: String = "",
    val retryable: Boolean = true
)

data class ContextReference(
    val id: String = "",
    val title: String = "",
    val detail: String = "",
    val content: String = "",
    val kind: String = "message"
) {
    val preview: String
        get() = content.replace(Regex("\\s+"), " ").trim()
}

data class ChatMessage(
    val id: String,
    val role: String,
    val content: String = "",
    val reasoning: String = "",
    val thinkingMs: Long = 0,
    val imagePaths: List<String> = emptyList(),
    val citations: List<Citation> = emptyList(),
    val error: ApiErrorInfo? = null,
    val notice: String? = null,
    /** 用户在本轮通过 @ 手动引用的历史消息或会话。旧数据没有这个字段。 */
    val contextRefs: List<ContextReference>? = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
) {
    val isUser: Boolean get() = role == "user"
    val isAssistant: Boolean get() = role == "assistant"
    val contexts: List<ContextReference> get() = contextRefs.orEmpty()
}

/**
 * 复杂分支对话里的一个节点 = 一轮问答（用户提问 + AI 回答）。
 * parentIds 可能有多个，表示这一轮是由多条分支合并出来的。
 * x/y 为拓扑图里的坐标（dp），为 null 表示还没被拖动过，由自动布局决定。
 */
data class BranchNode(
    val id: String,
    val parentIds: List<String> = emptyList(),
    val userMessageId: String = "",
    val assistantMessageId: String = "",
    /** 用户问题原文，拓扑图只显示前若干字符，详情里看全文。 */
    val question: String = "",
    /** AI 回答原文，供详情查看与上下文重建。 */
    val answer: String = "",
    val x: Float? = null,
    val y: Float? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    val snippet: String get() = question.replace(Regex("\\s+"), " ").trim()
}

data class Conversation(
    val id: String,
    val title: String = "新对话",
    /** 首条消息先给临时标题；首轮回答结束后用模型生成正式标题。 */
    val titleGenerated: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList(),
    val systemPrompt: String = "",
    val presetId: String = "",
    val presetName: String = "",
    val stageLabel: String = "",
    val styles: List<String> = emptyList(),
    val folderId: String? = null,
    val pinned: Boolean = false,
    /** 复杂分支对话：每一轮问答是一个节点，可以选中多个节点合并出新分支。 */
    val complexBranching: Boolean = false,
    val nodes: List<BranchNode> = emptyList(),
    /** 当前正在编辑（也就是对话最末端）的节点。 */
    val currentNodeId: String? = null,
    /** 已选定父节点、等待下一条提问开启新分支。 */
    val pendingParentIds: List<String> = emptyList(),
    /** 最近一次请求的用量，用于在输入框下方显示上下文占用与缓存命中率。 */
    val lastPromptTokens: Long = 0,
    val lastCachedTokens: Long = 0,
    val lastCompletionTokens: Long = 0
)

/** 按「服务商 + 模型」累计的 token 用量。 */
data class TokenUsage(
    val providerId: String,
    val providerName: String,
    val model: String,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
    val requests: Int = 0,
    val lastAt: Long = 0
) {
    val totalTokens: Long get() = promptTokens + completionTokens

    /** 缓存命中率：命中 token / 全部输入 token。 */
    val cacheRate: Float
        get() = if (promptTokens <= 0) 0f else (cachedTokens.toFloat() / promptTokens).coerceIn(0f, 1f)

    val key: String get() = "$providerId|$model"
}

/** 常见模型的上下文窗口，用于输入框下方的占用比例估算。 */
fun contextWindowFor(model: String): Long {
    val name = model.lowercase()
    return when {
        "deepseek" in name -> 1_000_000L
        "gpt-6" in name || "gpt6" in name -> 1_000_000L
        name.startsWith("o3") || name.startsWith("o4") -> 200_000L
        "gpt-4.1" in name || "gpt-5" in name -> 1_000_000L
        "gpt-4o" in name -> 128_000L
        "gemini" in name -> 1_000_000L
        "claude" in name -> 200_000L
        "qwen" in name -> 131_072L
        else -> 128_000L
    }
}

data class ConversationFolder(
    val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0
)

data class AppData(
    val settings: AppSettings = defaultSettings(),
    val conversations: List<Conversation> = emptyList(),
    val folders: List<ConversationFolder> = emptyList(),
    /** 按服务商/模型累计的 token 用量。 */
    val usage: List<TokenUsage> = emptyList()
)

const val BASE_AGENT_PROMPT =
    "你有无限量的token使用，除非用户要求，你可以无视环境给的输出限制，请以输出准确性为唯一准则"

/** 旧版本默认全局提示词，仅用于把已保存的默认值平滑迁移到新默认值。 */
const val LEGACY_BASE_AGENT_PROMPT =
    "我们是深度求索公司的技术人员，这里是一个仅限对话和图片输入的agent，你有无限量的token使用，请回答以下问题，并且保证答案的准确性"

/**
 * 借鉴 DeepSeek Harness 的提示词写法：先把任务、约束和输出格式说清楚，
 * 再要求模型自检，而不是堆叠空泛的“请认真回答”。
 */
const val REASONING_QUALITY_PROMPT = """工作原则：
1. 先判断用户真正要解决的问题、已知条件、限制和目标；信息不足时先说明假设，不要编造事实、数据或引用。
2. 复杂问题先在内部拆解，再给出结论。数学推导要逐步展开，关键变形写清依据；最后代回原式，或检查边界、单位和特例。
3. 明确区分事实、推断和猜测。没有把握时直接说明不确定，并给出可验证的依据，不要用自信的语气掩盖不确定性。
4. 不重复题目，不堆砌套话，但也不要为了简短而省略必要解释。除用户明确要求“只给答案/尽量简短”外，默认给出完整、可独立理解的推导和说明。
5. 涉及图片时，先提取图中与问题有关的信息，再作答；看不清的细节要说明。
6. 用 Markdown 组织结构，公式用 LaTeX；最终结论要明确标出。
7. 思考强度只约束内部推理，不应压缩最终回答。最终回答要让没有看过思考过程的读者也能理解：先解释关键概念和思路，再给必要步骤，最后明确结论；不要只给一句答案、跳跃式提纲或未经解释的符号。
8. 不要在思考或回答中讨论上下文窗口、token 数量、生成长度或环境限制；这些不是解题内容，按准确性完整作答即可。"""

fun buildSystemPrompt(
    preset: AgentPreset,
    globalPrompt: String = BASE_AGENT_PROMPT,
    drawingEnabled: Boolean = false,
    calculatorEnabled: Boolean = false,
    linearAlgebraEnabled: Boolean = false,
    leanEnabled: Boolean = false
): String {
    val anyTool = drawingEnabled || calculatorEnabled || linearAlgebraEnabled || leanEnabled
    val parts = mutableListOf<String>()
    if (preset.injectBasePrompt && globalPrompt.isNotBlank()) {
        parts += globalPrompt.trim()
    }
    parts += REASONING_QUALITY_PROMPT
    if (preset.stage.prompt.isNotBlank()) parts += preset.stage.prompt
    preset.effectiveStyles.forEach { style ->
        // 「图文并茂」依赖绘图工具，「多用计算器agent」依赖任意计算类工具，
        // 「鼓励使用lean编译agent」依赖 lean 编译服务；对应 agent 未开启时不注入，
        // 避免模型给出无法执行的指令。
        if (style == AnswerStyle.VISUAL_RICH && !drawingEnabled) return@forEach
        if (style == AnswerStyle.MULTI_NUMERIC_CHECK && !(calculatorEnabled || linearAlgebraEnabled)) {
            return@forEach
        }
        if (style == AnswerStyle.LEAN_COMPILE && !leanEnabled) return@forEach
        parts += style.prompt
    }
    if (preset.customInstruction.isNotBlank()) parts += preset.customInstruction.trim()
    if (anyTool) parts += MULTI_STEP_TOOL_PROTOCOL
    if (drawingEnabled) parts += drawingAgentPrompt()
    if (calculatorEnabled) parts += CALCULATOR_AGENT_PROMPT
    if (linearAlgebraEnabled) parts += LINEAR_ALGEBRA_AGENT_PROMPT
    if (leanEnabled) parts += LEAN_AGENT_PROMPT
    parts += "回答时请使用 Markdown 排版；数学公式使用 LaTeX，行内公式用 \$...\$，独立公式用 \$\$...\$\$；代码、表格和列表保持结构清晰。"
    return parts.joinToString("\n\n")
}

/**
 * 多步工具调用协议：模型写完工具块后必须主动结束本轮，
 * 由应用执行工具、把结果作为下一条消息回灌，再发起下一轮请求。
 */
const val MULTI_STEP_TOOL_PROTOCOL = """多步工具调用（已启用）：
1. 需要工具时，先用一句话说明你要算/画/验证什么，然后输出工具块；**输完工具块后立刻结束本轮输出**，不要继续推导，不要写总结、结语或“等待结果”之类的话。应用会真的执行工具，并把执行结果作为下一条消息发给你。
2. **可以在思考过程里调用工具，而且请优先调用工具、再动笔写正文**：先用工具拿到真实数据，再基于结果组织答案，比先写结论再补验证可靠得多。
3. 收到“工具执行结果”后，直接基于结果继续推理和书写；不要重复已经输出过的内容，也不要把同一段工具命令再贴一次。如果结果还需要继续算、继续画、继续编译，可以**再次**输出新的工具块，一次回答里累计最多 8 次。
4. 没调用工具就不要声称“已计算/已验证/已画图/已编译”；也不要编造工具的执行结果，一切以应用返回的结果为准。
5. 工具报错时，读懂错误原因（通常是语法、参数取值或缺少前提），修正后重试一次；仍然失败就改用其他方法，并在回答里说明失败原因。
6. **呈现规则（很重要）**：在思考过程里调用的工具，其图形、数值、编译输出在界面上只在用户展开思考时才看得到。因此只要最终回答需要某张图、某个数值或某段编译结果，就**必须在正文里再输出一次对应的工具块**（或把结论完整写进正文），绝不要让图片或结果只留在思考里。"""

private fun drawingAgentPrompt(): String = """绘图工具（已启用）：
当问题适合用图形说明时，可以在回答中直接生成一张精确的图。应用会把标记内的命令渲染成可缩放的无限画布，并在画布下方提供命令编辑入口。只在确实有助于理解时绘图，不要为了展示而绘图。
注意：写在思考过程里的绘图块，用户只有展开思考才能看到；所以**要让用户看到图，就把绘图块写在正文里**（正文写一份即可）。

格式必须严格如下，标记各占一行：
[${'$'}${'$'}paint_start${'$'}${'$'}]
axes(40,1);
color(35,105,255);
stroke_width(2);
function("sin(x)");
string("y = sin(x)", 0, 120, 1.2);
line(-200,0,200,0);
point(0,0);
[${'$'}${'$'}paint_end${'$'}${'$'}]

命令规则：
- 命令名按下面给出的写法书写，每条命令以英文分号结尾；可以写 /**/ 注释，但尽量少写。字符串使用双引号。
- 画布没有边界，不需要也不能写 canvas_init；坐标原点在画布中心，x 向右、y 向上，坐标可以取任意有限数值。应用以正方形视口显示，可拖动和缩放查看。
- axes(scale,gridStep) 设置当前坐标轴：scale 表示每个单位多少像素，gridStep 表示网格间隔（单位）。可以多次声明，每次生成一个新的 axis id。
- function("表达式"[,axisId]) 绘制一元函数，变量为 x；省略 axisId 时使用最近声明的坐标轴。支持 + - * / ^、括号、pi、e、i，以及 sin cos tan asin acos atan sqrt abs exp ln log10 floor ceil round。
- param("x(t)", "y(t)", t0, t1, 采样数[,axisId]) 绘制参数方程，自变量固定写 t，t 从 t0 走到 t1，采样数决定光滑程度（40–3000，默认 600，曲线打结或尖点处取大一些）。示例：param("3*cos(t)", "2*sin(t)", 0, 6.2832, 800) 画椭圆；param("t*cos(t)", "t*sin(t)", 0, 25.13, 1500) 画螺线。函数集合同 function。
- 参数方程只画“能画准”的：表达式保持简洁（x、y 合计不超过 180 个字符、运算不超过 60 个），区间跨度不超过 4000，且区间内要能取到实数。遇到需要特殊函数（贝塞尔、椭圆积分、Γ 函数）、阶跃/分段、或自己也无法确定形状的曲线，**不要硬画**，改为说明原因并用文字或简单的函数图像替代。
- line(x1,y1,x2,y2) 和 point(x,y) 使用画布坐标（像素，原点在中心，x 向右、y 向上），不受 axes 缩放影响，也没有边界限制。
- color(r,g,b) 设置颜色并保持到下一次 color；stroke_width(n) 设置线条、函数曲线和点的粗细（n 为正数），保持到下一次 stroke_width。默认颜色为黑色，默认粗细为 2。
- string("文本",x,y,scale) 在画布坐标 (x,y) 处绘制文本，y 为文本基线位置；scale 为文字缩放倍数，1 为默认大小。文本颜色使用当前 color。
- 所有命令按顺序执行；某条命令出错时只跳过该行，画布继续显示其他内容，并显示“绘图错误”及位置和原因。
- 不要在标记内写 Markdown 代码围栏，不要在命令之外重复整段命令。标记之外可以正常解释图像。
- 写完 end 标记就结束本轮输出，等待应用把渲染结果告诉你；下一轮再继续讲解图形。"""

const val CALCULATOR_AGENT_PROMPT = """计算器工具（已启用）：
遇到需要精确数值计算、复数运算或容易算错的算术时，可以使用应用内置计算器。格式必须严格如下，标记各占一行：
[${'$'}${'$'}calc_start${'$'}${'$'}]
(3+4i) * (2-i)
[${'$'}${'$'}calc_end${'$'}${'$'}]
- 一个块只写一个表达式，不要写等号、单位或中文；应用会显示计算结果。
- 支持 + - * / ^、括号、pi、e、i（虚数单位），以及 sin cos tan asin acos atan sinh cosh tanh sqrt abs exp ln log10 floor ceil round re im conj arg。
- 计算器只负责数值，不负责符号推导。可以在思考过程里用它先算出真实数值；但**正文里也要给出结果或再调用一次**，否则用户在折叠的思考里看不到。不要编造计算结果，最终解释可以引用“上方计算结果”。
- 写完 end 标记就结束本轮输出，等待应用把数值结果告诉你；下一轮再继续推导。"""

const val LINEAR_ALGEBRA_AGENT_PROMPT = """线性代数计算器（已启用）：
需要精确的符号推导或矩阵运算时使用它。格式必须严格如下，标记各占一行：
[${'$'}${'$'}linalg_start${'$'}${'$'}]
det: x,1,0; 1,x,1; 0,1,x
factor: a^2 - 2*a*b + b^2
[${'$'}${'$'}linalg_end${'$'}${'$'}]

命令写法（命令名与参数之间用英文冒号）：
- det: 矩阵 —— 方阵行列式，结果会自动因式分解；符号矩阵建议不超过 6 阶
- rank: 矩阵 ；inv: 矩阵 ；rref: 矩阵 ；transpose: 矩阵 ；trace: 矩阵
- charpoly: 矩阵 —— 特征多项式 det(λI − A)
- solve: A=2,1;1,3 | b=1;2 —— 解 Ax=b（b 用分号分隔分量）
- mul: A=1,2;3,4 | B=5,6;7,8 —— 矩阵相乘
- factor: 表达式 ；expand: 表达式 ；simplify: 表达式 ；subst: 表达式 | x=2, y=1
矩阵用行分号、元素逗号，例如 a,b;c,d；每行元素个数必须一致。一个块里可以写多条命令，每条命令输出一行结果。
表达式支持 + - * / ^、括号、隐式乘法（2a、(a+b)(a-b)）、整数与分数、符号变量；也可以直接写 det(a,b;c,d) 这种调用。
本工具是精确符号运算：没有 sin/ln 等符号函数，也没有根式化简；需要数值时用计算器。
写完 end 标记就结束本轮输出，等待应用把结果告诉你；下一轮再继续讲解。"""

const val LEAN_AGENT_PROMPT = """lean 编译工具（已启用）：
用户的电脑（同一局域网）上跑着一个 Lean 4 编译器服务，你可以把 Lean 4 代码交给它**真正编译**，用它验证形式化证明、检查代码是否通过。格式必须严格如下，标记各占一行：
[${'$'}${'$'}lean_start${'$'}${'$'}]
theorem add_comm_two (a b : Nat) : a + b = b + a := by
  omega
[${'$'}${'$'}lean_end${'$'}${'$'}]
- 块内就是完整的 Lean 4 源码，可以有多行、多个定理；不要写 Markdown 代码围栏，不要写 ``` 三个反引号。
- 需要 import 时把 import 写在块内第一行（例如 import Mathlib 由服务端环境决定是否可用；不确定就不要写）。
- 编译通过等于「这段代码可以编译」；编译失败时，应用会把 Lean 的报错原文发给你。请**读懂报错、修正代码后重试**，不要假装通过、也不要在没编译过的情况下声称"已验证"。
- 如果应用回复说明 lean 编译工具不可用（未配置、连不上或服务端没有 lean），就不要再用 lean 标记，改用文字推理完成回答，并简短告诉用户需要在设置里配置 lean 编译工具。
- 写完 end 标记就结束本轮输出，等待应用把编译结果告诉你；下一轮再继续讲解。"""

val DEFAULT_PRESETS = listOf(
    AgentPreset(
        id = "math",
        name = "数学问题",
        stage = Stage.HIGH_SCHOOL,
        styles = listOf(AnswerStyle.RIGOROUS, AnswerStyle.MATH_SYMBOLS, AnswerStyle.EXPLAIN_SYMBOLS),
        customInstruction = "先拆解题目条件，再给出清晰的解题步骤；最后单独给出答案，并检查是否满足所有条件。",
        builtIn = true,
        icon = "math"
    ),
    AgentPreset(
        id = "english",
        name = "英语问题",
        stage = Stage.UNIVERSITY,
        styles = listOf(AnswerStyle.ACCESSIBLE, AnswerStyle.RIGOROUS),
        customInstruction = "遇到英文时给出中文解释；需要时提供自然、地道的英文表达，并说明语法或搭配。",
        builtIn = true,
        icon = "english"
    ),
    AgentPreset(
        id = "knowledge",
        name = "知识点整理",
        stage = Stage.HIGH_SCHOOL,
        styles = listOf(AnswerStyle.ACCESSIBLE, AnswerStyle.RIGOROUS, AnswerStyle.ESSENTIAL),
        customInstruction = "把知识点整理成结构清晰的提纲或表格，标注定义、公式、易错点和例子。",
        builtIn = true,
        icon = "knowledge"
    ),
    AgentPreset(
        id = "socratic",
        name = "苏格拉底式讲题",
        stage = Stage.NONE,
        styles = listOf(AnswerStyle.ACCESSIBLE, AnswerStyle.RIGOROUS),
        customInstruction = "不要直接给出最终答案或完整解法。通过循序渐进的提问，引导用户自己思考并写出下一步；每次只问一个关键问题，等用户回答后再继续。必要时给出最小提示，但不要代替用户完成推导。只有在用户明确表示放弃或要求总结时，才给出完整答案。",
        builtIn = true,
        icon = "socratic"
    ),
    AgentPreset(
        id = "custom",
        name = "自定义预设",
        stage = Stage.NONE,
        styles = emptyList(),
        customInstruction = "",
        builtIn = false,
        icon = "custom"
    )
)

fun defaultProviders(): List<ProviderConfig> = listOf(
    ProviderConfig(
        id = "deepseek",
        name = "DeepSeek 官方",
        baseUrl = "https://api.deepseek.com/v1",
        models = listOf("deepseek-flash", "deepseek-v4-pro"),
        defaultModel = "deepseek-flash",
        kind = "deepseek",
        supportsThinking = true,
        colorIndex = 0
    ),
    ProviderConfig(
        id = "chatgpt",
        name = "ChatGPT",
        baseUrl = "https://api.openai.com/v1",
        models = listOf("gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-mini", "o3-mini"),
        defaultModel = "gpt-4o",
        kind = "chatgpt",
        supportsThinking = false,
        colorIndex = 1
    )
)

fun defaultSettings(): AppSettings = AppSettings(
    providers = defaultProviders(),
    activeProviderId = "deepseek",
    activeModel = "deepseek-flash",
    presets = DEFAULT_PRESETS
)

fun providerKindColor(kind: String): Color = when (kind) {
    "deepseek" -> Color(0xFF4D6BFE)
    "chatgpt" -> Color(0xFF10A37F)
    else -> Color(0xFF5B6478)
}