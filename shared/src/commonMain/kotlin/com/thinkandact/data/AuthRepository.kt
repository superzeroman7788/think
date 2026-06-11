package com.thinkandact.data

import com.thinkandact.core.config.SupabaseConfig
import com.thinkandact.data.remote.AnonymousSignInResponse
import com.thinkandact.data.remote.SmsLoginRequest
import com.thinkandact.data.remote.SmsSendRequest
import com.thinkandact.data.session.SessionStore
import com.thinkandact.data.session.SupabaseSession
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.datetime.Clock

/**
 * 手机号 + 短信验证码登录（C-10）。
 * 发码/校验走 Edge Function + 阿里云 PNVS；账号仍用合成 email 键 create-or-fetch。
 */
class AuthRepository(
    private val httpClient: HttpClient,
    private val sessionStore: SessionStore,
    private val sessionState: com.thinkandact.data.session.SessionState,
) {
    fun isLoggedIn(): Boolean = sessionStore.load() != null

    fun logout() { sessionStore.clear(); sessionState.onLoggedOut() }

    suspend fun sendSmsCode(phone: String) {
        val response = httpClient.post("${SupabaseConfig.URL}/functions/v1/auth-sms-send") {
            authHeaders()
            contentType(ContentType.Application.Json)
            setBody(SmsSendRequest(phone = phone))
        }
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            com.thinkandact.core.debug.FeDebug.backend("/auth-sms-send", response.status.value, null, body)
            throw IllegalStateException(parseApiErrorMessage(body) ?: "验证码没发出去,过一下再试。")
        }
    }

    suspend fun loginWithPhone(phone: String, code: String): SupabaseSession {
        require(code.isNotBlank()) { "请输入验证码" }
        val response = httpClient.post("${SupabaseConfig.URL}/functions/v1/auth-sms-login") {
            authHeaders()
            contentType(ContentType.Application.Json)
            setBody(SmsLoginRequest(phone = phone, code = code))
        }
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            com.thinkandact.core.debug.FeDebug.backend("/auth-sms-login", response.status.value, null, body)
            throw IllegalStateException(parseApiErrorMessage(body) ?: "登录没成功,过一下再试。")
        }
        return response.body<AnonymousSignInResponse>().toSession()
            .also { sessionStore.save(it); sessionState.onLoggedIn() }
    }

    private fun parseApiErrorMessage(body: String): String? {
        val match = Regex(""""message"\s*:\s*"((?:\\.|[^"\\])*)"""").find(body) ?: return null
        return match.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .takeIf { it.isNotBlank() }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authHeaders() {
        header("apikey", SupabaseConfig.CLIENT_KEY)
        header(HttpHeaders.Authorization, "Bearer ${SupabaseConfig.CLIENT_KEY}")
    }

    private fun AnonymousSignInResponse.toSession(): SupabaseSession {
        val token = accessToken?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(errorDescription ?: error ?: msg ?: "无 access token")
        val nowMs = Clock.System.now().toEpochMilliseconds()
        val expiresAtMillis = expiresAt?.let { it * 1000L } ?: expiresIn?.let { nowMs + it * 1000L }
        return SupabaseSession(accessToken = token, refreshToken = refreshToken, userId = user?.id, expiresAtMillis = expiresAtMillis)
    }
}
