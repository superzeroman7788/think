package com.thinkandact.app.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.thinkandact.app.R

object TnaFonts {
    val Display = FontFamily(Font(R.font.bricolage_grotesque))
    val Body = FontFamily.Default
    val Mono = FontFamily(Font(R.font.jetbrains_mono))
}

object TnaTypography {
    val Wordmark = TextStyle(
        fontFamily = TnaFonts.Display,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        letterSpacing = 0.sp,
        color = TnaColors.Ink
    )

    val Display = TextStyle(
        fontFamily = TnaFonts.Display,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
        color = TnaColors.Ink
    )

    val SectionTitle = TextStyle(
        fontFamily = TnaFonts.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 1.6.sp,
        color = TnaColors.Muted
    )

    val Body = TextStyle(
        fontFamily = TnaFonts.Body,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        color = TnaColors.Ink
    )

    val BodySoft = Body.copy(color = TnaColors.InkSoft)

    val AiVoice = TextStyle(
        fontFamily = TnaFonts.Body,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        color = TnaColors.AiVoice
    )

    val Mono = TextStyle(
        fontFamily = TnaFonts.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.sp,
        color = TnaColors.AccentDeep
    )
}
