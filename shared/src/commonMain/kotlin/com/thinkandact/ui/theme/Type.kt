package com.thinkandact.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

data class TnaFontFamilies(
    val display: FontFamily = FontFamily.Default,
    val mono: FontFamily = FontFamily.Default
)

val LocalTnaFonts = compositionLocalOf { TnaFontFamilies() }

object TnaTypography {
    val Wordmark: TextStyle
        @Composable get() = TextStyle(
            fontFamily = LocalTnaFonts.current.display,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            letterSpacing = 0.sp,
            color = TnaColors.Ink
        )

    val Display: TextStyle
        @Composable get() = TextStyle(
            fontFamily = LocalTnaFonts.current.display,
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
            lineHeight = 32.sp,
            letterSpacing = 0.sp,
            color = TnaColors.Ink
        )

    val SectionTitle: TextStyle
        @Composable get() = TextStyle(
            fontFamily = LocalTnaFonts.current.mono,
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            letterSpacing = 1.6.sp,
            color = TnaColors.Muted
        )

    val Body = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        color = TnaColors.Ink
    )

    val BodySoft = Body.copy(color = TnaColors.InkSoft)

    val AiVoice = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        color = TnaColors.AiVoice
    )

    val Mono: TextStyle
        @Composable get() = TextStyle(
            fontFamily = LocalTnaFonts.current.mono,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            letterSpacing = 0.sp,
            color = TnaColors.AccentDeep
        )
}

@Composable
expect fun rememberTnaFonts(): TnaFontFamilies
