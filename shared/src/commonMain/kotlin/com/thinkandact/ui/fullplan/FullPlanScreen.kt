package com.thinkandact.ui.fullplan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.thinkandact.core.time.PlanClockPhase
import com.thinkandact.core.time.addedDiffDetail
import com.thinkandact.core.time.formatClock
import com.thinkandact.core.time.formatDuration
import com.thinkandact.core.time.formatTimeRange
import com.thinkandact.core.time.planClockPhase
import com.thinkandact.core.time.plannedEndSec
import com.thinkandact.core.time.reviseDiffDetail
import com.thinkandact.data.remote.AddedReviseDto
import com.thinkandact.data.remote.RevisionDto
import com.thinkandact.data.remote.TaskRowDto
import com.thinkandact.ui.common.PressFeedbackOverlay
import com.thinkandact.ui.common.TnaButton
import com.thinkandact.ui.common.TnaButtonStyle
import com.thinkandact.ui.common.VoiceMicButton
import com.thinkandact.ui.theme.TnaColors
import com.thinkandact.ui.theme.TnaShapes
import com.thinkandact.ui.theme.TnaTypography
import com.thinkandact.voice.rememberMicPermissionController
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel

private val NowTerra = Color(0xFFB5654A)

private enum class RowState { Past, Current, Upcoming, Suggestion }

@Composable
fun FullPlanScreen(
    onBack: () -> Unit,
    viewModel: FullPlanViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val mic = rememberMicPermissionController()
    LaunchedEffect(Unit) { viewModel.load() }

    // 每 30s 刷新 now（还剩/现在线随时钟走）。
    var nowSec by remember { mutableStateOf(Clock.System.now().epochSeconds) }
    LaunchedEffect(Unit) { while (true) { nowSec = Clock.System.now().epochSeconds; delay(30_000) } }

    var amp = viewModel.amp.collectAsState().value
    var fbVisible by remember { mutableStateOf(false) }
    var fbArmed by remember { mutableStateOf(false) }
    var barCenterY by remember { mutableStateOf(0f) }
    var editTask by remember { mutableStateOf<TaskRowDto?>(null) }
    var addKind by remember { mutableStateOf<String?>(null) } // null=关；"block"=加一项；"point"=加时刻点
    var addDialogError by remember { mutableStateOf<String?>(null) }
    var showReviseType by remember { mutableStateOf(false) } // F7-03 轻点打字改今天

    val onPressStart: suspend () -> Unit = {
        if (!state.isFinalizing && !state.isProposing && state.proposal == null) {
            if (mic.request()) viewModel.startReviseVoice() else viewModel.dismissHint()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(TnaColors.Background)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
                Box(
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(999.dp)).background(TnaColors.Surface).clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) { Text("←", style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold)) }
                Text("今天的完整计划", modifier = Modifier.padding(start = 8.dp), style = TnaTypography.SectionTitle.copy(color = TnaColors.Ink))
            }

            Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
                when {
                    state.isLoading -> { Spacer(Modifier.height(30.dp)); Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), color = TnaColors.AccentDeep, strokeWidth = 2.dp); Text("  在看今天…", style = TnaTypography.Body.copy(color = TnaColors.InkSoft)) } }
                    state.tasks.isEmpty() -> { Spacer(Modifier.height(40.dp)); Text("今天还没有安排。先去早上排一下吧。", style = TnaTypography.BodySoft) }
                    else -> {
                        // 时刻点(钉子):把 point 归到所在 block(优先 anchor_task_id,否则按时间落在块范围内),
                        // 块永远完整一条,钉子渲染在块卡片内部下方;不落任何块的 point 独立成细钉子行。
                        val points = state.tasks.filter { it.isPoint }
                        val blocks = state.tasks.filter { !it.isPoint }
                        val nestedByBlock = HashMap<String, MutableList<TaskRowDto>>()
                        val standalonePoints = mutableListOf<TaskRowDto>()
                        points.forEach { p ->
                            val host = blocks.firstOrNull { b -> p.anchorTaskId != null && b.id == p.anchorTaskId }
                                ?: blocks.firstOrNull { b -> pointInBlock(p, b) }
                            if (host != null) nestedByBlock.getOrPut(host.id) { mutableListOf() }.add(p)
                            else standalonePoints.add(p)
                        }
                        // 渲染序列:块 + 独立钉子,按起点时间合并升序。
                        val sequence = (blocks + standalonePoints).sortedBy { startSec(it) ?: Long.MAX_VALUE }
                        // 现在线插在「已开始的最后一条(块或独立钉子)」之后。
                        val nowAfterId = sequence.filter { it.status != "suggested" && startSec(it)?.let { s -> s <= nowSec } == true }.lastOrNull()?.id
                        sequence.forEach { task ->
                            if (task.isPoint) {
                                PointRow(task, rowState(task, nowSec), onClick = { editTask = task })
                            } else {
                                val st = rowState(task, nowSec)
                                PlanRow(
                                    task = task, state = st, nowSec = nowSec, rem = remText(task, nowSec, st),
                                    nestedPoints = nestedByBlock[task.id].orEmpty(),
                                    onClick = { if (st == RowState.Suggestion) viewModel.acceptSuggestion(task.id) else editTask = task },
                                    onPointClick = { editTask = it },
                                )
                            }
                            if (task.id == nowAfterId) NowLine(nowSec)
                        }
                    }
                }
                // F7-03 点选兜底：纯手动加一项 / 加时刻点。
                if (!state.isLoading) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        AddChip("＋ 加一项", Modifier.weight(1f)) { addKind = "block" }
                        AddChip("＋ 加时刻点", Modifier.weight(1f)) { addKind = "point" }
                    }
                }
                state.errorMessage?.let { Spacer(Modifier.height(8.dp)); Text(it, style = TnaTypography.AiVoice.copy(color = TnaColors.AccentDeep)) }
            }

            // 底部语音改今天
            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                    .background(Brush.verticalGradient(0f to Color.Transparent, 0.3f to TnaColors.Background, 1f to TnaColors.Background))
                    .padding(top = 6.dp).padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                VoiceMicButton(
                    isRecording = state.isRecording, isConnecting = state.isConnecting, isFinalizing = state.isFinalizing,
                    onPressStart = onPressStart, onPressEnd = viewModel::stopReviseVoice, onTap = { showReviseType = true },
                    onPressDown = { fbVisible = true }, onPressUp = { fbVisible = false; fbArmed = false },
                    onCancel = viewModel::cancelReviseVoice, onCancelArmedChange = { fbArmed = it },
                    micOutlined = true, idleLabel = "按住说一句,改今天", recordingLabel = "在听,说吧 · 松开核对",
                    modifier = Modifier.onGloballyPositioned { barCenterY = it.positionInRoot().y + it.size.height / 2f },
                )
                state.reviseHint?.let {
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth().background(TnaColors.LineSoft, TnaShapes.Input).border(1.dp, TnaColors.Line, TnaShapes.Input).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(it, modifier = Modifier.weight(1f), style = TnaTypography.AiVoice)
                        Text("知道了", modifier = Modifier.padding(start = 8.dp).clickable(onClick = viewModel::dismissHint), style = TnaTypography.Body.copy(color = TnaColors.AccentDeep, fontWeight = FontWeight.SemiBold))
                    }
                }
            }
        }

        if (state.isProposing || state.isFinalizing) {
            Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)) {
                Row(modifier = Modifier.background(TnaColors.AccentSoft, TnaShapes.Input).border(1.dp, TnaColors.Accent.copy(alpha = 0.28f), TnaShapes.Input).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 9.dp).size(14.dp), color = TnaColors.AccentDeep, strokeWidth = 2.dp)
                    Text(if (state.isProposing) "在排…" else "整理中…", style = TnaTypography.Body.copy(color = TnaColors.Ink))
                }
            }
        }

        state.proposal?.let { p -> ReviseDiffDialog(p.summary, p.revisions, p.added, p.deferred, p.warnings, state.isApplying, viewModel::cancelProposal, viewModel::applyProposal) }
        editTask?.let { t ->
            // B6-04/可点性:已跳过(或旧 dropped)的任务不进普通编辑,只给「恢复 / 删除」,免得像还活着。
            if (t.status == "skipped" || t.status == "dropped") {
                SkippedTaskDialog(
                    task = t,
                    onDismiss = { editTask = null },
                    onRestore = { viewModel.restoreTask(t.id); editTask = null },
                    onDelete = { viewModel.deleteTask(t.id); editTask = null },
                )
            } else {
                EditTaskDialog(
                    task = t,
                    onDismiss = { editTask = null },
                    onDone = { viewModel.markDone(t.id); editTask = null },
                    onSkip = { viewModel.markSkip(t.id); editTask = null },
                    onDelete = { viewModel.deleteTask(t.id); editTask = null },
                    onSave = { title, h, m, dur, important ->
                        viewModel.editTask(t.id, title, h, m, dur, important, t.isPoint); editTask = null
                    },
                )
            }
        }
        addKind?.let { kind ->
            AddTaskDialog(
                isPoint = kind == "point",
                errorMessage = addDialogError,
                onDismiss = { addKind = null; addDialogError = null },
                onConfirm = { title, h, m, dur, important ->
                    val onDone: (Boolean, String?) -> Unit = { ok, err ->
                        if (ok) {
                            addKind = null
                            addDialogError = null
                        } else {
                            addDialogError = err ?: "加这一项没存上,再试一次。"
                        }
                    }
                    if (kind == "point") viewModel.addPoint(title, h, m, onDone)
                    else viewModel.addTask(title, h, m, important, dur, onDone)
                },
            )
        }
        if (showReviseType) {
            com.thinkandact.ui.common.TypeInputDialog(
                title = "改今天",
                placeholder = "打字说说怎么改,比如「写周报推到下午」",
                onDismiss = { showReviseType = false },
                onSubmit = { showReviseType = false; viewModel.reviseFromText(it) },
            )
        }

        PressFeedbackOverlay(visible = fbVisible, amp = amp, cancelArmed = fbArmed, anchorCenterYpx = barCenterY)
    }
}

@Composable
private fun PlanRow(
    task: TaskRowDto,
    state: RowState,
    nowSec: Long,
    rem: String?,
    onClick: () -> Unit,
    nestedPoints: List<TaskRowDto> = emptyList(),
    onPointClick: (TaskRowDto) -> Unit = {},
) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp), verticalAlignment = Alignment.Top) {
        Text(formatTimeRange(task.plannedStart, task.plannedDuration, false), modifier = Modifier.width(84.dp).padding(top = 13.dp), style = TnaTypography.Mono.copy(color = TnaColors.Muted))
        val bg = when (state) {
            RowState.Current -> Brush.verticalGradient(listOf(Color(0xFFFCEFE2), Color(0xFFF8E6D7)))
            RowState.Past -> Brush.verticalGradient(listOf(Color(0xFFFAF4EE), Color(0xFFFAF4EE)))
            else -> Brush.verticalGradient(listOf(TnaColors.Surface, TnaColors.Surface))
        }
        Column(
            modifier = Modifier.weight(1f)
                .then(if (state == RowState.Suggestion) Modifier.border(1.dp, Color(0xFFE0C9B6), RoundedCornerShape(14.dp)) else Modifier.background(bg, RoundedCornerShape(14.dp)).border(1.dp, if (state == RowState.Current) Color(0xFFE6C5AC) else TnaColors.Line, RoundedCornerShape(14.dp)))
                .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (task.important) Text("★ ", style = TnaTypography.Body.copy(color = TnaColors.Accent, fontWeight = FontWeight.Bold))
                if (state == RowState.Suggestion) {
                    Text("建议", modifier = Modifier.padding(end = 8.dp).border(1.dp, Color(0xFFECCBA6), RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 2.dp), style = TnaTypography.Mono.copy(color = Color(0xFFE29A52)))
                }
                Text(task.title, modifier = Modifier.weight(1f), style = TnaTypography.Body.copy(color = if (state == RowState.Past || state == RowState.Suggestion) TnaColors.Muted else TnaColors.Ink, fontWeight = if (state == RowState.Current) FontWeight.SemiBold else FontWeight.Normal))
                when (task.status) {
                    "done" -> StatusPill("完成", Color(0xFFF3E4D8), TnaColors.AccentDeep)
                    "skipped" -> StatusPill("跳过", Color(0xFFEFEAE5), TnaColors.Muted)
                    else -> if (state == RowState.Suggestion) Text("+ 加入", style = TnaTypography.Body.copy(color = TnaColors.AccentDeep, fontWeight = FontWeight.SemiBold))
                    else if (state == RowState.Current) Text("进行中", style = TnaTypography.Mono.copy(color = NowTerra, fontWeight = FontWeight.Bold))
                    else if (state == RowState.Past && task.planClockPhase(nowSec) == PlanClockPhase.Overdue) Text("超时", style = TnaTypography.Mono.copy(color = TnaColors.Muted))
                }
            }
            rem?.let { Text(it, modifier = Modifier.padding(top = 4.dp), style = TnaTypography.Mono.copy(color = if (state == RowState.Current) NowTerra else TnaColors.Muted)) }
            // 钉子区:块卡片内部下方,虚线分隔;块本身不被切开。
            if (nestedPoints.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp).height(1.dp)
                    .drawBehind { drawLine(Color(0xFFE3D3C4), androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))) })
                nestedPoints.forEach { p -> NestedPointRow(p, onClick = { onPointClick(p) }) }
            }
        }
    }
}

/** 块内钉子行:`● 15:00 给老张打电话`;done → 灰点 + 删除线。 */
@Composable
private fun NestedPointRow(p: TaskRowDto, onClick: () -> Unit) {
    val done = p.status == "done"
    val skipped = p.status == "skipped"
    val dim = done || skipped
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("●", style = TnaTypography.Mono.copy(color = if (dim) TnaColors.Muted else if (p.important) TnaColors.Accent else NowTerra))
        Text(formatClock(p.plannedStart), modifier = Modifier.padding(start = 6.dp).width(40.dp), style = TnaTypography.Mono.copy(color = TnaColors.Muted))
        Text(
            (if (p.important) "★ " else "") + p.title,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
            style = TnaTypography.Body.copy(
                color = if (dim) TnaColors.Muted else TnaColors.Ink,
                textDecoration = if (dim) TextDecoration.LineThrough else null,
            ),
        )
        if (done) Text("完成", style = TnaTypography.Mono.copy(color = TnaColors.AccentDeep))
        else if (skipped) Text("跳过", style = TnaTypography.Mono.copy(color = TnaColors.Muted))
    }
}

/** 不落任何块的独立钉子行(整屏一条细行)。 */
@Composable
private fun PointRow(p: TaskRowDto, state: RowState, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(formatClock(p.plannedStart), modifier = Modifier.width(84.dp), style = TnaTypography.Mono.copy(color = TnaColors.Muted))
        val done = p.status == "done"; val skipped = p.status == "skipped"; val dim = done || skipped
        Row(
            modifier = Modifier.weight(1f)
                .background(TnaColors.Surface, RoundedCornerShape(12.dp))
                .border(1.dp, TnaColors.Line, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("●", style = TnaTypography.Mono.copy(color = if (dim) TnaColors.Muted else if (p.important) TnaColors.Accent else NowTerra))
            Text(
                (if (p.important) "★ " else "") + p.title,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                style = TnaTypography.Body.copy(
                    color = if (dim) TnaColors.Muted else TnaColors.Ink,
                    textDecoration = if (dim) TextDecoration.LineThrough else null,
                ),
            )
            if (done) Text("完成", style = TnaTypography.Mono.copy(color = TnaColors.AccentDeep))
            else if (skipped) Text("跳过", style = TnaTypography.Mono.copy(color = TnaColors.Muted))
        }
    }
}

@Composable
private fun StatusPill(label: String, bg: Color, fg: Color) {
    Box(modifier = Modifier.background(bg, RoundedCornerShape(999.dp)).padding(horizontal = 10.dp, vertical = 3.dp)) { Text(label, style = TnaTypography.Mono.copy(color = fg)) }
}

@Composable
private fun NowLine(nowSec: Long) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = 44.dp).padding(top = 3.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(999.dp)).background(NowTerra))
        Box(Modifier.weight(1f).height(1.5.dp).padding(horizontal = 6.dp).background(Brush.horizontalGradient(listOf(NowTerra, Color.Transparent))))
        Text("现在 ${Instant.fromEpochSeconds(nowSec).hhmm()}", style = TnaTypography.Mono.copy(color = NowTerra, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun EditTaskDialog(
    task: TaskRowDto,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
    onSkip: () -> Unit,
    onDelete: () -> Unit,
    onSave: (title: String, hour: Int, minute: Int, durationMin: Int, important: Boolean) -> Unit,
) {
    val isPoint = task.isPoint
    var title by remember { mutableStateOf(task.title) }
    var hour by remember { mutableStateOf(task.plannedStart.hourOrDefault()) }
    var minute by remember { mutableStateOf(task.plannedStart.minuteOrDefault()) }
    var duration by remember { mutableStateOf((task.plannedDuration ?: 30).coerceAtLeast(15)) }
    var important by remember { mutableStateOf(task.important) }
    Dialog(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().background(TnaColors.Surface, TnaShapes.Card).border(1.dp, TnaColors.Line, TnaShapes.Card).padding(18.dp)) {
            TaskTitleField(title) { title = it }
            Spacer(Modifier.height(14.dp))
            ImportantToggle(important) { important = it }
            Spacer(Modifier.height(14.dp))
            TimeStepperRow(if (isPoint) "时刻" else "开始时间", hour, minute, { hour = it }, { minute = it })
            if (!isPoint) {
                Spacer(Modifier.height(14.dp))
                DurationStepperRow(duration) { duration = it }
            }
            Spacer(Modifier.height(18.dp))
            TnaButton("保存", onClick = { onSave(title, hour, minute, duration, important) }, style = TnaButtonStyle.Primary, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TnaButton("跳过", onSkip, style = TnaButtonStyle.Secondary, modifier = Modifier.weight(1f))
                TnaButton("标完成", onDone, style = TnaButtonStyle.Primary, modifier = Modifier.weight(1f))
            }
            // B6-04 点选删除:软删消失(区别于「跳过」=不做了灰显)。低调放底部,避免误触。
            Text(
                text = "删除这项",
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clickable(onClick = onDelete),
                style = TnaTypography.Mono.copy(color = TnaColors.Muted),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** B6-04:已跳过任务的受限操作 —— 只「恢复(撤销跳过)」或「删除」,不进普通编辑(免得像还活着)。 */
@Composable
private fun SkippedTaskDialog(
    task: TaskRowDto,
    onDismiss: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().background(TnaColors.Surface, TnaShapes.Card).border(1.dp, TnaColors.Line, TnaShapes.Card).padding(18.dp)) {
            Text(task.title, style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(6.dp))
            Text("这件今天跳过了。", style = TnaTypography.Mono.copy(color = TnaColors.Muted))
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TnaButton("删除", onDelete, style = TnaButtonStyle.Secondary, modifier = Modifier.weight(1f))
                TnaButton("恢复", onRestore, style = TnaButtonStyle.Primary, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** F7-03「+ 加一项 / + 加时刻点」纯点选新增。C8-05:空标题禁用、失败留弹窗内。 */
@Composable
private fun AddTaskDialog(
    isPoint: Boolean,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String, Int, Int, Int, Boolean) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var hour by remember { mutableStateOf(9) }
    var minute by remember { mutableStateOf(0) }
    var duration by remember { mutableStateOf(30) }
    var important by remember { mutableStateOf(false) }
    val titleOk = title.trim().isNotEmpty()
    Dialog(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().background(TnaColors.Surface, TnaShapes.Card).border(1.dp, TnaColors.Line, TnaShapes.Card).padding(18.dp)) {
            Text(if (isPoint) "加一个时刻点" else "加一项", style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(12.dp))
            TaskTitleField(title) { title = it }
            if (!isPoint) {
                Spacer(Modifier.height(14.dp))
                ImportantToggle(important) { important = it }
            }
            Spacer(Modifier.height(14.dp))
            TimeStepperRow(if (isPoint) "时刻" else "开始时间", hour, minute, { hour = it }, { minute = it })
            if (!isPoint) {
                Spacer(Modifier.height(14.dp))
                DurationStepperRow(duration) { duration = it }
            }
            errorMessage?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = TnaTypography.AiVoice.copy(color = TnaColors.AccentDeep))
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TnaButton("取消", onDismiss, style = TnaButtonStyle.Secondary, modifier = Modifier.weight(1f))
                TnaButton(
                    "加进今天",
                    onClick = { if (titleOk) onConfirm(title.trim(), hour, minute, duration, important) },
                    enabled = titleOk,
                    style = TnaButtonStyle.Primary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TaskTitleField(title: String, onChange: (String) -> Unit) {
    Text("标题", style = TnaTypography.Mono.copy(color = TnaColors.Muted))
    Box(Modifier.fillMaxWidth().padding(top = 6.dp).background(TnaColors.LineSoft, TnaShapes.Input).border(1.dp, TnaColors.Line, TnaShapes.Input).padding(horizontal = 12.dp, vertical = 11.dp)) {
        BasicTextField(
            value = title, onValueChange = onChange,
            textStyle = TnaTypography.Body.copy(color = TnaColors.Ink),
            cursorBrush = SolidColor(TnaColors.Accent),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner -> if (title.isEmpty()) Text("写点什么…", style = TnaTypography.Body.copy(color = TnaColors.MutedSoft)); inner() },
        )
    }
}

@Composable
private fun ImportantToggle(important: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("重要", modifier = Modifier.weight(1f), style = TnaTypography.Body.copy(color = TnaColors.Ink))
        Text(
            if (important) "★ 已标重要" else "☆ 普通",
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (important) TnaColors.AccentSoft else TnaColors.LineSoft).clickable { onChange(!important) }.padding(horizontal = 12.dp, vertical = 6.dp),
            style = TnaTypography.Mono.copy(color = if (important) TnaColors.AccentDeep else TnaColors.Muted),
        )
    }
}

@Composable
private fun TimeStepperRow(label: String, hour: Int, minute: Int, onHour: (Int) -> Unit, onMinute: (Int) -> Unit) {
    Text(label, style = TnaTypography.Mono.copy(color = TnaColors.Muted))
    val minutesOfDay = hour * 60 + minute
    val setMinutes: (Int) -> Unit = { m ->
        val clamped = m.coerceIn(0, 24 * 60 - 1)
        onHour(clamped / 60)
        onMinute(clamped % 60)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        Stepper(
            value = hour,
            onMinus = { setMinutes(minutesOfDay - 60) },
            onPlus = { setMinutes(minutesOfDay + 60) },
            label = hour.toString().padStart(2, '0'),
            minusEnabled = minutesOfDay >= 60,
            plusEnabled = minutesOfDay <= 23 * 60 + 59 - 60,
        )
        Text(" : ", style = TnaTypography.Body.copy(color = TnaColors.Ink))
        Stepper(
            value = minute,
            onMinus = { setMinutes(minutesOfDay - 15) },
            onPlus = { setMinutes(minutesOfDay + 15) },
            label = minute.toString().padStart(2, '0'),
            minusEnabled = minutesOfDay >= 15,
            plusEnabled = minutesOfDay <= 23 * 60 + 59 - 15,
        )
    }
}

@Composable
private fun DurationStepperRow(duration: Int, onChange: (Int) -> Unit) {
    Text("时长", style = TnaTypography.Mono.copy(color = TnaColors.Muted))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        Stepper(value = duration, onMinus = { onChange((duration - 15).coerceAtLeast(15)) }, onPlus = { onChange((duration + 15).coerceAtMost(600)) }, label = formatDuration(duration), labelWidth = 84.dp)
    }
}

@Composable
private fun AddChip(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text,
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(TnaColors.LineSoft).border(1.dp, TnaColors.Line, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 12.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        style = TnaTypography.Body.copy(color = TnaColors.AccentDeep, fontWeight = FontWeight.SemiBold),
    )
}

@Composable
private fun Stepper(
    value: Int,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    label: String,
    labelWidth: androidx.compose.ui.unit.Dp = 36.dp,
    minusEnabled: Boolean = true,
    plusEnabled: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepperBtn("−", minusEnabled, onMinus)
        Text(label, modifier = Modifier.width(labelWidth), style = TnaTypography.Mono.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        StepperBtn("+", plusEnabled, onPlus)
    }
}

@Composable
private fun StepperBtn(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) TnaColors.LineSoft else TnaColors.LineSoft.copy(alpha = 0.45f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TnaTypography.Body.copy(color = if (enabled) TnaColors.Ink else TnaColors.Muted))
    }
}

@Composable
private fun ReviseDiffDialog(summary: String, revisions: List<RevisionDto>, added: List<AddedReviseDto>, deferred: List<com.thinkandact.data.remote.DeferredItemDto>, warnings: List<String>, isApplying: Boolean, onCancel: () -> Unit, onApply: () -> Unit) {
    val changes = revisions.filter { it.change in setOf("moved", "skip", "delete", "dropped") }
    Dialog(onDismissRequest = onCancel) {
        Column(modifier = Modifier.fillMaxWidth().background(TnaColors.Surface, TnaShapes.Card).border(1.dp, TnaColors.Line, TnaShapes.Card).padding(18.dp)) {
            Text("想这么改", style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold))
            if (summary.isNotBlank()) Text(summary, modifier = Modifier.padding(top = 8.dp), style = TnaTypography.AiVoice.copy(color = TnaColors.InkSoft))
            Spacer(Modifier.height(12.dp))
            changes.forEach { r ->
                val detail = reviseDiffDetail(r.change, r.before.plannedStart, r.before.plannedDuration, r.after.plannedStart, r.after.plannedDuration)
                DiffRow(r.title, detail); Spacer(Modifier.height(8.dp))
            }
            added.forEach { a -> DiffRow(a.title, addedDiffDetail(a.plannedStart, a.plannedDuration, a.kind == "point"), accent = true); Spacer(Modifier.height(8.dp)) }
            com.thinkandact.ui.common.DeferredInboxChips(deferred = deferred)
            warnings.forEach { w -> Text("· $w", modifier = Modifier.padding(top = 4.dp), style = TnaTypography.AiVoice.copy(color = TnaColors.AccentDeep)) }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TnaButton("取消", onCancel, enabled = !isApplying, style = TnaButtonStyle.Secondary, modifier = Modifier.weight(1f))
                TnaButton(if (isApplying) "调整中…" else "应用", onApply, enabled = !isApplying, style = TnaButtonStyle.Primary, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DiffRow(name: String, detail: String, accent: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().background(if (accent) TnaColors.AccentSoft.copy(alpha = 0.35f) else TnaColors.LineSoft, TnaShapes.Input).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, modifier = Modifier.weight(1f), style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.SemiBold))
        Text(detail, style = TnaTypography.Mono.copy(color = TnaColors.AccentDeep))
    }
}

// ── 状态/还剩 计算（时钟制、不重置、不显负数）──────────────────────────
private fun startSec(t: TaskRowDto): Long? = t.plannedStart?.let { runCatching { Instant.parse(it).epochSeconds }.getOrNull() }
private fun endSec(t: TaskRowDto): Long? = startSec(t)?.let { it + (t.plannedDuration ?: 30) * 60L }

/** point 是否落在 block 的时间范围 [start, end) 内(钉子归位的时间兜底,anchor 缺失时用)。 */
private fun pointInBlock(p: TaskRowDto, b: TaskRowDto): Boolean {
    val ps = startSec(p) ?: return false
    val bs = startSec(b) ?: return false
    val be = endSec(b) ?: return false
    return ps in bs until be
}

private fun rowState(t: TaskRowDto, nowSec: Long): RowState {
    if (t.status == "suggested") return RowState.Suggestion
    if (t.status == "done" || t.status == "skipped" || t.status == "dropped") return RowState.Past
    return when (t.planClockPhase(nowSec)) {
        PlanClockPhase.NotStarted -> RowState.Upcoming
        PlanClockPhase.InProgress -> RowState.Current
        PlanClockPhase.Overdue -> RowState.Past
    }
}

private fun remText(t: TaskRowDto, nowSec: Long, st: RowState): String? {
    if (st == RowState.Past || st == RowState.Suggestion) {
        if (st == RowState.Past && t.planClockPhase(nowSec) == PlanClockPhase.Overdue) {
            val e = t.plannedEndSec() ?: return null
            return "超了约 ${fmtDur(nowSec - e)}· 该收尾了？"
        }
        return null
    }
    val s = startSec(t) ?: return null
    val e = endSec(t) ?: return null
    return when {
        nowSec < s -> "还剩约 ${fmtDur(s - nowSec)}开始"
        nowSec < e -> "还剩约 ${fmtDur(e - nowSec)}结束"
        else -> "超了约 ${fmtDur(nowSec - e)}· 该收尾了？"
    }
}

private fun fmtDur(sec: Long): String {
    val m = (sec / 60).coerceAtLeast(0)
    return if (m >= 60) "${m / 60} 小时${(m % 60).let { if (it > 0) "$it 分" else "" }} " else "$m 分 "
}

private fun Instant.hhmm(): String { val lt = toLocalDateTime(TimeZone.currentSystemDefault()); return "${lt.hour.toString().padStart(2, '0')}:${lt.minute.toString().padStart(2, '0')}" }
private fun String?.hourOrDefault(): Int = this?.let { runCatching { Instant.parse(it).toLocalDateTime(TimeZone.currentSystemDefault()).hour }.getOrNull() } ?: 9
private fun String?.minuteOrDefault(): Int = this?.let { runCatching { Instant.parse(it).toLocalDateTime(TimeZone.currentSystemDefault()).minute }.getOrNull() } ?: 0
