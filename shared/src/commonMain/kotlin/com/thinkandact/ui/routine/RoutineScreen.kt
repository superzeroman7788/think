package com.thinkandact.ui.routine

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.thinkandact.data.remote.RoutineDto
import com.thinkandact.ui.common.BrandWordmark
import com.thinkandact.ui.common.SectionLabel
import com.thinkandact.ui.common.TnaButton
import com.thinkandact.ui.common.TnaButtonStyle
import com.thinkandact.ui.common.VoiceMicButton
import com.thinkandact.ui.theme.TnaColors
import com.thinkandact.ui.theme.TnaShapes
import com.thinkandact.ui.theme.TnaTypography
import com.thinkandact.voice.rememberMicPermissionController
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun RoutineScreen(
    onBack: () -> Unit,
    viewModel: RoutineViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var editingForm by remember { mutableStateOf<RoutineFormState?>(null) }
    var deletingRoutine by remember { mutableStateOf<RoutineDto?>(null) }
    var showAddType by remember { mutableStateOf(false) } // F7-03 轻点打字加日常

    val micController = rememberMicPermissionController()
    // 语音优先：按住→申请权限并开始录音；松开→停止、等 final、再后端解析。
    val onVoicePressStart: suspend () -> Unit = {
        if (!state.isVoiceFinalizing && !state.isParsing) {
            if (micController.request()) viewModel.startVoiceCapture() else viewModel.onVoicePermissionDenied()
        }
    }
    val onVoicePressEnd: () -> Unit = { viewModel.stopVoiceCapture() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TnaColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))
        RoutineHeader(onBack = onBack)

        VoiceCreateCard(
            isRecording = state.isRecording,
            isConnecting = state.isVoiceConnecting,
            isFinalizing = state.isVoiceFinalizing,
            isParsing = state.isParsing,
            transcript = state.voiceTranscript,
            hint = state.voiceHint,
            onPressStart = onVoicePressStart,
            onPressEnd = onVoicePressEnd,
            onDismissHint = viewModel::dismissVoiceHint,
            onManualAdd = { editingForm = RoutineFormState() },
            onTap = { showAddType = true },
            modifier = Modifier.padding(top = 14.dp, bottom = 14.dp)
        )

        SectionLabel(text = "my routines", modifier = Modifier.padding(bottom = 8.dp))

        when {
            state.isLoading -> LoadingBlock()
            state.routines.isEmpty() -> EmptyBlock()
            else -> state.routines.forEach { routine ->
                RoutineCard(
                    routine = routine,
                    isPending = state.pendingRoutineId == routine.id,
                    onEdit = { editingForm = routine.toFormState() },
                    onToggle = { viewModel.toggleEnabled(routine) },
                    onDelete = { deletingRoutine = routine }
                )
            }
        }

        state.errorMessage?.let { msg ->
            ErrorBlock(message = msg, onRetry = viewModel::refresh, modifier = Modifier.padding(top = 12.dp))
        }

        Spacer(modifier = Modifier.height(22.dp))
    }

    editingForm?.let { form ->
        RoutineFormDialog(
            initial = form,
            isSaving = state.isSaving,
            onDismiss = { editingForm = null },
            onConfirm = { nextForm ->
                viewModel.saveRoutine(nextForm)
                editingForm = null
            }
        )
    }

    deletingRoutine?.let { routine ->
        ConfirmDeleteDialog(
            routine = routine,
            onDismiss = { deletingRoutine = null },
            onConfirm = {
                viewModel.deleteRoutine(routine)
                deletingRoutine = null
            }
        )
    }

    if (showAddType) {
        com.thinkandact.ui.common.TypeInputDialog(
            title = "加个日常",
            placeholder = "比如「每天早上 8 点喝水」",
            onDismiss = { showAddType = false },
            onSubmit = { showAddType = false; viewModel.addFromText(it) },
            submitLabel = "加进来",
        )
    }
}

@Composable
private fun RoutineHeader(onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .padding(end = 10.dp)
                .background(TnaColors.Surface, RoundedCornerShape(999.dp))
                .border(1.dp, TnaColors.Line, RoundedCornerShape(999.dp))
                .clickable(onClick = onBack)
                .padding(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.ArrowBack,
                contentDescription = "返回",
                tint = TnaColors.InkSoft,
                modifier = Modifier.size(20.dp)
            )
        }
        BrandWordmark()
    }
    Text(text = "我的日常", modifier = Modifier.padding(top = 14.dp), style = TnaTypography.Display)
    Text(
        text = "固定项只在这里管理,早上计划只显示后端返回的 routine。",
        modifier = Modifier.padding(top = 8.dp),
        style = TnaTypography.AiVoice
    )
}

@Composable
private fun VoiceCreateCard(
    isRecording: Boolean,
    isConnecting: Boolean,
    isFinalizing: Boolean,
    isParsing: Boolean,
    transcript: String,
    hint: String?,
    onPressStart: suspend () -> Unit,
    onPressEnd: () -> Unit,
    onDismissHint: () -> Unit,
    onManualAdd: () -> Unit,
    onTap: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TnaColors.Surface, TnaShapes.Card)
            .border(1.dp, TnaColors.Line, TnaShapes.Card)
            .padding(16.dp)
    ) {
        Text(text = "说一句,我来安排", style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold))
        Text(
            text = "比如:每天早上八点健身、工作日晚上九点学英语。",
            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            style = TnaTypography.AiVoice
        )

        // 麦克风按钮放在状态行之前：按下录音时把实时反馈插在按钮「下方」，
        // 按钮位置始终不变，避免手指脱离按钮导致手势取消（和早上屏同样的修复）。
        VoiceMicButton(
            isRecording = isRecording,
            isConnecting = isConnecting,
            isFinalizing = isFinalizing,
            onPressStart = onPressStart,
            onPressEnd = onPressEnd,
            onTap = onTap,
            idleLabel = "按住说话 · 说一句就好",
            recordingLabel = "在听,说吧 · 松开整理"
        )

        when {
            isParsing -> {
                Spacer(modifier = Modifier.height(10.dp))
                VoiceStatusRow(text = "整理中,马上排好…", spinner = true)
            }
            isRecording || isFinalizing -> {
                Spacer(modifier = Modifier.height(10.dp))
                VoiceStatusRow(
                    text = when {
                        isFinalizing -> "整理中…"
                        transcript.isBlank() -> "在听,说吧…"
                        else -> transcript
                    },
                    spinner = isFinalizing
                )
            }
        }

        hint?.let {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TnaColors.LineSoft, TnaShapes.Input)
                    .border(1.dp, TnaColors.Line, TnaShapes.Input)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = it, modifier = Modifier.weight(1f), style = TnaTypography.AiVoice)
                Text(
                    text = "知道了",
                    modifier = Modifier.padding(start = 8.dp).clickable(onClick = onDismissHint),
                    style = TnaTypography.Body.copy(color = TnaColors.AccentDeep, fontWeight = FontWeight.SemiBold)
                )
            }
        }

        // 和录音按钮拉开距离 + 分隔线，避免松手时误触「手动添加」。
        Spacer(modifier = Modifier.height(16.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(TnaColors.Line))
        Text(
            text = "或手动添加",
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 14.dp)
                .clickable(onClick = onManualAdd)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            style = TnaTypography.Body.copy(color = TnaColors.Muted, fontWeight = FontWeight.SemiBold)
        )
    }
}

@Composable
private fun VoiceStatusRow(text: String, spinner: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TnaColors.AccentSoft, TnaShapes.Input)
            .border(1.dp, TnaColors.Accent.copy(alpha = 0.28f), TnaShapes.Input)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (spinner) {
            CircularProgressIndicator(
                modifier = Modifier.padding(end = 9.dp).size(15.dp),
                color = TnaColors.AccentDeep,
                strokeWidth = 2.dp
            )
        }
        Text(text = text, modifier = Modifier.weight(1f), style = TnaTypography.Body.copy(color = TnaColors.Ink))
    }
}

@Composable
private fun LoadingBlock() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 42.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = TnaColors.Accent, trackColor = TnaColors.AccentSoft, strokeWidth = 3.dp)
        Text(text = "正在同步日常", modifier = Modifier.padding(top = 14.dp), style = TnaTypography.AiVoice)
    }
}

@Composable
private fun EmptyBlock() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TnaColors.LineSoft, TnaShapes.Card)
            .border(1.dp, TnaColors.Line, TnaShapes.Card)
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "还没有固定项", style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold))
        Text(
            text = "比如健身、吃药、学英语。加进来后由后端判断哪天进入早上计划。",
            modifier = Modifier.padding(top = 8.dp),
            style = TnaTypography.AiVoice,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun RoutineCard(
    routine: RoutineDto,
    isPending: Boolean,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .background(if (routine.enabled) TnaColors.AccentSoft else TnaColors.LineSoft, TnaShapes.Card)
            .border(
                width = if (routine.enabled) 2.dp else 1.dp,
                color = if (routine.enabled) TnaColors.Accent else TnaColors.LineSoft,
                shape = TnaShapes.Card
            )
            .padding(15.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = routine.title,
                        style = TnaTypography.Body.copy(
                            color = if (routine.enabled) TnaColors.Ink else TnaColors.Muted,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    RoutineStatusPill(enabled = routine.enabled)
                }
                Text(
                    text = "${routine.defaultTime.take(5)} · ${routine.repeatDays.dayLabel()}",
                    modifier = Modifier.padding(top = 5.dp),
                    style = TnaTypography.Mono.copy(color = TnaColors.InkSoft)
                )
            }
            RoutineToggleButton(
                enabled = routine.enabled,
                isPending = isPending,
                onClick = onToggle
            )
            RoutineIconButton(icon = Icons.Rounded.Edit, contentDescription = "编辑", enabled = !isPending, onClick = onEdit)
            RoutineIconButton(icon = Icons.Rounded.Delete, contentDescription = "删除", enabled = !isPending, tint = TnaColors.Muted, onClick = onDelete)
        }

        if (!routine.note.isNullOrBlank()) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text(
                    text = routine.note,
                    modifier = Modifier.weight(1f),
                    style = TnaTypography.AiVoice.copy(color = TnaColors.InkSoft)
                )
            }
        }
    }
}

@Composable
private fun RoutineIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = TnaColors.InkSoft,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .padding(start = 7.dp)
            .background(TnaColors.Background, RoundedCornerShape(999.dp))
            .border(1.dp, TnaColors.Line, RoundedCornerShape(999.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else TnaColors.Muted.copy(alpha = 0.45f),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun RoutineStatusPill(enabled: Boolean) {
    Text(
        text = if (enabled) "已启用" else "未启用",
        modifier = Modifier
            .background(
                if (enabled) TnaColors.Accent else TnaColors.Line,
                RoundedCornerShape(999.dp)
            )
            .padding(horizontal = 9.dp, vertical = 3.dp),
        style = TnaTypography.Mono.copy(
            color = if (enabled) TnaColors.Surface else TnaColors.InkSoft,
            fontWeight = FontWeight.Bold
        )
    )
}

@Composable
private fun RoutineToggleButton(enabled: Boolean, isPending: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(start = 7.dp)
            .background(
                if (enabled) TnaColors.Accent else TnaColors.Surface,
                RoundedCornerShape(999.dp)
            )
            .border(
                width = if (enabled) 0.dp else 1.dp,
                color = if (enabled) TnaColors.Accent else TnaColors.Line,
                shape = RoundedCornerShape(999.dp)
            )
            .clickable(enabled = !isPending, onClick = onClick)
            .padding(8.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.PowerSettingsNew,
            contentDescription = if (enabled) "停用" else "启用",
            tint = when {
                isPending -> TnaColors.Muted.copy(alpha = 0.45f)
                enabled -> TnaColors.Surface
                else -> TnaColors.Muted
            },
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun RoutineFormDialog(
    initial: RoutineFormState,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (RoutineFormState) -> Unit
) {
    var form by remember(initial) { mutableStateOf(initial) }

    TnaDialog(onDismiss = onDismiss) {
        Text(
            text = if (initial.id == null) "确认日常" else "编辑日常",
            style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold)
        )
        Spacer(modifier = Modifier.height(12.dp))
        RoutineInput(value = form.title, onValueChange = { form = form.copy(title = it) }, placeholder = "标题,比如:学英语", modifier = Modifier.height(52.dp))
        Spacer(modifier = Modifier.height(10.dp))
        RoutineInput(value = form.note, onValueChange = { form = form.copy(note = it) }, placeholder = "备注,可空", modifier = Modifier.height(70.dp))
        Spacer(modifier = Modifier.height(12.dp))
        TimePickerRow(value = form.defaultTime, onChange = { form = form.copy(defaultTime = it) })
        Spacer(modifier = Modifier.height(12.dp))
        RepeatDaysPicker(selected = form.repeatDays, onChange = { form = form.copy(repeatDays = it) })
        DialogActions(
            confirmText = if (isSaving) "保存中" else "保存",
            onDismiss = onDismiss,
            onConfirm = { onConfirm(form) },
            confirmEnabled = form.cleaned() != null && !isSaving
        )
    }
}

@Composable
private fun TimePickerRow(value: String, onChange: (String) -> Unit) {
    val minutes = value.toMinutesOfDay()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TnaColors.LineSoft, TnaShapes.Input)
            .border(1.dp, TnaColors.Line, TnaShapes.Input)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = value.take(5), style = TnaTypography.Display.copy(color = TnaColors.AccentDeep, fontWeight = FontWeight.Bold))
        Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeStepButton("-1h", Modifier.weight(1f)) { onChange((minutes - 60).toTimeText()) }
            TimeStepButton("-15m", Modifier.weight(1f)) { onChange((minutes - 15).toTimeText()) }
            TimeStepButton("+15m", Modifier.weight(1f)) { onChange((minutes + 15).toTimeText()) }
            TimeStepButton("+1h", Modifier.weight(1f)) { onChange((minutes + 60).toTimeText()) }
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
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, style = TnaTypography.Mono.copy(color = TnaColors.AccentDeep))
    }
}

@Composable
private fun RepeatDaysPicker(selected: Set<Int>, onChange: (Set<Int>) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = "重复", modifier = Modifier.padding(bottom = 8.dp), style = TnaTypography.Mono.copy(color = TnaColors.InkSoft))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to "日", 1 to "一", 2 to "二", 3 to "三", 4 to "四", 5 to "五", 6 to "六").forEach { (day, label) ->
                DayChip(day = day, label = label, selected = selected, onChange = onChange)
            }
        }
    }
}

@Composable
private fun RowScope.DayChip(day: Int, label: String, selected: Set<Int>, onChange: (Set<Int>) -> Unit) {
    val isSelected = day in selected
    Box(
        modifier = Modifier
            .weight(1f)
            .background(
                if (isSelected) TnaColors.AccentSoft else TnaColors.Surface,
                RoundedCornerShape(999.dp)
            )
            .border(1.dp, if (isSelected) TnaColors.AccentSoft else TnaColors.Line, RoundedCornerShape(999.dp))
            .clickable { onChange(if (isSelected) selected - day else selected + day) }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = TnaTypography.Body.copy(
                color = if (isSelected) TnaColors.AccentDeep else TnaColors.InkSoft,
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}

@Composable
private fun RoutineInput(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = TnaTypography.Body.copy(color = TnaColors.Ink),
        cursorBrush = SolidColor(TnaColors.Accent),
        modifier = modifier
            .fillMaxWidth()
            .background(TnaColors.Surface, TnaShapes.Input)
            .border(1.dp, TnaColors.Line, TnaShapes.Input)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        decorationBox = { innerTextField ->
            Box(modifier = Modifier.fillMaxSize()) {
                if (value.isEmpty()) Text(text = placeholder, style = TnaTypography.Body.copy(color = TnaColors.Muted))
                innerTextField()
            }
        }
    )
}

@Composable
private fun ConfirmDeleteDialog(routine: RoutineDto, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    TnaDialog(onDismiss = onDismiss) {
        Text(text = "删除「${routine.title}」?", style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold))
        Text(text = "删除后早上计划不会再拿到这条固定项。", modifier = Modifier.padding(top = 8.dp), style = TnaTypography.AiVoice, textAlign = TextAlign.Center)
        DialogActions(confirmText = "删除", onDismiss = onDismiss, onConfirm = onConfirm)
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TnaButton(text = "取消", onClick = onDismiss, style = TnaButtonStyle.Secondary, modifier = Modifier.weight(1f))
        TnaButton(text = confirmText, onClick = onConfirm, enabled = confirmEnabled, style = TnaButtonStyle.Primary, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ErrorBlock(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
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

private fun List<Int>.dayLabel(): String {
    val days = filter { it in 0..6 }.distinct().sorted()
    return when (days) {
        listOf(0, 1, 2, 3, 4, 5, 6) -> "每天"
        listOf(1, 2, 3, 4, 5) -> "工作日"
        listOf(0, 6) -> "周末"
        else -> days.joinToString(" ") { listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")[it] }
    }
}

private fun String.toMinutesOfDay(): Int {
    val parts = take(5).split(":")
    val hour = parts.getOrNull(0)?.toIntOrNull() ?: 9
    val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
    return hour.coerceIn(0, 23) * 60 + minute.coerceIn(0, 59)
}

private fun Int.toTimeText(): String {
    val normalized = ((this % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
    val h = normalized / 60
    val m = normalized % 60
    return h.toString().padStart(2, '0') + ":" + m.toString().padStart(2, '0')
}

private const val MINUTES_PER_DAY = 24 * 60
