package com.thinkandact.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 第四批 N-01:重启后 AlarmManager 闹钟被系统清空 → 开机从 prefs 持久化的提醒
 * 重排**还没到点**的那些(过点的不补,避免开机弹一串旧提醒)。
 */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        ReminderAlarms.ensureChannel(context)
        val now = System.currentTimeMillis()
        ReminderAlarms.loadReminders(context)
            .filter { it.triggerAtEpochMs > now }
            .forEach { ReminderAlarms.schedule(context, it) }
    }
}
