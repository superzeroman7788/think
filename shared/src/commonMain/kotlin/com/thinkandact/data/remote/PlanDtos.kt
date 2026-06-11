package com.thinkandact.data.remote

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

/** 邮箱+密码登录/注册请求体（mock 手机号登录:用合成 email 当账号键）。 */
@Serializable
data class EmailAuthRequest(
    val email: String,
    val password: String,
)

@Serializable
data class SmsSendRequest(val phone: String)

@Serializable
data class SmsLoginRequest(
    val phone: String,
    val code: String,
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
    val source: String? = null,
    /** v1.4 软建议：plan-generate 的 suggestion_tasks 带 "suggested"；tasks 带 "planned"。落库原样写入。 */
    val status: String = "planned",
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

/** N-05 确认去重:只取标题的轻量行。 */
@Serializable
data class TitleOnlyDto(val title: String)

/**
 * 执行屏读取的当天任务（含 id 与执行态，块一）。
 * 复盘腿额外用到 [wasRescheduled] / [source] / [rescheduleCount]（可选，旧 select 不取时为默认值）。
 */
@Serializable
data class TaskRowDto(
    val id: String,
    val title: String,
    /** 历史视图按日期分组用（其它屏的 select 不取时为 null）。 */
    val date: String? = null,
    val note: String? = null,
    @SerialName("planned_start") val plannedStart: String? = null,
    @SerialName("planned_duration") val plannedDuration: Int? = null,
    val important: Boolean = false,
    val status: String = "planned",
    @SerialName("actual_start") val actualStart: String? = null,
    @SerialName("actual_end") val actualEnd: String? = null,
    @SerialName("task_type") val taskType: String? = null,
    @SerialName("time_of_day") val timeOfDay: String? = null,
    @SerialName("was_rescheduled") val wasRescheduled: Boolean = false,
    val source: String? = null,
    @SerialName("reschedule_count") val rescheduleCount: Int = 0,
)

/** 完成回写：status=done + 实际起止（块一，采结构化数据）。 */
@Serializable
data class TaskDoneUpdateDto(
    val status: String = "done",
    @SerialName("actual_start") val actualStart: String,
    @SerialName("actual_end") val actualEnd: String,
)

/** 仅改状态（dropped 等）。 */
@Serializable
data class TaskStatusUpdateDto(
    val status: String,
)

/**
 * 跳过：status=skipped 且 **清空 actual_start**。
 * 否则任务变「当前」时写入的 actual_start 会残留 → 看着像「开始过没结束」,污染学习数据。
 * 何时跳过看 updated_at。
 */
@Serializable
data class TaskSkipUpdateDto(
    val status: String = "skipped",
    @SerialName("actual_start") val actualStart: String? = null,
)

/** 变「当前」时写实际开始时刻（块一，仅库内为 null 时）。 */
@Serializable
data class TaskActualStartUpdateDto(
    @SerialName("actual_start") val actualStart: String,
)

/** 完整计划页「改时间」：只改 planned_start。 */
@Serializable
data class TaskPlannedStartUpdateDto(
    @SerialName("planned_start") val plannedStart: String,
)

// ── 块二 日内重排 /plan-revise（契约 v1.1）──────────────────────────────

@Serializable
data class PlanReviseRequest(
    val date: String,
    val timezone: String,
    val now: String,
    val instruction: String,
    val tasks: List<ReviseTaskDto>,
)

@Serializable
data class ReviseTaskDto(
    val id: String,
    val title: String,
    @SerialName("planned_start") val plannedStart: String? = null,
    @SerialName("planned_duration") val plannedDuration: Int? = null,
    val important: Boolean = false,
    val status: String = "planned",
    /** 必填键（可 null）：已开始任务 BE 不得 output moved。 */
    @SerialName("actual_start") val actualStart: String? = null,
)

@Serializable
data class AddedReviseDto(
    @SerialName("client_key") val clientKey: String,
    val title: String,
    @SerialName("planned_start") val plannedStart: String? = null,
    @SerialName("planned_duration") val plannedDuration: Int? = null,
    val important: Boolean = false,
)

@Serializable
data class PlanReviseResponse(
    @SerialName("revision_id") val revisionId: String,
    val provider: String? = null,
    val summary: String = "",
    val revisions: List<RevisionDto> = emptyList(),
    val added: List<AddedReviseDto> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** v1.3：true ⟺ 有 moved/dropped/added。BE 未升级时默认 true,FE 仍按本地是否有变更判定。 */
    val applicable: Boolean = true,
    /** v1.3：applicable=false 时后端必填的人话拒绝原因;FE 必须照实展示,**不得**用固定「没听出」覆盖。 */
    @SerialName("reject_reason") val rejectReason: String? = null,
)

@Serializable
data class RevisionDto(
    @SerialName("task_id") val taskId: String,
    val title: String = "",
    /** moved / dropped / unchanged */
    val change: String = "unchanged",
    val started: Boolean = false,
    val before: RevisionStateDto = RevisionStateDto(),
    val after: RevisionStateDto = RevisionStateDto(),
)

@Serializable
data class RevisionStateDto(
    @SerialName("planned_start") val plannedStart: String? = null,
    @SerialName("planned_duration") val plannedDuration: Int? = null,
    val status: String? = null,
    @SerialName("actual_start") val actualStart: String? = null,
)

@Serializable
data class PlanReviseApplyRequest(
    val date: String,
    @SerialName("revision_id") val revisionId: String,
    /** 仅 moved + dropped（unchanged 不传）。 */
    val revisions: List<ApplyRevisionDto> = emptyList(),
    val added: List<ApplyAddedReviseDto> = emptyList(),
)

@Serializable
data class ApplyAddedReviseDto(
    @SerialName("client_key") val clientKey: String,
    val title: String,
    @SerialName("planned_start") val plannedStart: String? = null,
    @SerialName("planned_duration") val plannedDuration: Int? = null,
    val important: Boolean = false,
)

@Serializable
data class ApplyRevisionDto(
    @SerialName("task_id") val taskId: String,
    val change: String,
    val after: ApplyAfterDto,
)

@Serializable
data class ApplyAfterDto(
    @SerialName("planned_start") val plannedStart: String? = null,
    @SerialName("planned_duration") val plannedDuration: Int? = null,
    val status: String? = null,
)

@Serializable
data class PlanReviseApplyResponse(
    val ok: Boolean = true,
    @SerialName("applied_count") val appliedCount: Int = 0,
    val tasks: List<TaskRowDto> = emptyList(),
)
