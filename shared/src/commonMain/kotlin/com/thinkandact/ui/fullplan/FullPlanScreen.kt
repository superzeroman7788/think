package com.thinkandact.ui.fullplan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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
                Text("  今天的完整计划", style = TnaTypography.SectionTitle.copy(color = TnaColors.Ink))
            }

            Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
                when {
                    state.isLoading -> { Spacer(Modifier.height(30.dp)); Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), color = TnaColors.AccentDeep, strokeWidth = 2.dp); Text("  在看今天…", style = TnaTypography.Body.copy(color = TnaColors.InkSoft)) } }
                    state.tasks.isEmpty() -> { Spacer(Modifier.height(40.dp)); Text("今天还没有安排。先去早上排一下吧。", style = TnaTypography.BodySoft) }
                    else -> {
                        // 现在线插在「已开始的最后一条」之后。
                        val started = state.tasks.filter { it.status != "suggested" && startSec(it)?.let { s -> s <= nowSec } == true }
                        val nowAfterId = started.lastOrNull()?.id
                        state.tasks.forEach { task ->
                            val st = rowState(task, nowSec)
                            PlanRow(task = task, state = st, rem = remText(task, nowSec, st), onClick = { if (st == RowState.Suggestion) viewModel.acceptSuggestion(task.id) else editTask = task })
                            if (task.id == nowAfterId) NowLine(nowSec)
                        }
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
                    onPressStart = onPressStart, onPressEnd = viewModel::stopReviseVoice, onTap = viewModel::onReviseTap,
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

        state.proposal?.let { p -> ReviseDiffDialog(p.summary, p.revisions, p.added, p.warnings, state.isApplying, viewModel::cancelProposal, viewModel::applyProposal) }
        editTask?.let { t -> EditTaskDialog(t, onDismiss = { editTask = null }, onDone = { viewModel.markDone(t.id); editTask = null }, onSkip = { viewModel.markSkip(t.id); editTask = null }, onTime = { h, m -> viewModel.updateTime(t.id, h, m); editTask = null }) }

        PressFeedbackOverlay(visible = fbVisible, amp = amp, cancelArmed = fbArmed, anchorCenterYpx = barCenterY)
    }
}

@Composable
private fun PlanRow(task: TaskRowDto, state: RowState, rem: String?, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp), verticalAlignment = Alignment.Top) {
        Text(task.plannedStart.hhmm(), modifier = Modifier.width(44.dp).padding(top = 13.dp), style = TnaTypography.Mono.copy(color = TnaColors.Muted))
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
                }
            }
            rem?.let { Text(it, modifier = Modifier.padding(top = 4.dp), style = TnaTypography.Mono.copy(color = if (state == RowState.Current) NowTerra else TnaColors.Muted)) }
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
private fun EditTaskDialog(task: TaskRowDto, onDismiss: () -> Unit, onDone: () -> Unit, onSkip: () -> Unit, onTime: (Int, Int) -> Unit) {
    var hour by remember { mutableStateOf(task.plannedStart.hourOrDefault()) }
    var minute by remember { mutableStateOf(task.plannedStart.minuteOrDefault()) }
    Dialog(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().background(TnaColors.Surface, TnaShapes.Card).border(1.dp, TnaColors.Line, TnaShapes.Card).padding(18.dp)) {
            Text(task.title, style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(14.dp))
            // 改时间
            Text("改时间", style = TnaTypography.Mono.copy(color = TnaColors.Muted))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Stepper(value = hour, onMinus = { hour = (hour + 23) % 24 }, onPlus = { hour = (hour + 1) % 24 }, label = hour.toString().padStart(2, '0'))
                Text(" : ", style = TnaTypography.Body.copy(color = TnaColors.Ink))
                Stepper(value = minute, onMinus = { minute = (minute + 55) % 60 }, onPlus = { minute = (minute + 5) % 60 }, label = minute.toString().padStart(2, '0'))
                Spacer(Modifier.weight(1f))
                TnaButton("保存时间", onClick = { onTime(hour, minute) }, style = TnaButtonStyle.Secondary)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TnaButton("跳过", onSkip, style = TnaButtonStyle.Secondary, modifier = Modifier.weight(1f))
                TnaButton("标完成", onDone, style = TnaButtonStyle.Primary, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Stepper(value: Int, onMinus: () -> Unit, onPlus: () -> Unit, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(TnaColors.LineSoft).clickable(onClick = onMinus), contentAlignment = Alignment.Center) { Text("−", style = TnaTypography.Body.copy(color = TnaColors.Ink)) }
        Text(label, modifier = Modifier.width(36.dp), style = TnaTypography.Mono.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(TnaColors.LineSoft).clickable(onClick = onPlus), contentAlignment = Alignment.Center) { Text("+", style = TnaTypography.Body.copy(color = TnaColors.Ink)) }
    }
}

@Composable
private fun ReviseDiffDialog(summary: String, revisions: List<RevisionDto>, added: List<AddedReviseDto>, warnings: List<String>, isApplying: Boolean, onCancel: () -> Unit, onApply: () -> Unit) {
    val changes = revisions.filter { it.change == "moved" || it.change == "dropped" }
    Dialog(onDismissRequest = onCancel) {
        Column(modifier = Modifier.fillMaxWidth().background(TnaColors.Surface, TnaShapes.Card).border(1.dp, TnaColors.Line, TnaShapes.Card).padding(18.dp)) {
            Text("想这么改", style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold))
            if (summary.isNotBlank()) Text(summary, modifier = Modifier.padding(top = 8.dp), style = TnaTypography.AiVoice.copy(color = TnaColors.InkSoft))
            Spacer(Modifier.height(12.dp))
            changes.forEach { r ->
                val detail = if (r.change == "moved") "${r.before.plannedStart.hhmm()} → ${r.after.plannedStart.hhmm()}" else "不做了"
                DiffRow(r.title, detail); Spacer(Modifier.height(8.dp))
            }
            added.forEach { a -> DiffRow(a.title, "新增 · ${a.plannedStart.hhmm()}", accent = true); Spacer(Modifier.height(8.dp)) }
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

private fun rowState(t: TaskRowDto, nowSec: Long): RowState {
    if (t.status == "suggested") return RowState.Suggestion
    if (t.status == "done" || t.status == "skipped" || t.status == "dropped") return RowState.Past
    val s = startSec(t) ?: return RowState.Upcoming
    val e = endSec(t) ?: return RowState.Upcoming
    return when {
        nowSec < s -> RowState.Upcoming
        nowSec < e -> RowState.Current
        else -> RowState.Current // 超时未完成,仍当前(温和提示见 remText)
    }
}

private fun remText(t: TaskRowDto, nowSec: Long, st: RowState): String? {
    if (st == RowState.Past || st == RowState.Suggestion) return null
    val s = startSec(t) ?: return null
    val e = endSec(t) ?: return null
    return when {
        nowSec < s -> "还剩约 ${fmtDur(s - nowSec)}开始"
        nowSec < e -> "还剩约 ${fmtDur(e - nowSec)}结束"
        else -> "超了约 ${fmtDur(nowSec - e)}· 该收尾了?"
    }
}

private fun fmtDur(sec: Long): String {
    val m = (sec / 60).coerceAtLeast(0)
    return if (m >= 60) "${m / 60} 小时${(m % 60).let { if (it > 0) "$it 分" else "" }} " else "$m 分 "
}

private fun String?.hhmm(): String {
    if (this.isNullOrBlank()) return "--:--"
    val i = runCatching { Instant.parse(this) }.getOrNull() ?: return "--:--"
    val lt = i.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${lt.hour.toString().padStart(2, '0')}:${lt.minute.toString().padStart(2, '0')}"
}
private fun Instant.hhmm(): String { val lt = toLocalDateTime(TimeZone.currentSystemDefault()); return "${lt.hour.toString().padStart(2, '0')}:${lt.minute.toString().padStart(2, '0')}" }
private fun String?.hourOrDefault(): Int = this?.let { runCatching { Instant.parse(it).toLocalDateTime(TimeZone.currentSystemDefault()).hour }.getOrNull() } ?: 9
private fun String?.minuteOrDefault(): Int = this?.let { runCatching { Instant.parse(it).toLocalDateTime(TimeZone.currentSystemDefault()).minute }.getOrNull() } ?: 0
