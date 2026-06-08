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
 * EXP-BE-1：实际连上 ASR 时登记日配额（预取 session 不计数）。
 */
class SupabaseAsrUsageReporter(
    private val httpClient: HttpClient,
    private val planRepository: PlanRepository,
) {
    suspend fun reportUsage(sessionId: String): AsrUsageResult {
        val session = planRepository.ensureSession()
        val response = httpClient.post("${SupabaseConfig.URL}/functions/v1/asr-usage") {
            header("apikey", SupabaseConfig.CLIENT_KEY)
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken}")
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(AsrUsageRequest(sessionId = sessionId))
        }
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            val failure = parseAsrSessionFailure(response.status.value, body)
            throw AsrUnavailableException(
                message = "asr-usage HTTP ${failure.httpStatus}: ${failure.code ?: failure.rawBody}",
                failure = failure,
            )
        }
        return response.body<AsrUsageResult>()
    }
}

@Serializable
private data class AsrUsageRequest(
    @SerialName("session_id") val sessionId: String,
)

@Serializable
data class AsrUsageResult(
    val allowed: Boolean = true,
    @SerialName("already_counted") val alreadyCounted: Boolean = false,
    @SerialName("daily_asr_sessions") val dailyAsrSessions: Int = 0,
    val limit: Int = 20,
)
