package com.smath.app.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.smath.app.api.ApiCallException
import com.smath.app.api.ApiErrorCatalog
import com.smath.app.api.ChatApi
import com.smath.app.api.buildSearchContext
import com.smath.app.data.AgentPreset
import com.smath.app.data.AnswerStyle
import com.smath.app.data.AppData
import com.smath.app.data.AppStore
import com.smath.app.data.ChatMessage
import com.smath.app.data.ContextReference
import com.smath.app.data.Conversation
import com.smath.app.data.ConversationFolder
import com.smath.app.data.ListMode
import com.smath.app.data.BASE_AGENT_PROMPT
import com.smath.app.data.ProviderConfig
import com.smath.app.data.Stage
import com.smath.app.data.ThinkingLevel
import com.smath.app.data.buildSystemPrompt
import com.smath.app.data.defaultSettings
import com.smath.app.util.TimeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class StreamingMessage(
    val conversationId: String,
    val messageId: String,
    val content: String = "",
    val reasoning: String = "",
    val phase: String = "正在思考",
    val startedAt: Long = SystemClock.elapsedRealtime(),
    val searchNotice: String? = null
)

data class OrganizeUiState(
    val running: Boolean = false,
    val error: String? = null,
    val result: String? = null
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val store = AppStore(application)
    private val api = ChatApi()

    private val _data = MutableStateFlow(store.load())
    val data: StateFlow<AppData> = _data.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _selectedPresetId = MutableStateFlow(
        _data.value.settings.presets.firstOrNull { it.id == _data.value.settings.lastPresetId }?.id
            ?: _data.value.settings.presets.firstOrNull()?.id.orEmpty()
    )
    val selectedPresetId: StateFlow<String> = _selectedPresetId.asStateFlow()

    val activeConversation: StateFlow<Conversation?> =
        combine(_data, _activeId) { data, id -> data.conversations.firstOrNull { it.id == id } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _streaming = MutableStateFlow<StreamingMessage?>(null)
    val streaming: StateFlow<StreamingMessage?> = _streaming.asStateFlow()

    private val _organize = MutableStateFlow(OrganizeUiState())
    val organize: StateFlow<OrganizeUiState> = _organize.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private val saveRequests = Channel<Unit>(Channel.CONFLATED)
    private var generationJob: Job? = null
    private var generationToken = 0
    private var organizeJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for (ignored in saveRequests) {
                store.save(_data.value)
            }
        }
    }

    val isGenerating: Boolean get() = generationJob?.isActive == true

    private fun persist() {
        saveRequests.trySend(Unit)
    }

    private fun updateData(transform: (AppData) -> AppData) {
        _data.update(transform)
        persist()
    }

    private fun updateConversation(id: String, transform: (Conversation) -> Conversation) {
        updateData { data ->
            data.copy(conversations = data.conversations.map {
                if (it.id == id) transform(it) else it
            })
        }
    }

    fun currentPreset(): AgentPreset {
        val settings = _data.value.settings
        return settings.presets.firstOrNull { it.id == _selectedPresetId.value }
            ?: settings.presets.firstOrNull()
            ?: AgentPreset(id = "custom", name = "自定义预设")
    }

    fun newChat() {
        if (_activeId.value == null) {
            _events.tryEmit("已在新会话中")
            return
        }
        if (generationJob?.isActive == true) generationJob?.cancel()
        _activeId.value = null
        val last = _data.value.settings.lastPresetId
        _selectedPresetId.value = _data.value.settings.presets
            .firstOrNull { it.id == last }?.id
            ?: _data.value.settings.presets.firstOrNull()?.id.orEmpty()
    }

    /** direction 为 -1 上移、1 下移；排序结果会直接保存并同步到首页预设列表。 */
    fun movePreset(presetId: String, direction: Int) {
        updateData { data ->
            val list = data.settings.presets.toMutableList()
            val index = list.indexOfFirst { it.id == presetId }
            if (index < 0) return@updateData data
            val target = (index + direction).coerceIn(0, list.lastIndex)
            if (target == index) return@updateData data
            val item = list.removeAt(index)
            list.add(target, item)
            data.copy(settings = data.settings.copy(presets = list))
        }
    }

    fun openConversation(id: String) {
        if (_activeId.value == id) return
        if (generationJob?.isActive == true) generationJob?.cancel()
        _activeId.value = id
    }

    fun selectPreset(id: String) {
        if (_activeId.value != null) {
            _events.tryEmit("预设只在新对话开始时注入，请先新建对话")
            return
        }
        val preset = _data.value.settings.presets.firstOrNull { it.id == id } ?: return
        _selectedPresetId.value = id
        updateData { it.copy(settings = it.settings.copy(lastPresetId = preset.id)) }
    }

    fun updatePreset(preset: AgentPreset) {
        updateData { data ->
            data.copy(settings = data.settings.copy(
                presets = data.settings.presets.map { if (it.id == preset.id) preset else it }
            ))
        }
    }

    fun addPreset(preset: AgentPreset) {
        val created = preset.copy(
            id = "custom-${UUID.randomUUID()}",
            name = preset.name.trim().ifBlank { "自定义预设" },
            builtIn = false,
            icon = preset.icon.ifBlank { "custom" }
        )
        updateData { data ->
            data.copy(settings = data.settings.copy(
                presets = data.settings.presets + created,
                lastPresetId = created.id
            ))
        }
        if (_activeId.value == null) _selectedPresetId.value = created.id
    }

    /** 导入的预设一律作为新的自定义预设追加，并自动处理重名。 */
    fun importPresets(presets: List<AgentPreset>) {
        if (presets.isEmpty()) return
        val used = _data.value.settings.presets.map { it.name }.toMutableSet()
        val created = presets.map { preset ->
            val base = preset.name.trim().ifBlank { "自定义预设" }
            var candidate = base
            var index = 2
            while (candidate in used) {
                candidate = "$base ($index)"
                index++
            }
            used += candidate
            preset.copy(
                id = "custom-${UUID.randomUUID()}",
                name = candidate,
                builtIn = false,
                icon = preset.icon.ifBlank { "custom" }
            )
        }
        updateData { data ->
            data.copy(settings = data.settings.copy(presets = data.settings.presets + created))
        }
        _events.tryEmit("已导入 ${created.size} 个预设")
    }

    fun deletePreset(presetId: String) {
        val target = _data.value.settings.presets.firstOrNull { it.id == presetId } ?: return
        if (target.builtIn) return
        val remaining = _data.value.settings.presets.filterNot { it.id == presetId }
        val fallbackId = remaining.firstOrNull()?.id.orEmpty()
        updateData { data ->
            data.copy(settings = data.settings.copy(
                presets = remaining,
                lastPresetId = if (data.settings.lastPresetId == presetId) {
                    fallbackId
                } else {
                    data.settings.lastPresetId
                }
            ))
        }
        if (_selectedPresetId.value == presetId) {
            _selectedPresetId.value = fallbackId
        }
    }

    fun resetPresetToDefault(presetId: String) {
        val defaults = com.smath.app.data.DEFAULT_PRESETS
        val original = defaults.firstOrNull { it.id == presetId } ?: return
        updatePreset(original)
    }

    fun send(
        text: String,
        imagePaths: List<String>,
        contextRefs: List<ContextReference> = emptyList()
    ) {
        val clean = text.trim()
        if (clean.isEmpty() && imagePaths.isEmpty()) return
        if (generationJob?.isActive == true) {
            _events.tryEmit("正在回答中，请稍候或先停止生成")
            return
        }
        var conv = _activeId.value?.let { id -> _data.value.conversations.firstOrNull { it.id == id } }
        if (conv == null) {
            val preset = currentPreset()
            conv = Conversation(
                id = UUID.randomUUID().toString(),
                title = makeTitle(clean, imagePaths.isNotEmpty()),
                systemPrompt = buildSystemPrompt(
                    preset = preset,
                    globalPrompt = _data.value.settings.globalPrompt,
                    drawingEnabled = _data.value.settings.drawingAgentEnabled,
                    calculatorEnabled = _data.value.settings.calculatorAgentEnabled
                ),
                presetId = preset.id,
                presetName = preset.name,
                stageLabel = if (preset.stage == Stage.NONE) "" else preset.stage.label,
                styles = preset.effectiveStyles.map { it.label },
                messages = emptyList()
            )
            val created = conv
            updateData { data ->
                data.copy(
                    conversations = listOf(created) + data.conversations,
                    settings = data.settings.copy(lastPresetId = preset.id)
                )
            }
            _activeId.value = created.id
            _selectedPresetId.value = preset.id
            conv = created
        }
        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = "user",
            content = clean,
            imagePaths = imagePaths,
            contextRefs = contextRefs
        )
        val firstUser = conv.messages.none { it.role == "user" }
        updateConversation(conv.id) { current ->
            current.copy(
                title = if (firstUser) makeTitle(clean, imagePaths.isNotEmpty()) else current.title,
                messages = current.messages + userMessage,
                updatedAt = System.currentTimeMillis()
            )
        }
        startGeneration(conv.id)
    }

    private fun makeTitle(text: String, hasImages: Boolean): String {
        val base = text.replace(Regex("\\s+"), " ").trim()
        return when {
            base.isNotEmpty() -> base.take(24)
            hasImages -> "图片对话"
            else -> "新对话"
        }
    }

    private fun startGeneration(conversationId: String) {
        val conversation = _data.value.conversations.firstOrNull { it.id == conversationId } ?: return
        val settings = _data.value.settings
        val provider = settings.providers.firstOrNull { it.id == settings.activeProviderId }
        val model = settings.activeModel.ifBlank { provider?.displayModel.orEmpty() }
        generationToken += 1
        val token = generationToken
        val messageId = UUID.randomUUID().toString()
        val startedAt = SystemClock.elapsedRealtime()

        generationJob = viewModelScope.launch {
            val content = StringBuilder()
            val reasoning = StringBuilder()
            var lastPublish = 0L
            var firstContentAt = 0L
            var citations = emptyList<com.smath.app.data.Citation>()
            var searchNotice: String? = null
            var phase = "正在思考"

            fun publish(force: Boolean = false) {
                val now = SystemClock.elapsedRealtime()
                if (!force && now - lastPublish < 55) return
                lastPublish = now
                if (token != generationToken) return
                _streaming.value = StreamingMessage(
                    conversationId = conversationId,
                    messageId = messageId,
                    content = content.toString(),
                    reasoning = reasoning.toString(),
                    phase = phase,
                    startedAt = startedAt,
                    searchNotice = searchNotice
                )
            }

            publish(true)
            try {
                if (provider == null) {
                    throw ApiCallException(
                        com.smath.app.data.ApiErrorInfo(
                            code = 0,
                            label = "没有可用的服务商",
                            summary = "当前没有选择模型服务商。",
                            detail = "请到「模型和 API Key」中添加并选择一个服务商。",
                            retryable = false
                        )
                    )
                }
                if (settings.webSearchByDefault) {
                    phase = "正在联网搜索"
                    publish(true)
                    val query = conversation.messages.lastOrNull { it.role == "user" }?.content.orEmpty()
                        .ifBlank { "图片内容" }
                    val searchKey = settings.searchApiKey.ifBlank {
                        settings.providers.firstOrNull { it.id == "deepseek" }?.apiKey.orEmpty()
                    }
                    try {
                        val outcome = api.search(query, searchKey, settings.searchCount)
                        citations = outcome.citations
                        searchNotice = "已联网搜索 · ${outcome.citations.size} 个来源"
                    } catch (e: ApiCallException) {
                        searchNotice = "联网搜索未完成：${e.info.label}。已改为直接回答。"
                    }
                    phase = "正在思考"
                    publish(true)
                }
                val history = conversation.messages.filter {
                    it.role == "user" || (it.role == "assistant" && it.error == null && it.content.isNotBlank())
                }
                val searchContext = if (citations.isNotEmpty()) {
                    val outcome = com.smath.app.api.SearchOutcome(
                        conversation.messages.lastOrNull { it.role == "user" }?.content.orEmpty(),
                        citations
                    )
                    buildSearchContext(outcome)
                } else ""
                val system = listOf(conversation.systemPrompt, searchContext)
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
                val isFirstExchange = conversation.messages.count { it.role == "user" } == 1

                var finishReason: String? = null
                var continuation = 0
                while (true) {
                    val requestHistory = if (continuation == 0) {
                        history
                    } else {
                        history + listOf(
                            ChatMessage(
                                id = "$messageId-continued-$continuation",
                                role = "assistant",
                                content = content.toString()
                            ),
                            ChatMessage(
                                id = "$messageId-continue-request-$continuation",
                                role = "user",
                                content = "你上一条回答在句子中间被输出长度上限截断了。请紧接截断处继续写，不要重复已经输出的内容，也不要重新开头或总结。"
                            )
                        )
                    }
                    finishReason = null
                    api.streamChat(
                        provider = provider,
                        model = model,
                        systemPrompt = system,
                        messages = requestHistory,
                        thinkingLevel = settings.thinking,
                        onReasoning = { chunk ->
                            if (token == generationToken) {
                                reasoning.append(chunk)
                                publish()
                            }
                        },
                        onContent = { chunk ->
                            if (token == generationToken) {
                                if (firstContentAt == 0L) firstContentAt = SystemClock.elapsedRealtime()
                                phase = "正在回答"
                                content.append(chunk)
                                publish()
                            }
                        },
                        onCitations = { list ->
                            if (token == generationToken) {
                                citations = (citations + list).distinctBy { it.url }
                                publish(true)
                            }
                        },
                        onFinish = { reason -> finishReason = reason }
                    )
                    if (finishReason != "length" || content.isBlank() || continuation >= 2) break
                    continuation += 1
                    if (!content.endsWith("\n")) content.append("\n")
                    phase = "正在继续回答"
                    publish(true)
                }
                val finalContent = content.toString()
                val finalReasoning = reasoning.toString()
                val thinkingMs = when {
                    finalReasoning.isBlank() -> 0L
                    firstContentAt > 0 -> firstContentAt - startedAt
                    else -> SystemClock.elapsedRealtime() - startedAt
                }
                val notice = buildList {
                    searchNotice?.let(::add)
                    if (finishReason == "length") {
                        add(if (continuation > 0) "已自动续写 $continuation 次，输出仍达到长度上限" else "输出达到长度上限，内容可能不完整")
                    }
                }.joinToString(" · ").ifBlank { null }
                appendAssistant(
                    conversationId = conversationId,
                    ChatMessage(
                        id = messageId,
                        role = "assistant",
                        content = finalContent.ifBlank { "（模型没有返回内容，请重试。）" },
                        reasoning = finalReasoning,
                        thinkingMs = thinkingMs,
                        citations = citations,
                        notice = notice
                    )
                )
                if (isFirstExchange && finalContent.isNotBlank() && token == generationToken) {
                    generateConversationTitle(
                        conversationId = conversationId,
                        provider = provider,
                        model = model,
                        userText = conversation.messages.lastOrNull { it.role == "user" }?.content.orEmpty(),
                        assistantText = finalContent
                    )
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    if (token == generationToken) {
                        val finalContent = content.toString()
                        appendAssistant(
                            conversationId = conversationId,
                            ChatMessage(
                                id = messageId,
                                role = "assistant",
                                content = finalContent.ifBlank { "（已停止生成）" },
                                reasoning = reasoning.toString(),
                                citations = citations,
                                notice = if (finalContent.isNotBlank()) "已停止生成" else null
                            )
                        )
                    }
                }
                throw e
            } catch (e: ApiCallException) {
                if (token == generationToken) {
                    appendAssistant(
                        conversationId = conversationId,
                        ChatMessage(
                            id = messageId,
                            role = "assistant",
                            content = content.toString(),
                            reasoning = reasoning.toString(),
                            citations = citations,
                            error = e.info,
                            notice = searchNotice
                        )
                    )
                }
            } catch (t: Throwable) {
                if (token == generationToken) {
                    appendAssistant(
                        conversationId = conversationId,
                        ChatMessage(
                            id = messageId,
                            role = "assistant",
                            content = content.toString(),
                            reasoning = reasoning.toString(),
                            citations = citations,
                            error = ApiErrorCatalog.fromThrowable(t),
                            notice = searchNotice
                        )
                    )
                }
            } finally {
                if (token == generationToken) {
                    _streaming.value = null
                    generationJob = null
                }
            }
        }
    }

    private fun generateConversationTitle(
        conversationId: String,
        provider: ProviderConfig,
        model: String,
        userText: String,
        assistantText: String
    ) {
        if (userText.isBlank() || assistantText.isBlank()) return
        viewModelScope.launch {
            val raw = runCatching {
                api.generateTitle(provider, model, userText, assistantText)
            }.getOrNull().orEmpty()
            val title = cleanGeneratedTitle(raw)
            if (title.isBlank()) return@launch
            // 用户可能在标题生成期间手动改名；titleGenerated 为 true 时不覆盖。
            updateConversation(conversationId) { conv ->
                if (conv.titleGenerated) conv
                else conv.copy(title = title, titleGenerated = true)
            }
        }
    }

    private fun cleanGeneratedTitle(raw: String): String {
        var title = raw.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        title = title.removePrefix("标题：").removePrefix("标题:").trim()
        title = title.trim('"', '\'', '“', '”', '「', '」', '《', '》', '。', '.', ' ')
        return title.replace(Regex("\\s+"), " ").take(24)
    }

    private fun appendAssistant(conversationId: String, message: ChatMessage) {
        updateConversation(conversationId) { conv ->
            if (conv.messages.any { it.id == message.id }) conv
            else conv.copy(messages = conv.messages + message, updatedAt = System.currentTimeMillis())
        }
    }

    fun stopGenerating() {
        generationJob?.cancel()
    }

    fun retryLast() {
        regenerateLast()
    }

    /** 重新生成最后一次回答：丢掉末尾的 assistant 消息，再从用户那一轮重新请求。 */
    fun regenerateLast() {
        val conv = _activeId.value?.let { id -> _data.value.conversations.firstOrNull { it.id == id } } ?: return
        if (generationJob?.isActive == true) {
            _events.tryEmit("正在回答中，请稍候或先停止生成")
            return
        }
        val lastAssistant = conv.messages.indexOfLast { it.isAssistant }
        val kept = if (lastAssistant >= 0) conv.messages.take(lastAssistant) else conv.messages
        if (kept.none { it.role == "user" }) return
        updateConversation(conv.id) {
            it.copy(messages = kept, updatedAt = System.currentTimeMillis())
        }
        startGeneration(conv.id)
    }

    /**
     * 修改某条用户消息后重新生成：截断该消息之后的所有内容，用新文本重新提问。
     * 图片与 @ 上下文引用保持不变。
     */
    fun editUserMessage(messageId: String, newText: String) {
        val conv = _activeId.value?.let { id -> _data.value.conversations.firstOrNull { it.id == id } } ?: return
        if (generationJob?.isActive == true) {
            _events.tryEmit("正在回答中，请稍候或先停止生成")
            return
        }
        val index = conv.messages.indexOfFirst { it.id == messageId && it.role == "user" }
        if (index < 0) return
        val clean = newText.trim()
        if (clean.isEmpty()) {
            _events.tryEmit("消息内容不能为空")
            return
        }
        val edited = conv.messages[index].copy(content = clean)
        val kept = conv.messages.take(index) + edited
        updateConversation(conv.id) {
            it.copy(
                messages = kept,
                title = if (it.titleGenerated) it.title else makeTitle(clean, edited.imagePaths.isNotEmpty()),
                updatedAt = System.currentTimeMillis()
            )
        }
        startGeneration(conv.id)
    }

    fun setWebSearch(enabled: Boolean) {
        updateData { it.copy(settings = it.settings.copy(webSearchByDefault = enabled)) }
    }

    fun setListMode(mode: ListMode) {
        updateData { it.copy(settings = it.settings.copy(listMode = mode)) }
    }

    fun setThinkingLevel(level: ThinkingLevel) {
        updateData { it.copy(settings = it.settings.copy(thinkingLevel = level)) }
    }

    fun setDarkMode(mode: String) {
        val safe = mode.takeIf { it in listOf("system", "light", "dark") } ?: "system"
        updateData { it.copy(settings = it.settings.copy(darkMode = safe)) }
    }

    fun setScreenshotEnabled(enabled: Boolean) {
        updateData { it.copy(settings = it.settings.copy(screenshotEnabled = enabled)) }
    }

    fun setGlobalPrompt(prompt: String) {
        val clean = prompt.trim().ifBlank { BASE_AGENT_PROMPT }
        updateData { it.copy(settings = it.settings.copy(globalPrompt = clean)) }
    }

    fun setDrawingAgentEnabled(enabled: Boolean) {
        updateData { it.copy(settings = it.settings.copy(drawingAgentEnabled = enabled)) }
    }

    fun setCalculatorAgentEnabled(enabled: Boolean) {
        updateData { it.copy(settings = it.settings.copy(calculatorAgentEnabled = enabled)) }
    }

    fun setDrawingCanvasSize(size: Int) {
        val safe = size.coerceIn(320, 1600)
        updateData { it.copy(settings = it.settings.copy(drawingCanvasSize = safe)) }
    }

    fun createFolder(name: String): String? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        val existing = _data.value.folders.firstOrNull { it.name.equals(clean, ignoreCase = true) }
        if (existing != null) return existing.id
        val folder = ConversationFolder(
            id = UUID.randomUUID().toString(),
            name = clean.take(24),
            sortOrder = _data.value.folders.size
        )
        updateData { it.copy(folders = it.folders + folder) }
        return folder.id
    }

    fun renameFolder(folderId: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        updateData { data ->
            data.copy(folders = data.folders.map {
                if (it.id == folderId) it.copy(name = clean.take(24)) else it
            })
        }
    }

    fun deleteFolder(folderId: String) {
        updateData { data ->
            data.copy(
                folders = data.folders.filterNot { it.id == folderId },
                conversations = data.conversations.map {
                    if (it.folderId == folderId) it.copy(folderId = null) else it
                }
            )
        }
    }

    fun moveConversation(conversationId: String, folderId: String?) {
        updateConversation(conversationId) { it.copy(folderId = folderId) }
    }

    fun togglePinned(conversationId: String) {
        updateConversation(conversationId) { it.copy(pinned = !it.pinned) }
    }

    fun renameConversation(conversationId: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        updateConversation(conversationId) {
            it.copy(title = clean.take(40), titleGenerated = true)
        }
    }

    fun deleteConversation(conversationId: String) {
        if (_activeId.value == conversationId) {
            if (generationJob?.isActive == true) generationJob?.cancel()
            _activeId.value = null
        }
        updateData { data -> data.copy(conversations = data.conversations.filterNot { it.id == conversationId }) }
    }

    fun updateProvider(provider: ProviderConfig) {
        updateData { data ->
            val isActive = data.settings.activeProviderId == provider.id
            var model = data.settings.activeModel
            if (isActive) {
                model = if (provider.models.contains(model)) model else provider.displayModel
            }
            data.copy(settings = data.settings.copy(
                providers = data.settings.providers.map { if (it.id == provider.id) provider else it },
                activeModel = model
            ))
        }
    }

    /**
     * 新建服务商。以前这里直接把 name / baseUrl / 模型写成示例值塞进输入框，
     * 用户得先删掉 "https://api.example.com/v1" 才能填自己的地址——现在只写入
     * 用户真正填过的内容，示例值退到 placeholder。
     */
    fun addProvider(
        name: String,
        baseUrl: String,
        modelNames: List<String>,
        apiKey: String = ""
    ): String? {
        val cleanUrl = normalizeBaseUrl(baseUrl)
        val models = modelNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleanUrl.isBlank() || models.isEmpty()) return null
        val provider = ProviderConfig(
            id = "custom_${System.currentTimeMillis()}",
            name = name.trim().ifBlank { "自定义服务商" },
            baseUrl = cleanUrl,
            apiKey = apiKey.trim(),
            models = models,
            defaultModel = models.first(),
            kind = "custom",
            colorIndex = _data.value.settings.providers.size
        )
        updateData { it.copy(settings = it.settings.copy(providers = it.settings.providers + provider)) }
        return provider.id
    }

    private fun normalizeBaseUrl(raw: String): String {
        val text = raw.trim().trimEnd('/')
        if (text.isBlank()) return ""
        return when {
            text.startsWith("http://", ignoreCase = true) -> text
            text.startsWith("https://", ignoreCase = true) -> text
            else -> "https://$text"
        }
    }

    fun deleteProvider(providerId: String) {
        val settings = _data.value.settings
        if (settings.providers.size <= 1) return
        val remaining = settings.providers.filterNot { it.id == providerId }
        val active = if (settings.activeProviderId == providerId) remaining.first() else null
        updateData {
            it.copy(settings = it.settings.copy(
                providers = remaining,
                activeProviderId = active?.id ?: settings.activeProviderId,
                activeModel = active?.displayModel ?: settings.activeModel
            ))
        }
    }

    fun setActiveProvider(providerId: String) {
        val provider = _data.value.settings.providers.firstOrNull { it.id == providerId } ?: return
        updateData { it.copy(settings = it.settings.copy(
            activeProviderId = provider.id,
            activeModel = provider.displayModel
        )) }
    }

    fun setActiveModel(model: String) {
        updateData { it.copy(settings = it.settings.copy(activeModel = model)) }
    }

    fun setSearchKey(key: String) {
        updateData { it.copy(settings = it.settings.copy(searchApiKey = key)) }
    }

    fun setSearchCount(count: Int) {
        updateData { it.copy(settings = it.settings.copy(searchCount = count.coerceIn(1, 20))) }
    }

    fun organizeWithAi() {
        if (_organize.value.running) return
        val data = _data.value
        if (data.conversations.isEmpty()) {
            _organize.value = OrganizeUiState(error = "还没有可以整理的会话。")
            return
        }
        val settings = data.settings
        val provider = settings.providers.firstOrNull { it.id == settings.activeProviderId }
        if (provider == null) {
            _organize.value = OrganizeUiState(error = "请先在设置中选择一个模型服务商。")
            return
        }
        _organize.value = OrganizeUiState(running = true)
        organizeJob?.cancel()
        organizeJob = viewModelScope.launch {
            try {
                val titles = data.conversations.take(200).joinToString("\n") { conv ->
                    "- id=${conv.id} | 标题=${conv.title.replace("\n", " ").take(60)} | 最近更新=${TimeUtils.formatDateTime(conv.updatedAt)}"
                }
                val prompt = """
                    请把下面的会话整理成少量主题文件夹，方便在侧边栏查找。
                    要求：
                    1. 文件夹数量控制在 3 到 8 个，名称简洁（不超过 8 个汉字）。
                    2. 每个会话必须且只能放入一个文件夹，不能遗漏。
                    3. 文件夹名称要能概括其中的会话内容，不要使用日期作为名称。
                    4. 只输出 JSON，不要输出 Markdown 代码块或解释。

                    输出格式：
                    {"folders":[{"name":"文件夹名","conversation_ids":["id1","id2"]}]}

                    会话列表：
                    $titles
                """.trimIndent()
                val system = "你是会话整理助手。你只输出合法 JSON，不输出任何其他文字。"
                val builder = StringBuilder()
                api.streamChat(
                    provider = provider,
                    model = settings.activeModel.ifBlank { provider.displayModel },
                    systemPrompt = system,
                    messages = listOf(
                        ChatMessage(
                            id = UUID.randomUUID().toString(),
                            role = "user",
                            content = prompt
                        )
                    ),
                    onContent = { builder.append(it) }
                )
                val plan = parseOrganizePlan(builder.toString(), data.conversations.map { it.id }.toSet())
                if (plan.isEmpty()) {
                    _organize.value = OrganizeUiState(error = "AI 返回的内容不是可识别的整理方案，请重试。")
                    return@launch
                }
                applyOrganizePlan(plan)
                _organize.value = OrganizeUiState(
                    result = "已整理为 ${plan.size} 个文件夹"
                )
            } catch (e: ApiCallException) {
                _organize.value = OrganizeUiState(
                    error = "${e.info.label}：${e.info.summary}\n${e.info.detail}"
                )
            } catch (t: Throwable) {
                _organize.value = OrganizeUiState(
                    error = "整理失败：${t.message ?: t.javaClass.simpleName}"
                )
            }
        }
    }

    private data class AiFolderPlan(val name: String, val ids: List<String>)

    private fun parseOrganizePlan(raw: String, validIds: Set<String>): List<AiFolderPlan> {
        var text = raw.trim()
        text = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        text = text.substring(start, end + 1)
        return try {
            val root = JsonParser.parseString(text)
            if (!root.isJsonObject) return emptyList()
            val obj = root.asJsonObject
            val foldersArray = obj.getAsJsonArray("folders")
            val result = mutableListOf<AiFolderPlan>()
            if (foldersArray != null) {
                foldersArray.forEach { element ->
                    if (!element.isJsonObject) return@forEach
                    val folderObj = element.asJsonObject
                    val name = folderObj.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                        ?: return@forEach
                    val ids = readIds(folderObj).filter { validIds.contains(it) }.distinct()
                    if (name.isNotBlank() && ids.isNotEmpty()) {
                        result += AiFolderPlan(name.take(24), ids)
                    }
                }
            } else {
                obj.entrySet().forEach { (name, value) ->
                    if (!value.isJsonArray) return@forEach
                    val ids = value.asJsonArray.mapNotNull {
                        if (it.isJsonPrimitive) it.asString else null
                    }.filter { validIds.contains(it) }.distinct()
                    if (name.isNotBlank() && ids.isNotEmpty()) {
                        result += AiFolderPlan(name.take(24), ids)
                    }
                }
            }
            result
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun readIds(obj: JsonObject): List<String> {
        val keys = listOf("conversation_ids", "conversationIds", "ids")
        keys.forEach { key ->
            val arr = obj.getAsJsonArray(key)
            if (arr != null) {
                return arr.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
            }
        }
        return emptyList()
    }

    private fun applyOrganizePlan(plan: List<AiFolderPlan>) {
        val now = System.currentTimeMillis()
        val newFolders = mutableListOf<ConversationFolder>()
        val nameToId = mutableMapOf<String, String>()
        val convFolder = mutableMapOf<String, String>()
        plan.forEachIndexed { index, aiFolder ->
            val key = aiFolder.name.trim().lowercase()
            if (key.isEmpty() || nameToId.containsKey(key)) return@forEachIndexed
            val folder = ConversationFolder(
                id = UUID.randomUUID().toString(),
                name = aiFolder.name,
                createdAt = now,
                sortOrder = index
            )
            nameToId[key] = folder.id
            newFolders += folder
            aiFolder.ids.forEach { convFolder[it] = folder.id }
        }
        updateData { data ->
            data.copy(
                folders = newFolders,
                conversations = data.conversations.map {
                    it.copy(folderId = convFolder[it.id])
                }
            )
        }
    }

    fun clearOrganizeState() {
        _organize.value = OrganizeUiState()
    }

    fun dismissEvent() {}
}