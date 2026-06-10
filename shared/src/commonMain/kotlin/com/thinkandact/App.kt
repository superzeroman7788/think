package com.thinkandact

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.thinkandact.data.session.SessionState
import com.thinkandact.ui.common.AppBackHandler
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
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
    data object Inbox : Screen()
}

@Composable
fun App() {
    ThinkAndActTheme {
        val sessionState = koinInject<SessionState>()
        // 登录门控（BUG-03）：响应式登录态——会话刷新失败时被置 false → 自动回登录页（不再静默匿名号）。
        val loggedIn by sessionState.loggedIn.collectAsState()
        var legalDoc by remember { mutableStateOf<LegalDoc?>(null) }

        if (!loggedIn) {
            // 协议页：系统返回键回登录页,不退 App。
            AppBackHandler(enabled = legalDoc != null) { legalDoc = null }
            legalDoc?.let { LegalScreen(doc = it, onBack = { legalDoc = null }) }
                ?: LoginScreen(onLoggedIn = {}, onOpenLegal = { legalDoc = it })
            return@ThinkAndActTheme
        }

        var currentScreen by remember { mutableStateOf<Screen>(Screen.Morning) }
        // BUG-01：系统返回键/右滑 → 逐屏返回,而不是退 App。Morning 时不拦(默认退出)。
        AppBackHandler(enabled = currentScreen != Screen.Morning) {
            currentScreen = when (currentScreen) {
                Screen.Review, Screen.FullPlan, Screen.Inbox -> Screen.Execution
                else -> Screen.Morning
            }
        }
        // BUG-05：回前台时若已跨午夜 → 回到 Morning(重算 today、拉新一天),避免还显示昨天的任务。
        var lastDate by remember { mutableStateOf(Clock.System.todayIn(TimeZone.currentSystemDefault())) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
            if (today != lastDate) { lastDate = today; currentScreen = Screen.Morning }
        }
        // 第四批 N-03:点 ★ 提醒通知 → MainActivity 置位 → 直达执行屏(消费后复位)。
        val openExecution by com.thinkandact.ui.NavSignals.openExecution.collectAsState()
        LaunchedEffect(openExecution) {
            if (openExecution) {
                currentScreen = Screen.Execution
                com.thinkandact.ui.NavSignals.openExecution.value = false
            }
        }
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
                    onOpenInbox = { currentScreen = Screen.Inbox },
                )
                Screen.Review -> ReviewScreen(onBack = { currentScreen = Screen.Execution })
                Screen.History -> HistoryScreen(onBack = { currentScreen = Screen.Morning })
                Screen.FullPlan -> FullPlanScreen(onBack = { currentScreen = Screen.Execution })
                Screen.Inbox -> com.thinkandact.ui.inbox.InboxScreen(onBack = { currentScreen = Screen.Execution })
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
