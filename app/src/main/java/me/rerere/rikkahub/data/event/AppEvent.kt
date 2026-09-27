package me.rerere.rikkahub.data.event

import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

/**
 * 应用内事件（由 [AppEventBus] 分发）。
 *
 * ★新增子类时**必须**检查所有对 AppEvent 的 `when`：
 *   · `RouteActivity.AppRoutes()` 里的那个 `when (event)` 是**穷尽且无 else** 的，
 *     漏加分支会直接导致编译失败（`'when' expression must be exhaustive`）。
 *   · `ChatNotificationManager` 的那个有 `else -> {}`，不受影响但按惯例也应补分支。
 *
 * 当前消费方：RouteActivity（UI 类事件）、ChatNotificationManager（通知类事件）、
 * McpOAuthCoordinator（OAuth 回调）。
 */
sealed class AppEvent {
    data class Speak(val text: String) : AppEvent()
    data object OpenUsageAccessSettings : AppEvent()

    /** MCP OAuth 授权完成后经 deep link 回传的结果。 */
    data class McpOAuthCallback(
        val state: String?,
        val code: String?,
        val error: String?,
    ) : AppEvent()

    /** 聊天生成过程中的流式更新，由 ChatNotificationManager 消费用于 Live Update 通知。 */
    data class ChatGenerationUpdate(
        val conversationId: Uuid,
        val lastMessage: UIMessage,
        val senderName: String,
    ) : AppEvent()

    /**
     * 有工具在执行前需要用户批准。
     *
     * 发出后由 `ChatNotificationManager` 转成带「同意 / 拒绝」按钮的通知：
     * 模型此时**已暂停**（`GenerationHandler` 检测到 Pending 就 break），
     * 等用户在通知或应用内做出选择后才会继续。
     */
    data class ToolApprovalRequested(
        val conversationId: Uuid,
        val senderName: String,
        val requests: List<ToolApprovalRequest>,
    ) : AppEvent()

    /**
     * 聊天生成结束（完成、失败或取消）。
     * [contentPreview] 为 null 时仅取消 Live Update 通知，不发送完成通知。
     */
    data class ChatGenerationEnded(
        val conversationId: Uuid,
        val senderName: String,
        val contentPreview: String?,
    ) : AppEvent()
}

/**
 * 一条待批准的工具调用（用于通知展示）。
 *
 * [inputJson] 是工具原始参数，展示时才做摘要 —— 事件本身保持「不加工」，
 * 这样摘要策略变化不需要改发送方。
 */
data class ToolApprovalRequest(
    val toolCallId: String,
    val toolName: String,
    val inputJson: String,
)
