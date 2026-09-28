package me.rerere.ai.core

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 工具执行期间的**活动上报通道**。
 *
 * ── 要解决的问题 ──
 * 原先工具执行靠一个**固定总时长**的超时（`toolExecTimeout`，默认 120 秒）兜底。
 * 这个做法有两个都不好的结果：
 *
 *  · 时间设短了 → 正常但耗时的工具（克隆仓库、下载大文件、串口等待）被**误杀**；
 *  · 时间设长了 → 真正卡死的工具要**白等**那么久，用户只看到「没反应」。
 *
 * 而这两个问题其实都源于同一个缺陷：**系统不知道工具在不在干活**，
 * 只能拿时间当代理 —— 于是怎么做都不对。
 *
 * ── 这里的做法 ──
 * 给工具一个上报活动的通道。超时语义从「总时长」改成「**连续多久没有活动**」：
 *
 *  · 工具每上报一次（或任何一次进度更新），计时就**续期**；
 *  · 只有连续 [ToolProgress] 的观察者判定超时时，才认为它卡住了。
 *
 * 于是「慢但在干活」与「卡住不动」被区分开 —— 前者不会被误杀，后者能及时中断。
 *
 * ── 怎么用（工具侧）──
 * ```
 * execute = { input ->
 *     coroutineContext[ToolProgress]?.report("正在下载 30%")   // 需要时上报
 *     ...
 * }
 * ```
 * 取不到（例如工具在非托管环境里被直接调用）时为 null，**不报错、无副作用**，
 * 因此接入是可选的：没接入的工具行为与从前完全一致（仍按总时长超时）。
 *
 * ── 为什么用 CoroutineContext 而不是改 execute 签名 ──
 * `Tool.execute` 的签名一旦改动，现有 57 个工具全部要改，且每个新工具都得记住这个参数。
 * 放进协程上下文则：**现有工具零改动**、需要的工具按需取用、不取也没有负担。
 *
 * 注：构造函数是 public 而非 internal —— 因为要用它的 GenerationHandler 在 `app` 模块，
 * 与本 `ai` 模块不是同一个模块，`internal` 在那里不可见。
 */
class ToolProgress constructor(
    private val onActivity: (message: String?) -> Unit,
) : AbstractCoroutineContextElement(ToolProgress) {

    /**
     * 上报一次活动。
     *
     * @param message 可选的、给人看的进度说明（如「已写入 42 MB」）。
     *   会一路传到 UI；传 null 表示「还在跑，但没有可展示的说明」。
     */
    fun report(message: String? = null) = onActivity(message)

    companion object Key : CoroutineContext.Key<ToolProgress>
}

/**
 * 便捷读取：`coroutineContext[ToolProgress]`。
 *
 * 单独提供扩展是为了让工具侧的写法短一些，减少「想上报但懒得取」的情况。
 */
val CoroutineContext.toolProgress: ToolProgress?
    get() = this[ToolProgress]
