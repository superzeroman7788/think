package com.thinkandact.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thinkandact.calendar.CalendarSyncManager
import com.thinkandact.data.PlanRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val calendarSyncEnabled: Boolean = false,
    val message: String? = null,
)

/** 设置页：本期只有「★ 任务同步系统日历」开关（默认关）。 */
class SettingsViewModel(
    private val calendarSyncManager: CalendarSyncManager,
    private val planRepository: PlanRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState(calendarSyncEnabled = calendarSyncManager.isEnabled()))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** 权限已授 → 真正开启 + 立刻把今天的 ★ 任务写进日历。 */
    fun enableCalendarSync() {
        calendarSyncManager.setEnabled(true)
        _uiState.update { it.copy(calendarSyncEnabled = true, message = "★ 任务会同步到系统日历了。") }
        viewModelScope.launch {
            runCatching { planRepository.fetchTodayTasks(includeSuggested = false) }
                .onSuccess { calendarSyncManager.syncIfEnabled(it) }
        }
    }

    /** 关闭 → 删掉本 App 建的全部日历事件。 */
    fun disableCalendarSync() {
        calendarSyncManager.setEnabled(false)
        _uiState.update { it.copy(calendarSyncEnabled = false, message = "已关闭,系统日历里的相关事件都清掉了。") }
    }

    /** 权限被拒：开关回弹,温和说明,不反复索要。 */
    fun onPermissionDenied() {
        _uiState.update { it.copy(calendarSyncEnabled = false, message = "没拿到日历权限,先不同步。想开可去系统设置里允许。") }
    }

    fun dismissMessage() { _uiState.update { it.copy(message = null) } }
}
