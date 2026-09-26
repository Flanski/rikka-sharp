package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import kotlin.coroutines.resume

/**
 * 设备传感器工具。
 *
 * 设计要点：
 * 1. **只暴露本机真实存在的传感器** —— [SensorCatalog.available] 用 SensorManager 实际探测，
 *    避免在有/无某硬件的机型上出现无效开关。
 * 2. **开关状态按助手存** —— [me.rerere.rikkahub.data.model.Assistant.enabledSensors] 存 key 集合，
 *    只有被放行的传感器才会被读取（隐私最小化）。
 * 3. 需要运行时权限的传感器（定位 / 计步）在未授权时返回明确错误，而不是空值。
 */
object SensorCatalog {

    /** 定位不是 SensorManager 的传感器，单独给一个 key。 */
    const val LOCATION_KEY = "location"

    enum class Kind { ANDROID_SENSOR, LOCATION }

    data class Entry(
        /** 稳定标识：`sensor:<type int>` 或 [LOCATION_KEY]。用 type 而非名字，跨设备一致。 */
        val key: String,
        @StringRes val nameRes: Int,
        val kind: Kind,
        val sensorType: Int = -1,
        /** 需要授予的运行时权限（null = 不需要） */
        val permission: String? = null,
        /** SensorManager 里的传感器对象名（用于 UI 展示实际驱动名） */
        val hardwareName: String? = null,
    )

    private val STEP_PERMISSION: String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Manifest.permission.ACTIVITY_RECOGNITION else null

    /** 候选清单。用户需求指定：加速度/磁力(罗盘)/方向/重力/陀螺/计步 + 气压 + GPS；
     *  另含线性加速度与旋转矢量（融合传感器，对方向类推理很有用）。不含光线、接近。 */
    @Suppress("DEPRECATION")
    val ALL: List<Entry> = listOf(
        Entry("sensor:${Sensor.TYPE_ACCELEROMETER}", R.string.sensor_name_accelerometer,
            Kind.ANDROID_SENSOR, Sensor.TYPE_ACCELEROMETER),
        Entry("sensor:${Sensor.TYPE_MAGNETIC_FIELD}", R.string.sensor_name_magnetic_field,
            Kind.ANDROID_SENSOR, Sensor.TYPE_MAGNETIC_FIELD),
        Entry("sensor:${Sensor.TYPE_ORIENTATION}", R.string.sensor_name_orientation,
            Kind.ANDROID_SENSOR, Sensor.TYPE_ORIENTATION),
        Entry("sensor:${Sensor.TYPE_GRAVITY}", R.string.sensor_name_gravity,
            Kind.ANDROID_SENSOR, Sensor.TYPE_GRAVITY),
        Entry("sensor:${Sensor.TYPE_GYROSCOPE}", R.string.sensor_name_gyroscope,
            Kind.ANDROID_SENSOR, Sensor.TYPE_GYROSCOPE),
        Entry("sensor:${Sensor.TYPE_LINEAR_ACCELERATION}", R.string.sensor_name_linear_acceleration,
            Kind.ANDROID_SENSOR, Sensor.TYPE_LINEAR_ACCELERATION),
        Entry("sensor:${Sensor.TYPE_ROTATION_VECTOR}", R.string.sensor_name_rotation_vector,
            Kind.ANDROID_SENSOR, Sensor.TYPE_ROTATION_VECTOR),
        Entry("sensor:${Sensor.TYPE_STEP_COUNTER}", R.string.sensor_name_step_counter,
            Kind.ANDROID_SENSOR, Sensor.TYPE_STEP_COUNTER, STEP_PERMISSION),
        Entry("sensor:${Sensor.TYPE_PRESSURE}", R.string.sensor_name_pressure,
            Kind.ANDROID_SENSOR, Sensor.TYPE_PRESSURE),
        Entry(LOCATION_KEY, R.string.sensor_name_location, Kind.LOCATION,
            permission = Manifest.permission.ACCESS_FINE_LOCATION),
    )

    /** 本机真实存在的传感器（含 SensorManager 里的硬件名） */
    fun available(context: Context): List<Entry> {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return emptyList()
        return ALL.mapNotNull { entry ->
            when (entry.kind) {
                Kind.ANDROID_SENSOR -> {
                    val s = runCatching { sm.getDefaultSensor(entry.sensorType) }.getOrNull()
                    if (s == null) null else entry.copy(hardwareName = s.name)
                }

                Kind.LOCATION -> {
                    val hasGps = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)
                    val hasAny = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION)
                    if (hasGps || hasAny) entry else null
                }
            }
        }
    }

    fun find(key: String): Entry? = ALL.firstOrNull { it.key == key }

    fun hasPermission(context: Context, entry: Entry): Boolean {
        val p = entry.permission ?: return true
        return ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    }
}

/** 一条传感器读数 */
private data class Reading(
    val key: String,
    val name: String,
    val type: String,
    val values: List<Double>,
    val unit: String,
    val accuracy: Int? = null,
    /** 定位专有：数据距今多少秒（越大越不可信） */
    val ageSeconds: Double? = null,
    val provider: String? = null,
)

private fun round(v: Float, digits: Int = 4): Double {
    val f = Math.pow(10.0, digits.toDouble())
    return Math.round(v.toDouble() * f) / f
}

/** 读一次 Android 传感器（首个回调），带超时 */
private suspend fun readAndroidSensor(
    context: Context,
    entry: SensorCatalog.Entry,
    timeoutMs: Long,
): Reading? = withContext(Dispatchers.Main) {
    val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return@withContext null
    val sensor = runCatching { sm.getDefaultSensor(entry.sensorType) }.getOrNull() ?: return@withContext null
    withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    runCatching { sm.unregisterListener(this) }
                    if (cont.isActive) {
                        cont.resume(
                            Reading(
                                key = entry.key,
                                name = context.getString(entry.nameRes),
                                type = runCatching { sensor.stringType }.getOrNull()
                                    ?: "android.sensor.type_${sensor.type}",
                                values = event.values.map { round(it) },
                                unit = sensorUnit(sensor.type),
                                accuracy = event.accuracy,
                            )
                        )
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            cont.invokeOnCancellation { runCatching { sm.unregisterListener(listener) } }
            val ok = runCatching {
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            }.getOrDefault(false)
            if (!ok && cont.isActive) cont.resume(null)
        }
    }
}

/** 读定位：取所有 provider 中最近一次的已知位置（不做耗时的主动定位） */
private fun readLocation(context: Context, entry: SensorCatalog.Entry): Reading? {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    )
    val best = providers
        .mapNotNull { p ->
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let { p to it }
        }
        .maxByOrNull { it.second.time }
        ?: return null
    val (provider, loc) = best
    val ageSec = (System.currentTimeMillis() - loc.time) / 1000.0
    return Reading(
        key = entry.key,
        name = context.getString(entry.nameRes),
        type = "location",
        values = listOf(round(loc.latitude.toFloat(), 6), round(loc.longitude.toFloat(), 6))
            .plus(if (loc.hasAltitude()) listOf(round(loc.altitude.toFloat(), 2)) else emptyList()),
        unit = if (loc.hasAltitude()) "lat,lon,altitude_m" else "lat,lon",
        ageSeconds = round(ageSec.toFloat(), 1),
        provider = provider,
    )
}

private fun sensorUnit(type: Int): String = when (type) {
    Sensor.TYPE_ACCELEROMETER,
    Sensor.TYPE_GRAVITY,
    Sensor.TYPE_LINEAR_ACCELERATION -> "m/s^2"

    Sensor.TYPE_GYROSCOPE -> "rad/s"
    Sensor.TYPE_MAGNETIC_FIELD -> "uT"
    Sensor.TYPE_PRESSURE -> "hPa"
    Sensor.TYPE_LIGHT -> "lx"
    Sensor.TYPE_PROXIMITY -> "cm"
    Sensor.TYPE_RELATIVE_HUMIDITY -> "%"
    Sensor.TYPE_AMBIENT_TEMPERATURE -> "degC"
    else -> ""
}

/**
 * 设备传感器工具。只返回 [enabledKeys] 里放行的传感器。
 */
fun createSensorTool(context: Context, enabledKeys: Set<String>): Tool = Tool(
    name = "get_sensors",
    description = """
        Read the device's physical sensors. Only sensors the user has allowed are readable.

        Available kinds (when present on the device and enabled by the user):
        accelerometer, magnetometer (compass), orientation, gravity, gyroscope,
        linear acceleration, rotation vector, step counter, barometer, location (GPS).

        Args:
        - sensors: optional array of keys to read (e.g. ["sensor:1","location"]).
          Omit to read every enabled sensor.
        - timeout: per-sensor wait in seconds, default 2, max 10. Sensors that do not
          deliver a sample within the timeout are reported in `errors`.

        Response (JSON):
        - timestamp, count
        - sensors: one entry per sensor, with
            key, name, type, values, unit, accuracy (android sensors) or
            ageSeconds + provider (location)
        - errors: sensors that could not be read, with a reason
          (e.g. permission not granted, timed out, no reading yet)
        - available_but_disabled: sensors present on the device but not enabled by the user;
          if the request needs one of those, ask the user to enable it in
          Settings -> assistant -> Local tools -> Device sensors.

        Notes:
        - Location returns the most recent cached fix and its age; it is not a live GPS fix.
        - Rotation vector / orientation describe device attitude; use them for compass-like questions.
    """.trimIndent().replace("\n", " "),
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("sensors", buildJsonObject {
                    put("type", "array")
                    put("description", "Optional list of sensor keys to read, e.g. [\"sensor:1\",\"location\"]")
                    put("items", buildJsonObject { put("type", "string") })
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put("description", "Per-sensor wait in seconds (default 2, max 10)")
                })
            },
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val timeoutSec = (obj["timeout"]?.jsonPrimitive?.intOrNull ?: 2).coerceIn(1, 10)
        val requested: List<String>? = obj["sensors"]?.let { el ->
            runCatching {
                (el as kotlinx.serialization.json.JsonArray)
                    .mapNotNull { it.jsonPrimitive.contentOrNull }
            }.getOrNull()
        }

        val present = SensorCatalog.available(context)
        val presentKeys = present.map { it.key }.toSet()
        // 目标 = 请求的 ∩ (本机存在 ∩ 已放行)；未指定则 = 已放行的全部
        val targets = if (requested.isNullOrEmpty()) {
            present.filter { it.key in enabledKeys }
        } else {
            requested.mapNotNull { SensorCatalog.find(it) }.filter { it.key in presentKeys && it.key in enabledKeys }
        }

        val readings = mutableListOf<Reading>()
        val errors = mutableListOf<Triple<String, String, String>>()   // key, name, reason

        for (entry in targets) {
            if (!SensorCatalog.hasPermission(context, entry)) {
                errors.add(Triple(entry.key, context.getString(entry.nameRes), "permission_not_granted"))
                continue
            }
            val r = when (entry.kind) {
                SensorCatalog.Kind.ANDROID_SENSOR -> readAndroidSensor(context, entry, timeoutSec * 1000L)
                SensorCatalog.Kind.LOCATION -> withContext(Dispatchers.IO) { readLocation(context, entry) }
            }
            if (r == null) {
                errors.add(
                    Triple(
                        entry.key, context.getString(entry.nameRes),
                        if (entry.kind == SensorCatalog.Kind.LOCATION) "no_cached_location_yet" else "no_sample_within_timeout"
                    )
                )
            } else {
                readings.add(r)
            }
        }

        // 请求了但未放行 / 本机不存在的
        val notEnabled = mutableListOf<Triple<String, String, String>>()
        if (!requested.isNullOrEmpty()) {
            for (key in requested) {
                if (key in enabledKeys) continue
                val e = SensorCatalog.find(key)
                if (e != null && e.key in presentKeys) {
                    notEnabled.add(Triple(e.key, context.getString(e.nameRes), "disabled_by_user"))
                }
            }
        }

        val payload = buildJsonObject {
            put("timestamp", JsonPrimitive(System.currentTimeMillis()))
            put("count", JsonPrimitive(readings.size))
            put("sensors", buildJsonArray {
                readings.forEach { r ->
                    addJsonObject {
                        put("key", JsonPrimitive(r.key))
                        put("name", JsonPrimitive(r.name))
                        put("type", JsonPrimitive(r.type))
                        put("values", buildJsonArray { r.values.forEach { add(JsonPrimitive(it)) } })
                        put("unit", JsonPrimitive(r.unit))
                        r.accuracy?.let { put("accuracy", JsonPrimitive(it)) }
                        r.ageSeconds?.let { put("ageSeconds", JsonPrimitive(it)) }
                        r.provider?.let { put("provider", JsonPrimitive(it)) }
                    }
                }
            })
            if (errors.isNotEmpty()) {
                put("errors", buildJsonArray {
                    errors.forEach { (k, n, why) ->
                        addJsonObject {
                            put("key", JsonPrimitive(k)); put("name", JsonPrimitive(n)); put("reason", JsonPrimitive(why))
                        }
                    }
                })
            }
            if (notEnabled.isNotEmpty()) {
                put("requested_but_disabled", buildJsonArray {
                    notEnabled.forEach { (k, n, why) ->
                        addJsonObject {
                            put("key", JsonPrimitive(k)); put("name", JsonPrimitive(n)); put("reason", JsonPrimitive(why))
                        }
                    }
                })
            }
            val disabledButPresent = present.filter { it.key !in enabledKeys }.map { it.key }
            if (disabledButPresent.isNotEmpty()) {
                put("available_but_disabled", buildJsonArray { disabledButPresent.forEach { add(JsonPrimitive(it)) } })
            }
            if (readings.isEmpty() && errors.isEmpty() && targets.isEmpty()) {
                put(
                    "hint",
                    JsonPrimitive(
                        if (enabledKeys.isEmpty())
                            "No sensor is enabled for this assistant. Ask the user to enable some in " +
                                "Settings -> assistant -> Local tools -> Device sensors."
                        else "None of the enabled sensors exist on this device."
                    )
                )
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)
