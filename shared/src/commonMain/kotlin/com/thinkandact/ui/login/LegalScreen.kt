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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thinkandact.ui.theme.TnaColors
import com.thinkandact.ui.theme.TnaTypography

/** 协议文档类型。 */
enum class LegalDoc(val title: String) { TERMS("用户协议"), PRIVACY("隐私政策") }

/**
 * 法务正文 v1.0(内测版 · 个人开发者主体)。
 * TODO(老板):把 [CONTACT_EMAIL] 换成真实联系邮箱(此一处改动即全文生效);公开发版前请律师过一遍。
 */
private const val CONTACT_EMAIL = "【联系邮箱】"
private const val EFFECTIVE_DATE = "2026年6月"

/** 协议正文页(v1.0 内测正式初版;返回键/← 均回登录页,见 App.kt 的 AppBackHandler)。 */
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
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(999.dp)).background(TnaColors.Surface)
                    .clickable(onClick = onBack)
                    .semantics { contentDescription = "返回登录页" },
                contentAlignment = Alignment.Center,
            ) { Text("←", style = TnaTypography.Body.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold)) }
            Text("  《${doc.title}》", style = TnaTypography.SectionTitle.copy(color = TnaColors.Ink))
        }
        Spacer(Modifier.height(16.dp))
        Text("生效日期:$EFFECTIVE_DATE · 版本 v1.0(内测)", style = TnaTypography.Mono.copy(color = TnaColors.Muted))
        Spacer(Modifier.height(12.dp))
        Text(legalBody(doc), style = TnaTypography.AiVoice.copy(color = TnaColors.Ink))
    }
}

private fun legalBody(doc: LegalDoc): String = when (doc) {
    LegalDoc.TERMS -> """
欢迎使用 Think & Act(中文名「你的一日搭子」,下称"本应用")。本应用由个人开发者(下称"开发者")开发和运营。使用前请读完本协议——我们尽量写得简短、说人话。

1. 关于本应用
本应用是一款帮助你规划当天、执行任务、晚间复盘的个人工具。当前为内测版本:功能仍在开发打磨中,可能存在缺陷、变更或数据问题。

2. 账号
· 使用手机号 + 短信验证码登录;未注册的手机号将自动创建账号。
· 账号仅供你本人使用,请妥善保管手机号与验证码。
· 内测期间,开发者可能因测试需要重置或调整服务;重要变更会尽量提前告知。

3. 你的内容
· 你在本应用中输入的计划、任务、语音内容、随手记等,属于你。
· 你授权开发者为"向你提供服务"之目的处理这些内容(例如调用 AI 服务生成计划),具体见《隐私政策》。
· 开发者不会将你的内容用于向你投放广告,也不会出售你的内容。

4. 合理使用
请不要利用本应用从事违法活动,不要尝试攻击、逆向、爬取或干扰服务运行。

5. AI 生成内容说明
本应用的计划建议、总结与提醒由人工智能生成,仅供参考,可能不准确或不合适。重要事项(如就医、财务、法律安排)请自行判断,不要仅依赖 AI 建议。

6. 免责与责任限制
· 内测版本按"现状"提供,不对可用性、连续性、数据完整性作出保证(我们会尽力,但请理解内测的性质,重要信息建议自行留底)。
· 在法律允许的范围内,开发者对使用本应用产生的间接损失不承担责任。

7. 协议变更与终止
· 协议如有实质变更,会在应用内提示;继续使用即视为接受。
· 你可以随时停止使用;如需删除账号与数据,见《隐私政策》第 6 条。

8. 联系方式
邮箱:$CONTACT_EMAIL
""".trim()

    LegalDoc.PRIVACY -> """
这份政策说明本应用收集什么、用来做什么、存在哪里、你有什么权利。原则只有一条:只收集为了把"你的一日搭子"做好所必需的信息,不做别的用途。

1. 我们收集什么
· 账号信息:手机号 —— 用于登录、识别你的账号。
· 计划与任务:你输入的计划、任务、完成/跳过记录、随手记 —— 用于提供核心功能(生成计划、执行提醒、复盘、历史)。
· 语音:按住说话时的语音 —— 仅用于即时转写为文字;转写完成后应用不保留录音。
· 使用与设备信息:设备型号、系统版本、崩溃日志、基本使用记录 —— 用于排查问题、改进产品。
我们不收集:通讯录、位置、相册(除非将来某功能需要,会单独征求同意)。

2. AI 处理说明(重要)
为生成计划、总结与提醒,你的输入文本会被发送至第三方 AI 服务处理:
· 大模型服务:DeepSeek(中国境内服务商),用于理解你的输入并生成计划/总结;
· 语音识别:腾讯云(中国境内服务商),用于将语音转写为文字。
我们只发送完成该次功能所必需的内容,不附带你的手机号等身份信息。

3. 数据存在哪里(如实告知)
你的账号与任务数据目前存储于 Supabase 云服务(服务器位于新加坡)。内测期我们如实告知这一点;在正式公开发布前,数据将迁移至中国境内服务器,届时会更新本政策并提示你。

4. 我们如何使用
· 提供并改进核心功能(计划、执行、复盘、提醒、随手记);
· 通过你的使用模式(如完成情况、节律)让建议更贴合你——这些学习只服务于你自己的体验;
· 排查故障。
我们不向你投放广告,不出售你的个人信息,不与无关第三方共享。

5. 保存期限
数据在你使用期间保存;删除账号后,我们将在合理期限内(不超过 30 天)删除或匿名化你的个人数据(法律要求留存的除外)。

6. 你的权利
内测版本应用内的数据管理功能仍在建设中。在此期间,你可以随时通过邮箱 $CONTACT_EMAIL 联系开发者:
· 查询、更正你的个人信息;
· 删除全部数据 / 注销账号(我们将在 15 个工作日内处理并答复);
· 撤回同意、对处理方式提出异议。
正式版将提供应用内的记忆管理、数据导出与一键注销。

7. 未成年人
本应用面向成年用户。如你未满 14 周岁,请在监护人同意并陪同下使用;监护人可联系我们删除相关数据。

8. 安全
数据传输使用加密通道(HTTPS),访问受账号鉴权与行级权限控制。但请理解,任何互联网服务都无法保证绝对安全。

9. 政策更新
政策如有实质变更(尤其是第 3 条数据位置变化时),会在应用内显著提示。

10. 联系我们
邮箱:$CONTACT_EMAIL
""".trim()
}
