package com.thinkandact.data.remote

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

/**
 * 语音→日常 解析（BE：`/functions/v1/routine-parse`，待 Cursor 实现）。
 *
 * 请求：POST {SUPABASE_URL}/functions/v1/routine-parse
 *       Headers: apikey, Authorization: Bearer <jwt>
 *       Body:    { "text": "每天早上八点健身", "timezone": "Asia/Shanghai" }
 * 响应：200 { "routines": [ { title, default_time:"HH:MM", repeat_days:[0..6], note? } ] }
 *       一句话可能拆出多条（如「早上八点健身，晚上十点吃药」）。
 */
@Serializable
data class RoutineParseRequest(
    val text: String,
    val timezone: String,
)

@Serializable
data class RoutineParseResponse(
    val routines: List<RoutineDraftDto> = emptyList(),
)

@Serializable
data class RoutineDraftDto(
    val title: String,
    @SerialName("default_time") val defaultTime: String = "09:00",
    @SerialName("repeat_days") val repeatDays: List<Int> = listOf(1, 2, 3, 4, 5),
    val note: String? = null,
)

@Serializable
data class RoutineSoftDeleteDto(
    @SerialName("deleted_at") val deletedAt: String
)
