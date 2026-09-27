package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 电池信息读取的**统一实现**（工具与提示词占位符共用）。
 *
 * ── 为什么要多路回退 ──
 * 原先项目的 `battery_level` 占位符只用了
 * `BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY)`。
 * 这个属性在部分设备/系统（尤其是华为系与定制 ROM）上会返回 **0 或异常值**，
 * 于是提示词里拿到的电量恒为 0 —— 用户反馈的「电量变量拿不到」很可能就是它。
 *
 * 这里按可靠性排序依次尝试：
 *   1. sticky broadcast `ACTION_BATTERY_CHANGED` 的 `EXTRA_LEVEL / EXTRA_SCALE`
 *      —— 这是 Android 最古老的电池接口，覆盖面最广、几乎不会失败
 *   2. `BATTERY_PROPERTY_CAPACITY` —— 较新且有 1..100 直接结果，但可失败的场景见上
 *   3. 都拿不到时返回 null（由调用方决定如何呈现），**绝不返回 0 冒充真实电量**
 *
 * 注意：读取 sticky broadcast 用 `registerReceiver(null, filter)`，
 * 不注册接收者、无需任何权限，也不受 Android 14 导出标志要求的约束。
 */
object BatteryInfo {

    /** 读原始电池广播；拿不到返回 null */
    private fun batteryIntent(context: Context): Intent? =
        runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

    /**
     * 电量百分比。
     * 返回 null 表示两种途径都拿不到（调用方应据此显示「未知」而不是 0）。
     */
    fun levelPercent(context: Context): Int? {
        // 途径 1：sticky broadcast（最通用）
        batteryIntent(context)?.let { i ->
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                return (level * 100 / scale).coerceIn(0, 100)
            }
        }
        // 途径 2：BatteryManager 属性
        runCatching {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val v = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (v in 1..100) return v
        }
        return null
    }

    /** 组装完整电池信息（工具输出用） */
    fun snapshot(context: Context): kotlinx.serialization.json.JsonObject {
        val i = batteryIntent(context)
        val bm = runCatching {
            context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        }.getOrNull()

        fun prop(key: Int): Int? = runCatching {
            bm?.getIntProperty(key)?.takeIf { it != Int.MIN_VALUE && it != 0 }
        }.getOrNull()

        return buildJsonObject {
            val pct = levelPercent(context)
            if (pct != null) {
                put("level_percent", JsonPrimitive(pct))
            } else {
                put("level_percent", JsonPrimitive(-1))
                put("level_note", JsonPrimitive(
                    "无法读取电量（sticky broadcast 与 BATTERY_PROPERTY_CAPACITY 均未返回有效值）"
                ))
            }

            if (i == null) {
                put("note", JsonPrimitive("未能读取 ACTION_BATTERY_CHANGED，以下部分字段可能缺失"))
                return@buildJsonObject
            }

            // ── 状态 ──
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            put("status", JsonPrimitive(
                when (status) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                    BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                    BatteryManager.BATTERY_STATUS_FULL -> "full"
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
                    else -> "unknown"
                }
            ))

            // ── 充电来源 ──
            val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            put("plugged", JsonPrimitive(
                when (plugged) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                    BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                    BatteryManager.BATTERY_PLUGGED_DOCK -> "dock"
                    else -> "none"
                }
            ))
            put("is_charging", JsonPrimitive(
                status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            ))

            // ── 健康 ──
            val health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
            put("health", JsonPrimitive(
                when (health) {
                    BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                    BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
                    BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
                    else -> "unknown"
                }
            ))

            // ── 温度（单位 0.1℃）──
            val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (temp != Int.MIN_VALUE) {
                put("temperature_c", JsonPrimitive(temp / 10.0))
            }

            // ── 电压（单位 mV）──
            val voltage = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
            if (voltage != Int.MIN_VALUE && voltage > 0) {
                put("voltage_v", JsonPrimitive(voltage / 1000.0))
            }

            i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.let {
                put("technology", JsonPrimitive(it))
            }

            // ── 其它可用字段（常量名已对照 AOSP BatteryManager.java 的 21 个 EXTRA_* 核对）──
            // 这些在低版本广播里可能不存在，getIntExtra 会返回默认值 → 就不写该字段，
            // 避免用 0 冒充「循环次数为 0」这类误导信息。
            i.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1).takeIf { it >= 0 }
                ?.let { put("cycle_count", JsonPrimitive(it)) }
            i.getIntExtra(BatteryManager.EXTRA_CHARGE_COUNTER, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
                ?.let { put("extras_charge_counter", JsonPrimitive(it)) }
            i.getIntExtra(BatteryManager.EXTRA_MAX_CHARGING_CURRENT, Int.MIN_VALUE).takeIf { it > 0 }
                ?.let { put("max_charging_current_ma", JsonPrimitive(it)) }
            i.getIntExtra(BatteryManager.EXTRA_MAX_CHARGING_VOLTAGE, Int.MIN_VALUE).takeIf { it > 0 }
                ?.let { put("max_charging_voltage_mv", JsonPrimitive(it)) }
            i.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, false).takeIf { it }
                ?.let { put("battery_low", JsonPrimitive(true)) }
            i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true).takeIf { !it }
                ?.let { put("battery_present", JsonPrimitive(false)) }

            // ── 容量等级（API 28+ 的粗粒度指示：low/medium/high）──
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // ★常量名已对照 AOSP BatteryManager.java 核对 —— 完整清单只有 7 个：
                //   UNSUPPORTED(-1) / UNKNOWN(0) / CRITICAL(1) / LOW(2) / NORMAL(3) / HIGH(4) / FULL(5)
                //   先前我写的 BATTERY_CAPACITY_LEVEL_MEDIUM **并不存在**（正确的是 NORMAL），
                //   且漏了 CRITICAL / UNKNOWN。
                when (i.getIntExtra(BatteryManager.EXTRA_CAPACITY_LEVEL, Int.MIN_VALUE)) {
                    BatteryManager.BATTERY_CAPACITY_LEVEL_CRITICAL -> put("capacity_level", JsonPrimitive("critical"))
                    BatteryManager.BATTERY_CAPACITY_LEVEL_LOW -> put("capacity_level", JsonPrimitive("low"))
                    BatteryManager.BATTERY_CAPACITY_LEVEL_NORMAL -> put("capacity_level", JsonPrimitive("normal"))
                    BatteryManager.BATTERY_CAPACITY_LEVEL_HIGH -> put("capacity_level", JsonPrimitive("high"))
                    BatteryManager.BATTERY_CAPACITY_LEVEL_FULL -> put("capacity_level", JsonPrimitive("full"))
                    BatteryManager.BATTERY_CAPACITY_LEVEL_UNKNOWN -> put("capacity_level", JsonPrimitive("unknown"))
                    BatteryManager.BATTERY_CAPACITY_LEVEL_UNSUPPORTED -> put("capacity_level", JsonPrimitive("unsupported"))
                    else -> {}
                }
            }

            // ── 电流 / 电量计数（来自 BatteryManager 属性）──
            // 注意单位：BATTERY_PROPERTY_CURRENT_NOW 官方定义为**微安(µA)**，
            // 但部分厂商实现给的是毫安(mA)，因此这里同时给出原始值与按 µA 换算的 mA，
            // 由调用方结合设备经验判断（不擅自假定某一种）。
            prop(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.let {
                put("current_now_raw", JsonPrimitive(it))
                put("current_now_ua_as_ma", JsonPrimitive(it / 1000.0))
                put("current_note", JsonPrimitive(
                    "官方定义单位为µA；部分厂商返回mA。current_now_ua_as_ma 是按µA换算的结果。"
                ))
            }
            prop(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.let {
                // µAh → mAh
                put("charge_counter_mah", JsonPrimitive(it / 1000.0))
            }
            prop(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)?.let {
                // nWh → mWh
                put("energy_counter_mwh", JsonPrimitive(it / 1_000_000.0))
            }
            prop(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.let {
                put("capacity_property", JsonPrimitive(it))
            }
            prop(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)?.let {
                put("current_average_raw", JsonPrimitive(it))
                put("current_average_ua_as_ma", JsonPrimitive(it / 1000.0))
            }
            prop(BatteryManager.BATTERY_PROPERTY_STATUS)?.let {
                put("status_property", JsonPrimitive(it))
            }

            // ── API 34+ 才有的电池信息 ──
            // 这些属性在低版本上 getIntProperty 会抛异常，故都用 runCatching 包裹；
            // 拿不到就不写该字段（而不是写 0 让人误解）。
            if (Build.VERSION.SDK_INT >= 34) {
                @Suppress("NewApi")
                runCatching {
                    val bm2 = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                    bm2.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATE_OF_HEALTH)
                        .takeIf { it != Int.MIN_VALUE && it != 0 }
                        ?.let { put("state_of_health", JsonPrimitive(it)) }
                    bm2.getIntProperty(BatteryManager.BATTERY_PROPERTY_MANUFACTURING_DATE)
                        .takeIf { it > 0 }
                        ?.let { put("manufacturing_date_epoch_s", JsonPrimitive(it)) }
                    bm2.getIntProperty(BatteryManager.BATTERY_PROPERTY_FIRST_USAGE_DATE)
                        .takeIf { it > 0 }
                        ?.let { put("first_usage_date_epoch_s", JsonPrimitive(it)) }
                    bm2.getIntProperty(BatteryManager.BATTERY_PROPERTY_SERIAL_NUMBER)
                        .takeIf { it != Int.MIN_VALUE && it != 0 }
                        ?.let { put("serial_number", JsonPrimitive(it)) }
                }.onFailure {
                    put("api34_properties_note", JsonPrimitive(
                        "部分 API 34+ 电池属性不可用：${it.javaClass.simpleName}"
                    ))
                }
            }
        }
    }
}

/**
 * 电池工具 —— **只读**。
 *
 * 之所以单独提供，是因为提示词里的 `{{battery_level}}` 占位符只在**发消息那一刻**取值，
 * 模型无法在对话中途重新查询；而这个工具可以在需要时随时读到最新状态
 * （电量、充放电、温度、电压、电流等）。
 */
fun createBatteryTool(context: Context): Tool = Tool(
    name = "battery_info",
    description = """
        Read the device battery state: percentage, charging status and source, health,
        temperature, voltage, current and charge counter.
        Read-only — it changes nothing on the device.
        Some fields may be missing depending on the device/ROM (many vendors do not implement
        every BatteryManager property); when the percentage cannot be read it is reported as -1
        with a note, never faked as 0.
    """.trimIndent().replace("\n", " "),
    needsApproval = { false },
    // 无参数：只读快照
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        listOf(UIMessagePart.Text(BatteryInfo.snapshot(context).toString()))
    },
)
