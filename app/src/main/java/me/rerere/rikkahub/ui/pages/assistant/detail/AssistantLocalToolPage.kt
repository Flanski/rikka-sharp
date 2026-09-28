package me.rerere.rikkahub.ui.pages.assistant.detail

import android.Manifest
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.permission.PermissionInfo
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.hasUsageStatsPermission
import me.rerere.rikkahub.utils.openUsageAccessSettings
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantLocalToolPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_local_tools))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantLocalToolContent(
            innerPadding = innerPadding,
            assistant = assistant,
            settings = settings,
            scope = scope,
            onUpdate = { vm.update(it) },
        )
    }
}

@Composable
private fun AssistantLocalToolContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    settings: Settings,
    scope: kotlinx.coroutines.CoroutineScope,
    onUpdate: (Assistant) -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val nav = LocalNavController.current
    val permissionRequiredText =
        stringResource(R.string.assistant_page_local_tools_screen_time_permission_required)

    val calendarPermissionState = rememberPermissionState(
        permissions = setOf(
            PermissionInfo(
                permission = Manifest.permission.READ_CALENDAR,
                displayName = { Text(stringResource(R.string.permission_calendar_read)) },
                usage = { Text(stringResource(R.string.permission_calendar_read_desc)) },
                required = true
            ),
            PermissionInfo(
                permission = Manifest.permission.WRITE_CALENDAR,
                displayName = { Text(stringResource(R.string.permission_calendar_write)) },
                usage = { Text(stringResource(R.string.permission_calendar_write_desc)) },
                required = true
            ),
        )
    )
    PermissionManager(permissionState = calendarPermissionState)

    fun toggleLocalTool(option: LocalToolOption, enabled: Boolean) {
        if (enabled && option == LocalToolOption.ScreenTime && !context.hasUsageStatsPermission()) {
            toaster.show(message = permissionRequiredText, type = ToastType.Warning)
            context.openUsageAccessSettings()
        }
        if (enabled && option == LocalToolOption.Calendar && !calendarPermissionState.allPermissionsGranted) {
            calendarPermissionState.requestPermissions()
            return
        }
        val newLocalTools = if (enabled) {
            assistant.localTools + option
        } else {
            assistant.localTools - option
        }
        onUpdate(assistant.copy(localTools = newLocalTools))
    }

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
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_javascript_engine_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_javascript_engine_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.JavascriptEngine),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.JavascriptEngine, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_time_info_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_time_info_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.TimeInfo),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.TimeInfo, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_clipboard_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_clipboard_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.Clipboard),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Clipboard, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_tts_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_tts_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.Tts),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Tts, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_ask_user_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_ask_user_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.AskUser),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.AskUser, it) }
                    )
                }
            )
            // ── 以下三项此前**有枚举、无开关** ──
            // 也就是说：它们的注册逻辑取决于 assistant.localTools 里有没有这项，
            // 但界面上没有任何入口能增删它 —— 用户既看不到、也改不了。
            // TaskTools 与 Calculator 还在 Assistant 的默认列表里（默认开启），
            // 结果就是「默认在跑、却找不到地方关」。
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_task_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_task_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.TaskTools),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.TaskTools, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_calculator_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_calculator_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.Calculator),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Calculator, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_present_file_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_present_file_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.PresentFile),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.PresentFile, it) }
                    )
                }
            )
            item(
                headlineContent = { Text("Python 引擎") },
                supportingContent = { Text("允许 AI 执行 Python 代码处理数据、调用 API、生成文件") },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.PythonEngine),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.PythonEngine, it) }
                    )
                }
            )
            item(
                headlineContent = { Text("文件工具") },
                supportingContent = { Text("允许 AI 读取、写入、搜索设备文件") },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.FileTools),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.FileTools, it) }
                    )
                }
            )
            item(
                headlineContent = { Text("Shell 命令") },
                supportingContent = { Text("允许 AI 执行 shell 命令") },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.ShellTools),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.ShellTools, it) }
                    )
                }
            )
            item(
                headlineContent = { Text("SSH 客户端") },
                supportingContent = { Text("允许 AI 通过 SSH 操作你配置的远程主机（主机与密钥在「设置 → SSH 客户端」中管理）") },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.SshClient),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.SshClient, it) }
                    )
                }
            )
            item(
                headlineContent = { Text("数据库查询") },
                supportingContent = { Text("允许 AI 查询本地数据库（对话记录/设置）") },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.DatabaseQuery),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.DatabaseQuery, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_screen_time_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_screen_time_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.ScreenTime),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.ScreenTime, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_calendar_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_calendar_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.Calendar),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Calendar, it) }
                    )
                }
            )
            // 设备传感器：**纯入口**（无总开关）—— 总开关在子页面的第一个选项里。
            //
            // 为什么把总开关挪走：与「编程开发工具」「系统工具」保持同一种交互 ——
            // 主列表只负责「进哪个分类」，具体开关一律在分类页内部。
            // 之前这个条目同时承担「开关」与「进入」两种操作，容易误触
            // （想进去看明细，结果把总开关关了）。
            item(
                onClick = { nav.navigate(Screen.AssistantSensors(assistant.id.toString())) },
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_sensors))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_sensors_desc))
                },
                trailingContent = {
                    Icon(HugeIcons.ArrowRight01, contentDescription = null)
                }
            )
            // 编程开发工具：纯入口（无总开关）—— 它是分类容器，具体工具在子页面里各自开关
            item(
                onClick = { nav.navigate(Screen.AssistantProgrammingTools(assistant.id.toString())) },
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_programming_tools))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_programming_tools_desc))
                },
                trailingContent = {
                    Icon(HugeIcons.ArrowRight01, contentDescription = null)
                }
            )
            // 系统工具：纯入口（无总开关）—— WiFi / 蓝牙 / 短信 / 电池
            item(
                onClick = { nav.navigate(Screen.AssistantSystemTools(assistant.id.toString())) },
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_system_tools))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_system_tools_desc))
                },
                trailingContent = {
                    Icon(HugeIcons.ArrowRight01, contentDescription = null)
                }
            )
            // 本地工具设置：纯入口（无开关）—— 并行执行、各类上限与超时
            item(
                onClick = { nav.navigate(Screen.AssistantLocalToolSettings(assistant.id.toString())) },
                headlineContent = {
                    Text(stringResource(R.string.local_tool_settings_entry))
                },
                supportingContent = {
                    Text(stringResource(R.string.local_tool_settings_entry_desc))
                },
                trailingContent = {
                    Icon(HugeIcons.ArrowRight01, contentDescription = null)
                }
            )
            // 工具授权：纯入口（无开关）—— 哪些工具执行前需用户确认
            item(
                onClick = { nav.navigate(Screen.AssistantToolApproval(assistant.id.toString())) },
                headlineContent = {
                    Text(stringResource(R.string.tool_approval_entry))
                },
                supportingContent = {
                    Text(stringResource(R.string.tool_approval_entry_desc))
                },
                trailingContent = {
                    Icon(HugeIcons.ArrowRight01, contentDescription = null)
                }
            )
            // 下载类工具：HTTP 下载 / 仓库快照下载，支持同步与异步两种模式
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_download))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_download_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.DownloadTools),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.DownloadTools, it) }
                    )
                }
            )
        }

    }
}
