package com.thinkandact.data

import com.thinkandact.core.config.SupabaseConfig
import com.thinkandact.data.remote.RoutineDraftDto
import com.thinkandact.data.remote.RoutineDto
import com.thinkandact.data.remote.RoutineEnabledUpdateDto
import com.thinkandact.data.remote.RoutineInsertDto
import com.thinkandact.data.remote.RoutineParseRequest
import com.thinkandact.data.remote.RoutineParseResponse
import com.thinkandact.data.remote.RoutineSoftDeleteDto
import com.thinkandact.data.remote.RoutineUpdateDto
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

class RoutineRepository(
    private val httpClient: HttpClient,
    private val planRepository: PlanRepository
) {
    suspend fun listRoutines(): List<RoutineDto> {
        val session = planRepository.ensureSession()
        val response = httpClient.get(
            "${SupabaseConfig.URL}/rest/v1/routines" +
                "?select=id,user_id,title,note,type,default_time,repeat_days,enabled" +
                "&deleted_at=is.null&order=default_time.asc"
        ) {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) response.throwSupabaseError("Routine list")
        return response.body()
    }

    /** 语音转写的自然语句 → 结构化日常草稿（标题+时间+重复）。BE：routine-parse。 */
    suspend fun parseRoutines(text: String, timezone: String): List<RoutineDraftDto> {
        val session = planRepository.ensureSession()
        val response = httpClient.post("${SupabaseConfig.URL}/functions/v1/routine-parse") {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(RoutineParseRequest(text = text, timezone = timezone))
        }
        if (!response.status.isSuccess()) response.throwSupabaseError("Routine parse")
        return response.body<RoutineParseResponse>().routines
    }

    suspend fun createRoutine(
        title: String,
        note: String?,
        type: String?,
        defaultTime: String,
        repeatDays: List<Int>
    ): RoutineDto {
        val session = planRepository.ensureSession()
        val userId = session.userId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Missing user id for routine insert.")
        val response = httpClient.post("${SupabaseConfig.URL}/rest/v1/routines") {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(
                listOf(
                    RoutineInsertDto(
                        userId = userId,
                        title = title,
                        note = note,
                        type = type,
                        defaultTime = defaultTime,
                        repeatDays = repeatDays,
                        enabled = true
                    )
                )
            )
        }
        if (!response.status.isSuccess()) response.throwSupabaseError("Routine create")
        return response.body<List<RoutineDto>>().firstOrNull()
            ?: throw IllegalStateException("Routine create failed: empty response from Supabase.")
    }

    suspend fun updateRoutine(
        id: String,
        title: String,
        note: String?,
        type: String?,
        defaultTime: String,
        repeatDays: List<Int>,
        enabled: Boolean
    ): RoutineDto {
        val session = planRepository.ensureSession()
        val response = httpClient.patch(
            "${SupabaseConfig.URL}/rest/v1/routines?id=eq.${id.encodeURLQueryComponent()}"
        ) {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(RoutineUpdateDto(title = title, note = note, type = type, defaultTime = defaultTime, repeatDays = repeatDays, enabled = enabled))
        }
        if (!response.status.isSuccess()) response.throwSupabaseError("Routine update")
        return response.body<List<RoutineDto>>().firstOrNull()
            ?: throw IllegalStateException("Routine update failed: empty response from Supabase.")
    }

    suspend fun setEnabled(id: String, enabled: Boolean): RoutineDto {
        val session = planRepository.ensureSession()
        val response = httpClient.patch(
            "${SupabaseConfig.URL}/rest/v1/routines?id=eq.${id.encodeURLQueryComponent()}"
        ) {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(RoutineEnabledUpdateDto(enabled = enabled))
        }
        if (!response.status.isSuccess()) response.throwSupabaseError("Routine enabled update")
        return response.body<List<RoutineDto>>().firstOrNull()
            ?: throw IllegalStateException("Routine enabled update failed: empty response from Supabase.")
    }

    suspend fun deleteRoutine(id: String) {
        val session = planRepository.ensureSession()
        val response = httpClient.patch(
            "${SupabaseConfig.URL}/rest/v1/routines?id=eq.${id.encodeURLQueryComponent()}"
        ) {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(RoutineSoftDeleteDto(deletedAt = Clock.System.now().toString()))
        }
        if (!response.status.isSuccess()) response.throwSupabaseError("Routine delete")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.supabaseHeaders(bearerToken: String) {
        header("apikey", SupabaseConfig.CLIENT_KEY)
        header(HttpHeaders.Authorization, "Bearer $bearerToken")
    }

    private suspend fun io.ktor.client.statement.HttpResponse.throwSupabaseError(action: String): Nothing {
        val body = bodyAsText().trim()
        throw IllegalStateException("$action failed: HTTP ${status.value} ${body.ifBlank { "empty response body" }}")
    }
}
