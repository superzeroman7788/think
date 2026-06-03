package com.thinkandact.app.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thinkandact.app.ui.common.BrandWordmark
import com.thinkandact.app.ui.common.SectionLabel
import com.thinkandact.app.ui.theme.TnaColors
import com.thinkandact.app.ui.theme.TnaShapes
import com.thinkandact.app.ui.theme.TnaTypography

@Composable
fun ComponentGalleryScreen() {
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
            BrandWordmark()
            Text(
                text = "morning.",
                modifier = Modifier.padding(top = 14.dp),
                style = TnaTypography.Display
            )
            SectionLabel(
                text = "today",
                modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
            )

            MorningTaskCard(
                title = "写产品文档第一稿",
                time = "09:00",
                note = "早上脑子清楚,先啃最重的那块。",
                important = true
            )
            MorningTaskCard(
                title = "歇 10 分钟",
                time = "10:30",
                note = "起来走走,别一直坐着。",
                suggested = true
            )
            MorningTaskCard(
                title = "回几封邮件",
                time = "14:00",
                note = "下午精力下来了,集中处理。"
            )
            MorningTaskCard(
                title = "产品评审会",
                time = "15:00",
                important = true
            )
            MorningTaskCard(
                title = "夜跑",
                time = "19:00",
                note = "把脑子里的东西甩一甩。"
            )

            SectionLabel(
                text = "ai voice",
                modifier = Modifier.padding(top = 9.dp, bottom = 7.dp)
            )
            AiVoicePanel(
                text = "上午整段文档你专心干,中间歇 10 分钟别死磕。下午会议前记得给电脑充好电,跑步前多喝点水。"
            )
            Spacer(modifier = Modifier.height(18.dp))
        }

        MorningFooter()
    }
}

@Composable
private fun MorningTaskCard(
    title: String,
    time: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    important: Boolean = false,
    suggested: Boolean = false
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
            if (important) {
                Text(
                    text = "★",
                    modifier = Modifier.padding(end = 7.dp),
                    style = TnaTypography.Body.copy(
                        color = TnaColors.Accent,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = TnaTypography.Body.copy(
                    color = if (suggested) TnaColors.InkSoft else TnaColors.Ink,
                    fontWeight = if (suggested) FontWeight.Medium else FontWeight.Bold,
                    fontSize = TnaTypography.Body.fontSize
                )
            )
            Text(
                text = time,
                modifier = Modifier
                    .background(
                        color = if (suggested) Color.Transparent else TnaColors.AccentSoft,
                        shape = RoundedCornerShape(7.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                style = TnaTypography.Mono.copy(
                    color = if (suggested) TnaColors.InkSoft else TnaColors.Accent
                )
            )
        }

        if (note != null) {
            Text(
                text = note,
                modifier = Modifier.padding(top = 5.dp),
                style = TnaTypography.Body.copy(color = TnaColors.InkSoft)
            )
        }
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
private fun MorningFooter() {
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 8.dp,
                    shape = TnaShapes.Button,
                    ambientColor = TnaColors.Accent.copy(alpha = 0.36f),
                    spotColor = TnaColors.Accent.copy(alpha = 0.36f)
                )
                .background(TnaColors.Accent, TnaShapes.Button)
                .padding(vertical = 15.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "looks good",
                style = TnaTypography.Body.copy(
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = TnaTypography.Body.fontSize
                )
            )
        }
    }
}
