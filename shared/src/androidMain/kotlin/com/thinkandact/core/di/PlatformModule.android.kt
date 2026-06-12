package com.thinkandact.core.di

import com.thinkandact.data.session.AndroidSessionStore
import com.thinkandact.data.session.SessionStore
import com.thinkandact.reminders.AndroidReminderScheduler
import com.thinkandact.reminders.ReminderScheduler
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single<SessionStore> { AndroidSessionStore(androidContext()) }
    single<ReminderScheduler> { AndroidReminderScheduler(androidContext()) }
    single<com.thinkandact.calendar.SettingsStore> { com.thinkandact.calendar.AndroidSettingsStore(androidContext()) }
    single<com.thinkandact.calendar.CalendarSync> { com.thinkandact.calendar.AndroidCalendarSync(androidContext()) }
}
