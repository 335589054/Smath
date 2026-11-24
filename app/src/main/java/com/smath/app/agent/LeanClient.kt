package com.smath.app.agent

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * 本地 lean 编译器服务的客户端。
 *
 * 用户在设置里填「服务地址 + API Key」后，App 把 `[$$lean_start$$] … [$$lean_end$$]`
 * 里的代码 POST 到电脑上跑的 `tools/lean_server.py`，由电脑上真正的 lean 编译一遍，
 * 再把 stdout / stderr 整理成一句中文结论交回给模型。
 *
 * 设计原则：
 * 1. 全部走 `Dispatchers.IO`，绝不阻塞主线程；
 * 2. 任何失败都翻译成中文 `summary` 返回，**不抛异常**，避免打断一次对话；
 * 3. `raw` 保留完整输出，方便用户在界面上展开查看原始诊断。
 */
object LeanClient {

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** 编译请求：连接/写入要快，读要留够 lean 冷启动 + 类型检查的时间。 */
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 设置页「测试连接」用：只打 /health，不需要等编译。 */
    private val healthClient = client.newBuilder()
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 用户配置。`usable` 同时要求地址与 Key，二者缺一就用不了编译。 */
    data class LeanConfig(val baseUrl: String, val apiKey: String) {
        val usable: Boolean
            get() = baseUrl.isNotBlank() && apiKey.isNotBlank()

        /** 归一化后的编译端点，例如 `http://192.168.1.10:8788/v1/check`。 */
        val endpoint: String
            get() = normalizeBaseUrl(baseUrl) + "/v1/check"

        /** 归一化后的健康检查端点。 */
        val healthEndpoint: String
            get() = normalizeBaseUrl(baseUrl) + "/health"
    }

    data class LeanResult(
        val ok: Boolean,
        /** 给模型看的中文结论：成功 / 失败 / 不可用都写清楚。 */
        val summary: String,
        /** 原始输出，便于用户展开查看（已截断）。 */
        val raw: String,
        val elapsedMs: Long
    )

    /** 一次编译检查。 */
    suspend fun check(config: LeanConfig, code: String): LeanResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        if (!config.usable) {
            return@withContext LeanResult(
                ok = false,
                summary = "lean 编译工具尚未配置（需要服务地址与 API Key）",
                raw = "",
                elapsedMs = 0L
            )
        }
        val payload = JsonObject().apply {
            addProperty("code", code)
            addProperty("filename", "Main.lean")
        }
        val request = try {
            Request.Builder()
                .url(config.endpoint)
                .header("X-API-Key", config.apiKey.trim())
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonType))
                .build()
        } catch (t: Throwable) {
            return@withContext invalidUrl(config, t, startedAt)
        }
        val response = runCatching {
            client.newCall(request).execute().use { res ->
                HttpPayload(res.code, res.body?.string().orEmpty())
            }
        }
        response.fold(
            onSuccess = { http -> handleCheckResponse(http, startedAt) },
            onFailure = { t -> connectFailure(t, startedAt) }
        )
    }

    /** 设置页「测试连接」：打 /health，确认地址通不通、电脑上有没有 lean。 */
    suspend fun ping(config: LeanConfig): LeanResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        if (config.baseUrl.isBlank()) {
            return@withContext LeanResult(
                ok = false,
                summary = "lean 编译工具尚未配置（需要服务地址与 API Key）",
                raw = "",
                elapsedMs = 0L
            )
        }
        val keyState = if (config.apiKey.isBlank()) "还没有填 API Key" else "API Key 已填写"
        val request = try {
            Request.Builder()
                .url(config.healthEndpoint)
                .header("X-API-Key", config.apiKey.trim())
                .get()
                .build()
        } catch (t: Throwable) {
            return@withContext invalidUrl(config, t, startedAt)
        }
        val response = runCatching {
            healthClient.newCall(request).execute().use { res ->
                HttpPayload(res.code, res.body?.string().orEmpty())
            }
        }
        response.fold(
            onSuccess = { http ->
                val measured = elapsedMsSince(startedAt)
                val body = http.body.take(RAW_LIMIT)
                if (http.code == 401) {
                    LeanResult(false, "lean 服务拒绝了 API Key，请在设置里核对 Key", body, measured)
                } else if (http.code !in 200..299) {
                    LeanResult(false, "lean 服务返回 HTTP ${http.code}", body, measured)
                } else {
                    val json = runCatching { JsonParser.parseString(http.body).asJsonObject }.getOrNull()
                    if (json == null) {
                        LeanResult(false, "lean 服务返回了无法解析的内容", body, measured)
                    } else if (json.booleanOrNull("lean_available") == true) {
                        val version = json.stringOrEmpty("lean_version").ifBlank { "版本未知" }
                        LeanResult(
                            ok = true,
                            summary = "连接成功：lean 可用（$version）；$keyState",
                            raw = body,
                            elapsedMs = measured
                        )
                    } else {
                        LeanResult(
                            ok = false,
                            summary = "能连上 lean 服务，但电脑上没有装 lean（在电脑上运行 `python tools/lean_server.py --install-lean` 用 pip 装一个，或给 --lean 指定路径）",
                            raw = body,
                            elapsedMs = measured
                        )
                    }
                }
            },
            onFailure = { t -> connectFailure(t, startedAt) }
        )
    }

    /**
     * 归一化服务地址：去首尾空白、缺协议补 `http://`、去掉尾部斜杠，
     * 顺手把用户误填的 `/v1/check`、`/health` 折回去。
     */
    fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val withScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            else -> "http://$trimmed"
        }
        var base = withScheme.trimEnd('/')
        if (base.endsWith("/v1/check", ignoreCase = true)) base = base.dropLast("/v1/check".length)
        if (base.endsWith("/health", ignoreCase = true)) base = base.dropLast("/health".length)
        return base.trimEnd('/')
    }

    // ---------------------------------------------------------------------
    // 内部实现
    // ---------------------------------------------------------------------

    private const val RAW_LIMIT = 8000
    private const val STDERR_TAIL = 1500
    private const val STDOUT_TAIL = 2000

    private data class HttpPayload(val code: Int, val body: String)

    private fun handleCheckResponse(http: HttpPayload, startedAt: Long): LeanResult {
        val measured = elapsedMsSince(startedAt)
        val rawBody = http.body.take(RAW_LIMIT)
        if (http.code == 401) {
            return LeanResult(false, "lean 服务拒绝了 API Key，请在设置里核对 Key", rawBody, measured)
        }
        if (http.code !in 200..299) {
            return LeanResult(false, "lean 服务返回 HTTP ${http.code}", rawBody, measured)
        }
        val json = runCatching { JsonParser.parseString(http.body).asJsonObject }.getOrNull()
            ?: return LeanResult(false, "lean 服务返回了无法解析的内容", rawBody, measured)

        val ok = json.booleanOrNull("ok") == true
        val leanAvailable = json.booleanOrNull("lean_available") != false
        val timedOut = json.booleanOrNull("timed_out") == true
        val exitCode = json.longOrNull("exit_code") ?: -1L
        val stdout = json.stringOrEmpty("stdout")
        val stderr = json.stringOrEmpty("stderr")
        val elapsed = json.longOrNull("elapsed_ms") ?: measured

        val raw = buildString {
            if (stdout.isNotBlank()) append(stdout.trim())
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(stderr.trim())
            }
        }.take(RAW_LIMIT)

        if (!leanAvailable) {
            return LeanResult(
                ok = false,
                summary = "电脑上没有找到 lean 可执行文件（lean_server.py 的 --lean 路径不对或未安装）",
                raw = raw.ifBlank { rawBody },
                elapsedMs = elapsed
            )
        }
        if (ok) {
            val summary = buildString {
                append("编译通过，用时 $elapsed ms")
                if (stdout.isNotBlank()) append('\n').append(tail(stdout, STDOUT_TAIL))
            }
            return LeanResult(true, summary, raw.ifBlank { rawBody }, elapsed)
        }
        val detail = tail(if (stderr.isNotBlank()) stderr else stdout, STDERR_TAIL)
        val head = if (timedOut) "编译超时（用时 $elapsed ms）" else "编译失败（exit=$exitCode）"
        val summary = if (detail.isBlank()) head else "$head\n$detail"
        return LeanResult(false, summary, raw.ifBlank { rawBody }, elapsed)
    }

    private fun connectFailure(t: Throwable, startedAt: Long): LeanResult = LeanResult(
        ok = false,
        summary = "无法连接 lean 服务：${friendlyReason(t)}。请确认电脑上的 lean_server.py 正在运行、手机和电脑在同一局域网",
        raw = t.message.orEmpty(),
        elapsedMs = elapsedMsSince(startedAt)
    )

    private fun invalidUrl(config: LeanConfig, t: Throwable, startedAt: Long): LeanResult = LeanResult(
        ok = false,
        summary = "lean 服务地址不合法（当前填的是「${config.baseUrl}」），请填成 http://电脑IP:8788 这种形式",
        raw = t.message.orEmpty(),
        elapsedMs = elapsedMsSince(startedAt)
    )

    private fun friendlyReason(t: Throwable): String = when (t) {
        is SocketTimeoutException -> "连接超时"
        is UnknownHostException -> "找不到主机"
        is ConnectException -> "连接被拒绝"
        is IOException -> t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
        else -> t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
    }

    private fun elapsedMsSince(startedAt: Long): Long =
        (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)

    /** 取文本末尾，前面加省略号；用于把 stderr 的报错位置留在提示里。 */
    private fun tail(text: String, limit: Int): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.length <= limit) return trimmed
        return "…" + trimmed.takeLast(limit)
    }

    private fun JsonObject.booleanOrNull(key: String): Boolean? {
        val element = get(key) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) return null
        return runCatching { element.asBoolean }.getOrNull()
    }

    private fun JsonObject.stringOrEmpty(key: String): String {
        val element = get(key) ?: return ""
        if (!element.isJsonPrimitive) return ""
        return runCatching { element.asString }.getOrDefault("")
    }

    private fun JsonObject.longOrNull(key: String): Long? {
        val element = get(key) ?: return null
        if (!element.isJsonPrimitive) return null
        return runCatching { element.asLong }.getOrNull()
    }
}