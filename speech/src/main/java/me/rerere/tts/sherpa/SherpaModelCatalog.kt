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
/**
 * 模型的打包形式。
 *
 *  - [TARBZ2]：官方发布形式（tar.bz2，含 onnx + tokens + lexicon + fst + dict）
 *  - [RAW_ONNX]：裸 onnx 文件。上游未发布的角色由本项目在 GitHub Actions 里
 *    用官方转换脚本（`tools/vits-zh-hf-models.py`）从 .pth 导出后放到 Release，
 *    tokens/lexicon 等**共用文件**单独打包（见 [SherpaModelInfo.sharedResourcesUrl]）。
 *
 * 为什么需要区分：两者下载与安装流程不同 —— tar.bz2 要解压并探测目录，
 * 裸 onnx 直接落位并确保共用文件就位即可。
 */
enum class ModelPackage { TARBZ2, RAW_ONNX }

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
    /**
     * 该模型的「特色角色」音色序号；-1 表示无（通用模型）。
     *
     * ★为什么必须有这个字段：
     * 每个角色模型都是独立的 804 说话人模型，**说话人在模型之间完全不对齐**
     * （实测：同 sid 跨模型的频谱相似度 0.9353 ≈ 异 sid 跨模型 0.9335，
     *   而同模型内为 0.9773；跨模型同 sid 的基频差可达 27%）。
     * 因此角色音色必须「model + featuredSid」成对使用：
     *   theresa 模型 + sid=193 → 德丽莎 ✓
     *   theresa 模型 + sid=0   → 其它人（早期默认值取 0，导致音色对不上）
     *
     * 数值来自上游权威数据源 `csukuangfj/vits-models` 的
     * `pretrained_models/info.json`（该文件给出每个角色在自己模型中的 sid）。
     */
    val featuredSid: Int = -1,
    /** 角色出处，如「原神」「崩坏3」；通用模型为空 */
    val source: String = "",
    /** 模型语言（上游 info.json 的 language 字段） */
    val language: String = "Chinese",
    /** 打包形式 */
    val pkg: ModelPackage = ModelPackage.TARBZ2,
) {
    /**
     * 下载地址。
     * 官方模型来自 sherpa-onnx 的 tts-models；自转模型（RAW_ONNX）来自本项目 Release。
     */
    val url: String
        get() = when (pkg) {
            ModelPackage.TARBZ2 -> "$BASE_URL/$fileName"
            ModelPackage.RAW_ONNX -> "$EXTRA_BASE_URL/$id.onnx"
        }
    val sizeMb: Float get() = sizeBytes / 1048576f
    val extractedMb: Float get() = extractedBytes / 1048576f

    /** 由文件名推断分类 */
    val category: ModelCategory get() = categoryOf(fileName)

    companion object {
        const val BASE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

        /**
         * 本项目自转模型的 Release。
         *
         * 上游 38 个角色里只发布了 8 个角色（其余 30 个需自行从 .pth 转换）。
         * 本项目用 GitHub Actions（`.github/workflows/convert-vits-batch.yml`）
         * 跑官方转换脚本生成，实测产出与官方同类文件仅差约 99 字节，
         * 且能被 sherpa-onnx 加载并用中文合成。
         */
        const val EXTRA_BASE_URL =
            "https://github.com/Flanski/rikka-sharp/releases/download/tts-models-v1"

        /**
         * 共用资源包（tokens.txt + lexicon.txt + 4 个 .fst）。
         *
         * 所有 `vits-zh-hf-*` 模型的这些文件**逐字节相同**（已用 md5 逐一验证），
         * 因为它们由同一份 `config/config.json` 与同一套 pypinyin 词典生成。
         * 故只在首次安装 RAW_ONNX 模型时下载一次（压缩后约 0.5MB，解压约 2.9MB）。
         */
        const val SHARED_ARCHIVE_URL = "$EXTRA_BASE_URL/vits-zh-hf-shared.tar.bz2"

        /** 共用资源的存放目录名（位于模型根目录下） */
        const val SHARED_DIR_NAME = "_shared"

        /**
         * 已知模型的友好名称。只覆盖常用项；未命中的回退为文件名本身
         * （在线刷新会带来 600+ 个模型，逐个起中文名不现实也没必要）。
         */
        private val DISPLAY_NAMES = mapOf(
            "vits-zh-hf-keqing" to "刻晴（原神）",
            "vits-zh-hf-eula" to "优菈（原神）",
            "vits-zh-hf-theresa" to "德丽莎（崩坏3）",
            "vits-zh-hf-bronya" to "理之律者（崩坏3）",
            "vits-zh-hf-zenyatta" to "禅雅塔（守望先锋2）",
            "vits-zh-hf-abyssinvoker" to "深渊使徒（原神）",
            "vits-zh-hf-echo" to "回声（守望先锋2）",
            "vits-zh-hf-doom" to "末日铁拳（守望先锋2）",
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

    /**
     * 内置清单。
     *
     * 分两类：
     *  A. **角色音色**（8 个，全部为上游已发布且标注 Chinese 的模型）
     *     —— 每个都是独立的 804 说话人模型，[SherpaModelInfo.featuredSid] 是该角色在其中的序号。
     *  B. **通用模型** —— 无特色角色（featuredSid = -1）。
     *
     * ── 关于「为什么只有 8 个角色」──
     * 上游源数据集（HF Space `csukuangfj/vits-models`）共 **38 个角色**，其中
     * **只有 8 个标注为 Chinese，且这 8 个恰好全部已被 sherpa-onnx 打包发布**。
     * 其余 30 个标注 Japanese（含原神的神里绫华/纳西妲、星穹铁道的卡芙卡/黑塔），
     * **上游未提供现成 onnx**，需要自行用其转换脚本从 .pth 导出。
     * 也就是说：**中文角色一个没漏，缺的是日语角色**。
     */
    val ALL: List<SherpaModelInfo> = listOf(
        /* ───────── A. 角色音色（sid 来自上游 info.json）───────── */
        SherpaModelInfo(
            id = "theresa",
            displayName = "德丽莎（崩坏3）",
            fileName = "vits-zh-hf-theresa.tar.bz2",
            sizeBytes = 120596617L,
            extractedBytes = extracted(120596617L),
            speakers = 804,
            description = "崩坏3 德丽莎。选中后会自动把音色设为 193",
            featuredSid = 193,
            source = "崩坏3",
        ),
        SherpaModelInfo(
            id = "bronya",
            displayName = "理之律者（崩坏3）",
            fileName = "vits-zh-hf-bronya.tar.bz2",
            sizeBytes = 120595102L,
            extractedBytes = extracted(120595102L),
            speakers = 804,
            description = "崩坏3 理之律者（布洛妮娅）。选中后会自动把音色设为 193",
            featuredSid = 193,
            source = "崩坏3",
        ),
        SherpaModelInfo(
            id = "keqing",
            displayName = "刻晴（原神）",
            fileName = "vits-zh-hf-keqing.tar.bz2",
            sizeBytes = 120592220L,
            extractedBytes = extracted(120592220L),
            speakers = 804,
            description = "原神 刻晴。选中后会自动把音色设为 115",
            featuredSid = 115,
            source = "原神",
        ),
        SherpaModelInfo(
            id = "eula",
            displayName = "优菈（原神）",
            fileName = "vits-zh-hf-eula.tar.bz2",
            sizeBytes = 120562119L,
            extractedBytes = extracted(120562119L),
            speakers = 804,
            description = "原神 优菈。选中后会自动把音色设为 124",
            featuredSid = 124,
            source = "原神",
        ),
        SherpaModelInfo(
            id = "abyssinvoker",
            displayName = "深渊使徒（原神）",
            fileName = "vits-zh-hf-abyssinvoker.tar.bz2",
            sizeBytes = 120579632L,
            extractedBytes = extracted(120579632L),
            speakers = 804,
            description = "原神 深渊使徒。选中后会自动把音色设为 94",
            featuredSid = 94,
            source = "原神",
        ),
        SherpaModelInfo(
            id = "zenyatta",
            displayName = "禅雅塔（守望先锋2）",
            fileName = "vits-zh-hf-zenyatta.tar.bz2",
            sizeBytes = 120588994L,
            extractedBytes = extracted(120588994L),
            speakers = 804,
            description = "守望先锋2 禅雅塔。选中后会自动把音色设为 93",
            featuredSid = 93,
            source = "守望先锋2",
        ),
        SherpaModelInfo(
            id = "echo",
            displayName = "回声（守望先锋2）",
            fileName = "vits-zh-hf-echo.tar.bz2",
            sizeBytes = 120559956L,
            extractedBytes = extracted(120559956L),
            speakers = 804,
            description = "守望先锋2 回声。选中后会自动把音色设为 93",
            featuredSid = 93,
            source = "守望先锋2",
        ),
        SherpaModelInfo(
            id = "doom",
            displayName = "末日铁拳（守望先锋2）",
            fileName = "vits-zh-hf-doom.tar.bz2",
            sizeBytes = 120556412L,
            extractedBytes = extracted(120556412L),
            speakers = 804,
            description = "守望先锋2 末日铁拳。选中后会自动把音色设为 93",
            featuredSid = 93,
            source = "守望先锋2",
        ),

        /* ───────── B. 通用模型 ───────── */
        SherpaModelInfo(
            id = "vits-icefall-zh-aishell3",
            displayName = "aishell3（通用中文，体积小）",
            fileName = "vits-icefall-zh-aishell3.tar.bz2",
            sizeBytes = 31559701L,
            extractedBytes = extracted(31559701L),
            speakers = 174,
            description = "通用中文女声数据集，174 个说话人。体积只有角色模型的三分之一，建议先用它验证效果",
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
    ) + EXTRA

    /**
     * 裸 onnx 模型的声明辅助 —— 上游未发布的 30 个角色都由它构造。
     *
     * 这些角色的**原始数据均为日语**（上游 info.json 的 language 字段），
     * 但生成时用的 cleaner 是 `zh_ja_mixture_cleaners`（中英日混合），
     * 所以**读中文也没问题**（已在设备上用 C API 实测：卡芙卡 multi 与
     * 纳西妲 single 两种类型都能正常合成中文）。
     */
    private fun raw(
        id: String,
        nameZh: String,
        source: String,
        sid: Int,
        sizeBytes: Long,
    ) = SherpaModelInfo(
        id = id,
        displayName = "$nameZh（$source）",
        fileName = "$id.onnx",
        sizeBytes = sizeBytes,
        // 裸 onnx 无需解压，但安装时还要取共用资源（约 2.9MB），故留 4MB 余量
        extractedBytes = sizeBytes + 4L * 1024 * 1024,
        speakers = 804,
        description = "$source $nameZh。日语语音模型（实测也能读中文）。选中后会自动把音色设为 $sid",
        featuredSid = sid,
        source = source,
        language = "Japanese",
        pkg = ModelPackage.RAW_ONNX,
    )

    /**
     * 上游**未发布**的 30 个角色（含 4 个米哈游）。
     *
     * 由本项目在 GitHub Actions 上用官方转换脚本从 .pth 导出后发布到 Release，
     * 见 `.github/workflows/convert-vits-batch.yml`。
     * 实测：产出 onnx 与官方同类文件大小仅差约 99 字节（121,914,005 vs 121,913,906）。
     */
    val EXTRA: List<SherpaModelInfo> = listOf(
        raw("ameth", "爱梅斯", "公主连结", 0, 114041674L),
        raw("eriko", "惠理子", "公主连结", 0, 114041674L),
        raw("hatsune", "柏崎初音", "公主连结", 10, 121914005L),
        raw("hiyori", "日和莉", "公主连结", 0, 114041674L),
        raw("kokoro", "可可萝", "公主连结", 0, 114041674L),
        raw("kyaru", "凯露", "公主连结", 10, 121914005L),
        raw("kyoka", "镜华", "公主连结", 0, 121914005L),
        raw("misora", "美空", "公主连结", 0, 114041674L),
        raw("pecorine", "佩可莉姆", "公主连结", 10, 121914005L),
        raw("yuni", "优妮", "公主连结", 0, 114041674L),
        raw("ayaka-jp", "神里绫华-日语", "原神", 303, 121914005L),
        raw("nahida-jp", "纳西妲-日语", "原神", 0, 114041674L),
        raw("herta", "黑塔", "星穹铁道", 10, 121914005L),
        raw("kafka", "卡芙卡", "星穹铁道", 10, 121914005L),
        raw("chisato", "锦木千束", "莉可丽丝", 0, 114041674L),
        raw("takina", "井上泷奈", "莉可丽丝", 0, 114041674L),
        raw("alice", "天童爱丽丝", "蔚蓝档案", 10, 121914005L),
        raw("asuna", "一之濑明日奈", "蔚蓝档案", 10, 121914005L),
        raw("azusa", "白洲梓", "蔚蓝档案", 10, 121914005L),
        raw("hina", "空崎日奈", "蔚蓝档案", 10, 121914005L),
        raw("hoshino", "小鸟游星野", "蔚蓝档案", 10, 121914005L),
        raw("iori", "银镜伊织", "蔚蓝档案", 10, 121914005L),
        raw("iroha", "枣伊吕波", "蔚蓝档案", 10, 121914005L),
        raw("izuna", "久田泉奈", "蔚蓝档案", 10, 121914005L),
        raw("karin", "角楯花凛", "蔚蓝档案", 10, 121914005L),
        raw("mika", "圣园未花", "蔚蓝档案", 10, 121914005L),
        raw("miyu", "霞泽美游", "蔚蓝档案", 10, 121914005L),
        raw("momoi", "才羽桃井", "蔚蓝档案", 10, 121914005L),
        raw("shiroko", "砂狼白子", "蔚蓝档案", 10, 121914005L),
        raw("yuuka", "早濑优香", "蔚蓝档案", 40, 121914005L),
    )

    fun find(id: String): SherpaModelInfo? = ALL.firstOrNull { it.id == id }
}
