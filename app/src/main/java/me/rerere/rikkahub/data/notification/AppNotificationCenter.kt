package me.rerere.rikkahub.data.notification

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * 一条**应用内**通知（在应用界面里弹出的通知，不是系统通知栏那条）。
 *
 * ── 与系统通知的区别 ──
 *  · 系统通知：走 `NotificationManager`，用户切到别的应用也能看到；
 *  · 应用内通知：用相同的视觉模板在**当前界面**上弹出，不占用通知栏、不需要通知权限，
 *    适合「用户正在看着屏幕时」的即时反馈。
 *
 * 两者不是替代关系：重要的事两者都发，次要的只发应用内即可。
 */
data class AppNotification(
    val id: String = Uuid.random().toString(),

    val type: Type = Type.NORMAL,

    /**
     * 标题。
     *
     * 为 null 时由 UI 按 [type] 取一个本地化的默认标题 ——
     * 这样调用方（尤其是拿不到 `stringResource` 的非 Composable 代码，如 `AppToaster`）
     * 就不必自己写死文案，**避免中文写死在代码里导致英文界面显示中文**。
     */
    val title: String? = null,

    val body: String,

    /**
     * 点「复制」时复制到剪贴板的内容。
     *
     * 为 null 时不显示复制按钮 —— 例如纯提示类消息没有可复制的东西，
     * 放一个空按钮只会占地方。
     */
    val copyText: String? = null,

    /**
     * 多久后自动消失（毫秒）。null = 一直留着，等用户手动关闭。
     *
     * 默认给了 8 秒：多数应用内提示只是「告诉你一声」，堆着不走会挡住界面。
     * 需要用户明确处理的内容（如报错）应传 null 让它保留。
     */
    val autoDismissMs: Long? = 8_000L,

    val createdAt: Long = System.currentTimeMillis(),
) {
    enum class Type {
        /** 普通信息。模板里对应 type-normal（蓝） */
        NORMAL,

        /** 需要注意。模板里对应 type-warning（橙，黑字） */
        WARNING,

        /** 出错。模板里对应 type-error（红） */
        ERROR,
    }
}

/**
 * 应用内通知中心。
 *
 * ── 为什么是一个「状态中心」而不是往 AppEventBus 发事件 ──
 * `AppEventBus` 是**事件流**（`SharedFlow`，无状态、发完即忘），
 * 而通知需要三样事件流给不了的东西：
 *  ① **当前有哪些通知正显示着**（要能渲染出来，且订阅时机晚于发送时仍能看到）；
 *  ② **关闭**（用户点掉一条，其余不受影响）；
 *  ③ **自动消失**（各自独立的计时）。
 *
 * 因此这里持有一个 `StateFlow<List<AppNotification>>`。
 * 另外做成 `object`（而非 Koin 单例）是有意的：
 * 工具层（`data/ai/tools`）要能直接发通知，而那里只拿得到 `Context`，
 * 拿不到 Koin 图；多传一个参数会让 `getTools` 的签名继续膨胀。
 * 通知本来就是全局唯一的，做成单例与它的语义相符。
 *
 * ── 线程安全 ──
 * 状态更新都在 [scope]（单线程调度器）上串行执行，避免并发 send/dismiss 时丢更新。
 */
object AppNotificationCenter {

    /**
     * 同时最多显示几条。
     *
     * 超出后**丢弃最旧的** —— 而不是排队。理由：通知是「此刻想告诉你的事」，
     * 排队的旧消息等轮到时往往已经过时；而且无限堆叠会遮住整个界面。
     */
    private const val MAX_VISIBLE = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _notifications = MutableStateFlow<List<AppNotification>>(emptyList())

    /** 当前正在显示的通知（新的在前）。UI 订阅它即可。 */
    val notifications: StateFlow<List<AppNotification>> = _notifications.asStateFlow()

    /** 每条通知的自动消失任务，关闭时需要取消 */
    private val dismissJobs = mutableMapOf<String, Job>()

    /**
     * 发一条通知。
     *
     * @return 实际入队的那条（id 可能与传入的不同？不会 —— 直接返回入队的对象，便于调用方记录 id）
     */
    fun send(notification: AppNotification): AppNotification {
        _notifications.value = buildList {
            // 新的放在最前面：与「最新的事在最上面」的直觉一致
            add(notification)
            addAll(_notifications.value.filterNot { it.id == notification.id })
        }.take(MAX_VISIBLE)

        // 超出上限而被挤掉的那些，把它们的计时任务也取消掉，避免无谓地等待
        val alive = _notifications.value.map { it.id }.toSet()
        dismissJobs.keys.filterNot { it in alive }.forEach { id ->
            dismissJobs.remove(id)?.cancel()
        }

        notification.autoDismissMs?.let { ms ->
            dismissJobs.remove(notification.id)?.cancel()
            dismissJobs[notification.id] = scope.launch {
                delay(ms)
                dismiss(notification.id)
            }
        }
        return notification
    }

    /** 快捷方法：发一条普通通知。`title` 不传则由 UI 取默认标题 */
    fun info(title: String? = null, body: String, copyText: String? = null): AppNotification =
        send(AppNotification(type = AppNotification.Type.NORMAL, title = title, body = body, copyText = copyText))

    /** 快捷方法：发一条警告。`title` 不传则由 UI 取默认标题 */
    fun warning(title: String? = null, body: String, copyText: String? = null): AppNotification =
        send(AppNotification(type = AppNotification.Type.WARNING, title = title, body = body, copyText = copyText))

    /**
     * 快捷方法：发一条错误通知。
     *
     * 默认**不自动消失**（`autoDismissMs = null`）—— 报错信息往往需要用户看清或复制下来，
     * 自己走掉会让人来不及读。这也是把「复制」按钮做出来的原因。
     */
    fun error(title: String? = null, body: String, copyText: String? = body): AppNotification =
        send(
            AppNotification(
                type = AppNotification.Type.ERROR,
                title = title,
                body = body,
                copyText = copyText,
                autoDismissMs = null,
            )
        )

    /** 关闭指定通知 */
    fun dismiss(id: String) {
        dismissJobs.remove(id)?.cancel()
        _notifications.value = _notifications.value.filterNot { it.id == id }
    }

    /** 全部清掉（例如切换页面时） */
    fun clear() {
        dismissJobs.values.forEach { it.cancel() }
        dismissJobs.clear()
        _notifications.value = emptyList()
    }
}
