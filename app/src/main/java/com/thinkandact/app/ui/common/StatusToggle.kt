package com.thinkandact.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thinkandact.app.ui.theme.TnaColors
import com.thinkandact.app.ui.theme.TnaShapes
import com.thinkandact.app.ui.theme.TnaTypography

enum class ReviewStatus(val label: String) {
    Planned("planned"),
    Done("done"),
    Skipped("skipped")
}

@Composable
fun StatusPill(
    status: ReviewStatus,
    modifier: Modifier = Modifier
) {
    val background = when (status) {
        ReviewStatus.Planned -> TnaColors.Surface
        ReviewStatus.Done -> TnaColors.AccentSoft
        ReviewStatus.Skipped -> TnaColors.AccentSoft
    }
    val content = when (status) {
        ReviewStatus.Planned -> TnaColors.Muted
        ReviewStatus.Done -> TnaColors.AccentDeep
        ReviewStatus.Skipped -> TnaColors.InkSoft
    }
    val border = when (status) {
        ReviewStatus.Planned -> TnaColors.Line
        ReviewStatus.Done -> TnaColors.AccentSoft
        ReviewStatus.Skipped -> TnaColors.Line
    }

    Row(
        modifier = modifier
            .background(background, TnaShapes.Selection)
            .border(BorderStroke(1.dp, border), TnaShapes.Selection)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = status.label,
            style = TnaTypography.Mono.copy(
                color = content,
                fontWeight = FontWeight.Medium
            )
        )
    }
}
