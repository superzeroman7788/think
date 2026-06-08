package com.thinkandact

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thinkandact.data.AuthRepository
import com.thinkandact.ui.execution.ExecutionScreen
import com.thinkandact.ui.fullplan.FullPlanScreen
import com.thinkandact.ui.history.HistoryScreen
import com.thinkandact.ui.login.LegalDoc
import com.thinkandact.ui.login.LegalScreen
import com.thinkandact.ui.login.LoginScreen
import com.thinkandact.ui.morning.MorningScreen
import com.thinkandact.ui.reminders.RemindersViewModel
import com.thinkandact.ui.reminders.TaskReminderBanner
import com.thinkandact.ui.review.ReviewScreen
import com.thinkandact.ui.routine.RoutineScreen
import com.thinkandact.ui.theme.ThinkAndActTheme
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

sealed class Screen {
    data object Morning : Screen()
    data object Routines : Screen()
    data object Execution : Screen()
    data object Review : Screen()
    data object History : Screen()
    data object FullPlan : Screen()
}

@Composable
fun App() {
    ThinkAndActTheme {
        val auth = koinInject<AuthRepository>()
        // 登录门控:有会话 → 进首屏;无 → 登录页(本版 mock 手机号登录)。
        var loggedIn by remember { mutableStateOf(auth.isLoggedIn()) }
        var legalDoc by remember { mutableStateOf<LegalDoc?>(null) }

        if (!loggedIn) {
            legalDoc?.let { LegalScreen(doc = it, onBack = { legalDoc = null }) }
                ?: LoginScreen(onLoggedIn = { loggedIn = true }, onOpenLegal = { legalDoc = it })
            return@ThinkAndActTheme
        }

        var currentScreen by remember { mutableStateOf<Screen>(Screen.Morning) }
        val reminders: RemindersViewModel = koinViewModel()
        val activeReminder by reminders.active.collectAsState()

        Box(modifier = Modifier.fillMaxSize()) {
            when (currentScreen) {
                Screen.Morning -> MorningScreen(
                    onOpenRoutines = { currentScreen = Screen.Routines },
                    onOpenExecution = { currentScreen = Screen.Execution },
                    onOpenHistory = { currentScreen = Screen.History },
                )
                Screen.Routines -> RoutineScreen(onBack = { currentScreen = Screen.Morning })
                Screen.Execution -> ExecutionScreen(
                    onBack = { currentScreen = Screen.Morning },
                    onOpenReview = { currentScreen = Screen.Review },
                    onOpenFullPlan = { currentScreen = Screen.FullPlan },
                )
                Screen.Review -> ReviewScreen(onBack = { currentScreen = Screen.Execution })
                Screen.History -> HistoryScreen(onBack = { currentScreen = Screen.Morning })
                Screen.FullPlan -> FullPlanScreen(onBack = { currentScreen = Screen.Execution })
            }

            // 块三：普通任务到点的 App 内横幅,浮在当前屏顶部。
            activeReminder?.let { task ->
                TaskReminderBanner(
                    task = task,
                    onGo = { currentScreen = Screen.Execution; reminders.dismiss() },
                    onDismiss = reminders::dismiss,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }
        }
    }
}
