package com.thinkandact.reminders

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/** 闹钟到点 → 弹 ★ 任务本地通知。 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "该做这件了" }
        val taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("★ $title")
            .setContentText("到点了 · 现在做,还是先放一放?")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(taskId.hashCode(), notif) }
    }

    companion object {
        const val CHANNEL_ID = "tna_star_reminders"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TASK_ID = "task_id"
    }
}
