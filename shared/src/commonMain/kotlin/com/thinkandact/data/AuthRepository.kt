package com.thinkandact.data

import com.thinkandact.core.config.SupabaseConfig
import com.thinkandact.data.remote.AnonymousSignInResponse
import com.thinkandact.data.remote.EmailAuthRequest
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
 * 登录（发朋友版 = **MOCK**，不接真短信）。
 *
 * 账号按**手机号** create-or-fetch：用「合成 email + 确定性密码」当账号键，在 Supabase
 * 建/取该用户（跳过 OTP）。不同手机号 = 不同 user_id = 数据分开。
 *
 * 换真短信时：只把 [loginWithPhone] 里 `//MOCK` 那段「任意码通过」换成「校验真实 OTP」，
 * 其余（建/取账号、存会话）不动。
 */
class AuthRepository(
    private val httpClient: HttpClient,
    private val sessionStore: SessionStore,
    private val sessionState: com.thinkandact.data.session.SessionState,
) {
    fun isLoggedIn(): Boolean = sessionStore.load() != null

    fun logout() { sessionStore.clear(); sessionState.onLoggedOut() }

    suspend fun loginWithPhone(phone: String, code: String): SupabaseSession {
        // ───────────── //MOCK ─────────────
        // 测试期：任意验证码即通过（不发真短信、不校验 OTP）。
        // 换真短信：此处改为向 SMS 服务商校验 `code`，校验失败抛错；下面的建/取账号不变。
        require(code.isNotBlank()) { "请输入验证码" }
        // ───────────── //MOCK end ─────────
        val email = "$phone@mock.thinkandact.app"
        val password = "mock_${phone}_tna" // 确定性 → 同手机号永远取到同一账号
        // 1) 已有账号：密码登录
        runCatching { signIn(email, password) }.getOrNull()?.let { return it }
        // 2) 没有：注册（项目已关邮箱确认 → 直接返回 session）
        return signUp(email, password)
    }

    private suspend fun signIn(email: String, password: String): SupabaseSession {
        val r = httpClient.post("${SupabaseConfig.URL}/auth/v1/token?grant_type=password") {
            authHeaders(); contentType(ContentType.Application.Json)
            setBody(EmailAuthRequest(email, password))
        }
        if (!r.status.isSuccess()) throw IllegalStateException("signin ${r.status.value}")
        return r.body<AnonymousSignInResponse>().toSession().also { sessionStore.save(it); sessionState.onLoggedIn() }
    }

    private suspend fun signUp(email: String, password: String): SupabaseSession {
        val r = httpClient.post("${SupabaseConfig.URL}/auth/v1/signup") {
            authHeaders(); contentType(ContentType.Application.Json)
            setBody(EmailAuthRequest(email, password))
        }
        if (!r.status.isSuccess()) {
            val body = r.bodyAsText()
            com.thinkandact.core.debug.FeDebug.backend("/auth signup", r.status.value, null, body)
            throw IllegalStateException(body.ifBlank { "注册失败" })
        }
        val resp = r.body<AnonymousSignInResponse>()
        // 若未直接给 token（万一开了邮箱确认）→ 再尝试登录；仍无则报错（卡点：需 Cursor 关确认）。
        if (resp.accessToken.isNullOrBlank()) {
            return runCatching { signIn(email, password) }.getOrElse {
                throw IllegalStateException("建号未返回会话（可能开了邮箱确认）——需后端关闭 email 确认。")
            }
        }
        return resp.toSession().also { sessionStore.save(it); sessionState.onLoggedIn() }
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
