package com.thinkandact.voice

import com.thinkandact.core.config.SupabaseConfig
import com.thinkandact.data.PlanRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * BE-ASR-1 会话端点（已上线）的客户端实现。
 *
 *  请求：POST {SUPABASE_URL}/functions/v1/asr-session
 *        Headers: apikey, Authorization: Bearer <用户 access_token>
 *        Body:    { "sample_rate": 16000, "format": "pcm" }
 *  响应：200 { ws_url, sample_rate, expires_at }
 *
 *  约束（任务书 §4）：响应只含一条**预签名**的开箱即连 WS URL（~5min 过期），
 *  客户端不持有任何 key（永久或临时皆无），也不做任何签名——签名全在服务端完成。
 */
class SupabaseAsrSessionProvider(
    private val httpClient: HttpClient,
    private val planRepository: PlanRepository,
) : AsrSessionProvider {

    override suspend fun fetch(intent: String?): AsrSession {
        val session = planRepository.ensureSession()
        val response = httpClient.post("${SupabaseConfig.URL}/functions/v1/asr-session") {
            header("apikey", SupabaseConfig.CLIENT_KEY)
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken}")
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            // 后端 schema 严格：只认 intent=="prefetch"，其它值/`intent:null` 一律 400。
            // 全局 Json 是 encodeDefaults+explicitNulls，会把 null 也写出来，所以 live 必须用**不含该字段**的请求体。
            if (intent == SessionIntent.PREFETCH) {
                setBody(AsrSessionPrefetchRequest(sampleRate = VoiceAudioFormat.SAMPLE_RATE, format = "pcm"))
            } else {
                setBody(AsrSessionRequest(sampleRate = VoiceAudioFormat.SAMPLE_RATE, format = "pcm"))
            }
        }
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            val failure = parseAsrSessionFailure(response.status.value, body)
            throw AsrUnavailableException(
                message = "asr-session HTTP ${failure.httpStatus}: ${failure.code ?: failure.rawBody}",
                failure = failure,
            )
        }
        return runCatching { response.body<AsrSession>() }
            .getOrElse { throw AsrUnavailableException("asr-session parse failed: ${it.message}", cause = it) }
    }
}

/** asr-session 请求体（实连/默认）。注意 sample_rate 必须是**整数**（后端严格校验），且**不带 intent**。 */
@Serializable
private data class AsrSessionRequest(
    @SerialName("sample_rate") val sampleRate: Int,
    val format: String,
)

/** asr-session 预取请求体：固定 intent=="prefetch"（v1.1，不计配额）。 */
@Serializable
private data class AsrSessionPrefetchRequest(
    @SerialName("sample_rate") val sampleRate: Int,
    val format: String,
    val intent: String = SessionIntent.PREFETCH,
)
