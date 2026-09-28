package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 下载任务状态 */
enum class DownloadState { RUNNING, DONE, FAILED, CANCELLED }

/**
 * 一个下载任务（供「异步模式」查询进度）。
 *
 * 字段用 `@Volatile` 是为了让读写跨线程可见 —— 下载在 IO 协程里推进度，
 * 而查询来自另一个协程（工具调用）。
 */
class DownloadTask(
    val id: String,
    /** http / repo */
    val kind: String,
    val source: String,
    val destPath: String,
) {
    @Volatile var state: DownloadState = DownloadState.RUNNING
    @Volatile var bytes: Long = 0L
    /** 总字节；-1 表示服务端未提供 Content-Length */
    @Volatile var total: Long = -1L
    /** 当前阶段的人类可读描述（下载中 / 解压中 …） */
    @Volatile var phase: String = "准备中"
    @Volatile var error: String? = null
    @Volatile var finishedAt: Long? = null

    /**
     * 最近一次**有实际进展**的时刻（毫秒）。
     *
     * ── 为什么需要它 ──
     * 用户反馈「下载任务 done 之后，再次罗列仍显示 running / 连接中，bytes 为 0」。
     * 排查发现 `state` 本身并没错 —— 任务确实还在 RUNNING；
     * **缺的是「它到底有没有在推进」这个信息**：
     *  · 一个正常下载中的任务（bytes 在涨）与一个卡住的连接（bytes 一直是 0）
     *    在 `state` 上**完全一样**，调用方无法区分。
     *
     * 有了这个时间戳就能算出「安静了多久」，从而区分「慢但在动」与「卡住了」。
     * （与工具执行那边用活动看门狗替代固定超时是同一个思路：
     *   用「有没有进展」代替「过了多久」。）
     */
    @Volatile var lastUpdateAt: Long = System.currentTimeMillis()

    val startedAt: Long = System.currentTimeMillis()

    /** 距上次进展的秒数。用于识别「卡住」—— state 仍是 RUNNING，但已经很久没动静 */
    val idleSeconds: Long
        get() = (System.currentTimeMillis() - lastUpdateAt) / 1000

    val elapsedMs: Long get() = (finishedAt ?: System.currentTimeMillis()) - startedAt

    /** 0..1；未知总量时为 -1 */
    val progress: Float
        get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else -1f

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("kind", kind)
        put("source", source)
        put("state", state.name.lowercase())
        put("phase", phase)
        put("bytes", bytes)
        put("total", total)
        put("progress", progress.toDouble())
        put("elapsed_ms", elapsedMs)
        put("idle_seconds", idleSeconds)
        put("path", destPath)
        error?.let { put("error", it) }
    }
}

/**
 * 下载任务注册表（进程级单例）。
 *
 * ── 为什么需要它 ──
 * 用户要求下载工具支持两种模式：
 *  · **同步**：AI 发起后等待，直接拿到结果
 *  · **异步**：AI 发起后立即返回 taskId 继续输出，过段时间用 `download_status` 查询
 * 异步模式必须有个地方**保存进行中的任务**，这就是它的职责。
 *
 * ── 生命周期 ──
 * 任务保存在内存里，进程被杀即丢失（不做持久化）——
 * 下载本身也随进程结束而中断，把「已死的任务」持久化下来只会误导。
 */
object DownloadTaskRegistry {

    /** 任务上限；超过后清理最早完成的，避免长期运行内存增长 */
    private const val MAX_TASKS = 50

    private val tasks = ConcurrentHashMap<String, DownloadTask>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val seq = AtomicLong(0)

    /** 下载用的协程作用域。挂在进程级，异步任务在工具返回后继续跑。 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun create(kind: String, source: String, destPath: String): DownloadTask {
        val id = "dl-" + System.currentTimeMillis() + "-" + seq.incrementAndGet()
        val task = DownloadTask(id, kind, source, destPath)
        tasks[id] = task
        prune()
        return task
    }

    fun attachJob(id: String, job: Job) {
        jobs[id] = job
    }

    fun get(id: String): DownloadTask? = tasks[id]

    /** 按开始时间倒序，便于 AI 或用户看最近的 */
    fun list(): List<DownloadTask> = tasks.values.sortedByDescending { it.startedAt }

    fun cancel(id: String): Boolean {
        val task = tasks[id] ?: return false
        if (task.state != DownloadState.RUNNING) return false
        jobs.remove(id)?.cancel()
        task.state = DownloadState.CANCELLED
        task.phase = "已取消"
        task.finishedAt = System.currentTimeMillis()
        return true
    }

    private fun prune() {
        if (tasks.size <= MAX_TASKS) return
        tasks.values
            .filter { it.state != DownloadState.RUNNING }
            .sortedBy { it.startedAt }
            .take(tasks.size - MAX_TASKS)
            .forEach {
                tasks.remove(it.id)
                jobs.remove(it.id)
            }
    }
}
