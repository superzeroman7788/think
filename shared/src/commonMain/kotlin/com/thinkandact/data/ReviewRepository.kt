package com.thinkandact.data

import com.thinkandact.core.config.SupabaseConfig
import com.thinkandact.data.remote.DailyReflectionDto
import com.thinkandact.data.remote.DaySummaryRequest
import com.thinkandact.data.remote.DaySummaryResponse
import com.thinkandact.data.remote.MemoryAddRequest
import com.thinkandact.data.remote.MemoryAddResponse
import com.thinkandact.data.remote.MemoryRowDto
import com.thinkandact.data.remote.MemorySoftDeleteDto
import com.thinkandact.data.remote.RecentTaskDto
import com.thinkandact.data.remote.ReflectionProbeRequest
import com.thinkandact.data.remote.ReflectionProbeResponse
import com.thinkandact.data.remote.ReviewAddedDto
import com.thinkandact.data.remote.ReviewApplyProposalDto
import com.thinkandact.data.remote.ReviewApplyRequest
import com.thinkandact.data.remote.ReviewApplyResponse
import com.thinkandact.data.remote.ReviewDoneUpdateDto
import com.thinkandact.data.remote.ReviewParseRequest
import com.thinkandact.data.remote.ReviewParseResponse
import com.thinkandact.data.remote.ReviewTaskDto
import com.thinkandact.data.remote.ReviewTaskFullDto
import com.thinkandact.data.remote.TaskRowDto
import com.thinkandact.data.remote.TaskSkipUpdateDto
import com.thinkandact.data.remote.TaskStatusUpdateDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLQueryComponent
import io.ktor.http.isSuccess
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * 晚上「复盘」腿数据层（契约 v1.0：docs/BE_evening-review_接口契约.md）。
 *
 * 复用 [PlanRepository] 的会话/鉴权（[PlanRepository.ensureSession]）；REST + 5 个 edge fn。
 * 红线：记忆只在 `confirmed_by_user=true` 时调 [addMemory]；server 亦硬拦（400）。
 */
class ReviewRepository(
    private val httpClient: HttpClient,
    private val planRepository: PlanRepository,
) {
    private val tz get() = TimeZone.currentSystemDefault()
    private fun today() = Clock.System.todayIn(tz).toString()

    // ── 读：当天任务（§5.1 全量 select，含 was_rescheduled/source）──────────
    suspend fun fetchTodayTasks(): List<TaskRowDto> {
        val session = planRepository.ensureSession()
        val uid = session.userId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Missing user id for review fetch.")
        val response = httpClient.get(
            "${SupabaseConfig.URL}/rest/v1/tasks" +
                // v1.4:复盘 ① 审核只列已接受任务集,排除软建议(suggested)——不作打勾行、不进语音可改项。
                "?user_id=eq.$uid&date=eq.${today()}&deleted_at=is.null&status=not.eq.suggested" +
                "&select=id,title,note,planned_start,planned_duration,important,status,actual_start,actual_end," +
                "was_rescheduled,reschedule_count,source,task_type,time_of_day" +
                "&order=planned_start.asc.nullslast",
        ) {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(response.bodyAsText().ifBlank { "Fetch review tasks failed." })
        }
        return response.body()
    }

    // ── 读：当天复盘记录（§5.2，回读 summary/probe/response）──────────────
    suspend fun fetchTodayReflection(): DailyReflectionDto? {
        val session = planRepository.ensureSession()
        val uid = session.userId?.takeIf { it.isNotBlank() } ?: return null
        val response = httpClient.get(
            "${SupabaseConfig.URL}/rest/v1/daily_reflections" +
                "?user_id=eq.$uid&date=eq.${today()}" +
                "&select=date,ai_summary,ai_praise,ai_advice,ai_probe,user_response,promoted_memory,completed_at",
        ) {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) return null
        return response.body<List<DailyReflectionDto>>().firstOrNull()
    }

    // ── 点选逐条（§6.3）──────────────────────────────────────────────────
    /** 补记做了：status=done + actual_end（**不写 actual_start**，§8#2）。 */
    suspend fun markTaskDoneReview(id: String) {
        val session = planRepository.ensureSession()
        val response = httpClient.patch(taskByIdUrl(id)) {
            supabaseHeaders(session.accessToken)
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(ReviewDoneUpdateDto(actualEnd = Clock.System.now().toString()))
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(response.bodyAsText().ifBlank { "Review done update failed." })
        }
    }

    /** 三态循环回到「未做」：status=planned（点选循环用）。 */
    suspend fun markTaskPlannedReview(id: String) {
        val session = planRepository.ensureSession()
        val response = httpClient.patch(taskByIdUrl(id)) {
            supabaseHeaders(session.accessToken)
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(TaskStatusUpdateDto(status = "planned"))
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(response.bodyAsText().ifBlank { "Review reset update failed." })
        }
    }

    /** 标记没做：status=skipped（清 actual_start）。 */
    suspend fun markTaskSkippedReview(id: String) {
        val session = planRepository.ensureSession()
        val response = httpClient.patch(taskByIdUrl(id)) {
            supabaseHeaders(session.accessToken)
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(TaskSkipUpdateDto())
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(response.bodyAsText().ifBlank { "Review skip update failed." })
        }
    }

    // ── §6.1 语音批量 propose（计配额，只 propose 不落库）────────────────
    suspend fun parseReviewVoice(transcript: String, tasks: List<TaskRowDto>): ReviewParseResponse {
        val session = planRepository.ensureSession()
        val response = httpClient.post("${SupabaseConfig.URL}/functions/v1/review-parse-voice") {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(
                ReviewParseRequest(
                    date = today(),
                    timezone = tz.id,
                    transcript = transcript,
                    tasks = tasks.map {
                        ReviewTaskDto(
                            id = it.id, title = it.title, status = it.status,
                            plannedStart = it.plannedStart, actualStart = it.actualStart,
                        )
                    },
                ),
            )
        }
        if (!response.status.isSuccess()) {
            com.thinkandact.core.debug.FeDebug.backend("/review-parse-voice", response.status.value, null, response.bodyAsText())
            throw IllegalStateException("review-parse-voice HTTP ${response.status.value}: ${response.bodyAsText()}")
        }
        return response.body()
    }

    // ── §6.2 原子 apply（不计配额；基线陈旧 → REVIEW_STALE 409）──────────
    suspend fun applyReview(
        reviewId: String,
        proposed: List<ReviewApplyProposalDto>,
        added: List<ReviewAddedDto> = emptyList(),
    ): ReviewApplyResponse {
        val session = planRepository.ensureSession()
        val response = httpClient.post("${SupabaseConfig.URL}/functions/v1/review-apply") {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(ReviewApplyRequest(date = today(), reviewId = reviewId, proposed = proposed, added = added))
        }
        if (response.status.value == 409) {
            val body = response.bodyAsText()
            com.thinkandact.core.debug.FeDebug.backend("/review-apply", 409, "REVIEW_STALE?", body)
            if (body.contains("REVIEW_STALE", ignoreCase = true)) {
                throw ReviewStaleException("今天的记录刚才变过了,请看一下新的再确认。")
            }
            throw IllegalStateException("review-apply 409: $body")
        }
        if (!response.status.isSuccess()) {
            com.thinkandact.core.debug.FeDebug.backend("/review-apply", response.status.value, null, response.bodyAsText())
            throw IllegalStateException("review-apply HTTP ${response.status.value}: ${response.bodyAsText()}")
        }
        return response.body()
    }

    // ── §7 一句话总结（计配额；BE upsert daily_reflections.ai_summary）────
    suspend fun daySummary(tasks: List<TaskRowDto>): DaySummaryResponse {
        val session = planRepository.ensureSession()
        val response = httpClient.post("${SupabaseConfig.URL}/functions/v1/day-summary") {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(DaySummaryRequest(date = today(), tasks = tasks.map { it.toFullPayload() }))
        }
        if (!response.status.isSuccess()) {
            com.thinkandact.core.debug.FeDebug.backend("/day-summary", response.status.value, null, response.bodyAsText())
            throw IllegalStateException("day-summary HTTP ${response.status.value}: ${response.bodyAsText()}")
        }
        return response.body()
    }

    // ── §8 反思追问（计配额；BE upsert ai_probe；candidate_memory 仅回 FE）─
    suspend fun reflectionProbe(
        todayTasks: List<TaskRowDto>,
        recentTasks: List<RecentTaskDto> = emptyList(),
    ): ReflectionProbeResponse {
        val session = planRepository.ensureSession()
        val response = httpClient.post("${SupabaseConfig.URL}/functions/v1/reflection-probe") {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(
                ReflectionProbeRequest(
                    date = today(),
                    todayTasks = todayTasks.map { it.toFullPayload() },
                    recentTasks = recentTasks,
                ),
            )
        }
        if (!response.status.isSuccess()) {
            com.thinkandact.core.debug.FeDebug.backend("/reflection-probe", response.status.value, null, response.bodyAsText())
            throw IllegalStateException("reflection-probe HTTP ${response.status.value}: ${response.bodyAsText()}")
        }
        return response.body()
    }

    // ── §9 存记忆（不计配额；红线：confirmed_by_user 必 true）─────────────
    suspend fun addMemory(text: String, userResponse: String): MemoryAddResponse {
        val session = planRepository.ensureSession()
        val response = httpClient.post("${SupabaseConfig.URL}/functions/v1/memory-add") {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(
                MemoryAddRequest(
                    date = today(), text = text, userResponse = userResponse,
                    source = "reflection", confirmedByUser = true,
                ),
            )
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("memory-add HTTP ${response.status.value}: ${response.bodyAsText()}")
        }
        return response.body()
    }

    // ── §10 记忆 list / delete（"关于你"）────────────────────────────────
    suspend fun listMemories(): List<MemoryRowDto> {
        val session = planRepository.ensureSession()
        val uid = session.userId?.takeIf { it.isNotBlank() } ?: return emptyList()
        val response = httpClient.get(
            "${SupabaseConfig.URL}/rest/v1/memories" +
                "?user_id=eq.$uid&deleted_at=is.null&confirmed_by_user=eq.true" +
                "&select=id,text,source,added_at&order=added_at.desc",
        ) {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(response.bodyAsText().ifBlank { "List memories failed." })
        }
        return response.body()
    }

    suspend fun deleteMemory(id: String) {
        val session = planRepository.ensureSession()
        val response = httpClient.patch(
            "${SupabaseConfig.URL}/rest/v1/memories?id=eq.${id.encodeURLQueryComponent()}",
        ) {
            supabaseHeaders(session.accessToken)
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(MemorySoftDeleteDto(deletedAt = Clock.System.now().toString()))
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(response.bodyAsText().ifBlank { "Delete memory failed." })
        }
    }

    private fun TaskRowDto.toFullPayload() = ReviewTaskFullDto(
        id = id, title = title, plannedStart = plannedStart, plannedDuration = plannedDuration,
        status = status, actualStart = actualStart, actualEnd = actualEnd, important = important,
        wasRescheduled = wasRescheduled, source = source, taskType = taskType, timeOfDay = timeOfDay,
    )

    private fun taskByIdUrl(id: String): String =
        "${SupabaseConfig.URL}/rest/v1/tasks?id=eq.${id.encodeURLQueryComponent()}"

    private fun io.ktor.client.request.HttpRequestBuilder.supabaseHeaders(bearerToken: String) {
        header("apikey", SupabaseConfig.CLIENT_KEY)
        header(HttpHeaders.Authorization, "Bearer $bearerToken")
    }
}

/** review-apply 基线陈旧（propose 后任务变过）→ 上层重拉 + 重 parse（镜像 [PlanReviseStaleException]）。 */
class ReviewStaleException(message: String) : Exception(message)
