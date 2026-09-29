package me.rerere.rikkahub.ui.components.toolapproval

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.notification.AppNotification
import me.rerere.rikkahub.ui.components.notification.NotificationTemplateDialog
import me.rerere.rikkahub.ui.components.notification.NotificationTemplateColors
import me.rerere.rikkahub.ui.components.notification.TemplateDialogButton

/**
 * 工具授权弹窗。
 *
 * ── 为什么不用原来的「消息内联按钮」──
 * 原实现是在聊天消息里挂两个小图标按钮，**用户很容易没注意到**（消息在滚动区里、
 * 且需要主动查看那条消息）。而授权是「AI 要动你的设备」这件事的确认环节，
 * 属于**必须被看见**的交互。改为一屏遮罩 + 居中弹窗，配合展开动画。
 *
 * ── 本轮按模板规格改正了四处 ──
 *  ① **标题栏过高** —— 原来标题下还有一行「请先确认它要做什么」。
 *     模板的 header 只有一行；那行字既是废话又占屏幕。现已去掉。
 *  ② **去圆角** —— 模板移动端是直角，此前套了 8dp 圆角。
 *  ③ **按钮圆角** —— 模板是 4px，此前用 `TextButton` 默认的胶囊形。
 *  ④ **滚动条** —— 此前 `drawWithContent` 放错层级，滑块算出来接近整个视口高、
 *     完全看不出是滚动条。现由 [NotificationTemplateDialog] 的 `TemplateScrollColumn` 统一处理。
 *
 * ── 这里刻意**不可**轻易关闭 ──
 * 点遮罩 / 返回键都不关闭：授权必须有明确结论（允许或拒绝），
 * 否则会出现「未决的 Pending 状态」把生成卡住。想要中断可以点「拒绝」。
 */
@Composable
fun ToolApprovalDialog(
    toolName: String,
    intent: String,
    purpose: String,
    argumentsPreview: String,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    /**
     * 还有几个工具等待确认（含当前这个）。
     *
     * ── 为什么需要显示它 ──
     * 一次有多个工具待批准时，生成会**等全部处理完**才继续
     * （见 `ChatService.handleToolApproval` 结尾的 `hasPendingTools` 判断）。
     * 于是用户逐个点「允许」时，**前面的点击不会有任何可见反应** ——
     * 从界面上看很像"点了没用"甚至"只有最后一个生效"。
     *
     * 把剩余数量说出来，用户就知道「还要点几次」，不会误判成故障。
     * ≥2 时同时给出「全部允许」，让用户一次做完。
     */
    pendingCount: Int = 1,
    /** 一次批准全部待确认项；为 null 时不显示该按钮 */
    onApproveAll: (() -> Unit)? = null,
) {
    val colors = NotificationTemplateColors.of(AppNotification.Type.WARNING)
    val notProvided = stringResource(R.string.tool_approval_not_provided)

    NotificationTemplateDialog(
        // 授权用 warning（橙色 header）—— 它需要「先停一下」的语义，
        // 蓝色 header 会显得像普通通知而被略过。这是模板本身就提供的类型，不是另造配色。
        type = AppNotification.Type.WARNING,
        title = stringResource(R.string.tool_approval_title),
        icon = HugeIcons.Alert01,
        // 高度用模板的 1/3。授权内容通常不长，不需要更大。
        heightFraction = 1f / 3f,
        // ★不再传 subtitle —— 模板 header 只有一行，多一行就是「臃肿」
        onDismissRequest = { /* 刻意忽略：授权必须给出明确结论 */ },
        footer = {
            // 多个待确认时把剩余数量标出来 —— 否则用户不知道还要点几次，
            // 会以为是"点了没反应"。（生成要等全部处理完才继续，这是设计如此。）
            if (pendingCount > 1) {
                Text(
                    text = stringResource(R.string.tool_approval_pending_count, pendingCount),
                    color = colors.onContainer,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
            TemplateDialogButton(
                text = stringResource(R.string.tool_approval_deny),
                onClick = onDeny,
                primary = false,
            )
            if (pendingCount > 1 && onApproveAll != null) {
                TemplateDialogButton(
                    text = stringResource(R.string.tool_approval_allow_all),
                    onClick = onApproveAll,
                    primary = false,
                )
            }
            TemplateDialogButton(
                text = stringResource(R.string.tool_approval_allow),
                onClick = onApprove,
                primary = true,
            )
        },
    ) {
        // 注意：这里已经处在 NotificationTemplateDialog 的可滚动内容区里，
        // 不需要再套一层 scroll —— 否则会出现「滚动里套滚动」。
        LabelledBlock(
            label = stringResource(R.string.tool_approval_action),
            value = intent.ifBlank { notProvided },
            colors = colors,
        )
        LabelledBlock(
            label = stringResource(R.string.tool_approval_reason),
            value = purpose.ifBlank { notProvided },
            colors = colors,
        )
        LabelledBlock(
            label = stringResource(R.string.tool_approval_tool),
            value = toolName,
            colors = colors,
            monospace = true,
        )
        if (argumentsPreview.isNotBlank()) {
            LabelledBlock(
                label = stringResource(R.string.tool_approval_arguments),
                value = argumentsPreview,
                colors = colors,
                monospace = true,
            )
        }
    }
}

/** 「标签 + 内容」块。标签小一号、半透明，与模板里正文的层级关系一致 */
@Composable
private fun LabelledBlock(
    label: String,
    value: String,
    colors: NotificationTemplateColors.Palette,
    monospace: Boolean = false,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            color = colors.onContainer.copy(alpha = 0.75f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = value,
            color = colors.onContainer,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            fontFamily = if (monospace) FontFamily.Monospace else null,
        )
    }
}
