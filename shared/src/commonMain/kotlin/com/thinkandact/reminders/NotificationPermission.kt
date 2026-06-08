package com.thinkandact.reminders

import androidx.compose.runtime.Composable

/** 通知权限控制器。Compose 里用 [rememberNotificationPermission] 获取。 */
interface NotificationPermissionController {
    /** 触发系统授权(或读现状),返回是否已允许通知。 */
    suspend fun request(): Boolean
}

/**
 * 平台实现：
 * Android — POST_NOTIFICATIONS(API 33+)走 ActivityResult;<33 读 areNotificationsEnabled。
 * iOS — UNUserNotificationCenter.requestAuthorization(alert+sound)。
 */
@Composable
expect fun rememberNotificationPermission(): NotificationPermissionController
