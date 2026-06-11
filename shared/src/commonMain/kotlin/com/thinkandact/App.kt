package com.thinkandact

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.thinkandact.ui.theme.TnaColors
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

        // 冷启动落页 + 返回栈:今天已确认过计划 → 直达「今天」(它即首页,无 ← 退回 morning);
        // 没有 → 早上规划页。返回键/← 走真实返回栈:栈底(首页)按返回 → 退出 App,而不是跳 morning。
        val planRepository = koinInject<com.thinkandact.data.PlanRepository>()
        val backStack = remember { mutableStateListOf<Screen>() }
        LaunchedEffect(Unit) {
            if (backStack.isEmpty()) {
                val hasPlan = runCatching { planRepository.hasTodayPlan() }.getOrDefault(false)
                if (backStack.isEmpty()) backStack.add(if (hasPlan) Screen.Execution else Screen.Morning)
            }
        }
        val screen = backStack.lastOrNull()
        if (screen == null) {
            Box(modifier = Modifier.fillMaxSize().background(TnaColors.Background))
            return@ThinkAndActTheme
        }
        fun navTo(s: Screen) { if (backStack.lastOrNull() != s) backStack.add(s) }
        fun goBack() { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }

        // BUG-01：系统返回键 → 弹返回栈;栈底(首页)不拦 → 默认退出 App。
        AppBackHandler(enabled = backStack.size > 1) { goBack() }

        // BUG-05：回前台跨午夜 → 重置到 Morning(新一天重新规划),清空旧栈。
        var lastDate by remember { mutableStateOf(Clock.System.todayIn(TimeZone.currentSystemDefault())) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
            if (today != lastDate) { lastDate = today; backStack.clear(); backStack.add(Screen.Morning) }
        }
        // 第四批 N-03:点 ★ 提醒通知 → 直达执行屏(消费后复位)。
        val openExecution by com.thinkandact.ui.NavSignals.openExecution.collectAsState()
        LaunchedEffect(openExecution) {
            if (openExecution) {
                navTo(Screen.Execution)
                com.thinkandact.ui.NavSignals.openExecution.value = false
            }
        }
        val reminders: RemindersViewModel = koinViewModel()
        val activeReminder by reminders.active.collectAsState()

        Box(modifier = Modifier.fillMaxSize()) {
            when (screen) {
                Screen.Morning -> MorningScreen(
                    onOpenRoutines = { navTo(Screen.Routines) },
                    onOpenExecution = { navTo(Screen.Execution) },
                    onOpenHistory = { navTo(Screen.History) },
                    // 确认计划后:重置返回栈为 [今天],morning 不再可回(调整在今天/完整计划页做)。
                    onPlanConfirmed = { backStack.clear(); backStack.add(Screen.Execution) },
                )
                Screen.Routines -> RoutineScreen(onBack = { goBack() })
                Screen.Execution -> ExecutionScreen(
                    onBack = { goBack() },
                    showBack = backStack.size > 1, // 冷启动落「今天」=栈底 → 不显示 ←
                    onOpenReview = { navTo(Screen.Review) },
                    onOpenFullPlan = { navTo(Screen.FullPlan) },
                    onOpenInbox = { navTo(Screen.Inbox) },
                )
                Screen.Review -> ReviewScreen(onBack = { goBack() })
                Screen.History -> HistoryScreen(onBack = { goBack() })
                Screen.FullPlan -> FullPlanScreen(onBack = { goBack() })
                Screen.Inbox -> com.thinkandact.ui.inbox.InboxScreen(onBack = { goBack() })
            }

            // 块三：普通任务到点的 App 内横幅,浮在当前屏顶部。
            activeReminder?.let { task ->
                TaskReminderBanner(
                    task = task,
                    onGo = { navTo(Screen.Execution); reminders.dismiss() },
                    onDismiss = reminders::dismiss,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }
        }
    }
}
