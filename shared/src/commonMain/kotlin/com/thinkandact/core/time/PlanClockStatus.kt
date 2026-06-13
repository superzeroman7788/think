package com.thinkandact.core.time

import com.thinkandact.data.remote.TaskRowDto
import kotlinx.datetime.Instant

/**
 * C8-02: 全 App 统一的纯时钟制三态判定。
 *
 * - now < start → NotStarted
 * - start ≤ now < end → InProgress
 * - now ≥ end 且未完成 → Overdue
 *
 * 调用点:
 * - FullPlanScreen.rowState / remText
 * - ExecutionViewModel.pickCurrent / recomputeTide (间接 via planned times)
 * - HistoryScreen / ReviewScreen (如需状态标签时引用)
 */
enum class PlanClockPhase {
    NotStarted,
    InProgress,
    Overdue,
}

fun planClockPhase(
    nowSec: Long,
    startSec: Long?,
    endSec: Long?,
    terminal: Boolean,
): PlanClockPhase {
    if (terminal) return PlanClockPhase.Overdue
    if (startSec == null || endSec == null) return PlanClockPhase.NotStarted
    return when {
        nowSec < startSec -> PlanClockPhase.NotStarted
        nowSec < endSec -> PlanClockPhase.InProgress
        else -> PlanClockPhase.Overdue
    }
}

fun TaskRowDto.plannedStartSec(): Long? =
    plannedStart?.let { runCatching { Instant.parse(it).epochSeconds }.getOrNull() }

fun TaskRowDto.plannedEndSec(): Long? =
    plannedStartSec()?.let { it + (plannedDuration ?: if (isPoint) 0 else 30) * 60L }

fun TaskRowDto.isTerminalStatus(): Boolean =
    status == "done" || status == "skipped" || status == "dropped"

fun TaskRowDto.planClockPhase(nowSec: Long): PlanClockPhase =
    planClockPhase(nowSec, plannedStartSec(), plannedEndSec(), isTerminalStatus())

fun plannedStartEpoch(iso: String?): Long? =
    iso?.let { runCatching { Instant.parse(it).epochSeconds }.getOrNull() }

/** C8-01: 普通 block 提醒 — 按 planned_start 升序取第一条到点且仍 planned 的。 */
fun nextDueBlockReminder(
    tasks: List<TaskRowDto>,
    nowSec: Long,
    excludedIds: Set<String>,
): TaskRowDto? =
    tasks
        .asSequence()
        .filter { !it.important && !it.isPoint && it.status == "planned" && it.id !in excludedIds }
        .mapNotNull { t ->
            val start = t.plannedStartSec() ?: return@mapNotNull null
            if (start > nowSec) return@mapNotNull null
            t to start
        }
        .minByOrNull { it.second }
        ?.first

fun nextDuePoint(
    tasks: List<TaskRowDto>,
    nowSec: Long,
    excludedIds: Set<String>,
): TaskRowDto? {
    val due = tasks
        .asSequence()
        .filter { it.status == "planned" && it.id !in excludedIds }
        .mapNotNull { t ->
            val start = t.plannedStartSec() ?: return@mapNotNull null
            if (start > nowSec) return@mapNotNull null
            Triple(t, start, if (t.isPoint) 1 else 0)
        }
        .sortedWith(compareBy({ it.second }, { it.third }))
        .map { it.first }
        .firstOrNull()
    return due?.takeIf { it.isPoint }
}
