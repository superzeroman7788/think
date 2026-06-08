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
    val countdown: Int = 0,      // 获取验证码倒计时(cosmetic);0=可点
    val isLoading: Boolean = false,
    val error: String? = null,
    val wechatNote: String? = null,
) {
    val phoneValid get() = phone.length == 11 && phone.all { it.isDigit() }
    val canSendCode get() = phoneValid && countdown == 0
    val canLogin get() = phoneValid && code.isNotBlank() && agreed && !isLoading
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

    /** 获取验证码 = 纯倒计时(测试期任意码可进,不发真短信)。 */
    fun sendCode() {
        if (!uiState.value.canSendCode) return
        _uiState.update { it.copy(countdown = 60) }
        viewModelScope.launch {
            while (uiState.value.countdown > 0) {
                delay(1000)
                _uiState.update { it.copy(countdown = (it.countdown - 1).coerceAtLeast(0)) }
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
