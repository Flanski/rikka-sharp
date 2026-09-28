package me.rerere.rikkahub.utils

import android.content.Context
import android.util.Log
import me.rerere.ai.core.ReasoningTrace
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 把 [ReasoningTrace] 的内容落盘，用于排查 Reasoning 回传相关的偶发 400。
 *
 * ── 为什么要落盘而不是只打 Logcat ──
 * 该错误是**概率性**的，需要长时间跑才能复现一次。而 Logcat 会被系统滚动覆盖、
 * 也需要连电脑才能看。落盘则可以事后翻，且用户自己就能把文件发出来。
 *
 * ── 位置 ──
 * `Android/data/<包名>/files/reasoning_trace.log`
 * （走 [Context.getExternalFilesDir]，**无需任何存储权限**，且 Termux 等外部工具可直接读取）
 *
 * ── 注意 ──
 * 日志里**包含推理文本**（这是排查所必需的 —— 要看清明文到底有没有发出去）。
 * 因此：
 *  · 只在需要排查时才开启（[ReasoningTrace.sink] 为 null 时全程不产生任何开销）
 *  · 文件有大小上限，超出后自动轮转，不会无限增长
 *  · 不主动上传、不写入公开目录
 */
object ReasoningTraceFile {

    private const val TAG = "ReasoningTraceFile"

    /** 单个文件上限。超出后轮转为 .1，避免长期开启把存储写满 */
    private const val MAX_BYTES = 2L * 1024 * 1024

    private const val FILE_NAME = "reasoning_trace.log"

    /** 写盘放到单线程队列，避免拖慢网络回调线程 */
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "reasoning-trace-writer").apply { isDaemon = true }
    }

    private val written = AtomicLong(0)

    @Volatile
    private var traceFile: File? = null

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * 开启日志。可重复调用（幂等）。
     *
     * @return 实际写入的文件；失败（如外部目录不可用）时返回 null，且不会开启日志
     */
    fun start(context: Context): File? {
        val dir = context.getExternalFilesDir(null) ?: run {
            Log.w(TAG, "外部文件目录不可用，日志未开启")
            return null
        }
        val f = File(dir, FILE_NAME)
        return runCatching {
            if (!dir.exists()) dir.mkdirs()
            f.parentFile?.mkdirs()
            traceFile = f
            written.set(f.length())
            ReasoningTrace.sink = { line -> write(line) }
            appendHeader(f)
            f
        }.getOrElse {
            Log.w(TAG, "无法开启 reasoning 日志", it)
            null
        }
    }

    fun stop() {
        ReasoningTrace.sink = null
        traceFile = null
    }

    /** 当前日志文件（供设置页/诊断入口展示路径用） */
    fun currentFile(): File? = traceFile

    private fun appendHeader(f: File) {
        val version = runCatching {
            val pi = android.content.pm.PackageManager::class.java
            null
        }.getOrNull()
        writer.execute {
            runCatching {
                f.appendText(
                    "\n===== session start ${timeFormat.format(Date())} =====\n"
                )
            }
        }
    }

    private fun write(line: String) {
        val f = traceFile ?: return
        writer.execute {
            runCatching {
                // 大小超限 → 轮转（把当前文件挪走，重新开始）
                if (written.get() > MAX_BYTES) {
                    val rolled = File(f.parentFile, "$FILE_NAME.1")
                    runCatching { if (rolled.exists()) rolled.delete() }
                    runCatching { f.renameTo(rolled) }
                    written.set(0)
                    f.appendText("===== rolled, continued ${timeFormat.format(Date())} =====\n")
                }
                val text = "${timeFormat.format(Date())} $line\n"
                f.appendText(text)
                written.addAndGet(text.toByteArray().size.toLong())
            }.onFailure { Log.w(TAG, "写日志失败", it) }
        }
    }
}
