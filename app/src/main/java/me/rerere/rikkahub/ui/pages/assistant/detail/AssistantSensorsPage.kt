package me.rerere.rikkahub.ui.pages.assistant.detail

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.SensorCatalog
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.permission.PermissionInfo
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 设备传感器明细页：**一个传感器一个 list item**，可逐个放行。
 *
 * 只列出本机真实存在的传感器（[SensorCatalog.available] 用 SensorManager 实测），
 * 因此没有对应硬件的机型不会出现无效开关。
 *
 * 需要运行时权限的传感器（定位、计步）在打开开关时走与「日历」一致的权限流程：
 * 先弹说明对话框 → 系统授权 → 若被永久拒绝则引导到应用设置页。
 */
@Composable
fun AssistantSensorsPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = { parametersOf(id) }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // 本机实际存在的传感器（设备硬件不会变，remember 一次即可）
    val entries = remember(context) { SensorCatalog.available(context) }

    // 注意：stringResource 是 @Composable，只能在 composable 上下文求值。
    // 下面 CardGroup 的 DSL 与 buildList/forEach 的 lambda 都**不是** composable 上下文，
    // 所以在函数体这里先把字符串取好再传进去。
    val permissionRequiredText = stringResource(R.string.sensor_permission_required)

    val locationPermission = rememberPermissionState(
        permissions = setOf(
            PermissionInfo(
                permission = Manifest.permission.ACCESS_FINE_LOCATION,
                displayName = { Text(stringResource(R.string.sensor_permission_location)) },
                usage = { Text(stringResource(R.string.sensor_permission_location_desc)) },
                required = true,
            ),
            PermissionInfo(
                permission = Manifest.permission.ACCESS_COARSE_LOCATION,
                displayName = { Text(stringResource(R.string.sensor_permission_location)) },
                usage = { Text(stringResource(R.string.sensor_permission_location_desc)) },
                required = true,
            ),
        )
    )
    PermissionManager(permissionState = locationPermission)

    // 计步传感器在 Android 10+ 需要 ACTIVITY_RECOGNITION
    val activityPermission = rememberPermissionState(
        permissions = setOf(
            PermissionInfo(
                permission = Manifest.permission.ACTIVITY_RECOGNITION,
                displayName = { Text(stringResource(R.string.sensor_permission_activity_recognition)) },
                usage = { Text(stringResource(R.string.sensor_permission_activity_recognition_desc)) },
                required = true,
            ),
        )
    )
    PermissionManager(permissionState = activityPermission)

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.sensor_page_title)) },
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
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.sensor_page_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.sensor_page_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                CardGroup {
                    // ── 第一个选项：传感器工具总开关 ──
                    // 它控制的是「AI 能不能用传感器工具」（LocalToolOption.Sensors），
                    // 与下面每个传感器的开关是**两层**：
                    //   · 总开关关 → 整个 get_sensors 工具不注册，AI 完全看不到它
                    //   · 总开关开 → 工具可用，但只有被单独放行的传感器才会真正上报数据
                    // 放在第一个位置是因为它是这一页的「前提」——关掉时下面的开关没有意义。
                    item(
                        headlineContent = { Text(stringResource(R.string.sensor_page_master_switch)) },
                        supportingContent = { Text(stringResource(R.string.sensor_page_master_switch_desc)) },
                        trailingContent = {
                            Switch(
                                checked = assistant.localTools.contains(LocalToolOption.Sensors),
                                onCheckedChange = { enabled ->
                                    val newTools = if (enabled) {
                                        assistant.localTools + LocalToolOption.Sensors
                                    } else {
                                        assistant.localTools - LocalToolOption.Sensors
                                    }
                                    vm.update(assistant.copy(localTools = newTools))
                                }
                            )
                        }
                    )
                    entries.forEach { entry ->
                        // 权限是否已授予（读取 Compose 状态，权限变化后会自动重组）
                        val granted = when (entry.permission) {
                            null -> true
                            Manifest.permission.ACCESS_FINE_LOCATION -> locationPermission.allPermissionsGranted
                            Manifest.permission.ACTIVITY_RECOGNITION -> activityPermission.allPermissionsGranted
                            else -> SensorCatalog.hasPermission(context, entry)
                        }
                        val info = buildList {
                            entry.hardwareName?.takeIf { it.isNotBlank() }?.let { add(it) }
                            if (!granted) add(permissionRequiredText)
                        }.joinToString(" · ")

                        item(
                            headlineContent = { Text(stringResource(entry.nameRes)) },
                            // 始终提供 supporting 槽，内部按需渲染（避免条件式 composable lambda 的类型推断问题）
                            supportingContent = {
                                if (info.isNotEmpty()) Text(info)
                            },
                            trailingContent = {
                                Switch(
                                    checked = entry.key in assistant.enabledSensors,
                                    onCheckedChange = { enabled ->
                                        val needLocation = enabled &&
                                            entry.permission == Manifest.permission.ACCESS_FINE_LOCATION &&
                                            !locationPermission.allPermissionsGranted
                                        val needActivity = enabled &&
                                            entry.permission == Manifest.permission.ACTIVITY_RECOGNITION &&
                                            !activityPermission.allPermissionsGranted
                                        when {
                                            needLocation -> locationPermission.requestPermissions()
                                            needActivity -> activityPermission.requestPermissions()
                                            else -> {
                                                val next = if (enabled) {
                                                    assistant.enabledSensors + entry.key
                                                } else {
                                                    assistant.enabledSensors - entry.key
                                                }
                                                vm.update(assistant.copy(enabledSensors = next))
                                            }
                                        }
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}
