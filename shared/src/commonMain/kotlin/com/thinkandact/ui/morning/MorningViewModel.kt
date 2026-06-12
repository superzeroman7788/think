package com.thinkandact.ui.morning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thinkandact.data.PlanRepository
import com.thinkandact.data.remote.PlanGenerateResponseDto
import com.thinkandact.data.remote.PlanTaskDto
import com.thinkandact.voice.AsrEvent
import com.thinkandact.voice.VoiceInputService
import com.thinkandact.voice.VoiceLatencyTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.datetime.offsetIn
import kotlinx.datetime.toInstant
import kotlin.math.abs

class MorningViewModel(
    private val planRepository: PlanRepository,
    private val voiceInputService: VoiceInputService,
    private val inboxRepository: com.thinkandact.data.InboxRepository,
    private val reminderScheduler: com.thinkandact.reminders.ReminderScheduler,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MorningUiState())
    val uiState: StateFlow<MorningUiState> = _uiState.asStateFlow()

    /** 实时音量(0–1)给「按住即时反馈」波形复用。 */
    val amp get() = voiceInputService.amp

    private var voiceJob: Job? = null
    private var generateJob: Job? = null
    /** 录音前输入框已有的文字；转写实时拼在它后面。 */
    private var voiceBaseText: String = ""
    /** 已定稿的转写句子累计。 */
    private var voiceFinalText: String = ""
    private var regenerateAfterVoice = false
    /** 流畅度埋点（§0）：每次录入打 5 个点，导出 TTFW 等，按 provider 聚合做 A/B。 */
    private val latencyTracker = VoiceLatencyTracker()

    init {
        prepareSession()
        loadRecall()
        checkTodayPlan()
    }

    /** B6-03:开屏查今天是否已有确认计划。有 → 硬门:不生成,引导去改今天。 */
    fun checkTodayPlan() {
        viewModelScope.launch {
            val has = runCatching { planRepository.hasTodayPlan() }.getOrDefault(false)
            _uiState.update { it.copy(todayHasPlan = has) }
        }
    }

    // ── 早上浮现（§三）：到日子了的收件箱条目召回 ─────────────────────────
    /** pending 且 due ≤ 今天的条目，morning 顶部召回卡。 */
    fun loadRecall() {
        viewModelScope.launch {
            val today = kotlinx.datetime.Clock.System.todayIn(kotlinx.datetime.TimeZone.currentSystemDefault())
            runCatching { inboxRepository.list() }
                .onSuccess { items ->
                    val recall = items.filter { it.status == "pending" && it.dueDate?.let { d -> runCatching { kotlinx.datetime.LocalDate.parse(d) <= today }.getOrDefault(false) } == true }
                    _uiState.update { it.copy(recallItems = recall) }
                }
        }
    }

    /** 召回卡「加进今天」→ 后端生成真任务；成功后从召回里移除。 */
    fun addRecallToday(id: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(recallPendingId = id) }
            runCatching { inboxRepository.addToday(id) }
                .onSuccess { _uiState.update { st -> st.copy(recallPendingId = null, recallItems = st.recallItems.filterNot { it.id == id }) } }
                .onFailure { t -> _uiState.update { it.copy(recallPendingId = null, errorMessage = t.message ?: "加进今天没成功。") } }
        }
    }

    /** 召回卡「先不」→ dismiss，安静收起，永不自动再提。 */
    fun dismissRecall(id: String) {
        viewModelScope.launch {
            _uiState.update { st -> st.copy(recallItems = st.recallItems.filterNot { it.id == id }) } // 乐观移除
            runCatching { inboxRepository.dismiss(id) }
        }
    }

    fun toggleRecallExpanded() { _uiState.update { it.copy(recallExpanded = !it.recallExpanded) } }

    fun onInputChange(value: String) {
        _uiState.update { it.copy(rawInput = value, errorMessage = null) }
    }

    // ── 语音输入（FE-ASR-1）─────────────────────────────────────────────

    /** UI 确认已获麦克风权限后调用：开始录音 + 流式转写。 */
    fun startVoiceInput() {
        if (uiState.value.isRecording || uiState.value.isVoiceFinalizing) return
        voiceBaseText = uiState.value.rawInput.let { if (it.isBlank()) "" else it.trimEnd() + " " }
        voiceFinalText = ""
        latencyTracker.onTap() // 埋点 T_tap（§0）：点麦即录入起点。
        // 进入「准备中」：按下立刻给反馈，连上后再切到「在听」。
        _uiState.update {
            it.copy(isRecording = true, isVoiceConnecting = true, voiceSpokenText = "", voiceHint = null, errorMessage = null)
        }

        voiceJob = viewModelScope.launch {
            voiceInputService.transcribe().collect { event ->
                when (event) {
                    is AsrEvent.SessionReady ->
                        latencyTracker.onSession(event.provider, event.connectMode, event.prefetchHit) // T_session
                    AsrEvent.Connected -> {
                        latencyTracker.onOpen() // T_open
                        _uiState.update { it.copy(isVoiceConnecting = false, voiceHint = null) }
                    }
                    is AsrEvent.Partial -> {
                        latencyTracker.onTranscript() // T_first（首次）+ 顺滑度
                        val live = voiceFinalText + event.text
                        _uiState.update { it.copy(rawInput = voiceBaseText + live, voiceSpokenText = live) }
                    }
                    is AsrEvent.Final -> {
                        latencyTracker.onTranscript()
                        voiceFinalText += event.text
                        _uiState.update { it.copy(rawInput = voiceBaseText + voiceFinalText, voiceSpokenText = voiceFinalText) }
                    }
                    is AsrEvent.Completed -> {
                        latencyTracker.onFinal() // T_final
                        val spoke = voiceFinalText.isNotBlank()
                        if (regenerateAfterVoice && spoke) {
                            regenerateAfterVoice = false
                            finishVoiceSession()
                            generatePlan()
                        } else {
                            // 没说话就松手 → 不要白白重排(bug:空语音也跑去 loading)。给个轻提示。
                            if (regenerateAfterVoice && !spoke) {
                                regenerateAfterVoice = false
                                _uiState.update { it.copy(voiceHint = "没听清,再说一次？") }
                            }
                            finishVoiceSession()
                        }
                    }
                    is AsrEvent.Failed -> {
                        latencyTracker.onFailed(event.reason)
                        com.thinkandact.core.debug.FeDebug.raw(com.thinkandact.core.debug.FeDebug.Layer.BACKEND, "语音失败(原始): ${event.reason}")
                        regenerateAfterVoice = false
                        println("voice asr failed: ${event.reason}")
                        _uiState.update {
                            it.copy(
                                voiceHint = voiceFailureHint(event.reason),
                            )
                        }
                        finishVoiceSession()
                    }
                }
            }
        }
    }

    /** 松手：停采 + 等 final，不立刻 cancel 识别流。 */
    fun stopVoiceInput() {
        if (!uiState.value.isRecording || uiState.value.isVoiceFinalizing) {
            regenerateAfterVoice = false // BUG-07：提前 return 也复位,别把"重排"标志残留到下一段普通语音
            return
        }
        latencyTracker.onStop()
        _uiState.update {
            it.copy(isRecording = false, isVoiceConnecting = false, isVoiceFinalizing = true)
        }
        viewModelScope.launch { voiceInputService.requestStopRecording() }
    }

    private fun finishVoiceSession() {
        latencyTracker.flush()
        voiceJob?.cancel()
        voiceJob = null
        _uiState.update {
            it.copy(isRecording = false, isVoiceConnecting = false, isVoiceFinalizing = false)
        }
    }

    /**
     * 在计划页「按住说话调整」松开时调用：停止录音，再用当前输入
     * （原始口述 + 这次的调整口述）重新生成计划。没有可用输入则不动。
     */
    fun stopVoiceInputAndRegenerate() {
        regenerateAfterVoice = true
        stopVoiceInput()
    }

    /** F7-03 计划页轻点打字调整:把这句并进 rawInput 再重排(与"按住说话调整"等价)。 */
    fun regenerateFromText(text: String) {
        val t = text.trim()
        if (t.isBlank() || uiState.value.isLoading || uiState.value.isRecording) return
        val base = uiState.value.rawInput.trimEnd()
        _uiState.update { it.copy(rawInput = if (base.isBlank()) t else "$base\n$t") }
        generatePlan()
    }

    /** 上滑取消区松手:中止录音、不重排、不入框，整段丢弃。 */
    fun cancelVoiceInput() {
        regenerateAfterVoice = false
        voiceJob?.cancel(); voiceJob = null
        // 回退输入框到录音前的样子（去掉本次实时拼接）。
        _uiState.update {
            it.copy(
                isRecording = false, isVoiceConnecting = false, isVoiceFinalizing = false,
                rawInput = voiceBaseText.trimEnd(), voiceSpokenText = "", voiceHint = null,
            )
        }
    }

    /** 拒绝麦克风权限 → 优雅退文字。 */
    fun onVoicePermissionDenied() {
        _uiState.update {
            it.copy(isRecording = false, isVoiceConnecting = false, voiceHint = "没拿到麦克风权限,先用打字吧。")
        }
    }

    fun dismissVoiceHint() {
        _uiState.update { it.copy(voiceHint = null) }
    }

    override fun onCleared() {
        voiceJob?.cancel()
        generateJob?.cancel()
        super.onCleared()
    }

    fun generatePlan() {
        val input = uiState.value.rawInput.trim()
        if (input.isEmpty()) return
        if (uiState.value.isLoading) return // BUG-06 防抖：生成中再点忽略,别双倍消耗 AI / 竞态覆盖
        // B6-03 硬门:今天已有确认计划 → 禁止再生成(防 BUG-02/N-05 根场景)。
        if (uiState.value.todayHasPlan) {
            _uiState.update { it.copy(errorMessage = "今天已经有计划了,去「今天」改吧。") }
            return
        }
        generateJob?.cancel()
        generateJob = viewModelScope.launch {
            val thisJob = coroutineContext[Job]!!
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val plan = planRepository.generatePlan(input)
                val merged = mergeManualTasks(plan.toEditableTasks(), uiState.value.editableTasks)
                _uiState.update { state ->
                    state.copy(
                        proposal = plan,
                        editableTasks = merged,
                        isConfirmed = false,
                        saveErrorMessage = null,
                        errorMessage = null,
                        addTaskMessage = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                com.thinkandact.core.debug.FeDebug.raw(
                    com.thinkandact.core.debug.FeDebug.Layer.NETWORK,
                    "/plan-generate 异常: ${t.message ?: t}",
                )
                _uiState.update { it.copy(errorMessage = t.friendlyMessage()) }
            } finally {
                if (generateJob === thisJob) {
                    _uiState.update { it.copy(isLoading = false) }
                    generateJob = null
                }
            }
        }
    }

    /** 生成中用户点「取消」→ 中止请求并回到输入/草案页。 */
    fun cancelGeneratePlan() {
        generateJob?.cancel()
        generateJob = null
        _uiState.update { it.copy(isLoading = false) }
    }

    fun retry() = generatePlan()

    fun confirmPlan() {
        if (uiState.value.proposal == null || uiState.value.isSavingPlan) return
        viewModelScope.launch {
            // BUG-02：今天已有执行记录(已完成/已跳过)时,先强提醒,别直接覆盖。
            val hasExecuted = runCatching { planRepository.hasExecutedTasksToday() }.getOrDefault(false)
            if (hasExecuted) {
                _uiState.update { it.copy(showOverwriteWarning = true) }
            } else {
                doConfirm()
            }
        }
    }

    /** 强提醒里点「确定」→ 真正落库(已执行的会被保留,见 BUG-02)。 */
    fun confirmOverwrite() {
        _uiState.update { it.copy(showOverwriteWarning = false) }
        viewModelScope.launch { doConfirm() }
    }

    fun dismissOverwriteWarning() {
        _uiState.update { it.copy(showOverwriteWarning = false) }
    }

    private suspend fun doConfirm() {
        _uiState.update { it.copy(isSavingPlan = true, saveErrorMessage = null) }
        runCatching { planRepository.confirmTodayTasks(uiState.value.editableTasks.map { it.task }) }
            .onSuccess { insertedRows ->
                _uiState.update { it.copy(isSavingPlan = false, isConfirmed = true, saveErrorMessage = null) }
                // N-02:确认成功就地排 ★ 系统提醒——「早上确认→锁屏出门」不进执行屏也要响。
                runCatching {
                    val now = Clock.System.now().toEpochMilliseconds()
                    reminderScheduler.sync(com.thinkandact.reminders.ReminderPlanner.fromTasks(insertedRows, now))
                }
            }
            .onFailure { throwable ->
                println("confirmPlan failed: ${throwable.message}")
                _uiState.update { it.copy(isSavingPlan = false, saveErrorMessage = throwable.friendlyMessage(action = "save")) }
            }
    }

    /**
     * N-04/F-13:含 ★ 计划但通知权限被拒 → 弹门控,**先不确认**(不导航到执行屏),
     * 由用户选「去设置」或「仍然继续」。修复旧版「设了 hint 又立刻 confirm 导航」的竞态。
     */
    fun onNotifPermissionDenied() {
        _uiState.update { it.copy(notifHint = "想按时叫你做 ★ 的事,需要通知权限——可以去设置里打开。") }
    }

    fun dismissNotifHint() {
        _uiState.update { it.copy(notifHint = null) }
    }

    /** F-13:点「去设置」→ 真跳系统通知设置页(不确认、不导航);用户开完返回再点确认。 */
    fun openNotifSettings() {
        reminderScheduler.openNotificationSettings()
        dismissNotifHint()
    }

    /** 门控里点「仍然继续」→ 关门控并照常确认(权限是尽力而为,不阻断)。 */
    fun confirmAnyway() {
        dismissNotifHint()
        confirmPlan()
    }

    /** N-04:本次确认的计划里是否有 ★ 任务(有才值得先要通知权限)。 */
    fun hasImportantTasks(): Boolean = uiState.value.editableTasks.any { it.task.important }

    /** 确认并跳「今天」后调用:清空本屏，下次进早上屏是干净的入口。 */
    fun clearAfterConfirm() {
        _uiState.update {
            it.copy(
                proposal = null, editableTasks = emptyList(), isConfirmed = false,
                rawInput = "", voiceSpokenText = "", voiceHint = null, saveErrorMessage = null,
            )
        }
    }

    fun deleteTask(taskId: String) {
        _uiState.update { state ->
            state.copy(editableTasks = state.editableTasks.filterNot { it.id == taskId }, saveErrorMessage = null)
        }
    }

    /** 软建议「加入」→ 升级为真任务(status=planned);本地同步,confirm 时按 planned 落库。 */
    fun acceptSuggestion(taskId: String) {
        _uiState.update { state ->
            state.copy(
                editableTasks = state.editableTasks.map { item ->
                    if (item.id == taskId) item.copy(task = item.task.copy(status = "planned")) else item
                },
                saveErrorMessage = null,
            )
        }
    }

    fun toggleImportant(taskId: String) {
        _uiState.update { state ->
            state.copy(
                editableTasks = state.editableTasks.map { item ->
                    if (item.id == taskId) item.copy(task = item.task.copy(important = !item.task.important)) else item
                },
                saveErrorMessage = null
            )
        }
    }

    fun updateTaskTime(taskId: String, hour: Int, minute: Int) {
        _uiState.update { state ->
            state.copy(
                editableTasks = state.editableTasks.map { item ->
                    if (item.id == taskId) item.copy(task = item.task.copy(plannedStart = buildTodayIso(hour, minute))) else item
                },
                saveErrorMessage = null
            )
        }
    }

    /** @return false = 未加入(已写 [MorningUiState.addTaskMessage])。 */
    fun addTask(title: String, hour: Int, minute: Int): Boolean {
        val cleanedTitle = title.trim()
        if (cleanedTitle.isEmpty()) {
            _uiState.update { it.copy(addTaskMessage = "写个名字吧。") }
            return false
        }
        if (uiState.value.proposal == null) {
            _uiState.update { it.copy(addTaskMessage = "先生成今天的计划,再加一项。") }
            return false
        }
        if (uiState.value.isConfirmed) {
            _uiState.update { it.copy(addTaskMessage = "今天已经确认过了,要加的话先回早上屏改。") }
            return false
        }

        val plannedStart = buildTodayIso(hour, minute)
        val timeOfDay = inferTimeOfDay(hour)
        val id = "manual_${Clock.System.now().toEpochMilliseconds()}"
        _uiState.update { state ->
            state.copy(
                editableTasks = state.editableTasks + EditablePlanTask(
                    id = id,
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
                saveErrorMessage = null,
                addTaskMessage = "已加上「$cleanedTitle」",
            )
        }
        return true
    }

    fun dismissAddTaskMessage() {
        _uiState.update { it.copy(addTaskMessage = null) }
    }

    private fun prepareSession() {
        viewModelScope.launch {
            _uiState.update { it.copy(isPreparingSession = true, errorMessage = null) }
            runCatching { planRepository.ensureSession() }
                .onSuccess {
                    _uiState.update { it.copy(isPreparingSession = false, errorMessage = null) }
                    viewModelScope.launch { voiceInputService.warmSessionCache() }
                }
                .onFailure { throwable ->
                    println("prepareSession failed: ${throwable.message}")
                    _uiState.update { it.copy(isPreparingSession = false, errorMessage = throwable.friendlyMessage()) }
                }
        }
    }

    private fun voiceFailureHint(reason: String): String = when {
        reason.contains("ASR_QUOTA_EXCEEDED", ignoreCase = true) ||
            reason.contains("429") ||
            reason.contains("QUOTA", ignoreCase = true) ||
            reason.contains("语音次数用完了") ->
            "今天的语音次数用完了,先用打字描述今天吧。"
        reason.contains("ASR_NOT_CONFIGURED", ignoreCase = true) ->
            "语音服务还没准备好,请先用文字输入。"
        reason.contains("Unable to resolve host", ignoreCase = true) ||
            reason.contains("timeout", ignoreCase = true) ->
            "现在网络不太稳,语音先歇会儿,可以打字。"
        reason.contains("asr-session HTTP", ignoreCase = true) ||
            reason.contains("asr error code", ignoreCase = true) ->
            "语音暂时没接上,先用打字也行。"
        else -> reason.ifBlank { "语音暂时没接上,先用打字也行。" }
    }

    private fun Throwable.friendlyMessage(action: String = "generate"): String {
        val raw = message.orEmpty()
        if (raw.contains("NEEDS_CLARIFICATION", ignoreCase = true)) {
            Regex(""""message"\s*:\s*"((?:\\.|[^"\\])*)"""").find(raw)?.groupValues?.get(1)
                ?.let { return it.replace("\\\"", "\"").replace("\\\\", "\\") }
        }
        return when {
            raw.contains("Unable to resolve host", ignoreCase = true) -> "现在好像连不上网。等网络回来,我再帮你排今天。"
            raw.contains("timeout", ignoreCase = true) -> "这次等得有点久。可以再试一次。"
            raw.contains("FAIR_USE_EXCEEDED", ignoreCase = true) -> "今天的 AI 次数先用完了。"
            raw.contains("UNAUTHORIZED", ignoreCase = true) -> "登录状态有点卡住了,再试一次我会重新准备。"
            action == "save" -> "刚才保存今天计划时卡了一下。计划还在,可以再试一次。"
            else -> "刚才生成计划时卡了一下。再试一次就好。"
        }
    }
}

data class MorningUiState(
    val rawInput: String = "",
    /** B6-03:今天已有确认计划 → Morning 不展示生成流,改提示去「改今天」。 */
    val todayHasPlan: Boolean = false,
    val isPreparingSession: Boolean = false,
    val isLoading: Boolean = false,
    val isSavingPlan: Boolean = false,
    val isConfirmed: Boolean = false,
    val errorMessage: String? = null,
    val saveErrorMessage: String? = null,
    val proposal: PlanGenerateResponseDto? = null,
    val editableTasks: List<EditablePlanTask> = emptyList(),
    val isRecording: Boolean = false,
    /** 已按下、正在连接 ASR（「准备中」），连上后置 false 切到「在听」。 */
    val isVoiceConnecting: Boolean = false,
    /** 松手后等腾讯 final（「整理中…」）。 */
    val isVoiceFinalizing: Boolean = false,
    /** 本次按住正在说的实时文字（用于计划页录音预览气泡）。 */
    val voiceSpokenText: String = "",
    val voiceHint: String? = null,
    /** 「加一项」成功/失败反馈(N-04)。 */
    val addTaskMessage: String? = null,
    /** BUG-02：今天已有执行记录时,重确认前的强提醒(避免误覆盖)。 */
    val showOverwriteWarning: Boolean = false,
    /** 第四批 N-04:通知权限被拒后的轻引导(一次,可去设置)。 */
    val notifHint: String? = null,
    /** 早上浮现（§三）：到日子了的收件箱召回条目。 */
    val recallItems: List<com.thinkandact.data.remote.InboxItemDto> = emptyList(),
    val recallExpanded: Boolean = false,
    val recallPendingId: String? = null,
)

data class EditablePlanTask(val id: String, val task: PlanTaskDto)

/** 语音重生成计划时保留用户手动加的项(N-04:禁止静默丢)。 */
private fun mergeManualTasks(
    fromApi: List<EditablePlanTask>,
    existing: List<EditablePlanTask>,
): List<EditablePlanTask> {
    val manual = existing.filter { it.id.startsWith("manual_") }
    if (manual.isEmpty()) return fromApi
    val apiIds = fromApi.map { it.id }.toSet()
    return fromApi + manual.filter { it.id !in apiIds }
}

private fun PlanGenerateResponseDto.toEditableTasks(): List<EditablePlanTask> {
    return tasks.mapIndexed { index, task -> EditablePlanTask(id = "task_$index", task = task) } +
        suggestionTasks.mapIndexed { index, task ->
            // v1.4 软建议:固定 source=ai_suggestion + status=suggested(未接受),点「加入」才升 planned。
            EditablePlanTask(id = "suggestion_$index", task = task.copy(source = "ai_suggestion", status = "suggested"))
        }
}

/** BUG-12：统一走 todayLocalIso（本地偏移，与 plan-generate / 完整计划页一致）。 */
private fun buildTodayIso(hour: Int, minute: Int): String = com.thinkandact.core.time.todayLocalIso(hour, minute)

private fun inferTimeOfDay(hour: Int): String = when {
    hour < 12 -> "morning"
    hour < 14 -> "midday"
    hour < 18 -> "afternoon"
    else -> "evening"
}
