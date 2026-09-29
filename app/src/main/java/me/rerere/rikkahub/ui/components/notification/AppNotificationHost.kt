package me.rerere.rikkahub.ui.components.notification

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.notification.AppNotification
import me.rerere.rikkahub.data.notification.AppNotificationCenter

/**
 * 应用内通知的宿主 —— 订阅 [AppNotificationCenter] 并渲染。
 *
 * ── ★严格照模板：`/workspace/通知弹窗特效Demo.html` ──
 *
 * 模板的结构（HTML/CSS 原文）：
 * ```html
 * <div class="notification-overlay" onclick="closeNotification()"></div>
 * <div class="notification-modal type-normal">
 *   <div class="notification-header">图标 + 标题</div>
 *   <div class="notification-body">内容（可滚动）</div>
 *   <div class="notification-footer"><button class="notification-btn">确定</button></div>
 * </div>
 * ```
 * CSS 要点（移动端）：
 *   · 遮罩：position fixed、inset 0、背景 rgba(0,0,0,0.5)
 *   · 弹窗：left 0 / right 0（**全宽吸附屏幕两侧**）、height 33.333vh、背景 #409eff
 *   · 弹窗**没有圆角** —— border-radius 只在 PC 端 @media 里出现（见下）
 *   · PC 端（@media min-width 768px）：width 480px、border-radius 8px、居中
 *
 * ★★我第一版**自己另写了一套**（没复用已经照模板写好的组件），结果三处全违背模板：
 *   ① `padding(horizontal = 12.dp)` → **左右有间隙**（模板是 left:0 / right:0，**全宽吸附屏幕两侧**）；
 *   ② `RoundedCornerShape(8.dp)` → **圆角**（模板移动端是直角；圆角只在 PC 端 `@media` 里）；
 *   ③ **没有遮罩**（模板有 `.notification-overlay`，0.5 透明黑）。
 *
 *   现在改为**直接用 [NotificationTemplateDialog]** —— 它就是照模板写的：
 *   遮罩 / 全宽 / 无圆角 / 1/3 屏高 / 垂直居中 / header 一行 /
 *   可滚动 body / footer 右对齐，全部与模板一致。**不再自创样式。**
 *
 * ── 一次只显示一个 ──
 * 模板里只有一个 `#notificationModal` 元素，点「确定」或点遮罩即关闭 ——
 * 所以这里也只渲染**队列里的第一条**，关闭后自然露出下一条。
 * （第一版是堆叠显示多条，那也不是模板的行为。）
 *
 * ── 为什么不需要 zIndex ──
 * [NotificationTemplateDialog] 内部用 `Dialog`，那是**独立的窗口层**，
 * 天然绘制在页面内容之上 —— 不需要同层兄弟间的 zIndex 参与。
 */
@Composable
fun AppNotificationHost() {
    val notifications by AppNotificationCenter.notifications.collectAsStateWithLifecycle()

    // 只取第一条：模板一次只显示一个弹窗。
    // 列表按「新的在前」维护，所以第一条就是最新的那条。
    val current = notifications.firstOrNull() ?: return

    val colors = NotificationTemplateColors.of(current.type)
    val icon = when (current.type) {
        AppNotification.Type.NORMAL -> HugeIcons.InformationCircle
        AppNotification.Type.WARNING, AppNotification.Type.ERROR -> HugeIcons.Alert01
    }
    val defaultTitle = when (current.type) {
        AppNotification.Type.NORMAL -> stringResource(R.string.app_notification_title_normal)
        AppNotification.Type.WARNING -> stringResource(R.string.app_notification_title_warning)
        AppNotification.Type.ERROR -> stringResource(R.string.app_notification_title_error)
    }

    val dismiss = { AppNotificationCenter.dismiss(current.id) }

    NotificationTemplateDialog(
        type = current.type,
        title = current.title?.takeIf { it.isNotBlank() } ?: defaultTitle,
        icon = icon,
        // 模板固定 1/3 屏高
        heightFraction = 1f / 3f,
        // ★模板**点遮罩关闭**（`onclick="closeNotification()"`）→ 必须为 true。
        //   （工具授权弹窗才用 false —— 它需要一个明确结论，不能让遮罩把 Pending 留在那。）
        dismissOnClickOutside = true,
        onDismissRequest = { dismiss() },
        footer = {
            // 模板 footer 里只有一个「确定」按钮（右对齐）。
            // 复制按钮是用户额外要求的（报错信息要能复制出去），放在它左边。
            current.copyText?.let { text ->
                CopyButton(text = text)
            }
            TemplateDialogButton(
                text = stringResource(R.string.app_notification_ok),
                onClick = { dismiss() },
                primary = true,
            )
        },
    ) {
        // body：模板里是 `flex:1; padding:16px; overflow-y:auto; color:#fff`
        // 可滚动与内边距由 NotificationTemplateDialog 的 TemplateScrollColumn 负责，
        // 这里只放内容本身。
        Text(
            text = current.body,
            color = colors.onContainer,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 「复制」按钮（模板的次要按钮样式 `.btn-retry`：半透明白底白字）。
 *
 * 复制后的反馈用**按钮文字临时变化**，而不是再弹一条通知 ——
 * 后者会把用户刚要复制的那条挤掉（通知一次只显示一条）。
 */
@Composable
private fun CopyButton(text: String) {
    val clipboard = LocalClipboardManager.current
    var justCopied by remember { mutableStateOf(false) }
    LaunchedEffect(justCopied) {
        if (justCopied) {
            delay(1500)
            justCopied = false
        }
    }

    TemplateDialogButton(
        text = stringResource(
            if (justCopied) R.string.app_notification_copied else R.string.app_notification_copy
        ),
        onClick = {
            clipboard.setText(AnnotatedString(text))
            justCopied = true
        },
        primary = false,
    )
}
