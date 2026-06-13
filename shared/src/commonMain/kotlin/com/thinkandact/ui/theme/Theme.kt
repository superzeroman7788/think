package com.thinkandact.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier

@Composable
fun ThinkAndActTheme(content: @Composable () -> Unit) {
    val fonts = rememberTnaFonts()
    CompositionLocalProvider(LocalTnaFonts provides fonts) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(TnaColors.Background)
        ) {
            content()
        }
    }
}
