package com.thinkandact.reminders

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import androidx.core.app.NotificationCompat

/** 闹钟到点 → 弹 ★ 任务本地通知。 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 重启后闹钟可能先于 App 启动到点(N-01)→ 渠道可能还没建,通知前确保有。
        ReminderAlarms.ensureChannel(context)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "该做这件了" }
        val taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        // N-03:点通知 → 打开 App 并直达执行屏(当前任务)。
        val contentIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            putExtra(EXTRA_OPEN_EXECUTION, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }?.let {
            PendingIntent.getActivity(
                context, taskId.hashCode(), it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("★ $title")
            .setContentText("到点了 · 现在做,还是先放一放?")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(taskId.hashCode(), notif) }
    }

    companion object {
        const val CHANNEL_ID = "tna_star_reminders"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_OPEN_EXECUTION = "open_execution"
    }
}
