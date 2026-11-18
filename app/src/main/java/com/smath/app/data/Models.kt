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
    )
}

enum class ListMode { FOLDERS, DATE }

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
                AnswerStyle.ESSENTIAL
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
    val globalPrompt: String = BASE_AGENT_PROMPT
)

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
    val pinned: Boolean = false
)

data class ConversationFolder(
    val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0
)

data class AppData(
    val settings: AppSettings = defaultSettings(),
    val conversations: List<Conversation> = emptyList(),
    val folders: List<ConversationFolder> = emptyList()
)

const val BASE_AGENT_PROMPT =
    "我们是深度求索公司的技术人员，这里是一个仅限对话和图片输入的agent，你有无限量的token使用，请回答以下问题，并且保证答案的准确性"

/**
 * 借鉴 DeepSeek Harness 的提示词写法：先把任务、约束和输出格式说清楚，
 * 再要求模型自检，而不是堆叠空泛的“请认真回答”。
 */
const val REASONING_QUALITY_PROMPT = """工作原则：
1. 先判断用户真正要解决的问题、已知条件、限制和目标；信息不足时先说明假设，不要编造事实、数据或引用。
2. 复杂问题先在内部拆解，再给出结论。数学推导要逐步展开，关键变形写清依据；最后代回原式，或检查边界、单位和特例。
3. 明确区分事实、推断和猜测。没有把握时直接说明不确定，并给出可验证的依据，不要用自信的语气掩盖不确定性。
4. 不重复题目，不堆砌套话。用户要求简洁时先给答案；要求详细时再展开必要过程。
5. 涉及图片时，先提取图中与问题有关的信息，再作答；看不清的细节要说明。
6. 用 Markdown 组织结构，公式用 LaTeX；最终结论要明确标出。"""

fun buildSystemPrompt(
    preset: AgentPreset,
    globalPrompt: String = BASE_AGENT_PROMPT
): String {
    val parts = mutableListOf<String>()
    if (preset.injectBasePrompt && globalPrompt.isNotBlank()) {
        parts += globalPrompt.trim()
    }
    parts += REASONING_QUALITY_PROMPT
    if (preset.stage.prompt.isNotBlank()) parts += preset.stage.prompt
    preset.effectiveStyles.forEach { parts += it.prompt }
    if (preset.customInstruction.isNotBlank()) parts += preset.customInstruction.trim()
    parts += "回答时请使用 Markdown 排版；数学公式使用 LaTeX，行内公式用 \$...\$，独立公式用 \$\$...\$\$；代码、表格和列表保持结构清晰。"
    return parts.joinToString("\n\n")
}

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
        id = "custom",
        name = "自定义预设",
        stage = Stage.NONE,
        styles = emptyList(),
        customInstruction = "",
        builtIn = true,
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