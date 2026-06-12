package com.thinkandact.core.time

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetIn
import kotlinx.datetime.todayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs

/**
 * BUG-12：全应用**统一**的计划时间持久化格式 —— 当天本地时刻 + 时区偏移，
 * 如 `2026-06-09T15:00:00+08:00`（与 plan-generate 一致）。
 *
 * 之前早上屏写本地 `+08:00`、完整计划页写 UTC `…Z`，两种格式并存，BUG-04 改时钟制后
 * 解析会埋雷。所有"按当天 hour:minute 落库"的地方都走这一个函数。
 */
fun todayLocalIso(hour: Int, minute: Int): String {
    val tz = TimeZone.currentSystemDefault()
    val today = Clock.System.todayIn(tz)
    val h = hour.coerceIn(0, 23)
    val m = minute.coerceIn(0, 59)
    val offset = LocalDateTime(today, LocalTime(h, m)).toInstant(tz).offsetIn(tz)
    val sign = if (offset.totalSeconds >= 0) '+' else '-'
    val a = abs(offset.totalSeconds)
    val oh = (a / 3600).toString().padStart(2, '0')
    val om = ((a % 3600) / 60).toString().padStart(2, '0')
    return "${today}T${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:00$sign$oh:$om"
}

// ── 第七批 F7-01/02：全局统一的时间展示。所有屏共用这套,别再各写各的 ──────────

/** ISO（`…T15:00:00+08:00`）或裸 `HH:mm` → `"15:00"`；无值 → `""`。 */
fun formatClock(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val raw = iso.trim()
    if (raw.matches(Regex("^\\d{2}:\\d{2}(:\\d{2})?$"))) return raw.take(5)
    return runCatching {
        val dt = Instant.parse(raw).toLocalDateTime(TimeZone.currentSystemDefault())
        dt.hour.toString().padStart(2, '0') + ":" + dt.minute.toString().padStart(2, '0')
    }.getOrElse { raw.substringAfter("T", "").take(5) }
}

/** 时长：≥60 分钟显「X小时XX分」，否则「X分钟」。全局一致(F7-02 验收)。 */
fun formatDuration(minutes: Int): String {
    if (minutes <= 0) return ""
    if (minutes < 60) return "${minutes}分钟"
    val h = minutes / 60
    val m = minutes % 60
    return if (m > 0) "${h}小时${m}分" else "${h}小时"
}

/**
 * 任务时间列(F7-02)：block 显起–止「11:00–16:00」；point 只显时刻「15:00」；无起点 → ""。
 * isPoint 由调用方给(TaskRowDto 用 kind=="point"；diff 状态无 kind,用 duration==0 推断)。
 */
fun formatTimeRange(plannedStart: String?, plannedDurationMin: Int?, isPoint: Boolean): String {
    val start = formatClock(plannedStart)
    if (start.isEmpty()) return ""
    if (isPoint) return start
    val dur = plannedDurationMin ?: 0
    if (dur <= 0) return start
    val endSec = runCatching { Instant.parse(plannedStart!!.trim()).epochSeconds + dur * 60L }.getOrNull() ?: return start
    val end = Instant.fromEpochSeconds(endSec).toLocalDateTime(TimeZone.currentSystemDefault())
    return "$start–${end.hour.toString().padStart(2, '0')}:${end.minute.toString().padStart(2, '0')}"
}

/**
 * 变更预览 diff 文案(F7-01,定死格式)。change=moved/dropped；point 由 duration==0 推断。
 * moved/block → 两行「原  …」「新  …」(+ 时长变化第三行)；moved/point → 「原 15:00 / 新 16:00」。
 */
fun reviseDiffDetail(
    change: String,
    beforeStart: String?,
    beforeDur: Int?,
    afterStart: String?,
    afterDur: Int?,
): String {
    val isPoint = afterDur == 0 || beforeDur == 0
    val beforeRange = formatTimeRange(beforeStart, beforeDur, isPoint)
    return when (change) {
        "dropped" -> if (beforeRange.isNotEmpty()) "原 $beforeRange → 不做了" else "不做了"
        "moved" -> {
            if (isPoint) {
                "原 ${formatClock(beforeStart)} / 新 ${formatClock(afterStart)}"
            } else {
                val afterRange = formatTimeRange(afterStart, afterDur, false)
                val lines = mutableListOf("原  $beforeRange", "新  $afterRange")
                val bd = beforeDur ?: 0
                val ad = afterDur ?: 0
                if (bd > 0 && ad > 0 && bd != ad) lines.add("时长 ${formatDuration(bd)} → ${formatDuration(ad)}")
                lines.joinToString("\n")
            }
        }
        else -> ""
    }
}

/** 新增项 diff 文案(F7-01)：「新 11:00–16:00」/ point「新 15:00」。 */
fun addedDiffDetail(plannedStart: String?, plannedDurationMin: Int?, isPoint: Boolean): String {
    val r = formatTimeRange(plannedStart, plannedDurationMin, isPoint)
    return if (r.isNotEmpty()) "新 $r" else "新增到今天"
}
