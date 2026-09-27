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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 「编程开发工具」子页面。
 *
 * 入口位于「助手 → 本地工具」，是**纯入口项**（无总开关）——
 * 因为它是若干独立工具的**分类容器**，而不是一个能力本身。
 * 真正的开关在页面内逐个工具上。
 *
 * 当前包含（后续批次继续加入）：
 *  · Git 工具 —— 真正的 git（JGit）：克隆含历史、状态、日志、差异、提交、分支、拉取/推送
 *
 * 规划中：语法检查器、串口工具（USB，HEX/文本两种显示模式）等。
 *
 * ★注意：CardGroup 的 content 是 `CardGroupScope.() -> Unit`（**非 @Composable**），
 * 所以 stringResource 等 @Composable 必须在 CardGroup **之前**求值，
 * 条目也要做成 CardGroupScope 的扩展函数。
 */
@Composable
fun AssistantProgrammingToolsPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = { parametersOf(id) }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun toggle(option: LocalToolOption, enabled: Boolean) {
        val newTools = if (enabled) {
            assistant.localTools + option
        } else {
            assistant.localTools - option
        }
        vm.update(assistant.copy(localTools = newTools))
    }

    // @Composable 求值必须在 CardGroup 之前
    val gitTitle = stringResource(R.string.programming_git_tools)
    val gitDesc = stringResource(R.string.programming_git_tools_desc)
    val gitChecked = assistant.localTools.contains(LocalToolOption.GitTools)

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.programming_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
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
                    headlineContent = { Text(gitTitle) },
                    supportingContent = { Text(gitDesc) },
                    trailingContent = {
                        Switch(
                            checked = gitChecked,
                            onCheckedChange = { toggle(LocalToolOption.GitTools, it) },
                        )
                    },
                )
            }
        }
    }
}
