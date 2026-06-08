package com.thinkandact.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** ★ 任务系统提醒(Android):AlarmManager 精确闹钟 → [ReminderReceiver] 弹本地通知。 */
class AndroidReminderScheduler(private val context: Context) : ReminderScheduler {

    private val prefs = context.getSharedPreferences("tna_reminders", Context.MODE_PRIVATE)
    private val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    init { ensureChannel() }

    override fun sync(reminders: List<TaskReminder>) {
        // 先取消上次排过的全部,再排新的一组(计划一变就整组重排)。
        cancelAll()
        val ids = mutableSetOf<String>()
        reminders.forEach { r ->
            scheduleOne(r)
            ids += r.taskId
        }
        prefs.edit().putStringSet(KEY_SCHEDULED, ids).apply()
    }

    override fun cancelAll() {
        val ids = prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty()
        ids.forEach { id -> alarm.cancel(pendingIntentFor(id, "", update = false)) }
        prefs.edit().remove(KEY_SCHEDULED).apply()
    }

    private fun scheduleOne(r: TaskReminder) {
        val pi = pendingIntentFor(r.taskId, r.title, update = true)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
                // 没拿到精确闹钟权限 → 退而求其次用非精确(仍会响,只是不那么准)。
                alarm.set(AlarmManager.RTC_WAKEUP, r.triggerAtEpochMs, pi)
            } else {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerAtEpochMs, pi)
            }
        } catch (_: SecurityException) {
            alarm.set(AlarmManager.RTC_WAKEUP, r.triggerAtEpochMs, pi)
        }
    }

    private fun pendingIntentFor(taskId: String, title: String, update: Boolean): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = "$ACTION_PREFIX$taskId"
            putExtra(ReminderReceiver.EXTRA_TITLE, title)
            putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
        }
        val flags = (if (update) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or PendingIntent.FLAG_IMMUTABLE
        // 取消时用 FLAG_NO_CREATE 可能返回 null;给个回退保证能 cancel。
        return PendingIntent.getBroadcast(context, taskId.hashCode(), intent, flags)
            ?: PendingIntent.getBroadcast(context, taskId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(ReminderReceiver.CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(ReminderReceiver.CHANNEL_ID, "重要任务提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "★ 重要任务到点提醒"
                    },
                )
            }
        }
    }

    private companion object {
        const val KEY_SCHEDULED = "scheduled_ids"
        const val KEY_GUIDE_SHOWN = "bg_guide_shown"
        const val ACTION_PREFIX = "com.thinkandact.REMINDER."
    }
}
