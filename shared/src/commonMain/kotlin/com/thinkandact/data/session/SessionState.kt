package com.thinkandact.data.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局登录态（响应式）。App 观察它决定显示登录页还是主界面。
 *
 * BUG-03：会话刷新失败时**清会话并把它置 false → 路由回登录页**，
 * 绝不静默建匿名号（那会让用户"已登录"却看到空数据，像掉号/数据消失）。
 */
class SessionState(sessionStore: SessionStore) {
    private val _loggedIn = MutableStateFlow(sessionStore.load() != null)
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    fun onLoggedIn() { _loggedIn.value = true }
    fun onLoggedOut() { _loggedIn.value = false }
}

/** 会话过期且无法刷新（BUG-03）：上层据此回登录页，不再静默匿名。 */
class SessionExpiredException(message: String = "会话已过期,请重新登录") : Exception(message)
