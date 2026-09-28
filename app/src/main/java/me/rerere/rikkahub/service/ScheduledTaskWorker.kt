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
 * ── 职责刻意保持最小 ──
 * 只做两件事：**标记任务已触发** + **发一条通知**。
 *
 * 不做「自动让 AI 做事」，原因是手机上的后台执行不可靠（尤其华为/鸿蒙）：
 *  - 应用可能已被系统冻结或杀死；
 *  - 即使 Worker 被唤醒，也没有正在进行的生成可以「插入」内容；
 *  - 想强行拉起一次生成，会牵扯前台服务、通知权限、后台启动限制等一系列问题，
 *    并且在被限制的机型上**失败得毫无声息**。
 *
 * 因此这里只保证「用户一定会收到提醒」，把「AI 知晓」交给下一次生成的注入
 * （见 [ScheduledTaskStore.takePendingForAi]）。这样即使通知之外的环节全部失败，
 * 这个功能仍然有用。
 *
 * ── 为什么不用 CoroutineWorker ──
 * 这里没有挂起操作（读写一个小 JSON 文件 + 发通知都是同步的），
 * 用 [Worker] 更直接，也少一层调度开销。
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

        // 2. 发通知
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
            this.content = message.ifBlank { "（无内容）" }
            this.autoCancel = true
            this.useBigTextStyle = message.length > 60
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

        // 永远返回 success：
        // retry 会让 WorkManager 重跑，导致同一个提醒被重复发出；
        // 而 failure 会丢记录。这里没有「值得重试」的失败模式 ——
        // 存储问题与权限问题重试也不会变好。
        return Result.success()
    }
}
