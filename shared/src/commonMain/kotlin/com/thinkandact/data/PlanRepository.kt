package com.thinkandact.data

import com.thinkandact.core.config.SupabaseConfig
import com.thinkandact.data.remote.AnonymousSignInResponse
import com.thinkandact.data.remote.PlanGenerateRequest
import com.thinkandact.data.remote.PlanGenerateResponseDto
import com.thinkandact.data.remote.PlanTaskDto
import com.thinkandact.data.remote.ApplyAddedReviseDto
import com.thinkandact.data.remote.ApplyRevisionDto
import com.thinkandact.data.remote.PlanReviseApplyRequest
import com.thinkandact.data.remote.PlanReviseApplyResponse
import com.thinkandact.data.remote.PlanReviseRequest
import com.thinkandact.data.remote.PlanReviseResponse
import com.thinkandact.data.remote.RefreshTokenRequest
import com.thinkandact.data.remote.ReviseTaskDto
import com.thinkandact.data.remote.TaskActualStartUpdateDto
import com.thinkandact.data.remote.TaskDoneUpdateDto
import com.thinkandact.data.remote.TaskInsertDto
import com.thinkandact.data.remote.TaskRowDto
import com.thinkandact.data.remote.TitleOnlyDto
import com.thinkandact.data.remote.TaskSkipUpdateDto
import com.thinkandact.data.remote.TaskSoftDeleteDto
import com.thinkandact.data.session.SessionStore
import com.thinkandact.data.session.SupabaseSession
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
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
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn

class PlanRepository(
    private val httpClient: HttpClient,
    private val sessionStore: SessionStore,
    private val sessionState: com.thinkandact.data.session.SessionState,
) {
    suspend fun generatePlan(rawInput: String): PlanGenerateResponseDto {
        return try {
            withTimeout(PLAN_GENERATE_TIMEOUT_MS) {
                generatePlanOnce(rawInput)
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            throw IllegalStateException("plan-generate timeout after ${PLAN_GENERATE_TIMEOUT_MS / 1000}s")
        }
    }

    private suspend fun generatePlanOnce(rawInput: String): PlanGenerateResponseDto {
        val session = ensureSession()
        val response = httpClient.post("${SupabaseConfig.URL}/functions/v1/plan-generate") {
            supabaseHeaders(session.accessToken)
            contentType(ContentType.Application.Json)
            timeout {
                requestTimeoutMillis = PLAN_GENERATE_TIMEOUT_MS
                socketTimeoutMillis = PLAN_GENERATE_TIMEOUT_MS
            }
            setBody(
                PlanGenerateRequest(
                    date = Clock.System.todayIn(TimeZone.currentSystemDefault()).toString(),
                    rawInput = rawInput,
                    hardConstraints = emptyList(),
                    tone = "friendly"
                )
            )
        }
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            com.thinkandact.core.debug.FeDebug.backend("/plan-generate", response.status.value, null, body)
            throw IllegalStateException(body.ifBlank { "Plan generation failed." })
        }
        return response.body()
    }

    suspend fun confirmTodayPlan(proposal: PlanGenerateResponseDto) {
        confirmTodayTasks(proposal.tasks + proposal.suggestionTasks)
    }

    /**
     * @return 实际插入的任务行(含库内 id),供上层立刻排 ★ 提醒(N-02),不必再拉一遍。
     * N-05:重确认保留 done/skipped 后,重新生成的计划可能再含已做完的事 →
     * 落库前按**当天已执行任务的标题**去重,同名不重插(避免一条 done 一条 planned 并存)。
     */
    suspend fun confirmTodayTasks(tasks: List<PlanTaskDto>): List<TaskRowDto> {
        val session = ensureSession()
        val userId = session.userId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Missing user id for task insert.")
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault()).toString()

        val executedTitles = fetchExecutedTitlesToday(session, userId, today)
        val deduped = tasks.filterNot { normalizeTitle(it.title) in executedTitles }
        tasks.filter { normalizeTitle(it.title) in executedTitles }.forEach {
            com.thinkandact.core.debug.FeDebug.drop("confirm 去重跳过「${it.title}」", "今天已有同名 done/skipped(N-05)")
        }

        softDeleteTodayTasks(session, userId, today)
        if (deduped.isEmpty()) return emptyList()

        // 时刻点(钉子):两段式落库——先插 block 拿到库 id,再按 HH:MM 把 point 锚到所在 block。
        // 无 block(纯钉子日)或锚不上 → anchor_task_id 留 null,渲染时按时间归位,不阻断。
        val blocks = deduped.filter { it.kind != "point" }
        val points = deduped.filter { it.kind == "point" }

        val blockRows = if (blocks.isEmpty()) emptyList() else insertTasks(
            session, blocks.map { it.toInsertDto(userId, today, "proposal") }
        )
        if (points.isEmpty()) return blockRows

        val blockIdByHhmm = blockRows.associateBy({ hhmmOf(it.plannedStart) }, { it.id })
        val pointRows = insertTasks(
            session,
            points.map { p ->
                p.toInsertDto(
                    userId = userId, date = today, fallbackSource = "proposal",
                    anchorTaskId = p.anchorBlockStart?.let { blockIdByHhmm[hhmmOf(it)] },
                )
            },
        )
        return blockRows + pointRows
    }

    private suspend fun insertTasks(session: SupabaseSession, rows: List<TaskInsertDto>): List<TaskRowDto> {
        val insert = httpClient.post("${SupabaseConfig.URL}/rest/v1/tasks") {
            supabaseHeaders(session.accessToken)
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(rows)
        }
        if (!insert.status.isSuccess()) {
            throw IllegalStateException(insert.bodyAsText().ifBlank { "Task insert failed." })
        }
        return insert.body()
    }

    /** 取 planned_start 的 HH:MM(兼容 ISO 与裸 HH:MM),用于把 point 锚到同时刻起点的 block。 */
    private fun hhmmOf(s: String?): String {
        if (s.isNullOrBlank()) return ""
        if (Regex("^\\d{2}:\\d{2}").containsMatchIn(s.trim())) return s.trim().take(5)
        return runCatching {
            kotlinx.datetime.Instant.parse(s).toLocalDateTime(TimeZone.currentSystemDefault())
                .let { "${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}" }
        }.getOrDefault("")
    }

    /** N-05:当天已执行(done/skipped)任务的归一化标题集合。查询失败放行(空集合),不阻断确认。 */
    private suspend fun fetchExecutedTitlesToday(
        session: SupabaseSession,
        userId: String,
        today: String,
    ): Set<String> = runCatching {
        val response = httpClient.get(
            "${SupabaseConfig.URL}/rest/v1/tasks" +
                "?user_id=eq.$userId&date=eq.$today&deleted_at=is.null&status=in.(done,skipped)&select=title"
        ) {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) return@runCatching emptySet()
        response.body<List<TitleOnlyDto>>().map { normalizeTitle(it.title) }.toSet()
    }.getOrDefault(emptySet())

    private fun normalizeTitle(title: String): String = title.trim().lowercase()

    /** 今天是否已确认过计划(有非软建议的真任务)——冷启动据此直达「今天」,而不是空的早上页。 */
    suspend fun hasTodayPlan(): Boolean {
        val session = ensureSession()
        val userId = session.userId?.takeIf { it.isNotBlank() } ?: return false
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault()).toString()
        val response = httpClient.get(
            "${SupabaseConfig.URL}/rest/v1/tasks" +
                "?user_id=eq.$userId&date=eq.$today&deleted_at=is.null&status=neq.suggested&select=id&limit=1"
        ) {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) return false
        return response.bodyAsText().trim().let { it.isNotBlank() && it != "[]" }
    }

    /** BUG-02：今天是否已有执行记录(done/skipped)——重确认前据此强提醒,避免误覆盖白天进度。 */
    suspend fun hasExecutedTasksToday(): Boolean {
        val session = ensureSession()
        val userId = session.userId?.takeIf { it.isNotBlank() } ?: return false
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault()).toString()
        val response = httpClient.get(
            "${SupabaseConfig.URL}/rest/v1/tasks" +
                "?user_id=eq.$userId&date=eq.$today&deleted_at=is.null&status=in.(done,skipped)&select=id&limit=1"
        ) {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) return false
        return response.bodyAsText().trim().let { it.isNotBlank() && it != "[]" }
    }

    /** 执行屏（块一）：读当天未删除任务，按计划时间升序。 */
    /** @param includeSuggested 完整计划页要展示软建议(soft);执行/复盘默认排除。 */
    suspend fun fetchTodayTasks(includeSuggested: Boolean = false): List<TaskRowDto> {
        val session = ensureSession()
        val userId = session.userId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Missing user id for task fetch.")
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault()).toString()
        // v1.4:默认排除软建议(suggested)——不计数、不作当前、不进 revise 负载;完整计划页传 true 看全。
        val suggestedFilter = if (includeSuggested) "" else "&status=not.eq.suggested"
        val response = httpClient.get(
            "${SupabaseConfig.URL}/rest/v1/tasks" +
                "?user_id=eq.$userId&date=eq.$today&deleted_at=is.null$suggestedFilter" +
                "&select=id,title,note,planned_start,planned_duration,important,status,actual_start,actual_end,task_type,time_of_day,kind,anchor_task_id" +
                "&order=planned_start.asc.nullslast"
        ) {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(response.bodyAsText().ifBlank { "Fetch today tasks failed." })
        }
        return response.body()
    }

    /** 完成：status=done + actual_end；actual_start 仅在有真实起点时写入（禁止用完成时刻冒充）。 */
    suspend fun markTaskDone(id: String, actualEnd: String, actualStart: String? = null) {
        val session = ensureSession()
        val body = if (actualStart != null) {
            TaskDoneUpdateDto(actualStart = actualStart, actualEnd = actualEnd)
        } else {
            com.thinkandact.data.remote.ReviewDoneUpdateDto(actualEnd = actualEnd)
        }
        val response = httpClient.patch(taskByIdUrl(id)) {
            supabaseHeaders(session.accessToken)
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(response.bodyAsText().ifBlank { "Task done update failed." })
        }
    }

    /** 跳过：status=skipped 且清空 actual_start（块一），避免被跳过的任务看着像「开始过」。 */
    suspend fun markTaskSkipped(id: String) {
        val session = ensureSession()
        val response = httpClient.patch(taskByIdUrl(id)) {
            supabaseHeaders(session.accessToken)
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(TaskSkipUpdateDto())
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(response.bodyAsText().ifBlank { "Task skip update failed." })
        }
    }

    /** 完整计划页「改时间」：PATCH planned_start。 */
    suspend fun updateTaskPlannedStart(id: String, plannedStartIso: String) {
        val session = ensureSession()
        val response = httpClient.patch(taskByIdUrl(id)) {
            supabaseHeaders(session.accessToken)
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(com.thinkandact.data.remote.TaskPlannedStartUpdateDto(plannedStart = plannedStartIso))
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(response.bodyAsText().ifBlank { "Task time update failed." })
        }
    }

    /** F7-03 点选编辑「改标题」：PATCH title。 */
    suspend fun updateTaskTitle(id: String, title: String) {
        val session = ensureSession()
        val response = httpClient.patch(taskByIdUrl(id)) {
            supabaseHeaders(session.accessToken)
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(com.thinkandact.data.remote.TaskTitleUpdateDto(title = title))
        }
        if (!response.status.isSuccess()) throw IllegalStateException(response.bodyAsText().ifBlank { "Task title update failed." })
    }

    /** F7-03 点选编辑「改时长」：PATCH planned_duration(分钟)。 */
    suspend fun updateTaskDuration(id: String, minutes: Int) {
        val session = ensureSession()
        val response = httpClient.patch(taskByIdUrl(id)) {
            supabaseHeaders(session.accessToken)
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(com.thinkandact.data.remote.TaskDurationUpdateDto(plannedDuration = minutes))
        }
        if (!response.status.isSuccess()) throw IllegalStateException(response.bodyAsText().ifBlank { "Task duration update failed." })
    }

    /** F7-03 点选编辑「★ 重要」开关：PATCH important。 */
    suspend fun updateTaskImportant(id: String, important: Boolean) {
        val session = ensureSession()
        val response = httpClient.patch(taskByIdUrl(id)) {
            supabaseHeaders(session.accessToken)
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(com.thinkandact.data.remote.TaskImportantUpdateDto(important = important))
        }
        if (!response.status.isSuccess()) throw IllegalStateException(response.bodyAsText().ifBlank { "Task important update failed." })
    }

    /** F7-03 完整计划页「+ 加一项 / + 加时刻点」：直接往今天插一条真任务。point → planned_duration=0。 */
    suspend fun addTaskToday(title: String, plannedStartIso: String?, plannedDuration: Int, important: Boolean, kind: String): TaskRowDto {
        val session = ensureSession()
        val userId = session.userId?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("Missing user id for add task.")
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault()).toString()
        val row = TaskInsertDto(
            userId = userId,
            date = today,
            title = title,
            plannedStart = plannedStartIso,
            plannedDuration = if (kind == "point") 0 else plannedDuration,
            important = important,
            source = "user_text",
            status = "planned",
            kind = kind,
        )
        return insertTasks(session, listOf(row)).first()
    }

    /** 软建议「加入」→ 升级真任务：status=planned（不改 source）。 */
    suspend fun markTaskPlanned(id: String) {
        val session = ensureSession()
        val response = httpClient.patch(taskByIdUrl(id)) {
            supabaseHeaders(session.accessToken)
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(com.thinkandact.data.remote.TaskStatusUpdateDto(status = "planned"))
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(response.bodyAsText().ifBlank { "Accept suggestion failed." })
        }
    }

    /** 变「当前」时写实际开始（块一）。最佳努力,失败不阻断 UI。 */
    suspend fun markTaskStarted(id: String, actualStart: String) {
        val session = ensureSession()
        val response = httpClient.patch(taskByIdUrl(id)) {
            supabaseHeaders(session.accessToken)
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(TaskActualStartUpdateDto(actualStart = actualStart))
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(response.bodyAsText().ifBlank { "Task start update failed." })
        }
    }

    /** 块二：拿剩余任务 + 指令出拟议 + 前后 diff。不落库。 */
    suspend fun proposeRevision(instruction: String, tasks: List<TaskRowDto>): PlanReviseResponse {
        val session = ensureSession()
        val tz = TimeZone.currentSystemDefault()
        val response = httpClient.post("${SupabaseConfig.URL}/functions/v1/plan-revise") {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(
                PlanReviseRequest(
                    date = Clock.System.todayIn(tz).toString(),
                    timezone = tz.id,
                    now = Clock.System.now().toString(),
                    instruction = instruction,
                    tasks = tasks.map {
                        ReviseTaskDto(
                            id = it.id, title = it.title, plannedStart = it.plannedStart,
                            plannedDuration = it.plannedDuration, important = it.important,
                            status = it.status, actualStart = it.actualStart,
                            kind = it.kind, // BE 据此 enforce 块边界不因钉子变化
                        )
                    },
                )
            )
        }
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            com.thinkandact.core.debug.FeDebug.backend("/plan-revise", response.status.value, null, body)
            throw IllegalStateException("plan-revise HTTP ${response.status.value}: $body")
        }
        return response.body()
    }

    /** 块二：用户确认后原子落库。基线陈旧 → 抛 [PlanReviseStaleException]，上层重新 propose。 */
    suspend fun applyRevision(
        revisionId: String,
        revisions: List<ApplyRevisionDto>,
        added: List<ApplyAddedReviseDto> = emptyList(),
    ): PlanReviseApplyResponse {
        val session = ensureSession()
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault()).toString()
        val response = httpClient.post("${SupabaseConfig.URL}/functions/v1/plan-revise-apply") {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(PlanReviseApplyRequest(date = today, revisionId = revisionId, revisions = revisions, added = added))
        }
        if (response.status.value == 409) {
            val body = response.bodyAsText()
            com.thinkandact.core.debug.FeDebug.backend("/plan-revise-apply", 409, "APPLY_STALE?", body)
            if (body.contains("APPLY_STALE", ignoreCase = true)) {
                throw PlanReviseStaleException("今天的安排刚才变过了,请看一下新的再确认。")
            }
            throw IllegalStateException("plan-revise-apply 409: $body")
        }
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            com.thinkandact.core.debug.FeDebug.backend("/plan-revise-apply", response.status.value, null, body)
            throw IllegalStateException("plan-revise-apply HTTP ${response.status.value}: $body")
        }
        return response.body()
    }

    private fun taskByIdUrl(id: String): String =
        "${SupabaseConfig.URL}/rest/v1/tasks?id=eq.${id.encodeURLQueryComponent()}"

    suspend fun ensureSession(): SupabaseSession {
        val existing = sessionStore.load()
        if (existing != null && !existing.isExpiredSoon()) {
            return existing
        }
        if (existing?.refreshToken != null) {
            runCatching { refreshSession(existing.refreshToken) }
                .onSuccess { return it }
        }
        // BUG-03：刷新失败 → 清会话 + 置登录态 false（路由回登录页），**绝不静默建匿名号**。
        sessionStore.clear()
        sessionState.onLoggedOut()
        throw com.thinkandact.data.session.SessionExpiredException()
    }

    private suspend fun signInAnonymously(): SupabaseSession {
        val response = httpClient.post("${SupabaseConfig.URL}/auth/v1/signup") {
            supabaseHeaders(SupabaseConfig.CLIENT_KEY)
            contentType(ContentType.Application.Json)
            setBody(emptyMap<String, String>())
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(response.bodyAsText().ifBlank { "Anonymous sign-in failed." })
        }
        return response.body<AnonymousSignInResponse>().toSession().also(sessionStore::save)
    }

    private suspend fun refreshSession(refreshToken: String): SupabaseSession {
        val response = httpClient.post("${SupabaseConfig.URL}/auth/v1/token?grant_type=refresh_token") {
            supabaseHeaders(SupabaseConfig.CLIENT_KEY)
            contentType(ContentType.Application.Json)
            setBody(RefreshTokenRequest(refreshToken))
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(response.bodyAsText().ifBlank { "Session refresh failed." })
        }
        return response.body<AnonymousSignInResponse>().toSession().also(sessionStore::save)
    }

    /**
     * 确认今天的计划 = 重排今天：先软删当天**所有**未删除任务（不限状态），
     * 再插入新计划。否则上次 done/skipped 的任务会残留、累加，执行屏进度对不上。
     */
    private suspend fun softDeleteTodayTasks(
        session: SupabaseSession,
        userId: String,
        today: String
    ) {
        // BUG-02：只软删**未执行**的(planned/suggested);已 done/skipped/dropped 的进度保留,
        // 重确认不再抹掉当天白天进度、★提醒、历史"今天已完成"。
        val response = httpClient.patch(
            "${SupabaseConfig.URL}/rest/v1/tasks" +
                "?user_id=eq.$userId&date=eq.$today&deleted_at=is.null" +
                "&status=in.(planned,suggested)"
        ) {
            supabaseHeaders(session.accessToken)
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(TaskSoftDeleteDto(deletedAt = Clock.System.now().toString()))
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(response.bodyAsText().ifBlank { "Task cleanup failed." })
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.supabaseHeaders(bearerToken: String) {
        header("apikey", SupabaseConfig.CLIENT_KEY)
        header(HttpHeaders.Authorization, "Bearer $bearerToken")
    }

    private fun AnonymousSignInResponse.toSession(): SupabaseSession {
        val token = accessToken?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(errorDescription ?: error ?: msg ?: "No access token returned.")
        val nowMs = Clock.System.now().toEpochMilliseconds()
        val expiresAtMillis = expiresAt?.let { it * 1000L }
            ?: expiresIn?.let { nowMs + it * 1000L }
        return SupabaseSession(
            accessToken = token,
            refreshToken = refreshToken,
            userId = user?.id,
            expiresAtMillis = expiresAtMillis
        )
    }

    private fun SupabaseSession.isExpiredSoon(): Boolean {
        val expiresAt = expiresAtMillis ?: return false
        return expiresAt <= Clock.System.now().toEpochMilliseconds() + SESSION_EXPIRY_BUFFER_MS
    }

    private companion object {
        const val SESSION_EXPIRY_BUFFER_MS = 60_000L
        /** plan-generate 含 LLM 多轮 JSON 重试,比全局 HTTP 40s 更宽。 */
        const val PLAN_GENERATE_TIMEOUT_MS = 120_000L
    }
}

/** apply 时提案基线已陈旧（今天的安排在 propose 后变过）→ 上层重新 propose。 */
class PlanReviseStaleException(message: String) : Exception(message)

private fun PlanTaskDto.toInsertDto(
    userId: String,
    date: String,
    fallbackSource: String,
    forceSuggestion: Boolean = false,
    anchorTaskId: String? = null,
): TaskInsertDto = TaskInsertDto(
    userId = userId,
    date = date,
    title = title,
    note = note?.takeIf { it.isNotBlank() },
    plannedStart = plannedStart,
    // 时刻点不占时长:point 强制 duration=0(满足 DB 约束);block 照旧。
    plannedDuration = if (kind == "point") 0 else plannedDuration,
    important = important,
    taskType = taskType,
    timeOfDay = timeOfDay,
    source = if (forceSuggestion) "ai_suggestion" else source?.takeIf { it.isNotBlank() } ?: fallbackSource,
    // v1.4：原样写入 status(软建议 = suggested),禁止把 suggestion 当 planned 插入。
    status = if (forceSuggestion) "suggested" else this.status,
    kind = kind,
    anchorTaskId = anchorTaskId,
)
