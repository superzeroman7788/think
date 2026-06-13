package com.thinkandact.ui.reminders

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thinkandact.data.remote.TaskRowDto
import com.thinkandact.ui.theme.TnaColors
import com.thinkandact.ui.theme.TnaShapes
import com.thinkandact.ui.theme.TnaTypography
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** 普通任务到点的 App 内横幅（前台）。点「去看看」进执行屏,「知道了」关掉。 */
@Composable
fun TaskReminderBanner(
    task: TaskRowDto,
    onGo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .statusBarsPadding()
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .background(TnaColors.AccentDeep, TnaShapes.Card)
            .border(1.dp, TnaColors.Accent, TnaShapes.Card)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "该做这件了 · ${task.plannedStart.hhmm()}",
                style = TnaTypography.Mono.copy(color = TnaColors.Surface.copy(alpha = 0.85f)),
            )
            Text(
                text = task.title,
                modifier = Modifier.padding(top = 2.dp),
                style = TnaTypography.Body.copy(color = TnaColors.Surface, fontWeight = FontWeight.Bold),
            )
        }
        Text(
            text = "去看看",
            modifier = Modifier
                .padding(start = 10.dp)
                .background(TnaColors.Surface, TnaShapes.Selection)
                .clickable(onClick = onGo)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            style = TnaTypography.Body.copy(color = TnaColors.AccentDeep, fontWeight = FontWeight.SemiBold),
        )
        Text(
            text = "知道了",
            modifier = Modifier.padding(start = 10.dp).clickable(onClick = onDismiss).padding(vertical = 6.dp),
            style = TnaTypography.Body.copy(color = TnaColors.Surface.copy(alpha = 0.9f)),
        )
    }
}

private fun String?.hhmm(): String {
    if (this.isNullOrBlank()) return ""
    val raw = trim()
    if (raw.matches(Regex("^\\d{2}:\\d{2}(:\\d{2})?$"))) return raw.take(5)
    return runCatching {
        val dt = Instant.parse(raw).toLocalDateTime(TimeZone.currentSystemDefault())
        dt.hour.toString().padStart(2, '0') + ":" + dt.minute.toString().padStart(2, '0')
    }.getOrElse { "" }
}
