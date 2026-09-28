package me.rerere.rikkahub.data.schedule

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.service.ScheduledTaskWorker
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val TAG = "ScheduledTasks"

/** 任务 ID 在 WorkManager 与存储之间的对应关系所用的 key 前缀（避免与其它 Worker 撞名） */
private const val WORK_NAME_PREFIX = "scheduled_task_"

/**
 * 一条定时任务。
 *
 * ── 到期后会发生什么（**这是必须说清楚的语义**）──
 * 到期时应用**不一定在运行**，因此「让 AI 立刻做事」在手机上是不可靠的
 * （尤其华为/鸿蒙对后台的限制）。所以这里采用两级：
 *
 *  1. **一定发生**：发一条系统通知（标题 [title]、内容 [message]）——
 *     只要用户没关通知，就一定会看到；
 *  2. **回到应用后发生**：任务被标记为「待投递给 AI」，下一次该应用的生成开始前，
 *     [prompt] 会作为一条系统消息注入，AI 从而「记得」这件事。
 *
 * 也就是说：定时任务**不是**「到点自动执行动作」，而是「到点提醒 + 之后 AI 知晓」。
 * 这个区别很重要，不能对用户或模型含糊。
 */
@Serializable
data class ScheduledTask(
    val id: String = UUID.randomUUID().toString(),

    /** 通知标题；也用作列表里的简称 */
    val title: String,

    /** 通知正文（到期时展示） */
    val message: String,

    /**
     * 到期后要注入给 AI 的内容。
     *
     * 与 [message] 分开是因为两者面向的对象不同：
     * [message] 是给**人**看的（简短、可读）；
     * 这里是给**模型**看的（可以带上必要的上下文，让它在没有本次对话记录的情况下也能接上）。
     */
    val prompt: String,

    /** 到期时间（epoch 毫秒） */
    @SerialName("due_at")
    val dueAt: Long,

    /** 创建时间 */
    @SerialName("created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /** 是否已到期触发过（触发后保留，等待投递给 AI） */
    val fired: Boolean = false,

    /** 触发时间；未触发为 null */
    @SerialName("fired_at")
    val firedAt: Long? = null,
) {
    /** 是否已经过期但还没到点（用于 UI/列表展示） */
    val isPending: Boolean get() = !fired && dueAt > System.currentTimeMillis()

    /** 剩余毫秒（已过期或已触发时为 0） */
    fun remainingMs(now: Long = System.currentTimeMillis()): Long =
        if (fired) 0L else (dueAt - now).coerceAtLeast(0L)
}

/**
 * 定时任务的持久化。
 *
 * ── 为什么用文件而不是 DataStore ──
 * 两条硬性理由：
 *  1. **可能在 Worker 里被访问**。Worker 运行时不一定有完整的 DI 环境，
 *     而 DataStore 是异步 Flow API，在 Worker 的同步窗口里用起来别扭；
 *  2. 任务数量很小（几十条以内），不需要 DataStore 的迁移与类型安全机制。
 *
 * ── 为什么不只依赖 WorkManager 自己的持久化 ──
 * WorkManager 确实会把排队的任务写进自己的数据库、并在重启后恢复 ——
 * 但它的记录里**只有我们塞进去的 Data（键值对）**，且**任务执行完就被删除**。
 * 而我们还需要「已触发、但尚未投递给 AI」这一状态在触发后继续存在，
 * 因此需要自己这份存储。
 */
object ScheduledTaskStore {

    private const val FILE_NAME = "scheduled_tasks.json"

    /**
     * `ignoreUnknownKeys` 是必须的：
     * 将来给 [ScheduledTask] 加字段时，旧文件仍然要能读出来，否则用户升级后会丢掉全部任务。
     */
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun readAll(context: Context): List<ScheduledTask> = runCatching {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val text = f.readText()
        if (text.isBlank()) return emptyList()
        json.decodeFromString<List<ScheduledTask>>(text)
    }.getOrElse {
        // 解析失败时**不删除**文件：宁可让用户手动处理，也不要静默丢掉他的任务
        Log.w(TAG, "读取定时任务失败（文件保留）", it)
        emptyList()
    }

    @Synchronized
    fun writeAll(context: Context, tasks: List<ScheduledTask>) {
        runCatching {
            file(context).writeText(json.encodeToString(tasks))
        }.onFailure { Log.w(TAG, "写入定时任务失败", it) }
    }

    @Synchronized
    fun add(context: Context, task: ScheduledTask): List<ScheduledTask> {
        val list = readAll(context) + task
        writeAll(context, list)
        return list
    }

    @Synchronized
    fun remove(context: Context, id: String): Boolean {
        val list = readAll(context)
        val next = list.filterNot { it.id == id }
        if (next.size == list.size) return false
        writeAll(context, next)
        return true
    }

    /** 标记为已触发；返回更新后的任务（找不到则 null） */
    @Synchronized
    fun markFired(context: Context, id: String): ScheduledTask? {
        val list = readAll(context)
        var updated: ScheduledTask? = null
        val next = list.map {
            if (it.id == id && !it.fired) {
                it.copy(fired = true, firedAt = System.currentTimeMillis()).also { u -> updated = u }
            } else it
        }
        if (updated != null) writeAll(context, next)
        return updated
    }

    /**
     * 取出所有「已触发但还没给 AI 看过」的任务，并**从存储中删除**。
     *
     * 删除是刻意的：这些内容只会被注入一次，重复注入会让 AI 反复「想起」同一件事。
     * 因此在注入**之前**取走并删除，即使注入过程失败也只是丢一次提醒，
     * 不会造成重复注入 —— 两害相权取其轻。
     */
    @Synchronized
    fun takePendingForAi(context: Context): List<ScheduledTask> {
        val list = readAll(context)
        val pending = list.filter { it.fired }
        if (pending.isEmpty()) return emptyList()
        writeAll(context, list.filterNot { it.fired })
        return pending
    }

    /** 清掉所有已触发的任务（用户主动清理时用） */
    @Synchronized
    fun clearFired(context: Context): Int {
        val list = readAll(context)
        val fired = list.filter { it.fired }
        if (fired.isEmpty()) return 0
        writeAll(context, list.filterNot { it.fired })
        return fired.size
    }
}

/**
 * 把任务排进 WorkManager / 取消。
 *
 * ── 为什么选 WorkManager 而不是 AlarmManager ──
 *  · WorkManager **不需要任何权限**，重启后自动恢复，
 *    并且能在 Doze 下用 `setExactAndAllowWhileIdle` 那类 API 无法保证的场景里仍然跑起来；
 *  · AlarmManager 的精确闹钟要 `SCHEDULE_EXACT_ALARM`，它是 **appop** 类型
 *    （需用户去系统设置里授权，且可被撤销），对一个「提醒」功能来说代价过高；
 *  · 代价是**精度**：WorkManager 不保证准点，可能被系统推迟几分钟到更久
 *    （深睡、省电模式、厂商后台管理都会影响）。
 *
 * 对「提醒」用途来说，晚几分钟可以接受；如果将来确实需要秒级精度，
 * 再单独引入 AlarmManager 并请求用户授权，而不是一开始就付这个代价。
 */
object ScheduledTaskScheduler {

    /** 排一个任务。延迟为 0 或负数时按「尽快」处理 */
    fun schedule(context: Context, task: ScheduledTask) {
        val delayMs = (task.dueAt - System.currentTimeMillis()).coerceAtLeast(0L)

        val request = OneTimeWorkRequestBuilder<ScheduledTaskWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    ScheduledTaskWorker.KEY_TASK_ID to task.id,
                    // 冗余带上标题/内容：万一存储文件读取失败（例如被外部清理），
                    // Worker 仍能发出通知 —— 通知是这个功能的最低保证
                    ScheduledTaskWorker.KEY_TITLE to task.title,
                    ScheduledTaskWorker.KEY_MESSAGE to task.message,
                )
            )
            .addTag(ScheduledTaskWorker.TAG)
            .build()

        runCatching {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME_PREFIX + task.id,
                    // REPLACE：同一个任务重新排期时应该覆盖旧的，而不是排两条
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
        }.onFailure { Log.w(TAG, "排期失败 id=${task.id}", it) }
    }

    fun cancel(context: Context, id: String) {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PREFIX + id)
        }.onFailure { Log.w(TAG, "取消失败 id=$id", it) }
    }

    /**
     * 把所有未触发的任务重新排一遍。
     *
     * 用途：应用升级、或从备份恢复后，WorkManager 的队列可能与我们的存储不一致
     * （例如任务是在旧版本排的、或被系统清理掉了）。
     * 重新排一遍是幂等的（同名 unique work 会 REPLACE），因此可以放心调用。
     */
    fun rescheduleAll(context: Context) {
        val tasks = ScheduledTaskStore.readAll(context).filter { !it.fired }
        if (tasks.isEmpty()) return
        Log.i(TAG, "重新排期 ${tasks.size} 个未触发任务")
        tasks.forEach { schedule(context, it) }
    }
}
