package com.thinkandact.ui.routine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thinkandact.data.RoutineRepository
import com.thinkandact.data.remote.RoutineDto
import com.thinkandact.voice.AsrEvent
import com.thinkandact.voice.VoiceInputService
import com.thinkandact.voice.VoiceLatencyTracker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

class RoutineViewModel(
    private val routineRepository: RoutineRepository,
    private val voiceInputService: VoiceInputService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RoutineUiState())
    val uiState: StateFlow<RoutineUiState> = _uiState.asStateFlow()

    private var voiceJob: Job? = null
    private var voiceFinalText: String = ""
    private val latencyTracker = VoiceLatencyTracker()

    init {
        refresh()
    }

    // ── 语音优先建日常：说一句 → 转写 → 后端解析成「标题+时间+重复」→ 确认 ──────
    /** UI 确认已获麦克风权限后调用：开始录音。 */
    fun startVoiceCapture() {
        if (uiState.value.isRecording || uiState.value.isParsing) return
        voiceFinalText = ""
        latencyTracker.onTap()
        _uiState.update {
            it.copy(
                isRecording = true, isVoiceConnecting = true, isVoiceFinalizing = false,
                voiceTranscript = "", isParsing = false, voiceHint = null,
            )
        }
        voiceJob = viewModelScope.launch {
            voiceInputService.transcribe().collect { event ->
                when (event) {
                    is AsrEvent.SessionReady ->
                        latencyTracker.onSession(event.provider, event.connectMode, event.prefetchHit)
                    AsrEvent.Connected -> {
                        latencyTracker.onOpen()
                        _uiState.update { it.copy(isVoiceConnecting = false) }
                    }
                    is AsrEvent.Partial -> {
                        latencyTracker.onTranscript()
                        _uiState.update { it.copy(voiceTranscript = voiceFinalText + event.text) }
                    }
                    is AsrEvent.Final -> {
                        latencyTracker.onTranscript()
                        voiceFinalText += event.text
                        _uiState.update { it.copy(voiceTranscript = voiceFinalText) }
                    }
                    is AsrEvent.Completed -> {
                        latencyTracker.onFinal()
                        onCaptureComplete()
                    }
                    is AsrEvent.Failed -> {
                        latencyTracker.onFailed(event.reason)
                        endCapture()
                        _uiState.update { it.copy(voiceHint = voiceFailureHint(event.reason)) }
                    }
                }
            }
        }
    }

    /** 松手：停采 + 等 final，不立刻 cancel；final 到了再去解析。 */
    fun stopVoiceCapture() {
        if (!uiState.value.isRecording || uiState.value.isVoiceFinalizing) return
        latencyTracker.onStop()
        _uiState.update { it.copy(isRecording = false, isVoiceConnecting = false, isVoiceFinalizing = true) }
        viewModelScope.launch { voiceInputService.requestStopRecording() }
    }

    private fun endCapture() {
        latencyTracker.flush()
        voiceJob?.cancel()
        voiceJob = null
        _uiState.update { it.copy(isRecording = false, isVoiceConnecting = false, isVoiceFinalizing = false) }
    }

    /**
     * 录音收尾完成：转写 → 后端解析 → **直接建好并启用**,刷新列表。
     * 不再弹确认框;建错了用户可在列表里改/删。
     */
    private fun onCaptureComplete() {
        endCapture()
        val text = uiState.value.voiceTranscript.trim()
        if (text.isBlank()) {
            _uiState.update { it.copy(voiceHint = "没听清,再按住说一次？") }
            return
        }
        _uiState.update { it.copy(isParsing = true, voiceHint = null) }
        viewModelScope.launch {
            val drafts = runCatching { routineRepository.parseRoutines(text, uiState.value.timezone) }
                .getOrElse { t ->
                    _uiState.update { it.copy(isParsing = false, voiceHint = parseFailureHint(t)) }
                    return@launch
                }
            if (drafts.isEmpty()) {
                _uiState.update { it.copy(isParsing = false, voiceHint = "没听出具体安排,换种说法或手动加。") }
                return@launch
            }
            runCatching {
                drafts.forEach { d ->
                    routineRepository.createRoutine(
                        title = d.title,
                        note = d.note?.takeIf { it.isNotBlank() },
                        type = null,
                        defaultTime = d.defaultTime.toTimeOrDefault(),
                        repeatDays = d.repeatDays.filter { it in 0..6 }.distinct().sorted()
                            .ifEmpty { listOf(1, 2, 3, 4, 5) },
                    )
                }
                routineRepository.listRoutines()
            }.onSuccess { routines ->
                _uiState.update {
                    it.copy(
                        isParsing = false,
                        routines = routines,
                        voiceTranscript = "",
                        voiceHint = "已加入并启用:" + drafts.joinToString("、") { d -> d.title },
                    )
                }
            }.onFailure {
                _uiState.update { it.copy(isParsing = false, voiceHint = "整理出来了,但没加进去,先手动加吧。") }
            }
        }
    }

    fun onVoicePermissionDenied() {
        _uiState.update { it.copy(isRecording = false, isVoiceConnecting = false, voiceHint = "没拿到麦克风权限,先手动加吧。") }
    }

    fun dismissVoiceHint() {
        _uiState.update { it.copy(voiceHint = null) }
    }

    private fun voiceFailureHint(reason: String): String = when {
        reason.contains("麦克风") -> reason // N-10:设备级失败显真实原因,不套「没接上」
        reason.contains("QUOTA", ignoreCase = true) || reason.contains("429") ->
            "今天的语音次数用完了,先手动加吧。"
        reason.contains("Unable to resolve host", ignoreCase = true) || reason.contains("timeout", ignoreCase = true) ->
            "现在网络不太稳,语音先歇会儿,可以手动加。"
        else -> "语音暂时没接上,先手动加也行。"
    }

    private fun parseFailureHint(t: Throwable): String {
        val raw = t.message.orEmpty()
        return when {
            raw.contains("404") || raw.contains("Not Found", ignoreCase = true) ->
                "语音整理功能还在上线中,先手动加一条吧。"
            raw.contains("Unable to resolve host", ignoreCase = true) || raw.contains("timeout", ignoreCase = true) ->
                "现在网络不太稳,先手动加吧。"
            raw.contains("QUOTA", ignoreCase = true) || raw.contains("429") ->
                "今天的次数用完了,先手动加吧。"
            else -> "没整理成功,先手动加一条吧。"
        }
    }

    override fun onCleared() {
        voiceJob?.cancel()
        super.onCleared()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching { routineRepository.listRoutines() }
                .onSuccess { routines ->
                    _uiState.update { it.copy(isLoading = false, routines = routines, errorMessage = null) }
                    // §2 接缓存：进「我的日常」即预取 ASR 会话（不计配额），让首次语音建日常 preflight≈0。
                    viewModelScope.launch { voiceInputService.warmSessionCache() }
                }
                .onFailure { throwable ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = throwable.friendlyMessage()) }
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
                        title = cleaned.title, note = cleaned.note, type = cleaned.type,
                        defaultTime = cleaned.defaultTime, repeatDays = cleaned.repeatDays
                    )
                    routineRepository.listRoutines()
                }
            } else {
                runCatching {
                    routineRepository.updateRoutine(
                        id = cleaned.id, title = cleaned.title, note = cleaned.note, type = cleaned.type,
                        defaultTime = cleaned.defaultTime, repeatDays = cleaned.repeatDays, enabled = cleaned.enabled
                    )
                    routineRepository.listRoutines()
                }
            }
            result
                .onSuccess { refreshedRoutines ->
                    _uiState.update { it.copy(isSaving = false, routines = refreshedRoutines, errorMessage = null) }
                }
                .onFailure { throwable ->
                    _uiState.update { it.copy(isSaving = false, errorMessage = throwable.friendlyMessage(action = "save")) }
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
                    _uiState.update { it.copy(pendingRoutineId = null, routines = refreshedRoutines, errorMessage = null) }
                }
                .onFailure { throwable ->
                    _uiState.update { it.copy(pendingRoutineId = null, errorMessage = throwable.friendlyMessage(action = "save")) }
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
                    _uiState.update { it.copy(pendingRoutineId = null, routines = refreshedRoutines, errorMessage = null) }
                }
                .onFailure { throwable ->
                    _uiState.update { it.copy(pendingRoutineId = null, errorMessage = throwable.friendlyMessage(action = "delete")) }
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
}

data class RoutineUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val pendingRoutineId: String? = null,
    val errorMessage: String? = null,
    val routines: List<RoutineDto> = emptyList(),
    val timezone: String = TimeZone.currentSystemDefault().id,
    // 语音优先建日常
    val isRecording: Boolean = false,
    val isVoiceConnecting: Boolean = false,
    val isVoiceFinalizing: Boolean = false,
    /** 正在录的实时转写（说了什么）。 */
    val voiceTranscript: String = "",
    /** 转写完成、正在后端解析+建日常（「整理中…」）。 */
    val isParsing: Boolean = false,
    val voiceHint: String? = null,
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

fun RoutineDto.toFormState() = RoutineFormState(
    id = id,
    title = title,
    note = note.orEmpty(),
    type = type.orEmpty(),
    defaultTime = defaultTime.toTimeOrDefault(),
    repeatDays = repeatDays.filter { it in 0..6 }.toSet().ifEmpty { setOf(1, 2, 3, 4, 5) },
    enabled = enabled
)

internal fun String.toTimeOrDefault(): String {
    val raw = trim()
    if (raw.matches(Regex("^\\d{2}:\\d{2}(:\\d{2})?$"))) {
        val parts = raw.take(5).split(":")
        val h = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: return "09:00"
        val m = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: return "09:00"
        return h.toString().padStart(2, '0') + ":" + m.toString().padStart(2, '0')
    }
    val parts = raw.take(5).split(":")
    val h = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: return "09:00"
    val m = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: return "09:00"
    return h.toString().padStart(2, '0') + ":" + m.toString().padStart(2, '0')
}
