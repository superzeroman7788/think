package com.thinkandact.calendar

import android.content.Context
import androidx.core.content.edit

class AndroidSettingsStore(context: Context) : SettingsStore {
    private val prefs = context.getSharedPreferences("tna_settings", Context.MODE_PRIVATE)
    override var calendarSyncEnabled: Boolean
        get() = prefs.getBoolean("calendar_sync", false)
        set(value) { prefs.edit { putBoolean("calendar_sync", value) } }
}
