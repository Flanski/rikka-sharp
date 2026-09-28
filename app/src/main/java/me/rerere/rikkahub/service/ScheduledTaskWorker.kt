package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import me.rerere.rikkahub.AI_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.schedule.ScheduledTaskStore
import me.rerere.rikkahub.utils.NotificationUtil

/**
 * 定时任务到期时执行的 Worker。
 *
 * ── 做三件事（按重要性递减）──
 *  1. **尝试在目标对话里触发一次生成** —— 这是定时任务的主要用途：
 *     让 AI 自动继续（回复消息、必要时调用工具）。
 *     实现方式是通过 Koin 取 [ChatService] 并调用 `sendMessage(..., answer = true)`，
 *     它在**同一个 job 内**先插入消息再触发生成，因此不存在「消息还没写完就生成」的竞态。
 *  2. **标记任务已触发** —— 这决定下一次生成时要不要把 prompt 注入给 AI（兜底路径）。
 *  3. **发一条通知** —— 保证用户至少能看到。
 *
 * ── 为什么第 1 步之后仍然保留「下次生成时注入」──
 * 第 1 步**可能失败**，而且在某些机型上会静默失败：
 *  · 应用已被系统冻结或杀死；
 *  · Android 12+ 限制后台启动前台服务（`ForegroundServiceStartNotAllowedException`），
 *    生成会因无法保活而在中途被系统掐掉；
 *  · 华为/鸿蒙的后台管理更激进。
 * 所以通知与「待注入」两条兜底路径必须留着 —— 即使触发彻底失败，
 * 用户仍会收到提醒、AI 下次也会知道这件事。
 *
 * ── 为什么不用 CoroutineWorker ──
 * 触发生成本身是「发出去就不管」的（[ChatService.sendMessage] 内部自己 launch），
 * 这里没有需要 await 的挂起操作，用 [Worker] 更直接。
 * 生成的实际存活由 ChatService 自己的前台服务负责，不由 Worker 负责。
 */
class ScheduledTaskWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    companion object {
        const val TAG = "scheduled_task"
        const val KEY_TASK_ID = "task_id"
        const val KEY_TITLE = "title"
        const val KEY_MESSAGE = "message"

        /** 通知 ID 的命名空间，避免与其它通知（工具授权、生成完成等）撞 ID */
        private const val NOTIFICATION_ID_BASE = 40_000

        /**
         * Worker 返回前滞留多久（毫秒），留给生成启动前台服务的时间。
         * 见 doWork 末尾的说明 —— 太短会让触发变得不稳定，太长则拖延 WorkManager。
         */
        private const val TRIGGER_GRACE_MS = 3_000L
    }

    override fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID)
        val title = inputData.getString(KEY_TITLE).orEmpty().ifBlank { "定时提醒" }
        val message = inputData.getString(KEY_MESSAGE).orEmpty()

        Log.i(TAG, "触发 id=$taskId title=$title")

        // 1. 标记已触发 —— 这决定了下一次生成时会不会把它注入给 AI。
        //    即使标记失败（例如存储被清理），也继续发通知：通知是底线保证。
        val marked = taskId?.let {
            runCatching { ScheduledTaskStore.markFired(applicationContext, it) }
                .onFailure { e -> Log.w(TAG, "标记已触发失败 id=$it", e) }
                .getOrNull()
        }
        if (marked == null) {
            // 两种可能：存储里已没有这条（被用户删了），或标记失败。
            // 前者属正常情况（用户取消任务后 Worker 仍可能已排队），不当作错误。
            Log.i(TAG, "存储中未找到或未能标记 id=$taskId（若用户已取消则属正常）")
        }

        // 2. 尝试触发 AI 生成（定时任务的主要用途）
        val triggered = tryTriggerGeneration(marked)

        // 3. 发通知 —— 内容随触发结果变化，让用户知道 AI 到底有没有被唤醒
        val notificationId = NOTIFICATION_ID_BASE + (taskId?.hashCode()?.let { it and 0xFFFF } ?: 0)

        val contentIntent = runCatching {
            applicationContext.packageManager
                .getLaunchIntentForPackage(applicationContext.packageName)
                ?.let { intent ->
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    PendingIntent.getActivity(
                        applicationContext,
                        notificationId,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                }
        }.getOrNull()

        val delivered = NotificationUtil.notify(
            applicationContext,
            AI_NOTIFICATION_CHANNEL_ID,
            notificationId,
        ) {
            this.title = title
            // 若无法触发 AI（没绑定对话 / 触发失败），在通知里说明，
            // 避免用户以为「AI 已经在处理了」而一直等
            this.content = buildString {
                append(message.ifBlank { "（无内容）" })
                if (!triggered) append("\n（已记录，将在下次打开应用时告知 AI）")
            }
            this.autoCancel = true
            this.useBigTextStyle = this.content.length > 60
            this.contentIntent = contentIntent
            this.smallIcon = R.drawable.small_icon
            // 定时提醒是用户自己安排的，值得响一下；渠道本身是 DEFAULT importance，
            // 因此这里只是让它在允许的范围内尽量明显
            this.enableVibration = true
            this.priority = android.app.Notification.PRIORITY_DEFAULT
        }

        if (!delivered) {
            // 常见原因：通知权限被拒。此时任务仍已被标记为「待注入」，
            // 所以用户回到应用后 AI 仍会看到它 —— 不算彻底失败。
            Log.w(TAG, "通知未能发出 id=$taskId（可能是通知权限未授予）")
        }

        // ── 短暂滞留，让生成真正启动起来 ──
        //
        // 为什么需要：Worker 一旦返回，WorkManager 就不再「替我们撑住」这个进程了。
        // 而生成要经过几道初始化（取助手配置、准备消息、启动前台服务）才会进入受保护状态。
        // 若 Worker 立刻返回，系统可能在生成站稳之前就回收进程 —— 表现是「有时能触发、有时不能」。
        //
        // 3 秒是折中：足够让 ChatService 把前台服务拉起来（之后由服务保活，不再依赖 Worker），
        // 又不会明显拖延 WorkManager。这段阻塞发生在 WorkManager 的后台线程上，不影响 UI。
        runCatching { kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(TRIGGER_GRACE_MS) } }

        // 永远返回 success：
        // retry 会让 WorkManager 重跑，导致同一个提醒被重复发出；
        // 而 failure 会丢记录。这里没有「值得重试」的失败模式 ——
        // 存储问题与权限问题重试也不会变好。
        return Result.success()
    }

    /**
     * 在目标对话里触发一次生成。
     *
     * @return true = 已把生成请求交出去（不代表一定会跑完，后台保活由 ChatService 负责）
     *
     * ── 为什么用 Koin 的全局上下文取 ChatService ──
     * Worker 是独立的进程组件，没有 Activity/Fragment 的注入环境。
     * 项目里已有同样做法的先例（`WorkspaceDocumentsProvider` 用 `GlobalContext.get().get()`）。
     *
     * ★这里**必须全程容错**：Worker 里抛异常会让 WorkManager 记一次失败，
     * 而这个任务本身已经「触发过」了（markFired），重试只会重复打扰用户。
     */
    private fun tryTriggerGeneration(task: me.rerere.rikkahub.data.schedule.ScheduledTask?): Boolean {
        val conversationIdRaw = task?.conversationId
        if (conversationIdRaw.isNullOrBlank()) {
            // 没绑定对话（旧数据，或排期时拿不到）→ 只能走通知 + 下次注入
            Log.i(TAG, "任务未绑定对话，跳过触发 id=${task?.id}")
            return false
        }

        // 项目用的是 kotlin.uuid.Uuid（已有全局 optIn），**不是** java.util.UUID ——
        // 两者的类型不兼容，用错会在编译期报 "actual type is 'UUID', but 'Uuid' was expected"。
        val uuid = runCatching { kotlin.uuid.Uuid.parse(conversationIdRaw) }.getOrNull()
        if (uuid == null) {
            Log.w(TAG, "conversationId 格式非法: $conversationIdRaw")
            return false
        }

        return runCatching {
            val chatService = org.koin.core.context.GlobalContext.get()
                .get<me.rerere.rikkahub.service.ChatService>()

            // sendMessage 内部：先把消息写进对话，再在同一个 job 里触发生成。
            // 用 answer=true 才会真的让模型回复（这正是「定时任务」与「定时通知」的区别）。
            //
            // 消息文本带上来源标记，让模型能分辨这是系统按计划触发的、而不是用户随手发的。
            chatService.sendMessage(
                conversationId = uuid,
                content = listOf(
                    me.rerere.ai.ui.UIMessagePart.Text(
                        "（定时提醒）" + (task.prompt.ifBlank { task.title })
                    )
                ),
                answer = true,
            )
            Log.i(TAG, "已触发对话生成 id=$conversationIdRaw")
            true
        }.onFailure {
            // 常见原因：Koin 尚未初始化、对话已被删除、后台限制导致无法启动前台服务
            Log.w(TAG, "触发对话生成失败 id=$conversationIdRaw（将走通知 + 下次注入兜底）", it)
        }.getOrDefault(false)
    }
}
