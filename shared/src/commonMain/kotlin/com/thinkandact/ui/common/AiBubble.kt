package com.thinkandact.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thinkandact.ui.theme.TnaColors
import com.thinkandact.ui.theme.TnaShapes
import com.thinkandact.ui.theme.TnaTypography

@Composable
fun AiBubble(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(TnaColors.AccentSoft, TnaShapes.Input)
            .border(1.dp, TnaColors.LineSoft, TnaShapes.Input)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(text = text, style = TnaTypography.AiVoice)
    }
}
