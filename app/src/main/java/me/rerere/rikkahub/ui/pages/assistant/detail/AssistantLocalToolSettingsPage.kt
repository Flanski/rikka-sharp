package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 「本地工具设置」子页面。
 *
 * ── 为什么要单独开一页 ──
 * 这些是**全局性的执行参数**（并发、各种上限与超时），与「有哪些工具可用」是两类东西。
 * 原先它们和工具开关混在同一个列表里，视觉上分不清「这是在调参数」还是「这是在开关工具」；
 * 把它们收进独立子页面后，主列表就只剩「工具/分类」这一类条目，结构更清楚。
 *
 * ── 关于「单工具执行超时」的含义（与以往不同了）──
 * 现在它不再是「整个工具最多跑多久」，而是「**连续多久没有任何活动**才判定卡死」
 * （见 `GenerationHandler.executeToolWithWatchdog` 与 `ToolProgress`）。
 * 也就是说：会定期上报进度的工具（如下载）**不会**因为总时长超限被误杀，
 * 只有真正卡住的才会被中断。文案里按这个新语义描述，避免用户按旧理解去调大它。
 */
@Composable
fun AssistantLocalToolSettingsPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = { parametersOf(id) }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // CardGroup 的 content 是 `CardGroupScope.() -> Unit`（**非 @Composable**），
    // 所以 stringResource 必须在进入 CardGroup 之前求值。
    val tParallel = stringResource(R.string.local_tool_settings_parallel)
    val dParallel = stringResource(R.string.local_tool_settings_parallel_desc)
    val tRecurring = stringResource(R.string.local_tool_settings_recurring)
    val dRecurring = stringResource(R.string.local_tool_settings_recurring_desc)
    val tTotalSteps = stringResource(R.string.local_tool_settings_total_steps)
    val dTotalSteps = stringResource(R.string.local_tool_settings_total_steps_desc)
    val tToolTimeout = stringResource(R.string.local_tool_settings_tool_timeout)
    val dToolTimeout = stringResource(R.string.local_tool_settings_tool_timeout_desc)
    val tJsTimeout = stringResource(R.string.local_tool_settings_js_timeout)
    val dJsTimeout = stringResource(R.string.local_tool_settings_js_timeout_desc)
    val tShellTimeout = stringResource(R.string.local_tool_settings_shell_timeout)
    val dShellTimeout = stringResource(R.string.local_tool_settings_shell_timeout_desc)

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.local_tool_settings_title)) },
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
                    headlineContent = { Text(tParallel) },
                    supportingContent = { Text(dParallel) },
                    trailingContent = {
                        Switch(
                            checked = assistant.enableParallelToolExecution,
                            onCheckedChange = { vm.update(assistant.copy(enableParallelToolExecution = it)) }
                        )
                    }
                )
                NumberSettingItem(
                    title = tRecurring,
                    desc = dRecurring,
                    value = assistant.toolRecurringLimit.toString(),
                    onValueChange = { v ->
                        v.toIntOrNull()?.let { vm.update(assistant.copy(toolRecurringLimit = it)) }
                    },
                )
                NumberSettingItem(
                    title = tTotalSteps,
                    desc = dTotalSteps,
                    value = assistant.totalStepsLimit.toString(),
                    onValueChange = { v ->
                        v.toIntOrNull()?.let { vm.update(assistant.copy(totalStepsLimit = it)) }
                    },
                )
                NumberSettingItem(
                    title = tToolTimeout,
                    desc = dToolTimeout,
                    value = assistant.toolExecTimeout.toString(),
                    onValueChange = { v ->
                        v.toIntOrNull()?.let { vm.update(assistant.copy(toolExecTimeout = it)) }
                    },
                )
                NumberSettingItem(
                    title = tJsTimeout,
                    desc = dJsTimeout,
                    value = assistant.jsTimeout.toString(),
                    onValueChange = { v ->
                        v.toIntOrNull()?.let { vm.update(assistant.copy(jsTimeout = it)) }
                    },
                )
                NumberSettingItem(
                    title = tShellTimeout,
                    desc = dShellTimeout,
                    value = assistant.shellTimeout.toString(),
                    onValueChange = { v ->
                        v.toIntOrNull()?.let { vm.update(assistant.copy(shellTimeout = it)) }
                    },
                )
            }
        }
    }
}

/**
 * 一个「标题 + 说明 + 数字输入框」的设置条目。
 *
 * ★必须做成 [CardGroupScope] 的扩展函数，而不是普通的 @Composable：
 * `CardGroup { ... }` 的 content 类型是 `CardGroupScope.() -> Unit`，**不是 composable 上下文**，
 * 因此在里面直接调用 Composable 函数会编译失败
 * （报 `@Composable invocations can only happen from the context of a @Composable function`）。
 * 做成 CardGroupScope 扩展后，函数体内部可以调用 `item(...)`，
 * 而 `item` 的 `headlineContent` / `trailingContent` 等参数**是** composable lambda，
 * 延迟到渲染时执行 —— 那里才是可以放 Text / OutlinedTextField 的地方。
 *
 * （这个坑在本项目里出现过多次：判据是 **lambda 的类型**，而不是「代码写在哪里」。）
 */
private fun CardGroupScope.NumberSettingItem(
    title: String,
    desc: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    item(
        headlineContent = { Text(title) },
        supportingContent = { Text(desc) },
        trailingContent = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.width(70.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
    )
}
