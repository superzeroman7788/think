package com.thinkandact.app.ui.routine

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thinkandact.app.data.RoutineRepository
import com.thinkandact.app.data.remote.RoutineDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class RoutineViewModel @Inject constructor(
    private val routineRepository: RoutineRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(RoutineUiState())
    val uiState: StateFlow<RoutineUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching { routineRepository.listRoutines() }
                .onSuccess { routines ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            routines = routines,
                            errorMessage = null
                        )
                    }
                }
                .onFailure { throwable ->
                    Log.e(TAG, "list routines failed", throwable)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = throwable.friendlyMessage()
                        )
                    }
                }
        }
    }

    fun saveRoutine(form: RoutineFormState) {
        val cleaned = form.cleaned() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            val result = if (cleaned.id == null) {
                runCatching {
                    routineRepository.createRoutine(
                        title = cleaned.title,
                        note = cleaned.note,
                        type = cleaned.type,
                        defaultTime = cleaned.defaultTime,
                        repeatDays = cleaned.repeatDays
                    )
                    routineRepository.listRoutines()
                }
            } else {
                runCatching {
                    routineRepository.updateRoutine(
                        id = cleaned.id,
                        title = cleaned.title,
                        note = cleaned.note,
                        type = cleaned.type,
                        defaultTime = cleaned.defaultTime,
                        repeatDays = cleaned.repeatDays,
                        enabled = cleaned.enabled
                    )
                    routineRepository.listRoutines()
                }
            }

            result
                .onSuccess { refreshedRoutines ->
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            routines = refreshedRoutines,
                            errorMessage = null
                        )
                    }
                }
                .onFailure { throwable ->
                    Log.e(TAG, "save routine failed", throwable)
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            errorMessage = throwable.friendlyMessage(action = "save")
                        )
                    }
                }
        }
    }

    fun toggleEnabled(routine: RoutineDto) {
        viewModelScope.launch {
            _uiState.update { it.copy(pendingRoutineId = routine.id, errorMessage = null) }
            runCatching {
                routineRepository.setEnabled(routine.id, !routine.enabled)
                routineRepository.listRoutines()
            }
                .onSuccess { refreshedRoutines ->
                    _uiState.update {
                        it.copy(
                            pendingRoutineId = null,
                            routines = refreshedRoutines,
                            errorMessage = null
                        )
                    }
                }
                .onFailure { throwable ->
                    Log.e(TAG, "toggle routine failed", throwable)
                    _uiState.update {
                        it.copy(
                            pendingRoutineId = null,
                            errorMessage = throwable.friendlyMessage(action = "save")
                        )
                    }
                }
        }
    }

    fun deleteRoutine(routine: RoutineDto) {
        viewModelScope.launch {
            _uiState.update { it.copy(pendingRoutineId = routine.id, errorMessage = null) }
            runCatching {
                routineRepository.deleteRoutine(routine.id)
                routineRepository.listRoutines()
            }
                .onSuccess { refreshedRoutines ->
                    _uiState.update {
                        it.copy(
                            pendingRoutineId = null,
                            routines = refreshedRoutines,
                            errorMessage = null
                        )
                    }
                }
                .onFailure { throwable ->
                    Log.e(TAG, "delete routine failed", throwable)
                    _uiState.update {
                        it.copy(
                            pendingRoutineId = null,
                            errorMessage = throwable.friendlyMessage(action = "delete")
                        )
                    }
                }
        }
    }

    private fun Throwable.friendlyMessage(action: String = "load"): String {
        val raw = message.orEmpty()
        return when {
            raw.contains("Unable to resolve host", ignoreCase = true) -> "现在连不上网,日常先没有同步过来。"
            raw.contains("timeout", ignoreCase = true) -> "这次等得有点久,可以再试一次。"
            raw.contains("UNAUTHORIZED", ignoreCase = true) -> "登录状态有点卡住了,再试一次会重新准备。"
            action == "save" -> raw.ifBlank { "保存这条日常时卡了一下。" }
            action == "delete" -> raw.ifBlank { "删除这条日常时卡了一下。" }
            else -> "读取日常时卡了一下。"
        }
    }

    private companion object {
        const val TAG = "RoutineViewModel"
    }
}

data class RoutineUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val pendingRoutineId: String? = null,
    val errorMessage: String? = null,
    val routines: List<RoutineDto> = emptyList(),
    val timezone: String = ZoneId.systemDefault().id
)

data class RoutineFormState(
    val id: String? = null,
    val title: String = "",
    val note: String = "",
    val type: String = "",
    val defaultTime: String = "09:00",
    val repeatDays: Set<Int> = setOf(1, 2, 3, 4, 5),
    val enabled: Boolean = true
) {
    fun cleaned(): CleanRoutineForm? {
        val cleanedTitle = title.trim()
        if (cleanedTitle.isEmpty()) return null
        val days = repeatDays.filter { it in 0..6 }.distinct().sorted()
        if (days.isEmpty()) return null
        val time = defaultTime.toTimeOrDefault()
        return CleanRoutineForm(
            id = id,
            title = cleanedTitle,
            note = note.trim().takeIf { it.isNotEmpty() },
            type = type.trim().takeIf { it.isNotEmpty() },
            defaultTime = time,
            repeatDays = days,
            enabled = enabled
        )
    }
}

data class CleanRoutineForm(
    val id: String?,
    val title: String,
    val note: String?,
    val type: String?,
    val defaultTime: String,
    val repeatDays: List<Int>,
    val enabled: Boolean
)

fun RoutineDto.toFormState(): RoutineFormState {
    return RoutineFormState(
        id = id,
        title = title,
        note = note.orEmpty(),
        type = type.orEmpty(),
        defaultTime = defaultTime.toTimeOrDefault(),
        repeatDays = repeatDays.filter { it in 0..6 }.toSet().ifEmpty { setOf(1, 2, 3, 4, 5) },
        enabled = enabled
    )
}

private fun String.toTimeOrDefault(): String {
    val raw = trim()
    val normalized = when {
        raw.matches(Regex("^\\d{2}:\\d{2}(:\\d{2})?$")) -> raw.take(5)
        else -> runCatching { LocalTime.parse(raw.take(5), DateTimeFormatter.ofPattern("HH:mm")) }
            .map { "%02d:%02d".format(it.hour, it.minute) }
            .getOrDefault("09:00")
    }
    return runCatching { LocalTime.parse(normalized, DateTimeFormatter.ofPattern("HH:mm")) }
        .map { "%02d:%02d".format(it.hour, it.minute) }
        .getOrDefault("09:00")
}
