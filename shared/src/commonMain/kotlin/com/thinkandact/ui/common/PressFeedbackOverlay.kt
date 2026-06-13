package com.thinkandact.ui.common

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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thinkandact.ui.theme.TnaTypography
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

// 视觉源 = 按住即时反馈_位置对比_demo.html（底部版）。色值照抄。
private val Amber = Color(0xFFE29A52)
private val Terra = Color(0xFFB5654A)
private val Clay = Color(0xFF9E5238)
private val Muted = Color(0xFFA7998F)
private val ScrimColor = Color(0xFFFBF5F0) // 米白

private const val BAR_COUNT = 22

/**
 * 「按住即时反馈 · 秒应」覆盖层（§规格，commonMain 双端共享）。
 *
 * 红线：纯本地、零延迟——[visible] 一 true（pointerdown 那帧）就淡入,**不等** [amp]（真实 RMS）。
 * RMS 没来时波形用 baseline 动；[amp] 一进来就接管幅度。取消区 [cancelArmed] → 灰化 + 「松开取消」,不变红。
 *
 * @param amp 归一化实时音量 0–1（来自 VoiceInputService.amp）。
 * @param anchorCenterYpx 触发按钮在根坐标里的垂直中心(px)。反馈组就地**盖住这条 bar**(无论它在上/中/下)；
 *        ≤0 时回退到底部。各屏用 onGloballyPositioned 量自己那颗 VoiceMicButton 传进来。
 */
@Composable
fun PressFeedbackOverlay(
    visible: Boolean,
    amp: Float,
    cancelArmed: Boolean,
    anchorCenterYpx: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val vis by animateFloatAsState(if (visible) 1f else 0f, tween(160), label = "fbVis")
    if (!visible && vis < 0.01f) return

    // 自己的时间源（每帧推进 sec），波形据此动——与音频/WS 解耦。
    var sec by remember { mutableStateOf(0f) }
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        var start = -1L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (start < 0L) start = now
                sec = (now - start) / 1_000_000_000f
            }
        }
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
    // 反馈组约 156dp 高,以 bar 中心为中线 → 上下各 78dp,正好盖住 ~50dp 的 bar。
    val halfPx = with(density) { 78.dp.toPx() }

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.align(Alignment.TopCenter)
                .let { m ->
                    if (anchorCenterYpx > 0f) {
                        m.offset { androidx.compose.ui.unit.IntOffset(0, (anchorCenterYpx - halfPx).toInt()) }
                    } else {
                        m.align(Alignment.BottomCenter).padding(bottom = 90.dp) // 兜底:底部
                    }
                }
                .fillMaxWidth().padding(horizontal = 16.dp).alpha(vis),
            contentAlignment = Alignment.Center,
        ) {
            // 暖底卡片就地盖住原 bar（米白近实底 → 旧 bar 文字不透出）。
            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(ScrimColor.copy(alpha = 0.97f), RoundedCornerShape(22.dp))
                    .border(1.dp, Terra.copy(alpha = 0.14f), RoundedCornerShape(22.dp))
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Waveform(sec = sec, amp = amp, armed = cancelArmed, alpha = 1f)
                Box(modifier = Modifier.height(12.dp))
                if (cancelArmed) {
                    Text("松开取消", style = TnaTypography.Body.copy(color = Muted, fontWeight = FontWeight.Medium))
                } else {
                    Text("在听,说吧", style = TnaTypography.Body.copy(color = Clay, fontWeight = FontWeight.Medium))
                    Box(modifier = Modifier.height(4.dp))
                    Text("松开结束 · 上滑取消", style = TnaTypography.Mono.copy(color = Muted))
                }
            }
        }
    }
}

/** 22 根暖色竖条,一张 Canvas 画（§2 公式照抄）。 */
@Composable
private fun Waveform(sec: Float, amp: Float, armed: Boolean, alpha: Float) {
    // RMS 没来时也得动：amp 兜一个 baseline。
    val effAmp = amp.coerceAtLeast(0.12f)
    Canvas(modifier = Modifier.size(width = (BAR_COUNT * 4 + (BAR_COUNT - 1) * 4).dp, height = 64.dp)) {
        val barW = 4.dp.toPx()
        val gap = 4.dp.toPx()
        val h = size.height
        val brush = if (armed) {
            Brush.verticalGradient(listOf(Muted.copy(alpha = 0.85f), Color(0xFF8C8079))) // 灰化(去饱和)
        } else {
            Brush.verticalGradient(listOf(Amber, Terra))
        }
        val mid = (BAR_COUNT - 1) / 2f
        for (i in 0 until BAR_COUNT) {
            val c = 1f - abs(i - mid) / mid
            val v = 0.16f + effAmp * (0.4f + 0.6f * c) * (0.6f + 0.6f * abs(sin(sec * 5f + i * 0.6f)))
            val scaleY = min(1f, v)
            val barH = h * scaleY
            val x = i * (barW + gap)
            val top = (h - barH) / 2f // transform-origin: center
            drawRoundRect(
                brush = brush,
                topLeft = Offset(x, top),
                size = Size(barW, barH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f, barW / 2f),
                alpha = alpha,
            )
        }
    }
}

/** 暖环荡出:scale .85→2.1, opacity .5→0, 1.6s 循环。 */
@Composable
private fun PulseRing(modifier: Modifier = Modifier, alpha: Float) {
    val t = rememberInfiniteTransition(label = "pulse")
    val p by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "pulseP",
    )
    Canvas(modifier = modifier) {
        val scale = 0.85f + (2.1f - 0.85f) * p
        val op = (0.5f * (1f - p)) * alpha
        val r = size.minDimension / 2f * scale
        drawCircle(
            color = Terra.copy(alpha = op),
            radius = r,
            center = Offset(size.width / 2f, size.height / 2f),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}
