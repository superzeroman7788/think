package com.thinkandact.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thinkandact.data.HistoryRepository
import com.thinkandact.data.remote.TaskRowDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn

/** 一天的历史数据（即使无任务也占一根空柱）。 */
data class HistoryDay(
    val date: String,
    val label: String,        // 周一…周日 / 今天
    val shortDate: String,    // M/D
    val isToday: Boolean,
    val tasks: List<TaskRowDto>,
    val done: Int,
    val owed: Int,            // done + planned（跳过/建议不计）
    val praise: String?,      // 当天 ai_praise
)

data class HistoryUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val days: List<HistoryDay> = emptyList(),
    val totalDone: Int = 0,   // 7 天 done 求和（只增不减、正向）
    val selectedDate: String? = null,  // 展开的那天
    val hasAnyData: Boolean = false,
) {
    val maxOwed get() = days.maxOfOrNull { it.owed } ?: 0
}

/** 历史视图「过去 7 天」——你这几天的样子,不是成绩单。 */
class HistoryViewModel(
    private val historyRepository: HistoryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                val tasks = historyRepository.fetchLast7DaysTasks()
                val refs = runCatching { historyRepository.fetchLast7DaysReflections() }.getOrNull().orEmpty()
                tasks to refs.associateBy { it.date }
            }.onSuccess { (tasks, refByDate) ->
                val tz = TimeZone.currentSystemDefault()
                val today = Clock.System.todayIn(tz)
                val byDate = tasks.groupBy { it.date }
                val days = (0..6).map { offset ->
                    val d = today.minus(DatePeriod(days = 6 - offset))
                    val ds = d.toString()
                    val dayTasks = byDate[ds].orEmpty()
                    val (done, owed) = HistoryRepository.historyDayStats(dayTasks)
                    val praise = refByDate[ds]?.aiPraise?.takeIf { s -> s.isNotBlank() }
                    HistoryDay(
                        date = ds,
                        label = if (d == today) "今天" else weekdayCn(d.dayOfWeek),
                        shortDate = "${d.monthNumber}/${d.dayOfMonth}",
                        isToday = d == today,
                        tasks = dayTasks,
                        done = done, owed = owed, praise = praise,
                    )
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        days = days,
                        totalDone = days.sumOf { day -> day.done },
                        selectedDate = days.lastOrNull { day -> day.owed > 0 }?.date ?: days.lastOrNull()?.date,
                        hasAnyData = tasks.isNotEmpty(),
                        errorMessage = null,
                    )
                }
            }.onFailure { t ->
                _uiState.update { it.copy(isLoading = false, errorMessage = t.message ?: "拉历史失败了。") }
            }
        }
    }

    fun selectDay(date: String) {
        _uiState.update { it.copy(selectedDate = if (it.selectedDate == date) null else date) }
    }

    private fun weekdayCn(dow: DayOfWeek): String = when (dow) {
        DayOfWeek.MONDAY -> "周一"; DayOfWeek.TUESDAY -> "周二"; DayOfWeek.WEDNESDAY -> "周三"
        DayOfWeek.THURSDAY -> "周四"; DayOfWeek.FRIDAY -> "周五"; DayOfWeek.SATURDAY -> "周六"
        else -> "周日"
    }
}
