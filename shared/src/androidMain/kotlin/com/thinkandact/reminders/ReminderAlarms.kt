package com.thinkandact.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * ★ 提醒的 AlarmManager 落地——被 [AndroidReminderScheduler](App 内排程)与
 * [ReminderBootReceiver](重启后重排,第四批 N-01)共用,逻辑只此一份。
 * 同时负责提醒的 prefs 持久化(重启后闹钟被系统清空,从这里恢复)。
 */
object ReminderAlarms {

    private val json = Json { ignoreUnknownKeys = true }

    fun prefs(context: Context) = context.getSharedPreferences("tna_reminders", Context.MODE_PRIVATE)

    fun schedule(context: Context, r: TaskReminder) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntentFor(context, r.taskId, r.title, update = true)
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

    fun cancel(context: Context, taskId: String) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(pendingIntentFor(context, taskId, "", update = false))
    }

    fun pendingIntentFor(context: Context, taskId: String, title: String, update: Boolean): PendingIntent {
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

    // ── 持久化(N-01:重启后重排用)──────────────────────────────────────
    fun saveReminders(context: Context, reminders: List<TaskReminder>) {
        prefs(context).edit()
            .putString(KEY_REMINDERS_JSON, json.encodeToString(ListSerializer(TaskReminder.serializer()), reminders))
            .apply()
    }

    fun loadReminders(context: Context): List<TaskReminder> {
        val raw = prefs(context).getString(KEY_REMINDERS_JSON, null) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(TaskReminder.serializer()), raw) }
            .getOrDefault(emptyList())
    }

    fun ensureChannel(context: Context) {
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

    const val KEY_REMINDERS_JSON = "scheduled_reminders_json"
    const val ACTION_PREFIX = "com.thinkandact.REMINDER."
}
