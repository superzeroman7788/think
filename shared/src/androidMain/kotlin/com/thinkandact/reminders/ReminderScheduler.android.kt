package com.thinkandact.reminders

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * ★ 任务系统提醒(Android):AlarmManager 精确闹钟 → [ReminderReceiver] 弹本地通知。
 * 闹钟落地与持久化在 [ReminderAlarms](与重启重排的 BootReceiver 共用,N-01)。
 */
class AndroidReminderScheduler(private val context: Context) : ReminderScheduler {

    private val prefs = ReminderAlarms.prefs(context)

    init { ReminderAlarms.ensureChannel(context) }

    override fun sync(reminders: List<TaskReminder>) {
        // 先取消上次排过的全部,再排新的一组(计划一变就整组重排)。
        cancelAll()
        reminders.forEach { ReminderAlarms.schedule(context, it) }
        // N-01:持久化整组,重启后 BootReceiver 据此重排当天剩余的。
        ReminderAlarms.saveReminders(context, reminders)
    }

    override fun cancelAll() {
        ReminderAlarms.loadReminders(context).forEach { ReminderAlarms.cancel(context, it.taskId) }
        ReminderAlarms.saveReminders(context, emptyList())
    }

    override fun shouldShowBackgroundGuide(): Boolean = !prefs.getBoolean(KEY_GUIDE_SHOWN, false)
    override fun markBackgroundGuideShown() { prefs.edit().putBoolean(KEY_GUIDE_SHOWN, true).apply() }

    override fun openBackgroundSettings() {
        // 引导到本应用的系统设置页(用户在此开自启动/后台/电池不优化)。
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    override fun openNotificationSettings() {
        // F-13:直跳本应用的通知设置页;失败回退到应用详情页。
        val notifIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val ok = runCatching { context.startActivity(notifIntent); true }.getOrDefault(false)
        if (!ok) openBackgroundSettings()
    }

    private companion object {
        const val KEY_GUIDE_SHOWN = "bg_guide_shown"
    }
}
