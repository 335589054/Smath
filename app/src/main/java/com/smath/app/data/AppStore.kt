package com.smath.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class AppStore(private val context: Context) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val file = File(context.filesDir, "smath_data.json")
    private val tempFile = File(context.filesDir, "smath_data.tmp")
    private val lock = Any()

    fun load(): AppData {
        synchronized(lock) {
            if (!file.exists()) return AppData()
            return try {
                val text = file.readText(Charsets.UTF_8)
                val data = gson.fromJson(text, AppData::class.java) ?: AppData()
                sanitize(data)
            } catch (t: Throwable) {
                Log.e("SmathStore", "Failed to load data", t)
                try {
                    file.copyTo(File(context.filesDir, "smath_data.corrupt.json"), overwrite = true)
                } catch (_: Throwable) {
                }
                AppData()
            }
        }
    }

    fun save(data: AppData) {
        synchronized(lock) {
            try {
                tempFile.writeText(gson.toJson(data), Charsets.UTF_8)
                try {
                    Files.move(
                        tempFile.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (_: Throwable) {
                    Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (t: Throwable) {
                Log.e("SmathStore", "Failed to save data", t)
            }
        }
    }

    private fun sanitize(data: AppData): AppData {
        var settings = data.settings
        if (settings.providers.isEmpty()) {
            settings = settings.copy(providers = defaultProviders())
        }
        // 旧版本内置过 APIGoto 预设；现在只保留 DeepSeek 官方和 ChatGPT。
        val providers = settings.providers.filterNot { it.id == "apigoto" }
            .ifEmpty { defaultProviders() }
        settings = if (settings.activeProviderId == "apigoto") {
            settings.copy(
                providers = providers,
                activeProviderId = "deepseek",
                activeModel = "deepseek-flash"
            )
        } else {
            settings.copy(providers = providers)
        }
        if (settings.presets.isEmpty()) {
            settings = settings.copy(presets = DEFAULT_PRESETS)
        }
        if (settings.providers.none { it.id == settings.activeProviderId }) {
            val p = settings.providers.first()
            settings = settings.copy(activeProviderId = p.id, activeModel = p.displayModel)
        }
        val globalPrompt = settings.globalPrompt?.takeIf { it.isNotBlank() }
            ?: BASE_AGENT_PROMPT
        settings = settings.copy(globalPrompt = globalPrompt)
        return data.copy(settings = settings)
    }
}