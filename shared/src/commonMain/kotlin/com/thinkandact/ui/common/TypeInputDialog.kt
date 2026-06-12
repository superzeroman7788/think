package com.thinkandact.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.thinkandact.ui.theme.TnaColors
import com.thinkandact.ui.theme.TnaShapes
import com.thinkandact.ui.theme.TnaTypography

/**
 * F7-03 通用「轻点打字」弹窗：每条语音条轻点都弹它,打字后走和语音同一根管子。
 * 长按 = 说话(VoiceMicButton 自己处理),轻点 = 这个对话框。
 */
@Composable
fun TypeInputDialog(
    title: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    submitLabel: String = "发送",
) {
    var text by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .background(TnaColors.Surface, TnaShapes.Card)
                .border(1.dp, TnaColors.Line, TnaShapes.Card)
                .padding(18.dp),
        ) {
            Text(title, style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold))
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                    .background(TnaColors.LineSoft, TnaShapes.Input)
                    .border(1.dp, TnaColors.Line, TnaShapes.Input)
                    .padding(horizontal = 12.dp, vertical = 11.dp),
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TnaTypography.Body.copy(color = TnaColors.Ink),
                    cursorBrush = SolidColor(TnaColors.Accent),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    decorationBox = { inner ->
                        if (text.isEmpty()) Text(placeholder, style = TnaTypography.Body.copy(color = TnaColors.MutedSoft))
                        inner()
                    },
                )
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TnaButton("取消", onClick = onDismiss, style = TnaButtonStyle.Secondary, modifier = Modifier.weight(1f))
                TnaButton(submitLabel, onClick = { if (text.isNotBlank()) onSubmit(text.trim()) }, style = TnaButtonStyle.Primary, modifier = Modifier.weight(1f))
            }
        }
    }
}
