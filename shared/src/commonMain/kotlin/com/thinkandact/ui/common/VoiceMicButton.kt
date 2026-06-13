package com.thinkandact.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic as MicOutlined
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thinkandact.ui.theme.TnaColors
import com.thinkandact.ui.theme.TnaTypography
import kotlinx.coroutines.withTimeoutOrNull

/** 按住判定窗：松手早于此 = 点击(给提示)，晚于此 = 按住(开录)。 */
private const val TAP_SLOP_MS = 220L

/**
 * 通用「按住说话」按钮（早上屏与我的日常共用）。
 *
 * 状态：空闲 → 准备中(连接) → 在听(录音) → 整理中(松手等 final)。
 *
 * §1 防断手势：用 [awaitEachGesture] 自己收手势，而不是 [androidx.compose.foundation.gestures.detectTapGestures]——
 * 后者一旦手指移动超过 touch-slop 就**取消**手势（按住中途抖一下/被父级可滚动容器抢走 = 提前停录、丢尾句）。
 * 这里：按下即 [androidx.compose.ui.input.pointer.PointerInputChange.consume] 抢占手势；按住期间**吃掉所有位移事件**，
 * 让滚动抖动/父级滚动都抢不走；只有「真正抬手(所有指针离开)」才结束录音。系统强制取消(失焦)时走 finally 优雅收尾(不丢已识别文字)。
 * 关键：本控件高度恒定、不在按下时改变自身布局位置，避免重组/banner 改变按钮位置导致手势中断。
 */
@Composable
fun VoiceMicButton(
    isRecording: Boolean,
    isConnecting: Boolean,
    isFinalizing: Boolean,
    onPressStart: suspend () -> Unit,
    onPressEnd: () -> Unit,
    modifier: Modifier = Modifier,
    idleLabel: String = "按住说话",
    recordingLabel: String = "在听,说吧 · 松开结束",
    /** 快速点一下(非按住)→ 给提示,而不是开一段 0 秒的录音(防"点了没反应"。 */
    onTap: () -> Unit = {},
    /** pointerdown 那一帧立刻回调(秒应):上层据此**零延迟**亮出即时反馈层,不等录音/WS。 */
    onPressDown: () -> Unit = {},
    /** 任意抬手/取消后回调:上层据此收起反馈层。 */
    onPressUp: () -> Unit = {},
    /** 上滑进入取消区后松手:中止、零改动(区别于正常 [onPressEnd] 定稿)。 */
    onCancel: () -> Unit = {},
    /** 上滑取消「待命」状态变化:上层据此把波形灰化 + 文案转「松开取消」。 */
    onCancelArmedChange: (Boolean) -> Unit = {},
    /** 线条麦克风图标(复盘 v2 底部条:去填充、用 outline mic)。 */
    micOutlined: Boolean = false,
) {
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
        label = "voicePressScale",
    )

    val active = isRecording || isFinalizing
    val label = when {
        isFinalizing -> "整理中…"
        isConnecting -> "准备中…"
        isRecording -> recordingLabel
        else -> idleLabel
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(pressScale)
            .background(if (active) TnaColors.Accent else TnaColors.Surface, RoundedCornerShape(999.dp))
            .border(1.dp, if (active) TnaColors.Accent else TnaColors.Line, RoundedCornerShape(999.dp))
            .pointerInput(isFinalizing) {
                if (isFinalizing) return@pointerInput
                val cancelPx = CANCEL_THRESHOLD_DP.dp.toPx()
                while (true) {
                    // 按下即抢占：consume 首个 down，父级可滚动容器抢不走这次手势。
                    val down = awaitPointerEventScope {
                        awaitFirstDown(requireUnconsumed = false).also { it.consume() }
                    }
                    pressed = true
                    onPressDown() // 秒应:这一帧就通知上层亮反馈层,不等录音/WS。
                    val startY = down.position.y
                    // 判定窗：若这段内就抬手 = 用户在"点击"而非"按住"，给提示、不开 0 秒录音。
                    val releasedQuickly = withTimeoutOrNull(TAP_SLOP_MS) {
                        awaitPointerEventScope { awaitReleaseConsumingMoves(startY, cancelPx) {} }
                    } != null
                    if (releasedQuickly) {
                        pressed = false
                        onPressUp()
                        onTap()
                    } else {
                        onPressStart()
                        var armed = false
                        try {
                            // 按住期间吃掉所有位移：抖动/滚动都不取消；上滑超阈值进取消待命。
                            armed = awaitPointerEventScope {
                                awaitReleaseConsumingMoves(startY, cancelPx) { onCancelArmedChange(it) }
                            }
                        } finally {
                            pressed = false
                            onCancelArmedChange(false)
                            onPressUp()
                            if (armed) onCancel() else onPressEnd()
                        }
                    }
                }
            }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        when {
            isFinalizing -> CircularProgressIndicator(
                modifier = Modifier.padding(end = 9.dp).size(16.dp),
                color = TnaColors.Surface,
                strokeWidth = 2.dp,
            )
            isConnecting -> VoiceConnectingDots(color = TnaColors.Surface, modifier = Modifier.padding(end = 9.dp))
            isRecording -> VoiceWaveform(color = TnaColors.Surface, modifier = Modifier.padding(end = 9.dp))
            else -> Icon(
                imageVector = if (micOutlined) Icons.Outlined.MicOutlined else Icons.Rounded.Mic,
                contentDescription = idleLabel,
                tint = TnaColors.AccentDeep,
                modifier = Modifier.padding(end = 8.dp).width(18.dp).height(18.dp),
            )
        }
        Text(
            text = label,
            style = TnaTypography.Body.copy(
                color = if (active) TnaColors.Surface else TnaColors.AccentDeep,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

/**
 * 等到「真正抬手」（所有指针离开）才返回；按住期间把每一次位移都 consume 掉，
 * 这样滚动抖动 / 父级可滚动容器都无法把这次按住手势抢走（§1 防断核心）。
 * 系统强制取消手势（短暂失焦）时本协程被取消，由调用方 finally 优雅收尾。
 *
 * 同时跟踪「上滑取消」：相对按下点上移超过 [cancelThresholdPx] → 进取消待命，经 [onArmedChange] 上报。
 * @return 抬手那刻是否处于取消待命（true=中止，false=正常定稿）。
 */
private suspend fun AwaitPointerEventScope.awaitReleaseConsumingMoves(
    startY: Float,
    cancelThresholdPx: Float,
    onArmedChange: (Boolean) -> Unit,
): Boolean {
    var armed = false
    while (true) {
        val event = awaitPointerEvent()
        event.changes.forEach { change ->
            // 吃掉位移，阻止父级（滚动）以"拖动"名义抢走手势。
            if (change.positionChanged()) change.consume()
        }
        event.changes.firstOrNull()?.let { ch ->
            val nowArmed = (ch.position.y - startY) < -cancelThresholdPx // 上滑(y 变小)超阈值
            if (nowArmed != armed) { armed = nowArmed; onArmedChange(armed) }
        }
        // 没有任何指针仍按下 = 真正抬手 → 结束。
        if (event.changes.none { it.pressed }) return armed
    }
}

/** 上滑取消阈值（dp）。 */
private const val CANCEL_THRESHOLD_DP = 80f

/** 「准备中」三点呼吸。 */
@Composable
private fun VoiceConnectingDots(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "voiceDots")
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 480, delayMillis = i * 140, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "voiceDot$i",
            )
            Box(modifier = Modifier.size(5.dp).background(color.copy(alpha = alpha), RoundedCornerShape(999.dp)))
        }
    }
}

/** 「在听」跳动声波。 */
@Composable
private fun VoiceWaveform(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "voiceWave")
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.5.dp)) {
        listOf(0, 120, 240, 120, 0).forEach { delay ->
            val h by transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 420, delayMillis = delay, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "voiceBar$delay",
            )
            Box(modifier = Modifier.width(2.5.dp).height((6 + 12 * h).dp).background(color, RoundedCornerShape(999.dp)))
        }
    }
}
