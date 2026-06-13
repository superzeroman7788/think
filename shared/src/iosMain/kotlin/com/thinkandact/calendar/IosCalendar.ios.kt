@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.thinkandact.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.EventKit.EKAlarm
import platform.EventKit.EKAuthorizationStatusFullAccess
import platform.EventKit.EKAuthorizationStatusWriteOnly
import platform.EventKit.EKEntityType
import platform.EventKit.EKEvent
import platform.EventKit.EKEventStore
import platform.EventKit.EKSpan
import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults
import platform.Foundation.dateWithTimeIntervalSince1970
import kotlin.coroutines.resume

/** 设置开关持久化在 NSUserDefaults。 */
class IosSettingsStore : SettingsStore {
    private val d = NSUserDefaults.standardUserDefaults
    override var calendarSyncEnabled: Boolean
        get() = d.boolForKey("calendar_sync")
        set(value) { d.setBool(value, "calendar_sync") }
}

// iOS17 起细分:FullAccess(读写)/WriteOnly(只写,也够写事件)。旧的 Authorized 已从新 SDK 移除。
private fun canWriteCalendar(): Boolean {
    val s = EKEventStore.authorizationStatusForEntityType(EKEntityType.EKEntityTypeEvent)
    return s == EKAuthorizationStatusFullAccess || s == EKAuthorizationStatusWriteOnly
}

/**
 * 时刻点 §四(iOS):★ 任务(含 ★ point)写进系统日历(EventKit)。
 * 只动**本 App 自己建的事件**(taskId→eventIdentifier 记在 NSUserDefaults，前缀 evt_)，事件备注标「think & act」。
 * 无权限/无默认日历则安静跳过。对账逻辑与 Android 对齐:删 done/skip/改非★，upsert 当前应有集。
 */
class IosCalendarSync : CalendarSync {
    private val store = EKEventStore()
    private val defaults = NSUserDefaults.standardUserDefaults
    private val marker = "think & act"

    override fun hasPermission(): Boolean = canWriteCalendar()

    override fun sync(events: List<CalendarEvent>) {
        if (!hasPermission()) return
        val cal = store.defaultCalendarForNewEvents ?: return
        val tracked = trackedMap()
        val wantIds = events.map { it.taskId }.toSet()

        // 删除:已不在应有集合里的(done/skip/删/改非★)。
        tracked.forEach { (taskId, eventId) ->
            if (taskId !in wantIds) {
                store.eventWithIdentifier(eventId)?.let { ev ->
                    runCatching { store.removeEvent(ev, EKSpan.EKSpanThisEvent, null) }
                }
                defaults.removeObjectForKey(K_PREFIX + taskId)
            }
        }

        // upsert
        events.forEach { e ->
            val existingId = tracked[e.taskId]
            val ev = existingId?.let { store.eventWithIdentifier(it) } ?: EKEvent.eventWithEventStore(store)
            ev.title = "★ ${e.title}"
            ev.startDate = NSDate.dateWithTimeIntervalSince1970(e.startMs / 1000.0)
            ev.endDate = NSDate.dateWithTimeIntervalSince1970(e.endMs / 1000.0)
            ev.notes = marker
            ev.calendar = cal
            if (existingId == null) ev.addAlarm(EKAlarm.alarmWithRelativeOffset(0.0)) // 一条到点提醒
            val ok = runCatching { store.saveEvent(ev, EKSpan.EKSpanThisEvent, null) }.getOrDefault(false)
            if (ok) ev.eventIdentifier?.let { defaults.setObject(it, K_PREFIX + e.taskId) }
        }
    }

    override fun clearAll() {
        trackedMap().forEach { (taskId, eventId) ->
            store.eventWithIdentifier(eventId)?.let { ev ->
                runCatching { store.removeEvent(ev, EKSpan.EKSpanThisEvent, null) }
            }
            defaults.removeObjectForKey(K_PREFIX + taskId)
        }
    }

    /** 扫 NSUserDefaults 里 evt_ 前缀的映射:taskId → eventIdentifier。 */
    private fun trackedMap(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        defaults.dictionaryRepresentation().keys.forEach { k ->
            val key = k as? String ?: return@forEach
            if (key.startsWith(K_PREFIX)) {
                defaults.stringForKey(key)?.let { out[key.removePrefix(K_PREFIX)] = it }
            }
        }
        return out
    }

    private companion object { const val K_PREFIX = "evt_" }
}

@Composable
actual fun rememberCalendarPermission(): CalendarPermissionController = remember {
    object : CalendarPermissionController {
        override suspend fun request(): Boolean {
            if (canWriteCalendar()) return true
            return suspendCancellableCoroutine { cont ->
                EKEventStore().requestAccessToEntityType(EKEntityType.EKEntityTypeEvent) { granted, _ ->
                    cont.resume(granted)
                }
            }
        }
    }
}
