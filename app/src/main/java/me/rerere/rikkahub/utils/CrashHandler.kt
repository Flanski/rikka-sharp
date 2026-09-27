package me.rerere.rikkahub.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit

private const val TAG = "CrashHandler"
private const val PREFS_NAME = "crash_handler"
private const val KEY_CRASHED = "crashed"
private const val KEY_STACKTRACE = "stacktrace"
private const val MAX_STACKTRACE_LENGTH = 8000

object CrashHandler {
    fun install(context: Context) {
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
            markCrashed(appContext, thread, throwable)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(appContext, "崩溃: ${throwable.javaClass.simpleName}: ${throwable.message?.take(100)}", Toast.LENGTH_LONG).show()
            }
            Thread.sleep(3000)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun hasCrashed(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CRASHED, false)
    }

    fun getStackTrace(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STACKTRACE, null)
    }

    /**
     * 写入一条诊断日志到外部私有目录（`tts_diag.log`）。
     *
     * 用途：native 崩溃（SIGSEGV）不会被 uncaught handler 捕获，
     * 因此需要在崩溃前的关键步骤埋点，才能定位到"执行到哪一步挂掉"。
     * 与 [dumpToExternal] 同目录，便于一并读取。
     */
    fun diag(context: Context, tag: String, message: String) {
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: return@runCatching
            val f = java.io.File(dir, "tts_diag.log")
            if (f.exists() && f.length() > 512 * 1024) f.delete()
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            f.appendText("[$ts][$tag] $message\n")
        }
    }

    fun clearCrashed(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_CRASHED).remove(KEY_STACKTRACE) }
    }

    /**
     * 崩溃日志同时写入 **外部私有目录**（`Android/data/<pkg>/files/`）。
     *
     * 原因：SharedPreferences 位于 App 私有目录，adb/终端工具无法读取，
     * 排查问题时拿不到堆栈。外部私有目录**无需任何权限**即可写入，
     * 且在同一设备上其它终端工具可以读取，便于定位问题。
     */
    private fun dumpToExternal(context: Context, text: String) {
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: return@runCatching
            val f = java.io.File(dir, "crash.log")
            // 保留最近 3 次崩溃，避免无限增长
            if (f.exists() && f.length() > 256 * 1024) f.delete()
            f.appendText("\n===== ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())} =====\n")
            f.appendText(text)
            f.appendText("\n")
        }
    }

    private fun markCrashed(context: Context, thread: Thread, throwable: Throwable) {
        val stackTrace = buildString {
            appendLine("Thread: ${thread.name}")
            appendLine(throwable.stackTraceToString())
        }.take(MAX_STACKTRACE_LENGTH)
        dumpToExternal(context, stackTrace)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) {
                putBoolean(KEY_CRASHED, true)
                putString(KEY_STACKTRACE, stackTrace)
            }
    }
}
