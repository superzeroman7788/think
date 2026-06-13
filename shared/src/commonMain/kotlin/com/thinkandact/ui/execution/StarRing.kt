package com.thinkandact.ui.execution

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 执行屏中心「星环」（§4.6 / 视觉源 v9）。双环 + 8 颗固定暖星 + 一颗暖光彗星。
 * 一张 Canvas 一次性画（双端一致）；环心文字由上层 Compose 叠（见 CurrentTaskCenter）。
 *
 * @param progress §2 线性进度 p = clamp(elapsed/duration,0,1)。彗星头角度 = p×360（0=正上方，顺时针）。
 * @param dusk 过点：整圈轻亮 + p=1。
 */
@Composable
fun StarRing(progress: Float, dusk: Boolean, modifier: Modifier = Modifier) {
    // 彗星转动：每分钟 p 变一次，1.8s 补间（§4.6）。
    val p by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(1800, easing = CubicBezierEasing(0.4f, 0f, 0.25f, 1f)),
        label = "cometP",
    )
    val duskF by animateFloatAsState(if (dusk) 1f else 0f, tween(1000), label = "ringDusk")

    val transition = rememberInfiniteTransition(label = "ring")
    // 星星闪烁相位（0..2π，6.4s 线性循环；整数倍频保证循环连续，不跳）。
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(6400, easing = LinearEasing), RepeatMode.Restart),
        label = "twinkle",
    )
    // 彗星头呼吸 scale 1⟷1.16 / 3.4s（§4.6）。
    val headPulse by transition.animateFloat(
        initialValue = 1f, targetValue = 1.16f,
        animationSpec = infiniteRepeatable(tween(3400), RepeatMode.Reverse), label = "headPulse",
    )

    Canvas(modifier = modifier) {
        val w = size.minDimension
        val c = Offset(size.width / 2f, size.height / 2f)
        // 环半径留出余量,使彗星头部 bloom 不被画布边裁掉（容器比环略大）。
        val mainR = 0.40f * w
        val outerR = 0.435f * w

        // —— 外伴轨 r48（只一道细线）——
        drawCircle(color = Color(0.710f, 0.396f, 0.290f, 0.10f), radius = outerR, center = c, style = Stroke(0.010f * w))
        // —— 主轨 r44：竖直渐变 顶淡底暖（接住下方潮水的光）——
        // 主轨(内环)整体降 ~55%,缩小与外环(.10)的反差,别太深。
        drawCircle(
            brush = Brush.verticalGradient(
                0f to Color(0.710f, 0.396f, 0.290f, 0.022f),
                0.55f to Color(0.710f, 0.396f, 0.290f, 0.054f),
                1f to Color(0.710f, 0.396f, 0.290f, 0.135f),
                startY = c.y - mainR, endY = c.y + mainR,
            ),
            radius = mainR, center = c, style = Stroke(0.019f * w),
        )

        // —— 过点整圈轻亮（§4.6 fullglow）——
        if (duskF > 0f) {
            drawCircle(color = Color(0.831f, 0.549f, 0.376f, 0.45f * duskF), radius = mainR, center = c, style = Stroke(0.024f * w))
            drawCircle(color = Color(0.831f, 0.549f, 0.376f, 0.22f * duskF), radius = mainR, center = c, style = Stroke(0.05f * w))
        }

        // —— 8 颗固定暖星（闪烁：opacity .3⟷1 + scale .8⟷1.12）——
        for (s in STARS) {
            val tw = 0.5f + 0.5f * sin(phase * s.speed + s.phase) // 0..1
            val alpha = 0.3f + 0.7f * tw
            val scale = 0.8f + 0.32f * tw
            val ang = s.angleDeg * PI.toFloat() / 180f
            val px = c.x + s.radiusFrac * w * sin(ang)
            val py = c.y - s.radiusFrac * w * cos(ang)
            drawFourStar(
                center = Offset(px, py),
                size = s.sizeFrac * w * scale,
                tiltDeg = s.tiltDeg,
                color = Color(0.965f, 0.863f, 0.682f, alpha), // #F6DCAE
            )
        }

        // —— 彗星：尾(sweepGradient 画进主轨环带) + 头(多层 bloom)，整体转到 p×360 ——
        rotate(degrees = p * 360f, pivot = c) {
            // 尾：sweep 笔触落在主轨半径上，头在正上方(Compose sweep 0=3点钟,顶=0.75)。
            drawCircle(
                brush = Brush.sweepGradient(
                    0.0f to Color.Transparent,
                    0.51f to Color.Transparent,
                    0.617f to Color(0.910f, 0.627f, 0.337f, 0.14f),  // (232,160,86)
                    0.706f to Color(0.941f, 0.690f, 0.408f, 0.44f),  // (240,176,104)
                    0.75f to Color(1.0f, 0.886f, 0.682f, 0.90f),     // (255,226,174) 头
                    0.752f to Color.Transparent,
                    1.0f to Color.Transparent,
                    center = c,
                ),
                radius = mainR, center = c, style = Stroke(0.026f * w),
            )
            // 头部 coma：三层柔光 + 径向核，位于正上方主轨上。
            val head = Offset(c.x, c.y - mainR)
            val hr = 0.028f * w * headPulse
            drawCircle(Color(0.886f, 0.604f, 0.322f, 0.18f), 3.0f * hr, head) // bloom3 最外层光晕：更淡
            drawCircle(Color(0.933f, 0.612f, 0.259f, 0.55f), 1.9f * hr, head) // bloom2
            drawCircle(Color(1.0f, 0.906f, 0.690f, 0.90f), 1.25f * hr, head)  // bloom1
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color(1.0f, 0.969f, 0.925f),     // #FFF7EC
                    0.32f to Color(0.988f, 0.816f, 0.549f), // #FCD08C
                    0.64f to Color(0.933f, 0.612f, 0.259f), // #EE9C42
                    1f to Color(0.933f, 0.612f, 0.259f, 0f),
                    center = head, radius = hr,
                ),
                radius = hr, center = head,
            )
        }
    }
}

/** 四角星（§4.6 polygon），中心对齐，按 tilt 旋转。 */
private fun DrawScope.drawFourStar(center: Offset, size: Float, tiltDeg: Float, color: Color) {
    // polygon(50% 0,61% 39,100 50,61 61,50 100,39 61,0 50,39 39) → 相对中心(.5,.5)
    val pts = listOf(
        0.0f to -0.5f, 0.11f to -0.11f, 0.5f to 0.0f, 0.11f to 0.11f,
        0.0f to 0.5f, -0.11f to 0.11f, -0.5f to 0.0f, -0.11f to -0.11f,
    )
    val t = tiltDeg * PI.toFloat() / 180f
    val cosT = cos(t); val sinT = sin(t)
    val path = Path()
    pts.forEachIndexed { i, (ux, uy) ->
        val x = (ux * cosT - uy * sinT) * size + center.x
        val y = (ux * sinT + uy * cosT) * size + center.y
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color = color)
}

private data class StarSpec(
    val angleDeg: Float, val radiusFrac: Float, val sizeFrac: Float,
    val tiltDeg: Float, val speed: Float, val phase: Float,
)

/** 8 颗固定星（§4.6：位置写死,真机不抖；角度大致均分 + 抖动,半径≈.40w±,大小 .035–.06w）。 */
private val STARS = listOf(
    StarSpec(18f, 0.36f, 0.045f, -20f, 1f, 0.0f),
    StarSpec(70f, 0.38f, 0.055f, 30f, 2f, 1.1f),
    StarSpec(112f, 0.35f, 0.035f, -40f, 1f, 2.3f),
    StarSpec(158f, 0.37f, 0.050f, 15f, 2f, 3.0f),
    StarSpec(203f, 0.36f, 0.040f, -10f, 1f, 4.2f),
    StarSpec(250f, 0.39f, 0.060f, 40f, 2f, 5.0f),
    StarSpec(297f, 0.35f, 0.045f, -35f, 1f, 0.6f),
    StarSpec(340f, 0.37f, 0.050f, 25f, 2f, 2.0f),
)
