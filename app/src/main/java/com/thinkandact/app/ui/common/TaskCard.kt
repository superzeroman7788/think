package com.thinkandact.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thinkandact.app.ui.theme.TnaColors
import com.thinkandact.app.ui.theme.TnaShapes
import com.thinkandact.app.ui.theme.TnaTypography

@Composable
fun TaskCard(
    title: String,
    time: String,
    note: String,
    important: Boolean,
    modifier: Modifier = Modifier
) {
    TnaCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (important) {
                        Text(
                        text = "★",
                        style = TnaTypography.Body.copy(
                            color = TnaColors.Accent,
                            fontWeight = FontWeight.Medium
                            )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = title,
                        style = TnaTypography.Body.copy(fontWeight = FontWeight.Medium)
                    )
                }
                Text(
                    text = note,
                    modifier = Modifier.padding(top = 6.dp),
                    style = TnaTypography.AiVoice
                )
            }
            Text(
                text = time,
                modifier = Modifier
                    .background(TnaColors.AccentSoft, TnaShapes.Selection)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                style = TnaTypography.Mono
            )
        }
    }
}
