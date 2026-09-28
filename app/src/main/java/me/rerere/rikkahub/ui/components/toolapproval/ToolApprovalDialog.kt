package me.rerere.rikkahub.ui.components.toolapproval

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.rikkahub.R

/**
 * 工具授权弹窗。
 *
 * ── 为什么不用原来的「消息内联按钮」──
 * 原实现是在聊天消息里挂两个小图标按钮，**用户很容易没注意到**（消息在滚动区里、
 * 且需要主动查看那条消息）。而授权是「AI 要动你的设备」这件事的确认环节，
 * 属于**必须被看见**的交互。改为一屏遮罩 + 三分之一屏的居中弹窗，配合展开动画。
 *
 * ── 视觉来源 ──
 * 复刻 /workspace/通知弹窗特效Demo.html：
 *  · 遮罩        rgba(0,0,0,0.5)，随弹窗同步淡入淡出
 *  · 弹窗高度    屏幕的 1/3，垂直居中，以中心为轴做 scaleY 展开
 *  · 动画        250ms，cubic-bezier(0.4, 0, 0.2, 1) —— 即 Compose 的 [FastOutSlowInEasing]
 *  · 头部        图标 + 标题；背景色随类型变化（本弹窗固定为「需注意」的橙色警示风格）
 *  · 内容区      flex:1 + 可滚动 + 白字
 *  · 底部        按钮右对齐（次要按钮在左、主要按钮在右，与原模板的 .btn-retry 语义一致）
 *
 * ── 这里刻意**不可**轻易关闭 ──
 * 点击遮罩 / 返回键都不关闭：授权必须有明确结论（允许或拒绝），
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
) {
    // 展开动画：scaleY 0 → 1（以中心为轴）
    val scaleY = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        scaleY.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 250,
                easing = FastOutSlowInEasing, // = cubic-bezier(0.4, 0, 0.2, 1)
            ),
        )
    }

    Dialog(
        onDismissRequest = {
            // 故意忽略：授权必须给出明确结论，避免留下未决状态把生成卡住
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,   // 由我们自己控制宽度（移动端贴边、宽屏 480dp 居中）
            // ★让弹窗窗口延伸到系统栏区域。
            // 不加这一项时，Dialog 的窗口会避开状态栏/导航栏，于是那两条区域**不被遮罩覆盖** ——
            // 表现为「背景半透明遮罩不完全覆盖」（模板里是 `position: fixed; inset: 0`，全屏覆盖）。
            decorFitsSystemWindows = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val screenHeight = maxHeight
            val isWide = maxWidth >= 768.dp
            val widthModifier = if (isWide) Modifier.fillMaxWidth(0.8f) else Modifier.fillMaxWidth()

            // 遮罩
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f * scaleY.value))
            )

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = widthModifier
                        .height(screenHeight * 0.3333f)   // 屏幕 1/3，与模板一致
                        .graphicsLayer { this.scaleY = scaleY.value }
                        .clip(MaterialTheme.shapes.medium)
                        .background(ApprovalColors.container),
                ) {
                    ApprovalHeader()
                    ApprovalBody(
                        toolName = toolName,
                        intent = intent,
                        purpose = purpose,
                        argumentsPreview = argumentsPreview,
                        modifier = Modifier.weight(1f),
                    )
                    ApprovalFooter(onApprove = onApprove, onDeny = onDeny)
                }
            }
        }
    }
}

/** 头部：图标 + 标题 + 副标题（工具名）。背景色用警示橙，与「需要你确认」的语义对应 */
@Composable
private fun ApprovalHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ApprovalColors.header)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = HugeIcons.Alert01,
            contentDescription = null,
            tint = ApprovalColors.onHeader,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.tool_approval_title),
                color = ApprovalColors.onHeader,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.tool_approval_subtitle),
                color = ApprovalColors.onHeader.copy(alpha = 0.85f),
                fontSize = 12.sp,
            )
        }
    }
}

/** 内容区：intent / purpose / 参数 */
@Composable
private fun ApprovalBody(
    toolName: String,
    intent: String,
    purpose: String,
    argumentsPreview: String,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            // ── 自绘滚动条 ──
            //
            // 模板用的是 CSS 伪元素：
            //     ::-webkit-scrollbar        { width: 6px }
            //     ::-webkit-scrollbar-track  { background: rgba(255,255,255,0.1) }
            //     ::-webkit-scrollbar-thumb  { background: rgba(255,255,255,0.3); border-radius: 3px }
            // Compose 的 verticalScroll **不提供滚动条**，所以上一版视觉上「丢了滚动条」。
            // 这里用 drawWithContent 把同样规格的轨道与滑块画出来。
            //
            // 注意 Modifier 顺序：drawWithContent 放在 verticalScroll **之后**，
            // 这样拿到的是**视口**尺寸（而非内容总高），滚动条才能固定在右侧。
            .drawWithContent {
                drawContent()
                // 内容没超出时不画 —— 与 CSS 的 `overflow-y: auto` 语义一致
                val maxScroll = scrollState.maxValue
                if (maxScroll <= 0) return@drawWithContent

                val barWidth = 6.dp.toPx()
                val radius = CornerRadius(3.dp.toPx())
                val left = size.width - barWidth

                // 轨道：rgba(255,255,255,0.1)
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.1f),
                    topLeft = Offset(left, 0f),
                    size = Size(barWidth, size.height),
                    cornerRadius = radius,
                )

                // 滑块：rgba(255,255,255,0.3)
                // 高度按「视口 / 内容」比例，并给一个下限（否则内容极长时滑块细到看不见/点不中）
                val visibleRatio = size.height / (size.height + maxScroll).toFloat()
                val thumbHeight = (size.height * visibleRatio).coerceAtLeast(24.dp.toPx())
                val scrollRatio = if (maxScroll > 0) scrollState.value.toFloat() / maxScroll else 0f
                val thumbTop = (size.height - thumbHeight) * scrollRatio
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.3f),
                    topLeft = Offset(left, thumbTop),
                    size = Size(barWidth, thumbHeight),
                    cornerRadius = radius,
                )
            }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LabelledBlock(
            label = stringResource(R.string.tool_approval_action),
            value = intent.ifBlank { stringResource(R.string.tool_approval_not_provided) },
        )
        LabelledBlock(
            label = stringResource(R.string.tool_approval_reason),
            value = purpose.ifBlank { stringResource(R.string.tool_approval_not_provided) },
        )
        LabelledBlock(
            label = stringResource(R.string.tool_approval_tool),
            value = toolName,
            monospace = true,
        )
        if (argumentsPreview.isNotBlank()) {
            LabelledBlock(
                label = stringResource(R.string.tool_approval_arguments),
                value = argumentsPreview,
                monospace = true,
            )
        }
    }
}

@Composable
private fun LabelledBlock(label: String, value: String, monospace: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            color = ApprovalColors.onContainer.copy(alpha = 0.75f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = value,
            color = ApprovalColors.onContainer,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            overflow = TextOverflow.Clip,
        )
    }
}

/** 底部：右对齐。次要（拒绝）在左，主要（允许）在右 —— 与模板的 .btn-retry/.btn-confirm 顺序一致 */
@Composable
private fun ApprovalFooter(onApprove: () -> Unit, onDeny: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ApprovalColors.container)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 次要按钮：模板 .btn-retry（半透明白底 + 白字）
        TextButton(
            onClick = onDeny,
            colors = ButtonDefaults.textButtonColors(containerColor = ApprovalColors.secondaryButton),
        ) {
            Text(
                text = stringResource(R.string.tool_approval_deny),
                color = ApprovalColors.onContainer,
                fontWeight = FontWeight.Medium,
            )
        }
        // 主要按钮：模板 .notification-btn（白底 + 蓝字）
        TextButton(
            onClick = onApprove,
            colors = ButtonDefaults.textButtonColors(containerColor = Color.White),
        ) {
            Text(
                text = stringResource(R.string.tool_approval_allow),
                color = ApprovalColors.buttonText,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * 弹窗配色 —— **严格照搬模板**（用户明确要求「最大限度保留模板中的样式」）。
 *
 * 模板里的配色关系：
 *   .notification-modal { background: #409eff }            ← 整体蓝底
 *   .notification-body  { /* 无 background *\/ }             ← 内容区**透明**，显示的是容器的蓝
 *   .notification-header { background: 随类型变化 }
 *       type-normal → #409eff（蓝，配白字）
 *       type-warning → #e6a23c（橙，配黑字）
 *       type-error → #f56c6c（红，配白字）
 *   .notification-btn { background: #fff; color: #409eff }  ← 白底蓝字
 *   .btn-retry { background: rgba(255,255,255,0.2); color: #fff }
 *
 * ★我上一版自作主张改成「深底 + 橙头」，理由是「授权的语义应该更醒目」——
 * 那是错的：用户要的是移植模板、保留原样式，不是我重新设计一套配色。
 * 现在按模板还原：整体蓝底、内容区透明显蓝、橙色只用在 header（warning 类型）。
 */
private object ApprovalColors {
    /** 模板 .notification-modal 的 background */
    val container = Color(0xFF409EFF)

    /** 模板 .notification-modal.type-warning .notification-header 的背景 */
    val header = Color(0xFFE6A23C)

    /** warning header 用黑字（模板里 type-warning 的 color 是 #000） */
    val onHeader = Color(0xFF000000)

    /** 模板里 body / 按钮区的文字色为 #fff */
    val onContainer = Color(0xFFFFFFFF)

    /** 模板 .notification-btn 的白底蓝字 */
    val buttonText = Color(0xFF409EFF)

    /** 模板 .btn-retry（次要按钮）的半透明白底 */
    val secondaryButton = Color(0x33FFFFFF)
}
