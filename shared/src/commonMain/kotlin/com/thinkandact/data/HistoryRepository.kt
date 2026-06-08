package com.thinkandact.data

import com.thinkandact.core.config.SupabaseConfig
import com.thinkandact.data.remote.DailyReflectionDto
import com.thinkandact.data.remote.TaskRowDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn

/**
 * 历史视图「过去 7 天」数据层（FE 直连 REST，无新 Edge Function / schema）。
 * 计数 `做成/共` 与 BE `task_semantics.historyDayStats()` 对齐（见 BE_历史视图_数据读取_给Code.md）。
 */
class HistoryRepository(
    private val httpClient: HttpClient,
    private val planRepository: PlanRepository,
) {
    /** 近 7 天任务（含 suggested；卡片展开要显示;计数时再排除）。 */
    suspend fun fetchLast7DaysTasks(): List<TaskRowDto> {
        val session = planRepository.ensureSession()
        val uid = session.userId?.takeIf { it.isNotBlank() } ?: return emptyList()
        val tz = TimeZone.currentSystemDefault()
        val end = Clock.System.todayIn(tz)
        val start = end.minus(DatePeriod(days = 6))
        val response = httpClient.get(
            "${SupabaseConfig.URL}/rest/v1/tasks" +
                "?user_id=eq.$uid&date=gte.$start&date=lte.$end&deleted_at=is.null" +
                "&select=id,date,title,planned_start,planned_duration,important,status,source" +
                "&order=date.asc,planned_start.asc.nullslast",
        ) {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) {
            com.thinkandact.core.debug.FeDebug.backend("/rest tasks(history)", response.status.value, null, response.bodyAsText())
            throw IllegalStateException(response.bodyAsText().ifBlank { "Fetch history tasks failed." })
        }
        return response.body()
    }

    /** 近 7 天复盘「夸」（卡片 note）。 */
    suspend fun fetchLast7DaysReflections(): List<DailyReflectionDto> {
        val session = planRepository.ensureSession()
        val uid = session.userId?.takeIf { it.isNotBlank() } ?: return emptyList()
        val tz = TimeZone.currentSystemDefault()
        val end = Clock.System.todayIn(tz)
        val start = end.minus(DatePeriod(days = 6))
        val response = httpClient.get(
            "${SupabaseConfig.URL}/rest/v1/daily_reflections" +
                "?user_id=eq.$uid&date=gte.$start&date=lte.$end" +
                "&select=date,ai_praise,ai_summary&order=date.asc",
        ) {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) return emptyList()
        return response.body()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.supabaseHeaders(bearerToken: String) {
        header("apikey", SupabaseConfig.CLIENT_KEY)
        header(HttpHeaders.Authorization, "Bearer $bearerToken")
    }

    companion object {
        /**
         * `做成/共`（对齐 BE historyDayStats）：committed = 非 dropped/suggested；
         * 做成=done，共=done+planned（跳过不进分子分母）。
         */
        fun historyDayStats(tasks: List<TaskRowDto>): Pair<Int, Int> {
            val committed = tasks.filter { it.status != "dropped" && it.status != "suggested" }
            val done = committed.count { it.status == "done" }
            val undone = committed.count { it.status == "planned" }
            return done to (done + undone)
        }
    }
}
