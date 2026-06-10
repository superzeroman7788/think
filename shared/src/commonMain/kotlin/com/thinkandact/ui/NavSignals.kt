package com.thinkandact.ui

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 跨层导航信号(平台壳 → Compose 树)。
 * 第四批 N-03:点 ★ 提醒通知 → MainActivity 收 intent extra → 置位 → App() 跳执行屏并复位。
 */
object NavSignals {
    val openExecution = MutableStateFlow(false)
}
