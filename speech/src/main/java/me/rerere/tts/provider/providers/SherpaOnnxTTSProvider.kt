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
 *  - **一次性合成**：当前用非流式 `generateWithConfig`。
 *    （曾用带回调的流式版本 `generateWithConfigAndCallback`，但 App 内播放时崩溃；
 *      底层 C API 已排除 .so/模型/并发/多线程/规则文件等因素，故先规避 JNI 回调路径。
 *      待确认稳定后可再评估恢复流式。）
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

        diag(context, "TTS", "generateSpeech 开始 dir=${dir.absolutePath} text=${request.text.take(20)}")

        var pcmResult: ByteArray? = null
        var outSampleRate = 0
        val chunkMeta = mutableMapOf<String, String>()

        // JNI 调用是阻塞的，放到 IO 线程
        withContext(Dispatchers.IO) {
          SherpaTtsCache.withNative {
            diag(context, "TTS", "准备加载模型 threads=${providerSetting.numThreads}")

            // ★ 显式、提前加载 native 库 —— 用于**区分崩溃阶段**。
            //
            // Tts.kt 在 companion object 的 init 里调用 System.loadLibrary("sherpa-onnx-jni")，
            // 而该初始化由「首次引用 OfflineTts 类」隐式触发 —— 也就是下面 obtainLocked 里
            // 构造 OfflineTts 的那一刻。因此原本无法区分崩溃发生在
            //   ① dlopen 动态库阶段，还是 ② 库内 ORT 初始化/模型解析阶段。
            //
            // 这里提前显式加载（loadLibrary 是幂等的，重复调用无副作用），
            // 并在前后各写一条日志，崩溃后即可从日志判断：
            //   只有 "开始 loadLibrary"   → 崩在 dlopen（动态库本身的问题）
            //   有 "loadLibrary 成功" 但无 "模型就绪" → 崩在库内部（ORT/模型解析）
            diag(context, "TTS", "开始 System.loadLibrary(sherpa-onnx-jni)")
            try {
                System.loadLibrary("sherpa-onnx-jni")
                diag(context, "TTS", "loadLibrary 成功 ✓")
            } catch (t: Throwable) {
                diag(context, "TTS", "loadLibrary 失败: ${t.javaClass.name}: ${t.message}")
                throw IllegalStateException(
                    "无法加载本地 TTS 的 native 库：${t.javaClass.simpleName}: ${t.message}", t
                )
            }

            val tts = SherpaTtsCache.obtainLocked(dir, providerSetting)
            diag(context, "TTS", "模型就绪")

            val sr = tts.sampleRate()
            val numSpeakers = runCatching { tts.numSpeakers() }.getOrDefault(0)
            // sid 越界会让 native 侧行为未定义，这里夹紧到合法范围
            val sid = if (numSpeakers > 0) providerSetting.speakerId.coerceIn(0, numSpeakers - 1) else 0
            outSampleRate = sr
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

            // ★ 改用**非流式** generateWithConfig，不再使用带回调的流式版本。
            //
            // 依据：在设备 Termux 里用 C API 直接测过 sherpa-onnx 底层 ——
            //   模型加载 / 单次合成 / 4 线程并发 / num_threads=2 /
            //   4 个 rule_fsts / 173MB 的 rule_far 全部通过，未崩溃。
            // 但 App 仍崩，说明差异在 **JNI 路径**（静态版 .so 与动态版 c-api.so 是不同构建）。
            // 带回调的 JNI 版本会从 native 线程回调进 Kotlin，是唯一未验证的路径，
            // 因此先改用无回调的版本以规避风险 —— 代价是失去流式（首字延迟略高），
            // 收益是把崩溃面收敛到最小。等确认可用后再考虑恢复流式。
            diag(context, "TTS", "调用 generateWithConfig（非流式）sid=$sid speed=${providerSetting.speed}")
            val audio = tts.generateWithConfig(request.text, gen)
            diag(context, "TTS", "合成返回 samples=${audio.samples.size} sampleRate=${audio.sampleRate}")

            val pcm = floatToPcm16(audio.samples)
            diag(context, "TTS", "转 PCM 完成 bytes=${pcm.size}")
            pcmResult = pcm
            outSampleRate = audio.sampleRate
          }
        }

        val pcm = pcmResult
        // sampleRate=0 会生成非法的 WAV 头（AudioPlayer.pcmToWav 直接采用该值），
        // 兜底为 22050（VITS 中文模型常见采样率之一）
        if (outSampleRate <= 0) outSampleRate = 22050
        if (pcm != null && pcm.isNotEmpty()) {
            send(
                AudioChunk(
                    data = pcm,
                    format = AudioFormat.PCM,
                    sampleRate = outSampleRate,
                    isLast = false,
                    metadata = chunkMeta,
                )
            )
        } else {
            diag(context, "TTS", "警告：合成结果为空")
        }
        send(
            AudioChunk(
                data = ByteArray(0),
                format = AudioFormat.PCM,
                sampleRate = outSampleRate,
                isLast = true,
                metadata = chunkMeta,
            )
        )
        diag(context, "TTS", "generateSpeech 结束")
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

        // ★ data_dir 只在其**确实是 espeak-ng 数据目录**时才设置 —— 判据是存在 phontab。
        //
        // 踩过的坑：部分中文模型（如 vits-zh-hf-theresa / 刻晴 / 优菈 等 804 speakers 系列）
        // 目录下有个 `dict/`，里面放的是 **jieba 中文分词词典**（jieba.dict.utf8 / idf.utf8 /
        // hmm_model.utf8 …），**不是** VITS 的 espeak-ng data_dir。
        // 早期实现见到 dict/ 就当作 data_dir 传入，sherpa-onnx 随即在校验时报
        //   offline-tts-vits-model-config.cc:Validate: '/…/dict/phontab' does not exist
        // 创建随之失败；在 App 内表现为**播放时直接闪退**（无 Java 异常、无崩溃 Toast）。
        // aishell3 因为**没有** dict/ 目录而不受影响，所以只在角色模型上暴露。
        //
        // 实测（设备 Termux + C API，同一个 theresa 模型）：
        //   传 data_dir=dict  → 创建失败
        //   不传 data_dir     → 创建成功，sampleRate=22050、numSpeakers=804、合成正常
        val dataDir = File(dir, "dict").let {
            if (it.isDirectory && File(it, "phontab").isFile) it.absolutePath else ""
        }

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
        // OfflineTts 构造在 native 侧失败时会抛 IllegalArgumentException
        // （Tts.kt 里的 require(ptr != 0L)）。这里统一转成带排查指引的异常，
        // 避免上层只拿到一句 "Invalid OfflineTtsConfig"。
        val tts = try {
            OfflineTts(config = config)
        } catch (e: Throwable) {
            throw IllegalStateException(
                "本地 TTS 模型加载失败：${dir.name}\n" +
                    "模型文件：${modelFile.name}\n" +
                    "可能原因：模型文件不完整（重新下载）、或该模型的配置不被当前 sherpa-onnx 版本支持。\n" +
                    "原始错误：${e.message}",
                e,
            )
        }
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
