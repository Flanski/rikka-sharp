package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

private const val TAG = "MidiTools"

/** 等待 openDevice 回调的最长时间 */
private const val OPEN_TIMEOUT_MS = 10_000L

/** 单设备接收缓冲上限 */
private const val MAX_RX = 256 * 1024

/**
 * MIDI 工具 —— 基于 **Android 原生 `android.media.midi`**。
 *
 * ── 为什么零依赖且免驱 ──
 * USB MIDI 是 USB 标准设备类，Android 从 6.0(API 23) 起由系统提供 `MidiManager`，
 * **不需要任何第三方库，也不需要 root 或额外权限**（系统在打开设备时自行处理 USB 授权）。
 * 这与「免驱串口」的思路一致 —— 凡是被系统识别为标准类别的设备都可直接用。
 *
 * ── 典型用途 ──
 * 数字音频设备 / 控制器（键盘、打击垫）、合成器、MIDI 转接板等，
 * 无论是 USB MIDI 还是系统内的虚拟 MIDI 端口，都能在这里看到。
 *
 * ── 消息格式 ──
 * 收发都用**原始字节**（MIDI 本身就是字节协议）。`mode=text` 时会把字节解析成
 * 可读描述，例如 `90 3C 64` → `NoteOn ch=1 note=60(C4) vel=100`。
 */
fun createMidiTool(context: Context): Tool = Tool(
    name = "midi",
    description = """
        List and talk to MIDI devices (USB MIDI and virtual ports) via the Android MIDI API.
        No driver or extra permission is needed — USB MIDI is a standard USB class.
        Actions:
          list    — list MIDI devices with id, ports, type and name
          open    — open a device's input and/or output port (required before send/receive)
          send    — send MIDI bytes to the device's input port (data = hex like "90 3C 64",
                    or use note_on/note_off/cc helpers)
          receive — read MIDI bytes received from the device since the last read
          status  — which devices are open, counters, last error
          close   — close the device
        MIDI messages are bytes: 90 3C 64 = NoteOn channel 1 note 60 velocity 100;
        80 3C 40 = NoteOff; B0 07 7F = ControlChange #7 = 127.
    """.trimIndent().replace("\n", " "),
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "list | open | send | receive | status | close")
                })
                put("device", buildJsonObject {
                    put("type", "string")
                    put("description", "Device id from action=list. For send/receive/status/close you may omit it when exactly one device is open.")
                })
                put("data", buildJsonObject {
                    put("type", "string")
                    put("description", "Hex bytes to send, e.g. \"90 3C 64\". Also accepts \"0x90 0x3C 0x64\" or \"903C64\".")
                })
                put("note_on", buildJsonObject {
                    put("type", "array")
                    put("description", "Helper: [note, velocity] or [note, velocity, channel(1-16)]. Mutually exclusive with data.")
                    put("items", buildJsonObject { put("type", "integer") })
                })
                put("note_off", buildJsonObject {
                    put("type", "array")
                    put("description", "Helper: [note] or [note, channel(1-16)]")
                    put("items", buildJsonObject { put("type", "integer") })
                })
                put("cc", buildJsonObject {
                    put("type", "array")
                    put("description", "Helper: [controller, value] or [controller, value, channel(1-16)]")
                    put("items", buildJsonObject { put("type", "integer") })
                })
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("description", "Output format for receive: text (decoded descriptions, default) | hex | both")
                })
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "For receive: how long to wait for messages (default 0 = return immediately)")
                })
            },
            required = listOf("action"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val action = obj["action"]?.jsonPrimitive?.contentOrNull?.lowercase()
            ?: error("action is required")
        val deviceArg = obj["device"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val mode = obj["mode"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "text"
        val timeoutMs = (obj["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 0L).coerceIn(0L, 60_000L)

        when (action) {
            "list" -> midiText(listMidiDevices(context))
            "open" -> {
                val id = deviceArg ?: error("device is required for action=open")
                midiText(MidiRegistry.open(context, id))
            }
            "send" -> {
                val bytes = buildMidiBytes(obj)
                midiText(MidiRegistry.send(deviceArg, bytes))
            }
            "receive" -> midiText(MidiRegistry.receive(deviceArg, mode, timeoutMs))
            "status" -> midiText(MidiRegistry.status(deviceArg))
            "close" -> midiText(MidiRegistry.close(deviceArg))
            else -> error("未知 action：$action（支持 list / open / send / receive / status / close）")
        }
    },
)

private fun midiText(json: kotlinx.serialization.json.JsonObject) =
    listOf(UIMessagePart.Text(json.toString()))

// ────────────────────────── 设备列举 ──────────────────────────

private fun midiManager(context: Context) =
    context.getSystemService(Context.MIDI_SERVICE) as MidiManager

private fun typeName(type: Int): String = when (type) {
    MidiDeviceInfo.TYPE_USB -> "usb"
    MidiDeviceInfo.TYPE_BLUETOOTH -> "bluetooth"
    MidiDeviceInfo.TYPE_VIRTUAL -> "virtual"
    MidiDeviceInfo.TYPE_BUILTIN -> "builtin"
    else -> "type$type"
}

private fun listMidiDevices(context: Context): kotlinx.serialization.json.JsonObject {
    val manager = midiManager(context)
    val devices = manager.devices
    return buildJsonObject {
        put("count", JsonPrimitive(devices.size))
        put("devices", buildJsonArray {
            devices.forEach { info ->
                add(buildJsonObject {
                    put("id", JsonPrimitive(info.id.toString()))
                    put("name", JsonPrimitive(deviceDisplayName(info)))
                    put("type", JsonPrimitive(typeName(info.type)))
                    put("input_ports", JsonPrimitive(info.inputPortCount))
                    put("output_ports", JsonPrimitive(info.outputPortCount))
                    put("opened", JsonPrimitive(MidiRegistry.isOpen(info.id.toString())))
                })
            }
        })
        if (devices.isEmpty()) {
            put("hint", JsonPrimitive(
                "未发现 MIDI 设备。USB MIDI 是标准设备类，插上后系统应自动识别；" +
                    "若看不到，请确认设备工作在 MIDI 模式（部分开发板需要固件支持）。"
            ))
        }
    }
}

/** 设备名：API 29+ 走 properties，低版本回退到 id */
private fun deviceDisplayName(info: MidiDeviceInfo): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val props = info.properties
        val name = props.getString(MidiDeviceInfo.PROPERTY_NAME)
        val product = props.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
        val manufacturer = props.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)
        val parts = listOfNotNull(manufacturer, product, name).filter { it.isNotBlank() }.distinct()
        if (parts.isNotEmpty()) return parts.joinToString(" ")
    }
    return "MIDI device #${info.id}"
}

// ────────────────────────── 打开的设备表 ──────────────────────────

/**
 * 已打开的 MIDI 设备（进程级）。
 *
 * 与串口一样，MIDI 连接是**有状态**的：保持 input/output port 才能持续收发。
 * 收到的消息带时间戳进缓冲，`receive` 取走已有内容。
 */
private object MidiRegistry {

    /**
     * openDevice 回调的结果包装。
     *
     * 存在的理由：回调参数本身可空，而「等超时」也会产生 null ——
     * 直接把两者混在一个可空值里就无法区分失败原因。
     */
    private class OpenResult(val device: MidiDevice?)

    private class OpenDevice(
        val id: String,
        val device: MidiDevice,
        val inputPort: MidiInputPort?,
        val outputPort: MidiOutputPort?,
    ) {
        val rx = ByteArrayOutputStream()
        @Volatile var error: String? = null
        @Volatile var rxBytes: Long = 0
        @Volatile var txBytes: Long = 0
        lateinit var receiver: MidiReceiver

        fun append(data: ByteArray, count: Int) {
            synchronized(rx) {
                if (rx.size() + count > MAX_RX) {
                    val kept = rx.toByteArray().takeLast(MAX_RX / 2).toByteArray()
                    rx.reset(); rx.write(kept)
                }
                rx.write(data, 0, count)
                rxBytes += count
            }
        }

        fun drain(): ByteArray = synchronized(rx) {
            val all = rx.toByteArray(); rx.reset(); all
        }
    }

    private val devices = java.util.concurrent.ConcurrentHashMap<String, OpenDevice>()

    fun isOpen(id: String): Boolean = devices.containsKey(id)

    private fun resolve(id: String?): OpenDevice {
        if (!id.isNullOrBlank()) {
            return devices[id] ?: error("设备未打开：$id（先用 action=list，再 action=open）")
        }
        if (devices.size == 1) return devices.values.first()
        if (devices.isEmpty()) error("当前没有已打开的 MIDI 设备")
        error("有多个设备已打开（${devices.keys.joinToString()}），请显式指定 device")
    }

    suspend fun open(context: Context, id: String): kotlinx.serialization.json.JsonObject =
        withContext(Dispatchers.IO) {
            devices[id]?.let {
                return@withContext buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("already_open", JsonPrimitive(true))
                    put("id", JsonPrimitive(id))
                }
            }

            val manager = midiManager(context)
            val info = manager.devices.firstOrNull { it.id.toString() == id }
                ?: error("找不到 MIDI 设备：$id（用 action=list 查看；设备可能已拔出）")

            // openDevice 是**异步回调**式的（没有同步重载），因此用 CompletableDeferred 等待。
            // Handler 必须非 null，用主线程 Looper。
            //
            // ★用 OpenResult 包一层而不是直接 await 一个可空的 MidiDevice：
            //   否则「超时」与「回调给出 null（设备打开失败）」都会得到 null，
            //   无法区分两者，报错信息就会指错方向。
            val deferred = CompletableDeferred<OpenResult>()
            val listener = MidiManager.OnDeviceOpenedListener { opened ->
                deferred.complete(OpenResult(opened))
            }
            manager.openDevice(info, listener, Handler(Looper.getMainLooper()))

            val result = withTimeoutOrNull(OPEN_TIMEOUT_MS) { deferred.await() }
                ?: error("打开 MIDI 设备超时（$OPEN_TIMEOUT_MS ms，用户可能没有响应系统弹窗）")
            val device = result.device
                ?: error("系统拒绝打开该 MIDI 设备（可能被其它应用独占，或设备已断开）")

            val inputPort = runCatching {
                if (info.inputPortCount > 0) device.openInputPort(0) else null
            }.getOrElse {
                runCatching { device.close() }
                throw IllegalStateException("打开输入端口失败：${it.message}", it)
            }
            val outputPort = runCatching {
                if (info.outputPortCount > 0) device.openOutputPort(0) else null
            }.getOrElse {
                runCatching { inputPort?.close() }
                runCatching { device.close() }
                throw IllegalStateException("打开输出端口失败：${it.message}", it)
            }

            val open = OpenDevice(id, device, inputPort, outputPort)

            if (outputPort != null) {
                open.receiver = object : MidiReceiver() {
                    override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
                        // MidiReceiver 给的是「一段可能含多条消息」的字节；这里原样收下，
                        // 解析留到 receive 时按 mode 处理（避免在回调里做重活）
                        val slice = data.copyOfRange(offset, offset + count)
                        open.append(slice, slice.size)
                    }

                    override fun onFlush() { /* 设备要求丢弃未处理数据：缓冲区已在 drain 后自然清空 */ }
                }
                runCatching { outputPort.connect(open.receiver) }.onFailure {
                    open.error = "连接接收器失败：${it.message}"
                    Log.w(TAG, "connect receiver failed", it)
                }
            }

            devices[id] = open
            Log.i(TAG, "opened MIDI $id (name=${deviceDisplayName(info)})")

            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("id", JsonPrimitive(id))
                put("name", JsonPrimitive(deviceDisplayName(info)))
                put("input_port", JsonPrimitive(inputPort != null))
                put("output_port", JsonPrimitive(outputPort != null))
                put("hint", JsonPrimitive(
                    "设备已保持打开。可用 send 发送（需 input_port）与 receive 读取（需 output_port）。" +
                        "完成后 action=close。"
                ))
                open.error?.let { put("warning", JsonPrimitive(it)) }
            }
        }

    fun send(id: String?, bytes: ByteArray): kotlinx.serialization.json.JsonObject {
        val d = resolve(id)
        val port = d.inputPort
            ?: error("该设备没有输入端口（input_port=false），无法发送。MIDI 设备的接收端口才是发送目标。")
        if (bytes.isEmpty()) error("要发送的数据为空")
        return try {
            // 时间戳用 -1 表示「尽快发送」
            port.send(bytes, 0, bytes.size, -1)
            d.txBytes += bytes.size
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("id", JsonPrimitive(d.id))
                put("bytes", JsonPrimitive(bytes.size))
                put("sent_hex", JsonPrimitive(bytes.joinToString(" ") { "%02X".format(it) }))
                put("sent_text", JsonPrimitive(decodeMidi(bytes).joinToString(" | ")))
                put("total_tx", JsonPrimitive(d.txBytes))
            }
        } catch (t: Throwable) {
            buildJsonObject {
                put("ok", JsonPrimitive(false))
                put("id", JsonPrimitive(d.id))
                put("error", JsonPrimitive("${t.javaClass.simpleName}: ${t.message}"))
            }
        }
    }

    suspend fun receive(
        id: String?,
        mode: String,
        timeoutMs: Long,
    ): kotlinx.serialization.json.JsonObject {
        val d = resolve(id)
        if (d.outputPort == null) {
            error("该设备没有输出端口（output_port=false），无法接收。")
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        var data: ByteArray
        while (true) {
            data = d.drain()
            if (data.isNotEmpty() || System.currentTimeMillis() >= deadline) break
            delay(20)
        }
        return buildJsonObject {
            put("id", JsonPrimitive(d.id))
            put("bytes", JsonPrimitive(data.size))
            put("mode", JsonPrimitive(mode))
            if (mode == "hex" || mode == "both") {
                put("hex", JsonPrimitive(data.joinToString(" ") { "%02X".format(it) }))
            }
            if (mode != "hex") {
                put("messages", buildJsonArray {
                    decodeMidi(data).forEach { add(it) }
                })
            }
            put("total_rx", JsonPrimitive(d.rxBytes))
            d.error?.let { put("receiver_error", JsonPrimitive(it)) }
            if (data.isEmpty()) {
                put("hint", JsonPrimitive("本次没有收到消息。可增大 timeout_ms；也请确认设备确实在发送（多数设备只在演奏/操作时发）。"))
            }
        }
    }

    fun status(id: String?): kotlinx.serialization.json.JsonObject {
        val all = devices.values
        val target = if (!id.isNullOrBlank()) resolve(id) else null
        return buildJsonObject {
            put("open_count", JsonPrimitive(all.size))
            if (target != null) {
                put("id", JsonPrimitive(target.id))
                put("rx_bytes", JsonPrimitive(target.rxBytes))
                put("tx_bytes", JsonPrimitive(target.txBytes))
                put("buffered_bytes", JsonPrimitive(synchronized(target.rx) { target.rx.size() }))
                target.error?.let { put("receiver_error", JsonPrimitive(it)) }
            }
            put("devices", buildJsonArray {
                all.forEach { d ->
                    add(buildJsonObject {
                        put("id", JsonPrimitive(d.id))
                        put("buffered_bytes", JsonPrimitive(synchronized(d.rx) { d.rx.size() }))
                    })
                }
            })
        }
    }

    fun close(id: String?): kotlinx.serialization.json.JsonObject {
        val all = devices.values.toList()
        val targets = if (!id.isNullOrBlank()) listOf(resolve(id)) else all
        if (targets.isEmpty()) {
            return buildJsonObject { put("ok", JsonPrimitive(true)); put("closed", JsonPrimitive(0)) }
        }
        var n = 0
        targets.forEach { d ->
            if (d::receiver.isInitialized) runCatching { d.outputPort?.disconnect(d.receiver) }
            runCatching { d.inputPort?.close() }
            runCatching { d.outputPort?.close() }
            runCatching { d.device.close() }
            devices.remove(d.id)
            n++
            Log.i(TAG, "closed MIDI ${d.id}")
        }
        return buildJsonObject { put("ok", JsonPrimitive(true)); put("closed", JsonPrimitive(n)) }
    }
}

// ────────────────────────── MIDI 字节 ──────────────────────────

/** 从工具参数构造要发送的字节：支持 data(hex) 或 note_on / note_off / cc 便捷形式 */
private fun buildMidiBytes(obj: kotlinx.serialization.json.JsonObject): ByteArray {
    obj["data"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
        return parseMidiHex(it)
    }

    fun ints(key: String): List<Int>? = (obj[key] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.toIntOrNull() }

    ints("note_on")?.let { a ->
        require(a.size >= 2) { "note_on 需要 [note, velocity]（可选第三个是 channel）" }
        val note = a[0].coerceIn(0, 127)
        val vel = a[1].coerceIn(0, 127)
        val ch = (a.getOrNull(2) ?: 1).coerceIn(1, 16) - 1
        return byteArrayOf((0x90 or ch).toByte(), note.toByte(), vel.toByte())
    }
    ints("note_off")?.let { a ->
        require(a.isNotEmpty()) { "note_off 需要 [note]（可选第二个是 channel）" }
        val note = a[0].coerceIn(0, 127)
        val ch = (a.getOrNull(1) ?: 1).coerceIn(1, 16) - 1
        return byteArrayOf((0x80 or ch).toByte(), note.toByte(), 0x40)
    }
    ints("cc")?.let { a ->
        require(a.size >= 2) { "cc 需要 [controller, value]（可选第三个是 channel）" }
        val cc = a[0].coerceIn(0, 127)
        val v = a[1].coerceIn(0, 127)
        val ch = (a.getOrNull(2) ?: 1).coerceIn(1, 16) - 1
        return byteArrayOf((0xB0 or ch).toByte(), cc.toByte(), v.toByte())
    }

    error("需要 data（十六进制）或 note_on / note_off / cc 之一")
}

/** 解析 MIDI 十六进制输入（与串口工具同样的宽容策略） */
private fun parseMidiHex(input: String): ByteArray {
    val cleaned = input.replace(Regex("0[xX]"), "").replace(Regex("[,;:\\-\\s]"), "")
    if (cleaned.isEmpty()) return ByteArray(0)
    require(cleaned.length % 2 == 0) { "十六进制长度必须是偶数：$input" }
    val bad = cleaned.firstOrNull { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }
    require(bad == null) { "包含非十六进制字符 '$bad'：$input" }
    return ByteArray(cleaned.length / 2) { cleaned.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

/**
 * 把 MIDI 字节流解析成可读描述。
 *
 * MIDI 消息是**变长**的：System Real-Time（F8..FF）1 字节，
 * Program Change（C0/D0）2 字节，其余 Channel Voice 3 字节 —— 必须按状态字节判断长度，
 * 否则会把多条消息错位解读。
 */
private fun decodeMidi(bytes: ByteArray): List<String> {
    val out = mutableListOf<String>()
    var i = 0
    while (i < bytes.size) {
        val status = bytes[i].toInt() and 0xFF
        if (status < 0x80) {
            out += "数据字节 0x%02X（缺少状态字节，字节流可能未对齐）".format(status)
            i++
            continue
        }
        when {
            status >= 0xF8 -> { // System Real-Time：1 字节
                out += systemName(status)
                i++
            }
            status >= 0xF0 -> { // System Common：按类型取长度（这里只处理常见的 1/2/3 字节）
                val len = when (status) {
                    0xF1, 0xF3 -> 2
                    0xF2 -> 3
                    0xF6, 0xF7 -> 1
                    else -> 1
                }
                out += systemName(status)
                i += len
            }
            status and 0xF0 == 0xC0 || status and 0xF0 == 0xD0 -> { // 2 字节
                val d1 = bytes.getOrNull(i + 1)?.toInt()?.and(0xFF)
                out += if (status and 0xF0 == 0xC0) {
                    "ProgramChange ch=${(status and 0x0F) + 1} program=$d1"
                } else {
                    "ChannelPressure ch=${(status and 0x0F) + 1} value=$d1"
                }
                i += 2
            }
            else -> { // 3 字节 Channel Voice
                val d1 = bytes.getOrNull(i + 1)?.toInt()?.and(0xFF) ?: 0
                val d2 = bytes.getOrNull(i + 2)?.toInt()?.and(0xFF) ?: 0
                val ch = (status and 0x0F) + 1
                val noteName = "${NOTE_NAMES[d1 % 12]}${d1 / 12 - 1}"
                out += when (status and 0xF0) {
                    0x80 -> "NoteOff ch=$ch note=$d1($noteName) vel=$d2"
                    0x90 -> if (d2 == 0) "NoteOff ch=$ch note=$d1($noteName) vel=0（NoteOn velocity=0 等同于 NoteOff）"
                    else "NoteOn ch=$ch note=$d1($noteName) vel=$d2"
                    0xA0 -> "PolyPressure ch=$ch note=$d1($noteName) value=$d2"
                    0xB0 -> "ControlChange ch=$ch cc=$d1(${ccName(d1)}) value=$d2"
                    0xE0 -> {
                        val bend = ((d2 shl 7) or d1) - 8192
                        "PitchBend ch=$ch value=$bend"
                    }
                    else -> "未知状态 0x%02X".format(status)
                }
                i += 3
            }
        }
    }
    return out
}

private fun systemName(status: Int): String = when (status) {
    0xF0 -> "SystemExclusive 开始"
    0xF7 -> "SystemExclusive 结束"
    0xF1 -> "MTC Quarter Frame"
    0xF2 -> "Song Position Pointer"
    0xF3 -> "Song Select"
    0xF6 -> "Tune Request"
    0xF8 -> "Timing Clock"
    0xFA -> "Start"
    0xFB -> "Continue"
    0xFC -> "Stop"
    0xFE -> "Active Sensing"
    0xFF -> "System Reset"
    else -> "System 0x%02X".format(status)
}

/** 常见控制器名称（只覆盖最常用的几个，其余显示编号即可） */
private fun ccName(cc: Int): String = when (cc) {
    1 -> "Modulation"
    2 -> "Breath"
    7 -> "Volume"
    10 -> "Pan"
    11 -> "Expression"
    64 -> "Sustain Pedal"
    65 -> "Portamento"
    71 -> "Resonance"
    74 -> "Brightness"
    91 -> "Reverb"
    93 -> "Chorus"
    120 -> "All Sound Off"
    121 -> "Reset All Controllers"
    123 -> "All Notes Off"
    else -> "CC$cc"
}
