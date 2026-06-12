package com.thinkandact.ui

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 跨层导航信号(平台壳 → Compose 树)。
 * 第四批 N-03:点 ★ 提醒通知 → MainActivity 收 intent extra → 置位 → App() 跳执行屏并复位。
 */
object NavSignals {
    val openExecution = MutableStateFlow(false)
    /** 随手记快捷方式/widget:打开收件箱页。 */
    val openInbox = MutableStateFlow(false)
    /** 随手记快捷方式「记一笔」:进收件箱并直接弹捕捉面板。 */
    val openCapture = MutableStateFlow(false)

    // iOS 壳(AppDelegate Quick Actions)→ Kotlin 的入口:Swift 不便直接写 StateFlow.value,给它现成函数。
    fun signalOpenExecution() { openExecution.value = true }
    fun signalOpenInbox() { openInbox.value = true }
    fun signalOpenCapture() { openCapture.value = true }
}
