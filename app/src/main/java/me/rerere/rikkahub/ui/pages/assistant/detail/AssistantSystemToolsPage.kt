package me.rerere.rikkahub.ui.pages.assistant.detail

import android.Manifest
import android.os.Build
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.permission.PermissionInfo
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf


/**
 * 「系统工具」子页面：WiFi / 蓝牙 / 短信 / 电池。
 *
 * 入口在「助手 → 本地工具」，与「编程开发工具」一样是**纯入口项**（无总开关），
 * 具体能力在页面内各自开关。
 *
 * ── 关于权限 ──
 * 这三项需要运行时权限，且各 Android 版本要求不同：
 *  · WiFi 的 SSID/BSSID —— Android 13+ 要 NEARBY_WIFI_DEVICES；旧版要位置权限
 *  · 蓝牙设备列表 —— Android 12+ 要 BLUETOOTH_CONNECT
 *  · 短信 —— READ_SMS（危险权限）
 * 因此每个条目在**未授权时显示状态提示**；工具本身也会在缺权限时返回明确错误，
 * 而不是静默返回空结果。
 *
 * ★注意：CardGroup 的 content 是 `CardGroupScope.() -> Unit`（**非 @Composable**），
 * 所以 stringResource 等必须在 CardGroup **之前**求值。
 */
@Composable
fun AssistantSystemToolsPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = { parametersOf(id) }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // ── 运行时权限 ──
    //
    // 为什么必须有这块：没有它，用户在「缺权限」时看到提示却**没有任何入口去授予** ——
    // 与我们刚修掉的蓝牙缺陷同一类问题（用户侧无法自助修复）。
    //
    // 各版本所需的权限名不同，按版本选择；旧版本选到的是 normal 权限
    // （清单声明即视为已授予），因此 allPermissionsGranted 为 true、不会弹窗 —— 安全。

    /** 短信：READ_SMS 在所有版本都是危险权限，必须运行时申请 */
    val smsPermission = rememberPermissionState(
        permissions = setOf(
            PermissionInfo(
                permission = Manifest.permission.READ_SMS,
                displayName = { Text(stringResource(R.string.system_permission_sms_name)) },
                usage = { Text(stringResource(R.string.system_permission_sms_usage)) },
                required = true,
            )
        )
    )
    PermissionManager(permissionState = smsPermission)

    /** 蓝牙：Android 12+ 用 BLUETOOTH_CONNECT；更早是 normal 权限（BLUETOOTH） */
    val bluetoothPermission = rememberPermissionState(
        permissions = setOf(
            PermissionInfo(
                permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Manifest.permission.BLUETOOTH_CONNECT
                } else {
                    @Suppress("DEPRECATION")
                    Manifest.permission.BLUETOOTH
                },
                displayName = { Text(stringResource(R.string.system_permission_bt_name)) },
                usage = { Text(stringResource(R.string.system_permission_bt_usage)) },
                required = true,
            )
        )
    )
    PermissionManager(permissionState = bluetoothPermission)

    /**
     * 通知：**仅 Android 13(T)+ 需要** POST_NOTIFICATIONS；
     * 更早的系统上该权限不存在，声明了也不会请求 —— 此时视为已授予。
     */
    val notificationPermission = rememberPermissionState(
        permissions = setOf(
            PermissionInfo(
                permission = Manifest.permission.POST_NOTIFICATIONS,
                displayName = { Text(stringResource(R.string.system_permission_notif_name)) },
                usage = { Text(stringResource(R.string.system_permission_notif_usage)) },
                required = true,
            )
        )
    )
    PermissionManager(permissionState = notificationPermission)

    /** WiFi：Android 13+ 用 NEARBY_WIFI_DEVICES；更早用位置权限 */
    val wifiPermission = rememberPermissionState(
        permissions = setOf(
            PermissionInfo(
                permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.NEARBY_WIFI_DEVICES
                } else {
                    Manifest.permission.ACCESS_FINE_LOCATION
                },
                displayName = { Text(stringResource(R.string.system_permission_wifi_name)) },
                usage = { Text(stringResource(R.string.system_permission_wifi_usage)) },
                required = true,
            )
        )
    )
    PermissionManager(permissionState = wifiPermission)

    fun toggle(option: LocalToolOption, enabled: Boolean) {
        // 打开开关时，若对应权限尚未授予则先申请 —— 让用户当场就能授权，
        // 而不是打开后才发现工具用不了。
        if (enabled) {
            when (option) {
                LocalToolOption.Sms ->
                    if (!smsPermission.allPermissionsGranted) smsPermission.requestPermissions()
                LocalToolOption.Bluetooth ->
                    if (!bluetoothPermission.allPermissionsGranted) bluetoothPermission.requestPermissions()
                LocalToolOption.Wifi ->
                    if (!wifiPermission.allPermissionsGranted) wifiPermission.requestPermissions()
                LocalToolOption.Notification ->
                    // Android 13 以下没有这个权限，checkSelfPermission 会一直返回"已授予"，
                    // 因此这里不需要额外的版本判断
                    if (!notificationPermission.allPermissionsGranted) {
                        notificationPermission.requestPermissions()
                    }
                else -> Unit
            }
        }
        val newTools = if (enabled) assistant.localTools + option else assistant.localTools - option
        vm.update(assistant.copy(localTools = newTools))
    }

    // ── 权限状态 ──
    // ★用 PermissionState 的**响应式**状态，而不是「构造时用 checkSelfPermission 取一次快照」：
    //   快照在用户刚授予权限后不会更新，页面会继续显示「缺少权限」—— 与实际不符。
    //   （这与本次修掉的蓝牙 permission_granted 误报是同一类错误：状态必须反映真实情况。）
    val smsGranted = smsPermission.allPermissionsGranted
    val btGranted = bluetoothPermission.allPermissionsGranted
    val wifiGranted = wifiPermission.allPermissionsGranted
    val notificationGranted = notificationPermission.allPermissionsGranted

    // @Composable 求值必须在 CardGroup 之前
    val tWifi = stringResource(R.string.system_wifi)
    val dWifi = stringResource(R.string.system_wifi_desc)
    val tBt = stringResource(R.string.system_bluetooth)
    val dBt = stringResource(R.string.system_bluetooth_desc)
    val tSms = stringResource(R.string.system_sms)
    val dSms = stringResource(R.string.system_sms_desc)
    val tNotification = stringResource(R.string.system_notification)
    val dNotification = if (notificationGranted) {
        stringResource(R.string.system_notification_desc)
    } else {
        stringResource(R.string.system_notification_desc_no_permission)
    }
    val tBat = stringResource(R.string.system_battery)
    val dBat = stringResource(R.string.system_battery_desc)
    val grantedText = stringResource(R.string.system_permission_granted)
    val missingSms = stringResource(R.string.system_permission_missing_sms)
    val missingBt = stringResource(R.string.system_permission_missing_bt)
    val missingWifi = stringResource(R.string.system_permission_missing_wifi)

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.system_page_title)) },
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
                    headlineContent = { Text(tWifi) },
                    supportingContent = { Text(if (wifiGranted) dWifi else "$dWifi\n$missingWifi") },
                    trailingContent = {
                        Switch(
                            checked = assistant.localTools.contains(LocalToolOption.Wifi),
                            onCheckedChange = { toggle(LocalToolOption.Wifi, it) },
                        )
                    },
                )
                item(
                    headlineContent = { Text(tBt) },
                    supportingContent = { Text(if (btGranted) dBt else "$dBt\n$missingBt") },
                    trailingContent = {
                        Switch(
                            checked = assistant.localTools.contains(LocalToolOption.Bluetooth),
                            onCheckedChange = { toggle(LocalToolOption.Bluetooth, it) },
                        )
                    },
                )
                item(
                    headlineContent = { Text(tSms) },
                    supportingContent = { Text(if (smsGranted) dSms else "$dSms\n$missingSms") },
                    trailingContent = {
                        Switch(
                            checked = assistant.localTools.contains(LocalToolOption.Sms),
                            onCheckedChange = { toggle(LocalToolOption.Sms, it) },
                        )
                    },
                )
                item(
                    headlineContent = { Text(tBat) },
                    supportingContent = { Text(dBat) },
                    trailingContent = {
                        Switch(
                            checked = assistant.localTools.contains(LocalToolOption.Battery),
                            onCheckedChange = { toggle(LocalToolOption.Battery, it) },
                        )
                    },
                )
                item(
                    headlineContent = { Text(tNotification) },
                    supportingContent = { Text(dNotification) },
                    trailingContent = {
                        Switch(
                            checked = assistant.localTools.contains(LocalToolOption.Notification),
                            onCheckedChange = { toggle(LocalToolOption.Notification, it) },
                        )
                    },
                )
            }
            Text(
                text = stringResource(R.string.system_readonly_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
