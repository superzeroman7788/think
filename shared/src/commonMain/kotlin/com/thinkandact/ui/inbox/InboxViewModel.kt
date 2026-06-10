package com.thinkandact.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thinkandact.data.InboxRepository
import com.thinkandact.data.remote.InboxItemDto
import com.thinkandact.voice.AsrEvent
import com.thinkandact.voice.VoiceInputService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn

/** 收件箱分组（客户端按 due_date 算，本地今天为锚）。 */
enum class InboxGroup { DUE_TODAY, THIS_WEEK, LATER, NO_DATE }

data class InboxUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val items: List<InboxItemDto> = emptyList(),
    val pendingActionId: String? = null,
    // 记一笔 sheet
    val captureOpen: Boolean = false,
    val isRecording: Boolean = false,
    val isConnecting: Boolean = false,
    val isFinalizing: Boolean = false,
    val transcript: String = "",
    val isCapturing: Boolean = false,
    val captured: com.thinkandact.data.remote.InboxCaptureResponse? = null, // 已入箱、待展示 chip
    val captureError: String? = null,
) {
    /** 分组（pending 在前；dismissed/added 淡显也归到各自日子组里）。 */
    fun grouped(): Map<InboxGroup, List<InboxItemDto>> {
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        val weekEnd = today.plus(DatePeriod(days = 6))
        return items.groupBy { item ->
            val d = item.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            when {
                d == null -> InboxGroup.NO_DATE
                d <= today -> InboxGroup.DUE_TODAY
                d <= weekEnd -> InboxGroup.THIS_WEEK
                else -> InboxGroup.LATER
            }
        }
    }

    /** 「到日子了」未处理数（与角标口径一致：pending 且 due ≤ 今天）。 */
    val dueTodayPendingCount: Int
        get() {
            val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
            return items.count {
                it.status == "pending" &&
                    it.dueDate?.let { d -> runCatching { LocalDate.parse(d) <= today }.getOrDefault(false) } == true
            }
        }
}

/**
 * 随手记(收件箱)页 + 记一笔捕捉。
 * 红线：收件箱 ≠ 任务；不自动加入；失败显真实错误不伪装。
 */
class InboxViewModel(
    private val inboxRepository: InboxRepository,
    private val voiceInputService: VoiceInputService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(InboxUiState())
    val uiState: StateFlow<InboxUiState> = _uiState.asStateFlow()

    val amp get() = voiceInputService.amp
    private var voiceJob: Job? = null
    private var voiceFinalText = ""

    init { load() }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching { inboxRepository.list() }
                .onSuccess { items -> _uiState.update { it.copy(isLoading = false, items = items, errorMessage = null) } }
                .onFailure { t -> _uiState.update { it.copy(isLoading = false, errorMessage = t.message ?: "拉收件箱失败了。") } }
        }
    }

    // ── 条目操作 ───────────────────────────────────────────────────
    fun addToday(item: InboxItemDto) = act(item.id) { inboxRepository.addToday(item.id) }
    fun dismiss(id: String) = act(id) { inboxRepository.dismiss(id) }
    fun delete(id: String) = act(id) { inboxRepository.delete(id) }
    fun setDue(id: String, dueDate: String?, duePart: String?) = act(id) { inboxRepository.setDue(id, dueDate, duePart) }

    private fun act(id: String, op: suspend () -> Unit) {
        if (uiState.value.pendingActionId != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(pendingActionId = id, errorMessage = null) }
            runCatching { op() }
                .onSuccess { _uiState.update { it.copy(pendingActionId = null) }; load() }
                .onFailure { t -> _uiState.update { it.copy(pendingActionId = null, errorMessage = t.message ?: "没存上,再试一次。") } }
        }
    }

    // ── 记一笔 sheet ──────────────────────────────────────────────
    fun openCapture() {
        _uiState.update { it.copy(captureOpen = true, transcript = "", captured = null, captureError = null) }
        viewModelScope.launch { voiceInputService.warmSessionCache() }
    }

    fun closeCapture() {
        voiceJob?.cancel(); voiceJob = null; voiceFinalText = ""
        _uiState.update { it.copy(captureOpen = false, isRecording = false, isConnecting = false, isFinalizing = false, transcript = "", captured = null, captureError = null) }
    }

    fun startVoice() {
        val s = uiState.value
        if (s.isRecording || s.isCapturing) return
        voiceFinalText = ""
        _uiState.update { it.copy(isRecording = true, isConnecting = true, isFinalizing = false, transcript = "", captureError = null) }
        voiceJob = viewModelScope.launch {
            voiceInputService.transcribe().collect { e ->
                when (e) {
                    is AsrEvent.SessionReady -> Unit
                    AsrEvent.Connected -> _uiState.update { it.copy(isConnecting = false) }
                    is AsrEvent.Partial -> _uiState.update { it.copy(transcript = voiceFinalText + e.text) }
                    is AsrEvent.Final -> { voiceFinalText += e.text; _uiState.update { it.copy(transcript = voiceFinalText) } }
                    is AsrEvent.Completed -> onVoiceDone()
                    is AsrEvent.Failed -> { endVoice(); _uiState.update { it.copy(captureError = "语音没接上,打字也行。") } }
                }
            }
        }
    }

    fun stopVoice() {
        if (!uiState.value.isRecording || uiState.value.isFinalizing) return
        _uiState.update { it.copy(isRecording = false, isConnecting = false, isFinalizing = true) }
        viewModelScope.launch { voiceInputService.requestStopRecording() }
    }

    fun cancelVoice() {
        voiceJob?.cancel(); voiceJob = null; voiceFinalText = ""
        _uiState.update { it.copy(isRecording = false, isConnecting = false, isFinalizing = false) }
    }

    private fun endVoice() { voiceJob?.cancel(); voiceJob = null; _uiState.update { it.copy(isRecording = false, isConnecting = false, isFinalizing = false) } }

    private fun onVoiceDone() { endVoice() } // 转写留在输入框,等用户点「放进收件箱」

    fun onTranscriptEdited(text: String) { _uiState.update { it.copy(transcript = text) } }

    /** 放进收件箱：调 capture（AI 抽日期，失败兜底直插，绝不丢笔记）。 */
    fun putIntoInbox(source: String = "voice") {
        val text = uiState.value.transcript.trim()
        if (text.isEmpty() || uiState.value.isCapturing) return
        _uiState.update { it.copy(isCapturing = true, captureError = null) }
        viewModelScope.launch {
            runCatching { inboxRepository.capture(text, source) }
                .onSuccess { resp -> _uiState.update { it.copy(isCapturing = false, captured = resp) }; load() }
                .onFailure { t -> _uiState.update { it.copy(isCapturing = false, captureError = t.message ?: "没存上,网络好点再试(笔记没丢)。") } }
        }
    }

    /** 入箱后在 chip 上改日期。 */
    fun changeCapturedDue(dueDate: String?, duePart: String?) {
        val id = uiState.value.captured?.id ?: return
        viewModelScope.launch {
            runCatching { inboxRepository.setDue(id, dueDate, duePart) }
                .onSuccess {
                    _uiState.update { it.copy(captured = it.captured?.copy(dueDate = dueDate, duePart = duePart)) }
                    load()
                }
                .onFailure { t -> _uiState.update { it.copy(captureError = t.message) } }
        }
    }

    /** 入箱完成后清空,准备「再说一句」或收起。 */
    fun resetForNext() {
        voiceFinalText = ""
        _uiState.update { it.copy(transcript = "", captured = null, captureError = null) }
    }

    override fun onCleared() { voiceJob?.cancel(); super.onCleared() }
}
