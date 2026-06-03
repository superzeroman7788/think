package com.thinkandact.app.ui.morning

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.thinkandact.app.data.remote.PlanTaskDto
import com.thinkandact.app.ui.common.BrandWordmark
import com.thinkandact.app.ui.common.SectionLabel
import com.thinkandact.app.ui.common.TnaButton
import com.thinkandact.app.ui.common.TnaButtonStyle
import com.thinkandact.app.ui.theme.TnaColors
import com.thinkandact.app.ui.theme.TnaShapes
import com.thinkandact.app.ui.theme.TnaTypography
import java.time.OffsetDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun MorningScreen(
    onOpenRoutines: () -> Unit,
    viewModel: MorningViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var editingTimeTask by remember { mutableStateOf<EditablePlanTask?>(null) }
    var showAddTask by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TnaColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(10.dp))
            MorningHeader(onOpenRoutines = onOpenRoutines)

            when {
                state.proposal != null -> ProposalContent(
                    tasks = state.editableTasks,
                    aiComment = state.proposal!!.aiComment,
                    isConfirmed = state.isConfirmed,
                    saveErrorMessage = state.saveErrorMessage,
                    onDeleteTask = viewModel::deleteTask,
                    onToggleImportant = viewModel::toggleImportant,
                    onEditTime = { task -> editingTimeTask = task },
                    onAddTask = { showAddTask = true },
                    onRetrySave = viewModel::confirmPlan
                )
                state.isLoading -> LoadingContent()
                else -> EntryContent(
                    rawInput = state.rawInput,
                    isPreparingSession = state.isPreparingSession,
                    errorMessage = state.errorMessage,
                    onInputChange = viewModel::onInputChange,
                    onGenerate = viewModel::generatePlan,
                    onRetry = viewModel::retry
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
        }

        if (state.proposal != null) {
            ProposalFooter(
                isSaving = state.isSavingPlan,
                isConfirmed = state.isConfirmed,
                onLooksGood = viewModel::confirmPlan
            )
        }
    }

    editingTimeTask?.let { item ->
        TaskTimeDialog(
            title = item.task.title,
            initialTime = item.task.plannedStart.toLocalTimeOrDefault(),
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
                viewModel.addTask(title, hour, minute)
                showAddTask = false
            }
        )
    }
}

@Composable
private fun MorningHeader(onOpenRoutines: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BrandWordmark(modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .background(TnaColors.Surface, RoundedCornerShape(999.dp))
                .border(1.dp, TnaColors.Line, RoundedCornerShape(999.dp))
                .clickable(onClick = onOpenRoutines)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.CalendarMonth,
                    contentDescription = "打开我的日常",
                    tint = TnaColors.AccentDeep,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .width(18.dp)
                        .height(18.dp)
                )
                Text(
                    text = "我的日常",
                    style = TnaTypography.Body.copy(
                        color = TnaColors.AccentDeep,
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }
    }
    Text(
        text = "morning.",
        modifier = Modifier.padding(top = 14.dp),
        style = TnaTypography.Display
    )
}

@Composable
private fun EntryContent(
    rawInput: String,
    isPreparingSession: Boolean,
    errorMessage: String?,
    onInputChange: (String) -> Unit,
    onGenerate: () -> Unit,
    onRetry: () -> Unit
) {
    Text(
        text = "把脑子里的今天倒在这里,我帮你收成一条舒服的时间线。",
        modifier = Modifier.padding(top = 12.dp, bottom = 18.dp),
        style = TnaTypography.AiVoice
    )

    MorningInput(
        value = rawInput,
        onValueChange = onInputChange,
        placeholder = "今天上午写文档,下午3点开会,晚上想跑步",
        modifier = Modifier.height(148.dp)
    )

    if (errorMessage != null) {
        ErrorPanel(
            message = errorMessage,
            onRetry = onRetry,
            modifier = Modifier.padding(top = 12.dp)
        )
    }

    TnaButton(
        text = if (isPreparingSession) "准备中" else "生成今天",
        onClick = onGenerate,
        enabled = rawInput.isNotBlank() && !isPreparingSession,
        style = TnaButtonStyle.Primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
    )
}

@Composable
private fun LoadingContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 54.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            color = TnaColors.Accent,
            trackColor = TnaColors.AccentSoft,
            strokeWidth = 3.dp
        )
        Text(
            text = "我在把今天揉成一条顺一点的线。",
            modifier = Modifier.padding(top = 18.dp),
            style = TnaTypography.AiVoice,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ProposalContent(
    tasks: List<EditablePlanTask>,
    aiComment: String,
    isConfirmed: Boolean,
    saveErrorMessage: String?,
    onDeleteTask: (String) -> Unit,
    onToggleImportant: (String) -> Unit,
    onEditTime: (EditablePlanTask) -> Unit,
    onAddTask: () -> Unit,
    onRetrySave: () -> Unit
) {
    val sortedTasks = tasks.sortedBy { it.task.plannedStart.orEmpty() }

    SectionLabel(
        text = "today",
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
    )

    if (isConfirmed) {
        ConfirmedPanel(modifier = Modifier.padding(bottom = 10.dp))
    }

    sortedTasks.forEach { item ->
        MorningTaskCard(
            taskId = item.id,
            title = item.task.title,
            time = item.task.plannedStart.formatTime(),
            note = item.task.note,
            important = item.task.important,
            suggested = item.task.source == "ai_suggestion",
            routine = item.task.source == "routine",
            editable = !isConfirmed,
            onDelete = onDeleteTask,
            onToggleImportant = onToggleImportant,
            onEditTime = { onEditTime(item) }
        )
    }

    if (!isConfirmed) {
        AddTaskRow(
            onClick = onAddTask,
            modifier = Modifier.padding(top = 2.dp, bottom = 9.dp)
        )
    }

    SectionLabel(
        text = "ai voice",
        modifier = Modifier.padding(top = 9.dp, bottom = 7.dp)
    )
    AiVoicePanel(text = aiComment)

    if (saveErrorMessage != null) {
        ErrorPanel(
            message = saveErrorMessage,
            onRetry = onRetrySave,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}

@Composable
private fun MorningInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = TnaTypography.Body.copy(color = TnaColors.Ink),
        cursorBrush = SolidColor(TnaColors.Accent),
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 3.dp,
                shape = TnaShapes.Input,
                ambientColor = TnaColors.WarmShadow,
                spotColor = TnaColors.WarmShadow
            )
            .background(TnaColors.Surface, TnaShapes.Input)
            .border(1.dp, TnaColors.Line, TnaShapes.Input)
            .padding(horizontal = 15.dp, vertical = 14.dp),
        decorationBox = { innerTextField ->
            Box(modifier = Modifier.fillMaxSize()) {
                if (value.isEmpty()) {
                    Text(text = placeholder, style = TnaTypography.Body.copy(color = TnaColors.Muted))
                }
                innerTextField()
            }
        }
    )
}

@Composable
private fun ErrorPanel(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TnaColors.LineSoft, TnaShapes.Input)
            .border(1.dp, TnaColors.Line, TnaShapes.Input)
            .padding(14.dp)
    ) {
        Text(text = message, style = TnaTypography.AiVoice)
        Text(
            text = "再试一次",
            modifier = Modifier
                .padding(top = 8.dp)
                .clickable(onClick = onRetry),
            style = TnaTypography.Body.copy(color = TnaColors.AccentDeep, fontWeight = FontWeight.SemiBold)
        )
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
        Text(
            text = "已确认",
            style = TnaTypography.Body.copy(
                color = TnaColors.AccentDeep,
                fontWeight = FontWeight.Bold
            )
        )
        Text(
            text = "  今天就这么开始。",
            style = TnaTypography.AiVoice.copy(color = TnaColors.InkSoft)
        )
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
    onEditTime: () -> Unit = {}
) {
    val background = if (suggested) Color.Transparent else TnaColors.Surface
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 9.dp)
            .then(
                if (suggested) {
                    Modifier.dashedCardBorder()
                } else {
                    Modifier.shadow(
                        elevation = 3.dp,
                        shape = TnaShapes.Card,
                        ambientColor = TnaColors.WarmShadow,
                        spotColor = TnaColors.WarmShadow
                    )
                }
            )
            .background(background, TnaShapes.Card)
            .padding(horizontal = 15.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (important) "★" else "☆",
                modifier = Modifier
                    .padding(end = 7.dp)
                    .then(
                        if (editable) {
                            Modifier.clickable(onClick = { onToggleImportant(taskId) })
                        } else {
                            Modifier
                        }
                    ),
                style = TnaTypography.Body.copy(
                    color = if (important) TnaColors.Accent else TnaColors.Muted,
                    fontWeight = FontWeight.Bold
                )
            )
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = TnaTypography.Body.copy(
                    color = if (suggested) TnaColors.InkSoft else TnaColors.Ink,
                    fontWeight = if (suggested) FontWeight.Medium else FontWeight.Bold
                )
            )
            if (routine) {
                Text(
                    text = "routine",
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .background(TnaColors.AccentSoft, RoundedCornerShape(999.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    style = TnaTypography.Mono.copy(color = TnaColors.AccentDeep)
                )
            }
            Text(
                text = time,
                modifier = Modifier
                    .background(
                        color = if (suggested) Color.Transparent else TnaColors.AccentSoft,
                        shape = RoundedCornerShape(7.dp)
                    )
                    .then(
                        if (editable) {
                            Modifier.clickable(onClick = onEditTime)
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                style = TnaTypography.Mono.copy(
                    color = if (suggested) TnaColors.InkSoft else TnaColors.Accent
                )
            )
            if (editable) {
                Text(
                    text = "×",
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .clickable(onClick = { onDelete(taskId) })
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    style = TnaTypography.Body.copy(
                        color = TnaColors.Muted,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }

        if (!note.isNullOrBlank()) {
            Text(
                text = note,
                modifier = Modifier.padding(top = 5.dp),
                style = TnaTypography.Body.copy(color = TnaColors.InkSoft)
            )
        }
    }
}

@Composable
private fun AddTaskRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(TnaColors.LineSoft, TnaShapes.Input)
            .border(1.dp, TnaColors.Line, TnaShapes.Input)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "+",
            modifier = Modifier.padding(end = 8.dp),
            style = TnaTypography.Body.copy(
                color = TnaColors.AccentDeep,
                fontWeight = FontWeight.Bold
            )
        )
        Text(
            text = "加一项",
            style = TnaTypography.Body.copy(
                color = TnaColors.AccentDeep,
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}

@Composable
private fun TaskTimeDialog(
    title: String,
    initialTime: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    var minutesOfDay by remember { mutableStateOf(initialTime.hour * 60 + initialTime.minute) }

    TnaDialog(onDismiss = onDismiss) {
        Text(
            text = title,
            style = TnaTypography.Body.copy(
                color = TnaColors.Ink,
                fontWeight = FontWeight.Bold
            )
        )
        Spacer(modifier = Modifier.height(12.dp))
        TimeStepSelector(
            minutesOfDay = minutesOfDay,
            onMinutesChange = { minutesOfDay = it }
        )
        DialogActions(
            confirmText = "更新",
            onDismiss = onDismiss,
            onConfirm = { onConfirm(minutesOfDay / 60, minutesOfDay % 60) }
        )
    }
}

@Composable
private fun AddTaskDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Int, Int) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var minutesOfDay by remember { mutableStateOf(9 * 60) }

    TnaDialog(onDismiss = onDismiss) {
        Text(
            text = "加一项",
            style = TnaTypography.Body.copy(
                color = TnaColors.Ink,
                fontWeight = FontWeight.Bold
            )
        )
        Spacer(modifier = Modifier.height(10.dp))
        MorningInput(
            value = title,
            onValueChange = { title = it },
            placeholder = "比如:买咖啡豆",
            modifier = Modifier.height(54.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        TimeStepSelector(
            minutesOfDay = minutesOfDay,
            onMinutesChange = { minutesOfDay = it }
        )
        DialogActions(
            confirmText = "加入",
            onDismiss = onDismiss,
            onConfirm = { onConfirm(title, minutesOfDay / 60, minutesOfDay % 60) },
            confirmEnabled = title.trim().isNotEmpty()
        )
    }
}

@Composable
private fun TimeStepSelector(
    minutesOfDay: Int,
    onMinutesChange: (Int) -> Unit
) {
    val normalized = minutesOfDay.floorMod(MINUTES_PER_DAY)
    val hour = normalized / 60
    val minute = normalized % 60

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TnaColors.LineSoft, TnaShapes.Input)
            .border(1.dp, TnaColors.Line, TnaShapes.Input)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "%02d:%02d".format(hour, minute),
            style = TnaTypography.Display.copy(
                color = TnaColors.AccentDeep,
                fontWeight = FontWeight.Bold
            )
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TimeStepButton("-1h", Modifier.weight(1f)) { onMinutesChange((normalized - 60).floorMod(MINUTES_PER_DAY)) }
            TimeStepButton("-15m", Modifier.weight(1f)) { onMinutesChange((normalized - 15).floorMod(MINUTES_PER_DAY)) }
            TimeStepButton("+15m", Modifier.weight(1f)) { onMinutesChange((normalized + 15).floorMod(MINUTES_PER_DAY)) }
            TimeStepButton("+1h", Modifier.weight(1f)) { onMinutesChange((normalized + 60).floorMod(MINUTES_PER_DAY)) }
        }
    }
}

@Composable
private fun TimeStepButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .background(TnaColors.Surface, TnaShapes.Selection)
            .border(1.dp, TnaColors.Line, TnaShapes.Selection)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = TnaTypography.Mono.copy(color = TnaColors.AccentDeep)
        )
    }
}

@Composable
private fun TnaDialog(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
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
private fun DialogActions(
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TnaButton(
            text = "取消",
            onClick = onDismiss,
            style = TnaButtonStyle.Secondary,
            modifier = Modifier.weight(1f)
        )
        TnaButton(
            text = confirmText,
            onClick = onConfirm,
            enabled = confirmEnabled,
            style = TnaButtonStyle.Primary,
            modifier = Modifier.weight(1f)
        )
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
        Box(
            modifier = Modifier
                .padding(end = 11.dp)
                .background(TnaColors.Accent, RoundedCornerShape(999.dp))
                .width(3.dp)
                .height(58.dp)
        )
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = TnaTypography.AiVoice.copy(lineHeight = TnaTypography.AiVoice.lineHeight)
        )
    }
}

@Composable
private fun ProposalFooter(
    isSaving: Boolean,
    isConfirmed: Boolean,
    onLooksGood: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TnaColors.Background)
            .padding(horizontal = 20.dp)
            .padding(top = 10.dp, bottom = 22.dp)
    ) {
        Text(
            text = "想调整今天的安排?跟我说一声",
            modifier = Modifier
                .fillMaxWidth()
                .background(TnaColors.Surface, TnaShapes.Input)
                .border(1.dp, TnaColors.Line, TnaShapes.Input)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            style = TnaTypography.Body.copy(color = TnaColors.Muted)
        )
        Spacer(modifier = Modifier.height(10.dp))
        TnaButton(
            text = when {
                isConfirmed -> "已确认"
                isSaving -> "保存中"
                else -> "looks good"
            },
            onClick = onLooksGood,
            enabled = !isSaving && !isConfirmed,
            style = TnaButtonStyle.Primary,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun Modifier.dashedCardBorder(): Modifier = this.then(
    Modifier.border(0.dp, Color.Transparent, TnaShapes.Card)
).then(
    Modifier.drawBehind {
        drawRoundRect(
            color = TnaColors.Line,
            style = Stroke(
                width = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(
                    intervals = floatArrayOf(8.dp.toPx(), 6.dp.toPx()),
                    phase = 0f
                )
            ),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx(), 16.dp.toPx())
        )
    }
)

private fun String?.formatTime(): String {
    if (this.isNullOrBlank()) return "--:--"
    val raw = trim()
    if (raw.matches(Regex("^\\d{2}:\\d{2}(:\\d{2})?$"))) return raw.take(5)
    return runCatching {
        OffsetDateTime.parse(raw).format(DateTimeFormatter.ofPattern("HH:mm"))
    }.getOrElse {
        raw.substringAfter("T", raw).take(5).ifBlank { "--:--" }
    }
}

private fun String?.toLocalTimeOrDefault(): LocalTime {
    if (this.isNullOrBlank()) return LocalTime.of(9, 0)
    val raw = trim()
    if (raw.matches(Regex("^\\d{2}:\\d{2}(:\\d{2})?$"))) {
        return runCatching { LocalTime.parse(raw.take(5), DateTimeFormatter.ofPattern("HH:mm")) }
            .getOrDefault(LocalTime.of(9, 0))
    }
    return runCatching { OffsetDateTime.parse(raw).toLocalTime() }
        .getOrDefault(LocalTime.of(9, 0))
}

private fun Int.floorMod(other: Int): Int = ((this % other) + other) % other

private const val MINUTES_PER_DAY = 24 * 60
