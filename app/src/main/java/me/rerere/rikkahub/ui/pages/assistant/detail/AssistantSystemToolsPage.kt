package me.rerere.rikkahub.ui.pages.assistant.detail

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
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

    fun toggle(option: LocalToolOption, enabled: Boolean) {
        val newTools = if (enabled) assistant.localTools + option else assistant.localTools - option
        vm.update(assistant.copy(localTools = newTools))
    }

    // ── 权限状态（用于在条目下给出提示，说明为什么可能读不到数据）──
    val permissionHints = remember(context) {
        fun granted(p: String) =
            ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        val sms = granted(Manifest.permission.READ_SMS)
        val bt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_CONNECT)
        } else true
        val wifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            granted(Manifest.permission.NEARBY_WIFI_DEVICES) || granted(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            granted(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        Triple(sms, bt, wifi)
    }

    // @Composable 求值必须在 CardGroup 之前
    val tWifi = stringResource(R.string.system_wifi)
    val dWifi = stringResource(R.string.system_wifi_desc)
    val tBt = stringResource(R.string.system_bluetooth)
    val dBt = stringResource(R.string.system_bluetooth_desc)
    val tSms = stringResource(R.string.system_sms)
    val dSms = stringResource(R.string.system_sms_desc)
    val tBat = stringResource(R.string.system_battery)
    val dBat = stringResource(R.string.system_battery_desc)
    val grantedText = stringResource(R.string.system_permission_granted)
    val missingSms = stringResource(R.string.system_permission_missing_sms)
    val missingBt = stringResource(R.string.system_permission_missing_bt)
    val missingWifi = stringResource(R.string.system_permission_missing_wifi)

    val (smsGranted, btGranted, wifiGranted) = permissionHints

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
            }
            Text(
                text = stringResource(R.string.system_readonly_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
