package com.thinkandact.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thinkandact.data.ReviewRepository
import com.thinkandact.data.ReviewStaleException
import com.thinkandact.data.remote.ReviewAddedDto
import com.thinkandact.data.remote.ReviewApplyProposalDto
import com.thinkandact.data.remote.ReviewParseResponse
import com.thinkandact.data.remote.TaskRowDto
import com.thinkandact.voice.AsrEvent
import com.thinkandact.voice.VoiceInputService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReviewUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val tasks: List<TaskRowDto> = emptyList(),
    // 底部唯一语音(只做"改今天")
    val isRecording: Boolean = false,
    val isConnecting: Boolean = false,
    val isFinalizing: Boolean = false,
    val transcript: String = "",
    val voiceHint: String? = null,
    // 批量 propose → 确认卡
    val isParsing: Boolean = false,
    val parseResult: ReviewParseResponse? = null,
    val isApplying: Boolean = false,
    // ② 今天的提醒(两块,来自 day-summary)
    val praise: String = "",
    val advice: String = "",
    val isSummarizing: Boolean = false,
    /** 审核变更后,已显示的提醒已过时 → 提示「重新生成」(也用于测 BE 新文案,持久化否则不再 POST)。 */
    val reminderStale: Boolean = false,
) {
    val hasReminder get() = praise.isNotBlank() || advice.isNotBlank()
}

/**
 * 复盘屏 v2（契约 v1.0 + v2 改版）：只剩 ① 审核 + ② 今天的提醒 + 底部一条语音。
 * **本页无任何记忆写入**（反思/记忆已移除，addMemory 不在此调用）。
 * 红线：语音改 propose → 确认卡 → apply；取消 = 零改动。
 */
class ReviewViewModel(
    private val reviewRepository: ReviewRepository,
    private val voiceInputService: VoiceInputService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    /** 实时音量(0–1)给「按住即时反馈」波形复用。 */
    val amp get() = voiceInputService.amp

    private var voiceJob: Job? = null
    private var voiceFinalText: String = ""

    init { load() }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                val tasks = reviewRepository.fetchTodayTasks()
                val reflection = runCatching { reviewRepository.fetchTodayReflection() }.getOrNull()
                tasks to reflection
            }.onSuccess { (tasks, reflection) ->
                // v2:回读已持久化的 ai_praise/ai_advice(BE 三层口吻保障)→ ② 直接显示两块,不再占位按钮。
                // 不回灌旧的单句 ai_summary(可能含 v2 禁止的"数你没做"口吻)。
                _uiState.update {
                    it.copy(
                        isLoading = false, tasks = tasks,
                        praise = reflection?.aiPraise?.takeIf { s -> s.isNotBlank() } ?: it.praise,
                        advice = reflection?.aiAdvice?.takeIf { s -> s.isNotBlank() } ?: it.advice,
                        errorMessage = null,
                    )
                }
                viewModelScope.launch { voiceInputService.warmSessionCache() }
            }.onFailure { t ->
                _uiState.update { it.copy(isLoading = false, errorMessage = t.message ?: "拉今天的记录失败了。") }
            }
        }
    }

    // ── ① 审核:三态循环(乐观 + 单行 PATCH)──────────────────────────────
    fun cycleStatus(taskId: String) {
        val task = uiState.value.tasks.firstOrNull { it.id == taskId } ?: return
        if (task.status == "dropped") {
            com.thinkandact.core.debug.FeDebug.reject("点行循环跳过 dropped(契约:dropped 只读)", "cycle ${task.title}")
            return
        }
        // N-05: 已完成再点回到「未做」(撤销),不误滑到跳过;跳过用长按(见 ReviewScreen)。
        val next = when (task.status) {
            "planned" -> "done"
            "done" -> "planned"
            "skipped" -> "planned"
            else -> "done"
        }
        // 任务态变了 → 已显示的提醒过时,标记 stale(让用户可重新生成)。
        _uiState.update { st -> st.copy(tasks = st.tasks.map { if (it.id == taskId) it.copy(status = next) else it }, errorMessage = null, reminderStale = st.praise.isNotBlank() || st.advice.isNotBlank()) }
        viewModelScope.launch {
            runCatching {
                when (next) {
                    "done" -> reviewRepository.markTaskDoneReview(taskId)
                    "skipped" -> reviewRepository.markTaskSkippedReview(taskId)
                    else -> reviewRepository.markTaskPlannedReview(taskId)
                }
            }.onFailure {
                _uiState.update { st -> st.copy(tasks = st.tasks.map { if (it.id == taskId) it.copy(status = task.status) else it }, errorMessage = "刚那下没存上,再点一次。") }
            }
        }
    }

    /** 长按标记跳过(planned → skipped);避免「完成」态一点就变跳过。 */
    fun markSkipped(taskId: String) {
        val task = uiState.value.tasks.firstOrNull { it.id == taskId } ?: return
        if (task.status != "planned") return
        _uiState.update { st ->
            st.copy(
                tasks = st.tasks.map { if (it.id == taskId) it.copy(status = "skipped") else it },
                errorMessage = null,
                reminderStale = st.praise.isNotBlank() || st.advice.isNotBlank(),
            )
        }
        viewModelScope.launch {
            runCatching { reviewRepository.markTaskSkippedReview(taskId) }
                .onFailure {
                    _uiState.update { st ->
                        st.copy(
                            tasks = st.tasks.map { if (it.id == taskId) it.copy(status = task.status) else it },
                            errorMessage = "刚那下没存上,再试一次。",
                        )
                    }
                }
        }
    }

    // ── 底部语音(只做改今天):按住即时反馈 + 录音 ───────────────────────
    fun onVoiceTap() {
        if (uiState.value.isRecording || uiState.value.isParsing) return
        _uiState.update { it.copy(voiceHint = "按住这条说一句,改今天。") }
    }

    fun startVoice() {
        val s = uiState.value
        if (s.isRecording || s.isParsing || s.parseResult != null) return
        voiceFinalText = ""
        _uiState.update { it.copy(isRecording = true, isConnecting = true, isFinalizing = false, transcript = "", voiceHint = null) }
        voiceJob = viewModelScope.launch {
            voiceInputService.transcribe().collect { event ->
                when (event) {
                    is AsrEvent.SessionReady -> Unit
                    AsrEvent.Connected -> _uiState.update { it.copy(isConnecting = false) }
                    is AsrEvent.Partial -> _uiState.update { it.copy(transcript = voiceFinalText + event.text) }
                    is AsrEvent.Final -> {
                        voiceFinalText += event.text
                        _uiState.update { it.copy(transcript = voiceFinalText) }
                    }
                    is AsrEvent.Completed -> onVoiceComplete()
                    is AsrEvent.Failed -> {
                        com.thinkandact.core.debug.FeDebug.raw(com.thinkandact.core.debug.FeDebug.Layer.BACKEND, "语音失败(原始): ${event.reason}")
                        endVoice()
                        _uiState.update { it.copy(voiceHint = asrFailHint(event.reason)) }
                    }
                }
            }
        }
    }

    fun stopVoice() {
        if (!uiState.value.isRecording || uiState.value.isFinalizing) return
        _uiState.update { it.copy(isRecording = false, isConnecting = false, isFinalizing = true) }
        viewModelScope.launch { voiceInputService.requestStopRecording() }
    }

    /** 上滑取消区松手:中止、整段丢弃、零改动。 */
    fun cancelVoice() {
        voiceJob?.cancel(); voiceJob = null
        voiceFinalText = ""
        _uiState.update { it.copy(isRecording = false, isConnecting = false, isFinalizing = false, transcript = "", voiceHint = null) }
    }

    private fun endVoice() {
        voiceJob?.cancel(); voiceJob = null
        _uiState.update { it.copy(isRecording = false, isConnecting = false, isFinalizing = false) }
    }

    private fun onVoiceComplete() {
        endVoice()
        val text = uiState.value.transcript.trim()
        if (text.isBlank()) { _uiState.update { it.copy(voiceHint = "没听清,再说一次?") }; return }
        parseBatch(text)
    }

    private fun parseBatch(transcript: String) {
        _uiState.update { it.copy(isParsing = true, voiceHint = null) }
        viewModelScope.launch {
            runCatching { reviewRepository.parseReviewVoice(transcript, uiState.value.tasks) }
                .onSuccess { res ->
                    val noOp = res.unclear || (res.proposed.isEmpty() && res.added.isEmpty())
                    com.thinkandact.core.debug.FeDebug.llm(
                        userText = transcript,
                        rawStructure = "proposed=" + res.proposed.map { "${it.title}→${it.toStatus}" } +
                            " added=" + res.added.map { it.title } + " unclear=${res.unclear} warnings=" + res.warnings,
                        handling = if (noOp) "无可应用 → 提示用户" else "渲染前后对比",
                    )
                    if (noOp) {
                        val hint = res.warnings.firstOrNull()?.takeIf { it.isNotBlank() } ?: "没听清,换种说法?"
                        if (!res.unclear && res.warnings.isEmpty()) {
                            com.thinkandact.core.debug.FeDebug.reject("review-parse 返回无操作且后端无 reason", transcript)
                        }
                        _uiState.update { it.copy(isParsing = false, parseResult = null, voiceHint = hint) }
                    } else {
                        _uiState.update { it.copy(isParsing = false, parseResult = res) }
                    }
                }
                .onFailure { t -> _uiState.update { it.copy(isParsing = false, voiceHint = asrFailHint(t.message ?: "")) } }
        }
    }

    /** 确认 → 原子 apply（proposed + added）。基线陈旧 → 重拉。取消 = 零改动。 */
    fun applyParse() {
        val res = uiState.value.parseResult ?: return
        if (uiState.value.isApplying) return
        val proposed = res.proposed.map { ReviewApplyProposalDto(taskId = it.taskId, toStatus = it.toStatus) }
        val added: List<ReviewAddedDto> = res.added
        _uiState.update { it.copy(isApplying = true) }
        viewModelScope.launch {
            runCatching { reviewRepository.applyReview(res.reviewId, proposed, added) }
                .onSuccess { resp -> _uiState.update { it.copy(isApplying = false, parseResult = null, tasks = resp.tasks, voiceHint = "好,按你说的记下了。", reminderStale = it.praise.isNotBlank() || it.advice.isNotBlank()) } }
                .onFailure { t ->
                    if (t is ReviewStaleException) {
                        _uiState.update { it.copy(isApplying = false, parseResult = null, voiceHint = t.message) }
                        load()
                    } else {
                        _uiState.update { it.copy(isApplying = false, voiceHint = "刚才没存上,再说一次?") }
                    }
                }
        }
    }

    fun cancelParse() { _uiState.update { it.copy(parseResult = null) } }

    // ── ② 今天的提醒(day-summary v2:praise + advice)──────────────────────
    fun generateReminder() {
        if (uiState.value.isSummarizing) return
        _uiState.update { it.copy(isSummarizing = true, errorMessage = null) }
        viewModelScope.launch {
            runCatching { reviewRepository.daySummary(uiState.value.tasks) }
                .onSuccess { r ->
                    // BE v2 给 praise/advice 则用之；否则回退把单句 summary 放上块(advice 留空,隐藏下块)。
                    _uiState.update {
                        it.copy(
                            isSummarizing = false,
                            praise = r.praise?.takeIf { s -> s.isNotBlank() } ?: r.summary,
                            advice = r.advice?.takeIf { s -> s.isNotBlank() } ?: "",
                            reminderStale = false,
                        )
                    }
                }
                .onFailure { t -> _uiState.update { it.copy(isSummarizing = false, errorMessage = summaryFailHint(t)) } }
        }
    }

    fun dismissVoiceHint() { _uiState.update { it.copy(voiceHint = null) } }

    private fun asrFailHint(reason: String): String = when {
        reason.contains("麦克风") -> reason // N-10:设备级失败显真实原因,不套「没接上」
        reason.contains("QUOTA", true) || reason.contains("429") -> "今天的语音次数用完了,先点一下改吧。"
        else -> "语音没接上,先用点选改也行。"
    }

    private fun summaryFailHint(t: Throwable): String {
        val m = t.message ?: ""
        return if (m.contains("FAIR_USE", true) || m.contains("429")) "今天的 AI 次数用完了,明天再聊。" else "刚没成,过一下再试。"
    }
}
