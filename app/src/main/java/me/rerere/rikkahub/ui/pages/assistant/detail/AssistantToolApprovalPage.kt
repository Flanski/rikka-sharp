package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.resolveLocalToolApproval
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 「工具授权」子页面。
 *
 * ── 这里管的是什么 ──
 * 哪些工具**在执行前需要用户批准**。
 *
 * 流程（走 `ToolApprovalState`）：模型发起调用 → 生成暂停 → 弹出授权弹窗
 * （显示模型自己填的「要做什么 / 为什么」）→ 用户点同意/拒绝 → 生成继续。
 *
 * ── 为什么单独一页 ──
 * 它和「有没有这个工具」是两个不同的问题：工具可以开着、但仍要求在动手前问一句。
 * 混在同一个列表里时，用户容易把两者搞混（关掉开关以为只是禁用工具，实际是取消确认）。
 *
 * ── 关于默认值 ──
 * 默认需要批准的是「会改动设备、触碰远程主机、涉及隐私」的几类（见 `LocalToolDefaultApprovals`）。
 * 用户可以逐个关闭 —— **关闭后模型会直接执行**，风险由使用者自行承担。
 * 这一点在文案里写明，不淡化。
 */
@Composable
fun AssistantToolApprovalPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = { parametersOf(id) }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // CardGroup 的 content 非 @Composable → 字符串必须先在 composable 上下文求值
    val tHeader = stringResource(R.string.assistant_page_tool_approval)
    val dHeader = stringResource(R.string.assistant_page_tool_approval_desc)
    val lShell = stringResource(R.string.assistant_page_tool_approval_shell)
    val lSshExec = stringResource(R.string.assistant_page_tool_approval_ssh_exec)
    val lSshUpload = stringResource(R.string.assistant_page_tool_approval_ssh_upload)
    val lSshDownload = stringResource(R.string.assistant_page_tool_approval_ssh_download)
    val lSensors = stringResource(R.string.assistant_page_tool_approval_sensors)
    val onLabel = stringResource(R.string.tool_approval_switch_on)
    val offLabel = stringResource(R.string.tool_approval_switch_off)

    val approvals = assistant.toolApprovalOverrides
    val onToggleApproval: (String, Boolean) -> Unit = { name, required ->
        vm.update(
            assistant.copy(
                toolApprovalOverrides = assistant.toolApprovalOverrides + (name to required)
            )
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.tool_approval_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(innerPadding)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardGroup {
                item(
                    headlineContent = { Text(tHeader) },
                    supportingContent = { Text(dHeader) },
                )
                ApprovalItem("execute_command", lShell, onLabel, offLabel, approvals, onToggleApproval)
                ApprovalItem("ssh_exec", lSshExec, onLabel, offLabel, approvals, onToggleApproval)
                ApprovalItem("ssh_upload", lSshUpload, onLabel, offLabel, approvals, onToggleApproval)
                ApprovalItem("ssh_download", lSshDownload, onLabel, offLabel, approvals, onToggleApproval)
                ApprovalItem("get_sensors", lSensors, onLabel, offLabel, approvals, onToggleApproval)
            }
        }
    }
}

/**
 * 单个工具的授权开关（作为 CardGroup 的一个条目）。
 *
 * 开关打开 = 调用前需用户批准；取值优先用户覆盖，其次默认表（见 [resolveLocalToolApproval]）。
 *
 * ★为什么是 `CardGroupScope` 的扩展、而不是 @Composable 函数：
 * `CardGroup(content: CardGroupScope.() -> Unit)` 的 content **不是 @Composable**，
 * 在其中既不能直接调用 composable，也不能调用其它 @Composable 函数。
 * 因此把 UI 放进 `item(...)` 的 @Composable lambda 里，函数自身保持普通函数。
 */
private fun CardGroupScope.ApprovalItem(
    toolName: String,
    label: String,
    onLabel: String,
    offLabel: String,
    overrides: Map<String, Boolean>,
    onToggle: (String, Boolean) -> Unit,
) {
    val current = resolveLocalToolApproval(toolName, overrides)
    item(
        headlineContent = { Text(label) },
        // 把「当前是哪种行为」直接写出来，而不是只列工具名 ——
        // 开关的两种含义差别很大（要不要打断用户），不写清楚很容易被误解
        supportingContent = { Text("$toolName · ${if (current) onLabel else offLabel}") },
        trailingContent = {
            Switch(
                checked = current,
                onCheckedChange = { onToggle(toolName, it) },
            )
        },
    )
}
