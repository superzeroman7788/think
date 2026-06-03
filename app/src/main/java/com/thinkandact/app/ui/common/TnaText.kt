package com.thinkandact.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import com.thinkandact.app.ui.theme.TnaTypography

@Composable
fun BrandWordmark(modifier: Modifier = Modifier) {
    Text(
        text = "think & act",
        modifier = modifier,
        style = TnaTypography.Wordmark
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = TnaTypography.SectionTitle
    )
}
