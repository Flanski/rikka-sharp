package me.rerere.tts.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
sealed class TTSProviderSetting {
    abstract val id: Uuid
    abstract val name: String

    abstract fun copyProvider(
        id: Uuid = this.id,
        name: String = this.name,
    ): TTSProviderSetting

    @Serializable
    @SerialName("openai")
    data class OpenAI(
        override var id: Uuid = Uuid.random(),
        override var name: String = "OpenAI TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.openai.com/v1",
        val model: String = "gpt-4o-mini-tts",
        val voice: String = "alloy"
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("gemini")
    data class Gemini(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Gemini TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
        val model: String = "gemini-2.5-flash-preview-tts",
        val voiceName: String = "Kore"
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    /**
     * 本地（离线）神经网络语音 —— sherpa-onnx + VITS。
     *
     * 与 [SystemTTS] 的区别：声学模型在设备上推理，可选音色由模型的说话人数决定
     * （如 vits-zh-hf-theresa 有 804 个），并暴露韵律相关参数。
     */
    @Serializable
    @SerialName("sherpa-onnx")
    data class SherpaOnnx(
        override var id: Uuid = Uuid.random(),
        override var name: String = "本地神经网络语音",
        /**
         * 选中的模型 ID（见 SherpaModelCatalog，如 "keqing" / "eula"）。
         * 目录为 filesDir/tts_models/<modelId>；与 [modelDir] 二选一，后者优先。
         */
        var modelId: String = "",
        /** 手动指定的模型目录；非空时优先于 [modelId] */
        var modelDir: String = "",
        /** 音色序号，范围 [0, numSpeakers-1]；越界会在读取时被夹紧 */
        var speakerId: Int = 0,
        /** 语速，1.0 为原速 */
        var speed: Float = 1.0f,
        /** 韵律随机性：越大语调起伏越丰富（更自然、更少机械感） */
        var noiseScale: Float = 0.667f,
        /** 时长预测的随机性 */
        var noiseScaleW: Float = 0.8f,
        /** 句间停顿比例 */
        var silenceScale: Float = 0.2f,
        /** 推理线程数；过大在手机上反而变慢 */
        var numThreads: Int = 2,
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }

        /**
         * 解析实际使用的模型目录，优先级：
         * 1) 手动指定的 [modelDir]
         * 2) [modelId] 对应的 filesDir/tts_models/<modelId>
         * 3) 兜底返回模型根目录（此时通常尚未下载模型，provider 会给出可操作的报错）
         */
        fun resolvedModelDir(context: android.content.Context): java.io.File {
            if (modelDir.isNotBlank()) return java.io.File(modelDir)
            val root = java.io.File(context.filesDir, DEFAULT_MODEL_DIR_NAME)
            return if (modelId.isNotBlank()) java.io.File(root, modelId) else root
        }

        companion object {
            const val DEFAULT_MODEL_DIR_NAME = "tts_models"
        }
    }

    @Serializable
    @SerialName("system")
    data class SystemTTS(
        override var id: Uuid = Uuid.random(),
        override var name: String = "System TTS",
        val speechRate: Float = 1.0f,
        val pitch: Float = 1.0f,
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("minimax")
    data class MiniMax(
        override var id: Uuid = Uuid.random(),
        override var name: String = "MiniMax TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.minimaxi.com/v1",
        val model: String = "speech-2.6-turbo",
        val voiceId: String = "female-shaonv",
        val speed: Float = 1.0f
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("qwen")
    data class Qwen(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Qwen TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://dashscope.aliyuncs.com/api/v1",
        val model: String = "qwen3-tts-flash",
        val voice: String = "Cherry",
        val languageType: String = "Auto"
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("groq")
    data class Groq(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Groq TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.groq.com/openai/v1",
        val model: String = "canopylabs/orpheus-v1-english",
        val voice: String = "austin"
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("xai")
    data class XAI(
        override var id: Uuid = Uuid.random(),
        override var name: String = "xAI TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.x.ai/v1",
        val voiceId: String = "eve",
        val language: String = "auto"
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("mimo")
    // 默认值仅用于快捷起步 可在设置页任意修改
    data class MiMo(
        override var id: Uuid = Uuid.random(),
        override var name: String = "MiMo TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.xiaomimimo.com/v1",
        val model: String = "mimo-v2.5-tts",
        val voice: String = "mimo_default"
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("elevenlabs")
    data class ElevenLabs(
        override var id: Uuid = Uuid.random(),
        override var name: String = "ElevenLabs TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.elevenlabs.io",
        val model: String = "eleven_multilingual_v2",
        val voiceId: String = "JBFqnCBsd6RMkjVDRZzb",
        val stability: Float = 0.5f,
        val similarityBoost: Float = 0.75f,
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    /**
     * 阶跃星辰 Step TTS (step-tts-mini / step-tts-vivid / stepaudio-2.5-tts)。
     *
     * 与 Step ASR 共用同一个 baseUrl 与鉴权方式 (Authorization: Bearer sk-xxx),
     * 走 OpenAI 兼容的 [POST /v1/audio/speech] 非流式接口, 服务端一次性返回完整音频
     * 二进制 (默认 mp3, 也可选 wav/pcm/opus/flac)。客户端把整段音频包成一个 AudioChunk
     * 发出, 由 TtsSynthesizer 统一收集后交给播放器。
     *
     * 仅 stepaudio-2.5-tts 模型支持 [instruction] 字段 (全局语境, ≤200 字符), 其它模型
     * (step-tts-mini / step-tts-vivid / step-tts-2) 会忽略该字段, 留空时不下发。
     *
     * 官方文档:
     * - 模型总览: https://platform.stepfun.com/docs/zh/guides/models/stepaudio-2.5-tts
     * - 开发指南: https://platform.stepfun.com/docs/zh/guides/developer/tts
     */
    @Serializable
    @SerialName("step")
    data class Step(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Step TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.stepfun.com",
        // step-tts-mini | step-tts-vivid | stepaudio-2.5-tts | step-tts-2
        val model: String = "step-tts-mini",
        // 完整 voice-id 列表见开发指南; 默认值与官方 SDK 一致
        val voice: String = "elegantgentle-female",
        // mp3 | wav | pcm | opus | flac; 注意 StepFun API 使用 camelCase 字段名
        val responseFormat: String = "mp3",
        // 0.5 - 2.0, 1.0 为正常语速
        val speed: Float = 1.0f,
        // 0.1 - 2.0, 1.0 为正常音量
        val volume: Float = 1.0f,
        // 8000 | 16000 | 22050 | 24000
        val sampleRate: Int = 24000,
        // 仅 stepaudio-2.5-tts 生效; ≤200 字符, 留空时不下发
        val instruction: String = "",
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("fish-audio")
    data class FishAudio(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Fish Audio TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.fish.audio",
        val model: String = "s2.1-pro",
        val referenceId: String = "",
        val temperature: Float = 0.7f,
        val speed: Float = 1.0f,
        val format: String = "mp3",
        val topP: Float = 0.7f,
        val chunkLength: Int = 300,
        val normalize: Boolean = true,
        val latency: String = "normal",
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    companion object {
        val Types by lazy {
            listOf(
                OpenAI::class,
                Gemini::class,
                SystemTTS::class,
                MiniMax::class,
                Qwen::class,
                Groq::class,
                XAI::class,
                MiMo::class,
                ElevenLabs::class,
                Step::class,
                FishAudio::class,
            )
        }
    }
}
