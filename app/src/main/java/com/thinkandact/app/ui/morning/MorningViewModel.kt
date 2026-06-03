package com.thinkandact.app.ui.morning

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thinkandact.app.data.PlanRepository
import com.thinkandact.app.data.remote.PlanGenerateResponseDto
import com.thinkandact.app.data.remote.PlanTaskDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject

@HiltViewModel
class MorningViewModel @Inject constructor(
    private val planRepository: PlanRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(MorningUiState())
    val uiState: StateFlow<MorningUiState> = _uiState.asStateFlow()

    init {
        prepareSession()
    }

    fun onInputChange(value: String) {
        _uiState.update { it.copy(rawInput = value, errorMessage = null) }
    }

    fun generatePlan() {
        val input = uiState.value.rawInput.trim().decodeAdbEscapedInput()
        if (input.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching { planRepository.generatePlan(input) }
                .onSuccess { plan ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            proposal = plan,
                            editableTasks = plan.toEditableTasks(),
                            isConfirmed = false,
                            saveErrorMessage = null,
                            errorMessage = null
                        )
                    }
                }
                .onFailure { throwable ->
                    Log.e(TAG, "generatePlan failed", throwable)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = throwable.friendlyMessage()
                        )
                    }
                }
        }
    }

    fun retry() {
        generatePlan()
    }

    fun confirmPlan() {
        if (uiState.value.proposal == null) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSavingPlan = true,
                    saveErrorMessage = null
                )
            }
            runCatching { planRepository.confirmTodayTasks(uiState.value.editableTasks.map { it.task }) }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isSavingPlan = false,
                            isConfirmed = true,
                            saveErrorMessage = null
                        )
                    }
                }
                .onFailure { throwable ->
                    Log.e(TAG, "confirmPlan failed", throwable)
                    _uiState.update {
                        it.copy(
                            isSavingPlan = false,
                            saveErrorMessage = throwable.friendlyMessage(action = "save")
                        )
                    }
                }
        }
    }

    fun deleteTask(taskId: String) {
        _uiState.update { state ->
            state.copy(
                editableTasks = state.editableTasks.filterNot { it.id == taskId },
                saveErrorMessage = null
            )
        }
    }

    fun toggleImportant(taskId: String) {
        _uiState.update { state ->
            state.copy(
                editableTasks = state.editableTasks.map { item ->
                    if (item.id == taskId) {
                        item.copy(task = item.task.copy(important = !item.task.important))
                    } else {
                        item
                    }
                },
                saveErrorMessage = null
            )
        }
    }

    fun updateTaskTime(taskId: String, hour: Int, minute: Int) {
        _uiState.update { state ->
            state.copy(
                editableTasks = state.editableTasks.map { item ->
                    if (item.id == taskId) {
                        item.copy(task = item.task.copy(plannedStart = buildTodayIso(hour, minute)))
                    } else {
                        item
                    }
                },
                saveErrorMessage = null
            )
        }
    }

    fun addTask(title: String, hour: Int, minute: Int) {
        val cleanedTitle = title.trim()
        if (cleanedTitle.isEmpty()) return

        val plannedStart = buildTodayIso(hour, minute)
        val timeOfDay = inferTimeOfDay(hour)
        _uiState.update { state ->
            state.copy(
                editableTasks = state.editableTasks + EditablePlanTask(
                    id = "manual_${System.currentTimeMillis()}",
                    task = PlanTaskDto(
                        title = cleanedTitle,
                        plannedStart = plannedStart,
                        plannedDuration = 30,
                        important = false,
                        taskType = null,
                        timeOfDay = timeOfDay,
                        source = "user_text"
                    )
                ),
                saveErrorMessage = null
            )
        }
    }

    private fun prepareSession() {
        viewModelScope.launch {
            _uiState.update { it.copy(isPreparingSession = true, errorMessage = null) }
            runCatching { planRepository.ensureSession() }
                .onSuccess {
                    _uiState.update { it.copy(isPreparingSession = false, errorMessage = null) }
                }
                .onFailure { throwable ->
                    Log.e(TAG, "prepareSession failed", throwable)
                    _uiState.update {
                        it.copy(
                            isPreparingSession = false,
                            errorMessage = throwable.friendlyMessage()
                        )
                    }
                }
        }
    }

    private fun Throwable.friendlyMessage(action: String = "generate"): String {
        val raw = message.orEmpty()
        return when {
            raw.contains("Unable to resolve host", ignoreCase = true) -> "现在好像连不上网。等网络回来,我再帮你排今天。"
            raw.contains("timeout", ignoreCase = true) -> "这次等得有点久。可以再试一次。"
            raw.contains("FAIR_USE_EXCEEDED", ignoreCase = true) -> "今天的 AI 次数先用完了。"
            raw.contains("UNAUTHORIZED", ignoreCase = true) -> "登录状态有点卡住了,再试一次我会重新准备。"
            action == "save" -> "刚才保存今天计划时卡了一下。计划还在,可以再试一次。"
            else -> "刚才生成计划时卡了一下。再试一次就好。"
        }
    }

    private fun String.decodeAdbEscapedInput(): String {
        if (!contains("%")) return this
        return runCatching { URLDecoder.decode(this, StandardCharsets.UTF_8.name()) }
            .getOrDefault(this)
            .trim()
    }
}

data class MorningUiState(
    val rawInput: String = "",
    val isPreparingSession: Boolean = false,
    val isLoading: Boolean = false,
    val isSavingPlan: Boolean = false,
    val isConfirmed: Boolean = false,
    val errorMessage: String? = null,
    val saveErrorMessage: String? = null,
    val proposal: PlanGenerateResponseDto? = null,
    val editableTasks: List<EditablePlanTask> = emptyList()
)

data class EditablePlanTask(
    val id: String,
    val task: PlanTaskDto
)

private const val TAG = "MorningViewModel"

private fun PlanGenerateResponseDto.toEditableTasks(): List<EditablePlanTask> {
    return tasks.mapIndexed { index, task ->
        EditablePlanTask(id = "task_$index", task = task)
    } + suggestionTasks.mapIndexed { index, task ->
        EditablePlanTask(
            id = "suggestion_$index",
            task = task.copy(source = "ai_suggestion")
        )
    }
}

private fun buildTodayIso(hour: Int, minute: Int): String {
    return ZonedDateTime.of(
        LocalDate.now(),
        LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59)),
        ZoneId.systemDefault()
    ).toOffsetDateTime().toString()
}

private fun inferTimeOfDay(hour: Int): String {
    return when {
        hour < 12 -> "morning"
        hour < 14 -> "midday"
        hour < 18 -> "afternoon"
        else -> "evening"
    }
}
