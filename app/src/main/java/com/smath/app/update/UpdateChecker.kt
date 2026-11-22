package com.smath.app.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.math.min

/**
 * 「检查更新 → 下载 → 安装」的纯逻辑实现。
 *
 * 设计要点：
 * - 只依赖 OkHttp / Gson / coroutines，不引入任何新依赖，也不依赖项目里的其它文件；
 * - 网络失败一律转成中文文案返回，不向调用方抛异常（协程取消除外）；
 * - 首次解析走 GitHub Release API，失败自动回退官方源，再失败退化为解析 releases/latest 的跳转页。
 */
object UpdateChecker {

    /** 源标识：官方源。 */
    const val SOURCE_OFFICIAL: String = "official"

    /** 源标识：gh-proxy.com 镜像。 */
    const val SOURCE_GH_PROXY_COM: String = "gh-proxy.com"

    /** 源标识：gh-proxy.org 镜像。 */
    const val SOURCE_GH_PROXY_ORG: String = "gh-proxy.org"

    /** 下载/检查时按顺序尝试的源。 */
    private val SOURCE_ORDER = listOf(SOURCE_OFFICIAL, SOURCE_GH_PROXY_COM, SOURCE_GH_PROXY_ORG)

    private const val REPO_URL = "https://github.com/335589054/Smath"
    private const val API_LATEST = "https://api.github.com/repos/335589054/Smath/releases/latest"
    private val RELEASES_LATEST = "$REPO_URL/releases/latest"
    private const val RELEASE_TAG_PREFIX = "$REPO_URL/releases/tag/"
    private const val RELEASE_DOWNLOAD_PREFIX = "$REPO_URL/releases/download/"

    private const val USER_AGENT = "Smath-Android"

    /** 检查更新的整体时间预算，避免镜像挂掉时让用户干等。 */
    private const val CHECK_TOTAL_BUDGET_MS = 12_000L

    /** 剩余预算低于该值就不再尝试下一个候选地址。 */
    private const val MIN_ATTEMPT_MS = 1_200L

    /** 下载单次请求的上限（大包 + 慢速网络）。 */
    private const val DOWNLOAD_CALL_TIMEOUT_MS = 300_000L

    private const val NOTES_LIMIT = 4_000
    private const val READ_BUFFER = 64 * 1024

    private val TAG_REGEX = Regex("tag/(v?[0-9][0-9.]*)")
    private val APK_LINK_REGEX = Regex("(/335589054/Smath/releases/download/[^\"'\\s]+\\.apk)")

    // ------------------------------------------------------------------ 数据结构

    data class ReleaseInfo(
        val tag: String,
        val versionName: String,
        val apkUrl: String,
        val apkSize: Long,
        val pageUrl: String,
        val notes: String
    )

    data class CheckResult(val info: ReleaseInfo?, val error: String?)

    private class CheckException(message: String) : Exception(message)

    private class DownloadException(message: String) : Exception(message)

    // ------------------------------------------------------------------ 基础工具

    fun sourceLabel(source: String): String = when (source.trim().lowercase()) {
        SOURCE_OFFICIAL -> "官方源"
        SOURCE_GH_PROXY_COM -> "gh-proxy.com 镜像"
        SOURCE_GH_PROXY_ORG -> "gh-proxy.org 镜像"
        else -> "未知源"
    }

    /** 按源拼接真实请求地址：official 原样返回，镜像则加前缀。 */
    fun proxied(source: String, url: String): String {
        val raw = url.trim()
        if (raw.isEmpty()) return raw
        // 已经是镜像地址就不再二次加前缀
        if (raw.startsWith("https://gh-proxy.com/") || raw.startsWith("https://gh-proxy.org/")) return raw
        return when (source.trim().lowercase()) {
            SOURCE_GH_PROXY_COM -> "https://gh-proxy.com/$raw"
            SOURCE_GH_PROXY_ORG -> "https://gh-proxy.org/$raw"
            else -> raw
        }
    }

    @Suppress("DEPRECATION")
    fun currentVersionName(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName?.takeIf { it.isNotBlank() } ?: "0"
    }.getOrDefault("0")

    /** 语义化比较：remote 比 local 新返回 true。忽略 v 前缀与 -beta / +build 后缀。 */
    fun isNewer(remote: String, local: String): Boolean {
        val remoteParts = splitVersion(remote) ?: return false
        val localParts = splitVersion(local) ?: return false
        val size = maxOf(remoteParts.size, localParts.size)
        for (index in 0 until size) {
            val a = remoteParts.getOrElse(index) { 0 }
            val b = localParts.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun splitVersion(raw: String): List<Int>? {
        var text = raw.trim()
        if (text.startsWith("v") || text.startsWith("V")) text = text.substring(1)
        text = text.substringBefore('-').substringBefore('+').substringBefore(' ').trim()
        if (text.isEmpty()) return null
        val parts = text.split('.').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val numbers = ArrayList<Int>(parts.size)
        for (part in parts) {
            val digits = part.takeWhile { it.isDigit() }
            val value = digits.toIntOrNull() ?: return null
            numbers += value
        }
        return numbers
    }

    private fun normalizeVersion(tag: String): String {
        var text = tag.trim()
        if (text.startsWith("v") || text.startsWith("V")) text = text.substring(1)
        return text.substringBefore('-').substringBefore('+').trim()
    }

    // ------------------------------------------------------------------ 检查更新

    /**
     * 查询最新 release。网络/解析失败不抛异常，返回中文 error。
     * 内部切到 IO 线程，调用方可以直接在主线程调用。
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun check(context: Context, source: String): CheckResult = withContext(Dispatchers.IO) {
        try {
            checkBlocking(source)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            CheckResult(null, friendlyError(error))
        }
    }

    private fun checkBlocking(source: String): CheckResult {
        val deadline = System.currentTimeMillis() + CHECK_TOTAL_BUDGET_MS
        fun budget(): Long = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)

        var lastError = "网络连接失败，请检查网络或稍后重试"

        // 1) Release API（按当前源）+ 2) 官方 API 兜底
        val apiTargets = listOf(proxied(source, API_LATEST), API_LATEST).distinct()
        for (target in apiTargets) {
            val remaining = budget()
            if (remaining < MIN_ATTEMPT_MS) break
            try {
                return CheckResult(fetchFromApi(target, remaining), null)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                lastError = friendlyError(error)
            }
        }

        // 3) 退化为解析 releases/latest 跳转页
        val pageTargets = listOf(proxied(source, RELEASES_LATEST), RELEASES_LATEST).distinct()
        for (target in pageTargets) {
            val remaining = budget()
            if (remaining < MIN_ATTEMPT_MS) break
            try {
                return CheckResult(fetchFromReleasePage(target, remaining), null)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                lastError = friendlyError(error)
            }
        }

        return CheckResult(null, lastError)
    }

    private fun fetchFromApi(url: String, budgetMs: Long): ReleaseInfo {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        checkClient(budgetMs).newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw httpError(response.code)
            val body = response.body?.string().orEmpty()
            return parseReleaseJson(body)
        }
    }

    private fun parseReleaseJson(json: String): ReleaseInfo {
        val root = try {
            JsonParser.parseString(json).asJsonObject
        } catch (error: Throwable) {
            throw CheckException("镜像返回的内容不是有效的发布信息")
        }
        val tag = stringField(root, "tag_name")
        if (tag.isBlank()) throw CheckException("镜像返回的内容不是有效的发布信息")
        val versionName = normalizeVersion(tag)
        if (versionName.isEmpty()) throw CheckException("发布版本号无法解析：$tag")

        val pageUrl = stringField(root, "html_url").ifBlank { RELEASE_TAG_PREFIX + tag }
        val notes = stringField(root, "body").take(NOTES_LIMIT)

        var apkUrl = ""
        var apkSize = 0L
        val assets = runCatching { root.getAsJsonArray("assets") }.getOrNull()
        if (assets != null) {
            for (element in assets) {
                val asset = element as? JsonObject ?: continue
                val name = stringField(asset, "name")
                if (!name.lowercase().endsWith(".apk")) continue
                val downloadable = stringField(asset, "browser_download_url")
                if (downloadable.isBlank()) continue
                val size = longField(asset, "size")
                if (apkUrl.isEmpty() || size >= apkSize) {
                    apkUrl = downloadable
                    apkSize = size
                }
            }
        }
        if (apkUrl.isEmpty()) throw CheckException("最新发布中没有找到 APK 安装包")

        return ReleaseInfo(
            tag = tag,
            versionName = versionName,
            apkUrl = apkUrl,
            apkSize = apkSize,
            pageUrl = pageUrl,
            notes = notes
        )
    }

    /** 解析 `releases/latest` 的 302 目标页；拿不到 API 时的最后兜底。 */
    private fun fetchFromReleasePage(url: String, budgetMs: Long): ReleaseInfo {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/html")
            .header("User-Agent", USER_AGENT)
            .build()
        checkClient(budgetMs).newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 404) throw CheckException("未找到发布信息（404），仓库可能还没有正式 Release")
                throw httpError(response.code)
            }
            val finalUrl = response.request.url.toString()
            val html = runCatching { response.body?.string().orEmpty() }.getOrDefault("")

            val tag = TAG_REGEX.find(finalUrl)?.groupValues?.getOrNull(1)
                ?: TAG_REGEX.find(html)?.groupValues?.getOrNull(1)
            if (tag.isNullOrBlank()) throw CheckException("镜像返回的内容不是有效的发布信息")
            val versionName = normalizeVersion(tag)
            if (versionName.isEmpty()) throw CheckException("发布版本号无法解析：$tag")

            val assetLink = APK_LINK_REGEX.find(html)?.groupValues?.getOrNull(1)
            val apkUrl = when {
                assetLink.isNullOrBlank() -> RELEASE_DOWNLOAD_PREFIX + tag + "/Smath-" + versionName + "-release.apk"
                assetLink.startsWith("http") -> assetLink
                else -> "https://github.com" + assetLink
            }

            return ReleaseInfo(
                tag = tag,
                versionName = versionName,
                apkUrl = apkUrl,
                apkSize = 0L,
                pageUrl = RELEASE_TAG_PREFIX + tag,
                notes = ""
            )
        }
    }

    private fun httpError(code: Int): CheckException = when {
        code == 403 || code == 429 -> CheckException("GitHub 限流（$code），请改用镜像源")
        code == 404 -> CheckException("未找到发布信息（404），仓库可能还没有正式 Release")
        code in 500..599 -> CheckException("GitHub 服务异常（$code），请稍后重试")
        else -> CheckException("请求失败（HTTP $code）")
    }

    private fun friendlyError(error: Throwable): String = when (error) {
        is CheckException -> error.message ?: "检查更新失败，请稍后重试"
        is SocketTimeoutException -> "网络请求超时，请检查网络后重试"
        is UnknownHostException -> "网络连接失败，请检查网络或稍后重试"
        is SSLException -> "网络连接不安全（SSL 错误），请稍后重试"
        is IOException -> "网络连接失败，请检查网络或稍后重试"
        else -> error.message?.takeIf { it.isNotBlank() }?.let { "检查更新失败：$it" } ?: "检查更新失败，请稍后重试"
    }

    private fun stringField(obj: JsonObject, key: String): String {
        val element = obj.get(key) ?: return ""
        if (element.isJsonNull || !element.isJsonPrimitive) return ""
        return runCatching { element.asString }.getOrDefault("")
    }

    private fun longField(obj: JsonObject, key: String): Long {
        val element = obj.get(key) ?: return 0L
        if (!element.isJsonPrimitive) return 0L
        return runCatching { element.asLong }.getOrDefault(0L)
    }

    // ------------------------------------------------------------------ 下载

    /**
     * 下载 APK 到 `cacheDir/updates/`，返回文件；失败返回 null（文案由调用方给通用提示）。
     * onProgress 保证在主线程回调，0..100。
     */
    suspend fun download(
        context: Context,
        info: ReleaseInfo,
        source: String,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates")
        if (!directory.exists() && !directory.mkdirs()) return@withContext null

        val target = File(directory, apkFileName(info.versionName))
        if (info.apkSize > 0L && target.isFile && target.length() == info.apkSize) {
            emitProgress(onProgress, 100)
            return@withContext target
        }

        val remoteUrl = info.apkUrl.trim()
        if (remoteUrl.isEmpty()) return@withContext null

        val candidates = (listOf(source) + SOURCE_ORDER)
            .map { it.trim().lowercase() }
            .distinct()

        for (candidate in candidates) {
            try {
                return@withContext downloadOnce(directory, target, remoteUrl, candidate, onProgress)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // 该源失败：清理残留、换下一个源重试
            }
        }
        null
    }

    private suspend fun downloadOnce(
        directory: File,
        target: File,
        remoteUrl: String,
        source: String,
        onProgress: (Int) -> Unit
    ): File {
        val part = File(directory, target.name + ".part")
        if (part.exists() && !part.delete()) throw DownloadException("无法清理临时文件")

        var finished = false
        try {
            val request = Request.Builder()
                .url(proxied(source, remoteUrl))
                .header("Accept", "application/octet-stream")
                // 关掉透明 gzip：否则 contentLength 是压缩后的长度，进度与完整性校验都会错
                .header("Accept-Encoding", "identity")
                .header("User-Agent", USER_AGENT)
                .build()

            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw DownloadException("下载失败（HTTP ${response.code}）")
                val body = response.body ?: throw DownloadException("下载响应为空")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(READ_BUFFER)
                        var downloaded = 0L
                        var lastPercent = -1
                        while (true) {
                            ensureActiveOrThrow()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val percent = if (total > 0L) {
                                ((downloaded * 100L) / total).toInt().coerceIn(0, 99)
                            } else {
                                0
                            }
                            if (percent != lastPercent) {
                                lastPercent = percent
                                emitProgress(onProgress, percent)
                            }
                        }
                        output.flush()
                    }
                }
                if (total > 0L && part.length() != total) {
                    throw DownloadException("下载不完整（${part.length()}/$total 字节）")
                }
            }

            if (part.length() <= 0L) throw DownloadException("下载内容为空")
            // 镜像有时会用 200 返回一张 HTML 错误页，用 zip 魔数把这种情况拦下来，好让外层换源重试
            if (!looksLikeApk(part)) throw DownloadException("下载内容不是有效的 APK")

            if (target.exists() && !target.delete()) throw DownloadException("无法覆盖旧的安装包")
            if (!part.renameTo(target)) throw DownloadException("无法保存安装包")

            finished = true
            emitProgress(onProgress, 100)
            return target
        } finally {
            if (!finished && part.exists()) part.delete()
        }
    }

    private fun looksLikeApk(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(2)
            input.read(head) == 2 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()
        }
    }.getOrDefault(false)

    private fun apkFileName(versionName: String): String {
        val safe = buildString {
            for (char in versionName.trim()) {
                append(if (char.isLetterOrDigit() || char == '.' || char == '-' || char == '_') char else '_')
            }
        }
        val version = safe.ifBlank { "latest" }
        return "Smath-$version-release.apk"
    }

    /** 下载循环里的取消点：协程被取消时中断，并把 .part 交给 finally 清理。 */
    private suspend fun ensureActiveOrThrow() {
        val job = currentCoroutineContext().job
        if (!job.isActive) throw CancellationException("下载已取消")
    }

    /** 进度统一回主线程，调用方可以放心地在这里写 Compose 状态。 */
    private suspend fun emitProgress(onProgress: (Int) -> Unit, percent: Int) {
        withContext(Dispatchers.Main) {
            runCatching { onProgress(percent.coerceIn(0, 100)) }
        }
    }

    // ------------------------------------------------------------------ 安装

    /**
     * 拉起系统安装器。返回 null 表示已成功 startActivity；
     * 否则返回中文错误（例如需要先开启「安装未知应用」）。
     */
    fun install(context: Context, file: File): String? {
        if (!file.isFile || file.length() <= 0L) {
            return "安装包不存在或已损坏，请重新下载"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canInstall = runCatching { context.packageManager.canRequestPackageInstalls() }
                .getOrDefault(false)
            if (!canInstall) return "请先允许 Smath 安装未知来源应用"
        }
        val uri = apkUri(context, file) ?: return "无法生成安装包路径，请重新下载"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            null
        } catch (error: ActivityNotFoundException) {
            "未找到系统安装器，请到文件管理器手动安装"
        } catch (error: IllegalArgumentException) {
            "安装包路径无效，请重新下载"
        } catch (error: Throwable) {
            "无法拉起安装器：${error.message ?: error.javaClass.simpleName}"
        }
    }

    /**
     * 取 content:// URI。file_paths.xml 已声明 `cache-path path="."`，
     * 所以 cacheDir 下的文件都能直接用；万一路径在覆盖范围外，就先复制到 cacheDir 根目录。
     */
    private fun apkUri(context: Context, file: File): Uri? = runCatching {
        val authority = "${context.packageName}.fileprovider"
        val cacheRoot = context.cacheDir.absolutePath.trimEnd(File.separatorChar) + File.separator
        if (file.absolutePath.startsWith(cacheRoot)) {
            FileProvider.getUriForFile(context, authority, file)
        } else {
            val copied = File(context.cacheDir, file.name)
            if (!copied.isFile || copied.length() != file.length()) {
                file.inputStream().use { input ->
                    copied.outputStream().use { output -> input.copyTo(output) }
                }
            }
            FileProvider.getUriForFile(context, authority, copied)
        }
    }.getOrNull()

    /** 跳到本应用的「安装未知应用」授权页；返回 false 表示无法跳转。 */
    fun openInstallPermissionSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val packageUri = Uri.parse("package:${context.packageName}")
        val direct = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(direct) }.isSuccess) return true

        val generic = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(generic) }.isSuccess) return true

        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(details) }.isSuccess
    }

    // ------------------------------------------------------------------ HTTP

    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** 按剩余预算生成客户端，保证一次 check 的总耗时可控。 */
    private fun checkClient(budgetMs: Long): OkHttpClient {
        val bounded = budgetMs.coerceAtLeast(2_000L)
        return baseClient.newBuilder()
            .callTimeout(bounded, TimeUnit.MILLISECONDS)
            .connectTimeout(min(bounded, 6_000L), TimeUnit.MILLISECONDS)
            .readTimeout(min(bounded, 6_000L), TimeUnit.MILLISECONDS)
            .build()
    }

    private val downloadClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(DOWNLOAD_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }
}