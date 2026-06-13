package com.thinkandact.ui.inbox

import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/** 确认页 chip 文案:「交报告」是明天的,先放进收件箱了,到时早上提你。 */
fun deferredConfirmMessage(title: String, dueDate: String?, duePart: String?): String {
    val whenLabel = when {
        dueDate.isNullOrBlank() -> "改天"
        else -> {
            val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
            val d = runCatching { LocalDate.parse(dueDate) }.getOrNull()
            when (d) {
                null -> "改天"
                today -> "今天"
                today.plus(kotlinx.datetime.DatePeriod(days = 1)) -> "明天"
                today.plus(kotlinx.datetime.DatePeriod(days = 2)) -> "后天"
                else -> dueChipText(dueDate, duePart).substringBefore(" · ")
            }
        }
    }
    return "「$title」是${whenLabel}的,先放进收件箱了,到时早上提你。"
}
