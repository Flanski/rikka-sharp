package me.rerere.rikkahub.ui.components.notification

import androidx.compose.ui.graphics.Color
import me.rerere.rikkahub.data.notification.AppNotification

/**
 * 通知模板的配色 —— 取自 `/workspace/通知弹窗特效Demo.html` 的 CSS。
 *
 * ```css
 * .notification-modal        { background: #409eff }   → container
 * .notification-body         无 background              → 内容区透明，显示的是容器的蓝
 * .type-normal  .notification-header { background: #409eff; color: #fff }
 * .type-warning .notification-header { background: #e6a23c; color: #000 }
 * .type-error   .notification-header { background: #f56c6c; color: #fff }
 * .notification-btn          { background: #fff; color: #409eff }
 * .btn-retry                 { background: rgba(255,255,255,0.2); color: #fff }
 * ```
 *
 * ── 为什么单独一个文件 ──
 * 它被三个地方共用：`NotificationTemplateDialog`（模板弹窗本体）、
 * `ToolApprovalDialog`（工具授权）、`AskUserDialog`（ask_user 表单）。
 *
 * ★此前它和 `AppNotificationHost` 放在同一个文件里，
 * 而我**重写那个文件时把它一起删掉了** —— 造成 18 处引用全部失去定义。
 * 独立成文件后就不会再因为「重写某个页面文件」而连带丢失。
 * （通用的理由：**被多处共用的东西不要和某一处实现放在同一个文件里**。）
 */
internal object NotificationTemplateColors {

    /** 模板 `.notification-modal` 的 background（三种类型都是这个蓝底） */
    private val normalContainer = Color(0xFF409EFF)
    private val warningContainer = Color(0xFF409EFF)
    private val errorContainer = Color(0xFF409EFF)

    private val normalHeader = Color(0xFF409EFF)
    private val warningHeader = Color(0xFFE6A23C)
    private val errorHeader = Color(0xFFF56C6C)

    /** warning 的 header 用黑字（模板里 `type-warning` 的 color 是 #000） */
    private val normalOnHeader = Color(0xFFFFFFFF)
    private val warningOnHeader = Color(0xFF000000)
    private val errorOnHeader = Color(0xFFFFFFFF)

    /** 模板里 body / 按钮区的文字色为 #fff */
    private val onContainer = Color(0xFFFFFFFF)

    /** 模板 `.notification-btn` 的白底蓝字 */
    private val buttonText = Color(0xFF409EFF)

    data class Palette(
        val container: Color,
        val header: Color,
        val onHeader: Color,
        val onContainer: Color,
        val buttonText: Color,
    )

    fun of(type: AppNotification.Type): Palette = when (type) {
        AppNotification.Type.NORMAL -> Palette(
            container = normalContainer,
            header = normalHeader,
            onHeader = normalOnHeader,
            onContainer = onContainer,
            buttonText = buttonText,
        )

        AppNotification.Type.WARNING -> Palette(
            container = warningContainer,
            header = warningHeader,
            onHeader = warningOnHeader,
            onContainer = onContainer,
            buttonText = buttonText,
        )

        AppNotification.Type.ERROR -> Palette(
            container = errorContainer,
            header = errorHeader,
            onHeader = errorOnHeader,
            onContainer = onContainer,
            buttonText = buttonText,
        )
    }
}
