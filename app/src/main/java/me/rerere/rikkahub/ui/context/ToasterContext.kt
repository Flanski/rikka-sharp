package me.rerere.rikkahub.ui.context

import androidx.compose.runtime.staticCompositionLocalOf
import com.dokar.sonner.ToastType
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
 * sonner 原签名还有 `id` / `icon` / `action` / `duration` —— 如果将来需要，
 * 在这里补上并转发到通知中心即可；**不预先实现**是为了不写没人用、也没验证过的代码。
 * 好处是：万一有人用了未支持的参数，会**编译期报错**，而不是静默行为不一致。
 */
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
    ) {
        val body = message.toString()
        when (type) {
            ToastType.Error -> {
                // ★报错：不自动消失 + 给复制按钮。
                //   这正是用户明确要求的一处 —— 报错信息常常需要贴到别处去搜，
                //   自己走掉会让人来不及读；而没有复制按钮则要手抄。
                // 不传 title —— 由 UI 按 type 取本地化标题（见 AppNotification.title 的说明）
                AppNotificationCenter.error(body = body, copyText = body)
            }

            ToastType.Warning -> AppNotificationCenter.warning(body = body)

            // Success / Info / Normal —— 模板只有 normal / warning / error 三类，
            // 因此这三种都归到 normal（蓝）。刻意**不**另造一个绿色变体：
            // 那会偏离模板的配色体系，而保持模板一致是这次改造的前提。
            ToastType.Success, ToastType.Info, ToastType.Normal -> AppNotificationCenter.info(body = body)
        }
    }
}

/**
 * 当前作用域的轻提示入口。
 *
 * 类型从 `ToasterState`（sonner）换成了 [AppToaster]，但 `show()` 的调用形式不变，
 * 所以 32 个文件里的 150+ 处 `toaster.show(...)` 无需改动。
 */
val LocalToaster = staticCompositionLocalOf<AppToaster> { error("Not provided") }
