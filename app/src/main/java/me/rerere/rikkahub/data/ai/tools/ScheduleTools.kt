package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.schedule.ScheduledTask
import me.rerere.rikkahub.data.schedule.ScheduledTaskScheduler
import me.rerere.rikkahub.data.schedule.ScheduledTaskStore
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "ScheduleTools"

/** 与用户约定的时间格式（也接受几种常见变体，见 [parseAbsoluteTime]） */
private val TIME_FORMATS = listOf(
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
)

/**
 * 解析绝对时间文本为 epoch 毫秒。
 *
 * 只处理**不带时区**的本地时间（用户和模型说的「明天 8 点」都是本地时间），
 * 若字符串里带时区（以 Z 或 +/- 结尾）则交给 [Instant.parse]。
 *
 * 解析失败返回 null 而不是抛异常 —— 调用方要把「时间格式看不懂」作为**可读的错误**返回给模型，
 * 让它可以换个格式重试，而不是让整轮生成因为一个参数格式问题崩掉。
 */
private fun parseAbsoluteTime(text: String): Long? {
    val t = text.trim()
    if (t.isEmpty()) return null

    // 带时区的 ISO-8601（如 2026-09-29T08:00:00Z）
    if (t.endsWith("Z") || Regex(".*[+-]\\d{2}:?\\d{2}$").matches(t)) {
        runCatching { return Instant.parse(t).toEpochMilli() }
        runCatching {
            return java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli()
        }
    }

    for (fmt in TIME_FORMATS) {
        runCatching {
            val ldt = LocalDateTime.parse(t, fmt)
            return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
    }
    return null
}

/** 把毫秒数说成人话，用于返回给模型的确认信息 */
private fun humanDelay(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "${s} 秒后"
        s < 3600 -> "${s / 60} 分钟后"
        s < 86400 -> "${s / 3600} 小时 ${(s % 3600) / 60} 分钟后"
        else -> "${s / 86400} 天 ${(s % 86400) / 3600} 小时后"
    }
}

private fun formatLocal(epochMs: Long): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

/**
 * 定时任务工具。
 *
 * ── 这个工具到底能做什么（必须向模型说清楚）──
 * 到期时**不一定**能让 AI 立刻做事（应用可能没在运行）。实际保证是：
 *  ① 一定发一条通知给用户；
 *  ② 用户下次回来时，[prompt] 会注入给 AI，让它「想起」这件事。
 *
 * 因此它适合「提醒」「待办」「过一会儿再继续」这类场景，
 * **不适合**「到点必须精确执行动作」（精度与后台执行都不保证）。
 * 描述里把这条讲明白，避免模型对能力有错误预期而做出不可靠的承诺。
 */
fun createScheduleTools(context: Context, conversationId: String? = null): List<Tool> = listOf(
    scheduleReminderTool(context, conversationId),
    listRemindersTool(context),
    cancelReminderTool(context),
)

private fun scheduleReminderTool(context: Context, conversationId: String?): Tool = Tool(
    name = "schedule_reminder",
    description = """
        Schedule a reminder for a later time.

        WHAT HAPPENS at that time:
          1. The system tries to START A GENERATION in the conversation this was scheduled from,
             using your `prompt` as the incoming message. You will then be asked to respond —
             and you may call tools during that response, exactly as in a normal turn.
          2. A notification is posted as well, so the user still sees it if the app cannot run
             in the background.
        CAVEAT: step 1 depends on the OS allowing the app to run at that moment. On phones with
        aggressive background limits (notably Huawei/HONOR) it may be delayed or skipped, and then
        only the notification happens. So do not promise the user exact timing, and do not promise
        that something will definitely be executed at time T.

        ACCURACY: not guaranteed to be exact. The scheduler may delay the notification by minutes
        (or longer in deep sleep / battery saver / vendor background restrictions).
        Do not use it for anything requiring second-level precision.

        GIVE EITHER `delay_seconds` (relative) OR `at_time` (absolute, local time).

        Keep `message` short and readable — it is what the USER sees in the notification.
        Use `prompt` for anything YOU will need later (it may be read without the current context).
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Notification title — a few words.")
                })
                put("message", buildJsonObject {
                    put("type", "string")
                    put("description", "Notification body shown to the user. One sentence is enough.")
                })
                put("delay_seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "Fire this many seconds from now. Use instead of at_time.")
                })
                put("at_time", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute local time, e.g. \"2026-09-29 08:00\". Use instead of delay_seconds.")
                })
                put("prompt", buildJsonObject {
                    put("type", "string")
                    put("description", "What you want to be reminded of when you next run. Defaults to title + message.")
                })
            },
            required = listOf("title", "message"),
        )
    },
    // 只是排一个提醒，不改动数据、不外发信息 —— 但**会主动打断用户**，
    // 所以放在需要感知的范畴里，由用户在「本地工具」里决定是否要逐次确认。
    // 这里默认不要求授权，避免每次排队都要点一次。
    needsApproval = { false },
    execute = { input ->
        guarded("schedule_reminder") {
            val obj = input as? JsonObject ?: JsonObject(emptyMap())
            val title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            val message = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            val delaySec = obj["delay_seconds"]?.jsonPrimitive?.longOrNull
            val atTime = obj["at_time"]?.jsonPrimitive?.contentOrNull?.trim()
            val promptRaw = obj["prompt"]?.jsonPrimitive?.contentOrNull?.trim()

            buildJsonObject {
                if (title.isEmpty() || message.isEmpty()) {
                    put("ok", JsonPrimitive(false))
                    put("error", JsonPrimitive("title and message are required"))
                    return@buildJsonObject
                }

                // 二选一：相对时间优先
                val dueAt: Long? = when {
                    delaySec != null -> System.currentTimeMillis() + delaySec * 1000L
                    !atTime.isNullOrEmpty() -> parseAbsoluteTime(atTime)
                    else -> null
                }

                if (dueAt == null) {
                    put("ok", JsonPrimitive(false))
                    put("error", JsonPrimitive(
                        if (!atTime.isNullOrEmpty()) "could not parse at_time: \"$atTime\"" else "missing_delay"
                    ))
                    put("hint", JsonPrimitive(
                        "Provide delay_seconds (e.g. 1800 for 30 minutes), " +
                            "or at_time as \"yyyy-MM-dd HH:mm\" (e.g. \"2026-09-29 08:00\")."
                    ))
                    return@buildJsonObject
                }

                // 已经过去的时间：明确拒绝，而不是排一个立刻触发的任务 ——
                // 后者会让用户看到一个「莫名其妙马上响」的提醒，更难理解发生了什么
                if (dueAt <= System.currentTimeMillis()) {
                    put("ok", JsonPrimitive(false))
                    put("error", JsonPrimitive("time_in_past"))
                    put("hint", JsonPrimitive("The given time is not in the future: ${formatLocal(dueAt)}"))
                    return@buildJsonObject
                }

                val task = ScheduledTask(
                    title = title,
                    message = message,
                    prompt = promptRaw?.takeIf { it.isNotEmpty() } ?: "$title：$message",
                    conversationId = conversationId,
                    dueAt = dueAt,
                )

                ScheduledTaskStore.add(context, task)
                ScheduledTaskScheduler.schedule(context, task)

                put("ok", JsonPrimitive(true))
                put("task_id", JsonPrimitive(task.id))
                put("due_at", JsonPrimitive(formatLocal(task.dueAt)))
                put("in", JsonPrimitive(humanDelay(task.dueAt - System.currentTimeMillis())))
                put("note", JsonPrimitive(
                    if (conversationId != null) {
                        "At that time the system will try to START A GENERATION IN THIS CONVERSATION " +
                            "with your `prompt` — so you will be asked to respond, and you may call tools " +
                            "as usual. A notification is also posted (in case the app cannot run in the " +
                            "background). Timing may be a few minutes late; background scheduling is not exact."
                    } else {
                        "A notification will be posted at that time (it may be a few minutes late). " +
                            "This reminder has no conversation attached, so it cannot trigger a reply — " +
                            "your `prompt` will instead be delivered the next time a generation runs."
                    }
                ))
            }
        }
    },
)

private fun listRemindersTool(context: Context): Tool = Tool(
    name = "list_reminders",
    description = """
        List scheduled reminders.

        Shows pending ones by default. Set include_fired=true to also see those that already
        fired but have not yet been delivered to you.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("include_fired", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Also list reminders that already fired. Default false.")
                })
            },
        )
    },
    needsApproval = { false },
    execute = { input ->
        guarded("list_reminders") {
            val obj = input as? JsonObject ?: JsonObject(emptyMap())
            val includeFired = obj["include_fired"]?.jsonPrimitive?.contentOrNull
                ?.toBooleanStrictOrNull() ?: false

            val all = ScheduledTaskStore.readAll(context)
            val shown = if (includeFired) all else all.filter { !it.fired }
            val now = System.currentTimeMillis()

            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("count", JsonPrimitive(shown.size))
                put("total", JsonPrimitive(all.size))
                put("reminders", buildJsonArray {
                    shown.sortedBy { it.dueAt }.forEach { t ->
                        add(buildJsonObject {
                            put("task_id", JsonPrimitive(t.id))
                            put("title", JsonPrimitive(t.title))
                            put("message", JsonPrimitive(t.message))
                            put("due_at", JsonPrimitive(formatLocal(t.dueAt)))
                            if (t.fired) {
                                put("fired", JsonPrimitive(true))
                                put("note", JsonPrimitive("already fired; will be delivered to you on the next generation"))
                            } else {
                                put("remaining", JsonPrimitive(humanDelay((t.dueAt - now).coerceAtLeast(0))))
                            }
                        })
                    }
                })
            }
        }
    },
)

private fun cancelReminderTool(context: Context): Tool = Tool(
    name = "cancel_reminder",
    description = """
        Cancel a scheduled reminder by task_id (or set action="cancel_all" to clear every reminder).

        Cancelling also stops the pending notification, so the user will not see it.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("task_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Id returned by schedule_reminder.")
                })
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "cancel (default) | cancel_all")
                })
            },
        )
    },
    needsApproval = { false },
    execute = { input ->
        guarded("cancel_reminder") {
            val obj = input as? JsonObject ?: JsonObject(emptyMap())
            val taskId = obj["task_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val action = obj["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "cancel"

            buildJsonObject {
                when {
                    action == "cancel_all" -> {
                        val all = ScheduledTaskStore.readAll(context)
                        all.forEach { ScheduledTaskScheduler.cancel(context, it.id) }
                        ScheduledTaskStore.writeAll(context, emptyList())
                        put("ok", JsonPrimitive(true))
                        put("cancelled", JsonPrimitive(all.size))
                    }
                    taskId != null -> {
                        ScheduledTaskScheduler.cancel(context, taskId)
                        val removed = ScheduledTaskStore.remove(context, taskId)
                        put("ok", JsonPrimitive(true))
                        put("cancelled", JsonPrimitive(if (removed) 1 else 0))
                        if (!removed) {
                            put("note", JsonPrimitive("No reminder found with that id (already fired or cancelled?)."))
                        }
                    }
                    else -> {
                        put("ok", JsonPrimitive(false))
                        put("error", JsonPrimitive("provide task_id, or action=cancel_all"))
                    }
                }
            }
        }
    },
)
