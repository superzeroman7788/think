package com.thinkandact.app.data

import com.thinkandact.app.BuildConfig
import com.thinkandact.app.data.remote.AnonymousSignInResponse
import com.thinkandact.app.data.remote.PlanGenerateRequest
import com.thinkandact.app.data.remote.PlanGenerateResponseDto
import com.thinkandact.app.data.remote.PlanTaskDto
import com.thinkandact.app.data.remote.RefreshTokenRequest
import com.thinkandact.app.data.remote.TaskInsertDto
import com.thinkandact.app.data.remote.TaskSoftDeleteDto
import com.thinkandact.app.data.session.SupabaseSession
import com.thinkandact.app.data.session.SupabaseSessionStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlanRepository @Inject constructor(
    private val httpClient: HttpClient,
    private val sessionStore: SupabaseSessionStore
) {
    suspend fun generatePlan(rawInput: String): PlanGenerateResponseDto {
        val session = ensureSession()
        val response = httpClient.post("${BuildConfig.SUPABASE_URL}/functions/v1/plan-generate") {
            supabaseHeaders(session.accessToken)
            contentType(ContentType.Application.Json)
            setBody(
                PlanGenerateRequest(
                    date = LocalDate.now().toString(),
                    rawInput = rawInput,
                    hardConstraints = emptyList(),
                    tone = "friendly"
                )
            )
        }

        if (!response.status.isSuccess()) {
            throw IllegalStateException(response.bodyAsText().ifBlank { "Plan generation failed." })
        }

        return response.body()
    }

    suspend fun confirmTodayPlan(proposal: PlanGenerateResponseDto) {
        confirmTodayTasks(proposal.tasks + proposal.suggestionTasks)
    }

    suspend fun confirmTodayTasks(tasks: List<PlanTaskDto>) {
        val session = ensureSession()
        val userId = session.userId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Missing user id for task insert.")
        val today = LocalDate.now().toString()

        softDeleteTodayPlannedTasks(session, userId, today)

        val rows = tasks.map { task ->
            task.toInsertDto(
                userId = userId,
                date = today,
                fallbackSource = "user_voice",
                forceSuggestion = task.source == "ai_suggestion"
            )
        }

        if (rows.isEmpty()) return

        val insert = httpClient.post("${BuildConfig.SUPABASE_URL}/rest/v1/tasks") {
            supabaseHeaders(session.accessToken)
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(rows)
        }

        if (!insert.status.isSuccess()) {
            throw IllegalStateException(insert.bodyAsText().ifBlank { "Task insert failed." })
        }
    }

    suspend fun ensureSession(): SupabaseSession {
        val existing = sessionStore.load()
        if (existing != null && !existing.isExpiredSoon()) {
            return existing
        }

        if (existing?.refreshToken != null) {
            runCatching { refreshSession(existing.refreshToken) }
                .onSuccess { return it }
        }

        return signInAnonymously()
    }

    private suspend fun signInAnonymously(): SupabaseSession {
        val response = httpClient.post("${BuildConfig.SUPABASE_URL}/auth/v1/signup") {
            supabaseHeaders(BuildConfig.SUPABASE_CLIENT_KEY)
            contentType(ContentType.Application.Json)
            setBody(emptyMap<String, String>())
        }

        if (!response.status.isSuccess()) {
            throw IllegalStateException(response.bodyAsText().ifBlank { "Anonymous sign-in failed." })
        }

        return response.body<AnonymousSignInResponse>().toSession().also(sessionStore::save)
    }

    private suspend fun refreshSession(refreshToken: String): SupabaseSession {
        val response = httpClient.post("${BuildConfig.SUPABASE_URL}/auth/v1/token?grant_type=refresh_token") {
            supabaseHeaders(BuildConfig.SUPABASE_CLIENT_KEY)
            contentType(ContentType.Application.Json)
            setBody(RefreshTokenRequest(refreshToken))
        }

        if (!response.status.isSuccess()) {
            throw IllegalStateException(response.bodyAsText().ifBlank { "Session refresh failed." })
        }

        return response.body<AnonymousSignInResponse>().toSession().also(sessionStore::save)
    }

    private suspend fun softDeleteTodayPlannedTasks(
        session: SupabaseSession,
        userId: String,
        today: String
    ) {
        val response = httpClient.patch(
            "${BuildConfig.SUPABASE_URL}/rest/v1/tasks" +
                "?user_id=eq.$userId&date=eq.$today&status=eq.planned&deleted_at=is.null"
        ) {
            supabaseHeaders(session.accessToken)
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(TaskSoftDeleteDto(deletedAt = Instant.now().toString()))
        }

        if (!response.status.isSuccess()) {
            throw IllegalStateException(response.bodyAsText().ifBlank { "Task cleanup failed." })
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.supabaseHeaders(bearerToken: String) {
        header("apikey", BuildConfig.SUPABASE_CLIENT_KEY)
        header(HttpHeaders.Authorization, "Bearer $bearerToken")
    }

    private fun AnonymousSignInResponse.toSession(): SupabaseSession {
        val token = accessToken?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(errorDescription ?: error ?: msg ?: "No access token returned.")
        val expiresAtMillis = expiresAt?.let { it * 1000L }
            ?: expiresIn?.let { System.currentTimeMillis() + it * 1000L }

        return SupabaseSession(
            accessToken = token,
            refreshToken = refreshToken,
            userId = user?.id,
            expiresAtMillis = expiresAtMillis
        )
    }

    private fun SupabaseSession.isExpiredSoon(): Boolean {
        val expiresAt = expiresAtMillis ?: return false
        return expiresAt <= System.currentTimeMillis() + SESSION_EXPIRY_BUFFER_MS
    }

    private companion object {
        const val SESSION_EXPIRY_BUFFER_MS = 60_000L
    }
}

private fun PlanTaskDto.toInsertDto(
    userId: String,
    date: String,
    fallbackSource: String,
    forceSuggestion: Boolean = false
): TaskInsertDto {
    return TaskInsertDto(
        userId = userId,
        date = date,
        title = title,
        note = note?.takeIf { it.isNotBlank() },
        plannedStart = plannedStart,
        plannedDuration = plannedDuration,
        important = important,
        taskType = taskType,
        timeOfDay = timeOfDay,
        source = if (forceSuggestion) "ai_suggestion" else source?.takeIf { it.isNotBlank() } ?: fallbackSource
    )
}
