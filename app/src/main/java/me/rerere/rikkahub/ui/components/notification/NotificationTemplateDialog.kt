package me.rerere.rikkahub.ui.components.notification

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.data.notification.AppNotification
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 通知模板弹窗 —— 所有「用通知模板样式的弹窗」的公共实现。
 *
 * 来源：`/workspace/通知弹窗特效Demo.html`。
 *
 * ── 为什么抽成公共组件 ──
 * 授权确认与 `ask_user` 提问用的是**同一套视觉语言**（同样的 header / 配色 / 动画 / 按钮），
 * 只是内容与高度不同。抽出来意味着样式只维护一处，以后再加同类弹窗也不用复制一遍。
 *
 * ── 模板规格（照搬，不做发挥）──
 * ```css
 * .notification-overlay   position:fixed; inset:0; rgba(0,0,0,.5)     ← 全屏遮罩
 * .notification-modal     height:33.333vh; background:#409eff
 *                         —— 移动端**没有 border-radius**（圆角只在 PC 端 @media 里）
 * .notification-header    padding:14px 16px; 一行图标 + 标题
 * .notification-body      flex:1; padding:16px; overflow-y:auto; 无背景（透明显蓝）
 * .notification-footer    justify-content:flex-end; padding:12px 16px
 * .notification-btn       padding:8px 16px; border-radius:4px; 白底蓝字
 * 动画                    250ms cubic-bezier(.4,0,.2,1)（= FastOutSlowInEasing）
 * ```
 *
 * ★修正了上一版的四个问题（用户指出）：
 *  ① **标题栏过高** —— 上一版在标题下加了一行副标题（如「请先确认它要做什么」），
 *     既占屏幕又是废话。模板的 header **只有一行**，所以 [subtitle] 默认不显示。
 *  ② **圆角** —— 模板移动端是直角，上一版套了 8dp 圆角。现已去掉。
 *  ③ **按钮圆角过大** —— 模板是 4px，`TextButton` 默认是胶囊形。见 [TemplateDialogButton]。
 *  ④ **滚动条看不出是滚动条** —— 见 [TemplateScrollColumn] 的说明（这是理解错误，不是笔误）。
 */
@Composable
fun NotificationTemplateDialog(
    type: AppNotification.Type,
    title: String,
    icon: ImageVector,
    /** 弹窗高度占屏幕的比例。模板固定 1/3；`ask_user` 需要更大空间放表单，所以可配 */
    heightFraction: Float = 1f / 3f,
    /** 可选副标题。**默认不传** —— 模板 header 只有一行，多一行就显得臃肿 */
    subtitle: String? = null,
    /** 右上角是否显示关闭按钮 */
    showCloseButton: Boolean = false,
    onDismissRequest: () -> Unit,
    footer: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 展开动画：scaleY 0 → 1，以中心为轴（模板同款）
    val scaleY = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        scaleY.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        )
    }

    val colors = NotificationTemplateColors.of(type)

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // ★让弹窗窗口延伸到系统栏区域。
            // 不加这一项时窗口会避开状态栏/导航栏，那两条区域**不被遮罩覆盖** ——
            // 表现为「遮罩没盖全」（模板是 position:fixed; inset:0，全屏覆盖）。
            decorFitsSystemWindows = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val screenHeight = maxHeight
            val isWide = maxWidth >= 768.dp

            // 遮罩：rgba(0,0,0,0.5)，随弹窗同步淡入
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f * scaleY.value))
            )

            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier
                        .let { if (isWide) it.widthIn(max = 480.dp) else it.fillMaxWidth() }
                        .height(screenHeight * heightFraction)
                        .graphicsLayer { this.scaleY = scaleY.value }
                        // ★不加 clip(RoundedCornerShape(...))：
                        //   模板移动端是直角（圆角只在 PC 端 @media (min-width:768px) 里，
                        //   那是给桌面浏览器的）。上一版照 PC 端做了 8dp 圆角，与移动端不符。
                        .background(colors.container),
                ) {
                    // ── header ──（模板：padding 14px 16px，一行）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.header)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = colors.onHeader,
                            modifier = Modifier.size(20.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                color = colors.onHeader,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            // 副标题只在调用方明确提供时才占位 —— 默认不显示，避免标题栏臃肿
                            if (!subtitle.isNullOrBlank()) {
                                Text(
                                    text = subtitle,
                                    color = colors.onHeader.copy(alpha = 0.85f),
                                    fontSize = 11.sp,
                                )
                            }
                        }
                        if (showCloseButton) {
                            Text(
                                text = "✕",
                                color = colors.onHeader,
                                fontSize = 18.sp,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() },
                                        onClick = onDismissRequest,
                                    ),
                            )
                        }
                    }

                    // ── body ──（模板：flex:1 + 可滚动 + 无背景 → 透明显容器的蓝）
                    TemplateScrollColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(16.dp),
                        content = content,
                    )

                    // ── footer ──（模板：右对齐，padding 12px 16px）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        content = footer,
                    )
                }
            }
        }
    }
}

/**
 * 可滚动的内容列 + **自绘滚动条**。
 *
 * ── ★上一版的滚动条为什么看起来不像滚动条 ──
 * 上一版写的是：
 * ```kotlin
 * Modifier
 *     .verticalScroll(state)
 *     .drawWithContent { ... }     // ← 放在 verticalScroll 之后
 * ```
 * 我当时的注释还写着「放在 verticalScroll 之后，这样拿到的是视口尺寸」——**完全写反了**。
 *
 * Modifier 链里**越靠前的越外层**。`verticalScroll` 测量子节点时会把高度约束放宽到无限，
 * 所以它**内部**的 `drawWithContent` 拿到的 `size.height` 是**内容总高**而非视口高度。
 * 于是：
 * ```
 * visibleRatio = 内容总高 / (内容总高 + 可滚动量)     ← 这个比值没有物理意义
 * thumbHeight  = 内容总高 × visibleRatio             ← 算出来接近整个视口高
 * ```
 * 表现就是「滑块几乎和内容区一样高，看不出是滚动条」。
 *
 * ── 正确做法 ──
 * 把 `drawWithContent` 放在 `verticalScroll` **之前**（外层）。此时 `size` 是**视口**尺寸，
 * 再按「视口 / 内容总高」算滑块高度就是对的。
 *
 * 内容没超出时不画 —— 与 CSS 的 `overflow-y: auto` 语义一致。
 */
@Composable
fun TemplateScrollColumn(
    modifier: Modifier = Modifier,
    state: ScrollState = rememberScrollState(),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            // ★顺序关键：drawWithContent 在 verticalScroll **之前**（外层）→ size = 视口
            .drawScrollBar(state)
            .verticalScroll(state),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/**
 * 自绘滚动条（模板的 `::-webkit-scrollbar` 规格）。
 *
 * ```css
 * ::-webkit-scrollbar        { width: 6px }
 * ::-webkit-scrollbar-track  { background: rgba(255,255,255,0.1) }
 * ::-webkit-scrollbar-thumb  { background: rgba(255,255,255,0.3); border-radius: 3px }
 * ```
 *
 * 注意：此修饰符**必须放在 verticalScroll 外层**，否则 `size` 是内容总高（见 [TemplateScrollColumn]）。
 */
private fun Modifier.drawScrollBar(state: ScrollState): Modifier = drawWithContent {
    drawContent()

    val maxScroll = state.maxValue
    if (maxScroll <= 0) return@drawWithContent   // 内容没超出 → 不画

    val viewportHeight = size.height
    val barWidth = 6.dp.toPx()
    val corner = CornerRadius(3.dp.toPx())
    val left = size.width - barWidth

    // 轨道：rgba(255,255,255,0.1)
    drawRoundRect(
        color = Color.White.copy(alpha = 0.1f),
        topLeft = Offset(left, 0f),
        size = Size(barWidth, viewportHeight),
        cornerRadius = corner,
    )

    // 滑块高度 = 视口高 × (视口 / 内容总高)。
    // 内容总高 ≈ 视口高 + 可滚动量，所以直接用这个等价形式，不必真的去量内容。
    val contentHeight = viewportHeight + maxScroll
    val thumbHeight = (viewportHeight * viewportHeight / contentHeight)
        .coerceIn(12.dp.toPx(), viewportHeight)

    val scrollRatio = state.value.toFloat() / maxScroll
    val thumbTop = (viewportHeight - thumbHeight) * scrollRatio

    drawRoundRect(
        color = Color.White.copy(alpha = 0.3f),
        topLeft = Offset(left, thumbTop),
        size = Size(barWidth, thumbHeight),
        cornerRadius = corner,
    )
}

/**
 * 模板里的按钮。
 *
 * 模板规格：`.notification-btn { padding:8px 16px; border-radius:4px; background:#fff; color:#409eff }`
 * —— 圆角只有 **4px**。上一版用 `TextButton` 的默认形状（胶囊形），被用户指出「圆角搞那么大」。
 */
@Composable
fun TemplateDialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
) {
    val colors = NotificationTemplateColors.of(AppNotification.Type.NORMAL)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) Color.White else Color.White.copy(alpha = 0.2f),
            contentColor = if (primary) colors.buttonText else Color.White,
            disabledContainerColor = Color.White.copy(alpha = 0.3f),
            disabledContentColor = Color.White.copy(alpha = 0.6f),
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            fontWeight = if (primary) FontWeight.Bold else FontWeight.Medium,
            fontSize = 14.sp,
        )
    }
}
