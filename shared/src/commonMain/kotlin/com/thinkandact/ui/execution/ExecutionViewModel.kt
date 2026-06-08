package com.thinkandact.ui.execution

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
import com.thinkandact.voice.VoiceLatencyTracker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.math.ceil

/**
 * 执行屏（块一）+ 日内重排入口（块二）。
 *  - 块一：按当前时间定位「现在该做的那条」；变当前写 actual_start；完成/跳过回写 + 自动跳下一项。
 *  - 块二：底部说一句 → /plan-revise 出前后对比 → 确认才 apply（取消零改动）。
 */
class ExecutionViewModel(
    private val planRepository: PlanRepository,
    private val voiceInputService: VoiceInputService,
    private val reminderScheduler: com.thinkandact.reminders.ReminderScheduler,
) : ViewModel() {

    /** ★ 任务系统提醒:每次任务集变化都整组重排(仅 ★+planned+未来)。 */
    private fun syncReminders() {
        val now = Clock.System.now().toEpochMilliseconds()
        reminderScheduler.sync(com.thinkandact.reminders.ReminderPlanner.fromTasks(uiState.value.tasks, now))
    }

    fun shouldShowBgGuide() = reminderScheduler.shouldShowBackgroundGuide()
    fun markBgGuideShown() = reminderScheduler.markBackgroundGuideShown()
    fun openBgSettings() = reminderScheduler.openBackgroundSettings()

    private val _uiState = MutableStateFlow(ExecutionUiState())
    val uiState: StateFlow<ExecutionUiState> = _uiState.asStateFlow()

    /** 实时音量(0–1)给「按住即时反馈」波形复用。 */
    val amp get() = voiceInputService.amp

    private var voiceJob: Job? = null
    private var voiceFinalText: String = ""
    private val latencyTracker = VoiceLatencyTracker()
    /** 上次重排指令（基线陈旧时重新 propose 用）。 */
    private var lastInstruction: String = ""

    private var tideJob: Job? = null

    /**
     * v1.3：任务「变当前」那刻的**本地**起点(taskId→epochSec)。潮水/elapsed 用它算,
     * **不再** PATCH actual_start 到库(执行/学习语义归后端)。完成时可选用它作 actual_start。
     */
    private val displayStartedAt = mutableMapOf<String, Long>()

    init {
        load()
        startTideTicker()
    }

    // ── 块一：执行 ───────────────────────────────────────────────────
    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching { planRepository.fetchTodayTasks() }
                .onSuccess { tasks ->
                    val current = pickCurrent(tasks)
                    _uiState.update { it.copy(isLoading = false, tasks = tasks, currentTaskId = current?.id, errorMessage = null) }
                    ensureCurrentStarted()
                    recomputeTide()
                    syncReminders() // ★ 任务到点系统提醒
                    // §2 接缓存：进执行屏即预取 ASR 会话（不计配额），让「想调整今天」首次按麦 preflight≈0。
                    viewModelScope.launch { voiceInputService.warmSessionCache() }
                }
                .onFailure { t -> _uiState.update { it.copy(isLoading = false, errorMessage = t.friendly()) } }
        }
    }

    fun completeCurrent() {
        val cur = currentTask() ?: return
        if (uiState.value.pendingTaskId != null || uiState.value.isCompleting) return
        val now = Clock.System.now().toString()
        // v1.3：完成时才(可选)写 actual_start,来自**本地起点**;没有本地起点就用现在。
        val actualStart = cur.actualStart
            ?: displayStartedAt[cur.id]?.let { kotlinx.datetime.Instant.fromEpochSeconds(it).toString() }
            ?: now
        // 完成动效（§4.4）：取消上涨、退潮到 .08（Canvas 用 0.8s 快补间），✓ 约 1.3s 后切下一条。
        _uiState.update { it.copy(isCompleting = true, pendingTaskId = cur.id, tideLevel = 0.08f, errorMessage = null) }
        viewModelScope.launch {
            val ok = runCatching { planRepository.markTaskDone(cur.id, actualStart = actualStart, actualEnd = now) }.isSuccess
            delay(1300)
            _uiState.update { it.copy(isCompleting = false) }
            if (ok) {
                applyStatusAndAdvance(cur.id, "done", actualStart)
            } else {
                _uiState.update { it.copy(pendingTaskId = null, errorMessage = "刚才那一下没存上,再点一次就好。") }
                recomputeTide()
            }
        }
    }

    fun skipCurrent() {
        val cur = currentTask() ?: return
        if (uiState.value.pendingTaskId != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(pendingTaskId = cur.id, errorMessage = null) }
            runCatching { planRepository.markTaskSkipped(cur.id) }
                .onSuccess { applyStatusAndAdvance(cur.id, "skipped", null) }
                .onFailure { t -> _uiState.update { it.copy(pendingTaskId = null, errorMessage = t.friendly()) } }
        }
    }

    private fun applyStatusAndAdvance(id: String, status: String, actualStart: String?) {
        // 直接设置 actualStart：done 传入起点时刻；skip 传 null = 清空（不残留「开始过」）。
        val newTasks = uiState.value.tasks.map {
            if (it.id == id) it.copy(status = status, actualStart = actualStart) else it
        }
        val next = pickCurrent(newTasks)
        _uiState.update { it.copy(tasks = newTasks, currentTaskId = next?.id, pendingTaskId = null) }
        ensureCurrentStarted()
        recomputeTide()
        syncReminders() // done/skip 后:那条 ★ 不再 planned → 自动取消其提醒
    }

    // ── 潮水水位 ↔ 真实计划时间（§2）。每 30s 重算,Canvas 平滑补间 ──────
    private fun startTideTicker() {
        tideJob?.cancel()
        tideJob = viewModelScope.launch {
            while (true) {
                if (!uiState.value.isCompleting) recomputeTide()
                delay(30_000)
            }
        }
    }

    private fun recomputeTide() {
        if (uiState.value.isCompleting) return
        val cur = currentTask()
        if (cur == null) {
            _uiState.update { it.copy(tideLevel = 0.20f, tideProgress = 0f, tideDusk = false, remText = "", remNear = false) }
            return
        }
        val durMin = cur.plannedDuration ?: 30
        if (durMin <= 0) {
            _uiState.update { it.copy(tideLevel = 0.20f, tideProgress = 0f, tideDusk = false, remText = "还剩约 $durMin 分", remNear = false) }
            return
        }
        val nowSec = Clock.System.now().epochSeconds
        // v1.3：elapsed 从「本地变当前那刻」起算,不再用 plannedStart/库 actual_start（不做成压力倒计时）。
        val startSec = displayStartedAt.getOrPut(cur.id) { nowSec }
        val durSec = durMin * 60L
        val elapsed = (nowSec - startSec).coerceAtLeast(0L)
        if (elapsed >= durSec) {
            // 过点：在这件事上已花到计划时长；暮色 + .93 + 轻声（§2 / §1 不报警）。
            _uiState.update { it.copy(tideLevel = 0.93f, tideProgress = 1f, tideDusk = true, remText = "已过点 · 现在做,还是跳过?", remNear = true) }
            return
        }
        val p = (elapsed.toFloat() / durSec).coerceIn(0f, 1f)
        val remMin = ceil((durSec - elapsed) / 60.0).toInt().coerceAtLeast(0)
        val near = remMin in 1..10
        _uiState.update {
            it.copy(
                tideLevel = levelForP(p),
                tideProgress = p,
                tideDusk = false,
                remText = if (remMin <= 10) "快到时间了" else "还剩约 $remMin 分",
                remNear = near,
            )
        }
    }

    /** §2 分段水位曲线（与 demo 60 分钟曲线一致,按比例兼容任意时长）。 */
    private fun levelForP(p: Float): Float = when {
        p <= 0.5f -> 0.20f + (p / 0.5f) * 0.30f
        p <= 0.833f -> 0.50f + ((p - 0.5f) / 0.333f) * 0.28f
        else -> 0.78f + ((p - 0.833f) / 0.167f) * 0.10f
    }

    /**
     * v1.3：任务「变当前」只记**本地** displayStartedAt（潮水用）。
     * **不再** PATCH actual_start、**不再**伪造本地 actual_start —— 当前任务库内 actual_start 保持 null,
     * 这样语音「把当前这件挪到 X」时后端不会因脏 actual_start 拒绝(审计真凶)。
     */
    private fun ensureCurrentStarted() {
        val cur = currentTask() ?: return
        if (!displayStartedAt.containsKey(cur.id)) displayStartedAt[cur.id] = Clock.System.now().epochSeconds
    }

    private fun currentTask(): TaskRowDto? =
        uiState.value.tasks.firstOrNull { it.id == uiState.value.currentTaskId }

    private fun pickCurrent(tasks: List<TaskRowDto>): TaskRowDto? {
        // 按计划时间升序（无时间排最后），保证"最早一条"语义稳定。
        val planned = tasks.filter { it.status == STATUS_PLANNED }
            .sortedBy { it.plannedStart?.let { s -> runCatching { Instant.parse(s).epochSeconds }.getOrNull() } ?: Long.MAX_VALUE }
        if (planned.isEmpty()) return null
        val now = Clock.System.now()
        // 先处理**最早一条该开始却还没处理**的任务（含已过点）→ 过期任务先冒出来、按时间逐条提示，
        // 而不是埋在"最新那条"后面、等你做完才弹。都还没到点 → 取最早的未来一条。
        val due = planned.firstOrNull { t ->
            val start = t.plannedStart?.let { runCatching { Instant.parse(it) }.getOrNull() }
            start != null && start <= now
        }
        return due ?: planned.first()
    }

    // ── 块二：日内重排（说一句 → 前后对比 → 确认）─────────────────────
    /** 用户"点击"而非按住 → 给提示,别让它显得没反应。 */
    fun onReviseTap() {
        if (uiState.value.isReviseRecording || uiState.value.isProposing) return
        _uiState.update { it.copy(reviseHint = "按住这条说话,松手我就帮你改今天。") }
    }

    fun startReviseVoice() {
        if (uiState.value.isReviseRecording || uiState.value.isProposing || uiState.value.proposal != null) return
        voiceFinalText = ""
        latencyTracker.onTap()
        _uiState.update {
            it.copy(
                isReviseRecording = true, isReviseConnecting = true, isReviseFinalizing = false,
                reviseTranscript = "", reviseHint = null,
            )
        }
        voiceJob = viewModelScope.launch {
            voiceInputService.transcribe().collect { event ->
                when (event) {
                    is AsrEvent.SessionReady -> latencyTracker.onSession(event.provider, event.connectMode, event.prefetchHit)
                    AsrEvent.Connected -> {
                        latencyTracker.onOpen()
                        _uiState.update { it.copy(isReviseConnecting = false) }
                    }
                    is AsrEvent.Partial -> {
                        latencyTracker.onTranscript()
                        _uiState.update { it.copy(reviseTranscript = voiceFinalText + event.text) }
                    }
                    is AsrEvent.Final -> {
                        latencyTracker.onTranscript()
                        voiceFinalText += event.text
                        _uiState.update { it.copy(reviseTranscript = voiceFinalText) }
                    }
                    is AsrEvent.Completed -> {
                        latencyTracker.onFinal()
                        onReviseCaptureComplete()
                    }
                    is AsrEvent.Failed -> {
                        latencyTracker.onFailed(event.reason)
                        com.thinkandact.core.debug.FeDebug.raw(com.thinkandact.core.debug.FeDebug.Layer.BACKEND, "语音失败(原始): ${event.reason}")
                        endReviseCapture()
                        _uiState.update { it.copy(reviseHint = asrFailHint(event.reason)) }
                    }
                }
            }
        }
    }

    fun stopReviseVoice() {
        if (!uiState.value.isReviseRecording || uiState.value.isReviseFinalizing) return
        latencyTracker.onStop()
        _uiState.update { it.copy(isReviseRecording = false, isReviseConnecting = false, isReviseFinalizing = true) }
        viewModelScope.launch { voiceInputService.requestStopRecording() }
    }

    /** 上滑取消区松手:中止录音、整段丢弃、零改动(不 propose)。 */
    fun cancelReviseVoice() {
        voiceJob?.cancel(); voiceJob = null
        voiceFinalText = ""
        _uiState.update {
            it.copy(isReviseRecording = false, isReviseConnecting = false, isReviseFinalizing = false, reviseTranscript = "", reviseHint = null)
        }
    }

    private fun endReviseCapture() {
        latencyTracker.flush()
        voiceJob?.cancel()
        voiceJob = null
        _uiState.update { it.copy(isReviseRecording = false, isReviseConnecting = false, isReviseFinalizing = false) }
    }

    private fun onReviseCaptureComplete() {
        endReviseCapture()
        val text = uiState.value.reviseTranscript.trim()
        if (text.isBlank()) {
            _uiState.update { it.copy(reviseHint = "没听清,再说一次?") }
            return
        }
        lastInstruction = text
        propose(text)
    }

    private fun propose(instruction: String) {
        _uiState.update { it.copy(isProposing = true, reviseHint = null) }
        viewModelScope.launch {
            runCatching { planRepository.proposeRevision(instruction, uiState.value.tasks) }
                .onSuccess { proposal ->
                    val hasRevisionChange = proposal.revisions.any { it.change == "moved" || it.change == "dropped" }
                    val hasAdded = proposal.added.isNotEmpty()
                    // 调试探照灯:LLM 原话 → 返回结构 → FE 如何处理。
                    com.thinkandact.core.debug.FeDebug.llm(
                        userText = instruction,
                        rawStructure = "revisions=" + proposal.revisions.map { "${it.change}:${it.title}" } +
                            " added=" + proposal.added.map { it.title } + " warnings=" + proposal.warnings,
                        handling = if (!hasRevisionChange && !hasAdded) "无 moved/dropped/added → 提示用户" else "渲染前后对比",
                    )
                    // FE 不识别的操作类型(非 moved/dropped/unchanged)被丢:发声,别静默。
                    proposal.revisions.filter { it.change !in setOf("moved", "dropped", "unchanged") }.forEach {
                        com.thinkandact.core.debug.FeDebug.drop("plan-revise 操作 change=${it.change} (${it.title})", "FE 只渲染/应用 moved+dropped")
                    }
                    if (!hasRevisionChange && !hasAdded) {
                        // v1.3:照实展示后端 reject_reason / warnings;**不得**用固定「没听出」覆盖 BE 文案。
                        val beReason = proposal.rejectReason?.takeIf { it.isNotBlank() }
                            ?: proposal.warnings.firstOrNull()?.takeIf { it.isNotBlank() }
                        if (beReason == null) {
                            // 静默拒(v1.3 禁止):后端没给 reason → 才用兜底句,并记一条(催 BE)。
                            com.thinkandact.core.debug.FeDebug.reject("propose 无可应用操作且后端无 reject_reason/warnings(违反 v1.3)", instruction)
                        }
                        val hint = beReason ?: "这句我没听出要改什么,换种说法?"
                        _uiState.update { it.copy(isProposing = false, proposal = null, reviseHint = hint) }
                    } else {
                        _uiState.update { it.copy(isProposing = false, proposal = proposal) }
                    }
                }
                .onFailure { t ->
                    com.thinkandact.core.debug.FeDebug.raw(com.thinkandact.core.debug.FeDebug.Layer.NETWORK, "/plan-revise propose 异常: ${t.message ?: t}")
                    _uiState.update { it.copy(isProposing = false, reviseHint = reviseFailureHint(t)) }
                }
        }
    }

    /** 用户点「应用」→ 落库（仅 moved + dropped）。基线陈旧 → 自动重新 propose。 */
    fun applyProposal() {
        val proposal = uiState.value.proposal ?: return
        if (uiState.value.isApplying) return
        val applyList = proposal.revisions.mapNotNull { r ->
            when (r.change) {
                "moved" -> ApplyRevisionDto(
                    taskId = r.taskId, change = "moved",
                    after = ApplyAfterDto(
                        plannedStart = r.after.plannedStart,
                        plannedDuration = r.after.plannedDuration,
                        status = "planned",
                    ),
                )
                "dropped" -> ApplyRevisionDto(r.taskId, "dropped", ApplyAfterDto(status = "dropped"))
                "unchanged" -> null // 不变不落库,正常
                else -> {
                    com.thinkandact.core.debug.FeDebug.drop("apply 跳过 change=${r.change} (${r.title})", "FE 未识别该操作类型")
                    null
                }
            }
        }
        val addedList = proposal.added.map {
            ApplyAddedReviseDto(
                clientKey = it.clientKey,
                title = it.title,
                plannedStart = it.plannedStart,
                plannedDuration = it.plannedDuration,
                important = it.important,
            )
        }
        if (applyList.isEmpty() && addedList.isEmpty()) { _uiState.update { it.copy(proposal = null) }; return }
        _uiState.update { it.copy(isApplying = true) }
        viewModelScope.launch {
            runCatching { planRepository.applyRevision(proposal.revisionId, applyList, addedList) }
                .onSuccess { resp ->
                    val current = pickCurrent(resp.tasks)
                    _uiState.update {
                        it.copy(isApplying = false, proposal = null, tasks = resp.tasks, currentTaskId = current?.id, reviseHint = "好,按你说的调整了。")
                    }
                    ensureCurrentStarted()
                    recomputeTide()
                    syncReminders() // 重排后:★ 任务时间变了 → 提醒重排
                }
                .onFailure { t ->
                    if (t is PlanReviseStaleException) {
                        // 基线变了：丢弃旧提案,带同一句重新 propose 给用户看新的。
                        _uiState.update { it.copy(isApplying = false, proposal = null, reviseHint = t.message) }
                        refreshThenRepropose()
                    } else {
                        _uiState.update { it.copy(isApplying = false, reviseHint = reviseFailureHint(t)) }
                    }
                }
        }
    }

    /** 取消 = 零改动。 */
    fun cancelProposal() {
        _uiState.update { it.copy(proposal = null) }
    }

    fun dismissReviseHint() {
        _uiState.update { it.copy(reviseHint = null) }
    }

    private fun refreshThenRepropose() {
        viewModelScope.launch {
            runCatching { planRepository.fetchTodayTasks() }
                .onSuccess { tasks ->
                    val current = pickCurrent(tasks)
                    _uiState.update { it.copy(tasks = tasks, currentTaskId = current?.id) }
                    if (lastInstruction.isNotBlank()) propose(lastInstruction)
                }
        }
    }

    override fun onCleared() {
        voiceJob?.cancel()
        super.onCleared()
    }

    /** ASR 连接/录音失败的提示（区分配额/网络），不再一律「没接上」。 */
    private fun asrFailHint(reason: String): String = when {
        reason.contains("QUOTA", ignoreCase = true) || reason.contains("429") || reason.contains("次数用完") ->
            "今天的语音次数用完了,先手动点完成/跳过吧。"
        reason.contains("Unable to resolve host", ignoreCase = true) || reason.contains("timeout", ignoreCase = true) ->
            "现在网络不太稳,语音先歇会儿,先手动来。"
        else -> "语音暂时没接上,先手动来。"
    }

    private fun reviseFailureHint(t: Throwable): String {
        val raw = t.message.orEmpty()
        return when {
            raw.contains("404") || raw.contains("Not Found", ignoreCase = true) ->
                "日内重排还在上线中,先用执行就好。"
            raw.contains("Unable to resolve host", ignoreCase = true) || raw.contains("timeout", ignoreCase = true) ->
                "现在网络不太稳,等会儿再调。"
            raw.contains("FAIR_USE", ignoreCase = true) || raw.contains("429") ->
                "今天的次数用完了,先按现在的来。"
            else -> "没排成功,先按现在的来。"
        }
    }

    private fun Throwable.friendly(): String {
        val raw = message.orEmpty()
        return when {
            raw.contains("Unable to resolve host", ignoreCase = true) -> "现在连不上网,等会儿再试。"
            raw.contains("timeout", ignoreCase = true) -> "这次等得有点久,再试一次。"
            else -> "刚才那一下没存上,再点一次就好。"
        }
    }

    private companion object {
        const val STATUS_PLANNED = "planned"
    }
}

data class ExecutionUiState(
    val isLoading: Boolean = false,
    val tasks: List<TaskRowDto> = emptyList(),
    val currentTaskId: String? = null,
    val pendingTaskId: String? = null,
    val errorMessage: String? = null,
    // 块二 重排
    val isReviseRecording: Boolean = false,
    val isReviseConnecting: Boolean = false,
    val isReviseFinalizing: Boolean = false,
    val reviseTranscript: String = "",
    val isProposing: Boolean = false,
    val proposal: PlanReviseResponse? = null,
    val isApplying: Boolean = false,
    val reviseHint: String? = null,
    // 潮水动效（§2/§3）+ 中心星环线性进度 p（§4.6 彗星角度 = p×360）
    val tideLevel: Float = 0.20f,
    val tideProgress: Float = 0f,
    val tideDusk: Boolean = false,
    val remText: String = "",
    val remNear: Boolean = false,
    val isCompleting: Boolean = false,
) {
    val currentTask: TaskRowDto? get() = tasks.firstOrNull { it.id == currentTaskId }
    /** 进度 = 已处理(完成 + 跳过 + 不做了)/ 总数。 */
    val progressCount: Int get() = tasks.count { it.status != "planned" }
    val totalCount: Int get() = tasks.size
    val allCleared: Boolean get() = tasks.isNotEmpty() && currentTaskId == null
}
