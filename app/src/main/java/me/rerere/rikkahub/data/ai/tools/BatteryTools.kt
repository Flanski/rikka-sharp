package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * 电池信息读取的**统一实现**（工具与提示词占位符共用）。
 *
 * ── 为什么要多路回退 ──
 * 原先项目的 `battery_level` 占位符只用了
 * `BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY)`。
 * 该属性在部分设备/系统（尤其华为系与定制 ROM）上会返回 **0**，
 * 于是提示词里的电量恒为 0 —— 用户反馈的「电量变量拿不到」正是这种表现。
 *
 * 这里按可靠性排序依次尝试：
 *   1. sticky broadcast `ACTION_BATTERY_CHANGED` 的 `EXTRA_LEVEL / EXTRA_SCALE`
 *      —— Android 最古老的电池接口，覆盖面最广、几乎不会失败
 *   2. `BATTERY_PROPERTY_CAPACITY`（限定 1..100 才算有效）
 *   3. 都失败时返回 null / -1（**绝不用 0 冒充真实电量**：
 *      模型看到 0 会以为「没电了」，看到 -1 才能意识到是「读取失败」）
 *
 * 读取 sticky broadcast 用 `registerReceiver(null, filter)`：
 * 不注册接收者、无需任何权限，也不受 Android 14 导出标志要求的约束。
 *
 * ── ★只用「编译可见」的常量（重要）──
 * AOSP **源码**里的常量不等于编译用的 `android.jar` 里有。判据（已实测归纳）：
 *   · `@hide` / `@SystemApi` / `@UnsupportedAppUsage` → **编译不可见**，不能用
 *   · `@FlaggedApi`                                     → **编译可见**（运行时受 flag 影响）
 *   · 无注解                                            → 公开
 * 本文件已据此剔除过 7 个 `@hide`/`@SystemApi` 常量
 * （`EXTRA_CHARGE_COUNTER`、`EXTRA_MAX_CHARGING_CURRENT/VOLTAGE`、
 *   `BATTERY_PROPERTY_{STATE_OF_HEALTH,MANUFACTURING_DATE,FIRST_USAGE_DATE,SERIAL_NUMBER}`）。
 * 工具 `/workspace/rp_dev/tools/api_check.py` 可核对符号是否存在，
 * 但**它无法判断 @hide** —— 那一点只能靠上面的判据人工确认。
 */
object BatteryInfo {

    /** 读原始电池广播；拿不到返回 null */
    private fun batteryIntent(context: Context): Intent? =
        runCatching {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

    /**
     * 电量百分比。
     * 返回 null 表示两种途径都拿不到（调用方应显示「未知」而不是 0）。
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

        /** 读 BatteryManager 属性；无效值（0 或 MIN_VALUE）视为不可用 */
        fun prop(key: Int): Int? = runCatching {
            bm?.getIntProperty(key)?.takeIf { it != Int.MIN_VALUE && it != 0 }
        }.getOrNull()

        return buildJsonObject {
            // ── 电量（多路回退）──
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

            // ── 充电状态 ──
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            put("status", JsonPrimitive(statusName(status)))
            put("is_charging", JsonPrimitive(
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            ))

            // ── 充电来源 ──
            val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            put("plugged", JsonPrimitive(pluggedName(plugged)))

            // ── 健康度 ──
            put("health", JsonPrimitive(healthName(i.getIntExtra(BatteryManager.EXTRA_HEALTH, -1))))

            // ── 温度（单位 0.1℃）──
            i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                .takeIf { it != Int.MIN_VALUE }
                ?.let { put("temperature_c", JsonPrimitive(it / 10.0)) }

            // ── 电压（单位 mV）──
            i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
                .takeIf { it > 0 }
                ?.let { put("voltage_v", JsonPrimitive(it / 1000.0)) }

            i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.takeIf { it.isNotBlank() }
                ?.let { put("technology", JsonPrimitive(it)) }

            // ── 容量等级（API 28+ 的粗粒度指示）──
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val lv = i.getIntExtra(BatteryManager.EXTRA_CAPACITY_LEVEL, Int.MIN_VALUE)
                capacityLevelName(lv)?.let { put("capacity_level", JsonPrimitive(it)) }
            }

            // ── 其它公开字段 ──
            // 循环次数：电池健康度的关键指标（EXTRA_CYCLE_COUNT 无隐藏注解，编译可见）
            i.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1).takeIf { it >= 0 }
                ?.let { put("cycle_count", JsonPrimitive(it)) }
            if (i.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, false)) {
                put("battery_low", JsonPrimitive(true))
            }
            if (!i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)) {
                put("battery_present", JsonPrimitive(false))
            }

            // ── 来自 BatteryManager 的数值（单位见注释）──
            // 官方定义 BATTERY_PROPERTY_CURRENT_NOW 为**微安(µA)**，但部分厂商返回毫安(mA)，
            // 因此同时给出原始值与按 µA 换算的 mA，不擅自假定某一种。
            prop(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.let {
                put("current_now_raw", JsonPrimitive(it))
                put("current_now_ua_as_ma", JsonPrimitive(it / 1000.0))
                put("current_note", JsonPrimitive(
                    "官方定义单位为µA；部分厂商返回mA。current_now_ua_as_ma 是按µA换算的结果。"
                ))
            }
            prop(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)?.let {
                put("current_average_raw", JsonPrimitive(it))
                put("current_average_ua_as_ma", JsonPrimitive(it / 1000.0))
            }
            prop(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.let {
                put("charge_counter_mah", JsonPrimitive(it / 1000.0))  // µAh → mAh
            }
            prop(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)?.let {
                put("energy_counter_mwh", JsonPrimitive(it / 1_000_000.0))  // nWh → mWh
            }
            prop(BatteryManager.BATTERY_PROPERTY_STATUS)?.let {
                put("status_property", JsonPrimitive(statusName(it)))
            }
        }
    }

    private fun statusName(status: Int): String = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
        BatteryManager.BATTERY_STATUS_UNKNOWN -> "unknown"
        else -> "unknown"
    }

    private fun pluggedName(plugged: Int): String = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        BatteryManager.BATTERY_PLUGGED_DOCK -> "dock"
        else -> "none"
    }

    private fun healthName(health: Int): String = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "cold"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
        BatteryManager.BATTERY_HEALTH_UNKNOWN -> "unknown"
        else -> "unknown"
    }

    /**
     * 容量等级名称。
     *
     * AOSP 的完整清单只有 7 个：
     *   UNSUPPORTED(-1) / UNKNOWN(0) / CRITICAL(1) / LOW(2) / NORMAL(3) / HIGH(4) / FULL(5)
     * **没有 MEDIUM** —— 我先前凭印象写过它，导致编译失败。
     * 返回 null 表示「不是已知取值」，调用方就不写该字段。
     */
    private fun capacityLevelName(level: Int): String? = when (level) {
        BatteryManager.BATTERY_CAPACITY_LEVEL_CRITICAL -> "critical"
        BatteryManager.BATTERY_CAPACITY_LEVEL_LOW -> "low"
        BatteryManager.BATTERY_CAPACITY_LEVEL_NORMAL -> "normal"
        BatteryManager.BATTERY_CAPACITY_LEVEL_HIGH -> "high"
        BatteryManager.BATTERY_CAPACITY_LEVEL_FULL -> "full"
        BatteryManager.BATTERY_CAPACITY_LEVEL_UNKNOWN -> "unknown"
        BatteryManager.BATTERY_CAPACITY_LEVEL_UNSUPPORTED -> "unsupported"
        else -> null
    }
}

/**
 * 电池工具 —— **只读**。
 *
 * 之所以单独提供：提示词里的 `{{battery_level}}` 占位符只在**发消息那一刻**取值，
 * 模型无法在对话中途重新查询；而这个工具可随时读到最新状态
 * （电量、充放电、温度、电压、电流、循环次数等）。
 */
fun createBatteryTool(context: Context): Tool = Tool(
    name = "battery_info",
    description = """
        Read the device battery state: percentage, charging status and source, health,
        temperature, voltage, current, charge counter and cycle count.
        Read-only — it changes nothing on the device.
        Some fields may be missing depending on the device/ROM (many vendors do not implement
        every BatteryManager property); when a value cannot be read it is simply omitted,
        and the percentage is reported as -1 with a note rather than faked as 0.
    """.trimIndent().replace("\n", " "),
    needsApproval = { false },
    // 无参数：只读快照
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        listOf(UIMessagePart.Text(BatteryInfo.snapshot(context).toString()))
    },
)
