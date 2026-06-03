package com.thinkandact.app.data

import com.thinkandact.app.BuildConfig
import com.thinkandact.app.data.remote.RoutineDto
import com.thinkandact.app.data.remote.RoutineEnabledUpdateDto
import com.thinkandact.app.data.remote.RoutineInsertDto
import com.thinkandact.app.data.remote.RoutineSoftDeleteDto
import com.thinkandact.app.data.remote.RoutineUpdateDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoutineRepository @Inject constructor(
    private val httpClient: HttpClient,
    private val planRepository: PlanRepository
) {
    suspend fun listRoutines(): List<RoutineDto> {
        val session = planRepository.ensureSession()
        val response = httpClient.get(
            "${BuildConfig.SUPABASE_URL}/rest/v1/routines" +
                "?select=id,user_id,title,note,type,default_time,repeat_days,enabled" +
                "&deleted_at=is.null&order=default_time.asc"
        ) {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
        }

        if (!response.status.isSuccess()) {
            response.throwSupabaseError("Routine list")
        }

        return response.body()
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
        val response = httpClient.post("${BuildConfig.SUPABASE_URL}/rest/v1/routines") {
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

        if (!response.status.isSuccess()) {
            response.throwSupabaseError("Routine create")
        }

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
        val response = httpClient.patch("${BuildConfig.SUPABASE_URL}/rest/v1/routines?id=eq.${id.urlEncode()}") {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(
                RoutineUpdateDto(
                    title = title,
                    note = note,
                    type = type,
                    defaultTime = defaultTime,
                    repeatDays = repeatDays,
                    enabled = enabled
                )
            )
        }

        if (!response.status.isSuccess()) {
            response.throwSupabaseError("Routine update")
        }

        return response.body<List<RoutineDto>>().firstOrNull()
            ?: throw IllegalStateException("Routine update failed: empty response from Supabase.")
    }

    suspend fun setEnabled(id: String, enabled: Boolean): RoutineDto {
        val session = planRepository.ensureSession()
        val response = httpClient.patch("${BuildConfig.SUPABASE_URL}/rest/v1/routines?id=eq.${id.urlEncode()}") {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(RoutineEnabledUpdateDto(enabled = enabled))
        }

        if (!response.status.isSuccess()) {
            response.throwSupabaseError("Routine enabled update")
        }

        return response.body<List<RoutineDto>>().firstOrNull()
            ?: throw IllegalStateException("Routine enabled update failed: empty response from Supabase.")
    }

    suspend fun deleteRoutine(id: String) {
        val session = planRepository.ensureSession()
        val response = httpClient.patch("${BuildConfig.SUPABASE_URL}/rest/v1/routines?id=eq.${id.urlEncode()}") {
            supabaseHeaders(session.accessToken)
            accept(ContentType.Application.Json)
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(RoutineSoftDeleteDto(deletedAt = Instant.now().toString()))
        }

        if (!response.status.isSuccess()) {
            response.throwSupabaseError("Routine delete")
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.supabaseHeaders(bearerToken: String) {
        header("apikey", BuildConfig.SUPABASE_CLIENT_KEY)
        header(HttpHeaders.Authorization, "Bearer $bearerToken")
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())

    private suspend fun io.ktor.client.statement.HttpResponse.throwSupabaseError(action: String): Nothing {
        val body = bodyAsText().trim()
        val detail = body.ifBlank { "empty response body" }
        throw IllegalStateException("$action failed: HTTP ${status.value} $detail")
    }
}
