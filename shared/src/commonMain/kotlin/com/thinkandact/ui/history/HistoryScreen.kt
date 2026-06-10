package com.thinkandact.ui.history

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thinkandact.data.remote.TaskRowDto
import com.thinkandact.ui.theme.TnaColors
import com.thinkandact.ui.theme.TnaTypography
import org.koin.compose.viewmodel.koinViewModel
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// demo 色值（历史视图_过去7天_设计_v2）
private val Amber = Color(0xFFE29A52)
private val Terra = Color(0xFFB5654A)
private val Clay = Color(0xFF9E5238)
private val TodayTop = Color(0xFFC8743F)
private val CardInk = Color(0xFF5C4A3E)
private val PraiseBg = Color(0xFFFBEFE4)

@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    viewModel: HistoryViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.load() }

    Column(
        modifier = Modifier.fillMaxSize().background(TnaColors.Background)
            .statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(999.dp)).background(TnaColors.Surface).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Text("←", style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold)) }
            Text("  过去 7 天", style = TnaTypography.SectionTitle.copy(color = TnaColors.Ink))
        }

        when {
            state.isLoading -> {
                Spacer(Modifier.height(40.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = TnaColors.AccentDeep, strokeWidth = 2.dp)
                    Text("  在翻这几天…", style = TnaTypography.Body.copy(color = TnaColors.InkSoft))
                }
            }
            state.errorMessage != null -> {
                Spacer(Modifier.height(48.dp))
                Text(
                    state.errorMessage ?: "拉历史失败了。",
                    style = TnaTypography.AiVoice.copy(color = TnaColors.AccentDeep),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "再试一次",
                    modifier = Modifier.clickable { viewModel.load() },
                    style = TnaTypography.Body.copy(color = TnaColors.AccentDeep, fontWeight = FontWeight.SemiBold),
                )
            }
            !state.hasAnyData -> {
                Spacer(Modifier.height(48.dp))
                Text("才刚开始,过几天这里就有你的节奏了。", style = TnaTypography.AiVoice.copy(color = TnaColors.InkSoft))
            }
            else -> {
                // 累计句（只增不减、正向；无完成率、无连胜）。
                Spacer(Modifier.height(8.dp))
                Text(
                    "这 7 天,你做成了 ${state.totalDone} 件。看看自己的节奏就好。",
                    style = TnaTypography.AiVoice.copy(color = TnaColors.InkSoft),
                )

                Spacer(Modifier.height(16.dp))
                BarChart(state, onTap = viewModel::selectDay)

                Spacer(Modifier.height(18.dp))
                state.days.forEach { day ->
                    DayCard(day = day, expanded = day.date == state.selectedDate, onToggle = { viewModel.selectDay(day.date) })
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun BarChart(state: HistoryUiState, onTap: (String) -> Unit) {
    val maxOwed = maxOf(state.maxOwed, 1)
    Row(
        modifier = Modifier.fillMaxWidth().height(118.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        state.days.forEach { day ->
            val barH = 20f + day.owed.toFloat() / maxOwed * 74f
            val solidFrac = if (day.owed > 0) day.done.toFloat() / day.owed else 0f
            Column(
                modifier = Modifier.weight(1f).clickable { onTap(day.date) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 柱子：上淡斜纹(未做) + 下实色(做成)
                Column(
                    modifier = Modifier.fillMaxWidth(0.62f).height(barH.dp).clip(RoundedCornerShape(7.dp, 7.dp, 3.dp, 3.dp)),
                ) {
                    val ghostFrac = 1f - solidFrac
                    if (ghostFrac > 0f) {
                        StripedGhost(modifier = Modifier.fillMaxWidth().weight(ghostFrac.coerceAtLeast(0.001f)))
                    }
                    if (solidFrac > 0f) {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(solidFrac)
                                .background(Brush.verticalGradient(if (day.isToday) listOf(TodayTop, Clay) else listOf(Amber, Terra))),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    day.label.removePrefix("周").let { if (day.isToday) "今天" else day.label },
                    style = TnaTypography.Mono.copy(color = if (day.isToday) Clay else TnaColors.Muted, fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal),
                )
                Text(
                    "${day.done}/${day.owed}",
                    style = TnaTypography.Mono.copy(color = if (day.isToday) Clay else Terra, fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

/** 淡暖斜纹（未做部分）。 */
@Composable
private fun StripedGhost(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.background(Color.Transparent)) {
        val stripe = Terra.copy(alpha = 0.14f)
        val gap = 6.dp.toPx()
        val w = size.width; val h = size.height
        var x = -h
        while (x < w) {
            drawLine(stripe, Offset(x, h), Offset(x + h, 0f), strokeWidth = 3.dp.toPx())
            x += gap
        }
    }
}

@Composable
private fun DayCard(day: HistoryDay, expanded: Boolean, onToggle: () -> Unit) {
    val committed = day.tasks.filter { it.status != "suggested" && it.status != "dropped" }
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(TnaColors.Surface)
            .border(1.dp, if (expanded) Color(0xFFE3C3AC) else TnaColors.Line, RoundedCornerShape(16.dp))
            .clickable(onClick = onToggle),
    ) {
        // 折叠头：日期 + 一句暖总结 + 做成/共
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.width(64.dp)) {
                Text(if (day.isToday) "今天" else day.label, style = TnaTypography.Body.copy(color = if (day.isToday) Clay else TnaColors.Ink, fontWeight = FontWeight.SemiBold))
                Text(day.shortDate, style = TnaTypography.Mono.copy(color = TnaColors.Muted))
            }
            Text(oneLine(day), modifier = Modifier.weight(1f).padding(horizontal = 8.dp), style = TnaTypography.AiVoice.copy(color = CardInk))
            Box(modifier = Modifier.background(Color(0xFFF3E4D8), RoundedCornerShape(999.dp)).padding(horizontal = 11.dp, vertical = 6.dp)) {
                Text("${day.done}/${day.owed}", style = TnaTypography.Mono.copy(color = Clay, fontWeight = FontWeight.SemiBold))
            }
        }
        // 展开体：明细 + 提醒(夸)
        if (expanded) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp).padding(bottom = 15.dp)) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFF4EAE0)))
                if (committed.isEmpty() && day.tasks.isEmpty()) {
                    Text("这天没有安排。", modifier = Modifier.padding(top = 10.dp), style = TnaTypography.BodySoft)
                } else {
                    day.tasks.filter { it.status != "suggested" }.forEach { t -> TaskDetailRow(t) }
                }
                day.praise?.let { p ->
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).background(PraiseBg).padding(horizontal = 13.dp, vertical = 11.dp)) {
                        Text(p, style = TnaTypography.AiVoice.copy(color = CardInk))
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskDetailRow(t: TaskRowDto) {
    val (label, bg, fg) = when (t.status) {
        "done" -> Triple("完成", Color(0xFFF3E4D8), Clay)
        "skipped" -> Triple("跳过", Color(0xFFEFEAE5), TnaColors.Muted) // 中性灰,非红
        else -> Triple("未做", Color.White, TnaColors.Muted)
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t.plannedStart.hhmm(), modifier = Modifier.width(46.dp), style = TnaTypography.Mono.copy(color = TnaColors.Muted))
        Text(t.title, modifier = Modifier.weight(1f), style = TnaTypography.Body.copy(color = if (t.status == "skipped") TnaColors.Muted else TnaColors.Ink))
        Box(modifier = Modifier.background(bg, RoundedCornerShape(999.dp)).then(if (t.status == "planned") Modifier.border(1.dp, Color(0xFFE7DACE), RoundedCornerShape(999.dp)) else Modifier).padding(horizontal = 10.dp, vertical = 3.dp)) {
            Text(label, style = TnaTypography.Mono.copy(color = fg))
        }
    }
}

/** 折叠卡片暖总结（FE 本地；有 done 点名,否则中性、不标差）。 */
private fun oneLine(day: HistoryDay): String {
    val doneTitles = day.tasks.filter { it.status == "done" }.map { it.title }
    return when {
        doneTitles.isNotEmpty() -> {
            val head = doneTitles.take(2).joinToString("、")
            if (doneTitles.size > 2) "$head 等都顺了" else "$head 做成了"
        }
        day.owed > 0 -> "这天缓一缓"
        else -> "歇了一天"
    }
}

private fun String?.hhmm(): String {
    if (this.isNullOrBlank()) return "--:--"
    val i = runCatching { Instant.parse(this) }.getOrNull() ?: return "--:--"
    val lt = i.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${lt.hour.toString().padStart(2, '0')}:${lt.minute.toString().padStart(2, '0')}"
}
