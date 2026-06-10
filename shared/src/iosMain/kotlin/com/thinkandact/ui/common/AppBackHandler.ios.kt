package com.thinkandact.ui.common

import androidx.compose.runtime.Composable

// iOS 无系统返回键（单视图）；屏内「←」已覆盖返回，这里空实现。
@Composable
actual fun AppBackHandler(enabled: Boolean, onBack: () -> Unit) {}
