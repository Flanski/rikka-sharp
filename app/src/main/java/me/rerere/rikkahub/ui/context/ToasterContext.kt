package me.rerere.rikkahub.ui.context

import androidx.compose.runtime.staticCompositionLocalOf
import com.dokar.sonner.ToastType
import kotlin.time.Duration
import me.rerere.rikkahub.data.notification.AppNotification
import me.rerere.rikkahub.data.notification.AppNotificationCenter

/**
 * 应用内的「轻提示」入口。
 *
 * ── 为什么换掉了 sonner 的 `ToasterState` ──
 * 用户要求把吐司改成**通知模板**的样式（`/workspace/通知弹窗特效Demo.html`），
 * 并且**报错信息要有复制按钮**。
 *
 * 原本考虑「保留 sonner 的状态对象、只替换渲染组件」，但那**做不到**：
 * `ToasterState.toasts` 是 `internal`（已核对 sonner 0.4.0 源码），
 * 外部读不到当前有哪些吐司，也就无法自己渲染。
 *
 * 所以改为在这里提供一个**行为兼容**的替代品：
 *  · 保持 `show(message = ..., type = ...)` 的调用形式不变 ——
 *    已核对全项目 **150+ 处调用只用到了这两个参数**（7 处具名调用全是这两个），
 *    因此这些调用点**一行都不用改**；
 *  · 内部转发到 [AppNotificationCenter]，由 `AppNotificationHost` 用通知模板渲染；
 *  · `ToastType` 继续沿用 sonner 的枚举，避免改动 3 个引用它的文件。
 *
 * ── 有意保留的差异 ──
 * 只实现了 `message` / `type` 两个参数（真实用到的全部）。
 * sonner 原签名还有 `id` / `icon` / `action` —— 这三个项目里**没有一处用到**，
 * 因此**不预先实现**（不写没人用、也没验证过的代码）。
 * 好处是：万一将来有人用了它们，会**编译期报错**，而不是静默行为不一致。
 *
 * `duration` 则已支持（实测有 1 处在用）。
 */
/**
 * 普通提示的默认显示时长（毫秒）。
 *
 * 比 sonner 原来的 4000ms 稍长：应用内通知用的是**通知卡片**（比原来的小吐司更大、信息更多），
 * 阅读需要多一点时间。报错**不受这个值影响** —— 它默认不自动消失（见 [show]）。
 */
private const val DEFAULT_TOAST_MS = 5_000L

class AppToaster {

    /**
     * 显示一条轻提示。
     *
     * @param message 内容。声明为 `Any` 与 sonner 原签名一致 ——
     *   调用方有时传 `String`，有时传 `@StringRes` 已解析的结果；
     *   传其它类型时按 `toString()` 处理，避免调用方莫名编译失败。
     * @param type 语义类型，决定配色与行为（见下）
     */
    fun show(
        message: Any,
        type: ToastType = ToastType.Normal,
        /**
         * 显示时长。
         *
         * **声明为可空是有意的** —— 用来区分两种情况：
         *  · 调用方没传（null）→ 按 [type] 决定：报错不自动消失，其余用默认时长；
         *  · 调用方显式传了 → 一律尊重调用方意图。
         *
         * 若给它一个非空默认值（例如 sonner 的 4000ms），就没法区分
         * 「调用方想要 4 秒」和「调用方没管」——「报错不自动消失」这条规则
         * 会被默认值悄悄覆盖掉。
         */
        duration: Duration? = null,
    ) {
        val body = message.toString()
        val autoDismissMs = duration?.inWholeMilliseconds

        // 不传 title —— 由 UI 按 type 取本地化标题（见 AppNotification.title 的说明）
        val notification = when (type) {
            ToastType.Error ->
                // ★报错：默认**不自动消失**（autoDismissMs 传 null）+ 带复制按钮。
                //   这正是用户明确要求的一处 —— 报错信息常需贴到别处搜，
                //   自己走掉会让人来不及读；没有复制按钮则要手抄。
                AppNotification(
                    type = AppNotification.Type.ERROR,
                    body = body,
                    copyText = body,
                    autoDismissMs = autoDismissMs,
                )

            ToastType.Warning -> AppNotification(
                type = AppNotification.Type.WARNING,
                body = body,
                // ★必须在这里给默认值：如果直接写 `autoDismissMs = autoDismissMs`，
                //   当调用方没传 duration 时它就是 null，会**覆盖掉** AppNotification 自带的
                //   8000ms 默认值 —— 结果是普通提示也会永久停在屏幕上，
                //   与「堆着不走会挡住界面」的设计意图相反。（这是我自己写出来又发现的问题。）
                autoDismissMs = autoDismissMs ?: DEFAULT_TOAST_MS,
            )

            // Success / Info / Normal —— 模板只有 normal / warning / error 三类，
            // 因此这三种都归到 normal（蓝）。刻意**不**另造绿色变体：
            // 那会偏离模板的配色体系，而保持模板一致是这次改造的前提。
            ToastType.Success, ToastType.Info, ToastType.Normal -> AppNotification(
                type = AppNotification.Type.NORMAL,
                body = body,
                // 同上：必须给默认值，否则会被 null 覆盖成「永不消失」
                autoDismissMs = autoDismissMs ?: DEFAULT_TOAST_MS,
            )
        }
        AppNotificationCenter.send(notification)
    }
}

/**
 * 当前作用域的轻提示入口。
 *
 * 类型从 `ToasterState`（sonner）换成了 [AppToaster]，`show()` 的调用形式保持一致，
 * 因此绝大多数 `toaster.show(...)` 调用点无需改动。
 *
 * ★但**并非全部** —— 实测漏了三类，编译时才暴露（12 条错误）：
 *  ① 有 8 处把 `toaster` 作为**函数参数**往下传，那些签名写的是 `ToasterState`
 *     （`ChatInput` / `AssistantDetailPage` / `AssistantImporter` / `SkillDetailPage` /
 *       `SettingProviderPage`），必须一起改；
 *  ② `Export.kt` 自己调了 sonner 的 `rememberToasterState()`；
 *  ③ 有 1 处传了 `duration = 1.seconds`。
 * **教训：统计调用点时只看了 `xxx.show(...)` 的参数，没看「类型被当作参数传递」的地方
 *   —— 判断"改动影响面"必须按**类型**去找引用，而不是按调用形式。**
 */
val LocalToaster = staticCompositionLocalOf<AppToaster> { error("Not provided") }