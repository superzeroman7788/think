package com.thinkandact.ui.common

import androidx.compose.runtime.Composable

/**
 * BUG-01：拦系统返回键 → 逐屏返回，而不是退 App。
 * Android: 走 activity-compose 的 BackHandler；iOS: 无系统返回键，空实现。
 */
@Composable
expect fun AppBackHandler(enabled: Boolean, onBack: () -> Unit)
