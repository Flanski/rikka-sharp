package me.rerere.ai.core

/**
 * Reasoning 回传的诊断日志开关。
 *
 * ── 为什么需要 ──
 * 线上偶发这个 400：
 *
 *     The `reasoning_text` in the thinking mode must be passed back to the API.
 *
 * 它的含义很明确：**思考模式下必须把 `reasoning_text` 回传**。
 * 而我们的 Responses 路径里，某个环节可能只发出了 `encrypted_content` 却没有 `reasoning_text`：
 *
 *     val content = reasoningParts
 *         .filter { it.reasoningType == ReasoningType.REASONING_TEXT }
 *         .filter { it.reasoning.isNotEmpty() }
 *     if (content.isNotEmpty()) { put("content", [...reasoning_text...]) }   // ← 空了就不发
 *     reasoningMetadata?.encryptedContent?.let { put("encrypted_content", it) }  // ← 但这个照发
 *
 * 可问题是：**这条推理链上有多个可能分叉**（part 类型不对、文本为空、metadata 缺失、
 * 或者消息根本没进入上传列表），静态推演无法确定是哪一条，而且它是**概率性**发生的。
 * 因此正确的做法是：**把每次请求实际发出的 reasoning 内容落盘**，等复现时按日志定位。
 *
 * ── 设计 ──
 * 这里只放一个可注入的 sink，`ai` 模块**不依赖 Android / 不做文件 IO**，
 * 由 app 层（[me.rerere.rikkahub.utils.ReasoningTraceFile]）负责写入。
 * 这样 ai 模块保持干净，且日志是否启用完全由上层决定。
 *
 * 未注入 sink 时 [isEnabled] 为 false，所有调用点都会先判断它，
 * 因此**关闭时几乎没有开销**。
 */
object ReasoningTrace {

    /** 日志落点；由 app 层在启动时注入。null = 不记录 */
    @Volatile
    var sink: ((String) -> Unit)? = null

    val isEnabled: Boolean get() = sink != null

    fun log(line: String) {
        sink?.invoke(line)
    }

    /** 只在该行需要拼接较多内容时使用，避免无谓的字符串构造 */
    inline fun logLazy(build: () -> String) {
        if (isEnabled) sink?.invoke(build())
    }
}
