package com.thinkandact.data

import com.thinkandact.core.config.SupabaseConfig
import com.thinkandact.data.remote.InboxBadgeResponse
import com.thinkandact.data.remote.InboxCaptureRequest
import com.thinkandact.data.remote.InboxStructuredCaptureDto
import com.thinkandact.data.remote.InboxCaptureResponse
import com.thinkandact.data.remote.InboxItemDto
import com.thinkandact.data.remote.InboxListResponse
import com.thinkandact.data.remote.InboxUpdateRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * 随手记(收件箱)数据层——走已上线的 4 个 Edge Function（契约 BE_随手记_接口契约_给Code.md）。
 *
 * 红线：收件箱 ≠ 任务，只有 add_today 写 tasks(source=inbox)；不自动加入；失败显真实 reason。
 * 绝不丢笔记：capture 失败/超时由后端兜底(extract_failed + 原文落库)；离线本地暂存留后续。
 */
class InboxRepository(
    private val httpClient: HttpClient,
    private val planRepository: PlanRepository,
) {
    private val base = "${SupabaseConfig.URL}/functions/v1"
    private fun today() = Clock.System.todayIn(TimeZone.currentSystemDefault()).toString()
    private fun tzId() = TimeZone.currentSystemDefault().id

    /** plan defer:结构化直写 inbox(不走 LLM,与 inbox-capture 同管道)。 */
    suspend fun insertDeferred(title: String, dueDate: String, duePart: String? = null) {
        val session = planRepository.ensureSession()
        val resp = httpClient.post("$base/inbox-capture") {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(
                InboxCaptureRequest(
                    structured = InboxStructuredCaptureDto(title = title.trim(), dueDate = dueDate, duePart = duePart),
                    clientLocalDate = today(),
                    clientTz = tzId(),
                    source = "plan_defer",
                ),
            )
        }
        if (!resp.status.isSuccess()) {
            val raw = resp.bodyAsText()
            com.thinkandact.core.debug.FeDebug.backend("/inbox-capture structured", resp.status.value, null, raw)
            throw IllegalStateException(captureReason(raw))
        }
    }

    /** 批量 defer(plan 确认 / revise apply 后)。 */
    suspend fun insertDeferredAll(items: List<com.thinkandact.data.remote.DeferredItemDto>) {
        for (item in items) {
            val due = item.dueDate?.takeIf { it.isNotBlank() } ?: continue
            insertDeferred(item.title, due, item.duePart)
        }
    }

    /** 记一笔 + AI 抽日期。后端保证原文不丢(extract_failed 时 due 为 null)。 */
    suspend fun capture(rawText: String, source: String): InboxCaptureResponse {
        val session = planRepository.ensureSession()
        val resp = httpClient.post("$base/inbox-capture") {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(InboxCaptureRequest(rawText = rawText.trim(), clientLocalDate = today(), clientTz = tzId(), source = source))
        }
        if (!resp.status.isSuccess()) {
            val raw = resp.bodyAsText()
            com.thinkandact.core.debug.FeDebug.backend("/inbox-capture", resp.status.value, null, raw)
            throw IllegalStateException(captureReason(raw))
        }
        return resp.body()
    }

    /** 全量非 deleted；客户端分组。 */
    suspend fun list(): List<InboxItemDto> {
        val session = planRepository.ensureSession()
        val resp = httpClient.get("$base/inbox-list") {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
        }
        if (!resp.status.isSuccess()) {
            com.thinkandact.core.debug.FeDebug.backend("/inbox-list", resp.status.value, null, resp.bodyAsText())
            throw IllegalStateException(resp.bodyAsText().ifBlank { "拉收件箱失败了。" })
        }
        return resp.body<InboxListResponse>().items
    }

    /** 角标：pending 且 due ≤ 本地今天（没定日子不计）。失败返回 0(安静)。 */
    suspend fun badgeCount(): Int = runCatching {
        val session = planRepository.ensureSession()
        val resp = httpClient.get("$base/inbox-badge?client_local_date=${today()}") {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
        }
        if (!resp.status.isSuccess()) 0 else resp.body<InboxBadgeResponse>().count
    }.getOrDefault(0)

    /** 加进今天：后端原子生成 tasks(source=inbox) + 翻 added。失败抛明确 reason。 */
    suspend fun addToday(id: String) = update(
        InboxUpdateRequest(id = id, action = "add_today", clientLocalDate = today(), clientTz = tzId()),
    )

    suspend fun dismiss(id: String) = update(InboxUpdateRequest(id = id, action = "dismiss"))
    suspend fun delete(id: String) = update(InboxUpdateRequest(id = id, action = "delete"))
    suspend fun setDue(id: String, dueDate: String?, duePart: String?) =
        update(InboxUpdateRequest(id = id, action = "set_due", dueDate = dueDate, duePart = duePart))

    private suspend fun update(req: InboxUpdateRequest) {
        val session = planRepository.ensureSession()
        val resp = httpClient.post("$base/inbox-update") {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(req)
        }
        if (!resp.status.isSuccess()) {
            val raw = resp.bodyAsText()
            com.thinkandact.core.debug.FeDebug.backend("/inbox-update ${req.action}", resp.status.value, null, raw)
            throw IllegalStateException(updateReason(req.action, raw))
        }
    }

    private fun captureReason(raw: String): String = when {
        raw.contains("FAIR_USE", true) -> "今天的次数用完了,先打字记吧。"
        raw.contains("TOO_LONG", true) -> "这条太长了,精简一下。"
        raw.contains("Unable to resolve host", true) || raw.contains("timeout", true) || raw.contains("TIMEOUT", true) ->
            "现在网络不太稳,笔记还没存上,等会儿再记。"
        else -> "没存上,再试一次(笔记没丢)。"
    }

    private fun updateReason(action: String, raw: String): String = when {
        raw.contains("已经处理过") || raw.contains("NOT_PENDING") -> "这条已经处理过了。"
        raw.contains("NOT_FOUND") || raw.contains("找不到") -> "找不到这条记录。"
        raw.contains("Unable to resolve host", true) || raw.contains("timeout", true) -> "现在网络不太稳,等会儿再来。"
        action == "add_today" -> "加进今天没成功,稍后再试。"
        else -> "没存上,再试一次。"
    }

    private fun io.ktor.client.request.HttpRequestBuilder.supabaseHeaders(bearerToken: String) {
        header("apikey", SupabaseConfig.CLIENT_KEY)
        header(HttpHeaders.Authorization, "Bearer $bearerToken")
    }
}
