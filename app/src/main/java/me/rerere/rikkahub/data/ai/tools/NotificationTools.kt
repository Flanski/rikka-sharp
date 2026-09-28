package me.rerere.rikkahub.data.ai.tools

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AI_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.AI_NOTIFICATION_URGENT_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.NotificationUtil
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "NotificationTools"

/**
 * 每分钟最多允许发送的通知条数。
 *
 * ── 为什么要有这个限制 ──
 * 通知是**会打断用户**的东西，而模型很难自己判断「已经发太多了」——
 * 如果没有上限，一个循环里的模型可能连发十几条把状态栏刷满，
 * 用户只能去关掉整个渠道（连带失去其它重要提醒）。
 *
 * 12 条/分钟 ≈ 每 5 秒一条，正常汇报用途完全够用；
 * 真的需要更高频率的场景（例如进度更新）应该用**同一个 tag 覆盖**同一条通知，
 * 这在下面 `send` 的实现里是支持的。
 */
private const val RATE_LIMIT_PER_MINUTE = 12

/** 限流窗口 */
private const val RATE_WINDOW_MS = 60_000L

private val sendTimestamps = ArrayDeque<Long>()
private val rateLock = Any()

/** 无 tag 时用于生成递增的通知 ID（避免互相覆盖） */
private val autoId = AtomicInteger(20_000)

/**
 * 工具 ID 生成：有 tag 时用 tag 的稳定哈希 ——
 * **同一个 tag 必然覆盖同一条通知**，这正是「更新进度」需要的语义。
 */
private fun notificationIdFor(tag: String?): Int =
    tag?.takeIf { it.isNotBlank() }?.hashCode()?.let { it and 0x7FFFFFFF } ?: autoId.incrementAndGet()

/**
 * 限流检查。返回剩余可发送条数；为 0 表示已超限。
 *
 * 用时间戳队列而非计数器：窗口滑动后自动释放额度，避免「每分钟整点清零」那种突刺。
 */
private fun checkRateLimit(): Int = synchronized(rateLock) {
    val now = System.currentTimeMillis()
    while (sendTimestamps.isNotEmpty() && now - sendTimestamps.first() > RATE_WINDOW_MS) {
        sendTimestamps.removeFirst()
    }
    val remaining = RATE_LIMIT_PER_MINUTE - sendTimestamps.size
    if (remaining > 0) sendTimestamps.addLast(now)
    remaining
}

/** 把可能很长的文本裁短，避免超过系统的通知文本上限（过大时会静默截断且难排查） */
private fun trim(text: String, max: Int = 1200): String =
    if (text.length <= max) text else text.take(max) + "…"

/**
 * 通知类工具。
 *
 * 让 AI 能在**不把结果塞进聊天正文**的情况下告知用户（例如长任务完成、需要留意的事）。
 * 支持 Android 通知的主要能力：大文本/列表样式、进度条、常驻、点击打开链接、
 * 自动消失、分组、重要性（是否响铃）。
 *
 * 与「工具授权通知」「工具活动通知」是不同用途：
 * 那两个是**系统**发出的过程通知；本工具是 **AI 主动汇报**，因此单独一个渠道，
 * 用户可以只关它而不影响其它提醒。
 */
fun createNotificationTools(context: Context): List<Tool> = listOf(
    sendNotificationTool(context),
    cancelNotificationTool(context),
)

private fun sendNotificationTool(context: Context): Tool = Tool(
    name = "send_notification",
    description = """
        Send a system notification to the user's device.

        WHEN TO USE: to report a result or an event when the user may not be looking at the chat —
        e.g. a long task has finished, a background download completed, or something needs attention.
        Keep the text brief; this is a notification, not a report.

        WHEN NOT TO USE: do NOT use it to repeat what you are already saying in the chat,
        and do not send one notification per trivial step. If you need to show progress,
        send repeatedly with the **same `tag`** — that updates the existing notification in place
        instead of stacking new ones. Sending is rate-limited (see below) precisely to prevent spam.

        `urgent=true` additionally rings/vibrates and uses a separate high-importance channel;
        reserve it for things the user should look at soon. Everything else leaves `urgent` false.

        Returns the notification id and tag (pass the tag to `cancel_notification` later),
        or an explanation of why nothing was shown (e.g. notification permission not granted).
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Notification title. Short — a few words.")
                })
                put("message", buildJsonObject {
                    put("type", "string")
                    put("description", "Notification body. One or two sentences at most.")
                })
                put("urgent", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Ring and vibrate, and use the high-importance channel. Default false. Use sparingly.")
                })
                put("style", buildJsonObject {
                    put("type", "string")
                    put("description", "basic (default) | bigtext (long text, expanded) | inbox (use `lines`).")
                })
                put("lines", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "Used when style=inbox. Up to 7 lines are shown.")
                })
                put("progress", buildJsonObject {
                    put("type", "integer")
                    put("description", "0-100 to show a progress bar. Omit for no bar.")
                })
                put("ongoing", buildJsonObject {
                    put("type", "boolean")
                    put("description", "true = cannot be swiped away (use for in-progress work, and cancel it later).")
                })
                put("auto_cancel", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Dismiss when the user taps it. Default true.")
                })
                put("timeout_seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "Auto-dismiss after this many seconds. Useful so reminders do not pile up.")
                })
                put("tag", buildJsonObject {
                    put("type", "string")
                    put("description", "Stable key. Sending again with the same tag UPDATES the same notification.")
                })
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "Open this URL when the user taps the notification.")
                })
            },
            required = listOf("title", "message"),
        )
    },
    // 低风险：发通知不改动数据、不泄露隐私。默认不要求授权，但**有频率上限**防止刷屏。
    needsApproval = { false },
    execute = { input ->
        guarded("send_notification") {
            val obj = input as? kotlinx.serialization.json.JsonObject ?: kotlinx.serialization.json.JsonObject(emptyMap())
            val title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            val message = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            val urgent = obj["urgent"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val style = obj["style"]?.jsonPrimitive?.contentOrNull?.lowercase()
            val lines = obj["lines"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            val progress = obj["progress"]?.jsonPrimitive?.longOrNull?.toInt()
            val ongoing = obj["ongoing"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val autoCancel = obj["auto_cancel"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
            val timeoutSec = obj["timeout_seconds"]?.jsonPrimitive?.longOrNull?.toInt()
            val tag = obj["tag"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val url = obj["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

            buildJsonObject {
                if (title.isEmpty() || message.isEmpty()) {
                    put("ok", JsonPrimitive(false))
                    put("error", JsonPrimitive("title and message are required and must not be blank"))
                    return@buildJsonObject
                }
                if (!NotificationUtil.hasNotificationPermission(context)) {
                    // 工具内不能弹权限对话框（没有 Activity），因此把情况说清楚让模型转告用户
                    put("ok", JsonPrimitive(false))
                    put("error", JsonPrimitive("notification_permission_not_granted"))
                    put("hint", JsonPrimitive(
                        "The app lacks the notification permission, so nothing was shown. " +
                            "Ask the user to grant notifications for this app (Android 13+)."
                    ))
                    return@buildJsonObject
                }

                val remaining = checkRateLimit()
                if (remaining <= 0) {
                    put("ok", JsonPrimitive(false))
                    put("error", JsonPrimitive("rate_limited"))
                    put("hint", JsonPrimitive(
                        "Too many notifications in the last minute (limit $RATE_LIMIT_PER_MINUTE). " +
                            "Reuse a single `tag` to update one notification instead of sending new ones."
                    ))
                    return@buildJsonObject
                }

                val notificationId = notificationIdFor(tag)
                val idForIntent = notificationId
                val channel = if (urgent) AI_NOTIFICATION_URGENT_CHANNEL_ID else AI_NOTIFICATION_CHANNEL_ID

                // 点击行为：有 url 就打开它，否则打开应用
                val contentIntent = runCatching {
                    val launchIntent = if (url != null) {
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    } else {
                        context.packageManager.getLaunchIntentForPackage(context.packageName)
                    }
                    launchIntent?.let {
                        if (url == null) it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        PendingIntent.getActivity(
                            context,
                            idForIntent,
                            it,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        )
                    }
                }.getOrNull()

                val delivered = NotificationUtil.notify(context, channel, notificationId) {
                    this.title = title
                    this.content = trim(message)
                    this.autoCancel = autoCancel
                    this.ongoing = ongoing
                    this.onlyAlertOnce = true
                    this.enableVibration = urgent
                    this.priority = if (urgent) {
                        android.app.Notification.PRIORITY_HIGH
                    } else {
                        android.app.Notification.PRIORITY_DEFAULT
                    }
                    this.contentIntent = contentIntent
                    this.smallIcon = R.drawable.small_icon
                    this.useBigTextStyle = style == "bigtext"
                    this.style = style
                    this.inboxLines = lines
                    this.progress = progress?.coerceIn(0, 100)
                    this.groupKey = tag?.let { "ai_$it" }
                    this.timeoutAfterMs = timeoutSec?.let { it * 1000L }
                }

                put("ok", JsonPrimitive(delivered))
                put("notification_id", JsonPrimitive(notificationId))
                tag?.let { put("tag", JsonPrimitive(it)) }
                put("channel", JsonPrimitive(if (urgent) "urgent" else "normal"))
                put("remaining_this_minute", JsonPrimitive(remaining - 1))
                if (!delivered) {
                    put("note", JsonPrimitive("The system rejected the notification."))
                }
            }
        }
    },
)

private fun cancelNotificationTool(context: Context): Tool = Tool(
    name = "cancel_notification",
    description = """
        Cancel a notification previously sent by send_notification.

        Pass the same `tag` you used when sending (preferred — it maps to the same notification),
        or the numeric `notification_id` if you kept it.

        Use action="cancel_all" to clear every notification this app posted.
        Typical use: a task has finished, so its ongoing/progress notification is no longer meaningful.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "cancel (default) | cancel_all")
                })
                put("tag", buildJsonObject {
                    put("type", "string")
                    put("description", "Tag used when sending.")
                })
                put("notification_id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Id returned by send_notification.")
                })
            },
        )
    },
    needsApproval = { false },
    execute = { input ->
        guarded("cancel_notification") {
            val obj = input as? kotlinx.serialization.json.JsonObject ?: kotlinx.serialization.json.JsonObject(emptyMap())
            val action = obj["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "cancel"
            val tag = obj["tag"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val id = obj["notification_id"]?.jsonPrimitive?.longOrNull?.toInt()

            buildJsonObject {
                when {
                    action == "cancel_all" -> {
                        NotificationUtil.cancelAll(context)
                        put("ok", JsonPrimitive(true))
                        put("cancelled", JsonPrimitive("all"))
                    }
                    tag != null || id != null -> {
                        val target = id ?: notificationIdFor(tag)
                        NotificationUtil.cancel(context, target)
                        put("ok", JsonPrimitive(true))
                        put("cancelled", JsonPrimitive(target))
                    }
                    else -> {
                        put("ok", JsonPrimitive(false))
                        put("error", JsonPrimitive("provide either tag or notification_id (or action=cancel_all)"))
                    }
                }
            }
        }
    },
)
