package com.thinkandact.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thinkandact.ui.theme.TnaColors
import com.thinkandact.ui.theme.TnaTypography

/**
 * 通用顶栏右侧的一个圆形图标按钮。
 * primary=true → 陶土实底、白图标(主导航,如「今天→」);否则白底、细描边、陶土线条图标。
 * badge>0 → 右上角红点数字(如收件箱待处理数)。
 */
data class TnaTopBarAction(
    val icon: ImageVector,
    val contentDescription: String,
    val onClick: () -> Unit,
    val primary: Boolean = false,
    val badge: Int? = null,
)

/**
 * 全局通用顶栏(Morning 改版引入,执行屏/复盘等统一复用)。
 * - 右侧:一排 34dp 同款圆形图标按钮(矢量线条,不用 emoji)。
 * - 可选左侧:返回(圆形 ←)+ 标题(收小、永不换行、超长省略)。
 * - 可选 [trailing]:跟在图标后的非按钮元素(如执行屏的「2/5」进度小胶囊)。
 */
@Composable
fun TnaTopBar(
    actions: List<TnaTopBarAction>,
    modifier: Modifier = Modifier,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            CircleIconButton(TnaTopBarAction(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack))
        }
        if (title != null) {
            Text(
                title,
                modifier = Modifier.weight(1f).padding(start = if (onBack != null) 10.dp else 2.dp, end = 8.dp),
                style = TnaTypography.SectionTitle.copy(color = TnaColors.Ink, fontWeight = FontWeight.Bold, fontSize = 15.5.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            actions.forEach { CircleIconButton(it) }
            trailing?.invoke()
        }
    }
}

@Composable
private fun CircleIconButton(action: TnaTopBarAction) {
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(if (action.primary) TnaColors.Accent else TnaColors.Surface, CircleShape)
                .then(if (action.primary) Modifier else Modifier.border(1.dp, TnaColors.Line, CircleShape))
                .clickable(onClick = action.onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = action.contentDescription,
                tint = if (action.primary) Color.White else TnaColors.AccentDeep,
                modifier = Modifier.size(18.dp),
            )
        }
        val n = action.badge ?: 0
        if (n > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 3.dp, y = (-3).dp)
                    .defaultMinSize(minWidth = 15.dp, minHeight = 15.dp)
                    .background(TnaColors.Accent, CircleShape)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (n > 99) "99+" else n.toString(),
                    style = TnaTypography.Mono.copy(color = Color.White, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
