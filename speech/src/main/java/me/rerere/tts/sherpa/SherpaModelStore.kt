package me.rerere.tts.sherpa

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "SherpaModelStore"

/**
 * 模型索引来源。
 *
 * 设计取舍：**内置精选 + 在线刷新** 双轨。
 *  - 内置（[SherpaModelCatalog.ALL]）：离线可用，覆盖中文与角色音色等常用项
 *  - 在线（[SherpaModelStore.refresh]）：从官方 release 拉全量清单并缓存到本地
 *
 * 只读一次网络、结果落盘，之后离线也能看全量列表。
 */
enum class ModelIndexSource { BUILTIN, REMOTE }

/** 在线清单的本地缓存结构（独立 DTO，避免污染 UI 用的 SherpaModelInfo） */
@Serializable
private data class CachedIndex(
    @SerialName("fetchedAt") val fetchedAt: Long,
    @SerialName("models") val models: List<CachedModel>,
)

@Serializable
private data class CachedModel(
    @SerialName("file") val fileName: String,
    @SerialName("size") val sizeBytes: Long,
)

/**
 * sherpa-onnx 模型清单的在线获取 + 本地缓存。
 *
 * 数据源：官方 release `tts-models` 的资产列表
 *   https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/tts-models
 *
 * 注意：
 *  - 该接口**未认证**时限流 60 次/小时。刷新是手动操作，正常使用不会触发。
 *  - 只收录 **VITS** 系列（`vits-*` / `sherpa-onnx-vits-*`）。
 *    原因：本项目的 TTS provider 目前只配置 `OfflineTtsVitsModelConfig`。
 *    Kokoro / Matcha / Kitten / ZipVoice 需要**不同的配置类与文件结构**，
 *    列出来会让用户下载后无法使用 —— 宁可不显示，也不要误导。
 */
class SherpaModelStore(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.MINUTES)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val cacheFile: File
        get() = File(context.filesDir, "tts_models/_index.json")

    /** 上次成功刷新的时间；0 表示从未刷新 */
    fun lastFetchedAt(): Long = runCatching { readCache()?.fetchedAt ?: 0L }.getOrDefault(0L)

    /**
     * 当前应展示的清单：内置 ∪ 缓存（缓存为空时即内置）。
     * 纯本地操作，可在 composable 里直接调（不涉及网络）。
     */
    fun currentModels(): List<SherpaModelInfo> {
        val builtin = SherpaModelCatalog.ALL
        val cached = runCatching { readCache()?.models }.getOrNull().orEmpty()
        if (cached.isEmpty()) return builtin
        return merge(builtin, cached)
    }

    /**
     * 从官方 release 拉取全量清单并落盘。
     * 返回合并后的列表；失败抛异常（调用方展示错误）。
     */
    suspend fun refresh(): List<SherpaModelInfo> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(INDEX_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "RikkaSharp")
            .build()
        val body = http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException(
                    "获取模型清单失败：HTTP ${resp.code}" +
                        if (resp.code == 403) "（可能是 GitHub 接口限流，稍后再试）" else ""
                )
            }
            resp.body.string()
        }

        val models = parseAssets(body)
        if (models.isEmpty()) {
            throw IllegalStateException("模型清单为空（接口返回格式可能已变化）")
        }

        writeCache(CachedIndex(System.currentTimeMillis(), models))
        Log.i(TAG, "refresh ok: ${models.size} models")
        merge(SherpaModelCatalog.ALL, models)
    }

    /** 解析 release JSON 的 assets，过滤出 VITS 模型 */
    private fun parseAssets(jsonText: String): List<CachedModel> {
        val root = runCatching { json.parseToJsonElement(jsonText).jsonObject }.getOrNull()
            ?: return emptyList()
        val assets: JsonArray = root["assets"] as? JsonArray ?: return emptyList()
        return assets.mapNotNull { el ->
            val a: JsonObject = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val name = a["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val size = a["size"]?.jsonPrimitive?.longOrNull ?: 0L
            if (!name.endsWith(".tar.bz2")) return@mapNotNull null
            if (!isSupportedModelName(name)) return@mapNotNull null
            CachedModel(name, size)
        }
    }

    private fun isSupportedModelName(fileName: String): Boolean {
        val n = fileName.lowercase()
        if (!(n.startsWith("vits-") || n.startsWith("sherpa-onnx-vits-"))) return false
        if (n.contains("lexicon") || n.contains("espeak") || n.contains("checksum")) return false
        return true
    }

    private fun merge(
        builtin: List<SherpaModelInfo>,
        remote: List<CachedModel>,
    ): List<SherpaModelInfo> {
        val byFile = linkedMapOf<String, SherpaModelInfo>()
        // 内置优先（带精心准备的显示名与描述）
        builtin.forEach { byFile[it.fileName] = it }
        remote.forEach { r ->
            val old = byFile[r.fileName]
            if (old != null) {
                // 内置项不覆盖，但用在线数据校正体积（避免硬编码数字过时）
                if (r.sizeBytes > 0 && r.sizeBytes != old.sizeBytes) {
                    byFile[r.fileName] = old.copy(
                        sizeBytes = r.sizeBytes,
                        extractedBytes = estimateExtracted(r.sizeBytes),
                    )
                }
            } else {
                byFile[r.fileName] = SherpaModelInfo.fromFileName(r.fileName, r.sizeBytes)
            }
        }
        val builtinFiles = builtin.map { it.fileName }.toSet()
        val head = builtin.mapNotNull { byFile[it.fileName] }
        val tail = byFile.values.filter { it.fileName !in builtinFiles }.sortedBy { it.fileName }
        return head + tail
    }

    private fun writeCache(index: CachedIndex) {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(json.encodeToString(CachedIndex.serializer(), index))
        }.onFailure { Log.w(TAG, "writeCache failed", it) }
    }

    private fun readCache(): CachedIndex? = runCatching {
        if (!cacheFile.isFile) null
        else json.decodeFromString(CachedIndex.serializer(), cacheFile.readText())
    }.getOrNull()

    companion object {
        const val INDEX_URL =
            "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/tts-models"

        /**
         * 解压后体积估算。实测差异很大：
         *   · vits-icefall-zh-aishell3  30MB → 204MB（约 6.5 倍，大头是 173MB 的 rule.far）
         *   · vits-zh-hf-theresa       115MB → 约 160MB（约 1.4 倍，无 rule.far）
         * 取 3 倍作为中性估计，仅供空间提示，不作强约束。
         */
        internal fun estimateExtracted(compressed: Long): Long =
            (compressed * 3).coerceAtLeast(compressed)
    }
}
