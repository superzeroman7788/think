package com.thinkandact.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thinkandact.ui.theme.TnaColors
import com.thinkandact.ui.theme.TnaTypography

/** 协议占位文档类型。 */
enum class LegalDoc(val title: String) { TERMS("用户协议"), PRIVACY("隐私政策") }

/** 协议占位页（发朋友版:占位文案,链接可打开、不死链;上架前换正式文本）。 */
@Composable
fun LegalScreen(doc: LegalDoc, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(TnaColors.Background)
            .statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(999.dp)).background(TnaColors.Surface).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Text("←", style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold)) }
            Text("  《${doc.title}》", style = TnaTypography.SectionTitle.copy(color = TnaColors.Ink))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "（占位版 · 发朋友内测用）",
            style = TnaTypography.Mono.copy(color = TnaColors.Muted),
        )
        Spacer(Modifier.height(12.dp))
        Text(placeholderBody(doc), style = TnaTypography.AiVoice.copy(color = TnaColors.Ink))
    }
}

private fun placeholderBody(doc: LegalDoc): String = when (doc) {
    LegalDoc.TERMS ->
        "这是 Think & Act 的《用户协议》占位文本。\n\n" +
            "本应用目前处于朋友内测阶段,功能与数据仍在调整。你使用本应用即表示理解这是测试版本。\n\n" +
            "我们会善待你的数据,只用于帮你规划与复盘今天,不做与此无关的用途。\n\n" +
            "正式版本上架前,这里会替换为正式的用户协议条款。"
    LegalDoc.PRIVACY ->
        "这是 Think & Act 的《隐私政策》占位文本。\n\n" +
            "内测期间,我们收集你输入的任务/计划/复盘内容,用于在你自己的账号下提供规划、提醒、历史与复盘功能。\n\n" +
            "语音转写仅用于把你说的话变成文字,不另作他用。\n\n" +
            "你的数据按账号隔离(每个手机号一个账号)。正式上架前,这里会替换为正式的隐私政策。"
}
