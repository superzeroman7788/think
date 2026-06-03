package com.thinkandact.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RoutineDto(
    val id: String,
    @SerialName("user_id") val userId: String? = null,
    val title: String,
    val note: String? = null,
    val type: String? = null,
    @SerialName("default_time") val defaultTime: String,
    @SerialName("repeat_days") val repeatDays: List<Int>,
    val enabled: Boolean = true
)

@Serializable
data class RoutineInsertDto(
    @SerialName("user_id") val userId: String,
    val title: String,
    val note: String? = null,
    val type: String? = null,
    @SerialName("default_time") val defaultTime: String,
    @SerialName("repeat_days") val repeatDays: List<Int>,
    val enabled: Boolean = true
)

@Serializable
data class RoutineUpdateDto(
    val title: String,
    val note: String? = null,
    val type: String? = null,
    @SerialName("default_time") val defaultTime: String,
    @SerialName("repeat_days") val repeatDays: List<Int>,
    val enabled: Boolean
)

@Serializable
data class RoutineEnabledUpdateDto(
    val enabled: Boolean
)

@Serializable
data class RoutineSoftDeleteDto(
    @SerialName("deleted_at") val deletedAt: String
)
