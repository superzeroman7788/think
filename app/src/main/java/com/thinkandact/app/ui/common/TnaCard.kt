package com.thinkandact.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.thinkandact.app.ui.theme.TnaColors
import com.thinkandact.app.ui.theme.TnaShapes

@Composable
fun TnaCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .shadow(
                elevation = 7.dp,
                shape = TnaShapes.Card,
                ambientColor = TnaColors.WarmShadow,
                spotColor = TnaColors.WarmShadow
            )
            .background(TnaColors.Surface, TnaShapes.Card)
            .border(1.dp, TnaColors.Line, TnaShapes.Card)
            .padding(14.dp),
        content = content
    )
}
