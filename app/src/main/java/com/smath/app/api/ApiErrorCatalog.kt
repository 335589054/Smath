package com.smath.app.api

import com.smath.app.data.ApiErrorInfo
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

object ApiErrorCatalog {
    fun fromHttp(code: Int, body: String): ApiErrorInfo {
        val rawMessage = extractMessage(body).ifBlank { body }
        val (label, summary, detail, retryable) = when (code) {
            400 -> ErrorCopy(
                "请求无法被理解",
                "请求格式或参数有误，常见原因是当前模型不支持图片输入，或消息内容过长。",
                "请检查所选模型是否支持图片；尝试减少图片数量或压缩图片；若刚修改过接口地址，请确认地址以 /v1 结尾。",
                false
            )
            401 -> ErrorCopy(
                "API Key 无效",
                "服务端没有通过身份验证。",
                "请在「模型和 API Key」中检查该服务商的 Key 是否填写正确、是否已过期，以及是否属于当前接口地址对应的平台。",
                false
            )
            402 -> ErrorCopy(
                "余额不足",
                "账户可用额度不足，无法完成本次调用。",
                "请到服务商控制台充值或检查套餐额度。联网搜索按成功请求计费，也可能单独消耗额度。",
                false
            )
            403 -> ErrorCopy(
                "没有访问权限",
                "Key 有效，但没有权限使用该模型或该接口，或当前地区被限制。",
                "确认账号已开通所选模型；部分模型需要单独申请权限。也可以换一个模型或服务商再试。",
                false
            )
            404 -> ErrorCopy(
                "接口或模型不存在",
                "请求的地址或模型名称找不到。",
                "检查接口地址是否正确（通常应以 /v1 结尾），并确认模型名称与服务商文档一致。",
                false
            )
            408 -> ErrorCopy(
                "请求超时",
                "服务端等待请求完成时超时。",
                "稍后重试；如果经常发生，请缩短问题或减少图片。",
                true
            )
            413 -> ErrorCopy(
                "请求内容过大",
                "图片或文本超过了服务端允许的大小。",
                "删除部分图片，或重新裁剪图片后再发送。单张图片建议压缩到 1600 像素以内。",
                false
            )
            422 -> ErrorCopy(
                "参数校验失败",
                "请求中有服务端不接受的字段或取值。",
                "通常是模型不支持当前参数。请换用其他模型；如果问题持续，请把下面的原始错误一起反馈。",
                false
            )
            429 -> ErrorCopy(
                "请求过于频繁或额度用尽",
                "触发了频率限制，或当前账号的调用额度已用完。",
                "等待几十秒后重试；降低连续发送的频率；检查服务商后台的额度与限流设置。",
                true
            )
            500 -> ErrorCopy(
                "服务端内部错误",
                "模型服务在处理请求时发生了异常。",
                "这通常不是你的问题，稍后重试即可；若持续出现，可以切换模型或服务商。",
                true
            )
            502 -> ErrorCopy(
                "网关错误",
                "上游服务返回了无效响应，常见于中转平台。",
                "稍后重试，或检查中转平台的模型映射与状态页。",
                true
            )
            503 -> ErrorCopy(
                "服务暂时不可用",
                "模型服务繁忙或正在维护。",
                "稍等片刻后重试；高峰期可以换用其他模型。",
                true
            )
            504 -> ErrorCopy(
                "网关超时",
                "中转网关等待上游模型响应超时。",
                "稍后重试；如果正在生成很长的回答，可以要求它简短一些。",
                true
            )
            else -> ErrorCopy(
                "调用失败（HTTP $code）",
                "服务端返回了未预期的状态码。",
                "请检查接口地址、模型名称和 Key；如果错误持续，请参考下方原始信息。",
                code >= 500
            )
        }
        return ApiErrorInfo(
            code = code,
            label = label,
            summary = summary,
            detail = detail,
            raw = rawMessage.take(1200),
            retryable = retryable
        )
    }

    fun fromThrowable(t: Throwable): ApiErrorInfo {
        val (label, summary, detail) = when (t) {
            is UnknownHostException -> Triple(
                "无法连接服务器",
                "域名解析失败，设备可能没有网络，或接口地址填写有误。",
                "检查网络连接和接口地址；如果是自定义服务商，确认域名可以正常访问。"
            )
            is SocketTimeoutException -> Triple(
                "连接超时",
                "在限定时间内没有收到服务端响应。",
                "检查网络后重试；生成较长回答时也可能因等待过久超时。"
            )
            is SSLException -> Triple(
                "安全连接失败",
                "TLS 握手失败，可能是网络劫持、代理证书或系统时间不正确。",
                "关闭异常的代理或 VPN，检查系统时间；公司网络环境下请确认证书设置。"
            )
            is IOException -> Triple(
                "网络连接中断",
                "连接在响应完成前被关闭。",
                "检查网络稳定性后重试；如果使用了代理，请确认代理允许流式连接。"
            )
            else -> Triple(
                "发生未知错误",
                t.message ?: t.javaClass.simpleName,
                "可以重试；如果问题持续，请检查接口地址、模型和 Key。"
            )
        }
        return ApiErrorInfo(
            code = 0,
            label = label,
            summary = summary,
            detail = detail,
            raw = (t.message ?: t.javaClass.name).take(1200),
            retryable = true
        )
    }

    fun fromStreamMessage(message: String, code: Int?): ApiErrorInfo {
        val normalized = message.lowercase()
        val inferred = when {
            normalized.contains("context") || normalized.contains("too long") -> 400
            normalized.contains("api key") || normalized.contains("unauthorized") -> 401
            normalized.contains("quota") || normalized.contains("balance") -> 402
            normalized.contains("rate limit") -> 429
            else -> code ?: 500
        }
        return fromHttp(inferred, message)
    }

    private fun extractMessage(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val root = com.google.gson.JsonParser.parseString(body)
            if (root.isJsonObject) {
                val obj = root.asJsonObject
                val error = obj.get("error")
                if (error != null && error.isJsonObject) {
                    val e = error.asJsonObject
                    val message = e.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                    val type = e.get("type")?.takeIf { it.isJsonPrimitive }?.asString
                    val code = e.get("code")?.takeIf { it.isJsonPrimitive }?.asString
                    return listOfNotNull(message, type, code?.let { "($it)" }).joinToString(" ")
                }
                obj.get("message")?.takeIf { it.isJsonPrimitive }?.asString?.let { return it }
                obj.get("msg")?.takeIf { it.isJsonPrimitive }?.asString?.let { return it }
            }
            body
        } catch (_: Throwable) {
            body
        }
    }

    private data class ErrorCopy(
        val label: String,
        val summary: String,
        val detail: String,
        val retryable: Boolean
    )
}