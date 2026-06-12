package com.thinkandact.core.di

import com.thinkandact.data.session.IosSessionStore
import com.thinkandact.data.session.SessionStore
import com.thinkandact.reminders.IosReminderScheduler
import com.thinkandact.reminders.ReminderScheduler
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single<SessionStore> { IosSessionStore() }
    single<ReminderScheduler> { IosReminderScheduler() }
    single<com.thinkandact.calendar.SettingsStore> { com.thinkandact.calendar.IosSettingsStore() }
    single<com.thinkandact.calendar.CalendarSync> { com.thinkandact.calendar.IosCalendarSync() }
}
