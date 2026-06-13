package com.thinkandact.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.thinkandact.app.MainActivity
import com.thinkandact.core.config.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

private val Cream = Color(0xFFFBF5F0)
private val Terra = Color(0xFFB5654A)
private val Ink = Color(0xFF2E2924)
private val Muted = Color(0xFFA89C8E)

data class InboxSnapshot(val dueCount: Int, val topTitle: String?, val topDue: String?, val totalPending: Int)

/** 随手记桌面小组件：收件箱概览（到期数 + 最该看的一条），点击进收件箱。 */
class InboxWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = runCatching { fetchSnapshot(context) }.getOrNull()
        provideContent { WidgetContent(snap) }
    }

    @Composable
    private fun WidgetContent(snap: InboxSnapshot?) {
        val context = LocalContext.current
        val openInbox = Intent(context, MainActivity::class.java).setAction("com.thinkandact.action.INBOX")
        Column(
            modifier = GlanceModifier.fillMaxSize().background(ColorProvider(Cream))
                .cornerRadius(18.dp).padding(14.dp)
                .clickable(actionStartActivity(openInbox)),
        ) {
            Row {
                Text("收件箱", style = TextStyle(color = ColorProvider(Terra), fontSize = 13.sp, fontWeight = FontWeight.Bold))
                if (snap != null && snap.dueCount > 0) {
                    Spacer(GlanceModifier.width(6.dp))
                    Text("${snap.dueCount} 件到日子了", style = TextStyle(color = ColorProvider(Terra), fontSize = 11.sp))
                }
            }
            Spacer(GlanceModifier.height(8.dp))
            when {
                snap == null -> Text("点开看看", style = TextStyle(color = ColorProvider(Muted), fontSize = 12.sp))
                snap.totalPending == 0 -> Text("没什么挂着的事", style = TextStyle(color = ColorProvider(Muted), fontSize = 13.sp))
                else -> {
                    Text(snap.topTitle ?: "", style = TextStyle(color = ColorProvider(Ink), fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                    snap.topDue?.let {
                        Spacer(GlanceModifier.height(2.dp))
                        Text(it, style = TextStyle(color = ColorProvider(Muted), fontSize = 11.sp))
                    }
                    if (snap.totalPending > 1) {
                        Spacer(GlanceModifier.height(6.dp))
                        Text("还有 ${snap.totalPending - 1} 条在收件箱", style = TextStyle(color = ColorProvider(Muted), fontSize = 11.sp))
                    }
                }
            }
        }
    }

    private suspend fun fetchSnapshot(context: Context): InboxSnapshot = withContext(Dispatchers.IO) {
        val token = context.getSharedPreferences("think_and_act_session", Context.MODE_PRIVATE)
            .getString("access_token", null) ?: return@withContext InboxSnapshot(0, null, null, 0)
        val conn = (URL("${SupabaseConfig.URL}/functions/v1/inbox-list").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("apikey", SupabaseConfig.CLIENT_KEY)
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 8000; readTimeout = 8000
        }
        try {
            if (conn.responseCode !in 200..299) return@withContext InboxSnapshot(0, null, null, 0)
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val items = JSONObject(body).optJSONArray("items") ?: return@withContext InboxSnapshot(0, null, null, 0)
            val today = LocalDate.now()
            var due = 0
            var totalPending = 0
            var topTitle: String? = null
            var topDue: String? = null
            var topDate: LocalDate? = null
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                if (o.optString("status") != "pending") continue
                totalPending++
                val dueStr = o.optString("due_date", "").takeIf { it.isNotBlank() && it != "null" }
                val d = dueStr?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                if (d != null && !d.isAfter(today)) due++
                val better = when {
                    topTitle == null -> true
                    topDate == null && d != null -> true
                    d != null && topDate != null && d.isBefore(topDate) -> true
                    else -> false
                }
                if (better) {
                    topTitle = o.optString("text")
                    topDate = d
                    topDue = dueChip(d, o.optString("due_part", ""))
                }
            }
            InboxSnapshot(due, topTitle, topDue, totalPending)
        } finally {
            conn.disconnect()
        }
    }

    private fun dueChip(d: LocalDate?, part: String): String {
        if (d == null) return "没定日子"
        val today = LocalDate.now()
        val day = when (d) {
            today -> "今天"
            today.plusDays(1) -> "明天"
            else -> "${d.monthValue}/${d.dayOfMonth}"
        }
        val p = when (part) { "morning" -> " 上午"; "afternoon" -> " 下午"; "evening" -> " 晚上"; else -> "" }
        return day + p
    }
}

class InboxWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = InboxWidget()
}
