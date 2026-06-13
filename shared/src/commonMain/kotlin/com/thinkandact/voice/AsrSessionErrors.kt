package com.thinkandact.voice

/** 解析 asr-session HTTP 错误体，供 UI 展示准确提示。 */
internal fun parseAsrSessionFailure(httpStatus: Int, body: String): AsrSessionFailure {
    val code = when {
        body.contains("ASR_QUOTA_EXCEEDED") -> "ASR_QUOTA_EXCEEDED"
        body.contains("ASR_NOT_CONFIGURED") -> "ASR_NOT_CONFIGURED"
        httpStatus == 401 -> "UNAUTHORIZED"
        else -> null
    }
    return AsrSessionFailure(httpStatus = httpStatus, code = code, rawBody = body.take(300))
}

data class AsrSessionFailure(
    val httpStatus: Int,
    val code: String?,
    val rawBody: String,
) {
    fun toUserMessage(): String = when (code) {
        "ASR_QUOTA_EXCEEDED" -> "今天的语音次数用完了,先用打字描述今天吧。"
        "ASR_NOT_CONFIGURED" -> "语音服务还没准备好,请先用文字输入。"
        "UNAUTHORIZED" -> "登录状态有点卡住了,再试一次会重新准备。"
        else -> if (httpStatus == 429) {
            "今天的语音次数用完了,先用打字描述今天吧。"
        } else {
            "语音暂时没接上,先用打字也行。"
        }
    }
}
