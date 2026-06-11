package com.thinkandact.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thinkandact.data.AuthRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val phone: String = "",
    val code: String = "",
    val agreed: Boolean = false,
    val countdown: Int = 0,      // 获取验证码倒计时;0=可点。发送成功后才起跳。
    val isSendingCode: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val wechatNote: String? = null,
) {
    val phoneValid get() = phone.length == 11 && phone.all { it.isDigit() }
    val canSendCode get() = phoneValid && countdown == 0 && !isSendingCode
    val canLogin get() = phoneValid && code.isNotBlank() && agreed && !isLoading
    /**
     * F-10:灰按钮真禁用的口径(进入按钮 clickable=canLogin)。
     * 同时给「缺什么」的顺序提示(手机号 → 验证码 → 协议),让用户知道为何灰着。
     */
    val gateHint: String?
        get() = when {
            isLoading -> null
            !phoneValid -> "请输入 11 位手机号"
            code.isBlank() -> "请输入验证码"
            !agreed -> "请勾选并同意下方协议"
            else -> null
        }
}

class LoginViewModel(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun onPhone(v: String) { _uiState.update { it.copy(phone = v.filter { c -> c.isDigit() }.take(11), error = null) } }
    fun onCode(v: String) { _uiState.update { it.copy(code = v.filter { c -> c.isDigit() }.take(6), error = null) } }
    fun toggleAgree() { _uiState.update { it.copy(agreed = !it.agreed, error = null) } }
    fun dismissWechatNote() { _uiState.update { it.copy(wechatNote = null) } }

    /** 获取验证码(C-10)— 调 auth-sms-send；成功才起 60s 倒计时,失败显后端 reason、不倒计时。 */
    fun sendCode() {
        val phone = uiState.value.phone
        if (!uiState.value.canSendCode) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSendingCode = true, error = null) }
            runCatching { authRepository.sendSmsCode(phone) }
                .onSuccess {
                    _uiState.update { it.copy(isSendingCode = false, countdown = 60) }
                    while (uiState.value.countdown > 0) {
                        delay(1000)
                        _uiState.update { s -> s.copy(countdown = (s.countdown - 1).coerceAtLeast(0)) }
                    }
                }
                .onFailure { t ->
                    _uiState.update {
                        it.copy(
                            isSendingCode = false,
                            error = t.message?.takeIf { m -> m.isNotBlank() } ?: "验证码没发出去,过一下再试。",
                        )
                    }
                }
        }
    }

    fun onWechat() {
        // 微信登录占位:点了不报错、给轻提示。
        _uiState.update { it.copy(wechatNote = "微信登录还在路上,先用手机号进吧。") }
    }

    fun login(onSuccess: () -> Unit) {
        val s = uiState.value
        if (!s.canLogin) {
            if (!s.agreed) _uiState.update { it.copy(error = "先勾选下面的协议哦。") }
            return
        }
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            runCatching { authRepository.loginWithPhone(s.phone, s.code) }
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false) }
                    onSuccess()
                }
                .onFailure { t ->
                    _uiState.update { it.copy(isLoading = false, error = t.message?.takeIf { m -> m.isNotBlank() } ?: "进不去,过一下再试。") }
                }
        }
    }
}
