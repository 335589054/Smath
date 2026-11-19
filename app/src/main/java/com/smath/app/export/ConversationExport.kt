package com.smath.app.export

import android.content.Context
import android.net.Uri
import com.smath.app.data.AppData
import com.smath.app.data.ChatMessage
import com.smath.app.data.Conversation
import com.smath.app.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Export is deliberately independent from the UI model: a snapshot of [Conversation]
 * is enough, so exporting cannot change chat state or include API keys.
 */
object ConversationExport {
    fun suggestedMarkdownName(conversation: Conversation): String =
        "Smath-${safeName(conversation.title, 42)}-${timestamp("yyyyMMdd-HHmm")}.md"

    fun suggestedZipName(): String = "Smath-全部对话-${timestamp("yyyyMMdd-HHmm")}.zip"

    suspend fun writeMarkdown(
        context: Context,
        uri: Uri,
        conversation: Conversation
    ) = withContext(Dispatchers.IO) {
        val markdown = buildMarkdown(conversation) { path, _ -> ImageUtils.toDataUrl(path) }
        val stream = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("无法创建导出文件")
        stream.bufferedWriter(Charsets.UTF_8).use { it.write(markdown) }
    }

    suspend fun writeAllZip(
        context: Context,
        uri: Uri,
        data: AppData
    ): Int = withContext(Dispatchers.IO) {
        val conversations = data.conversations.sortedByDescending { it.updatedAt }
        if (conversations.isEmpty()) throw IOException("还没有可导出的对话")
        val stream = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("无法创建导出文件")
        BufferedOutputStream(stream).use { buffered ->
            ZipOutputStream(buffered).use { zip ->
                addTextEntry(
                    zip,
                    "README.md",
                    buildString {
                        appendLine("# Smath 对话导出")
                        appendLine()
                        appendLine("导出时间：${timestamp("yyyy-MM-dd HH:mm")}")
                        appendLine()
                        appendLine("共 ${conversations.size} 个对话。Markdown 位于 `conversations/`，图片位于 `images/`。")
                        appendLine()
                        appendLine("此压缩包不包含设置、API Key 或任何凭据。")
                    }
                )
                var exported = 0
                conversations.forEachIndexed { index, conversation ->
                    val imageLinks = LinkedHashMap<String, String>()
                    var imageIndex = 0
                    conversation.messages.forEach { message ->
                        message.imagePaths.forEach { path ->
                            val file = File(path)
                            if (!file.isFile || file.length() == 0L) return@forEach
                            val ext = file.extension.lowercase().ifBlank { "jpg" }
                            val entryName = "images/${index + 1}-${++imageIndex}.$ext"
                            zip.putNextEntry(ZipEntry(entryName))
                            file.inputStream().use { input -> input.copyTo(zip) }
                            zip.closeEntry()
                            imageLinks[path] = "../$entryName"
                        }
                    }
                    val name = "conversations/${index + 1}-${safeName(conversation.title, 48)}.md"
                    addTextEntry(zip, name, buildMarkdown(conversation) { path, _ -> imageLinks[path] })
                    exported++
                }
                exported
            }
        }
    }

    private fun buildMarkdown(
        conversation: Conversation,
        imageUrl: ((String, Int) -> String?)? = null
    ): String = buildString {
        appendLine("# ${conversation.title.ifBlank { "Smath 对话" }}")
        appendLine()
        appendLine("> 创建时间：${formatDate(conversation.createdAt)}")
        appendLine("> 导出时间：${formatDate(System.currentTimeMillis())}")
        if (conversation.presetName.isNotBlank()) {
            appendLine("> Agent 预设：${conversation.presetName}")
        }
        if (conversation.stageLabel.isNotBlank()) {
            appendLine("> 学段：${conversation.stageLabel}")
        }
        if (conversation.styles.isNotEmpty()) {
            appendLine("> 回答风格：${conversation.styles.joinToString("、")}")
        }
        appendLine()
        conversation.messages.forEach { message ->
            if (message.content.isBlank() && message.imagePaths.isEmpty() && message.error == null) {
                return@forEach
            }
            appendLine("---")
            appendLine()
            val role = if (message.isUser) "用户" else "助手"
            appendLine("### $role · ${formatDate(message.createdAt)}")
            appendLine()
            if (message.content.isNotBlank()) {
                appendLine(message.content.trim())
                appendLine()
            }
            message.imagePaths.forEachIndexed { index, path ->
                val url = imageUrl?.invoke(path, index)
                if (!url.isNullOrBlank()) {
                    appendLine("![图片 ${index + 1}]($url)")
                    appendLine()
                }
            }
            if (message.reasoning.isNotBlank()) {
                appendLine("<details>")
                appendLine("<summary>思考过程</summary>")
                appendLine()
                appendLine(message.reasoning.trim())
                appendLine()
                appendLine("</details>")
                appendLine()
            }
            message.error?.let { error ->
                appendLine("> 调用失败：${error.label.ifBlank { "错误" }}")
                if (error.summary.isNotBlank()) appendLine("> ${error.summary}")
                if (error.raw.isNotBlank()) appendLine("> 原始信息：${error.raw}")
                appendLine()
            }
            message.notice?.takeIf { it.isNotBlank() }?.let {
                appendLine("> $it")
                appendLine()
            }
            if (message.citations.isNotEmpty()) {
                appendLine("**参考来源**")
                appendLine()
                message.citations.forEachIndexed { index, citation ->
                    val title = citation.title.ifBlank { citation.url }
                    appendLine("${index + 1}. [$title](${citation.url})")
                }
                appendLine()
            }
        }
    }

    private fun addTextEntry(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun formatDate(value: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(value))

    private fun timestamp(pattern: String): String =
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date())

    private fun safeName(raw: String, maxLength: Int): String {
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
        return cleaned.take(maxLength).ifBlank { "对话" }
    }
}