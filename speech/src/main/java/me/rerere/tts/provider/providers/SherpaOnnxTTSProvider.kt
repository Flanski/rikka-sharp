package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import java.io.File

private const val TAG = "SherpaOnnxTTSProvider"

/**
 * 本地（离线）TTS —— 基于 sherpa-onnx + VITS 中文多说话人模型。
 *
 * 相比系统 TTS（只能调语速与音高）的核心能力：
 *  - **音色**：多说话人模型可逐 speaker 切换（`sid`）；例如
 *    `vits-zh-hf-theresa` / `vits-zh-hf-eula` 有 804 个音色，
 *    `vits-icefall-zh-aishell3` 有 174 个。
 *  - **韵律（"语气"）**：VITS 的 `noiseScale` / `noiseScaleW` 控制合成的随机性，
 *    调高会让语调起伏更自然、更少机械感；再配合 `speed`（语速）与
 *    `silenceScale`（句间停顿）可表达不同情绪倾向。
 *  - **流式**：通过 `generateWithConfigAndCallback` 边合成边回传 PCM，
 *    无需等整段合成完（长文本首字延迟明显更低）。
 *
 * 注意（能力边界，勿误传）：VITS/Kokoro 这类模型**不支持内联情感标记**
 * （不像 ChatTTS 的 `[laugh]`）。所谓"带语气"是通过上面三个维度 + AI 用
 * 标点控制停顿来实现的，不是真正的情感标签。
 */
class SherpaOnnxTTSProvider : TTSProvider<TTSProviderSetting.SherpaOnnx> {

    /**
     * 该模型不支持内联标记，因此引导 AI 用**标点与断句**表达语气。
     * 约束与项目既有注释一致：不要用 `*` `_`（会被 stripMarkdown 删掉）、
     * 标记内部不要含 `。，！？…`（会被 TextChunker 切断）。
     */
    override val promptGuidance: String
        get() = """
            你正在用本地神经网络语音朗读，需要通过**标点与断句**来传达语气：
            - 想让语气舒缓或制造停顿：用逗号、省略号、换行，而不是长句一气读完
            - 想强调情绪：把短句独立成句，用感叹号/问号收尾
            - 不要输出 Markdown 标记（星号、下划线）或括号标记，它们不会被朗读出来
            - 只把要朗读的正文放进 text_to_speech 的 text 参数，不要包含说明文字
        """.trimIndent()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.SherpaOnnx,
        request: TTSRequest,
    ): Flow<AudioChunk> = channelFlow {
        val dir = providerSetting.resolvedModelDir(context)
        val error = validateModelDir(dir)
        if (error != null) {
            // 给出可操作的提示，而不是让上层只看到一句 native 报错
            throw IllegalStateException(error)
        }

        // 注意：以下所有操作（加载模型 / 查询说话人 / 合成）都在同一把锁内串行执行。
        // 见 SherpaTtsCache.nativeLock 的说明 —— 并发使用同一 OfflineTts 会导致 native 崩溃。
        var sampleRate = 0
        var numSpeakers = 0
        var sid = 0
        val chunkMeta = mutableMapOf<String, String>()

        // JNI 调用是阻塞的，放到 IO 线程；回调里用 trySend 逐步推给消费者
        withContext(Dispatchers.IO) {
          SherpaTtsCache.withNative {
            // native 崩溃（SIGSEGV）不会被 try-catch 捕获，因此逐步埋点，
            // 崩溃后可据此判断"执行到哪一步"。日志写入外部私有目录，
            // 位置：Android/data/me.rerere.rikkasharp/files/tts_diag.log
            diag(context, "TTS", "准备加载模型 dir=${dir.absolutePath} threads=${providerSetting.numThreads}")
            val tts = SherpaTtsCache.obtainLocked(dir, providerSetting)
            diag(context, "TTS", "模型加载完成")
            sampleRate = tts.sampleRate()
            numSpeakers = runCatching { tts.numSpeakers() }.getOrDefault(0)
            // sid 越界会让 native 侧行为未定义，这里夹紧到合法范围
            sid = if (numSpeakers > 0) providerSetting.speakerId.coerceIn(0, numSpeakers - 1) else 0
            chunkMeta.putAll(
                mapOf(
                    "provider" to "sherpa-onnx",
                    "model" to dir.name,
                    "speakerId" to sid.toString(),
                    "numSpeakers" to numSpeakers.toString(),
                    "speed" to providerSetting.speed.toString(),
                )
            )

            val gen = GenerationConfig(
                speed = providerSetting.speed,
                sid = sid,
                silenceScale = providerSetting.silenceScale,
            )
            var emitted = 0
            var stopped = false
            diag(context, "TTS", "开始合成 text=${request.text.take(30)} sid=$sid speed=${providerSetting.speed} silence=${providerSetting.silenceScale}")

            tts.generateWithConfigAndCallback(request.text, gen) { samples ->
                if (stopped) return@generateWithConfigAndCallback 1
                val pcm = floatToPcm16(samples)
                if (pcm.isEmpty()) {
                    0
                } else {
                    val ok = trySend(
                        AudioChunk(
                            data = pcm,
                            format = AudioFormat.PCM,
                            sampleRate = sampleRate,
                            isLast = false,
                            metadata = chunkMeta,
                        )
                    ).isSuccess
                    if (!ok) {
                        // 下游已取消（例如用户停止播放），通知 native 侧提前收尾
                        stopped = true
                        1
                    } else {
                        emitted++
                        0
                    }
                }
            }

            Log.d(TAG, "generateSpeech: emitted=$emitted chunks, sampleRate=$sampleRate, sid=$sid")
            diag(context, "TTS", "合成结束 emitted=$emitted sampleRate=$sampleRate stopped=$stopped")
            trySend(
                AudioChunk(
                    data = ByteArray(0),
                    format = AudioFormat.PCM,
                    sampleRate = sampleRate,
                    isLast = true,
                    metadata = chunkMeta,
                )
            )
          }
        }
    // channelFlow 默认容量是 RENDEZVOUS（0），此时 trySend 在消费者尚未就绪
    // 或消费稍慢时会**直接失败**；而 native 回调是同步的、不能挂起等待，
    // 一旦失败我的实现就会停止生成 → 结果是空/不完整音频。
    // 用无界缓冲吸收这种速度差（音频总量有限，不会无限增长）。
    }.buffer(Channel.UNLIMITED)

    /**
     * 写诊断日志到外部私有目录 `Android/data/<pkg>/files/tts_diag.log`。
     *
     * 为什么不复用 app 模块的 CrashHandler：依赖方向是 **app → speech**，
     * speech 不能反向引用 app 的类（会编译失败）。因此这里自己写一份。
     *
     * 为什么需要它：native 崩溃（SIGSEGV）不会被 Kotlin 的 try-catch 捕获，
     * 只有在关键步骤落盘，崩溃后才能判断"执行到哪一步"。
     */
    private fun diag(context: Context, tag: String, message: String) {
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: return@runCatching
            val f = File(dir, "tts_diag.log")
            // 避免无限增长
            if (f.exists() && f.length() > 512 * 1024) f.delete()
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            f.appendText("[$ts][$tag] $message\n")
        }
    }

    /** 校验模型目录；返回 null 表示可用，否则返回给用户看的错误信息 */
    private fun validateModelDir(dir: File): String? {
        if (!dir.isDirectory) {
            return "本地 TTS 模型目录不存在：${dir.absolutePath}\n" +
                "请在「设置 → 文字转语音 → 本地模型」中下载或选择模型目录。"
        }
        val model = resolveModelFile(dir)
        if (model == null) {
            return "模型目录中未找到 .onnx 模型文件：${dir.absolutePath}\n" +
                "请确认已完整解压模型包（应包含 model.onnx / lexicon.txt / tokens.txt）。"
        }
        val tokens = File(dir, "tokens.txt")
        if (!tokens.isFile) {
            return "模型目录缺少 tokens.txt：${dir.absolutePath}"
        }
        return null
    }

    companion object {
        /** 模型文件命名在不同模型里可能是 model.onnx / *.onnx，逐个探测 */
        fun resolveModelFile(dir: File): File? {
            val exact = File(dir, "model.onnx")
            if (exact.isFile) return exact
            return dir.listFiles { f -> f.isFile && f.name.endsWith(".onnx") }
                ?.sortedBy { it.name }
                ?.firstOrNull()
        }

        /** FloatArray(-1f..1f) → 16-bit 小端 PCM */
        fun floatToPcm16(samples: FloatArray): ByteArray {
            val out = ByteArray(samples.size * 2)
            var i = 0
            for (s in samples) {
                val v = (s.coerceIn(-1f, 1f) * 32767f).toInt()
                out[i++] = (v and 0xFF).toByte()
                out[i++] = ((v shr 8) and 0xFF).toByte()
            }
            return out
        }
    }
}

/**
 * [OfflineTts] 的实例缓存。
 *
 * 加载 onnx 模型开销较大（读文件 + 初始化会话），而每次朗读都重新构造会明显卡顿，
 * 因此按「模型目录 + 线程数」缓存。仅改 sid/speed/noiseScale 这类**生成期**参数
 * 时命中缓存，不需要重载。
 *
 * 目前只保留一份（单模型场景足够）；换模型时释放旧的。
 */
internal object SherpaTtsCache {
    private var cachedKey: String? = null
    private var cached: OfflineTts? = null

    /**
     * 串行化**所有** native 操作（加载 / 推理 / 释放）。
     *
     * 为什么必须：
     * `TtsController` 会并发预取分片（`prefetchCount = 4`，各自跑在 Dispatchers.IO），
     * 即同一时刻可能有 4 个协程调用同一个 `OfflineTts` 实例。
     * 而 sherpa-onnx 的 `OfflineTts` **不支持并发使用** —— 并发调用会触发
     * native 崩溃（SIGSEGV）：进程直接退出，`TtsController` 里的
     * `catch (e: Exception)` **捕获不到**，用户看到的就是「播放时闪退」。
     *
     * 同一把锁还保护「释放旧实例」，避免推理进行中实例被 free（use-after-free）。
     *
     * 代价：并发合成的 4 个分片会串行执行。这是正确的取舍 ——
     * 本地推理本来就是单实例串行，宁可慢也不要崩。
     */
    private val nativeLock = Mutex()

    /** 在同一把锁内执行 native 操作。**不可重入**（内部不要再调 withNative）。 */
    suspend fun <T> withNative(block: () -> T): T = nativeLock.withLock { block() }

    /** 模型被下载/删除/替换后调用，释放 native 实例并清空缓存 */
    suspend fun invalidateAll() = withNative { invalidateLocked() }

    @Synchronized
    private fun invalidateLocked() {
        runCatching { cached?.free() }.onFailure { Log.w(TAG, "invalidate free failed", it) }
        cached = null
        cachedKey = null
    }

    /**
     * 取得（或加载）OfflineTts 实例。
     * **调用方必须已持有 [withNative] 的锁** —— 加载过程本身也访问 native，
     * 并发加载会出现多个实例同时初始化，既浪费内存也可能崩溃。
     */
    fun obtainLocked(dir: File, setting: TTSProviderSetting.SherpaOnnx): OfflineTts {
        val key = "${dir.absolutePath}|${setting.numThreads}"
        cached?.let { if (cachedKey == key) return it }

        // 释放旧实例，避免 native 内存泄漏
        invalidateLocked()

        val modelFile = SherpaOnnxTTSProvider.resolveModelFile(dir)
            ?: error("未找到 .onnx 模型文件：${dir.absolutePath}")
        val tokens = File(dir, "tokens.txt").absolutePath
        val lexicon = File(dir, "lexicon.txt").let { if (it.isFile) it.absolutePath else "" }
        // 中文模型常带 dict/ 目录（jieba 词典等），没有就留空
        val dataDir = File(dir, "dict").let { if (it.isDirectory) it.absolutePath else "" }

        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = modelFile.absolutePath,
                    lexicon = lexicon,
                    tokens = tokens,
                    dataDir = dataDir,
                    // 韵律随机性：影响语调起伏（"语气"）
                    noiseScale = setting.noiseScale,
                    noiseScaleW = setting.noiseScaleW,
                    // lengthScale 与生成期的 speed 相乘，故此处固定 1.0，统一由 speed 控制
                    lengthScale = 1.0f,
                ),
                numThreads = setting.numThreads,
                debug = false,
                provider = "cpu",
            ),
            // 中文读法规则：只启用体积很小的 *.fst
            // （date/number/phone/new_heteronym，各几十 KB）。
            //
            // **刻意不加载 rule.far**：实测 vits-icefall-zh-aishell3 的 rule.far
            // 有 **173MB**，把它交给 sherpa-onnx 会在 native 侧解析成庞大的规则表，
            // 在手机上很可能 OOM（native 侧 OOM 会直接终止进程，表现为闪退，
            // Kotlin 的 try-catch 捕获不到）。
            //
            // 代价：数字/日期/多音字的读法会不如启用时准确。
            // 收益：排除一个明确的崩溃风险源。等基础功能在真机验证通过后，
            // 若确有需要再考虑提供「加载大规则包」的开关。
            ruleFsts = collectByExtension(dir, "fst"),
            ruleFars = "",
        )

        Log.i(TAG, "loading OfflineTts: model=${modelFile.name}, threads=${setting.numThreads}, dir=${dir.absolutePath}")
        val tts = OfflineTts(config = config)
        cached = tts
        cachedKey = key
        Log.i(TAG, "OfflineTts loaded: sampleRate=${tts.sampleRate()}, speakers=${tts.numSpeakers()}")
        return tts
    }

    /**
     * 收集目录下指定扩展名的规则文件（以逗号分隔，供 sherpa-onnx 使用）。
     * 排序保证结果稳定（sherpa-onnx 按顺序应用规则）。
     */
    private fun collectByExtension(dir: File, ext: String): String =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".$ext") }
            ?.sortedBy { it.name }
            ?.joinToString(",") { it.absolutePath }
            .orEmpty()
}
