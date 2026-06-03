package com.thinkandact.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AnonymousSignInResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val user: AuthUser? = null,
    val msg: String? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null
)

@Serializable
data class AuthUser(
    val id: String? = null,
    @SerialName("is_anonymous") val isAnonymous: Boolean? = null
)

@Serializable
data class RefreshTokenRequest(
    @SerialName("refresh_token") val refreshToken: String
)

@Serializable
data class PlanGenerateRequest(
    val date: String,
    @SerialName("raw_input") val rawInput: String,
    @SerialName("hard_constraints") val hardConstraints: List<HardConstraintDto> = emptyList(),
    val tone: String = "friendly"
)

@Serializable
data class HardConstraintDto(
    val start: String,
    val end: String,
    val title: String
)

@Serializable
data class PlanGenerateResponseDto(
    @SerialName("proposal_id") val proposalId: String,
    val tasks: List<PlanTaskDto> = emptyList(),
    @SerialName("suggestion_tasks") val suggestionTasks: List<PlanTaskDto> = emptyList(),
    @SerialName("ai_comment") val aiComment: String = ""
)

@Serializable
data class PlanTaskDto(
    val title: String,
    val note: String? = null,
    @SerialName("planned_start") val plannedStart: String? = null,
    @SerialName("planned_duration") val plannedDuration: Int? = null,
    val important: Boolean = false,
    @SerialName("task_type") val taskType: String? = null,
    @SerialName("time_of_day") val timeOfDay: String? = null,
    val source: String? = null
)

@Serializable
data class TaskInsertDto(
    @SerialName("user_id") val userId: String,
    val date: String,
    val title: String,
    val note: String? = null,
    @SerialName("planned_start") val plannedStart: String? = null,
    @SerialName("planned_duration") val plannedDuration: Int? = null,
    val important: Boolean = false,
    @SerialName("task_type") val taskType: String? = null,
    @SerialName("time_of_day") val timeOfDay: String? = null,
    val source: String,
    val status: String = "planned"
)

@Serializable
data class TaskSoftDeleteDto(
    @SerialName("deleted_at") val deletedAt: String
)
