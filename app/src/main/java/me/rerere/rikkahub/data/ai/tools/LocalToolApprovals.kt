package me.rerere.rikkahub.data.ai.tools

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
