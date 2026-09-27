package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.cancelNotification
import org.koin.java.KoinJavaComponent
import kotlin.uuid.Uuid

private const val TAG = "ToolApprovalReceiver"

/**
 * 处理通知上「同意 / 拒绝」按钮的点击。
 *
 * ── 为什么需要它 ──
 * 工具授权时模型会**暂停等待**用户决定（见 `GenerationHandler` 的 Pending 分支）。
 * 如果用户当时不在应用内，只靠消息气泡里的按钮就无从下手 ——
 * 于是通过通知提供入口，让用户不打开应用也能做出决定。
 *
 * ── 与 UI 的关系 ──
 * 两条路径最终都汇到同一个地方：[ChatService.handleToolApproval]。
 * 区别只是调用方（Compose 按钮 vs 本 Receiver），行为完全一致。
 *
 * ── 生命周期 ──
 * `handleToolApproval` 内部用 `appScope.launch` 异步执行并立即返回，
 * 因此无需 `goAsync()` —— onReceive 返回后工作仍在继续进行。
 */
class ToolApprovalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TOOL_APPROVAL) return

        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
        val toolCallId = intent.getStringExtra(EXTRA_TOOL_CALL_ID)
        val approved = intent.getBooleanExtra(EXTRA_APPROVED, false)
        if (conversationId.isNullOrBlank() || toolCallId.isNullOrBlank()) {
            Log.w(TAG, "缺少必要参数，忽略")
            return
        }

        runCatching {
            // ChatService 在 AppModule 里注册为 single，可直接取到同一个实例
            // 与项目其它处一致地用 KoinJavaComponent.get<具体类型>(clazz)
            val chatService = KoinJavaComponent.get<ChatService>(ChatService::class.java)
            chatService.handleToolApproval(
                conversationId = Uuid.parse(conversationId),
                toolCallId = toolCallId,
                approved = approved,
                reason = if (approved) "" else context.getString(R.string.notification_tool_approval_denied_reason),
            )
        }.onFailure {
            Log.e(TAG, "处理工具授权失败", it)
        }

        // 无论成功与否都移除通知 —— 用户已经做出了选择，留着会误导
        context.cancelNotification(notificationId(toolCallId))
    }

    companion object {
        const val ACTION_TOOL_APPROVAL = "me.rerere.rikkahub.action.TOOL_APPROVAL"
        const val EXTRA_CONVERSATION_ID = "conversationId"
        const val EXTRA_TOOL_CALL_ID = "toolCallId"
        const val EXTRA_APPROVED = "approved"

        /**
         * 每个工具调用一个通知。
         *
         * 之所以不按会话合并成一个通知：多个待批准工具时，用户点一次「同意」
         * 无法表达「同意哪一个」，语义会含糊。逐个通知可以让决定精确到具体调用。
         */
        fun notificationId(toolCallId: String): Int = toolCallId.hashCode()

        /** 构造「同意 / 拒绝」按钮的 PendingIntent */
        fun approvalPendingIntent(
            context: Context,
            conversationId: Uuid,
            toolCallId: String,
            approved: Boolean,
        ): PendingIntent {
            val intent = Intent(context, ToolApprovalReceiver::class.java).apply {
                action = ACTION_TOOL_APPROVAL
                putExtra(EXTRA_CONVERSATION_ID, conversationId.toString())
                putExtra(EXTRA_TOOL_CALL_ID, toolCallId)
                putExtra(EXTRA_APPROVED, approved)
            }
            return PendingIntent.getBroadcast(
                context,
                // requestCode 必须区分「同意」与「拒绝」，否则后者会覆盖前者的 extras
                (toolCallId + if (approved) "|y" else "|n").hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
