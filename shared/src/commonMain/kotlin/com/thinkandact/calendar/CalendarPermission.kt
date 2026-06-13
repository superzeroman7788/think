package com.thinkandact.calendar

import androidx.compose.runtime.Composable

/** 系统日历写权限（Android WRITE_CALENDAR）。iOS 本期不做日历。 */
interface CalendarPermissionController {
    suspend fun request(): Boolean
}

@Composable
expect fun rememberCalendarPermission(): CalendarPermissionController
