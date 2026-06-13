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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * 执行屏「时间流逝」潮水背景（commonMain，双端共用 Canvas）。
 *
 * 严格照《实现规格(定稿)》§3 的色值/时长；水位由 [targetLevel] 给(真实计划时间映射见 VM §2)。
 * 红线(§1)：无红色、无闪烁/脉冲、平滑过渡、过点只转「暖暮色」不报警。
 */
/**
 * §3.0 全局强度旋钮：乘到水所有层(水体+柔光+软浪)的 alpha。
 * demo=1.0；真机 OLED/广色域 + Compose alpha 合成偏浓,起始 0.65,真机滑到像 v5/v6 那种淡为止。
 * **只动这一个数**——色相/时长/水位曲线/布局都不动。
 */
const val WATER_INTENSITY = 0.65f

@Composable
fun TideBackground(
    targetLevel: Float,
    dusk: Boolean,
    important: Boolean,
    completing: Boolean,
    modifier: Modifier = Modifier,
    intensity: Float = WATER_INTENSITY,
    /** 潮水最高只能涨到屏高的这个比例处（0=屏顶，默认 0=不限）。用来把涨潮压在「跳过/完成」下面那条横线之下。 */
    topBoundFraction: Float = 0f,
) {
    // 水位平滑补间：常态 1.8s，完成退潮 0.8s（§2 / §4.4），曲线 cubic-bezier(.4,0,.25,1)。
    val easing = CubicBezierEasing(0.4f, 0f, 0.25f, 1f)
    val level by animateFloatAsState(
        targetValue = targetLevel,
        animationSpec = tween(durationMillis = if (completing) 800 else 1800, easing = easing),
        label = "tideLevel",
    )
    // 暮色 1.4s 颜色补间（§3.1）。
    val duskF by animateFloatAsState(
        targetValue = if (dusk) 1f else 0f,
        animationSpec = tween(1400),
        label = "duskFrac",
    )
    // ★ 重要：水光浓一档，1s 渐变（§4.5）。
    val importantF by animateFloatAsState(
        targetValue = if (important) 1f else 0f,
        animationSpec = tween(1000),
        label = "importantFrac",
    )

    val transition = rememberInfiniteTransition(label = "tide")
    // 呼吸：整片水 0.82⟷1.0，9s（§3.2）。
    val breathe by transition.animateFloat(
        initialValue = 0.82f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(9000), RepeatMode.Reverse), label = "breathe",
    )
    // 柔光漂移（§3.3）：g1 24s、g2 30s，各自 0→峰值→0。
    val drift1 by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(24000), RepeatMode.Reverse), label = "drift1",
    )
    val drift2 by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(30000), RepeatMode.Reverse), label = "drift2",
    )
    // 软浪横移（§3.4）：24s 线性，无缝循环。
    val wave by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(24000, easing = LinearEasing), RepeatMode.Restart), label = "wave",
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sx = w / 302f
        val sy = h / 638f
        val lv = level.coerceIn(0f, 0.95f)
        // 把潮水关进「横线以下」的带子里：level 0..1 映射到 [boundTop, 底]，水面永不越过 boundTop。
        val boundTop = topBoundFraction.coerceIn(0f, 1f) * h
        val waterTop = boundTop + (h - boundTop) * (1f - lv)

        // —— 水体：底→顶羽化，常态/暮色按 duskF 叠加，呼吸控总透明 ——
        val waterRect = Size(w, h - waterTop)
        val topLeft = Offset(0f, waterTop)
        if (duskF < 1f) {
            drawRect(
                brush = normalBody(waterTop, h, intensity),
                topLeft = topLeft, size = waterRect,
                alpha = breathe * (1f - duskF),
            )
        }
        if (duskF > 0f) {
            drawRect(
                brush = duskBody(waterTop, h, intensity),
                topLeft = topLeft, size = waterRect,
                alpha = breathe * duskF,
            )
        }

        // —— 软浪：水面上一道暗示（§3.4），低透明、横移无缝；整层 opacity ×intensity ——
        drawWave(w, sx, waterTop, wave, intensity)

        // —— 两团柔光（§3.3 / §4.5 ★ 浓一档）：径向渐变软圆，锚在水面附近 ——
        // g1：240×130，左偏 -40，水面上方约 -34，幅度 translate(46,-12)；alpha ×intensity
        val g1c = lerpColor(Color(0.878f, 0.675f, 0.557f, 0.30f), Color(0.902f, 0.690f, 0.518f, 0.40f), importantF).scaleAlpha(intensity)
        drawGlow(
            color = g1c,
            cx = (-40f + 240f / 2f) * sx + 46f * sx * drift1,
            cy = waterTop + (-34f + 130f / 2f) * sy - 12f * sy * drift1,
            r = 240f / 2f * sx,
        )
        // g2：220×110，右偏 -46，约 -12，幅度 translate(-40,-8)；alpha ×intensity
        val g2c = lerpColor(Color(0.792f, 0.525f, 0.408f, 0.24f), Color(0.831f, 0.549f, 0.376f, 0.32f), importantF).scaleAlpha(intensity)
        drawGlow(
            color = g2c,
            cx = w - (-46f + 220f / 2f) * sx - 40f * sx * drift2,
            cy = waterTop + (-12f + 110f / 2f) * sy - 8f * sy * drift2,
            r = 220f / 2f * sx,
        )
    }
}

/** 水体常态垂直渐变（§3.1）：底(1f)实 → 顶(0f)透明；alpha ×intensity（§3.0）。 */
private fun normalBody(top: Float, h: Float, k: Float): Brush = Brush.verticalGradient(
    colorStops = arrayOf(
        0f to Color.Transparent,
        0.26f to Color(0.855f, 0.651f, 0.525f, 0.10f * k), // rgba(218,166,134,.10) @74%
        0.60f to Color(0.792f, 0.541f, 0.416f, 0.22f * k), // rgba(202,138,106,.22) @40%
        1f to Color(0.745f, 0.455f, 0.337f, 0.34f * k),    // rgba(190,116,86,.34) @0%
    ),
    startY = top, endY = h,
)

/** 水体暮色垂直渐变（§3.1 dusk）；alpha ×intensity。 */
private fun duskBody(top: Float, h: Float, k: Float): Brush = Brush.verticalGradient(
    colorStops = arrayOf(
        0f to Color.Transparent,
        0.20f to Color(0.784f, 0.557f, 0.439f, 0.11f * k), // rgba(200,142,112,.11) @80%
        0.56f to Color(0.682f, 0.424f, 0.314f, 0.26f * k), // rgba(174,108,80,.26) @44%
        1f to Color(0.588f, 0.329f, 0.235f, 0.40f * k),    // rgba(150,84,60,.40) @0%
    ),
    startY = top, endY = h,
)

private fun Color.scaleAlpha(k: Float): Color = copy(alpha = alpha * k)

private fun DrawScope.drawGlow(color: Color, cx: Float, cy: Float, r: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color, color.copy(alpha = 0f)),
            center = Offset(cx, cy), radius = r,
        ),
        radius = r, center = Offset(cx, cy),
    )
}

/** 软浪 path（§3.4 viewBox 100×44），横向平铺两幅、按 wave 偏移；整层 opacity .6×intensity。 */
private fun DrawScope.drawWave(w: Float, sx: Float, waterTop: Float, wave: Float, k: Float) {
    val unit = w               // 一个周期宽 = 屏宽
    val amp = 11f * sx         // 浪幅(viewBox 20±11)
    // 波峰最高 = baseY-amp = waterTop，浪只在水面线及其下方起伏，不越过那条横线。
    val baseY = waterTop + amp
    val offset = -wave * unit
    val fill = Color(0.871f, 0.667f, 0.549f, 0.6f) // rgba(222,170,140,.6)
    val path = Path()
    var x = offset - unit
    while (x < w + unit) {
        // 每个周期: 一个完整正弦样波(两峰两谷,近似 demo path)
        path.moveTo(x, baseY)
        val step = unit / 4f
        path.cubicTo(x + step * 0.4f, baseY - amp, x + step * 0.6f, baseY - amp, x + step, baseY)
        path.cubicTo(x + step * 1.4f, baseY + amp, x + step * 1.6f, baseY + amp, x + step * 2, baseY)
        path.cubicTo(x + step * 2.4f, baseY - amp, x + step * 2.6f, baseY - amp, x + step * 3, baseY)
        path.cubicTo(x + step * 3.4f, baseY + amp, x + step * 3.6f, baseY + amp, x + step * 4, baseY)
        path.lineTo(x + unit, size.height)
        path.lineTo(x, size.height)
        path.close()
        x += unit
    }
    drawPath(path, color = fill, alpha = 0.6f * k)
}

private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t,
)
