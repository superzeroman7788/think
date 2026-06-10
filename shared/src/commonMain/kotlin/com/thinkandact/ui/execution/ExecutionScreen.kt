package com.thinkandact.ui.execution

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.thinkandact.data.remote.AddedReviseDto
import com.thinkandact.data.remote.RevisionDto
import com.thinkandact.data.remote.TaskRowDto
import com.thinkandact.ui.common.TnaButton
import com.thinkandact.ui.common.TnaButtonStyle
import com.thinkandact.ui.common.PressFeedbackOverlay
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

// 设计 token（规格附录）
private val Amber = Color(0xFFD98A3A)
// 环心倒计时数字色 —— 暖陶土 clay #9E5238（规格：不发红、不闪烁）
private val Clay = Color(0xFF9E5238)
// 「超」字底色 —— rgba(181,101,74,.55)，柔暖半透、无压力感
private val OverTint = Color(0xFFB5654A)

@Composable
fun ExecutionScreen(
    onBack: () -> Unit,
    onOpenReview: () -> Unit = {},
    onOpenFullPlan: () -> Unit = {},
    onOpenInbox: () -> Unit = {},
    viewModel: ExecutionViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val micController = rememberMicPermissionController()
    val cur = state.currentTask
    val important = cur?.important == true

    // 按住即时反馈(秒应):纯本地,pointerdown 即亮、与录音/WS 解耦。
    val amp by viewModel.amp.collectAsState()
    var fbVisible by remember { mutableStateOf(false) }
    var fbArmed by remember { mutableStateOf(false) }
    var barCenterY by remember { mutableStateOf(0f) } // 量「想调整」语音条中心 → 反馈层就地盖住它

    // ★ 系统提醒:首次进执行屏申请通知权限 + 国内 OEM 白名单引导(只一次)。
    val notifPerm = com.thinkandact.reminders.rememberNotificationPermission()
    var showBgGuide by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        notifPerm.request()
        if (viewModel.shouldShowBgGuide()) showBgGuide = true
    }

    // VM 被 Activity 级 store 持有、不随导航销毁 → 每次进「今天」都重拉,避免显示旧计划（bug#1）。
    LaunchedEffect(Unit) { viewModel.load() }

    val onRevisePressStart: suspend () -> Unit = {
        if (!state.isReviseFinalizing && !state.isProposing && state.proposal == null) {
            if (micController.request()) viewModel.startReviseVoice() else viewModel.dismissReviseHint()
        }
    }
    val onRevisePressEnd: () -> Unit = { viewModel.stopReviseVoice() }

    // 量出「跳过/完成 下面那条横线」在整屏里的位置，把潮水压在它之下。
    var rootHeightPx by remember { mutableStateOf(0f) }
    var dividerYPx by remember { mutableStateOf(0f) }
    val tideTopBound = if (rootHeightPx > 0f && dividerYPx > 0f) {
        (dividerYPx / rootHeightPx).coerceIn(0f, 0.95f)
    } else {
        0.78f // 量到之前的合理兜底
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TnaColors.Background)
            .onGloballyPositioned { rootHeightPx = it.size.height.toFloat() },
    ) {
        // 潮水背景（仅当有当前任务时跑，§7 省电）
        if (cur != null) {
            TideBackground(
                topBoundFraction = tideTopBound,
                targetLevel = state.tideLevel,
                dusk = state.tideDusk,
                important = important,
                completing = state.isCompleting,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            ExecutionHeader(progress = state.progressCount, total = state.totalCount, onBack = onBack, onOpenReview = onOpenReview, onOpenFullPlan = onOpenFullPlan, onOpenInbox = onOpenInbox, inboxBadge = state.inboxBadge)

            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    state.isLoading -> CenterNote("正在拉今天的安排…", spinner = true)
                    state.tasks.isEmpty() -> CenterNote("今天还没有安排。先去早上排一下吧。")
                    state.allCleared -> CenterNote("今天的安排都走完了。\n剩下的时间,松一口气。")
                    cur != null -> CurrentTaskCenter(
                        task = cur,
                        important = important,
                        progress = state.tideProgress,
                        dusk = state.tideDusk,
                    )
                }
                if (state.isCompleting) CompletionOverlay()
            }

            ExecutionFooter(
                hasCurrent = cur != null && !state.isCompleting,
                isBusy = state.pendingTaskId != null,
                isReviseRecording = state.isReviseRecording,
                isReviseConnecting = state.isReviseConnecting,
                isReviseFinalizing = state.isReviseFinalizing,
                isProposing = state.isProposing,
                reviseTranscript = state.reviseTranscript,
                reviseHint = state.reviseHint,
                onDone = viewModel::completeCurrent,
                onSkip = viewModel::skipCurrent,
                onRevisePressStart = onRevisePressStart,
                onRevisePressEnd = onRevisePressEnd,
                onDismissReviseHint = viewModel::dismissReviseHint,
                onReviseTap = viewModel::onReviseTap,
                onRevisePressDown = { fbVisible = true },
                onRevisePressUp = { fbVisible = false; fbArmed = false },
                onReviseCancel = viewModel::cancelReviseVoice,
                onReviseCancelArmedChange = { fbArmed = it },
                onVoiceBarCenter = { barCenterY = it },
                onDividerPositioned = { dividerYPx = it },
            )
        }

        state.errorMessage?.let { msg ->
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 110.dp)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = msg,
                    style = TnaTypography.AiVoice.copy(color = TnaColors.AccentDeep),
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "再试一次",
                    modifier = Modifier.padding(top = 8.dp).clickable { viewModel.load() },
                    style = TnaTypography.Body.copy(color = TnaColors.AccentDeep, fontWeight = FontWeight.SemiBold),
                    textAlign = TextAlign.Center,
                )
            }
        }

        // 即时反馈覆盖层(就地盖住「想调整」语音条,不抢已在进行的按住手势)。
        PressFeedbackOverlay(visible = fbVisible, amp = amp, cancelArmed = fbArmed, anchorCenterYpx = barCenterY)

        // ★ 提醒白名单引导(一次性、温和)
        if (showBgGuide) {
            BackgroundGuideDialog(
                onSettings = { viewModel.markBgGuideShown(); showBgGuide = false; viewModel.openBgSettings() },
                onDismiss = { viewModel.markBgGuideShown(); showBgGuide = false },
            )
        }
    }

    state.proposal?.let { proposal ->
        ReviseDiffDialog(
            summary = proposal.summary,
            revisions = proposal.revisions,
            added = proposal.added,
            warnings = proposal.warnings,
            isApplying = state.isApplying,
            onCancel = viewModel::cancelProposal,
            onApply = viewModel::applyProposal,
        )
    }
}

@Composable
private fun ExecutionHeader(progress: Int, total: Int, onBack: () -> Unit, onOpenReview: () -> Unit, onOpenFullPlan: () -> Unit = {}, onOpenInbox: () -> Unit = {}, inboxBadge: Int = 0) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .background(TnaColors.Surface.copy(alpha = 0.72f), RoundedCornerShape(999.dp))
                .clickable(onClick = onBack)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(text = "←", style = TnaTypography.Body.copy(color = TnaColors.InkSoft))
        }
        Text(text = "今天", modifier = Modifier.padding(start = 12.dp).weight(1f), style = TnaTypography.Display.copy(fontWeight = FontWeight.Bold))
        // 收件箱入口 + 角标(§六:count=pending 且 due≤今天;0 不显示)。
        Box(modifier = Modifier.padding(end = 8.dp)) {
            Box(
                modifier = Modifier
                    .background(TnaColors.Surface.copy(alpha = 0.72f), RoundedCornerShape(999.dp))
                    .clickable(onClick = onOpenInbox)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(text = "▤", style = TnaTypography.Body.copy(color = TnaColors.AccentDeep))
            }
            if (inboxBadge > 0) {
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp)
                        .background(TnaColors.Accent, RoundedCornerShape(999.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                ) { Text(text = if (inboxBadge > 9) "9+" else "$inboxBadge", style = TnaTypography.Mono.copy(color = Color.White, fontSize = 10.sp)) }
            }
        }
        // 完整计划入口(列表图标):拉远看整天 + 随时改。
        Box(
            modifier = Modifier
                .padding(end = 8.dp)
                .background(TnaColors.Surface.copy(alpha = 0.72f), RoundedCornerShape(999.dp))
                .clickable(onClick = onOpenFullPlan)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Icon(imageVector = Icons.Rounded.FormatListBulleted, contentDescription = "完整计划", tint = TnaColors.AccentDeep, modifier = Modifier.width(18.dp).height(18.dp))
        }
        Box(
            modifier = Modifier
                .background(TnaColors.Surface.copy(alpha = 0.72f), RoundedCornerShape(999.dp))
                .clickable(onClick = onOpenReview)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(text = "复盘", style = TnaTypography.Body.copy(color = TnaColors.AccentDeep, fontWeight = FontWeight.SemiBold))
        }
        if (total > 0) {
            Text(
                text = "$progress / $total",
                modifier = Modifier.padding(start = 8.dp).background(TnaColors.Surface.copy(alpha = 0.72f), RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                style = TnaTypography.Mono.copy(color = TnaColors.Accent, fontWeight = FontWeight.Bold),
            )
        }
    }
}

/**
 * v9 中心：现在 / ★重要 / 星环（潮水进度，不动）/ 环心三态分钟倒计时 / 标题(浮动 + ★暖晕)。
 * 倒计时**时钟制·无状态**：每次由 planned_start / planned_end 与 now 当场算，不引本地计时状态。
 */
@Composable
private fun CurrentTaskCenter(
    task: TaskRowDto,
    important: Boolean,
    progress: Float,
    dusk: Boolean,
) {
    val infinite = rememberInfiniteTransition(label = "center")
    // 标题轻浮（§4 demo .title float，0⟷-4，8s）
    val floatY by infinite.animateFloat(
        initialValue = 0f, targetValue = -4f,
        animationSpec = infiniteRepeatable(tween(4000), RepeatMode.Reverse), label = "float",
    )

    // 时钟源：每 5s 重读系统时间。显示值用整分钟（ceil），故只在跨分钟时才变，不跳秒。
    var nowSec by remember { mutableStateOf(Clock.System.now().epochSeconds) }
    LaunchedEffect(task.id, task.plannedStart, task.plannedDuration) {
        while (true) {
            nowSec = Clock.System.now().epochSeconds
            kotlinx.coroutines.delay(5_000)
        }
    }
    val countdown = remember(task.plannedStart, task.plannedDuration, nowSec) {
        ringCountdown(task.plannedStart, task.plannedDuration, nowSec)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 20.dp, start = 24.dp, end = 24.dp)) {
        Text(text = "现在", style = TnaTypography.Mono.copy(color = TnaColors.Accent.copy(alpha = 0.7f)))
        if (important) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "★ 重要", style = TnaTypography.Body.copy(color = Amber, fontWeight = FontWeight.SemiBold))
        }
        Spacer(modifier = Modifier.height(10.dp))
        // 星环（§4.6）：双环 + 8 星 + 彗星；潮水进度沿用定稿。环心 = 三态分钟倒计时。
        Box(modifier = Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            StarRing(progress = progress, dusk = dusk, modifier = Modifier.fillMaxSize())
            RingCenterCountdown(countdown)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(contentAlignment = Alignment.Center) {
            // ★ 标题暖晕（§4.5）
            if (important) {
                Box(
                    modifier = Modifier
                        .size(width = 230.dp, height = 120.dp)
                        .drawBehind {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(Color(0.906f, 0.667f, 0.424f, 0.34f), Color(0.906f, 0.667f, 0.424f, 0f)),
                                    radius = size.minDimension / 2f,
                                ),
                            )
                        },
                )
            }
            Text(
                text = task.title,
                modifier = Modifier.offset(y = floatY.dp),
                style = TnaTypography.Display.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 环心三态倒计时（时钟制·无状态的纯结果）。null = 无固定时间任务，不显示倒计时。 */
private sealed interface RingCountdown {
    /** now < planned_start：大「X 分」+ 小「距开始」。 */
    data class Before(val minutes: Int) : RingCountdown
    /** planned_start ≤ now < planned_end：大「X 分」+ 小「距结束」/「快到时间了」(X≤10)。 */
    data class During(val minutes: Int) : RingCountdown
    /** now ≥ planned_end 未完成：三行「超」/「X 分」/「该收尾了?」。 */
    data class Over(val minutes: Int) : RingCountdown
}

/**
 * 纯函数：由 planned_start / planned_duration 与 now（epoch 秒）当场算三态。
 * 无 planned_start 或无 planned_duration → null（不显示倒计时，更不显负数）。
 * X 一律 ⌈差/60⌉（正向），只跳分钟。
 */
private fun ringCountdown(plannedStart: String?, plannedDuration: Int?, nowSec: Long): RingCountdown? {
    val startSec = plannedStart?.let { s -> runCatching { Instant.parse(s).epochSeconds }.getOrNull() } ?: return null
    val durMin = plannedDuration ?: return null
    if (durMin <= 0) return null
    val endSec = startSec + durMin * 60L
    fun ceilMin(diffSec: Long): Int = ((diffSec.coerceAtLeast(0L) + 59) / 60).toInt()
    return when {
        nowSec < startSec -> RingCountdown.Before(ceilMin(startSec - nowSec).coerceAtLeast(1))
        nowSec < endSec -> RingCountdown.During(ceilMin(endSec - nowSec).coerceAtLeast(1))
        else -> RingCountdown.Over(ceilMin(nowSec - endSec).coerceAtLeast(1))
    }
}

/** 环心渲染：三态文字。无固定时间(null) → 环心留空（按普通任务渲染）。 */
@Composable
private fun RingCenterCountdown(cd: RingCountdown?) {
    when (cd) {
        null -> Unit // 无固定时间：不出现倒计时
        is RingCountdown.Before -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BigMinutes(cd.minutes)
            Spacer(modifier = Modifier.height(6.dp))
            RingSub(text = "距开始", near = false)
        }
        is RingCountdown.During -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BigMinutes(cd.minutes)
            Spacer(modifier = Modifier.height(6.dp))
            if (cd.minutes <= 10) RingSub(text = "快到时间了", near = true)
            else RingSub(text = "距结束", near = false)
        }
        is RingCountdown.Over -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 「超」—— 柔暖陶土半透、字距 3，不刺眼、无压力感。
            Text(
                text = "超",
                style = TnaTypography.AiVoice.copy(
                    fontSize = 14.sp,
                    color = OverTint.copy(alpha = 0.55f),
                    letterSpacing = 3.sp,
                ),
            )
            Spacer(modifier = Modifier.height(2.dp))
            BigMinutes(cd.minutes)
            Spacer(modifier = Modifier.height(4.dp))
            // 温和、带问号，不是命令/失败。
            RingSub(text = "该收尾了?", near = false)
        }
    }
}

/** 大数字（mono clay）+ 小一号「分」(opacity .66)。 */
@Composable
private fun BigMinutes(minutes: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = minutes.toString(),
            style = TnaTypography.Mono.copy(color = Clay, fontSize = 44.sp, fontWeight = FontWeight.Medium),
        )
        Text(
            text = "分",
            modifier = Modifier.padding(start = 3.dp, bottom = 6.dp),
            style = TnaTypography.Body.copy(color = Clay.copy(alpha = 0.66f), fontSize = 18.sp),
        )
    }
}

/** 小字副标题（距开始/距结束/快到时间了/该收尾了?）。 */
@Composable
private fun RingSub(text: String, near: Boolean) {
    Text(
        text = text,
        style = TnaTypography.AiVoice.copy(
            fontSize = 12.sp,
            color = if (near) Clay.copy(alpha = 0.85f) else TnaColors.Ink.copy(alpha = 0.6f),
        ),
    )
}

/** 完成态覆盖（§4.4）：暖涟漪一圈 + 「✓ 已完成」轻现。 */
@Composable
private fun CompletionOverlay() {
    val rip by animateFloatAsState(targetValue = 1f, animationSpec = tween(1000), label = "ripple")
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val scale = 1f + 14f * rip
            val baseR = 12.dp.toPx()
            drawCircle(
                color = Color(0.878f, 0.675f, 0.557f, 0.55f * (1f - rip)),
                radius = baseR * scale,
                center = Offset(size.width / 2f, size.height * 0.44f),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        Text(
            text = "✓ 已完成",
            modifier = Modifier.alpha((rip * 1.6f).coerceIn(0f, 1f).let { if (rip > 0.7f) (1f - (rip - 0.7f) / 0.3f).coerceIn(0f, 1f) else it }),
            style = TnaTypography.Display.copy(color = TnaColors.Accent, fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun CenterNote(text: String, spinner: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp)) {
        if (spinner) {
            CircularProgressIndicator(color = TnaColors.Accent, trackColor = TnaColors.AccentSoft, strokeWidth = 3.dp)
            Spacer(modifier = Modifier.height(16.dp))
        }
        Text(text = text, style = TnaTypography.AiVoice, textAlign = TextAlign.Center)
    }
}

/** 底部布局（§5）：跳过/完成 + 淡出分隔线 + 弱化语音条(块二入口)。 */
@Composable
private fun ExecutionFooter(
    hasCurrent: Boolean,
    isBusy: Boolean,
    isReviseRecording: Boolean,
    isReviseConnecting: Boolean,
    isReviseFinalizing: Boolean,
    isProposing: Boolean,
    reviseTranscript: String,
    reviseHint: String?,
    onDone: () -> Unit,
    onSkip: () -> Unit,
    onRevisePressStart: suspend () -> Unit,
    onRevisePressEnd: () -> Unit,
    onDismissReviseHint: () -> Unit,
    onReviseTap: () -> Unit,
    onRevisePressDown: () -> Unit,
    onRevisePressUp: () -> Unit,
    onReviseCancel: () -> Unit,
    onReviseCancelArmedChange: (Boolean) -> Unit,
    onVoiceBarCenter: (Float) -> Unit,
    onDividerPositioned: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 22.dp)) {
        if (hasCurrent) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                FooterPill(text = "跳过", bg = TnaColors.Surface.copy(alpha = 0.9f), fg = TnaColors.Ink, bold = false, enabled = !isBusy, onClick = onSkip, modifier = Modifier.weight(1f))
                FooterPill(text = if (isBusy) "记一下…" else "完成", bg = Color.White, fg = TnaColors.Accent, bold = true, enabled = !isBusy, onClick = onDone, modifier = Modifier.weight(1f))
            }
            // 两端淡出的暖色分隔线（§5）；量它的位置,潮水压在它之下。
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 38.dp, vertical = 22.dp).height(1.dp)
                    .onGloballyPositioned { onDividerPositioned(it.positionInRoot().y) }
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0.47f, 0.31f, 0.235f, 0.16f), Color.Transparent))),
            )
        } else {
            Spacer(modifier = Modifier.height(10.dp))
        }

        // 区二·语音条(块二入口)，明显比按钮弱（§5）。恒定 alpha：不在按下瞬间切换图层,
        // 避免触发重组/图层重建影响按住手势的命中（bug#2）。
        Box(
            modifier = Modifier.alpha(0.85f)
                .onGloballyPositioned { onVoiceBarCenter(it.positionInRoot().y + it.size.height / 2f) },
        ) {
            VoiceMicButton(
                isRecording = isReviseRecording,
                isConnecting = isReviseConnecting,
                isFinalizing = isReviseFinalizing,
                onPressStart = onRevisePressStart,
                onPressEnd = onRevisePressEnd,
                onTap = onReviseTap,
                onPressDown = onRevisePressDown,
                onPressUp = onRevisePressUp,
                onCancel = onReviseCancel,
                onCancelArmedChange = onReviseCancelArmedChange,
                idleLabel = "想调整今天?按住跟我说",
                recordingLabel = "在听,说吧 · 松开重排",
            )
        }
        // 固定高度槽：录音时状态行只填进这里、不改变页脚高度，避免按住时按钮上移失焦。
        Box(modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 8.dp)) {
            when {
                isProposing -> ReviseStatus(text = "在排…", spinner = true)
                isReviseFinalizing -> ReviseStatus(text = "整理中…", spinner = true)
                isReviseRecording -> ReviseStatus(text = reviseTranscript.ifBlank { "在听,说吧…松开重排" }, spinner = false)
            }
        }
        reviseHint?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().background(TnaColors.LineSoft, TnaShapes.Input).border(1.dp, TnaColors.Line, TnaShapes.Input).padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = it, modifier = Modifier.weight(1f), style = TnaTypography.AiVoice)
                Text(text = "知道了", modifier = Modifier.padding(start = 8.dp).clickable(onClick = onDismissReviseHint), style = TnaTypography.Body.copy(color = TnaColors.AccentDeep, fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

@Composable
private fun FooterPill(text: String, bg: Color, fg: Color, bold: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = TnaTypography.Body.copy(color = fg, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))
    }
}

@Composable
private fun ReviseStatus(text: String, spinner: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().background(TnaColors.AccentSoft, TnaShapes.Input).border(1.dp, TnaColors.Accent.copy(alpha = 0.28f), TnaShapes.Input).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (spinner) {
            CircularProgressIndicator(modifier = Modifier.padding(end = 9.dp).size(15.dp), color = TnaColors.AccentDeep, strokeWidth = 2.dp)
        }
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = TnaTypography.Body.copy(color = TnaColors.Ink),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 块二硬规矩：改前必出前后对照，确认才生效。 */
@Composable
private fun ReviseDiffDialog(
    summary: String,
    revisions: List<RevisionDto>,
    added: List<AddedReviseDto>,
    warnings: List<String>,
    isApplying: Boolean,
    onCancel: () -> Unit,
    onApply: () -> Unit,
) {
    val changes = revisions.filter { it.change == "moved" || it.change == "dropped" }
    val unchangedCount = revisions.count { it.change == "unchanged" }
    Dialog(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier.fillMaxWidth().background(TnaColors.Surface, TnaShapes.Card).border(1.dp, TnaColors.Line, TnaShapes.Card).padding(18.dp),
        ) {
            Text(text = "想这么改", style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold))
            if (summary.isNotBlank()) {
                Text(text = summary, modifier = Modifier.padding(top = 8.dp), style = TnaTypography.AiVoice.copy(color = TnaColors.InkSoft))
            }
            Spacer(modifier = Modifier.height(12.dp))
            changes.forEach { r -> DiffRow(r); Spacer(modifier = Modifier.height(8.dp)) }
            added.forEach { a ->
                AddedRow(a)
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (unchangedCount > 0) {
                Text(text = "其余 $unchangedCount 项不变。", style = TnaTypography.Mono.copy(color = TnaColors.Muted))
            }
            warnings.forEach { w ->
                Text(text = "· $w", modifier = Modifier.padding(top = 6.dp), style = TnaTypography.AiVoice.copy(color = TnaColors.AccentDeep))
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TnaButton(text = "取消", onClick = onCancel, enabled = !isApplying, style = TnaButtonStyle.Secondary, modifier = Modifier.weight(1f))
                TnaButton(text = if (isApplying) "调整中…" else "应用", onClick = onApply, enabled = !isApplying, style = TnaButtonStyle.Primary, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AddedRow(a: AddedReviseDto) {
    Row(
        modifier = Modifier.fillMaxWidth().background(TnaColors.AccentSoft.copy(alpha = 0.35f), TnaShapes.Input).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = a.title, style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.SemiBold))
            val time = a.plannedStart.formatTime()
            Text(
                text = if (time != "--:--") "新增 · $time" else "新增到今天的安排",
                modifier = Modifier.padding(top = 3.dp),
                style = TnaTypography.Mono.copy(color = TnaColors.AccentDeep),
            )
        }
        Text(text = "+", style = TnaTypography.Body.copy(color = TnaColors.Accent, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun DiffRow(r: RevisionDto) {
    Row(
        modifier = Modifier.fillMaxWidth().background(TnaColors.LineSoft, TnaShapes.Input).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = r.title, style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.SemiBold))
            val detail = when (r.change) {
                "moved" -> "${r.before.plannedStart.formatTime()} → ${r.after.plannedStart.formatTime()}"
                "dropped" -> "不做了"
                else -> ""
            }
            if (detail.isNotBlank()) {
                Text(text = detail, modifier = Modifier.padding(top = 3.dp), style = TnaTypography.Mono.copy(color = if (r.change == "dropped") TnaColors.Muted else TnaColors.AccentDeep))
            }
        }
        Text(
            text = if (r.change == "dropped") "✕" else "→",
            style = TnaTypography.Body.copy(color = if (r.change == "dropped") TnaColors.Muted else TnaColors.Accent, fontWeight = FontWeight.Bold),
        )
    }
}

private fun String?.formatTime(): String {
    if (this.isNullOrBlank()) return "--:--"
    val raw = trim()
    if (raw.matches(Regex("^\\d{2}:\\d{2}(:\\d{2})?$"))) return raw.take(5)
    return runCatching {
        val dt = Instant.parse(raw).toLocalDateTime(TimeZone.currentSystemDefault())
        dt.hour.toString().padStart(2, '0') + ":" + dt.minute.toString().padStart(2, '0')
    }.getOrElse { raw.substringAfter("T", raw).take(5).ifBlank { "--:--" } }
}

/** ★ 提醒白名单引导(一次性、温和、不说教)。 */
@Composable
private fun BackgroundGuideDialog(onSettings: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().background(TnaColors.Surface, TnaShapes.Card)
                .border(1.dp, TnaColors.Line, TnaShapes.Card).padding(18.dp),
        ) {
            Text("想让 ★ 重要任务到点提醒你", style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold))
            Text(
                "有些手机会在后台把提醒掐掉。开一下「自启动 / 允许后台 / 不优化电池」,到点就能稳稳响。",
                modifier = Modifier.padding(top = 8.dp),
                style = TnaTypography.AiVoice.copy(color = TnaColors.InkSoft),
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TnaButton(text = "知道了", onClick = onDismiss, style = TnaButtonStyle.Secondary, modifier = Modifier.weight(1f))
                TnaButton(text = "去设置", onClick = onSettings, style = TnaButtonStyle.Primary, modifier = Modifier.weight(1f))
            }
        }
    }
}
