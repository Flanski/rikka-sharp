package me.rerere.tts.provider

import android.content.Context
import kotlinx.coroutines.flow.Flow
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.TTSRequest

interface TTSProvider<T : TTSProviderSetting> {
    /**
     * 预热：把「首次朗读才付出的一次性开销」提前到用户刚打开/切换 TTS 时。
     *
     * 对本地模型（sherpa-onnx）而言，加载 onnx 需要数秒到数十秒（theresa 121MB 约 20s），
     * 若等到按下朗读才开始，用户会先经历一段无反馈的等待。预热可把这段等待挪走。
     *
     * 默认空实现 —— 在线 provider 没有可预热的东西。
     * 实现方需自行保证：可重复调用、失败不抛（预热失败不应影响后续正常朗读）。
     */
    suspend fun warmUp(context: Context, providerSetting: T) {
        // 默认无操作
    }

    fun generateSpeech(
        context: Context,
        providerSetting: T,
        request: TTSRequest
    ): Flow<AudioChunk>

    /**
     * 可选：指导 AI 如何在朗读文本中加入该 provider 支持的语气/情感标记的提示词。
     *
     * 默认空 = 不注入。支持内联标记的 provider 直接在实现类里覆盖此值（硬编码）。
     * 该内容会在 text_to_speech 工具启用时被追加进 system prompt。
     *
     * 注意（写内容时的两个约束）：
     * 1. 要求 AI 仅把标记放进 text_to_speech 工具的 text 参数，不要出现在给用户看的正文里；
     * 2. 标记需能扛过清洗管线：避免 `*` `_` 等会被 stripMarkdown 删除的符号，
     *    且标记内部不要含 `。，！？…` 等会被 TextChunker 切断的标点。优先用方括号/尖括号。
     */
    val promptGuidance: String
        get() = ""
}
