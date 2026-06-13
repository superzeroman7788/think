package com.thinkandact.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import com.thinkandact.shared.generated.resources.Res
import com.thinkandact.shared.generated.resources.bricolage_grotesque
import com.thinkandact.shared.generated.resources.jetbrains_mono
import org.jetbrains.compose.resources.Font

@Composable
actual fun rememberTnaFonts() = TnaFontFamilies(
    display = FontFamily(Font(Res.font.bricolage_grotesque)),
    mono = FontFamily(Font(Res.font.jetbrains_mono))
)
