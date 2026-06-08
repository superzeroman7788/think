package com.thinkandact.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ════════════════════════════════════════════════════════════════════════
//  晚上「复盘」腿 DTO（契约 v1.0 已锁：docs/BE_evening-review_接口契约.md）
//  风格对齐 PlanRevise*；命名见契约 §22「DTO 命名预告」。
// ════════════════════════════════════════════════════════════════════════

// ── §5.2 当天复盘记录（回读 summary/probe/response）──────────────────────
@Serializable
data class DailyReflectionDto(
    val date: String? = null,
    @SerialName("ai_summary") val aiSummary: String? = null,
    /** v2「今天的提醒」上块·夸+收尾（持久化列）。 */
    @SerialName("ai_praise") val aiPraise: String? = null,
    /** v2「今天的提醒」下块·给明天的温柔建议（持久化列）。 */
    @SerialName("ai_advice") val aiAdvice: String? = null,
    @SerialName("ai_probe") val aiProbe: String? = null,
    @SerialName("user_response") val userResponse: String? = null,
    @SerialName("promoted_memory") val promotedMemory: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
)

// ── §6 语音批量任务负载（parse 用最小子集；契约 §6.1）──────────────────
@Serializable
data class ReviewTaskDto(
    val id: String,
    val title: String,
    val status: String = "planned",
    @SerialName("planned_start") val plannedStart: String? = null,
    @SerialName("actual_start") val actualStart: String? = null,
)

/** day-summary / reflection-probe 用的全量任务负载（契约 §5.1 同构）。 */
@Serializable
data class ReviewTaskFullDto(
    val id: String,
    val title: String,
    @SerialName("planned_start") val plannedStart: String? = null,
    @SerialName("planned_duration") val plannedDuration: Int? = null,
    val status: String = "planned",
    @SerialName("actual_start") val actualStart: String? = null,
    @SerialName("actual_end") val actualEnd: String? = null,
    val important: Boolean = false,
    @SerialName("was_rescheduled") val wasRescheduled: Boolean = false,
    val source: String? = null,
    @SerialName("task_type") val taskType: String? = null,
    @SerialName("time_of_day") val timeOfDay: String? = null,
)

// ── §6.1 review-parse-voice（propose）────────────────────────────────────
@Serializable
data class ReviewParseRequest(
    val date: String,
    val timezone: String,
    val transcript: String,
    val tasks: List<ReviewTaskDto>,
)

@Serializable
data class ReviewParseResponse(
    @SerialName("review_id") val reviewId: String,
    val provider: String? = null,
    val summary: String = "",
    val proposed: List<ReviewProposalDto> = emptyList(),
    val added: List<ReviewAddedDto> = emptyList(),
    val warnings: List<String> = emptyList(),
    val unclear: Boolean = false,
)

/** 既有任务的状态拟议（仅 done | skipped）。 */
@Serializable
data class ReviewProposalDto(
    @SerialName("task_id") val taskId: String,
    val title: String = "",
    @SerialName("from_status") val fromStatus: String = "",
    @SerialName("to_status") val toStatus: String = "",
)

/** v1.1：补记的计划外活动（source=review_voice, status=done）。 */
@Serializable
data class ReviewAddedDto(
    @SerialName("client_key") val clientKey: String,
    val title: String,
    @SerialName("planned_start") val plannedStart: String? = null,
    @SerialName("planned_duration") val plannedDuration: Int? = null,
    @SerialName("to_status") val toStatus: String = "done",
)

// ── §6.2 review-apply（原子）─────────────────────────────────────────────
@Serializable
data class ReviewApplyRequest(
    val date: String,
    @SerialName("review_id") val reviewId: String,
    /** 既有任务状态变更（必传，无则 []）。 */
    val proposed: List<ReviewApplyProposalDto> = emptyList(),
    /** 计划外补记（必传，无则 []）。 */
    val added: List<ReviewAddedDto> = emptyList(),
)

@Serializable
data class ReviewApplyProposalDto(
    @SerialName("task_id") val taskId: String,
    @SerialName("to_status") val toStatus: String,
)

@Serializable
data class ReviewApplyResponse(
    val ok: Boolean = true,
    @SerialName("applied_count") val appliedCount: Int = 0,
    val tasks: List<TaskRowDto> = emptyList(),
)

// ── §7 day-summary ──────────────────────────────────────────────────────
@Serializable
data class DaySummaryRequest(
    val date: String,
    val tasks: List<ReviewTaskFullDto>,
)

@Serializable
data class DaySummaryResponse(
    val provider: String? = null,
    val summary: String = "",
    /** v2「今天的提醒」上块·夸+收尾（搭子语气,只夸真做成的；BE 未升级时为 null,FE 回退用 [summary]）。 */
    val praise: String? = null,
    /** v2「今天的提醒」下块·给明天的温柔建议（问句不命令；BE 未升级时为 null,FE 隐藏该块）。 */
    val advice: String? = null,
)

// ── §8 reflection-probe ─────────────────────────────────────────────────
@Serializable
data class ReflectionProbeRequest(
    val date: String,
    @SerialName("today_tasks") val todayTasks: List<ReviewTaskFullDto>,
    @SerialName("recent_tasks") val recentTasks: List<RecentTaskDto> = emptyList(),
)

@Serializable
data class RecentTaskDto(
    val date: String,
    val title: String,
    val status: String,
    @SerialName("task_type") val taskType: String? = null,
    @SerialName("was_rescheduled") val wasRescheduled: Boolean = false,
)

@Serializable
data class ReflectionProbeResponse(
    val provider: String? = null,
    val probe: String = "",
    @SerialName("candidate_memory") val candidateMemory: String? = null,
    @SerialName("pattern_hint") val patternHint: String? = null,
)

// ── §9 memory-add（确认才存）────────────────────────────────────────────
@Serializable
data class MemoryAddRequest(
    val date: String,
    val text: String,
    @SerialName("user_response") val userResponse: String,
    val source: String = "reflection",
    /** 必须 true，否则后端 400（红线：无静默写记忆）。 */
    @SerialName("confirmed_by_user") val confirmedByUser: Boolean = true,
)

@Serializable
data class MemoryAddResponse(
    val id: String,
    val text: String = "",
    val source: String? = null,
    @SerialName("confirmed_by_user") val confirmedByUser: Boolean = true,
    @SerialName("added_at") val addedAt: String? = null,
    val reflection: MemoryReflectionDto? = null,
)

@Serializable
data class MemoryReflectionDto(
    val date: String? = null,
    @SerialName("user_response") val userResponse: String? = null,
    @SerialName("promoted_memory") val promotedMemory: String? = null,
)

// ── §10 记忆 list（"关于你"）─────────────────────────────────────────────
@Serializable
data class MemoryRowDto(
    val id: String,
    val text: String = "",
    val source: String? = null,
    @SerialName("added_at") val addedAt: String? = null,
)

@Serializable
data class MemorySoftDeleteDto(
    @SerialName("deleted_at") val deletedAt: String,
)

/**
 * 复盘点选「补记做了」：`status=done` + `actual_end`，**不写 actual_start**（§8#2，
 * 与执行屏当场完成带 actual_start 区分）。
 */
@Serializable
data class ReviewDoneUpdateDto(
    val status: String = "done",
    @SerialName("actual_end") val actualEnd: String,
)
