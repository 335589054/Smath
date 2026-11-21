package com.smath.app.data

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Agent 预设的导出 / 导入。
 *
 * 导出为 ZIP，内含 `smath-presets.json`（自描述格式，含版本号）和一份说明。
 * 导入同时兼容：
 * - 本应用导出的 ZIP（可一次包含多个预设）
 * - 只含一个 JSON 数组（或 `{"presets":[...]}`）的 ZIP
 * - 直接选择的 `.json` 文件
 */
object PresetTransfer {

    const val ENTRY_NAME = "smath-presets.json"
    private const val BUNDLE_APP = "Smath"

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun suggestedZipName(): String = "smath-presets.zip"

    private data class PresetDto(
        val name: String = "",
        val stage: String = "NONE",
        val styles: List<String> = emptyList(),
        val customInstruction: String = "",
        val injectBasePrompt: Boolean = true,
        val icon: String = "custom"
    )

    private data class PresetBundle(
        val app: String = BUNDLE_APP,
        val version: Int = 1,
        val exportedAt: Long = 0L,
        val presets: List<PresetDto> = emptyList()
    )

    /** 返回写入的预设数量。 */
    fun write(context: Context, uri: Uri, presets: List<AgentPreset>): Int {
        val bundle = PresetBundle(
            exportedAt = System.currentTimeMillis(),
            presets = presets.map { preset ->
                PresetDto(
                    name = preset.name,
                    stage = preset.stage.name,
                    styles = preset.effectiveStyles.map { it.name },
                    customInstruction = preset.customInstruction,
                    injectBasePrompt = preset.injectBasePrompt,
                    icon = preset.icon
                )
            }
        )
        val json = gson.toJson(bundle)
        val stream = context.contentResolver.openOutputStream(uri)
            ?: throw IllegalStateException("无法写入所选文件")
        ZipOutputStream(BufferedOutputStream(stream)).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY_NAME))
            zip.write(json.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(readme(presets.size).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return presets.size
    }

    /** 读取并解析预设；返回的预设 id 为空，由调用方重新分配。 */
    fun read(context: Context, uri: Uri): List<AgentPreset> {
        val name = uri.lastPathSegment.orEmpty().lowercase()
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("无法读取所选文件")
        val texts = if (name.endsWith(".json")) {
            listOf(String(bytes, Charsets.UTF_8))
        } else {
            readJsonEntries(bytes)
        }
        val imported = texts.flatMap { parsePresets(it) }
        return imported.distinctBy { "${it.name}|${it.customInstruction}" }
    }

    private fun readJsonEntries(bytes: ByteArray): List<String> {
        val out = mutableListOf<String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.lowercase().endsWith(".json")) {
                    out += zip.readBytes().toString(Charsets.UTF_8)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (out.isEmpty()) throw IllegalStateException("ZIP 中没有找到预设 JSON 文件")
        return out
    }

    private fun parsePresets(text: String): List<AgentPreset> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val element = runCatching { JsonParser.parseString(trimmed) }.getOrNull() ?: return emptyList()
        val array = when {
            element.isJsonArray -> element.asJsonArray
            element.isJsonObject -> {
                val obj = element.asJsonObject
                when {
                    obj.has("presets") && obj.get("presets").isJsonArray -> obj.getAsJsonArray("presets")
                    else -> return emptyList()
                }
            }
            else -> return emptyList()
        }
        return array.mapNotNull { item ->
            if (!item.isJsonObject) return@mapNotNull null
            val dto = runCatching { gson.fromJson(item, PresetDto::class.java) }.getOrNull()
                ?: return@mapNotNull null
            val presetName = dto.name.trim()
            if (presetName.isEmpty()) return@mapNotNull null
            AgentPreset(
                id = "",
                name = presetName,
                stage = Stage.entries.firstOrNull { it.name == dto.stage } ?: Stage.NONE,
                styles = dto.styles.mapNotNull { key ->
                    AnswerStyle.entries.firstOrNull { it.name == key }
                },
                customInstruction = dto.customInstruction.trim(),
                injectBasePrompt = dto.injectBasePrompt,
                builtIn = false,
                icon = dto.icon.ifBlank { "custom" }
            )
        }
    }

    private fun readme(count: Int): String = """
        Smath Agent 预设导出

        共 $count 个预设，导出文件：$ENTRY_NAME
        导入方式：设置 → Agent 预设 → 导入，选择这个 ZIP 或其中的 JSON 文件。
        导入的预设会作为新的自定义预设追加，不会覆盖已有预设。
    """.trimIndent()
}