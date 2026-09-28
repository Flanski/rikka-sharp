package me.rerere.rikkahub.ui.components.notification

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.notification.AppNotification
import me.rerere.rikkahub.data.notification.AppNotificationCenter

/**
 * 应用内通知的宿主 —— 挂在根布局上，订阅 [AppNotificationCenter] 并渲染。
 *
 * ── 与授权弹窗（`ToolApprovalDialog`）的关系 ──
 * 两者**共用同一套视觉模板**（来自 `/workspace/通知弹窗特效Demo.html`）：
 * 同款配色（normal 蓝 / warning 橙黑字 / error 红）、同款 header 结构（图标 + 标题 + 副标题）、
 * 同款圆角与按钮样式、同款 `cubic-bezier(0.4,0,0.2,1)` 缓动（= [FastOutSlowInEasing]）。
 *
 * ★但**刻意不加遮罩**，也不强制用户先处理它：
 *  · 授权弹窗必须阻塞 —— 它要求一个明确结论，不处理就会把生成卡住；
 *  · 应用内通知只是「告诉你一件事」，加遮罩会挡住界面、阻碍正常操作，反而更烦。
 * 所以这里改为**顶部堆叠、不拦截触摸**，用与模板一致的展开动画出现。
 *
 * ── 为什么不复用 sonner 的 Toaster ──
 * 那是第三方的吐司组件，样式改不动（用户要求改成通知模板的样式）。
 * 而是让 `LocalToaster` 提供一个行为兼容的 `AppToaster`，
 * 把所有 `toaster.show(...)` 转发到 [AppNotificationCenter] —— 因此那 **150+ 处调用点一行都不用改**，
 * 样式则统一由这里用通知模板渲染。
 */
@Composable
fun AppNotificationHost(modifier: Modifier = Modifier) {
    val notifications by AppNotificationCenter.notifications.collectAsStateWithLifecycle()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 宽屏限宽居中，窄屏留出左右边距 —— 与模板的 PC 端适配同一思路
        val isWide = maxWidth >= 768.dp
        val cardModifier = if (isWide) {
            Modifier.widthIn(max = 480.dp)
        } else {
            Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // ★不拦截触摸：通知区域之外的点击照常传给下层界面。
                // Box 本身不消费事件，所以只要内部不铺满全屏就不影响操作。
                .padding(top = 56.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = cardModifier,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                notifications.forEach { n ->
                    // key 用 id：同一位置的内容变化时不会错误复用动画状态
                    key(n.id) {
                        // ★用 MutableTransitionState 而不是 `visible = true`：
                        //   直接传 true 表示"一开始就可见"，**不会播放进场动画** ——
                        //   通知会突兀地闪现。用 targetState = true 才能让 enter 动画真正跑起来。
                        //   （这与授权弹窗里那种"出现即播放"的做法一致。）
                        val visibleState = remember {
                            MutableTransitionState(false).apply { targetState = true }
                        }
                        AnimatedVisibility(
                            visibleState = visibleState,
                            enter = expandVertically(
                                expandFrom = Alignment.Top,
                                animationSpec = tween(250, easing = FastOutSlowInEasing),
                            ) + fadeIn(tween(250)),
                            exit = shrinkVertically(
                                shrinkTowards = Alignment.Top,
                                animationSpec = tween(200, easing = FastOutSlowInEasing),
                            ) + fadeOut(tween(200)),
                        ) {
                            AppNotificationCard(
                                notification = n,
                                onDismiss = { AppNotificationCenter.dismiss(n.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单条应用内通知的卡片。
 *
 * 结构照搬模板：header（图标 + 标题 + 副标题）/ body（可滚动）/ footer（按钮右对齐）。
 */
@Composable
private fun AppNotificationCard(
    notification: AppNotification,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current

    // ★复制后的反馈**不能**再用 toaster.show(...)。
    //   因为 `toaster` 现在也走 AppNotificationCenter ——
    //   那样一点复制就会再叠一条「已复制」通知，把原来那条挤掉（MAX_VISIBLE=3），
    //   用户反而找不到自己刚要复制的内容。
    //   改成「按钮文字临时变成『已复制』」：零副作用、不打断、不改动通知栈。
    var justCopied by remember { mutableStateOf(false) }
    LaunchedEffect(justCopied) {
        if (justCopied) {
            delay(1500)
            justCopied = false
        }
    }

    val colors = remember(notification.type) { NotificationTemplateColors.of(notification.type) }
    val icon = when (notification.type) {
        AppNotification.Type.NORMAL -> HugeIcons.InformationCircle
        AppNotification.Type.WARNING -> HugeIcons.Alert01
        AppNotification.Type.ERROR -> HugeIcons.Alert01
    }
    val subtitle = when (notification.type) {
        AppNotification.Type.NORMAL -> stringResource(R.string.app_notification_subtitle_normal)
        AppNotification.Type.WARNING -> stringResource(R.string.app_notification_subtitle_warning)
        AppNotification.Type.ERROR -> stringResource(R.string.app_notification_subtitle_error)
    }
    // 调用方未给标题时用类型对应的本地化标题。
    // 这样「工具发来的通知」（自带标题）与「toast 转来的」（不带标题）都能正确显示，
    // 且都不需要在代码里写死文案。
    val defaultTitle = when (notification.type) {
        AppNotification.Type.NORMAL -> stringResource(R.string.app_notification_title_normal)
        AppNotification.Type.WARNING -> stringResource(R.string.app_notification_title_warning)
        AppNotification.Type.ERROR -> stringResource(R.string.app_notification_title_error)
    }
    val title = notification.title?.takeIf { it.isNotBlank() } ?: defaultTitle

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.container),
    ) {
        // ── header ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.header)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = colors.onHeader, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = colors.onHeader,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = subtitle,
                    color = colors.onHeader.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                )
            }
            // 关闭按钮放在 header 右侧：与模板的 .notification-close 语义一致
            TextButton(onClick = onDismiss) {
                Icon(
                    HugeIcons.Cancel01,
                    contentDescription = stringResource(R.string.app_notification_close),
                    tint = colors.onHeader,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // ── body ──
        // 高度上限 + 可滚动：模板里 body 是 flex:1 可滚动的，这里用 maxHeight 达到同样效果
        // （不设上限的话，一条超长报错会把整屏占满）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = notification.body,
                color = colors.onContainer,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
        }

        // ── footer ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        ) {
            if (notification.copyText != null) {
                // 复制按钮：用户明确要求（报错信息需要能复制出去）
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(notification.copyText!!))
                    justCopied = true
                }) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = if (justCopied) HugeIcons.CheckmarkCircle02 else HugeIcons.Copy01,
                            contentDescription = null,
                            tint = colors.buttonText,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = stringResource(
                                if (justCopied) R.string.app_notification_copied else R.string.app_notification_copy
                            ),
                            color = colors.buttonText,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(containerColor = Color.White),
            ) {
                Text(
                    text = stringResource(R.string.app_notification_ok),
                    color = colors.buttonText,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * 通知模板的配色 —— 与 `ToolApprovalDialog` 里的 `ApprovalColors` 同源，
 * 都取自 `/workspace/通知弹窗特效Demo.html`。
 *
 * 模板的配色关系：
 * ```css
 * .notification-modal            { background: #409eff }
 * .notification-body             —— 无 background（透明显蓝）
 * .type-normal  .notification-header { background: #409eff; color: #fff }
 * .type-warning .notification-header { background: #e6a23c; color: #000 }
 * .type-error   .notification-header { background: #f56c6c; color: #fff }
 * .notification-btn              { background: #fff; color: #409eff }
 * ```
 */
internal object NotificationTemplateColors {
    val normalContainer = Color(0xFF409EFF)
    val normalHeader = Color(0xFF409EFF)
    val normalOnHeader = Color(0xFFFFFFFF)

    val warningContainer = Color(0xFF409EFF)
    val warningHeader = Color(0xFFE6A23C)
    val warningOnHeader = Color(0xFF000000)

    val errorContainer = Color(0xFF409EFF)
    val errorHeader = Color(0xFFF56C6C)
    val errorOnHeader = Color(0xFFFFFFFF)

    val onContainer = Color(0xFFFFFFFF)
    val buttonText = Color(0xFF409EFF)

    data class Palette(
        val container: Color,
        val header: Color,
        val onHeader: Color,
        val onContainer: Color,
        val buttonText: Color,
    )

    fun of(type: AppNotification.Type): Palette = when (type) {
        AppNotification.Type.NORMAL -> Palette(
            container = normalContainer, header = normalHeader,
            onHeader = normalOnHeader, onContainer = onContainer, buttonText = buttonText,
        )
        AppNotification.Type.WARNING -> Palette(
            container = warningContainer, header = warningHeader,
            onHeader = warningOnHeader, onContainer = onContainer, buttonText = buttonText,
        )
        AppNotification.Type.ERROR -> Palette(
            container = errorContainer, header = errorHeader,
            onHeader = errorOnHeader, onContainer = onContainer, buttonText = buttonText,
        )
    }
}
