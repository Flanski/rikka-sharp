package me.rerere.tts.sherpa

/**
 * sherpa-onnx 中文 TTS 模型清单。
 *
 * 全部来自官方 release `tts-models`：
 *   https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
 *
 * 体积与大小均为**实测值**（HTTP HEAD 的 content-length / release 资产 size），非估算。
 *
 * ── 关于"音色数" ──
 * 多个模型共享同一批说话人（804 个），模型名对应的其实是"以该角色为特色的版本"，
 * 因此同一个模型里既有角色音色也有其它音色。想找角色本人的音色，建议从 sid=0 开始逐个试。
 *
 * ── 关于体积 ──
 * 除 aishell3 外均为 115MB 左右。模型不随 APK 分发，由用户在设置页按需下载。
 */
/** 用于界面筛选的粗分类（由文件名推断，不追求精确语言识别） */
enum class ModelCategory(val label: String) {
    CHINESE("中文"),
    ENGLISH("英文"),
    MULTILINGUAL("多语言"),
    OTHER("其它"),
}

data class SherpaModelInfo(
    /** 稳定标识，同时用作本地目录名（= 文件名去掉 .tar.bz2） */
    val id: String,
    /** 界面上显示的名称 */
    val displayName: String,
    /** release 资产文件名 */
    val fileName: String,
    /** 压缩包字节数 */
    val sizeBytes: Long,
    /** 解压后大小（估算，用于提示留出空间） */
    val extractedBytes: Long,
    /** 该模型的说话人数（0 = 未知，来自官方文档或 speakers.txt） */
    val speakers: Int,
    /** 简介 */
    val description: String,
) {
    val url: String get() = "$BASE_URL/$fileName"
    val sizeMb: Float get() = sizeBytes / 1048576f
    val extractedMb: Float get() = extractedBytes / 1048576f

    /** 由文件名推断分类 */
    val category: ModelCategory get() = categoryOf(fileName)

    companion object {
        const val BASE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

        /**
         * 已知模型的友好名称。只覆盖常用项；未命中的回退为文件名本身
         * （在线刷新会带来 600+ 个模型，逐个起中文名不现实也没必要）。
         */
        private val DISPLAY_NAMES = mapOf(
            "vits-zh-hf-keqing" to "刻晴（原神）",
            "vits-zh-hf-eula" to "优菈（原神）",
            "vits-zh-hf-theresa" to "德丽莎（崩坏3）",
            "vits-zh-hf-bronya" to "布洛妮娅（崩坏3）",
            "vits-zh-hf-zenyatta" to "禅雅塔（守望先锋）",
            "vits-zh-hf-abyssinvoker" to "深渊召唤者（Dota2）",
            "vits-zh-hf-echo" to "回声（守望先锋）",
            "vits-zh-hf-doom" to "毁灭战士",
            "vits-icefall-zh-aishell3" to "aishell3（通用中文，体积小）",
            "vits-cantonese-hf-xiaomaiiwn" to "粤语（xiaomaiiwn）",
            "vits-piper-zh_CN-huayan-medium" to "华研（piper 中文）",
            "vits-piper-zh_CN-chaowen-medium" to "超文（piper 中文）",
            "vits-piper-zh_CN-xiao_ya-medium" to "小雅（piper 中文）",
            "vits-melo-tts-zh_en" to "MeloTTS（中英混读）",
            "sherpa-onnx-vits-zh-ll" to "中文通用（ll）",
            "vits-zh-aishell3" to "aishell3（完整版，140MB）",
        )

        /** 由 release 资产文件名构造条目（在线刷新用） */
        fun fromFileName(fileName: String, sizeBytes: Long): SherpaModelInfo {
            val id = fileName.removeSuffix(".tar.bz2")
            val known = DISPLAY_NAMES[id]
            return SherpaModelInfo(
                id = id,
                displayName = known ?: id,
                fileName = fileName,
                sizeBytes = sizeBytes,
                extractedBytes = (sizeBytes * 3).coerceAtLeast(sizeBytes),
                speakers = 0,
                description = "",
            )
        }

        fun categoryOf(fileName: String): ModelCategory {
            val n = fileName.lowercase()
            return when {
                n.contains("melo") || n.contains("multi-lang") -> ModelCategory.MULTILINGUAL
                Regex("zh|chinese|cantonese").containsMatchIn(n) -> ModelCategory.CHINESE
                Regex("(^|-)en([_-]|$)|english|ljspeech|vctk").containsMatchIn(n) -> ModelCategory.ENGLISH
                else -> ModelCategory.OTHER
            }
        }
    }
}

object SherpaModelCatalog {

    /**
     * 解压后大小估算。
     *
     * **实测**（vits-icefall-zh-aishell3）：压缩包 31.5MB → 解压后 **204MB**（约 6.5 倍）。
     * 构成：model.onnx 30MB + **rule.far 173MB** + lexicon 2MB + 若干 .fst。
     * 大头是 rule.far（中文读法规则归档），它不随模型大小等比缩放，
     * 因此这里用「压缩包 × 6」作**保守上限**，宁可高估也不要因低估导致下载中途空间不足。
     */
    private fun extracted(compressed: Long) = (compressed * 6).toLong()

    /** 游戏/角色音色（用户主要诉求）+ 通用模型 */
    val ALL: List<SherpaModelInfo> = listOf(
        SherpaModelInfo(
            id = "keqing",
            displayName = "刻晴（原神）",
            fileName = "vits-zh-hf-keqing.tar.bz2",
            sizeBytes = 120592220L,
            extractedBytes = extracted(120592220L),
            speakers = 804,
            description = "原神 刻晴 音色，804 个说话人可选",
        ),
        SherpaModelInfo(
            id = "eula",
            displayName = "优菈（原神）",
            fileName = "vits-zh-hf-eula.tar.bz2",
            sizeBytes = 120562119L,
            extractedBytes = extracted(120562119L),
            speakers = 804,
            description = "原神 优菈 音色，804 个说话人可选",
        ),
        SherpaModelInfo(
            id = "theresa",
            displayName = "德丽莎（崩坏3）",
            fileName = "vits-zh-hf-theresa.tar.bz2",
            sizeBytes = 120596617L,
            extractedBytes = extracted(120596617L),
            speakers = 804,
            description = "崩坏3 德丽莎 音色，804 个说话人可选",
        ),
        SherpaModelInfo(
            id = "bronya",
            displayName = "布洛妮娅（崩坏3）",
            fileName = "vits-zh-hf-bronya.tar.bz2",
            sizeBytes = 120595102L,
            extractedBytes = extracted(120595102L),
            speakers = 804,
            description = "崩坏3 布洛妮娅 音色，804 个说话人可选",
        ),
        SherpaModelInfo(
            id = "zenyatta",
            displayName = "禅雅塔（守望先锋）",
            fileName = "vits-zh-hf-zenyatta.tar.bz2",
            sizeBytes = 120588994L,
            extractedBytes = extracted(120588994L),
            speakers = 804,
            description = "守望先锋 禅雅塔 音色，804 个说话人可选",
        ),
        SherpaModelInfo(
            id = "abyssinvoker",
            displayName = "深渊召唤者（Dota2）",
            fileName = "vits-zh-hf-abyssinvoker.tar.bz2",
            sizeBytes = 120579632L,
            extractedBytes = extracted(120579632L),
            speakers = 804,
            description = "Dota2 深渊召唤者 音色，804 个说话人可选",
        ),
        SherpaModelInfo(
            id = "echo",
            displayName = "回声（守望先锋）",
            fileName = "vits-zh-hf-echo.tar.bz2",
            sizeBytes = 120559956L,
            extractedBytes = extracted(120559956L),
            speakers = 804,
            description = "守望先锋 回声 音色，804 个说话人可选",
        ),
        SherpaModelInfo(
            id = "doom",
            displayName = "毁灭战士",
            fileName = "vits-zh-hf-doom.tar.bz2",
            sizeBytes = 120556412L,
            extractedBytes = extracted(120556412L),
            speakers = 804,
            description = "804 个说话人可选",
        ),
        SherpaModelInfo(
            id = "vits-cantonese-hf-xiaomaiiwn",
            displayName = "粤语（xiaomaiiwn）",
            fileName = "vits-cantonese-hf-xiaomaiiwn.tar.bz2",
            sizeBytes = 108003328L,
            extractedBytes = extracted(108003328L),
            speakers = 0,
            description = "粤语模型",
        ),
        SherpaModelInfo(
            id = "vits-melo-tts-zh_en",
            displayName = "MeloTTS（中英混读）",
            fileName = "vits-melo-tts-zh_en.tar.bz2",
            sizeBytes = 167006755L,
            extractedBytes = extracted(167006755L),
            speakers = 1,
            description = "中英文混读（英文仅能读 lexicon.txt 里收录的词）",
        ),
        SherpaModelInfo(
            id = "vits-icefall-zh-aishell3",
            displayName = "aishell3（通用中文，体积小）",
            fileName = "vits-icefall-zh-aishell3.tar.bz2",
            sizeBytes = 31559701L,
            extractedBytes = extracted(31559701L),
            speakers = 174,
            description = "通用中文女声数据集，174 个说话人。体积只有其它模型的三分之一，建议先用它验证效果",
        ),
    )

    fun find(id: String): SherpaModelInfo? = ALL.firstOrNull { it.id == id }
}
