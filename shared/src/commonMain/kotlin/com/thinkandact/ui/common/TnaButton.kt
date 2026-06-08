package com.thinkandact.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thinkandact.ui.theme.TnaColors
import com.thinkandact.ui.theme.TnaShapes
import com.thinkandact.ui.theme.TnaTypography

enum class TnaButtonStyle { Primary, Secondary, Ink }

@Composable
fun TnaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TnaButtonStyle = TnaButtonStyle.Primary,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    data class Colors(val bg: Color, val content: Color, val border: Color)
    val colors = when (style) {
        TnaButtonStyle.Primary -> Colors(TnaColors.AccentSoft, TnaColors.AccentDeep, TnaColors.AccentSoft)
        TnaButtonStyle.Secondary -> Colors(TnaColors.Surface, TnaColors.InkSoft, TnaColors.Line)
        TnaButtonStyle.Ink -> Colors(TnaColors.AccentSoft, TnaColors.AccentDeep, TnaColors.AccentSoft)
    }

    Row(
        modifier = modifier
            .scale(if (pressed) 0.97f else 1f)
            .defaultMinSize(minHeight = 48.dp)
            .background(
                color = if (enabled) colors.bg else colors.bg.copy(alpha = 0.45f),
                shape = TnaShapes.Button
            )
            .border(
                border = BorderStroke(1.dp, if (enabled) colors.border else colors.border.copy(alpha = 0.45f)),
                shape = TnaShapes.Button
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 18.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading?.invoke()
        Text(
            text = text,
            style = TnaTypography.Body.copy(
                color = if (enabled) colors.content else colors.content.copy(alpha = 0.5f),
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}
