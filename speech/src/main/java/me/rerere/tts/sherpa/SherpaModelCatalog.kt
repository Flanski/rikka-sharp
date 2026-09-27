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
data class SherpaModelInfo(
    /** 稳定标识，同时用作本地目录名 */
    val id: String,
    /** 界面上显示的名称 */
    val displayName: String,
    /** release 资产文件名 */
    val fileName: String,
    /** 压缩包字节数（实测） */
    val sizeBytes: Long,
    /** 解压后大小（约，用于提示用户留出空间） */
    val extractedBytes: Long,
    /** 该模型的说话人数（来自官方文档） */
    val speakers: Int,
    /** 简介 */
    val description: String,
) {
    val url: String get() = "$BASE_URL/$fileName"
    val sizeMb: Float get() = sizeBytes / 1048576f
    val extractedMb: Float get() = extractedBytes / 1048576f

    companion object {
        const val BASE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
    }
}

object SherpaModelCatalog {

    /** 官方 VITS 模型解压后约为压缩包的 1.4 倍（含 .onnx + 词典 + 音色表） */
    private fun extracted(compressed: Long) = (compressed * 1.4).toLong()

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
            id = "aishell3",
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
