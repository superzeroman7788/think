package com.thinkandact.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.thinkandact.App
import com.thinkandact.reminders.ReminderReceiver
import com.thinkandact.ui.NavSignals

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        consumeNavExtras(intent)
        setContent {
            App()
        }
    }

    // N-03:点 ★ 提醒通知再次拉起已存在的实例(singleTask)→ 这里收 extra。
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeNavExtras(intent)
    }

    private fun consumeNavExtras(intent: Intent?) {
        if (intent?.getBooleanExtra(ReminderReceiver.EXTRA_OPEN_EXECUTION, false) == true) {
            NavSignals.openExecution.value = true
            intent.removeExtra(ReminderReceiver.EXTRA_OPEN_EXECUTION)
        }
        // 随手记快捷方式 / widget(action 路由)。
        when (intent?.action) {
            "com.thinkandact.action.CAPTURE" -> NavSignals.openCapture.value = true
            "com.thinkandact.action.INBOX" -> NavSignals.openInbox.value = true
        }
    }
}
