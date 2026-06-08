package com.thinkandact.reminders

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter

/** ★ 任务系统提醒(iOS):UNUserNotificationCenter 本地通知。 */
class IosReminderScheduler : ReminderScheduler {
    private val center = UNUserNotificationCenter.currentNotificationCenter()

    override fun sync(reminders: List<TaskReminder>) {
        center.removeAllPendingNotificationRequests()
        val nowMs = (NSDate().timeIntervalSince1970() * 1000.0).toLong()
        reminders.forEach { r ->
            val secs = (r.triggerAtEpochMs - nowMs) / 1000.0
            if (secs <= 0.0) return@forEach
            val content = UNMutableNotificationContent().apply {
                setTitle("★ ${r.title}")
                setBody("到点了 · 现在做,还是先放一放?")
                setSound(UNNotificationSound.defaultSound)
            }
            val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(secs, repeats = false)
            val req = UNNotificationRequest.requestWithIdentifier(r.taskId, content, trigger)
            center.addNotificationRequest(req, withCompletionHandler = null)
        }
    }

    override fun cancelAll() { center.removeAllPendingNotificationRequests() }

    // iOS 无国内 OEM 杀后台问题。
    override fun shouldShowBackgroundGuide(): Boolean = false
    override fun markBackgroundGuideShown() {}
    override fun openBackgroundSettings() {}
}
