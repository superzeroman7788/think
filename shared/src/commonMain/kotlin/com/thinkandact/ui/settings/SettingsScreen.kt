package com.thinkandact.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.unit.dp
import com.thinkandact.calendar.rememberCalendarPermission
import com.thinkandact.ui.theme.TnaColors
import com.thinkandact.ui.theme.TnaShapes
import com.thinkandact.ui.theme.TnaTypography
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = org.koin.compose.viewmodel.koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val calPerm = rememberCalendarPermission()
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().background(TnaColors.Background).statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).background(TnaColors.Surface, RoundedCornerShape(999.dp)).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Text("←", style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold))
            }
            Text("设置", modifier = Modifier.padding(start = 12.dp), style = TnaTypography.Display.copy(fontWeight = FontWeight.Bold))
        }
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth().background(TnaColors.Surface, TnaShapes.Card).border(1.dp, TnaColors.Line, TnaShapes.Card).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("★ 任务同步到系统日历", style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(4.dp))
                Text("让重要提醒在系统日程里也能看到,App 被清后台也不漏。只同步标★的任务。", style = TnaTypography.Mono.copy(color = TnaColors.InkSoft))
            }
            Switch(
                checked = state.calendarSyncEnabled,
                onCheckedChange = { want ->
                    if (want) {
                        scope.launch { if (calPerm.request()) viewModel.enableCalendarSync() else viewModel.onPermissionDenied() }
                    } else viewModel.disableCalendarSync()
                },
                colors = SwitchDefaults.colors(checkedTrackColor = TnaColors.Accent, checkedThumbColor = Color.White),
            )
        }

        state.message?.let {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().background(TnaColors.AccentSoft.copy(alpha = 0.5f), TnaShapes.Input).padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(it, modifier = Modifier.weight(1f), style = TnaTypography.AiVoice.copy(color = TnaColors.AccentDeep))
                Text("知道了", modifier = Modifier.padding(start = 8.dp).clickable(onClick = viewModel::dismissMessage), style = TnaTypography.Body.copy(color = TnaColors.AccentDeep, fontWeight = FontWeight.SemiBold))
            }
        }
    }
}
