package com.thinkandact.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import java.util.TimeZone

/**
 * 时刻点 §四：把 ★ 任务写进系统日历。
 * 只动**本 App 自己建的事件**(eventId 记在 tna_calendar prefs，taskId→eventId)，
 * 事件备注标「think & act」。无权限/无可写日历则安静跳过。
 */
class AndroidCalendarSync(private val context: Context) : CalendarSync {
    private val prefs = context.getSharedPreferences("tna_calendar", Context.MODE_PRIVATE)
    private val resolver get() = context.contentResolver
    private val marker = "think & act"

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun writableCalendarId(): Long? {
        if (!hasPermission()) return null
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        return runCatching {
            resolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { c ->
                var fallback: Long? = null
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val primary = c.getInt(1)
                    val access = c.getInt(2)
                    if (primary == 1) return@use id
                    if (access >= CalendarContract.Calendars.CAL_ACCESS_OWNER && fallback == null) fallback = id
                    if (fallback == null) fallback = id
                }
                fallback
            }
        }.getOrNull()
    }

    override fun sync(events: List<CalendarEvent>) {
        if (!hasPermission()) return
        val calId = writableCalendarId() ?: return
        val tz = TimeZone.getDefault().id
        val tracked = prefs.all.keys.filter { it.startsWith(K_PREFIX) }.associate { it.removePrefix(K_PREFIX) to prefs.getLong(it, -1L) }
        val wantIds = events.map { it.taskId }.toSet()

        // 删除：已不在应有集合里的(done/skip/删/改非★)。
        tracked.forEach { (taskId, eventId) ->
            if (taskId !in wantIds && eventId > 0) {
                runCatching { resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null) }
                prefs.edit { remove(K_PREFIX + taskId) }
            }
        }

        // upsert
        events.forEach { e ->
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, "★ ${e.title}")
                put(CalendarContract.Events.DTSTART, e.startMs)
                put(CalendarContract.Events.DTEND, e.endMs)
                put(CalendarContract.Events.EVENT_TIMEZONE, tz)
                put(CalendarContract.Events.DESCRIPTION, marker)
            }
            val existing = tracked[e.taskId]?.takeIf { it > 0 }
            if (existing != null) {
                runCatching { resolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existing), values, null, null) }
            } else {
                val uri = runCatching { resolver.insert(CalendarContract.Events.CONTENT_URI, values) }.getOrNull()
                val eventId = uri?.lastPathSegment?.toLongOrNull()
                if (eventId != null) {
                    prefs.edit { putLong(K_PREFIX + e.taskId, eventId) }
                    // 一条到点提醒
                    runCatching {
                        resolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                            put(CalendarContract.Reminders.EVENT_ID, eventId)
                            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                            put(CalendarContract.Reminders.MINUTES, 0)
                        })
                    }
                }
            }
        }
    }

    override fun clearAll() {
        if (!hasPermission()) { prefs.edit { clear() }; return }
        prefs.all.keys.filter { it.startsWith(K_PREFIX) }.forEach { key ->
            val eventId = prefs.getLong(key, -1L)
            if (eventId > 0) runCatching { resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null) }
        }
        prefs.edit { clear() }
    }

    private companion object { const val K_PREFIX = "evt_" }
}
