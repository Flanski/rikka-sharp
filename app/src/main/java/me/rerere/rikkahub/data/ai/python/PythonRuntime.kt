package me.rerere.rikkahub.data.ai.python

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "PythonRuntime"

/**
 * Chaquopy 运行时的统一启动入口。
 *
 * ── 为什么需要它（原来三处各写一份，且都有同一个竞态）──
 * `CalculatorTool` / `PythonTools` / `CodeTools` 各自写了一份启动代码，形如：
 *
 * ```kotlin
 * if (!Python.isStarted()) {              // ← 检查发生在这里（**不在** Main 线程）
 *     withContext(Dispatchers.Main) {
 *         Python.start(AndroidPlatform(context))   // ← 第二个进来的就抛 already started
 *     }
 * }
 * ```
 * `Python.start()` 只能调用一次，第二次抛 `IllegalStateException: Python already started`。
 * 而上面的写法里，**检查与启动不在同一个线程**：
 *
 * ```
 * 线程 A: isStarted() == false
 * 线程 B: isStarted() == false          ← B 也通过了检查
 * 线程 A: 切到 Main → start() 成功
 * 线程 B: 切到 Main → start() → 抛 already started
 * ```
 *
 * 项目支持并行工具执行（`enableParallelToolExecution`），只要模型一次发出多个工具调用
 * （例如同时算两个表达式），就会踩中。表现是「**有时能用、有时报 already started**」——
 * 而 `CalculatorTool` 与 `PythonTools` 共用同一个 Python 实例，
 * 所以一个失败会连带另一个也不可用。
 *
 * ── 修法 ──
 * 把「检查 + 启动」**都放进 Main 线程**。Main 是单线程，这两个操作在那里天然是原子的 ——
 * 不需要额外的锁，也不会明显阻塞（`Python.start` 本身很快）。
 *
 * 另外对 `already started` 做容错：即便将来出现别的并发路径，
 * 这个特定异常也意味着「已经可用了」，没有理由让调用方失败。
 */
object PythonRuntime {

    /**
     * 确保 Python 已启动。可重复调用；已启动时立即返回。
     *
     * @throws Throwable 启动过程中的**真实**失败（如 Chaquopy 未打包、平台初始化错误）会原样抛出。
     *   只有 `already started` 这一种会被忽略 —— 它表示目标状态已达成。
     */
    suspend fun ensureStarted(context: Context) {
        // 快速路径：绝大多数调用走这里，省掉一次线程切换
        if (Python.isStarted()) return

        withContext(Dispatchers.Main) {
            // ★检查必须在这里（Main 线程内），与 start 处于同一线程 —— 这是修掉竞态的关键。
            //   放在外面的话，两个并发调用会双双通过检查，然后在 Main 上排队时第二个失败。
            if (Python.isStarted()) return@withContext

            try {
                Python.start(AndroidPlatform(context.applicationContext))
                Log.i(TAG, "Chaquopy 运行时已启动")
            } catch (t: Throwable) {
                if (isAlreadyStarted(t)) {
                    // 目标状态已达成 —— 不是错误，忽略
                    Log.i(TAG, "Python 已由其它路径启动，忽略: ${t.message}")
                    return@withContext
                }
                // 真实失败：记下来并向上抛，让调用方把原因如实告诉用户/模型，
                // 而不是吞掉之后让工具返回一个没有解释的空结果。
                Log.e(TAG, "Chaquopy 启动失败", t)
                throw t
            }
        }
    }

    private fun isAlreadyStarted(t: Throwable): Boolean =
        t is IllegalStateException &&
            t.message.orEmpty().contains("already started", ignoreCase = true)
}
