package com.thinkandact.calendar

/** 轻量本地设置（持久化）。本期只有「★ 任务同步系统日历」开关。 */
interface SettingsStore {
    var calendarSyncEnabled: Boolean
}
