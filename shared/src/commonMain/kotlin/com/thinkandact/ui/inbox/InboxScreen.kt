package com.thinkandact.ui.inbox

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thinkandact.data.remote.InboxItemDto
import com.thinkandact.ui.common.PressFeedbackOverlay
import com.thinkandact.ui.common.TnaButton
import com.thinkandact.ui.common.TnaButtonStyle
import com.thinkandact.ui.common.VoiceMicButton
import com.thinkandact.ui.theme.TnaColors
import com.thinkandact.ui.theme.TnaShapes
import com.thinkandact.ui.theme.TnaTypography
import com.thinkandact.voice.rememberMicPermissionController
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn

@Composable
fun InboxScreen(
    onBack: () -> Unit,
    viewModel: InboxViewModel = org.koin.compose.viewmodel.koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.load() }

    Box(modifier = Modifier.fillMaxSize().background(TnaColors.Background)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // 顶栏
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(40.dp).background(TnaColors.Surface, RoundedCornerShape(999.dp)).clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) { Text("←", style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold)) }
                Text("收件箱", modifier = Modifier.padding(start = 12.dp), style = TnaTypography.Display.copy(fontWeight = FontWeight.Bold))
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.isLoading -> Center { CircularProgressIndicator(color = TnaColors.Accent, strokeWidth = 3.dp) }
                    state.errorMessage != null -> Center {
                        Text(state.errorMessage!!, style = TnaTypography.AiVoice.copy(color = TnaColors.AccentDeep), textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Text("再试一次", modifier = Modifier.clickable { viewModel.load() }, style = TnaTypography.Body.copy(color = TnaColors.AccentDeep, fontWeight = FontWeight.SemiBold))
                    }
                    state.items.isEmpty() -> Center {
                        Text("收件箱空着。", style = TnaTypography.AiVoice.copy(color = TnaColors.InkSoft))
                        Spacer(Modifier.height(6.dp))
                        Text("想起什么,点右下角 + 记一笔。", style = TnaTypography.Mono.copy(color = TnaColors.MutedSoft))
                    }
                    else -> InboxList(state, onDelete = viewModel::delete, onAddToday = { viewModel.addToday(it) }, pendingId = state.pendingActionId)
                }
            }
        }

        // FAB +
        Box(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 28.dp)
                .navigationBarsPadding().size(56.dp)
                .background(TnaColors.Accent, RoundedCornerShape(999.dp)).clickable(onClick = viewModel::openCapture),
            contentAlignment = Alignment.Center,
        ) { Text("+", style = TnaTypography.Display.copy(color = Color.White, fontWeight = FontWeight.Bold)) }

        if (state.captureOpen) CaptureSheet(viewModel, state)
    }
}

@Composable
private fun InboxList(
    state: InboxUiState,
    onDelete: (String) -> Unit,
    onAddToday: (InboxItemDto) -> Unit,
    pendingId: String?,
) {
    val grouped = state.grouped()
    val order = listOf(
        InboxGroup.DUE_TODAY to "到日子了",
        InboxGroup.THIS_WEEK to "这周",
        InboxGroup.LATER to "以后",
        InboxGroup.NO_DATE to "没定日子",
    )
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        order.forEach { (g, label) ->
            val rows = grouped[g].orEmpty()
            if (rows.isNotEmpty()) {
                item(key = "h_$g") {
                    val n = if (g == InboxGroup.DUE_TODAY) rows.count { it.status == "pending" } else 0
                    Row(modifier = Modifier.padding(top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = TnaTypography.SectionTitle.copy(color = TnaColors.Ink))
                        if (n > 0) Text("  $n", style = TnaTypography.Mono.copy(color = TnaColors.Accent, fontWeight = FontWeight.Bold))
                    }
                }
                items(rows, key = { it.id }) { item ->
                    InboxRow(item, due = g == InboxGroup.DUE_TODAY, busy = pendingId == item.id, onDelete = { onDelete(item.id) }, onAddToday = { onAddToday(item) })
                }
            }
        }
        item { Spacer(Modifier.height(14.dp)) }
        item {
            Text(
                "「到日子了」的事处理一件少一件;其他的不用管,过好今天就行。",
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                style = TnaTypography.Mono.copy(color = TnaColors.MutedSoft), textAlign = TextAlign.Center,
            )
        }
        item { Spacer(Modifier.height(80.dp)) } // FAB 让位
    }
}

@Composable
private fun InboxRow(item: InboxItemDto, due: Boolean, busy: Boolean, onDelete: () -> Unit, onAddToday: () -> Unit) {
    val dim = item.status != "pending" // dismissed/added 淡显安静躺着
    val bg = if (due && !dim) TnaColors.AccentSoft.copy(alpha = 0.5f) else TnaColors.Surface
    Row(
        modifier = Modifier.fillMaxWidth().alpha(if (dim) 0.45f else 1f)
            .background(bg, TnaShapes.Card).border(1.dp, TnaColors.Line, TnaShapes.Card)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.text, style = TnaTypography.Body.copy(color = TnaColors.Ink), maxLines = 2)
            Spacer(Modifier.height(4.dp))
            Text(dueChipText(item.dueDate, item.duePart), style = TnaTypography.Mono.copy(color = if (due) TnaColors.AccentDeep else TnaColors.Muted))
        }
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), color = TnaColors.AccentDeep, strokeWidth = 2.dp)
        } else {
            if (due && item.status == "pending") {
                Text("加进今天", modifier = Modifier.clickable(onClick = onAddToday).padding(end = 12.dp), style = TnaTypography.Body.copy(color = TnaColors.AccentDeep, fontWeight = FontWeight.SemiBold))
            }
            Text("✕", modifier = Modifier.clickable(onClick = onDelete).padding(start = 4.dp, end = 4.dp), style = TnaTypography.Body.copy(color = TnaColors.Muted))
        }
    }
}

/** 记一笔捕捉面板（底部 sheet）。 */
@Composable
private fun CaptureSheet(viewModel: InboxViewModel, state: InboxUiState) {
    val mic = rememberMicPermissionController()
    val amp by viewModel.amp.collectAsState()
    var fbVisible by remember { mutableStateOf(false) }
    var fbArmed by remember { mutableStateOf(false) }
    var barCenterY by remember { mutableStateOf(0f) }
    var showDuePicker by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color(0f, 0f, 0f, 0.32f)).clickable(onClick = viewModel::closeCapture)) {
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(TnaColors.Background, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .clickable(enabled = false) {}
                .navigationBarsPadding().imePadding().padding(20.dp),
        ) {
            Text("记一笔", style = TnaTypography.SectionTitle.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(4.dp))
            Text("先放进收件箱,到那天早上我再提你 —— 今天的计划不动。", style = TnaTypography.Mono.copy(color = TnaColors.MutedSoft))
            Spacer(Modifier.height(14.dp))

            // 输入框(可打字,转写也落这里)
            Box(
                modifier = Modifier.fillMaxWidth().background(TnaColors.Surface, TnaShapes.Input).border(1.dp, TnaColors.Line, TnaShapes.Input).padding(14.dp),
            ) {
                BasicTextField(
                    value = state.transcript,
                    onValueChange = viewModel::onTranscriptEdited,
                    textStyle = TnaTypography.Body.copy(color = TnaColors.Ink),
                    cursorBrush = SolidColor(TnaColors.Accent),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    decorationBox = { inner ->
                        if (state.transcript.isEmpty()) Text("按住说 / 打字…", style = TnaTypography.Body.copy(color = TnaColors.MutedSoft))
                        inner()
                    },
                )
            }

            // 已入箱 → 日期 chip
            state.captured?.let { cap ->
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.background(TnaColors.AccentSoft, RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(dueChipText(cap.dueDate, cap.duePart), style = TnaTypography.Mono.copy(color = TnaColors.AccentDeep, fontWeight = FontWeight.SemiBold))
                    }
                    Box(
                        modifier = Modifier.border(1.dp, TnaColors.Line, RoundedCornerShape(999.dp)).clickable { showDuePicker = !showDuePicker }.padding(horizontal = 12.dp, vertical = 6.dp),
                    ) { Text("改时间", style = TnaTypography.Mono.copy(color = TnaColors.Muted)) }
                    if (cap.extractFailed) Text("没听出日子,可手动补", style = TnaTypography.Mono.copy(color = TnaColors.MutedSoft))
                }
                if (showDuePicker) DuePicker { date, part -> viewModel.changeCapturedDue(date, part); showDuePicker = false }
            }

            state.captureError?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = TnaTypography.AiVoice.copy(color = TnaColors.AccentDeep))
            }

            Spacer(Modifier.height(16.dp))

            if (state.captured == null) {
                // 语音条(秒应) + 放进收件箱
                Box(modifier = Modifier.onBarCenter { barCenterY = it }) {
                    VoiceMicButton(
                        isRecording = state.isRecording, isConnecting = state.isConnecting, isFinalizing = state.isFinalizing,
                        onPressStart = { if (mic.request()) viewModel.startVoice() },
                        onPressEnd = viewModel::stopVoice,
                        onPressDown = { fbVisible = true }, onPressUp = { fbVisible = false; fbArmed = false },
                        onCancel = viewModel::cancelVoice, onCancelArmedChange = { fbArmed = it },
                        idleLabel = "按住说一句", recordingLabel = "在听,说吧 · 松开",
                    )
                }
                Spacer(Modifier.height(10.dp))
                TnaButton(
                    text = if (state.isCapturing) "放进去…" else "放进收件箱",
                    onClick = { viewModel.putIntoInbox() },
                    enabled = state.transcript.isNotBlank() && !state.isCapturing && !state.isRecording,
                    style = TnaButtonStyle.Primary, modifier = Modifier.fillMaxWidth(),
                )
            } else {
                // 入箱后:再说一句 / 完成
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TnaButton(text = "再说一句", onClick = viewModel::resetForNext, style = TnaButtonStyle.Secondary, modifier = Modifier.weight(1f))
                    TnaButton(text = "完成", onClick = viewModel::closeCapture, style = TnaButtonStyle.Primary, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        PressFeedbackOverlay(visible = fbVisible, amp = amp, cancelArmed = fbArmed, anchorCenterYpx = barCenterY)
    }
}

/** 极简日期选择：今天/明天/后天/没定 × 上午/下午/晚上/不限。 */
@Composable
private fun DuePicker(onPick: (date: String?, part: String?) -> Unit) {
    val tz = TimeZone.currentSystemDefault()
    val today = Clock.System.todayIn(tz)
    var date by remember { mutableStateOf<String?>(today.toString()) }
    var part by remember { mutableStateOf<String?>(null) }
    Column(modifier = Modifier.padding(top = 10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("今天" to today.toString(), "明天" to today.plus(DatePeriod(days = 1)).toString(), "后天" to today.plus(DatePeriod(days = 2)).toString(), "没定" to null).forEach { (l, d) ->
                PickChip(l, date == d) { date = d }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("上午" to "morning", "下午" to "afternoon", "晚上" to "evening", "不限" to null).forEach { (l, p) ->
                PickChip(l, part == p) { part = p }
            }
        }
        Spacer(Modifier.height(10.dp))
        TnaButton(text = "就这天", onClick = { onPick(date, part) }, style = TnaButtonStyle.Primary, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun PickChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(if (selected) TnaColors.Accent else TnaColors.Surface, RoundedCornerShape(999.dp))
            .border(1.dp, if (selected) TnaColors.Accent else TnaColors.Line, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    ) { Text(label, style = TnaTypography.Mono.copy(color = if (selected) Color.White else TnaColors.Ink)) }
}

@Composable
private fun Center(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

private fun Modifier.onBarCenter(cb: (Float) -> Unit): Modifier =
    this.onGloballyPositioned { cb(it.positionInRoot().y + it.size.height / 2f) }

/** ISO date + part → 「周四 6/12 · 下午」/「没定日子」。 */
fun dueChipText(dueDate: String?, duePart: String?): String {
    if (dueDate.isNullOrBlank()) return "没定日子"
    val d = runCatching { LocalDate.parse(dueDate) }.getOrNull() ?: return "没定日子"
    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    val dayLabel = when (d) {
        today -> "今天"
        today.plus(DatePeriod(days = 1)) -> "明天"
        else -> weekday(d) + " " + d.monthNumber + "/" + d.dayOfMonth
    }
    val partLabel = when (duePart) {
        "morning" -> " · 上午"; "afternoon" -> " · 下午"; "evening" -> " · 晚上"; else -> ""
    }
    return dayLabel + partLabel
}

/** due_part → 「 · 上午/下午/晚上」后缀（无则空）。 */
fun partSuffix(duePart: String?): String = when (duePart) {
    "morning" -> " · 上午"; "afternoon" -> " · 下午"; "evening" -> " · 晚上"; else -> ""
}

private fun weekday(d: LocalDate): String = when (d.dayOfWeek.isoDayNumber) {
    1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"; 5 -> "周五"; 6 -> "周六"; else -> "周日"
}
