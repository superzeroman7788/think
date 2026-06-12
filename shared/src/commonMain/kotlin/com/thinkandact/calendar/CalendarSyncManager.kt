package com.thinkandact.calendar

import com.thinkandact.data.remote.TaskRowDto

/** 设置开关 + 日历同步的编排。任务集变化处调 [syncIfEnabled]；设置页用 [setEnabled]。 */
class CalendarSyncManager(
    private val settings: SettingsStore,
    private val calendarSync: CalendarSync,
) {
    fun isEnabled(): Boolean = settings.calendarSyncEnabled
    fun hasPermission(): Boolean = calendarSync.hasPermission()

    /** 开关打开（调用方需已拿到权限）/关闭（关 → 清掉本 App 全部事件）。 */
    fun setEnabled(enabled: Boolean) {
        settings.calendarSyncEnabled = enabled
        if (!enabled) calendarSync.clearAll()
    }

    /** 任务集变化时对账：只在开启时同步 ★ 任务(含 ★ point)。 */
    fun syncIfEnabled(tasks: List<TaskRowDto>) {
        if (settings.calendarSyncEnabled && calendarSync.hasPermission()) {
            calendarSync.sync(tasksToCalendarEvents(tasks))
        }
    }
}
