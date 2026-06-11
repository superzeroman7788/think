package com.thinkandact.reminders

import com.thinkandact.data.remote.TaskRowDto
import kotlinx.datetime.Instant

/** 一条 ★ 任务的本地提醒。可序列化:Android 持久化到 prefs,重启后 BootReceiver 重排(第四批 N-01)。 */
@kotlinx.serialization.Serializable
data class TaskReminder(
    val taskId: String,
    val title: String,
    val triggerAtEpochMs: Long,
)

/**
 * ★ 任务系统提醒(本地定时通知)。发朋友版:本地通知 + 白名单引导,不接厂商通道。
 *
 * 平台实现:Android = AlarmManager exact + 通知;iOS = UNUserNotificationCenter。
 * 通过 platformModule 注入(同 SessionStore)。
 */
interface ReminderScheduler {
    /** 取消所有旧的、按给定列表重排(★ 未来任务)。计划一变就整组重排。 */
    fun sync(reminders: List<TaskReminder>)

    /** 全部取消。 */
    fun cancelAll()

    /** 国内 OEM 杀后台 → 首次引导一次白名单(自启动/后台/不优化电池)。iOS 恒 false。 */
    fun shouldShowBackgroundGuide(): Boolean
    fun markBackgroundGuideShown()

    /** 打开系统设置页引导白名单(Android 跳电池优化设置;iOS no-op)。 */
    fun openBackgroundSettings()

    /** F-13:直接打开本应用的**系统通知设置页**(Android ACTION_APP_NOTIFICATION_SETTINGS;iOS no-op)。 */
    fun openNotificationSettings()
}

/**
 * 纯逻辑:从今天任务挑出该推送的 ★ 提醒。
 * 规则:**仅 ★(important) + status=planned(已接受真任务、未完成) + 计划时刻在未来**。
 * 软建议(suggested)/done/skipped/dropped/无时间 → **永不推**。
 */
object ReminderPlanner {
    fun fromTasks(tasks: List<TaskRowDto>, nowMs: Long): List<TaskReminder> =
        tasks.mapNotNull { t ->
            if (!t.important) return@mapNotNull null
            if (t.status != "planned") return@mapNotNull null
            val start = t.plannedStart
                ?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
                ?: return@mapNotNull null
            if (start <= nowMs) return@mapNotNull null
            TaskReminder(taskId = t.id, title = t.title, triggerAtEpochMs = start)
        }
}
