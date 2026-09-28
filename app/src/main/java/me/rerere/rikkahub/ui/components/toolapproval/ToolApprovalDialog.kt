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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
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
        TextButton(onClick = onDeny) {
            Text(
                text = stringResource(R.string.tool_approval_deny),
                color = ApprovalColors.onContainer,
                fontWeight = FontWeight.Medium,
            )
        }
        TextButton(onClick = onApprove) {
            Text(
                text = stringResource(R.string.tool_approval_allow),
                color = ApprovalColors.header,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * 弹窗配色。
 *
 * 与模板的差异：模板整体是蓝色底（`#409eff`），但**授权**的语义是「先停一下、请你确认」，
 * 用蓝色会显得像普通通知而被忽略。这里改为「深底 + 橙色强调」，保留模板的结构与动画，
 * 只调整语义色 —— 醒目是这次改造的目的。
 */
private object ApprovalColors {
    val header = Color(0xFFE6A23C)        // 模板的 warning 色
    val container = Color(0xFF2B2F36)     // 深色容器，保证白字可读
    val onHeader = Color(0xFF1A1A1A)
    val onContainer = Color(0xFFFFFFFF)
}
