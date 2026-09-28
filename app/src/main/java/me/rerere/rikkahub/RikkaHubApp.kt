package me.rerere.rikkahub

import androidx.work.Configuration
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.runtime.Composer
import androidx.compose.runtime.tooling.ComposeStackTraceMode
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import me.rerere.common.android.appTempFolder
import com.whl.quickjs.android.QuickJSLoader
import me.rerere.rikkahub.di.appModule
import me.rerere.rikkahub.di.dataSourceModule
import me.rerere.rikkahub.di.repositoryModule
import me.rerere.rikkahub.di.viewModelModule
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.WebServerService
import me.rerere.rikkahub.utils.ReasoningTraceFile
import me.rerere.rikkahub.data.schedule.ScheduledTaskScheduler
import me.rerere.rikkahub.utils.CrashHandler
import me.rerere.rikkahub.utils.DatabaseUtil
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin

private const val TAG = "RikkaHubApp"

const val CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID = "chat_completed"
const val CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID = "chat_live_update"
const val CHAT_GENERATION_FOREGROUND_CHANNEL_ID = "chat_generation_foreground"
const val WEB_SERVER_NOTIFICATION_CHANNEL_ID = "web_server"

/**
 * 工具授权请求的通知渠道。
 *
 * 重要性设为 HIGH：模型会**一直等待**用户决定才继续生成，
 * 若通知不醒目（静默/沉底），用户可能长时间以为卡住了。
 */
const val TOOL_APPROVAL_NOTIFICATION_CHANNEL_ID = "tool_approval"

/**
 * 「模型正在执行工具」的活动通知渠道。
 *
 * 重要性 LOW：这是**告知性**的（让用户瞥一眼就知道 AI 在做什么），
 * 不需要像授权请求那样打断用户。
 */
const val TOOL_ACTIVITY_NOTIFICATION_CHANNEL_ID = "tool_activity"

/**
 * AI 主动发起的通知（通过 `send_notification` 工具）。
 *
 * 单开渠道而不复用其它：用户可以在系统设置里**单独**控制它的行为，
 * 或直接整个关掉 —— 不至于因为 AI 爱发通知而被迫放弃「对话完成」「工具授权」等更重要的提醒。
 */
const val AI_NOTIFICATION_CHANNEL_ID = "ai_notification"

/** 同上，但 importance=HIGH：用于 AI 标注为「重要」的通知（会响铃/震动） */
const val AI_NOTIFICATION_URGENT_CHANNEL_ID = "ai_notification_urgent"

/**
 * ★实现了 [Configuration.Provider]，但**实际不依赖它** —— 真正生效的是 [onCreate]
 * 里的显式 `WorkManager.initialize(...)`。理由见 onCreate 中的详细注释
 * （简言之：Provider 靠运行期 instanceof，R8 会删掉实现；静态调用不会）。
 *
 * 原因：`AndroidManifest.xml` 里把 WorkManager 的自动初始化移除了
 * （`androidx.work.WorkManagerInitializer` 带 `tools:node="remove"`），
 * 而项目里此前**没有任何地方**初始化 WorkManager。
 *
 * 这本身不会立刻出问题 —— 只要没人调用 `WorkManager.getInstance()`。
 * 但一旦调用，WorkManager 会走「按需初始化」分支：
 *     if (appContext instanceof Configuration.Provider) { 用它的配置初始化 }
 *     else { throw IllegalStateException("... your Application does not implement
 *            Configuration.Provider") }
 * （见 WorkManagerImpl.getInstance(Context) 的实现）
 * 即：**要么实现这个接口，要么就不能用 WorkManager。**
 *
 * 定时任务功能依赖 WorkManager，所以把这一环补上。
 */
class RikkaHubApp : Application(), Configuration.Provider {

    /**
     * WorkManager 的配置。
     *
     * 刻意**不设置 WorkerFactory**：
     *  · 我们的 Worker（[me.rerere.rikkahub.service.ScheduledTaskWorker]）自己读文件存储、
     *    自己发通知，不需要依赖注入，用默认 factory 即可；
     *  · 而 Koin 的 `workManagerFactory()` 虽然已在 [onCreate] 里注册，
     *    但在这里取用它要经过 Koin 的全局上下文 API —— 那是一条**未经验证**的路径，
     *    一旦 API 用法不对就会是运行期崩溃。
     *    （这个项目已经因为「引入时没验证运行期行为」踩过坑，不再重复。）
     *
     * 将来若有 Worker 需要注入依赖，在这里加上 `setWorkerFactory(...)` 即可，
     * 但那时应当先确认取到的 factory 确实可用，而不是想当然。
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        try {
            startKoin {
                androidLogger()
                androidContext(this@RikkaHubApp)
                workManagerFactory()
                modules(appModule, viewModelModule, dataSourceModule, repositoryModule)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Koin 初始化失败", e)
            throw e
        }
        this.createNotificationChannel()

        // set cursor window size to 32MB
        DatabaseUtil.setCursorWindowSize(32 * 1024 * 1024)

        // install crash handler
        CrashHandler.install(this)

        // 开启 Reasoning 回传诊断日志。
        //
        // 背景：线上偶发 400「The `reasoning_text` in the thinking mode must be passed back
        // to the API.」，是**概率性**的，需要长时间运行才能抓到一次，因此默认开启并把
        // 每次请求实际发出的 reasoning 内容落盘（含最关键的一项：有没有「带 encrypted 但缺明文」的 item）。
        //
        // 位置：Android/data/<包名>/files/reasoning_trace.log（无需权限，外部工具可读）
        // 日志含推理文本，仅用于排查；修复后把 ReasoningTrace.sink 置空即可完全关闭（关闭时零开销）。
        runCatching { ReasoningTraceFile.start(this) }

        // ── 显式初始化 WorkManager ──
        //
        // ★这里刻意**不依赖** Configuration.Provider 的运行期 `instanceof` 检查。
        //
        // 原因（已在构建产物中实测确认，不是推测）：那个 instanceof 是 WorkManager 库内部行为，
        // **不在我们代码的静态调用图里** → R8 认为接口无人使用 → 把 RikkaHubApp 的接口实现删掉。
        // 证据：dex 里 `Landroidx/work/Configuration$Provider;` 出现 **0 次**，
        // 连 `Configuration$Builder`（实现体里用到的）也是 0 次 —— 说明整个方法体被优化掉了。
        //
        // 而且加 keep 规则**救不回来**：`-keep class * implements androidx.work.Configuration$Provider`
        // 依赖"接口存在"这个前提，但接口本身先被删了 → 匹配不到任何类（鸡生蛋问题）。
        // 实测加规则后 `Landroidx/work/Configuration$Provider;` 仍然是 0 次。
        //
        // 改用**静态方法调用**：它明确出现在调用图里，R8 不会删。
        // 代价是初始化时机从"首次用到"提前到"进程启动"，但我们的 Configuration 是默认构造，
        // 开销很小，而**可靠性比这点启动时间重要得多**（定时任务一旦静默失效很难排查）。
        runCatching {
            if (!androidx.work.WorkManager.isInitialized()) {
                androidx.work.WorkManager.initialize(this, workManagerConfiguration)
            }
        }.onFailure { Log.w(TAG, "WorkManager 初始化失败", it) }

        // 恢复定时任务的排期。
        //
        // 为什么需要：WorkManager 自己会持久化队列并在重启后恢复，但有两种情况会不一致 ——
        //  ① 应用升级后（WorkManager 的数据库可能有历史遗留条目）；
        //  ② 存储文件里的任务与 WorkManager 队列不同步（例如队列被系统清掉）。
        // 重新排一遍是**幂等**的（同名 unique work 用 REPLACE 策略），因此可以放心调用。
        //
        // 放在这里而不是延迟执行：只读一个小 JSON 文件 + 若干次入队，耗时在毫秒级。
        runCatching { ScheduledTaskScheduler.rescheduleAll(this) }
            .onFailure { Log.w(TAG, "恢复定时任务排期失败", it) }

        // Init QuickJS native library
        QuickJSLoader.init()

        // delete temp files
        deleteTempFiles()

        // sync upload files to DB
        syncManagedFiles()

        // Start WebServer if enabled in settings
        startWebServerIfEnabled()

        // Increment launch count
        incrementLaunchCount()

        // Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.Auto)
    }

    private fun incrementLaunchCount() {
        get<AppScope>().launch {
            runCatching {
                val store = get<SettingsStore>()
                val current = store.settingsFlowRaw.first()
                store.update(current.copy(launchCount = current.launchCount + 1))
                Log.i(TAG, "incrementLaunchCount: ${store.settingsFlowRaw.first().launchCount}")
            }.onFailure {
                Log.e(TAG, "incrementLaunchCount failed", it)
            }
        }
    }

    private fun deleteTempFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            val dir = appTempFolder
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }

    private fun syncManagedFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<FilesManager>().syncFolder()
            }.onFailure {
                Log.e(TAG, "syncManagedFiles failed", it)
            }
        }
    }

    private fun startWebServerIfEnabled() {
        get<AppScope>().launch {
            runCatching {
                delay(500)
                val settings = get<SettingsStore>().settingsFlowRaw.first()
                if (settings.webServerEnabled) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            this@RikkaHubApp,
                            android.Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: notification permission not granted, skipping")
                        return@launch
                    }
                    if (Build.VERSION.SDK_INT >= 37 &&
                        !settings.webServerLocalhostOnly &&
                        ContextCompat.checkSelfPermission(
                            this@RikkaHubApp,
                            android.Manifest.permission.ACCESS_LOCAL_NETWORK
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: local network permission not granted, skipping")
                        return@launch
                    }
                    val intent = Intent(this@RikkaHubApp, WebServerService::class.java).apply {
                        action = WebServerService.ACTION_START
                        putExtra(WebServerService.EXTRA_PORT, settings.webServerPort)
                        putExtra(WebServerService.EXTRA_LOCALHOST_ONLY, settings.webServerLocalhostOnly)
                    }
                    startForegroundService(intent)
                }
            }.onFailure {
                Log.e(TAG, "startWebServerIfEnabled failed", it)
            }
        }
    }

    private fun createNotificationChannel() {
        val notificationManager = NotificationManagerCompat.from(this)
        val chatCompletedChannel = NotificationChannelCompat
            .Builder(
                CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
            .setName(getString(R.string.notification_channel_chat_completed))
            .setVibrationEnabled(true)
            .build()
        notificationManager.createNotificationChannel(chatCompletedChannel)

        val chatLiveUpdateChannel = NotificationChannelCompat
            .Builder(
                CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW
            )
            .setName(getString(R.string.notification_channel_chat_live_update))
            .setVibrationEnabled(false)
            .build()
        notificationManager.createNotificationChannel(chatLiveUpdateChannel)

        val webServerChannel = NotificationChannelCompat
            .Builder(WEB_SERVER_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_web_server))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(webServerChannel)

        val toolApprovalChannel = NotificationChannelCompat
            .Builder(
                TOOL_APPROVAL_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
            .setName(getString(R.string.notification_channel_tool_approval))
            .setVibrationEnabled(true)
            .build()
        notificationManager.createNotificationChannel(toolApprovalChannel)

        val toolActivityChannel = NotificationChannelCompat
            .Builder(
                TOOL_ACTIVITY_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW
            )
            .setName(getString(R.string.notification_channel_tool_activity))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(toolActivityChannel)

        val generationForegroundChannel = NotificationChannelCompat
            .Builder(CHAT_GENERATION_FOREGROUND_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_generation_foreground))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(generationForegroundChannel)

        // AI 主动通知（普通）：IMPORTANCE_DEFAULT —— 会出现在状态栏，但不强行打断
        val aiNotificationChannel = NotificationChannelCompat
            .Builder(AI_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(getString(R.string.notification_channel_ai))
            .setVibrationEnabled(false)
            .build()
        notificationManager.createNotificationChannel(aiNotificationChannel)

        // AI 主动通知（重要）：IMPORTANCE_HIGH —— 响铃/震动，用于 AI 明确判断需要用户留意的事
        val aiUrgentChannel = NotificationChannelCompat
            .Builder(AI_NOTIFICATION_URGENT_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(getString(R.string.notification_channel_ai_urgent))
            .setVibrationEnabled(true)
            .build()
        notificationManager.createNotificationChannel(aiUrgentChannel)
    }

    override fun onTerminate() {
        super.onTerminate()
        get<AppScope>().cancel()
        stopService(Intent(this, WebServerService::class.java))
    }
}

class AppScope : CoroutineScope by CoroutineScope(
    SupervisorJob()
        + Dispatchers.Main
        + CoroutineName("AppScope")
        + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "AppScope exception", e)
    }
)
