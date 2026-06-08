package com.thinkandact.ui.review

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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.thinkandact.data.remote.ReviewParseResponse
import com.thinkandact.data.remote.TaskRowDto
import com.thinkandact.ui.common.PressFeedbackOverlay
import com.thinkandact.ui.common.TnaButton
import com.thinkandact.ui.common.TnaButtonStyle
import com.thinkandact.ui.common.VoiceMicButton
import com.thinkandact.ui.theme.TnaColors
import com.thinkandact.ui.theme.TnaShapes
import com.thinkandact.ui.theme.TnaTypography
import com.thinkandact.voice.rememberMicPermissionController
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel

/** 复盘"时段"分界:此点之前算白天/未到复盘,先给「今天的变化」小结。 */
private const val EVENING_HOUR = 19

// demo 色值（复盘页_重设计_UI审核_v2）
private val Clay = Color(0xFF9E5238)
private val Amber = Color(0xFFE29A52)
private val ReminderInk = Color(0xFF5C4A3E)
private val PraiseTop = Color(0xFFFBEFE4)
private val PraiseBot = Color(0xFFF8E7D8)
private val CardBorder = Color(0xFFF0DFD2)
private val AdviceDivider = Color(0xFFF2E6DA)
private val DockBorder = Color(0xFFF0E5DB)

@Composable
fun ReviewScreen(
    onBack: () -> Unit,
    viewModel: ReviewViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val mic = rememberMicPermissionController()

    // VM 由 Activity store 持有 → 每次进屏重拉。
    LaunchedEffect(Unit) { viewModel.load() }

    // 按住即时反馈(秒应)
    val amp by viewModel.amp.collectAsState()
    var fbVisible by remember { mutableStateOf(false) }
    var fbArmed by remember { mutableStateOf(false) }
    var barCenterY by remember { mutableStateOf(0f) } // 量底部语音条中心 → 反馈层就地盖住它

    // bug8:白天(未到复盘时段)进来,先给「今天的变化」小结。
    val nowHour = remember { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour }
    val isDaytime = nowHour < EVENING_HOUR
    val doneN = state.tasks.count { it.status == "done" }
    val skipN = state.tasks.count { it.status == "skipped" }
    val movedN = state.tasks.count { it.wasRescheduled }
    val droppedN = state.tasks.count { it.status == "dropped" }
    val hasChanges = doneN + skipN + movedN + droppedN > 0

    val onVoiceStart: suspend () -> Unit = {
        if (!state.isFinalizing && !state.isParsing && state.parseResult == null) {
            if (mic.request()) viewModel.startVoice() else viewModel.dismissVoiceHint()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(TnaColors.Background)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // —— 滚动内容 ——
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp).padding(top = 18.dp, bottom = 12.dp),
            ) {
                ReviewHeader(onBack = onBack)
                Spacer(Modifier.height(6.dp))
                Text("晚上,把今天收一收。", style = TnaTypography.AiVoice.copy(color = TnaColors.InkSoft))

                state.errorMessage?.let { Banner(it, onDismiss = null) }

                if (state.isLoading) {
                    Spacer(Modifier.height(40.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = TnaColors.AccentDeep, strokeWidth = 2.dp)
                        Text("  拉今天的记录…", style = TnaTypography.Body.copy(color = TnaColors.InkSoft))
                    }
                } else {
                    // bug8:白天进复盘、且今天确实有变化 → 先给一条「今天的变化」小结。
                    if (isDaytime && hasChanges) {
                        Spacer(Modifier.height(14.dp))
                        ChangesSummaryCard(done = doneN, skipped = skipN, moved = movedN, dropped = droppedN)
                    }
                    // ① 审核今天
                    SectionTitle("1", "审核今天")
                    if (state.tasks.isEmpty()) {
                        Text("今天没有安排可复盘。", style = TnaTypography.BodySoft)
                    } else {
                        state.tasks.forEach { task ->
                            TaskReviewRow(task = task, onClick = { viewModel.cycleStatus(task.id) })
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(
                            "点一行循环:未做 → 完成 → 跳过(或用下面那条语音一句话改)",
                            style = TnaTypography.Mono.copy(color = TnaColors.Muted),
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    // ② 今天的提醒(两块)
                    SectionTitle("2", "今天的提醒")
                    if (state.hasReminder) {
                        ReminderCard(praise = state.praise, advice = state.advice)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (state.reminderStale) {
                                Text("任务变了 · ", style = TnaTypography.Mono.copy(color = TnaColors.AccentDeep))
                            }
                            Text(
                                if (state.isSummarizing) "在想…" else "重新生成",
                                modifier = Modifier.clickable(enabled = !state.isSummarizing, onClick = viewModel::generateReminder),
                                style = TnaTypography.Mono.copy(
                                    color = if (state.isSummarizing) TnaColors.Muted else TnaColors.AccentDeep,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            )
                        }
                    } else {
                        TnaButton(
                            text = if (state.isSummarizing) "在想…" else "看看今天的提醒",
                            onClick = viewModel::generateReminder,
                            enabled = !state.isSummarizing,
                            style = TnaButtonStyle.Secondary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // —— 底部唯一一条语音(改今天)——
            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                    .background(Brush.verticalGradient(0f to Color.Transparent, 0.3f to TnaColors.Background, 1f to TnaColors.Background))
                    .padding(top = 6.dp).padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                VoiceMicButton(
                    isRecording = state.isRecording,
                    isConnecting = state.isConnecting,
                    isFinalizing = state.isFinalizing,
                    onPressStart = onVoiceStart,
                    onPressEnd = viewModel::stopVoice,
                    onTap = viewModel::onVoiceTap,
                    onPressDown = { fbVisible = true },
                    onPressUp = { fbVisible = false; fbArmed = false },
                    onCancel = viewModel::cancelVoice,
                    onCancelArmedChange = { fbArmed = it },
                    micOutlined = true,
                    idleLabel = "按住说一句,改今天",
                    recordingLabel = "在听,说吧 · 松开核对",
                    modifier = Modifier.onGloballyPositioned { barCenterY = it.positionInRoot().y + it.size.height / 2f },
                )
                Spacer(Modifier.height(9.dp))
                Text(
                    "比如「深度活儿其实做了」「健身改成了跑步」",
                    modifier = Modifier.fillMaxWidth(),
                    style = TnaTypography.Mono.copy(color = TnaColors.Muted),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                state.voiceHint?.let { Banner(it, onDismiss = viewModel::dismissVoiceHint) }
            }
        }

        // 语音批量 → 前后对比确认卡（红线:确认才生效,取消零改动）
        state.parseResult?.let { res ->
            ParseConfirmDialog(
                res = res,
                tasks = state.tasks,
                isApplying = state.isApplying,
                onCancel = viewModel::cancelParse,
                onApply = viewModel::applyParse,
            )
        }

        // 即时反馈覆盖层(就地盖住底部语音条)
        PressFeedbackOverlay(visible = fbVisible, amp = amp, cancelArmed = fbArmed, anchorCenterYpx = barCenterY)
    }
}

@Composable
private fun ReviewHeader(onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(999.dp)).background(TnaColors.Surface).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { Text("←", style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold)) }
        Text("  复盘", style = TnaTypography.SectionTitle.copy(color = TnaColors.Ink))
    }
}

@Composable
private fun SectionTitle(num: String, text: String) {
    Spacer(Modifier.height(18.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(20.dp).clip(RoundedCornerShape(999.dp)).background(TnaColors.AccentSoft),
            contentAlignment = Alignment.Center,
        ) { Text(num, style = TnaTypography.Mono.copy(color = TnaColors.AccentDeep, fontWeight = FontWeight.Bold)) }
        Text("  $text", style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold))
    }
    Spacer(Modifier.height(10.dp))
}

/** ② 两块:夸+收尾 / 给明天的小提醒。下块仅在有 advice 时显示。 */
/** bug8:白天进复盘先看「今天的变化」——挪/完成/跳过/撤 的轻量小结(搭子语气,不评判)。 */
@Composable
private fun ChangesSummaryCard(done: Int, skipped: Int, moved: Int, dropped: Int) {
    val parts = buildList {
        if (done > 0) add("完成 $done")
        if (moved > 0) add("挪了 $moved 件")
        if (skipped > 0) add("跳过 $skipped")
        if (dropped > 0) add("撤了 $dropped 件")
    }
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(PraiseTop, PraiseBot)))
            .border(1.dp, CardBorder, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(999.dp)).background(Clay))
            Text("  今天的变化", style = TnaTypography.Mono.copy(color = Clay, fontWeight = FontWeight.Bold))
        }
        Spacer(Modifier.height(7.dp))
        Text(
            if (parts.isEmpty()) "今天还没动过。" else "到现在 — " + parts.joinToString(" · ") + "。",
            style = TnaTypography.AiVoice.copy(color = ReminderInk),
        )
        Text("晚点收尾再回来复盘也行。", modifier = Modifier.padding(top = 4.dp), style = TnaTypography.Mono.copy(color = TnaColors.Muted))
    }
}

@Composable
private fun ReminderCard(praise: String, advice: String) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.dp, CardBorder, RoundedCornerShape(18.dp)),
    ) {
        if (praise.isNotBlank()) {
            ReminderPart(
                bg = Brush.verticalGradient(listOf(PraiseTop, PraiseBot)),
                label = "今天做得不错", labelColor = Clay, body = praise, topDivider = false,
            )
        }
        if (advice.isNotBlank()) {
            ReminderPart(
                bg = Brush.verticalGradient(listOf(Color.White, Color.White)),
                label = "给明天的小提醒", labelColor = Amber, body = advice, topDivider = praise.isNotBlank(),
            )
        }
    }
}

@Composable
private fun ReminderPart(bg: Brush, label: String, labelColor: Color, body: String, topDivider: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .then(if (topDivider) Modifier.border(0.dp, Color.Transparent) else Modifier)
            .background(bg)
            .padding(top = if (topDivider) 1.dp else 0.dp), // 细线分隔靠 advice 顶边色
    ) {
        if (topDivider) Box(Modifier.fillMaxWidth().height(1.dp).background(AdviceDivider))
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(RoundedCornerShape(999.dp)).background(labelColor))
                Text("  $label", style = TnaTypography.Mono.copy(color = labelColor, fontWeight = FontWeight.Bold))
            }
            Spacer(Modifier.height(7.dp))
            Text(body, style = TnaTypography.AiVoice.copy(color = ReminderInk))
        }
    }
}

@Composable
private fun Banner(text: String, onDismiss: (() -> Unit)?) {
    Spacer(Modifier.height(10.dp))
    Row(
        modifier = Modifier.fillMaxWidth().background(TnaColors.LineSoft, TnaShapes.Input)
            .border(1.dp, TnaColors.Line, TnaShapes.Input).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, modifier = Modifier.weight(1f), style = TnaTypography.AiVoice)
        if (onDismiss != null) {
            Text("知道了", modifier = Modifier.padding(start = 8.dp).clickable(onClick = onDismiss),
                style = TnaTypography.Body.copy(color = TnaColors.AccentDeep, fontWeight = FontWeight.SemiBold))
        }
    }
}

@Composable
private fun TaskReviewRow(task: TaskRowDto, onClick: () -> Unit) {
    val dropped = task.status == "dropped"
    val (label, color) = statusChip(task.status)
    Row(
        modifier = Modifier.fillMaxWidth().background(TnaColors.Surface, TnaShapes.Input).border(1.dp, TnaColors.Line, TnaShapes.Input)
            .then(if (dropped) Modifier else Modifier.clickable(onClick = onClick))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(task.title, style = TnaTypography.Body.copy(color = if (dropped) TnaColors.Muted else TnaColors.Ink, fontWeight = FontWeight.SemiBold))
            val time = task.plannedStart.formatTime()
            if (time != "--:--") Text(time, modifier = Modifier.padding(top = 2.dp), style = TnaTypography.Mono.copy(color = TnaColors.Muted))
        }
        Box(modifier = Modifier.background(color.copy(alpha = 0.16f), RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 5.dp)) {
            Text(label, style = TnaTypography.Mono.copy(color = color))
        }
    }
}

private fun statusChip(status: String): Pair<String, Color> = when (status) {
    "done" -> "完成" to TnaColors.AccentDeep
    "skipped" -> "跳过" to TnaColors.Muted
    "dropped" -> "已撤" to TnaColors.MutedSoft
    else -> "未做" to TnaColors.Ink
}

/** 语音批量前后对比确认卡（取消=零改动）。 */
@Composable
private fun ParseConfirmDialog(
    res: ReviewParseResponse,
    tasks: List<TaskRowDto>,
    isApplying: Boolean,
    onCancel: () -> Unit,
    onApply: () -> Unit,
) {
    val titleOf = { id: String -> tasks.firstOrNull { it.id == id }?.title ?: "" }
    Dialog(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier.fillMaxWidth().background(TnaColors.Surface, TnaShapes.Card).border(1.dp, TnaColors.Line, TnaShapes.Card).padding(18.dp),
        ) {
            Text("想这么记", style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold))
            if (res.summary.isNotBlank()) Text(res.summary, modifier = Modifier.padding(top = 8.dp), style = TnaTypography.AiVoice.copy(color = TnaColors.InkSoft))
            Spacer(Modifier.height(12.dp))
            res.proposed.forEach { p ->
                ChangeRow(name = p.title.ifBlank { titleOf(p.taskId) }, detail = if (p.toStatus == "done") "→ 完成" else "→ 跳过")
                Spacer(Modifier.height(8.dp))
            }
            res.added.forEach { a ->
                ChangeRow(name = a.title, detail = "新增 · 完成", accent = true)
                Spacer(Modifier.height(8.dp))
            }
            res.warnings.forEach { w -> Text("· $w", modifier = Modifier.padding(top = 4.dp), style = TnaTypography.AiVoice.copy(color = TnaColors.AccentDeep)) }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TnaButton("取消", onCancel, enabled = !isApplying, style = TnaButtonStyle.Secondary, modifier = Modifier.weight(1f))
                TnaButton(if (isApplying) "记…" else "就这么记", onApply, enabled = !isApplying, style = TnaButtonStyle.Primary, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ChangeRow(name: String, detail: String, accent: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().background(if (accent) TnaColors.AccentSoft.copy(alpha = 0.35f) else TnaColors.LineSoft, TnaShapes.Input).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, modifier = Modifier.weight(1f), style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.SemiBold))
        Text(detail, style = TnaTypography.Mono.copy(color = TnaColors.AccentDeep))
    }
}

private fun String?.formatTime(): String {
    if (this.isNullOrBlank()) return "--:--"
    val instant = runCatching { Instant.parse(this) }.getOrNull() ?: return "--:--"
    val lt = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${lt.hour.toString().padStart(2, '0')}:${lt.minute.toString().padStart(2, '0')}"
}
