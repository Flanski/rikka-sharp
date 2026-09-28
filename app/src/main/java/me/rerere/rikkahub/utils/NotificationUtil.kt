package me.rerere.rikkahub.utils

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.rerere.rikkahub.R

/**
 * 通知上的一个操作按钮（`addAction`）。
 *
 * 用途：让用户**不打开应用**就能对通知做出决定 —— 例如工具授权的「同意 / 拒绝」。
 * 点击后由 [intent]（通常是 BroadcastReceiver 的 PendingIntent）处理。
 */
data class NotificationAction(
    /** 按钮图标。系统只取 alpha 通道，故用现成的单色小图标即可 */
    val icon: Int,
    /** 按钮文字 */
    val title: String,
    val intent: PendingIntent,
)

/**
 * 通知构建器的配置 DSL
 */
class NotificationConfig {
    var title: String = ""
    var content: String = ""
    var subText: String? = null
    var smallIcon: Int = R.drawable.small_icon
    var autoCancel: Boolean = false
    var ongoing: Boolean = false
    var onlyAlertOnce: Boolean = false
    var category: String? = null
    var visibility: Int = NotificationCompat.VISIBILITY_PRIVATE
    var contentIntent: PendingIntent? = null
    var useBigTextStyle: Boolean = false

    // Live Update 相关
    var requestPromotedOngoing: Boolean = false
    var shortCriticalText: String? = null

    // 默认通知效果
    var useDefaults: Boolean = false

    /** 操作按钮（系统最多显示 3 个，超出部分不显示） */
    var actions: List<NotificationAction> = emptyList()

    // ── 以下为「AI 通知工具」需要的扩展项 ──

    /**
     * 多行列表（InboxStyle）。
     *
     * 适用「一次汇报好几件事」：比在正文里堆换行更清晰，
     * 系统会把每行独立显示。
     */
    var inboxLines: List<String> = emptyList()

    /** 大文本样式；与 [useBigTextStyle] 等价，但用于让 AI 明确选择 */
    var style: String? = null

    /** 进度条：0..100 显示确定进度；null 不显示 */
    var progress: Int? = null

    /** 进度不确定（转圈），仅当 [progress] 为 null 且此项为 true 时生效 */
    var indeterminate: Boolean = false

    /**
     * 过多久自动消失（毫秒）。
     *
     * AI 汇报类通知若一直堆着会变成垃圾，设置超时可让它自己清理掉。
     */
    var timeoutAfterMs: Long? = null

    /** 分组键：同组通知在系统里会折叠在一起 */
    var groupKey: String? = null

    /** 是否响铃/震动/亮屏（需配合渠道的 importance 才有实际效果） */
    var enableVibration: Boolean = false

    /** 优先级（仅 Android 7.1 及以下有效；更高版本由渠道 importance 决定） */
    var priority: Int = NotificationCompat.PRIORITY_DEFAULT
}

object NotificationUtil {

    /**
     * 检查是否有通知权限
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 使用 DSL 风格创建并发送通知
     *
     * @param context 上下文
     * @param channelId 通知渠道 ID
     * @param notificationId 通知 ID
     * @param config 通知配置 lambda
     * @return 是否成功发送
     */
    @SuppressLint("MissingPermission")
    fun notify(
        context: Context,
        channelId: String,
        notificationId: Int,
        config: NotificationConfig.() -> Unit
    ): Boolean {
        if (!hasNotificationPermission(context)) {
            return false
        }

        val notificationConfig = NotificationConfig().apply(config)
        val notification = buildNotification(context, channelId, notificationConfig)

        NotificationManagerCompat.from(context).notify(notificationId, notification.build())
        return true
    }

    /**
     * 构建通知
     */
    fun buildNotification(
        context: Context,
        channelId: String,
        config: NotificationConfig
    ): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, channelId).apply {
            setContentTitle(config.title)
            setContentText(config.content)
            setSmallIcon(config.smallIcon)
            setAutoCancel(config.autoCancel)
            setOngoing(config.ongoing)
            setOnlyAlertOnce(config.onlyAlertOnce)
            setVisibility(config.visibility)

            config.subText?.let { setSubText(it) }
            config.category?.let { setCategory(it) }
            config.contentIntent?.let { setContentIntent(it) }

            if (config.useBigTextStyle) {
                setStyle(NotificationCompat.BigTextStyle().bigText(config.content))
            }

            // ── 样式：显式指定时优先于 useBigTextStyle ──
            when (config.style) {
                "bigtext" -> setStyle(NotificationCompat.BigTextStyle().bigText(config.content))
                "inbox" -> {
                    val st = NotificationCompat.InboxStyle()
                    config.inboxLines.take(7).forEach { st.addLine(it) }
                    // 超过 7 行时给个汇总，避免系统静默截断让人以为只有这些
                    if (config.inboxLines.size > 7) st.setSummaryText("+${config.inboxLines.size - 7}")
                    setStyle(st)
                }
                else -> Unit
            }

            // ── 进度 ──
            //
            // 写成语句而不是 `?: if (...)` 表达式 —— 后者要求 `if` 有 else 分支
            // （`?:` 的右操作数必须是一个值），编译会报
            // "'if' must have both main and 'else' branches when used as an expression"。
            val progressValue = config.progress
            if (progressValue != null) {
                setProgress(100, progressValue.coerceIn(0, 100), false)
            } else if (config.indeterminate) {
                setProgress(0, 0, true)
            }

            // ── 自动消失：AI 汇报类通知不该永久堆积 ──
            config.timeoutAfterMs?.let { setTimeoutAfter(it.coerceAtLeast(1000L)) }

            config.groupKey?.let { setGroup(it) }

            if (config.enableVibration) {
                setVibrate(longArrayOf(0, 200, 120, 200))
            }

            setPriority(config.priority)

            if (config.useDefaults) {
                setDefaults(NotificationCompat.DEFAULT_ALL)
            }

            // 操作按钮 —— 让用户直接在通知上做决定（如工具授权的同意/拒绝）
            config.actions.forEach { action ->
                addAction(action.icon, action.title, action.intent)
            }

            // Android 15+ Live Update 支持
            if (config.requestPromotedOngoing && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                setRequestPromotedOngoing(true)
            }

            // Android 16+ 状态栏 chip 文本
            if (config.shortCriticalText != null && Build.VERSION.SDK_INT >= 36) {
                setShortCriticalText(config.shortCriticalText!!)
            }
        }
    }

    /**
     * 取消通知
     */
    fun cancel(context: Context, notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    /**
     * 取消所有通知
     */
    fun cancelAll(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }
}

/**
 * Context 扩展函数，简化通知发送
 */
fun Context.sendNotification(
    channelId: String,
    notificationId: Int,
    config: NotificationConfig.() -> Unit
): Boolean = NotificationUtil.notify(this, channelId, notificationId, config)

/**
 * Context 扩展函数，取消通知
 */
fun Context.cancelNotification(notificationId: Int) {
    NotificationUtil.cancel(this, notificationId)
}
