package com.thinkandact.core.time

import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetIn
import kotlinx.datetime.todayIn
import kotlinx.datetime.toInstant
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
