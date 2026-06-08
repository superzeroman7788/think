package com.thinkandact.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.unit.dp
import com.thinkandact.ui.theme.TnaColors
import com.thinkandact.ui.theme.TnaTypography
import org.koin.compose.viewmodel.koinViewModel

private val WeChatGreen = Color(0xFF4DB04F)

@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    onOpenLegal: (LegalDoc) -> Unit,
    viewModel: LoginViewModel = koinViewModel(),
) {
    val s by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().background(TnaColors.Background)
            .statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(90.dp))
        // 品牌区
        Text("★", style = TnaTypography.Body.copy(color = Color(0xFFF6DCAE)))
        Spacer(Modifier.height(8.dp))
        Text("think & act", style = TnaTypography.Display.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(8.dp))
        Text("你的一日搭子", style = TnaTypography.Body.copy(color = TnaColors.AccentDeep, fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.height(6.dp))
        Text("把脑子里的今天倒出来,我帮你收成一条舒服的时间线。", style = TnaTypography.AiVoice.copy(color = TnaColors.InkSoft), textAlign = TextAlign.Center)

        Spacer(Modifier.height(44.dp))

        // 手机号
        FieldRow {
            Text("+86", style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.Medium))
            Box(Modifier.width(1.dp).height(20.dp).background(TnaColors.Line).padding(horizontal = 0.dp))
            PlainInput(value = s.phone, onChange = viewModel::onPhone, placeholder = "手机号", modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        // 验证码 + 获取
        FieldRow {
            PlainInput(value = s.code, onChange = viewModel::onCode, placeholder = "验证码", modifier = Modifier.weight(1f))
            val codeLabel = if (s.countdown > 0) "${s.countdown}s 后重发" else "获取验证码"
            Text(
                codeLabel,
                modifier = Modifier.clickable(enabled = s.canSendCode, onClick = viewModel::sendCode),
                style = TnaTypography.Body.copy(color = if (s.canSendCode) TnaColors.AccentDeep else TnaColors.Muted, fontWeight = FontWeight.SemiBold),
            )
        }

        Spacer(Modifier.height(18.dp))
        // 进入
        Box(
            modifier = Modifier.fillMaxWidth().height(50.dp)
                .background(if (s.canLogin) TnaColors.Accent else Color(0xFFDEC9BC), RoundedCornerShape(14.dp))
                .clickable(enabled = !s.isLoading) { viewModel.login(onLoggedIn) },
            contentAlignment = Alignment.Center,
        ) {
            if (s.isLoading) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            else Text("进入", style = TnaTypography.Body.copy(color = Color.White, fontWeight = FontWeight.Bold))
        }

        s.error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = TnaTypography.AiVoice.copy(color = TnaColors.AccentDeep), textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(14.dp))
        // 协议勾选门控
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier.size(18.dp).padding(top = 1.dp)
                    .background(if (s.agreed) TnaColors.Accent else Color.Transparent, RoundedCornerShape(5.dp))
                    .border(1.5.dp, if (s.agreed) TnaColors.Accent else Color(0xFFD8C4B5), RoundedCornerShape(5.dp))
                    .clickable(onClick = viewModel::toggleAgree),
                contentAlignment = Alignment.Center,
            ) { if (s.agreed) Text("✓", style = TnaTypography.Mono.copy(color = Color.White)) }
            Row(modifier = Modifier.padding(start = 8.dp)) {
                Text("我已阅读并同意 ", style = TnaTypography.Mono.copy(color = TnaColors.Muted))
            }
        }
        Row(modifier = Modifier.padding(start = 26.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("《用户协议》", modifier = Modifier.clickable { onOpenLegal(LegalDoc.TERMS) }, style = TnaTypography.Mono.copy(color = TnaColors.AccentDeep))
            Text(" 和 ", style = TnaTypography.Mono.copy(color = TnaColors.Muted))
            Text("《隐私政策》", modifier = Modifier.clickable { onOpenLegal(LegalDoc.PRIVACY) }, style = TnaTypography.Mono.copy(color = TnaColors.AccentDeep))
        }

        Spacer(Modifier.height(10.dp))
        Text("未注册的手机号将自动创建账号 · 测试期任意验证码可进", style = TnaTypography.Mono.copy(color = TnaColors.MutedSoft), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

        // 分隔 + 微信占位
        Spacer(Modifier.height(26.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f).height(1.dp).background(TnaColors.Line))
            Text("  其它方式  ", style = TnaTypography.Mono.copy(color = TnaColors.MutedSoft))
            Box(Modifier.weight(1f).height(1.dp).background(TnaColors.Line))
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp)
                .background(TnaColors.Surface, RoundedCornerShape(14.dp))
                .border(1.dp, Color(0xFFEAD9CB), RoundedCornerShape(14.dp))
                .clickable(onClick = viewModel::onWechat),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(20.dp).background(WeChatGreen, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                Text("✚", style = TnaTypography.Mono.copy(color = Color.White))
            }
            Text("  微信登录", style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.Medium))
        }
        s.wechatNote?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, modifier = Modifier.clickable(onClick = viewModel::dismissWechatNote).fillMaxWidth(), style = TnaTypography.Mono.copy(color = TnaColors.Muted), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun FieldRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(52.dp)
            .background(TnaColors.Surface, RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFFEFE2D8), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun PlainInput(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = TnaTypography.Body.copy(color = TnaColors.Ink),
        cursorBrush = SolidColor(TnaColors.Accent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, style = TnaTypography.Body.copy(color = TnaColors.MutedSoft))
                inner()
            }
        },
    )
}
