package com.thinkandact.reminders

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

@Composable
actual fun rememberNotificationPermission(): NotificationPermissionController = remember {
    object : NotificationPermissionController {
        override suspend fun request(): Boolean = suspendCancellableCoroutine { cont ->
            val opts = UNAuthorizationOptionAlert or UNAuthorizationOptionSound
            UNUserNotificationCenter.currentNotificationCenter()
                .requestAuthorizationWithOptions(opts) { granted, _ -> cont.resume(granted) }
        }
    }
}
