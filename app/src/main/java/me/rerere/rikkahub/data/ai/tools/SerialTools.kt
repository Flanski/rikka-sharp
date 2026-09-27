package me.rerere.rikkahub.data.ai.tools

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "SerialTools"

/** USB 权限请求的 action（仅应用内使用） */
private const val ACTION_USB_PERMISSION = "me.rerere.rikkahub.action.USB_PERMISSION"

/** 等待用户点 USB 权限弹窗的最长时间 */
private const val PERMISSION_TIMEOUT_MS = 60_000L

/** 单个端口接收缓冲上限（防止无限增长吃内存） */
private const val MAX_RX_BUFFER = 1 * 1024 * 1024

/**
 * 串口工具。
 *
 * ── 免驱优先 ──
 * 对「USB 模拟串口」的设备（**RP2040 / ESP32-S2 / ESP32-S3** 等）走 **CDC-ACM** ——
 * 这是 USB 标准设备类，Android 侧无需任何厂商驱动即可通信。
 * 库同时也覆盖 CH340 / CP2102 / FTDI / PL2303 等桥接芯片，插上即可被识别。
 *
 * ── 与「工具授权」的关系 ──
 * 串口会向**外部设备**写数据，但它不在用户指定的三类需授权工具里
 * （那三类是：Shell 命令、SSH 写操作、传感器），故此处不做强制授权。
 * 注意 USB 权限本身是**系统弹窗**（[UsbManager.requestPermission]），
 * 与应用的「工具授权」是两回事。
 *
 * ── 状态 ──
 * 打开的端口是**有状态**的：连接、接收缓冲都保存在 [SerialPortRegistry] 里，
 * 因此 `read` 可以拿到「上次读取之后新到的数据」。用完请用 action=close 释放。
 */
fun createSerialTool(context: Context): Tool = Tool(
    name = "serial_port",
    description = """
        Talk to a USB serial device (CDC-ACM devices like RP2040 / ESP32-S2 / ESP32-S3 need no driver;
        CH340 / CP2102 / FTDI / PL2303 are also supported).
        Actions:
          list   — list connected USB serial devices with their id, chip type and VID:PID
          open   — open a port (port, baud, data_bits, stop_bits, parity). The first call triggers the
                   system USB permission dialog — the user must confirm on the device.
          read   — return data received since the last read (mode: hex | hex_compact | text | dump)
          write  — send data (mode hex accepts "0xAB 0xC1", "ABC1" or "AB,C1"; mode text sends UTF-8)
          status — current port state, counters and the last error
          close  — close the port and release it
        Ports stay open between calls, so read/write keep working until you close them.
    """.trimIndent().replace("\n", " "),
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "list | open | read | write | status | close")
                })
                put("port", buildJsonObject {
                    put("type", "string")
                    put("description", "Port id from action=list (e.g. /dev/bus/usb/001/002). For read/write/status/close you may omit it when exactly one port is open.")
                })
                put("baud", buildJsonObject {
                    put("type", "integer")
                    put("description", "Baud rate (default 115200)")
                })
                put("data_bits", buildJsonObject {
                    put("type", "integer")
                    put("description", "5 | 6 | 7 | 8 (default 8)")
                })
                put("stop_bits", buildJsonObject {
                    put("type", "integer")
                    put("description", "1 | 2 (default 1)")
                })
                put("parity", buildJsonObject {
                    put("type", "string")
                    put("description", "none | odd | even (default none)")
                })
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("description", "Data format for read/write: hex (\"0xAB 0xC1\") | hex_compact (\"0xABC1\") | text | dump (read only)")
                })
                put("data", buildJsonObject {
                    put("type", "string")
                    put("description", "Data to write (action=write)")
                })
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "For read: how long to wait for data (default 0 = return immediately). For write: write timeout (default 1000).")
                })
                put("max_bytes", buildJsonObject {
                    put("type", "integer")
                    put("description", "For read: cap the returned bytes (default 4096)")
                })
            },
            required = listOf("action"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val action = obj["action"]?.jsonPrimitive?.contentOrNull?.lowercase()
            ?: error("action is required")
        val portArg = obj["port"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val mode = obj["mode"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "hex"
        val timeoutMs = (obj["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 0L).coerceIn(0L, 60_000L)
        val maxBytes = (obj["max_bytes"]?.jsonPrimitive?.longOrNull ?: 4096L).toInt().coerceIn(1, 65536)

        when (action) {
            "list" -> text(listPorts(context))
            "open" -> {
                val id = portArg ?: error("port is required for action=open")
                val baud = (obj["baud"]?.jsonPrimitive?.longOrNull ?: 115200L).toInt()
                val dataBits = (obj["data_bits"]?.jsonPrimitive?.longOrNull ?: 8L).toInt()
                val stopBits = (obj["stop_bits"]?.jsonPrimitive?.longOrNull ?: 1L).toInt()
                val parity = obj["parity"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "none"
                text(SerialPortRegistry.open(context, id, baud, dataBits, stopBits, parity))
            }
            "read" -> text(SerialPortRegistry.read(portArg, mode, timeoutMs, maxBytes))
            "write" -> {
                val body = obj["data"]?.jsonPrimitive?.contentOrNull
                    ?: error("data is required for action=write")
                val writeTimeout = if (timeoutMs > 0) timeoutMs.toInt() else 1000
                text(SerialPortRegistry.write(portArg, mode, body, writeTimeout))
            }
            "status" -> text(SerialPortRegistry.status(portArg))
            "close" -> text(SerialPortRegistry.close(portArg))
            else -> error("未知 action：$action（支持 list / open / read / write / status / close）")
        }
    },
)

private fun text(json: kotlinx.serialization.json.JsonObject) =
    listOf(UIMessagePart.Text(json.toString()))

// ────────────────────────── 设备列举 ──────────────────────────

private fun listPorts(context: Context): kotlinx.serialization.json.JsonObject {
    val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
    return buildJsonObject {
        put("count", JsonPrimitive(drivers.size))
        put("devices", buildJsonArray {
            drivers.forEach { d ->
                val device = d.device
                add(buildJsonObject {
                    put("id", JsonPrimitive(device.deviceName))
                    put("chip", JsonPrimitive(d.javaClass.simpleName.replace("SerialDriver", "")))
                    put("vendor_id", JsonPrimitive(String.format("0x%04X", device.vendorId)))
                    put("product_id", JsonPrimitive(String.format("0x%04X", device.productId)))
                    put("ports", JsonPrimitive(d.ports.size))
                    put("permission", JsonPrimitive(usbManager.hasPermission(device)))
                    put("opened", JsonPrimitive(SerialPortRegistry.isOpen(device.deviceName)))
                    // CDC 类设备（RP2040 / ESP32-S2/S3 等免驱方案）单独标出，便于判断
                    put("cdc_acm", JsonPrimitive(d.javaClass.simpleName == "CdcAcmSerialDriver"))
                })
            }
        })
        if (drivers.isEmpty()) {
            put("hint", JsonPrimitive(
                "未发现 USB 串口设备。请确认：①OTG 转接头已插好；②设备已上电；" +
                    "③若是 RP2040 / ESP32-S2/S3，其 USB 口需工作在 CDC 模式（Arduino 里通常是默认的）。"
            ))
        }
    }
}

// ────────────────────────── 端口状态表 ──────────────────────────

/**
 * 已打开端口的注册表（进程级）。
 *
 * 读取走 [SerialInputOutputManager] 的**异步**回调，数据先进缓冲；
 * `read` 从缓冲取走已有数据 —— 这样「读」不会因为设备没发数据而卡住，
 * 也避免用阻塞式 `port.read()` 时反复超时。
 */
private object SerialPortRegistry {

    private class OpenPort(
        val id: String,
        val device: UsbDevice,
        val port: UsbSerialPort,
        val connection: android.hardware.usb.UsbDeviceConnection,
        val baud: Int,
    ) {
        val rx = ByteArrayOutputStream()
        @Volatile var error: String? = null
        @Volatile var txBytes: Long = 0
        @Volatile var rxBytes: Long = 0
        @Volatile var ioManager: SerialInputOutputManager? = null

        fun appendData(data: ByteArray) {
            synchronized(rx) {
                if (rx.size() + data.size > MAX_RX_BUFFER) {
                    // 超过上限时丢弃最旧的一半，保证不会无界增长
                    val kept = rx.toByteArray().takeLast(MAX_RX_BUFFER / 2).toByteArray()
                    rx.reset()
                    rx.write(kept)
                }
                rx.write(data)
                rxBytes += data.size
            }
        }

        fun drain(): ByteArray = synchronized(rx) {
            val all = rx.toByteArray()
            rx.reset()
            all
        }
    }

    private val ports = ConcurrentHashMap<String, OpenPort>()

    fun isOpen(id: String): Boolean = ports.containsKey(id)

    /** 解析端口：显式给了 id 就用它；否则仅当恰好一个已打开时才接受 */
    private fun resolve(id: String?): OpenPort {
        if (!id.isNullOrBlank()) {
            return ports[id] ?: error("端口未打开：$id（先用 action=list 看设备，再 action=open）")
        }
        if (ports.size == 1) return ports.values.first()
        if (ports.isEmpty()) error("当前没有已打开的端口")
        error("有多个端口已打开（${ports.keys.joinToString()}），请显式指定 port")
    }

    /** 等待用户确认 USB 权限弹窗 */
    private suspend fun ensurePermission(context: Context, device: UsbDevice): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(device)) return true

        val deferred = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                deferred.complete(granted)
            }
        }
        // Android 14+ 要求显式声明导出标志；用接收者本身不会跨应用，故选 NOT_EXPORTED
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        return try {
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            // ★必须用 FLAG_MUTABLE：系统要往这个 Intent 里写入 EXTRA_PERMISSION_GRANTED。
            //   用 IMMUTABLE 会导致回调里读不到结果（这是 Android 12+ 的已知约束）。
            val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val pi = PendingIntent.getBroadcast(context, device.deviceId, intent, flags)
            usbManager.requestPermission(device, pi)
            withTimeoutOrNull(PERMISSION_TIMEOUT_MS) { deferred.await() } ?: false
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    suspend fun open(
        context: Context,
        id: String,
        baud: Int,
        dataBits: Int,
        stopBits: Int,
        parity: String,
    ): kotlinx.serialization.json.JsonObject = withContext(Dispatchers.IO) {
        ports[id]?.let {
            return@withContext buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("already_open", JsonPrimitive(true))
                put("id", JsonPrimitive(it.id))
                put("baud", JsonPrimitive(it.baud))
            }
        }

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            .firstOrNull { it.device.deviceName == id }
            ?: error("找不到设备：$id（用 action=list 查看当前可用设备；设备可能已被拔出）")

        if (!ensurePermission(context, driver.device)) {
            error("未获得 USB 权限（用户拒绝，或弹窗超时未确认）。请重试并允许访问该 USB 设备。")
        }

        val connection = usbManager.openDevice(driver.device)
            ?: error("无法打开 USB 设备（可能被其它应用占用）")
        val port = driver.ports.firstOrNull() ?: run {
            connection.close()
            error("该设备没有可用端口")
        }

        val parityValue = when (parity) {
            "none" -> UsbSerialPort.PARITY_NONE
            "odd" -> UsbSerialPort.PARITY_ODD
            "even" -> UsbSerialPort.PARITY_EVEN
            else -> run { connection.close(); error("不支持的 parity：$parity（none | odd | even）") }
        }
        val stopBitsValue = when (stopBits) {
            1 -> UsbSerialPort.STOPBITS_1
            2 -> UsbSerialPort.STOPBITS_2
            else -> run { connection.close(); error("不支持的 stop_bits：$stopBits（1 | 2）") }
        }

        try {
            port.open(connection)
            port.setParameters(baud, dataBits, stopBitsValue, parityValue)
            // DTR/RTS 置位：CDC 设备（如 RP2040）常以此判断「主机已连接」才开始发数据
            // 用显式方法名而非 Kotlin 属性语法：Java 的 setDTR/setRTS 会被 Kotlin 属性化，
            // 但属性名（dtr / isDTR）在不同库版本下可能不同，方法调用形式更稳。
            runCatching { port.setDTR(true); port.setRTS(true) }
        } catch (t: Throwable) {
            runCatching { port.close() }
            runCatching { connection.close() }
            throw IllegalStateException("打开端口失败：${t.javaClass.simpleName}: ${t.message}", t)
        }

        val openPort = OpenPort(id, driver.device, port, connection, baud)
        val manager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) = openPort.appendData(data)

            override fun onRunError(e: Exception) {
                openPort.error = "${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, "串口读取线程结束：$id", e)
            }
        })
        manager.start()
        openPort.ioManager = manager
        ports[id] = openPort

        Log.i(TAG, "opened $id @ $baud, ${dataBits}${if (parity != "none") parity.first().uppercase() else ""}$stopBits")

        buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("id", JsonPrimitive(id))
            put("baud", JsonPrimitive(baud))
            put("data_bits", JsonPrimitive(dataBits))
            put("stop_bits", JsonPrimitive(stopBits))
            put("parity", JsonPrimitive(parity))
            put("chip", JsonPrimitive(driver.javaClass.simpleName.replace("SerialDriver", "")))
            put("hint", JsonPrimitive("端口已保持打开，可直接 read/write。完成后再 action=close。"))
        }
    }

    /**
     * 读取缓冲里的数据；[timeoutMs] > 0 时最多等待这么久（有数据就提前返回）。
     *
     * 用 `delay` 而非 Thread.sleep —— 工具的 execute 本身是 suspend 的，
     * 挂起比占住线程更合适（等待期间不阻塞调度器）。
     */
    suspend fun read(
        id: String?,
        mode: String,
        timeoutMs: Long,
        maxBytes: Int,
    ): kotlinx.serialization.json.JsonObject {
        val p = resolve(id)
        val deadline = System.currentTimeMillis() + timeoutMs
        var data: ByteArray
        while (true) {
            data = p.drain()
            if (data.isNotEmpty() || System.currentTimeMillis() >= deadline) break
            kotlinx.coroutines.delay(20)
        }
        val bytes = if (data.size > maxBytes) data.copyOf(maxBytes) else data
        val truncated = data.size > bytes.size

        return buildJsonObject {
            put("id", JsonPrimitive(p.id))
            put("bytes", JsonPrimitive(bytes.size))
            if (truncated) put("truncated_from", JsonPrimitive(data.size))
            put("mode", JsonPrimitive(mode))
            put("data", JsonPrimitive(formatBytes(bytes, mode)))
            put("total_rx", JsonPrimitive(p.rxBytes))
            p.error?.let { put("reader_error", JsonPrimitive(it)) }
            if (bytes.isEmpty()) put("hint", JsonPrimitive("本次没有新数据。可增大 timeout_ms 等待，或检查设备是否在发送。"))
        }
    }

    fun write(id: String?, mode: String, body: String, timeoutMs: Int):
        kotlinx.serialization.json.JsonObject {
        val p = resolve(id)
        val bytes = when (mode) {
            "text" -> body.toByteArray(Charsets.UTF_8)
            "hex", "hex_compact", "dump" -> parseHex(body)
            else -> error("不支持的 mode：$mode（text | hex | hex_compact）")
        }
        if (bytes.isEmpty()) error("要写入的数据为空")
        return try {
            p.port.write(bytes, timeoutMs)
            p.txBytes += bytes.size
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("id", JsonPrimitive(p.id))
                put("bytes", JsonPrimitive(bytes.size))
                put("sent", JsonPrimitive(formatBytes(bytes, if (mode == "text") "text" else "hex")))
                put("total_tx", JsonPrimitive(p.txBytes))
            }
        } catch (t: Throwable) {
            buildJsonObject {
                put("ok", JsonPrimitive(false))
                put("id", JsonPrimitive(p.id))
                put("error", JsonPrimitive("${t.javaClass.simpleName}: ${t.message}"))
            }
        }
    }

    fun status(id: String?): kotlinx.serialization.json.JsonObject {
        val all = ports.values
        val target = if (!id.isNullOrBlank()) resolve(id) else null
        return buildJsonObject {
            put("open_count", JsonPrimitive(all.size))
            if (target != null) {
                put("id", JsonPrimitive(target.id))
                put("baud", JsonPrimitive(target.baud))
                put("rx_bytes", JsonPrimitive(target.rxBytes))
                put("tx_bytes", JsonPrimitive(target.txBytes))
                put("buffered_bytes", JsonPrimitive(synchronized(target.rx) { target.rx.size() }))
                target.error?.let { put("reader_error", JsonPrimitive(it)) }
            }
            put("ports", buildJsonArray {
                all.forEach { p ->
                    add(buildJsonObject {
                        put("id", JsonPrimitive(p.id))
                        put("baud", JsonPrimitive(p.baud))
                        put("buffered_bytes", JsonPrimitive(synchronized(p.rx) { p.rx.size() }))
                    })
                }
            })
        }
    }

    fun close(id: String?): kotlinx.serialization.json.JsonObject {
        val all = ports.values.toList()
        val targets = if (!id.isNullOrBlank()) listOf(resolve(id)) else all
        if (targets.isEmpty()) {
            return buildJsonObject { put("ok", JsonPrimitive(true)); put("closed", JsonPrimitive(0)) }
        }
        var n = 0
        targets.forEach { p ->
            runCatching { p.ioManager?.stop() }
            runCatching { p.port.close() }
            runCatching { p.connection.close() }
            ports.remove(p.id)
            n++
            Log.i(TAG, "closed ${p.id}")
        }
        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("closed", JsonPrimitive(n))
        }
    }
}

// ────────────────────────── 数据格式 ──────────────────────────

/** 按模式格式化字节 */
private fun formatBytes(bytes: ByteArray, mode: String): String = when (mode) {
    "text" -> bytes.toString(Charsets.UTF_8)
    "hex_compact" -> "0x" + bytes.joinToString("") { "%02X".format(it) }
    "dump" -> hexDump(bytes)
    else -> bytes.joinToString(" ") { "0x%02X".format(it) }
}

/** 经典 hexdump：偏移 + 16 字节十六进制 + ASCII */
private fun hexDump(bytes: ByteArray): String {
    val sb = StringBuilder()
    var offset = 0
    while (offset < bytes.size) {
        val chunk = bytes.copyOfRange(offset, minOf(offset + 16, bytes.size))
        sb.append("%08X  ".format(offset))
        for (i in 0 until 16) {
            sb.append(if (i < chunk.size) "%02X ".format(chunk[i]) else "   ")
            if (i == 7) sb.append(' ')
        }
        sb.append(" |")
        chunk.forEach { b ->
            val c = b.toInt().toChar()
            sb.append(if (c in ' '..'~') c else '.')
        }
        sb.append("|\n")
        offset += 16
    }
    return sb.toString().trimEnd('\n')
}

/**
 * 解析十六进制输入。
 *
 * 容忍这些写法（都是从实际使用习惯里来的）：
 *   "0xAB 0xC1 0x23" / "AB C1 23" / "ABC123" / "AB,C1,23" / "ab-c1-23"
 */
private fun parseHex(input: String): ByteArray {
    val cleaned = input
        .replace(Regex("0[xX]"), "")
        .replace(Regex("[,;:\\-\\s]"), "")
    if (cleaned.isEmpty()) return ByteArray(0)
    if (cleaned.length % 2 != 0) {
        throw IllegalArgumentException("十六进制字符串长度必须是偶数（当前 ${cleaned.length} 个字符）：$input")
    }
    val bad = cleaned.firstOrNull { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }
    if (bad != null) {
        throw IllegalArgumentException("包含非十六进制字符 '$bad'：$input")
    }
    return ByteArray(cleaned.length / 2) {
        cleaned.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}
