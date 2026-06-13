package com.thinkandact.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thinkandact.data.remote.DeferredItemDto
import com.thinkandact.ui.inbox.deferredConfirmMessage
import com.thinkandact.ui.theme.TnaColors
import com.thinkandact.ui.theme.TnaShapes
import com.thinkandact.ui.theme.TnaTypography

/** 非今天 defer → 收件箱:确认区每条一行轻提示 chip。 */
@Composable
fun DeferredInboxChips(deferred: List<DeferredItemDto>, modifier: Modifier = Modifier) {
    if (deferred.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        deferred.forEach { item ->
            Text(
                text = deferredConfirmMessage(item.title, item.dueDate, item.duePart),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(TnaColors.AccentSoft.copy(alpha = 0.45f), TnaShapes.Input)
                    .border(1.dp, TnaColors.Line, TnaShapes.Input)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                style = TnaTypography.AiVoice.copy(color = TnaColors.AccentDeep),
            )
        }
    }
}
