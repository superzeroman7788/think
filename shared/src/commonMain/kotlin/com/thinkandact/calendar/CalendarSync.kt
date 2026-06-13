package com.thinkandact.calendar

import com.thinkandact.data.remote.TaskRowDto
import kotlinx.datetime.Instant

/** 一条要写进系统日历的事件（★ 任务 / ★ 时刻点）。 */
data class CalendarEvent(
    val taskId: String,
    val title: String,
    val startMs: Long,
    val endMs: Long, // point: startMs+POINT_LEN；block: start+duration
    val isPoint: Boolean,
)

/**
 * 时刻点 §四：★ 任务（含 ★ point）同步到系统日历（仅 Android，iOS 空实现）。
 * 只动本 App 创建的事件；事件备注标来源「think & act」。
 */
interface CalendarSync {
    fun hasPermission(): Boolean

    /**
     * 把当前应有的 ★ 事件集与系统日历对账：
     * - 集合内 → upsert（已存在则更新时间/标题，否则新建）；
     * - 不在集合内但本 App 之前建过的（done/skip/删/改非★）→ 删除其事件。
     */
    fun sync(events: List<CalendarEvent>)

    /** 删除全部本 App 创建的日历事件（关开关时）。 */
    fun clearAll()
}

/** ★ planned 任务(block+point) → 日历事件。非★/非 planned 不进。 */
fun tasksToCalendarEvents(tasks: List<TaskRowDto>): List<CalendarEvent> {
    val pointLenMs = 15 * 60 * 1000L
    return tasks.mapNotNull { t ->
        if (!t.important || t.status != "planned") return@mapNotNull null
        val startMs = t.plannedStart?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() } ?: return@mapNotNull null
        val isPoint = t.kind == "point"
        val endMs = if (isPoint) startMs + pointLenMs else startMs + ((t.plannedDuration ?: 30).coerceAtLeast(1)) * 60_000L
        CalendarEvent(taskId = t.id, title = t.title, startMs = startMs, endMs = endMs, isPoint = isPoint)
    }
}
