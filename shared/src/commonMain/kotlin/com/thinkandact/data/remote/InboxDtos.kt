package com.thinkandact.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 随手记(收件箱)DTO。契约见《随手记 前/后端任务书》+ migration 0017 / _shared/inbox。 */

@Serializable
data class InboxStructuredCaptureDto(
    val title: String,
    @SerialName("due_date") val dueDate: String,
    @SerialName("due_part") val duePart: String? = null,
)

@Serializable
data class InboxCaptureRequest(
    @SerialName("raw_text") val rawText: String? = null,
    val structured: InboxStructuredCaptureDto? = null,
    @SerialName("client_local_date") val clientLocalDate: String,
    @SerialName("client_tz") val clientTz: String,
    val source: String = "voice",
)

@Serializable
data class InboxCaptureResponse(
    val id: String? = null,
    val title: String = "",
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("due_part") val duePart: String? = null,
    @SerialName("extract_failed") val extractFailed: Boolean = false,
    val provider: String? = null,
)

/** 列表条目（inbox-list / 直连 REST 共用）。 */
@Serializable
data class InboxItemDto(
    val id: String,
    val text: String = "",
    @SerialName("raw_text") val rawText: String? = null,
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("due_part") val duePart: String? = null,
    val status: String = "pending",
    val source: String? = null,
    @SerialName("added_task_id") val addedTaskId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("due_handled_at") val dueHandledAt: String? = null,
)

/** inbox-list 返回信封 `{ items: [...] }`。 */
@Serializable
data class InboxListResponse(val items: List<InboxItemDto> = emptyList())

/** inbox-badge 返回 `{ count }`。 */
@Serializable
data class InboxBadgeResponse(val count: Int = 0)

/** inbox-update 入参（add_today / dismiss / delete / set_due 复用）。 */
@Serializable
data class InboxUpdateRequest(
    val id: String,
    val action: String,
    @SerialName("client_local_date") val clientLocalDate: String? = null,
    @SerialName("client_tz") val clientTz: String? = null,
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("due_part") val duePart: String? = null,
)
