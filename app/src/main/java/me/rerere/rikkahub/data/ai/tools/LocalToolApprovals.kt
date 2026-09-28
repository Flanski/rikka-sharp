package me.rerere.rikkahub.data.ai.tools

// ★注意：put(...) 是 JsonObjectBuilder 的**成员函数**，必须用短名调用 ——
//   写 kotlinx.serialization.json.put(...) 会报 Unresolved reference
//   （成员函数不属于包级命名空间，无法用全限定名调用）。
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 本地工具（`LocalToolOption`）的**授权默认值**。
 *
 * 与工作区的做法保持一致（见 `WorkspaceTools.kt` 的 `WorkspaceToolDefaultApprovals`）：
 *   默认值 → 用户覆盖 → `false` 兜底
 *
 * ── 为什么需要授权 ──
 * 部分本地工具会对设备产生**不可逆或涉及隐私**的影响，让模型不经确认就调用并不合适：
 *  · `execute_command` —— 在 App 沙箱内执行任意 shell 命令
 *  · `ssh_exec` / `ssh_upload` / `ssh_download` —— 对**远程**主机做写操作
 *  · `get_sensors` —— 读取设备传感器（隐私：位置/加速度等）
 *
 * ── 语义 ──
 * `true` = 调用前必须经用户批准（`ToolApprovalState.Pending` → 用户点同意/拒绝 → 恢复生成）
 * `false` = 直接执行
 *
 * ── 注意 ──
 * 这些是**默认值**，用户可在「助手 → 本地工具」页逐个覆盖。
 * 覆盖项里若显式写 `true` 即强制要求授权；写 `false` 即关闭授权
 * （关闭属于用户自己的决定，风险自担）。
 *
 * 只读类工具（`ssh_hosts` / `ssh_ls`、`file_*`、`time_info` 等）不在表中 → 默认无需授权。
 */
val LocalToolDefaultApprovals: Map<String, Boolean> = mapOf(
    // 本地 shell：可能做危险操作（删除、覆盖、上传数据）
    "execute_command" to true,
    // SSH：对远程主机的写操作
    "ssh_exec" to true,
    "ssh_upload" to true,
    "ssh_download" to true,
    // 传感器：涉及隐私（位置、运动、环境）
    "get_sensors" to true,
)

/**
 * 解析某个工具是否需要授权。
 *
 * 优先级：用户覆盖 > 默认表 > false
 * （与 `resolveWorkspaceToolApproval` 完全同构，便于理解与维护。）
 */
fun resolveLocalToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: LocalToolDefaultApprovals[name] ?: false

/**
 * 生成一个 `needsApproval` 判定函数。
 *
 * `Tool.needsApproval` 的参数是工具的输入 JSON —— 这里**不使用**它，
 * 即「整个工具统一要求授权」。
 *
 * 之所以不做「按参数动态判定」（例如只在命令含 `rm -rf` 时才要求授权）：
 * 命令的破坏性很难用模式穷尽（管道、变量展开、base64 编码等都能绕过），
 * 一旦漏判就等于形同虚设，反而给人虚假的安全感。
 */
fun localToolApprovalChecker(overrides: Map<String, Boolean>): (String) -> Boolean =
    { name -> resolveLocalToolApproval(name, overrides) }

/**
 * 工具执行的统一保护。
 *
 * ── 为什么需要 ──
 * `Tool.execute` 若抛出**未捕获异常**，会向上冒泡并**中断整轮生成** ——
 * 这比「返回一条结构化错误」严重得多：用户看到的是生成失败，而不是「这个工具没成功」。
 *
 * 而工具要调用的都是系统 API（蓝牙、WiFi、短信、USB…），它们**任何一处都可能抛**：
 * 权限被拒、服务不可用、设备被拔出、厂商 ROM 行为差异……
 * 与其在每个调用点都记得包 try/catch（迟早会漏），不如在出口统一兜住。
 *
 * 兜住后模型总能拿到可理解的反馈，可以据此换策略或如实告知用户。
 */
internal fun guarded(
    toolName: String,
    block: () -> kotlinx.serialization.json.JsonObject,
): List<me.rerere.ai.ui.UIMessagePart> = try {
    listOf(me.rerere.ai.ui.UIMessagePart.Text(block().toString()))
} catch (t: Throwable) {
    listOf(
        me.rerere.ai.ui.UIMessagePart.Text(
            kotlinx.serialization.json.buildJsonObject {
                put("ok", JsonPrimitive(false))
                put("tool", JsonPrimitive(toolName))
                put("error", JsonPrimitive("${t.javaClass.simpleName}: ${t.message}"))
                put("hint", JsonPrimitive("工具内部出现未预期的错误（不是权限提示）。该信息可用于反馈问题。"))
            }.toString()
        )
    )
}
