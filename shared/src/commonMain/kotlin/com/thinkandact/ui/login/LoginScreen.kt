package com.thinkandact.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
    val focusManager = LocalFocusManager.current

    // 验证码够 6 位 → 自动收起键盘,露出「进入」+ 协议勾选(iOS 数字键盘没有回退键)。
    LaunchedEffect(s.code) { if (s.code.length >= 6) focusManager.clearFocus() }

    // 点空白处收起键盘(数字键盘无 return,否则键盘挡住下半屏没法操作)。
    Box(
        modifier = Modifier.fillMaxSize().background(TnaColors.Background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { focusManager.clearFocus() },
    ) {
    Column(
        modifier = Modifier.fillMaxSize()
            .statusBarsPadding().navigationBarsPadding()
            .imePadding() // 键盘弹出时整体上移,不再遮挡按钮
            .verticalScroll(rememberScrollState()) // 兜底:小屏也能滚到底部
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
            PlainInput(value = s.phone, onChange = viewModel::onPhone, placeholder = "手机号", modifier = Modifier.weight(1f), imeAction = ImeAction.Next)
        }
        Spacer(Modifier.height(12.dp))
        // 验证码 + 获取
        FieldRow {
            PlainInput(value = s.code, onChange = viewModel::onCode, placeholder = "验证码", modifier = Modifier.weight(1f))
            val codeLabel = when {
                s.isSendingCode -> "发送中…"
                s.countdown > 0 -> "${s.countdown}s 后重发"
                else -> "获取验证码"
            }
            Text(
                codeLabel,
                modifier = Modifier.clickable(enabled = s.canSendCode, onClick = viewModel::sendCode),
                style = TnaTypography.Body.copy(color = if (s.canSendCode) TnaColors.AccentDeep else TnaColors.Muted, fontWeight = FontWeight.SemiBold),
            )
        }

        Spacer(Modifier.height(18.dp))
        // 进入 — F-10:灰态真禁用,clickable 跟随 canLogin。
        Box(
            modifier = Modifier.fillMaxWidth().height(50.dp)
                .background(if (s.canLogin) TnaColors.Accent else Color(0xFFDEC9BC), RoundedCornerShape(14.dp))
                .clickable(enabled = s.canLogin) { viewModel.login(onLoggedIn) }
                .semantics { contentDescription = if (s.canLogin) "进入" else "进入(暂不可用)" },
            contentAlignment = Alignment.Center,
        ) {
            if (s.isLoading) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            else Text("进入", style = TnaTypography.Body.copy(color = Color.White, fontWeight = FontWeight.Bold))
        }

        // F-10:缺什么的顺序提示(手机号 → 验证码 → 协议),解释按钮为何灰着。
        (s.error ?: s.gateHint)?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = TnaTypography.AiVoice.copy(color = TnaColors.AccentDeep), textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(14.dp))
        // 协议勾选门控 — F-10:扩大点选热区(整行可点)+ 无障碍语义。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = viewModel::toggleAgree,
                )
                .semantics {
                    contentDescription = if (s.agreed) "已同意用户协议与隐私政策,点按取消" else "我已阅读并同意用户协议与隐私政策,点按勾选"
                    role = Role.Checkbox
                    toggleableState = if (s.agreed) ToggleableState.On else ToggleableState.Off
                }
                .padding(vertical = 4.dp),
        ) {
            Box(
                modifier = Modifier.size(20.dp)
                    .background(if (s.agreed) TnaColors.Accent else Color.Transparent, RoundedCornerShape(5.dp))
                    .border(1.5.dp, if (s.agreed) TnaColors.Accent else Color(0xFFD8C4B5), RoundedCornerShape(5.dp)),
                contentAlignment = Alignment.Center,
            ) { if (s.agreed) Text("✓", style = TnaTypography.Mono.copy(color = Color.White)) }
            Text("我已阅读并同意 ", modifier = Modifier.padding(start = 8.dp), style = TnaTypography.Mono.copy(color = TnaColors.Muted))
        }
        Row(modifier = Modifier.padding(start = 26.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("《用户协议》", modifier = Modifier.clickable { onOpenLegal(LegalDoc.TERMS) }, style = TnaTypography.Mono.copy(color = TnaColors.AccentDeep))
            Text(" 和 ", style = TnaTypography.Mono.copy(color = TnaColors.Muted))
            Text("《隐私政策》", modifier = Modifier.clickable { onOpenLegal(LegalDoc.PRIVACY) }, style = TnaTypography.Mono.copy(color = TnaColors.AccentDeep))
        }

        Spacer(Modifier.height(10.dp))
        Text("未注册的手机号将自动创建账号 · 验证码 5 分钟内有效", style = TnaTypography.Mono.copy(color = TnaColors.MutedSoft), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

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
        Spacer(Modifier.height(28.dp)) // 底部留白,滚动到底时最后一项也不顶边
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
private fun PlainInput(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Done,
) {
    val focusManager = LocalFocusManager.current
    BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = TnaTypography.Body.copy(color = TnaColors.Ink),
        cursorBrush = SolidColor(TnaColors.Accent),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onNext = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) },
            onDone = { focusManager.clearFocus() },
        ),
        modifier = modifier,
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, style = TnaTypography.Body.copy(color = TnaColors.MutedSoft))
                inner()
            }
        },
    )
}
