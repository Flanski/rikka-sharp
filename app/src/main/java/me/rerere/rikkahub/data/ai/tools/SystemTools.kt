package me.rerere.rikkahub.data.ai.tools

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private const val TAG = "SystemTools"

/**
 * 系统工具：WiFi / 蓝牙 / 短信。
 *
 * ── 统一的原则 ──
 * 这些能力都要**运行时权限**，而且不同 Android 版本的权限模型差别很大
 * （例如读 WiFi SSID 在 13+ 需要 NEARBY_WIFI_DEVICES，蓝牙设备列表在 12+ 需要 BLUETOOTH_CONNECT）。
 * 因此每个字段各自 try/catch：**能读的读出来，读不了的明确说明缺什么权限**
 * —— 而不是整体失败，也不是返回空值让模型误以为「设备就是这样」。
 *
 * ── 蓝牙连接控制的实际限制（重要，已在工具描述里写明）──
 * 普通应用**无法程序化地连接/断开蓝牙音频设备**：
 * `BluetoothA2dp.connect()` / `BluetoothHeadset.connect()` 属于 hidden/SystemApi，
 * 需要系统权限（BLUETOOTH_PRIVILEGED），反射调用在新版本上也被限制。
 * 所以这里提供的是「查看设备与连接状态」+「打开系统蓝牙设置页由用户手动连接」，
 * 并如实告诉调用方为什么不能直接连。
 */
fun createWifiTool(context: Context): Tool = Tool(
    name = "wifi_info",
    description = """
        Read the device's WiFi state: whether WiFi is on, and (when permitted) the current
        connection's SSID, BSSID, IP address, frequency, link speed and signal level.
        Set include_scan=true to also list nearby networks — that requires location permission
        (Android 12 and below) or NEARBY_WIFI_DEVICES (Android 13+), plus the system location toggle
        being on. If unavailable, the response carries "missing_permissions" and explains why,
        instead of silently returning nothing.
        PERMISSIONS: reading the current connection's SSID/BSSID needs the same location/nearby-wifi
        permission; without it those fields are simply omitted (the network may still be connected).
        Read-only.
    """.trimIndent().replace("\n", " ").replace(Regex(" +"), " "),
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("include_scan", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Also scan for nearby networks (needs location permission + location on)")
                })
            },
        )
    },
    execute = { args ->
        val includeScan = args.jsonObject["include_scan"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() ?: false
        guarded("wifi_info") { wifiSnapshot(context, includeScan) }
    },
)

@Suppress("DEPRECATION")
private fun wifiSnapshot(context: Context, includeScan: Boolean): kotlinx.serialization.json.JsonObject {
    val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    return buildJsonObject {
        if (wm == null) {
            put("error", JsonPrimitive("本设备没有 WiFi 服务"))
            return@buildJsonObject
        }
        put("wifi_enabled", JsonPrimitive(runCatching { wm.isWifiEnabled }.getOrDefault(false)))

        // ── 当前连接信息 ──
        // Android 13+ 读 SSID/BSSID 需要 NEARBY_WIFI_DEVICES；旧版本需要位置权限。
        // connectionInfo 在 API 31 起被标记废弃，但在可获取时仍返回有效数据；
        // 权限不足时字段会是 <unknown ssid>，因此这里额外检查占位值。
        runCatching {
            val info = wm.connectionInfo
            val ssid = info?.ssid?.removeSurrounding("\"")?.takeIf {
                it.isNotBlank() && !it.contains("unknown", true) && it != "0x"
            }
            val bssid = info?.bssid?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" }

            put("connected", JsonPrimitive(ssid != null || bssid != null))
            if (ssid != null) put("ssid", JsonPrimitive(ssid))
            if (bssid != null) put("bssid", JsonPrimitive(bssid))
            info?.let { i ->
                if (i.ipAddress != 0) {
                    put("ip_address", JsonPrimitive(intToIp(i.ipAddress)))
                }
                if (i.frequency > 0) put("frequency_mhz", JsonPrimitive(i.frequency))
                if (i.linkSpeed > 0) put("link_speed_mbps", JsonPrimitive(i.linkSpeed))
                if (i.rssi != 0) {
                    put("rssi_dbm", JsonPrimitive(i.rssi))
                    // 0..4 档，便于直接呈现「信号强弱」
                    runCatching { WifiManager.calculateSignalLevel(i.rssi, 5) }
                        .getOrNull()?.let { put("signal_level", JsonPrimitive("$it/4")) }
                }
            }
            if (ssid == null && bssid == null) {
                put("connection_info_note", JsonPrimitive(
                    "未能读取 SSID/BSSID —— Android 13+ 需要「附近的 WiFi 设备」权限，" +
                        "旧版本需要位置权限且系统定位需开启。网络本身可能仍是连着的。"
                ))
            }
        }.onFailure {
            put("connection_info_error", JsonPrimitive("${it.javaClass.simpleName}: ${it.message}"))
        }

        // ── 扫描附近网络（可选）──
        if (includeScan) {
            if (!hasWifiScanPermission(context)) {
                put("scan_error", JsonPrimitive("扫描附近网络所需的权限未授予"))
                // ★按版本给出**正确**的权限名：
                //   Android 13+ 扫描需要 NEARBY_WIFI_DEVICES；更早的版本用位置权限。
                //   报错里写错权限名会让用户去设置里找一个不存在的授权项。
                put("missing_permissions", buildJsonArray {
                    // ★add(String) 是扩展函数（需 import kotlinx.serialization.json.add），
                    //   成员函数只有 add(JsonElement)。显式包 JsonPrimitive 更明确、也不依赖 import。
                    wifiScanPermissions().forEach { add(JsonPrimitive(it)) }
                })
                put("hint", JsonPrimitive(
                    "扫描需要上述权限之一；此外多数设备还要求**系统定位开关处于开启状态**，" +
                        "否则即使权限已授予也会返回空结果。"
                ))
            } else {
                    runCatching {
                        @Suppress("DEPRECATION")
                        val results = wm.scanResults
                        put("scan_count", JsonPrimitive(results?.size ?: 0))
                        put("networks", buildJsonArray {
                            results.orEmpty().distinctBy { it.BSSID }.take(30).forEach { r ->
                                add(buildJsonObject {
                                    put("ssid", JsonPrimitive(r.SSID?.removeSurrounding("\"") ?: ""))
                                    put("bssid", JsonPrimitive(r.BSSID ?: ""))
                                    put("rssi_dbm", JsonPrimitive(r.level))
                                    put("frequency_mhz", JsonPrimitive(r.frequency))
                                    put("secured", JsonPrimitive(r.capabilities?.contains("WPA") == true ||
                                        r.capabilities?.contains("WEP") == true))
                                })
                            }
                        })
                        if ((results?.size ?: 0) == 0) {
                            put("scan_note", JsonPrimitive(
                                "扫描结果为空。scanResults 只反映最近一次扫描的缓存；" +
                                    "系统可能限制后台扫描，且定位未开启时部分设备会过滤结果。"
                            ))
                        }
                    }.onFailure {
                        put("scan_error", JsonPrimitive("${it.javaClass.simpleName}: ${it.message}"))
                    }
                }
            }
        }
    }

    /** 把 WifiManager 的 int 形式 IPv4 转成点分十进制 */
    private fun intToIp(ip: Int): String =
        "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"

    // ────────────────────────── 蓝牙 ──────────────────────────

    fun createBluetoothTool(context: Context): Tool = Tool(
        name = "bluetooth_info",
        description = """
            Read Bluetooth state: whether the adapter is on, and the list of bonded (paired) devices
            with name, address and type.
        PERMISSIONS: requires android.permission.BLUETOOTH (Android 11 and below — a normal
        permission that only needs to be declared in the manifest) or BLUETOOTH_CONNECT
        (Android 12+, a runtime permission the user must grant).
        This tool probes by actually reading the adapter state, so "permission_granted": false is
        authoritative; when it is false the response also carries "missing_permissions" and a "hint"
        stating whether the user can fix it in Settings or the app must be reinstalled.
        Do not retry blindly while permission_granted is false.
            action="open_settings" opens the system Bluetooth settings page so the user can connect or
            disconnect manually.
            IMPORTANT: a normal (non-system) app cannot programmatically connect or disconnect Bluetooth
            audio devices — BluetoothA2dp.connect()/BluetoothHeadset.connect() are hidden system APIs.
            This tool therefore reports state and guides the user instead of pretending to control it.
        """.trimIndent().replace("\n", " ").replace(Regex(" +"), " "),
        needsApproval = { false },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("description", "status (default) | open_settings")
                    })
                },
            )
        },
        execute = { args ->
            val action = args.jsonObject["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "status"
            when (action) {
                "open_settings" -> {
                    runCatching {
                        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }.onFailure { Log.w(TAG, "打开蓝牙设置失败", it) }
                    listOf(UIMessagePart.Text(buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("opened", JsonPrimitive("系统蓝牙设置页"))
                        put("hint", JsonPrimitive("已为用户打开系统设置页 —— 连接/断开蓝牙设备需要用户在那里手动操作。"))
                    }.toString()))
                }
                else -> guarded("bluetooth_info") { bluetoothSnapshot(context) }
            }
        },
    )

    /**
 * 当前 Android 版本下读取蓝牙状态所需的权限。
 *
 * 两个版本的权限名**不同**，必须按版本给出正确的那一个，
 * 否则错误提示会指向一个在本系统上根本不存在的权限。
 */
/**
 * 扫描附近网络所需的权限。
 *
 * **按版本不同**：
 *  · Android 13(T)+ —— NEARBY_WIFI_DEVICES（声明为 neverForLocation 时不强制位置权限）
 *  · Android 12 及以下 —— ACCESS_FINE_LOCATION
 * 拿错权限名会让错误提示指向一个本系统上不存在的授权项。
 */
private fun wifiScanPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/** 只要上述权限中**任一**已授予即可（新版本允许用 NEARBY_WIFI_DEVICES 替代位置权限） */
private fun hasWifiScanPermission(context: Context): Boolean =
    wifiScanPermissions().any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

private fun bluetoothRequiredPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf("android.permission.BLUETOOTH")
    }

/**
 * 蓝牙读取失败时的修复指引。
 *
 * 刻意区分两种原因，因为**可行的修复方式完全不同**：
 *  · 运行时权限未授予（Android 12+ 的 BLUETOOTH_CONNECT）→ 用户可在系统设置里授予后重试
 *  · 清单未声明（Android 11 及以下的 BLUETOOTH 是 normal 权限）→ 用户**无法自助修复**，只能重装
 * 把两者混为一谈会让用户白折腾。
 */
private fun bluetoothFixHint(context: Context): String {
    val runtimeMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
        PackageManager.PERMISSION_GRANTED
    return if (runtimeMissing) {
        "缺少运行时权限「附近的设备」（BLUETOOTH_CONNECT）。" +
            "可在系统设置 → 应用 → 本应用 → 权限中授予后重试。"
    } else {
        "蓝牙 API 调用被拒。所需权限可能未在 AndroidManifest 中声明 —— " +
            "这种情况用户无法在设置页自助修复，需要重新安装（或更新）应用。"
    }
}

private fun bluetoothSnapshot(context: Context): kotlinx.serialization.json.JsonObject {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        return buildJsonObject {
            if (adapter == null) {
                put("supported", JsonPrimitive(false))
                put("note", JsonPrimitive("本设备没有蓝牙适配器"))
                return@buildJsonObject
            }
            put("supported", JsonPrimitive(true))

            // ★真实探测：直接尝试读一次适配器状态。
            //
            // 为什么不按「SDK 版本推断需要什么权限」：
            //   那种推断只覆盖「运行时权限未授予」，漏掉了「清单里根本没声明」的情况。
            //   Android 11 及以下的 android.permission.BLUETOOTH 是 **normal 权限**
            //   （AOSP 已核实 protectionLevel="normal"）—— 不需要运行时申请，
            //   但**必须清单声明**；漏声明时 API 直接抛 SecurityException，
            //   而用户无法在系统设置里自助修复（属清单级问题，只能重装）。
            //   本工具先前正是如此：只声明了 Android 12+ 的 BLUETOOTH_CONNECT，
            //   于是旧系统上 permission_granted 报 true 却调用失败 —— 状态与真实情况冲突。
            // 现在以真实调用结果为准：能读到 → true；抛异常 → false + 列出缺失权限。
            val probe = runCatching { adapter.isEnabled }
            if (probe.isFailure) {
                val ex = probe.exceptionOrNull()
                put("permission_granted", JsonPrimitive(false))
                put("missing_permissions", buildJsonArray {
                    bluetoothRequiredPermissions().forEach { add(JsonPrimitive(it)) }
                })
                put("error", JsonPrimitive("${ex?.javaClass?.simpleName}: ${ex?.message}"))
                put("hint", JsonPrimitive(bluetoothFixHint(context)))
                return@buildJsonObject
            }
            put("permission_granted", JsonPrimitive(true))

            val btEnabled = probe.getOrThrow()
            put("enabled", JsonPrimitive(btEnabled))
            if (!btEnabled) {
                put("note", JsonPrimitive("蓝牙未开启。开启蓝牙需要用户操作（应用不能静默开启）。"))
                return@buildJsonObject
            }

            runCatching {
                put("name", JsonPrimitive(adapter.name ?: ""))
                put("state", JsonPrimitive(
                    when (adapter.state) {
                        BluetoothAdapter.STATE_ON -> "on"
                        BluetoothAdapter.STATE_OFF -> "off"
                        BluetoothAdapter.STATE_TURNING_ON -> "turning_on"
                        BluetoothAdapter.STATE_TURNING_OFF -> "turning_off"
                        else -> "unknown"
                    }
                ))
                val bonded = adapter.bondedDevices.orEmpty()
                put("bonded_count", JsonPrimitive(bonded.size))
                put("bonded_devices", buildJsonArray {
                    bonded.forEach { d: BluetoothDevice ->
                        add(buildJsonObject {
                            put("name", JsonPrimitive(runCatching { d.name }.getOrNull() ?: ""))
                            put("address", JsonPrimitive(runCatching { d.address }.getOrNull() ?: ""))
                            put("type", JsonPrimitive(
                                when (runCatching { d.type }.getOrDefault(-1)) {
                                    BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
                                    BluetoothDevice.DEVICE_TYPE_LE -> "le"
                                    BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
                                    else -> "unknown"
                                }
                            ))
                            // bondState 表示「配对状态」，不是「连接状态」——
                            // 已配对 ≠ 已连接，这里明确区分，避免模型误解
                            put("bond_state", JsonPrimitive(
                                when (runCatching { d.bondState }.getOrDefault(-1)) {
                                    BluetoothDevice.BOND_BONDED -> "bonded"
                                    BluetoothDevice.BOND_BONDING -> "bonding"
                                    BluetoothDevice.BOND_NONE -> "none"
                                    else -> "unknown"
                                }
                            ))
                        })
                    }
                })
                put("limitation", JsonPrimitive(
                    "普通应用无法程序化连接/断开蓝牙设备（A2DP/HFP 的 connect() 是隐藏系统 API）。" +
                        "需要连接时请让用户操作：action=open_settings 打开系统蓝牙设置页。"
                ))
            }.onFailure {
                put("error", JsonPrimitive("${it.javaClass.simpleName}: ${it.message}"))
            }
        }
    }

    // ────────────────────────── 短信 ──────────────────────────

    fun createSmsTool(context: Context): Tool = Tool(
        name = "sms_read",
        description = """
            Read SMS messages from the device inbox (read-only — this tool never sends anything).
            PERMISSIONS: requires the READ_SMS runtime permission (a dangerous permission the user must
        grant). If it is not granted the response says so explicitly rather than returning an empty
        list — an empty list would wrongly suggest "no messages".
            Parameters: limit (default 20, max 200), address (filter by phone number, substring match),
            unread_only (only unread messages).
            Message bodies are truncated to 1000 characters each to keep the result readable.
        """.trimIndent().replace("\n", " ").replace(Regex(" +"), " "),
        needsApproval = { false },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "How many messages to return (default 20, max 200)")
                    })
                    put("address", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional phone number / sender filter (substring match)")
                    })
                    put("unread_only", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Only return unread messages (default false)")
                    })
                },
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            val limit = (obj["limit"]?.jsonPrimitive?.longOrNull ?: 20L).toInt().coerceIn(1, 200)
            val addressFilter = obj["address"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val unreadOnly = obj["unread_only"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

            val hasPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_SMS
            ) == PackageManager.PERMISSION_GRANTED
            // 注意：不能写 return@Tool —— Tool(...) 是构造调用，并不是一个 lambda，
            // 因此不存在该标签（这里用 if/else 表达式代替提前返回）。
            if (!hasPermission) {
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("ok", JsonPrimitive(false))
                    put("error", JsonPrimitive("缺少读取短信权限（READ_SMS）"))
                    put("hint", JsonPrimitive("请在系统设置里为本应用授予「短信」权限后重试。"))
                }.toString()))
            } else {
            val result = runCatching {
                // address 过滤走 selectionArgs 参数化 —— 直接拼进 selection 会有 SQL 注入口
                val selection = buildString {
                    if (unreadOnly) append("${Telephony.Sms.READ} = 0")
                    if (addressFilter != null) {
                        if (isNotEmpty()) append(" AND ")
                        append("${Telephony.Sms.ADDRESS} LIKE ?")
                    }
                }.takeIf { it.isNotBlank() }
                val selectionArgs = addressFilter?.let { arrayOf("%$it%") }

                val messages = mutableListOf<kotlinx.serialization.json.JsonObject>()
                context.contentResolver.query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    arrayOf(
                        Telephony.Sms.ADDRESS,
                        Telephony.Sms.BODY,
                        Telephony.Sms.DATE,
                        Telephony.Sms.READ,
                    ),
                    selection,
                    selectionArgs,
                    "${Telephony.Sms.DATE} DESC",
                )?.use { cursor ->
                    val iAddr = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                    val iBody = cursor.getColumnIndex(Telephony.Sms.BODY)
                    val iDate = cursor.getColumnIndex(Telephony.Sms.DATE)
                    val iRead = cursor.getColumnIndex(Telephony.Sms.READ)
                    while (cursor.moveToNext() && messages.size < limit) {
                        messages += buildJsonObject {
                            put("address", JsonPrimitive(if (iAddr >= 0) cursor.getString(iAddr) ?: "" else ""))
                            val body = if (iBody >= 0) cursor.getString(iBody) ?: "" else ""
                            put("body", JsonPrimitive(body.take(1000)))
                            if (body.length > 1000) put("body_truncated", JsonPrimitive(true))
                            if (iDate >= 0) put("date_ms", JsonPrimitive(cursor.getLong(iDate)))
                            if (iRead >= 0) put("read", JsonPrimitive(cursor.getInt(iRead) == 1))
                        }
                    }
                }
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("count", JsonPrimitive(messages.size))
                    put("messages", buildJsonArray { messages.forEach { add(it) } })
                    if (messages.isEmpty()) put("hint", JsonPrimitive("没有符合条件的短信。"))
                }
            }.getOrElse {
                buildJsonObject {
                    put("ok", JsonPrimitive(false))
                    put("error", JsonPrimitive("${it.javaClass.simpleName}: ${it.message}"))
                }
            }
            guarded("sms_read") { result }
        }
    },
)
