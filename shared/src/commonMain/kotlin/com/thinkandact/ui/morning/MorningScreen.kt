package com.thinkandact.ui.morning

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.scale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.thinkandact.core.time.formatTimeRange
import com.thinkandact.ui.common.SectionLabel
import com.thinkandact.ui.common.TnaTopBar
import com.thinkandact.ui.common.TnaTopBarAction
import com.thinkandact.ui.common.TnaButton
import com.thinkandact.ui.common.TnaButtonStyle
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.thinkandact.ui.common.PressFeedbackOverlay
import com.thinkandact.ui.common.VoiceMicButton
import com.thinkandact.ui.theme.TnaColors
import com.thinkandact.ui.theme.TnaShapes
import com.thinkandact.ui.theme.TnaTypography
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.thinkandact.voice.rememberMicPermissionController
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun MorningScreen(
    onOpenRoutines: () -> Unit,
    onOpenExecution: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    /** 确认计划后进入「今天」——与顶部「今天→」peek 区分:确认后 morning 不再留在返回栈(确认完无需再回)。 */
    onPlanConfirmed: () -> Unit = onOpenExecution,
    viewModel: MorningViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var editingTimeTask by remember { mutableStateOf<EditablePlanTask?>(null) }
    var showAddTask by remember { mutableStateOf(false) }
    var showAdjustType by remember { mutableStateOf(false) } // F7-03 计划页轻点打字调整

    // 确认计划后直接进「今天」(bug:原来停在一个无意义的"已确认"页);morning 退出返回栈。
    LaunchedEffect(state.isConfirmed) {
        if (state.isConfirmed) {
            onPlanConfirmed()
            viewModel.clearAfterConfirm()
        }
    }

    // 第四批 N-04:确认计划(要排 ★ 提醒)前请求通知权限,不再只绑在执行屏首进。
    // 被拒不阻断确认,只轻引导一次(见 notifHint)。
    val notifPerm = com.thinkandact.reminders.rememberNotificationPermission()
    val confirmScope = rememberCoroutineScope()
    val onConfirmPlan: () -> Unit = {
        confirmScope.launch {
            // F-13:含 ★ 且权限被拒 → 弹门控,**不**继续确认/导航(避免「去设置」时已被带进执行屏)。
            if (viewModel.hasImportantTasks() && !notifPerm.request()) {
                viewModel.onNotifPermissionDenied()
            } else {
                viewModel.confirmPlan()
            }
        }
    }

    val micController = rememberMicPermissionController()
    // 按住说话：按下→申请权限并开始录音；松开→停止。
    val onMicPressStart: suspend () -> Unit = {
        // 整理中（松手等 final）期间忽略再次按下，避免打断收尾。
        if (!state.isVoiceFinalizing) {
            if (micController.request()) viewModel.startVoiceInput() else viewModel.onVoicePermissionDenied()
        }
    }
    val onMicPressEnd: () -> Unit = { viewModel.stopVoiceInput() }
    // 调整计划：松开后停止录音并用「原始输入+口述调整」重新生成。
    val onAdjustPressEnd: () -> Unit = { viewModel.stopVoiceInputAndRegenerate() }

    // 按住即时反馈(秒应):纯本地、pointerdown 即亮。
    val amp by viewModel.amp.collectAsState()
    var fbVisible by remember { mutableStateOf(false) }
    var fbArmed by remember { mutableStateOf(false) }
    var barCenterY by remember { mutableStateOf(0f) } // 量当前语音条中心 → 反馈层就地盖住它(首页 bar 在中间)
    val onFbDown: () -> Unit = { fbVisible = true }
    val onFbUp: () -> Unit = { fbVisible = false; fbArmed = false }
    val onFbArmed: (Boolean) -> Unit = { fbArmed = it }

    Box(modifier = Modifier.fillMaxSize().background(TnaColors.Background)) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // 通用顶栏(改版):去字标,四个同款圆形矢量图标按钮,右对齐;「今天→」实底突出。
        Spacer(modifier = Modifier.height(10.dp))
        TnaTopBar(
            actions = listOf(
                TnaTopBarAction(Icons.Rounded.Settings, "设置", onOpenSettings),
                TnaTopBarAction(Icons.Rounded.BarChart, "过去 7 天", onOpenHistory),
                TnaTopBarAction(Icons.Rounded.CalendarMonth, "我的日常", onOpenRoutines),
                TnaTopBarAction(Icons.AutoMirrored.Rounded.ArrowForward, "今天", onOpenExecution, primary = true),
            ),
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        // 早上浮现（§三）：到日子了的收件箱召回卡。
        if (state.recallItems.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                InboxRecallCards(
                    items = state.recallItems,
                    expanded = state.recallExpanded,
                    pendingId = state.recallPendingId,
                    onAdd = viewModel::addRecallToday,
                    onDismiss = viewModel::dismissRecall,
                    onToggleExpand = viewModel::toggleRecallExpanded,
                )
            }
        }

        when {
            // 生成 / 重新生成中：优先显示加载提示。
            state.isLoading -> Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            ) {
                LoadingContent(isAdjusting = state.proposal != null, onCancel = viewModel::cancelGeneratePlan)
            }
            state.proposal != null -> Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            ) {
                ProposalContent(
                    tasks = state.editableTasks,
                    aiComment = state.proposal!!.aiComment,
                    isConfirmed = state.isConfirmed,
                    saveErrorMessage = state.saveErrorMessage,
                    addTaskMessage = state.addTaskMessage,
                    onDismissAddTaskMessage = viewModel::dismissAddTaskMessage,
                    onDeleteTask = viewModel::deleteTask,
                    onToggleImportant = viewModel::toggleImportant,
                    onEditTime = { task -> editingTimeTask = task },
                    onAddTask = { showAddTask = true },
                    onRetrySave = onConfirmPlan,
                    onAcceptSuggestion = viewModel::acceptSuggestion,
                )
                Spacer(modifier = Modifier.height(18.dp))
            }
            // B6-03 硬门:今天已有确认计划 → 不展示生成流,引导去「改今天」。
            state.todayHasPlan -> PlanExistsGate(modifier = Modifier.weight(1f), onGoToToday = onOpenExecution)
            // 输入页(改版):输入框=主角占满,标题/副标在上,语音条+生成今天钉底。
            else -> EntryContent(
                modifier = Modifier.weight(1f),
                rawInput = state.rawInput,
                isPreparingSession = state.isPreparingSession,
                errorMessage = state.errorMessage,
                isRecording = state.isRecording,
                isVoiceConnecting = state.isVoiceConnecting,
                isVoiceFinalizing = state.isVoiceFinalizing,
                voiceSpokenText = state.voiceSpokenText,
                voiceHint = state.voiceHint,
                onInputChange = viewModel::onInputChange,
                onGenerate = viewModel::generatePlan,
                onRetry = viewModel::retry,
                onMicPressStart = onMicPressStart,
                onMicPressEnd = onMicPressEnd,
                onDismissVoiceHint = viewModel::dismissVoiceHint,
                onPressDown = onFbDown,
                onPressUp = onFbUp,
                onCancel = viewModel::cancelVoiceInput,
                onCancelArmedChange = onFbArmed,
                onBarCenter = { barCenterY = it },
            )
        }

        if (state.proposal != null && !state.isLoading) {
            ProposalFooter(
                isSaving = state.isSavingPlan,
                isConfirmed = state.isConfirmed,
                isRecording = state.isRecording,
                isVoiceConnecting = state.isVoiceConnecting,
                isVoiceFinalizing = state.isVoiceFinalizing,
                voiceSpokenText = state.voiceSpokenText,
                onMicPressStart = onMicPressStart,
                onAdjustPressEnd = onAdjustPressEnd,
                onAdjustTap = { showAdjustType = true },
                onLooksGood = onConfirmPlan,
                onPressDown = onFbDown,
                onPressUp = onFbUp,
                onCancel = viewModel::cancelVoiceInput,
                onCancelArmedChange = onFbArmed,
                onBarCenter = { barCenterY = it },
            )
        }
    }
        // 即时反馈覆盖层(就地盖住当前语音条)
        PressFeedbackOverlay(visible = fbVisible, amp = amp, cancelArmed = fbArmed, anchorCenterYpx = barCenterY)
    }

    editingTimeTask?.let { item ->
        TaskTimeDialog(
            title = item.task.title,
            initialMinutesOfDay = item.task.plannedStart.toMinutesOfDayOrDefault(),
            onDismiss = { editingTimeTask = null },
            onConfirm = { hour, minute ->
                viewModel.updateTaskTime(item.id, hour, minute)
                editingTimeTask = null
            }
        )
    }

    if (showAddTask) {
        AddTaskDialog(
            onDismiss = { showAddTask = false },
            onConfirm = { title, hour, minute ->
                if (viewModel.addTask(title, hour, minute)) showAddTask = false
            }
        )
    }

    if (showAdjustType) {
        com.thinkandact.ui.common.TypeInputDialog(
            title = "想调整？打字告诉我",
            placeholder = "比如「把跑步换到晚上」「上午多留点时间」",
            onDismiss = { showAdjustType = false },
            onSubmit = { showAdjustType = false; viewModel.regenerateFromText(it) },
            submitLabel = "重新生成",
        )
    }

    // BUG-02：今天已动过(有完成/跳过) → 重确认前强提醒。
    if (state.showOverwriteWarning) {
        TnaDialog(onDismiss = viewModel::dismissOverwriteWarning) {
            Text("今天已经动过了", style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold))
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "今天已经有完成/跳过的记录。重新确认会替换还没做的安排——已完成、已跳过的都会保留。要继续吗？",
                style = TnaTypography.AiVoice.copy(color = TnaColors.InkSoft),
            )
            DialogActions(
                confirmText = "继续确认",
                onDismiss = viewModel::dismissOverwriteWarning,
                onConfirm = viewModel::confirmOverwrite,
            )
        }
    }

    // N-04/F-13:通知权限被拒门控——「去设置」真跳系统通知设置,「仍然继续」照常确认。
    state.notifHint?.let { hint ->
        TnaDialog(onDismiss = viewModel::confirmAnyway) {
            Text("提醒可能响不了", style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold))
            Spacer(modifier = Modifier.height(10.dp))
            Text(hint, style = TnaTypography.AiVoice.copy(color = TnaColors.InkSoft))
            Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TnaButton("仍然继续", onClick = viewModel::confirmAnyway, style = TnaButtonStyle.Secondary, modifier = Modifier.weight(1f))
                TnaButton("去设置", onClick = viewModel::openNotifSettings, style = TnaButtonStyle.Primary, modifier = Modifier.weight(1f))
            }
        }
    }
}


/** 早上浮现（§三）：到日子了的收件箱召回卡。最多 3 条，余下折叠。 */
@Composable
private fun InboxRecallCards(
    items: List<com.thinkandact.data.remote.InboxItemDto>,
    expanded: Boolean,
    pendingId: String?,
    onAdd: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onToggleExpand: () -> Unit,
) {
    val shown = if (expanded) items else items.take(3)
    val rest = items.size - shown.size
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { item ->
            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(TnaColors.AccentSoft.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0xFFE6C5AC), RoundedCornerShape(16.dp))
                    .padding(14.dp),
            ) {
                Text(recallPrefix(item.createdAt) + "记过", style = TnaTypography.Mono.copy(color = TnaColors.AccentDeep))
                Spacer(Modifier.height(4.dp))
                Text(item.text, style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(2.dp))
                Text("今天" + com.thinkandact.ui.inbox.partSuffix(item.duePart), style = TnaTypography.Mono.copy(color = TnaColors.Muted))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (pendingId == item.id) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = TnaColors.AccentDeep, strokeWidth = 2.dp)
                    } else {
                        Box(
                            modifier = Modifier.background(TnaColors.Accent, RoundedCornerShape(999.dp)).clickable { onAdd(item.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        ) { Text("加进今天", style = TnaTypography.Body.copy(color = Color.White, fontWeight = FontWeight.SemiBold)) }
                        Box(
                            modifier = Modifier.border(1.dp, TnaColors.Line, RoundedCornerShape(999.dp)).clickable { onDismiss(item.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        ) { Text("先不", style = TnaTypography.Body.copy(color = TnaColors.Muted)) }
                    }
                }
            }
        }
        if (rest > 0 && !expanded) {
            Text("还有 $rest 条在收件箱", modifier = Modifier.clickable(onClick = onToggleExpand).padding(vertical = 4.dp), style = TnaTypography.Mono.copy(color = TnaColors.AccentDeep))
        }
    }
}

/** created_at → 「你周X」前缀（拿不到就「你之前」）。 */
private fun recallPrefix(createdAt: String?): String {
    val d = createdAt?.let { runCatching { kotlinx.datetime.Instant.parse(it).toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date }.getOrNull() }
        ?: return "你之前"
    val wd = when (d.dayOfWeek.isoDayNumber) { 1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"; 5 -> "周五"; 6 -> "周六"; else -> "周日" }
    return "你$wd"
}

/** B6-03 硬门:今天已有确认计划时,Morning 不生成,只引导去「改今天」(plan-revise)。 */
@Composable
private fun PlanExistsGate(modifier: Modifier = Modifier, onGoToToday: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("你今天已经有计划了", style = TnaTypography.Display.copy(fontWeight = FontWeight.Bold))
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            "要直接去改今天吗？不用重新规划。",
            style = TnaTypography.AiVoice.copy(color = TnaColors.InkSoft),
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(20.dp))
        TnaButton(text = "去改今天", onClick = onGoToToday, style = TnaButtonStyle.Primary, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun EntryContent(
    modifier: Modifier = Modifier,
    rawInput: String,
    isPreparingSession: Boolean,
    errorMessage: String?,
    isRecording: Boolean,
    isVoiceConnecting: Boolean,
    isVoiceFinalizing: Boolean,
    voiceSpokenText: String,
    voiceHint: String?,
    onInputChange: (String) -> Unit,
    onGenerate: () -> Unit,
    onRetry: () -> Unit,
    onMicPressStart: suspend () -> Unit,
    onMicPressEnd: () -> Unit,
    onDismissVoiceHint: () -> Unit,
    onPressDown: () -> Unit = {},
    onPressUp: () -> Unit = {},
    onCancel: () -> Unit = {},
    onCancelArmedChange: (Boolean) -> Unit = {},
    onBarCenter: (Float) -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        // 标题区(压扁):大标题 + 一行副标,超长省略不换行。
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "日程规划", style = TnaTypography.Display)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "把脑子里的今天倒在这里,我帮你收成一条舒服的时间线。",
            style = TnaTypography.AiVoice,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(14.dp))
        // 输入框 = 主角:占满副标以下、语音条以上的全部空间,多行内部滚动。
        MorningInput(
            value = rawInput,
            onValueChange = onInputChange,
            placeholder = "今天上午写文档,下午3点开会,晚上想跑步",
            // 输入框占可用空间的 3/4(缩小 1/4);余下 1/4 由底部留白吸收,语音条+按钮随之上挪。
            modifier = Modifier.weight(3f).fillMaxWidth(),
        )
        CharCountFooter(rawInput)
        // 首页不放听写气泡(理由见旧版注释):转写直接进框、按钮自身显示状态,且避免顶高按钮断手势。
        voiceHint?.let { VoiceHintPanel(message = it, onDismiss = onDismissVoiceHint, modifier = Modifier.padding(top = 8.dp)) }
        errorMessage?.let { ErrorPanel(message = it, onRetry = onRetry, modifier = Modifier.padding(top = 8.dp)) }
        // 底部钉死:语音条(样式保持现状)+ 生成今天。
        VoiceInputRow(
            isRecording = isRecording,
            isConnecting = isVoiceConnecting,
            isFinalizing = isVoiceFinalizing,
            onMicPressStart = onMicPressStart,
            onMicPressEnd = onMicPressEnd,
            onPressDown = onPressDown,
            onPressUp = onPressUp,
            onCancel = onCancel,
            onCancelArmedChange = onCancelArmedChange,
            onBarCenter = onBarCenter,
            modifier = Modifier.padding(top = 12.dp),
        )
        TnaButton(
            text = if (isPreparingSession) "准备中" else "生成今天",
            onClick = onGenerate,
            enabled = rawInput.isNotBlank() && !isPreparingSession,
            style = TnaButtonStyle.Primary,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
        )
        // 缩小输入框后余下的 1/4 留白:把语音条+按钮整体上挪、屏幕底部留呼吸。
        Spacer(modifier = Modifier.weight(1f))
    }
}

/** 输入框底部:虚线分隔 + 小字计数(有内容时右侧「说多少都放得下」)。 */
@Composable
private fun CharCountFooter(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp).height(1.dp)
            .drawBehind {
                drawLine(
                    color = TnaColors.Line,
                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                    end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)),
                )
            },
    )
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = "${text.length} 字", style = TnaTypography.Mono.copy(color = TnaColors.Muted))
        Spacer(modifier = Modifier.weight(1f))
        if (text.isNotBlank()) {
            Text(text = "说多少都放得下", style = TnaTypography.Mono.copy(color = TnaColors.Muted))
        }
    }
}

/** 早上屏语音条:薄适配层 → 共享 [VoiceMicButton]（含按住即时反馈秒应 + 上滑取消 + tap-slop）。 */
@Composable
private fun VoiceInputRow(
    isRecording: Boolean,
    onMicPressStart: suspend () -> Unit,
    onMicPressEnd: () -> Unit,
    modifier: Modifier = Modifier,
    isConnecting: Boolean = false,
    isFinalizing: Boolean = false,
    idleLabel: String = "按住说话",
    recordingLabel: String = "在听,说吧 · 松开结束",
    onPressDown: () -> Unit = {},
    onPressUp: () -> Unit = {},
    onCancel: () -> Unit = {},
    onCancelArmedChange: (Boolean) -> Unit = {},
    onBarCenter: (Float) -> Unit = {},
    onTap: () -> Unit = {},
) {
    VoiceMicButton(
        isRecording = isRecording,
        isConnecting = isConnecting,
        isFinalizing = isFinalizing,
        onPressStart = onMicPressStart,
        onPressEnd = onMicPressEnd,
        onPressDown = onPressDown,
        onPressUp = onPressUp,
        onCancel = onCancel,
        onCancelArmedChange = onCancelArmedChange,
        onTap = onTap,
        idleLabel = idleLabel,
        recordingLabel = recordingLabel,
        modifier = modifier.onGloballyPositioned { onBarCenter(it.positionInRoot().y + it.size.height / 2f) },
    )
}

/** 「准备中」：三点呼吸动画。 */
@Composable
private fun ConnectingDots(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "dots")
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 480, delayMillis = i * 140, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$i"
            )
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(color.copy(alpha = alpha), RoundedCornerShape(999.dp))
            )
        }
    }
}

/** 「在听」：跳动的声波条。 */
@Composable
private fun VoiceWaveform(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "wave")
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.5.dp)) {
        val delays = listOf(0, 120, 240, 120, 0)
        delays.forEach { delay ->
            val h by transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 420, delayMillis = delay, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar$delay"
            )
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height((6 + 12 * h).dp)
                    .background(color, RoundedCornerShape(999.dp))
            )
        }
    }
}

@Composable
private fun VoiceHintPanel(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(TnaColors.LineSoft, TnaShapes.Input)
            .border(1.dp, TnaColors.Line, TnaShapes.Input)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = message, modifier = Modifier.weight(1f), style = TnaTypography.AiVoice)
        Text(
            text = "知道了",
            modifier = Modifier.padding(start = 10.dp).clickable(onClick = onDismiss),
            style = TnaTypography.Body.copy(color = TnaColors.AccentDeep, fontWeight = FontWeight.SemiBold)
        )
    }
}

@Composable
private fun LoadingContent(isAdjusting: Boolean = false, onCancel: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 54.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = TnaColors.Accent, trackColor = TnaColors.AccentSoft, strokeWidth = 3.dp)
        Text(
            text = if (isAdjusting) "收到了,我正在按你说的帮你改今天的安排…" else "我在把今天揉成一条顺一点的线。",
            modifier = Modifier.padding(top = 18.dp),
            style = TnaTypography.AiVoice,
            textAlign = TextAlign.Center
        )
        TnaButton(
            text = "取消",
            onClick = onCancel,
            style = TnaButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        )
    }
}

@Composable
private fun ProposalContent(
    tasks: List<EditablePlanTask>,
    aiComment: String,
    isConfirmed: Boolean,
    saveErrorMessage: String?,
    addTaskMessage: String? = null,
    onDismissAddTaskMessage: () -> Unit = {},
    onDeleteTask: (String) -> Unit,
    onToggleImportant: (String) -> Unit,
    onEditTime: (EditablePlanTask) -> Unit,
    onAddTask: () -> Unit,
    onRetrySave: () -> Unit,
    onAcceptSuggestion: (String) -> Unit = {},
) {
    val sortedTasks = tasks.sortedWith(
        compareBy<EditablePlanTask> { it.task.plannedStart.toMinutesOfDayOrDefault() }
            .thenBy { it.id },
    )

    SectionLabel(text = "today", modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
    if (isConfirmed) ConfirmedPanel(modifier = Modifier.padding(bottom = 10.dp))

    sortedTasks.forEach { item ->
        MorningTaskCard(
            taskId = item.id,
            title = item.task.title,
            time = formatTimeRange(item.task.plannedStart, item.task.plannedDuration, item.task.kind == "point").ifEmpty { "--:--" },
            note = item.task.note,
            important = item.task.important,
            // v1.4:软=未接受(status=suggested);加入后 status=planned → 不再软。旧数据兜底:ai_suggestion 仍非 planned。
            suggested = item.task.status == "suggested",
            routine = item.task.source == "routine",
            editable = !isConfirmed,
            onDelete = onDeleteTask,
            onToggleImportant = onToggleImportant,
            onEditTime = { onEditTime(item) },
            onAccept = { onAcceptSuggestion(item.id) },
        )
    }
    if (!isConfirmed) AddTaskRow(onClick = onAddTask, modifier = Modifier.padding(top = 2.dp, bottom = 9.dp))
    addTaskMessage?.let { AddTaskFeedbackBanner(it, onDismissAddTaskMessage) }

    SectionLabel(text = "ai voice", modifier = Modifier.padding(top = 9.dp, bottom = 7.dp))
    AiVoicePanel(text = aiComment)
    saveErrorMessage?.let { ErrorPanel(message = it, onRetry = onRetrySave, modifier = Modifier.padding(top = 12.dp)) }
}

@Composable
private fun AddTaskFeedbackBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(TnaColors.LineSoft, TnaShapes.Input)
            .border(1.dp, TnaColors.Line, TnaShapes.Input)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = message, modifier = Modifier.weight(1f), style = TnaTypography.AiVoice)
        Text(
            text = "知道了",
            modifier = Modifier.padding(start = 10.dp).clickable(onClick = onDismiss),
            style = TnaTypography.Body.copy(color = TnaColors.AccentDeep, fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun MorningInput(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    // F-11/F-12:用 TextFieldValue 守住光标与选区。外部 value 变了(语音转写/清空/加载回填)才同步进来,
    // 并把光标放到末尾;用户自己打字只上抛文本、不被回流的同值打断 → 不丢字、不被替换、按钮可用性跟随。
    var tfv by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (value != tfv.text) {
        tfv = TextFieldValue(value, TextRange(value.length))
    }
    val scrollState = rememberScrollState()
    LaunchedEffect(value) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    BasicTextField(
        value = tfv,
        onValueChange = { next ->
            tfv = next
            if (next.text != value) onValueChange(next.text)
        },
        textStyle = TnaTypography.Body.copy(color = TnaColors.Ink),
        cursorBrush = SolidColor(TnaColors.Accent),
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .shadow(elevation = 3.dp, shape = TnaShapes.Input, ambientColor = TnaColors.WarmShadow, spotColor = TnaColors.WarmShadow)
            .background(TnaColors.Surface, TnaShapes.Input)
            .border(1.dp, TnaColors.Line, TnaShapes.Input)
            .padding(horizontal = 15.dp, vertical = 14.dp),
        decorationBox = { innerTextField ->
            Box(modifier = Modifier.fillMaxWidth()) {
                if (value.isEmpty()) Text(text = placeholder, style = TnaTypography.Body.copy(color = TnaColors.Muted))
                innerTextField()
            }
        }
    )
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TnaColors.LineSoft, TnaShapes.Input)
            .border(1.dp, TnaColors.Line, TnaShapes.Input)
            .padding(14.dp)
    ) {
        Text(text = message, style = TnaTypography.AiVoice)
        Text(text = "再试一次", modifier = Modifier.padding(top = 8.dp).clickable(onClick = onRetry), style = TnaTypography.Body.copy(color = TnaColors.AccentDeep, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun ConfirmedPanel(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(TnaColors.AccentSoft, TnaShapes.Input)
            .border(1.dp, TnaColors.AccentSoft, TnaShapes.Input)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "已确认", style = TnaTypography.Body.copy(color = TnaColors.AccentDeep, fontWeight = FontWeight.Bold))
        Text(text = "  今天就这么开始。", style = TnaTypography.AiVoice.copy(color = TnaColors.InkSoft))
    }
}

@Composable
private fun MorningTaskCard(
    taskId: String,
    title: String,
    time: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    important: Boolean = false,
    suggested: Boolean = false,
    routine: Boolean = false,
    editable: Boolean = false,
    onDelete: (String) -> Unit = {},
    onToggleImportant: (String) -> Unit = {},
    onEditTime: () -> Unit = {},
    onAccept: () -> Unit = {},
) {
    val background = if (suggested) Color.Transparent else TnaColors.Surface
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 9.dp)
            .then(if (suggested) Modifier.dashedCardBorder() else Modifier.shadow(elevation = 3.dp, shape = TnaShapes.Card, ambientColor = TnaColors.WarmShadow, spotColor = TnaColors.WarmShadow))
            .background(background, TnaShapes.Card)
            .then(if (routine && !suggested) Modifier.border(1.dp, TnaColors.Accent, TnaShapes.Card) else Modifier)
            .padding(horizontal = 15.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (important) "★" else "☆",
                modifier = Modifier.padding(end = 7.dp).then(if (editable) Modifier.clickable { onToggleImportant(taskId) } else Modifier),
                style = TnaTypography.Body.copy(color = if (important) TnaColors.Accent else TnaColors.Muted, fontWeight = FontWeight.Bold)
            )
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = TnaTypography.Body.copy(color = if (suggested) TnaColors.InkSoft else TnaColors.Ink, fontWeight = if (suggested) FontWeight.Medium else FontWeight.Bold)
            )
            if (suggested) {
                Text(
                    text = "建议",
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .border(1.dp, TnaColors.Muted.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                    style = TnaTypography.Mono.copy(color = TnaColors.Muted)
                )
            } else if (routine) {
                Text(
                    text = "日常",
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .background(TnaColors.AccentSoft, RoundedCornerShape(999.dp))
                        .border(1.dp, TnaColors.Accent.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                    style = TnaTypography.Body.copy(color = TnaColors.AccentDeep, fontWeight = FontWeight.SemiBold)
                )
            }
            Text(
                text = time,
                modifier = Modifier
                    .background(if (suggested) Color.Transparent else TnaColors.AccentSoft, RoundedCornerShape(7.dp))
                    .then(if (editable) Modifier.clickable(onClick = onEditTime) else Modifier)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                style = TnaTypography.Mono.copy(color = if (suggested) TnaColors.InkSoft else TnaColors.Accent)
            )
            if (suggested && editable) {
                // 「加入」→ 升级为真任务(planned)。软建议不进已接受集,加入后才算数。
                Text(
                    text = "加入",
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .background(TnaColors.AccentSoft, RoundedCornerShape(999.dp))
                        .clickable(onClick = onAccept)
                        .padding(horizontal = 11.dp, vertical = 4.dp),
                    style = TnaTypography.Body.copy(color = TnaColors.AccentDeep, fontWeight = FontWeight.SemiBold)
                )
            }
            if (editable) {
                Text(
                    text = "×",
                    modifier = Modifier.padding(start = 10.dp).clickable { onDelete(taskId) }.padding(horizontal = 5.dp, vertical = 1.dp),
                    style = TnaTypography.Body.copy(color = TnaColors.Muted, fontWeight = FontWeight.Bold)
                )
            }
        }

        if (!note.isNullOrBlank()) {
            Text(text = note, modifier = Modifier.padding(top = 5.dp), style = TnaTypography.Body.copy(color = TnaColors.InkSoft))
        }
    }
}

@Composable
private fun AddTaskRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(TnaColors.LineSoft, TnaShapes.Input)
            .border(1.dp, TnaColors.Line, TnaShapes.Input)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "+", modifier = Modifier.padding(end = 8.dp), style = TnaTypography.Body.copy(color = TnaColors.AccentDeep, fontWeight = FontWeight.Bold))
        Text(text = "加一项", style = TnaTypography.Body.copy(color = TnaColors.AccentDeep, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun TaskTimeDialog(title: String, initialMinutesOfDay: Int, onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
    var minutesOfDay by remember { mutableIntStateOf(initialMinutesOfDay) }
    TnaDialog(onDismiss = onDismiss) {
        Text(text = title, style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold))
        Spacer(modifier = Modifier.height(12.dp))
        TimeStepSelector(minutesOfDay = minutesOfDay, onMinutesChange = { minutesOfDay = it })
        DialogActions(confirmText = "更新", onDismiss = onDismiss, onConfirm = { onConfirm(minutesOfDay / 60, minutesOfDay % 60) })
    }
}

@Composable
private fun AddTaskDialog(onDismiss: () -> Unit, onConfirm: (String, Int, Int) -> Unit) {
    var title by remember { mutableStateOf("") }
    var minutesOfDay by remember { mutableIntStateOf(9 * 60) }
    TnaDialog(onDismiss = onDismiss) {
        Text(text = "加一项", style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold))
        Spacer(modifier = Modifier.height(10.dp))
        MorningInput(value = title, onValueChange = { title = it }, placeholder = "比如:买咖啡豆", modifier = Modifier.height(54.dp))
        Spacer(modifier = Modifier.height(12.dp))
        TimeStepSelector(minutesOfDay = minutesOfDay, onMinutesChange = { minutesOfDay = it })
        DialogActions(confirmText = "加入", onDismiss = onDismiss, onConfirm = { onConfirm(title, minutesOfDay / 60, minutesOfDay % 60) }, confirmEnabled = title.trim().isNotEmpty())
    }
}

@Composable
private fun TimeStepSelector(minutesOfDay: Int, onMinutesChange: (Int) -> Unit) {
    val normalized = ((minutesOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
    val hour = normalized / 60
    val minute = normalized % 60
    val display = hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0')

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TnaColors.LineSoft, TnaShapes.Input)
            .border(1.dp, TnaColors.Line, TnaShapes.Input)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = display, style = TnaTypography.Display.copy(color = TnaColors.AccentDeep, fontWeight = FontWeight.Bold))
        Spacer(modifier = Modifier.height(12.dp))
        // F-17:单日聚焦,夹在今天 00:00–23:59,到边界就停,不回绕到昨天/明天。
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeStepButton("-1h", Modifier.weight(1f)) { onMinutesChange((normalized - 60).coerceIn(0, MINUTES_PER_DAY - 1)) }
            TimeStepButton("-15m", Modifier.weight(1f)) { onMinutesChange((normalized - 15).coerceIn(0, MINUTES_PER_DAY - 1)) }
            TimeStepButton("+15m", Modifier.weight(1f)) { onMinutesChange((normalized + 15).coerceIn(0, MINUTES_PER_DAY - 1)) }
            TimeStepButton("+1h", Modifier.weight(1f)) { onMinutesChange((normalized + 60).coerceIn(0, MINUTES_PER_DAY - 1)) }
        }
    }
}

@Composable
private fun TimeStepButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .background(TnaColors.Surface, TnaShapes.Selection)
            .border(1.dp, TnaColors.Line, TnaShapes.Selection)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, style = TnaTypography.Mono.copy(color = TnaColors.AccentDeep))
    }
}

@Composable
private fun TnaDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(TnaColors.Surface, TnaShapes.Card)
                .border(1.dp, TnaColors.Line, TnaShapes.Card)
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content
        )
    }
}

@Composable
private fun DialogActions(confirmText: String, onDismiss: () -> Unit, onConfirm: () -> Unit, confirmEnabled: Boolean = true) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TnaButton(text = "取消", onClick = onDismiss, style = TnaButtonStyle.Secondary, modifier = Modifier.weight(1f))
        TnaButton(text = confirmText, onClick = onConfirm, enabled = confirmEnabled, style = TnaButtonStyle.Primary, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AiVoicePanel(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TnaColors.LineSoft, TnaShapes.Input)
            .border(1.dp, TnaColors.LineSoft, TnaShapes.Input)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Box(modifier = Modifier.padding(end = 11.dp).background(TnaColors.Accent, RoundedCornerShape(999.dp)).width(3.dp).height(58.dp))
        Text(text = text, modifier = Modifier.weight(1f), style = TnaTypography.AiVoice)
    }
}

@Composable
private fun ProposalFooter(
    isSaving: Boolean,
    isConfirmed: Boolean,
    isRecording: Boolean,
    isVoiceConnecting: Boolean,
    isVoiceFinalizing: Boolean,
    voiceSpokenText: String,
    onMicPressStart: suspend () -> Unit,
    onAdjustPressEnd: () -> Unit,
    onAdjustTap: () -> Unit = {},
    onLooksGood: () -> Unit,
    onPressDown: () -> Unit = {},
    onPressUp: () -> Unit = {},
    onCancel: () -> Unit = {},
    onCancelArmedChange: (Boolean) -> Unit = {},
    onBarCenter: (Float) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TnaColors.Background)
            .padding(horizontal = 20.dp)
            .padding(top = 10.dp, bottom = 22.dp)
    ) {
        if (!isConfirmed) {
            // 录音时在上方显示实时听写气泡，让用户看到「说了什么」。
            if (isRecording || isVoiceFinalizing) {
                VoiceListeningBubble(
                    connecting = isVoiceConnecting,
                    finalizing = isVoiceFinalizing,
                    spokenText = voiceSpokenText,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            VoiceInputRow(
                isRecording = isRecording,
                isConnecting = isVoiceConnecting,
                isFinalizing = isVoiceFinalizing,
                onMicPressStart = onMicPressStart,
                onMicPressEnd = onAdjustPressEnd,
                onPressDown = onPressDown,
                onPressUp = onPressUp,
                onCancel = onCancel,
                onCancelArmedChange = onCancelArmedChange,
                onBarCenter = onBarCenter,
                onTap = onAdjustTap,
                idleLabel = "想调整？按住说话告诉我",
                recordingLabel = "在听,说吧 · 松开重新生成"
            )
            Spacer(modifier = Modifier.height(10.dp))
        }
        TnaButton(
            text = when {
                isConfirmed -> "已确认"
                isSaving -> "保存中"
                else -> "就按这个安排"
            },
            onClick = onLooksGood,
            enabled = !isSaving && !isConfirmed,
            style = TnaButtonStyle.Primary,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 计划页录音时的实时听写气泡：连接时提示「准备中」，连上后显示边说边出的文字。 */
@Composable
private fun VoiceListeningBubble(
    connecting: Boolean,
    finalizing: Boolean,
    spokenText: String,
    modifier: Modifier = Modifier,
) {
    val text = when {
        connecting -> "准备中,马上可以说…"
        finalizing -> "整理中…"
        spokenText.isBlank() -> "在听,说吧…(松开后我按这句重新排今天)"
        else -> spokenText
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(TnaColors.AccentSoft, TnaShapes.Input)
            .border(1.dp, TnaColors.Accent.copy(alpha = 0.28f), TnaShapes.Input)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            finalizing -> CircularProgressIndicator(
                modifier = Modifier.padding(end = 9.dp).size(16.dp),
                color = TnaColors.AccentDeep,
                strokeWidth = 2.dp,
            )
            connecting -> ConnectingDots(color = TnaColors.AccentDeep, modifier = Modifier.padding(end = 9.dp))
            else -> VoiceWaveform(color = TnaColors.AccentDeep, modifier = Modifier.padding(end = 9.dp))
        }
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = TnaTypography.Body.copy(
                color = if (spokenText.isBlank() || connecting || finalizing) TnaColors.InkSoft else TnaColors.Ink
            )
        )
    }
}

private fun Modifier.dashedCardBorder(): Modifier = this
    .border(0.dp, Color.Transparent, TnaShapes.Card)
    .drawBehind {
        drawRoundRect(
            color = TnaColors.Line,
            style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()), 0f)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx(), 16.dp.toPx())
        )
    }


private fun String?.toMinutesOfDayOrDefault(): Int {
    if (this.isNullOrBlank()) return 9 * 60
    val raw = trim()
    return runCatching {
        if (raw.matches(Regex("^\\d{2}:\\d{2}(:\\d{2})?$"))) {
            val parts = raw.split(":")
            val h = parts[0].toInt().coerceIn(0, 23)
            val m = parts[1].toInt().coerceIn(0, 59)
            h * 60 + m
        } else {
            val instant = Instant.parse(raw)
            val localDt = instant.toLocalDateTime(TimeZone.currentSystemDefault())
            localDt.hour * 60 + localDt.minute
        }
    }.getOrDefault(9 * 60)
}

private const val MINUTES_PER_DAY = 24 * 60
