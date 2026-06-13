package com.thinkandact.ui.fullplan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thinkandact.data.PlanRepository
import com.thinkandact.data.PlanReviseStaleException
import com.thinkandact.data.remote.ApplyAddedReviseDto
import com.thinkandact.data.remote.ApplyAfterDto
import com.thinkandact.data.remote.ApplyRevisionDto
import com.thinkandact.data.remote.PlanReviseResponse
import com.thinkandact.data.remote.TaskRowDto
import com.thinkandact.voice.AsrEvent
import com.thinkandact.voice.VoiceInputService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.toInstant
import kotlinx.datetime.todayIn

data class FullPlanUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val tasks: List<TaskRowDto> = emptyList(),    // 全天(含 suggested),按 planned_start 升序
    // 语音改今天(plan-revise)
    val isRecording: Boolean = false,
    val isConnecting: Boolean = false,
    val isFinalizing: Boolean = false,
    val transcript: String = "",
    val reviseHint: String? = null,
    val isProposing: Boolean = false,
    val isApplying: Boolean = false,
    val proposal: PlanReviseResponse? = null,
)

/**
 * 今天的完整计划页：随时看整天 + 随时改（拼装：全天视图 + plan-revise + 点选微调）。
 * 软建议软显示、+加入才升级（建议≠任务）；语音改一律 propose→确认→apply。
 */
class FullPlanViewModel(
    private val planRepository: PlanRepository,
    private val voiceInputService: VoiceInputService,
    private val reminderScheduler: com.thinkandact.reminders.ReminderScheduler,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FullPlanUiState())
    val uiState: StateFlow<FullPlanUiState> = _uiState.asStateFlow()

    val amp get() = voiceInputService.amp
    private var voiceJob: Job? = null
    private var voiceFinalText = ""

    init { load() }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching { planRepository.fetchTodayTasks(includeSuggested = true) }
                .onSuccess { tasks ->
                    _uiState.update { it.copy(isLoading = false, tasks = tasks, errorMessage = null) }
                    val now = Clock.System.now().toEpochMilliseconds()
                    reminderScheduler.sync(com.thinkandact.reminders.ReminderPlanner.fromTasks(tasks, now)) // ★ 提醒重排
                    viewModelScope.launch { voiceInputService.warmSessionCache() }
                }
                .onFailure { t -> _uiState.update { it.copy(isLoading = false, errorMessage = t.message ?: "拉今天的计划失败了。") } }
        }
    }

    // ── 点选微调（手动）────────────────────────────────────────────────
    fun markDone(id: String) = patch(id) { taskId ->
        val task = uiState.value.tasks.firstOrNull { t -> t.id == taskId }
        val now = Clock.System.now().toString()
        planRepository.markTaskDone(taskId, actualEnd = now, actualStart = task?.actualStart)
    }
    fun markSkip(id: String) = patch(id) { planRepository.markTaskSkipped(it) }
    fun acceptSuggestion(id: String) = patch(id) { planRepository.markTaskPlanned(it) }
    // B6-04:点选删除 → 软删消失;跳过任务「恢复(撤销跳过)」→ 回 planned。
    fun deleteTask(id: String) = patch(id) { planRepository.softDeleteTask(it) }
    fun restoreTask(id: String) = patch(id) { planRepository.markTaskPlanned(it) }
    fun updateTime(id: String, hour: Int, minute: Int) = patch(id) {
        planRepository.updateTaskPlannedStart(it, todayIsoAt(hour, minute))
    }
    /** F7-03 点选编辑弹窗「保存」：标题 / 时间 / 时长 / ★ 一次改完,只改有变化的字段,最后刷一次。 */
    fun editTask(id: String, title: String, hour: Int, minute: Int, durationMin: Int, important: Boolean, isPoint: Boolean) {
        viewModelScope.launch {
            runCatching {
                val cur = uiState.value.tasks.firstOrNull { it.id == id }
                val t = title.trim()
                if (cur != null && t.isNotBlank() && t != cur.title) planRepository.updateTaskTitle(id, t)
                planRepository.updateTaskPlannedStart(id, todayIsoAt(hour, minute))
                if (!isPoint) planRepository.updateTaskDuration(id, durationMin.coerceAtLeast(1))
                if (cur != null && important != cur.important) planRepository.updateTaskImportant(id, important)
            }.onSuccess { load() }
                .onFailure { e -> _uiState.update { it.copy(errorMessage = e.message ?: "改这一下没存上,再试一次。") } }
        }
    }

    // F7-03「+ 加一项 / + 加时刻点」：插一条真任务后刷新。
    fun addTask(title: String, hour: Int, minute: Int, important: Boolean, durationMin: Int = 30, onResult: (Boolean, String?) -> Unit = { _, _ -> }) =
        add(title, hour, minute, important, durationMin, "block", onResult)
    fun addPoint(title: String, hour: Int, minute: Int, onResult: (Boolean, String?) -> Unit = { _, _ -> }) =
        add(title, hour, minute, false, 0, "point", onResult)

    private fun add(
        title: String,
        hour: Int,
        minute: Int,
        important: Boolean,
        durationMin: Int,
        kind: String,
        onResult: (Boolean, String?) -> Unit,
    ) {
        val t = title.trim()
        if (t.isBlank()) {
            onResult(false, "先写个标题。")
            return
        }
        viewModelScope.launch {
            runCatching { planRepository.addTaskToday(t, todayIsoAt(hour, minute), durationMin, important, kind) }
                .onSuccess { load(); onResult(true, null) }
                .onFailure { e -> onResult(false, e.message ?: "加这一项没存上,再试一次。") }
        }
    }

    private fun patch(id: String, op: suspend (String) -> Unit) {
        viewModelScope.launch {
            runCatching { op(id) }
                .onSuccess { load() }
                .onFailure { t -> _uiState.update { it.copy(errorMessage = t.message ?: "改这一下没存上,再试一次。") } }
        }
    }

    // ── 底部语音改今天（plan-revise，复用 + 秒应）────────────────────────
    fun onReviseTap() { if (!uiState.value.isRecording && !uiState.value.isProposing) _uiState.update { it.copy(reviseHint = "按住这条说一句,改今天。") } }

    /** F7-03 轻点打字:与语音同一根管子(propose)。 */
    fun reviseFromText(text: String) {
        val t = text.trim()
        if (t.isBlank() || uiState.value.isRecording || uiState.value.isProposing || uiState.value.proposal != null) return
        propose(t)
    }

    fun startReviseVoice() {
        val s = uiState.value
        if (s.isRecording || s.isProposing || s.proposal != null) return
        voiceFinalText = ""
        _uiState.update { it.copy(isRecording = true, isConnecting = true, isFinalizing = false, transcript = "", reviseHint = null) }
        voiceJob = viewModelScope.launch {
            voiceInputService.transcribe().collect { e ->
                when (e) {
                    is AsrEvent.SessionReady -> Unit
                    AsrEvent.Connected -> _uiState.update { it.copy(isConnecting = false) }
                    is AsrEvent.Partial -> _uiState.update { it.copy(transcript = voiceFinalText + e.text) }
                    is AsrEvent.Final -> { voiceFinalText += e.text; _uiState.update { it.copy(transcript = voiceFinalText) } }
                    is AsrEvent.Completed -> onCaptureDone()
                    is AsrEvent.Failed -> {
                        com.thinkandact.core.debug.FeDebug.raw(com.thinkandact.core.debug.FeDebug.Layer.BACKEND, "语音失败(原始): ${e.reason}")
                        endVoice(); _uiState.update { it.copy(reviseHint = asrFailHint(e.reason)) }
                    }
                }
            }
        }
    }

    fun stopReviseVoice() {
        if (!uiState.value.isRecording || uiState.value.isFinalizing) return
        _uiState.update { it.copy(isRecording = false, isConnecting = false, isFinalizing = true) }
        viewModelScope.launch { voiceInputService.requestStopRecording() }
    }

    fun cancelReviseVoice() {
        voiceJob?.cancel(); voiceJob = null; voiceFinalText = ""
        _uiState.update { it.copy(isRecording = false, isConnecting = false, isFinalizing = false, transcript = "", reviseHint = null) }
    }

    private fun endVoice() { voiceJob?.cancel(); voiceJob = null; _uiState.update { it.copy(isRecording = false, isConnecting = false, isFinalizing = false) } }

    private fun onCaptureDone() {
        endVoice()
        val text = uiState.value.transcript.trim()
        if (text.isBlank()) { _uiState.update { it.copy(reviseHint = "没听清,再说一次？") }; return }
        propose(text)
    }

    private fun propose(instruction: String) {
        _uiState.update { it.copy(isProposing = true, reviseHint = null) }
        viewModelScope.launch {
            // 只把已接受任务(planned/done/skipped)送 revise,软建议不进可改项。
            val revisable = uiState.value.tasks.filter { it.status != "suggested" }
            runCatching { planRepository.proposeRevision(instruction, revisable) }
                .onSuccess { p ->
                    val hasChange = p.revisions.any {
                        it.change in setOf("moved", "skip", "delete", "dropped")
                    } || p.added.isNotEmpty() || p.deferred.isNotEmpty()
                    if (!hasChange) {
                        val reason = p.rejectReason?.takeIf { it.isNotBlank() } ?: p.warnings.firstOrNull()?.takeIf { it.isNotBlank() }
                        if (reason == null) com.thinkandact.core.debug.FeDebug.reject("propose 无操作且无 reject_reason", instruction)
                        _uiState.update { it.copy(isProposing = false, proposal = null, reviseHint = reason ?: "这句我没听出要改什么,换种说法？") }
                    } else {
                        _uiState.update { it.copy(isProposing = false, proposal = p) }
                    }
                }
                .onFailure { t -> _uiState.update { it.copy(isProposing = false, reviseHint = t.message ?: "刚没接上,再说一次？") } }
        }
    }

    fun applyProposal() {
        val p = uiState.value.proposal ?: return
        if (uiState.value.isApplying) return
        val applyList = p.revisions.mapNotNull { r ->
            when (r.change) {
                "moved" -> ApplyRevisionDto(r.taskId, "moved", ApplyAfterDto(plannedStart = r.after.plannedStart, plannedDuration = r.after.plannedDuration, status = "planned"))
                "skip", "dropped" -> ApplyRevisionDto(r.taskId, "skip", ApplyAfterDto(status = "skipped"))
                "delete" -> ApplyRevisionDto(r.taskId, "delete", ApplyAfterDto(status = "deleted"))
                else -> null
            }
        }
        val addedList = p.added.map { ApplyAddedReviseDto(it.clientKey, it.title, it.plannedStart, it.plannedDuration, it.important, it.kind) }
        if (applyList.isEmpty() && addedList.isEmpty() && p.deferred.isEmpty()) { _uiState.update { it.copy(proposal = null) }; return }
        _uiState.update { it.copy(isApplying = true) }
        viewModelScope.launch {
            runCatching { planRepository.applyRevision(p.revisionId, applyList, addedList, p.deferred) }
                .onSuccess { _uiState.update { it.copy(isApplying = false, proposal = null, reviseHint = "好,按你说的调整了。") }; load() }
                .onFailure { t ->
                    if (t is PlanReviseStaleException) { _uiState.update { it.copy(isApplying = false, proposal = null, reviseHint = t.message) }; load() }
                    else _uiState.update { it.copy(isApplying = false, reviseHint = t.message ?: "刚没存上,再试一次？") }
                }
        }
    }

    fun cancelProposal() { _uiState.update { it.copy(proposal = null) } }
    fun dismissHint() { _uiState.update { it.copy(reviseHint = null) } }

    private fun asrFailHint(reason: String) = when {
        reason.contains("麦克风") -> reason // N-10:设备级失败显真实原因,不套「没接上」
        reason.contains("QUOTA", true) || reason.contains("429") -> "今天的语音次数用完了,先点一下改吧。"
        else -> "语音没接上,先点选改也行。"
    }

    // BUG-12：统一时间格式（本地偏移），与早上屏一致。
    private fun todayIsoAt(hour: Int, minute: Int): String = com.thinkandact.core.time.todayLocalIso(hour, minute)
}
