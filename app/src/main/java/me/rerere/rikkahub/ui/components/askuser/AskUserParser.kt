package me.rerere.rikkahub.ui.components.askuser

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.tools.local.AskUserControls

/**
 * 把 `ask_user` 的工具参数解析成强类型。
 *
 * ── 为什么要单独一个解析层 ──
 * 模型给的参数是「尽力而为」的：字段可能缺、类型可能不对（该给数组却给了字符串）、
 * 用了已废弃的字段名。若把这些判断散落在 UI 里，会出现
 * 「某个分支忘了判空 → 弹窗空白 → 用户只能取消」这类问题。
 * 集中解析 + **永不抛异常**（出错就退回合理默认值），UI 那边就简单了。
 *
 * ── 容错策略（每条都对应模型可能的偏差）──
 *  · `control` 缺失 → 看有没有 options：有就猜 radio/single，没有就当 text
 *  · `control` 不认识 → 同上（不认识的当文本，总比什么都不显示好）
 *  · 只有旧字段 `selection_type` → 用 [AskUserControls.fromLegacy] 映射
 *  · `options` 里的元素可能是字符串，也可能是 `{value,label,description}` 对象 → 两种都收
 *  · `required` 可能是布尔、也可能是字符串 "true" → 都收
 *  · `radio`/`select` 却没有 options → 退化为文本输入（否则用户无从作答）
 *  · 多选类却没有 options → 同上
 */
object AskUserParser {

    /** 解析结果 */
    data class Parsed(
        val questions: List<AskUserQuestion>,
        val sizeFraction: Float,
    )

    /**
     * @param input 工具的输入 JSON
     */
    fun parse(input: JsonElement?): Parsed {
        val root = input as? JsonObject ?: return Parsed(emptyList(), DEFAULT_FRACTION)

        val rawQuestions = (root["questions"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val questions = rawQuestions.mapIndexedNotNull { index, obj -> parseQuestion(obj, index) }

        return Parsed(
            questions = questions,
            sizeFraction = parseSize((root["size"] as? JsonPrimitive)?.contentOrNull),
        )
    }

    private fun parseQuestion(obj: JsonObject, index: Int): AskUserQuestion? {
        val questionText = (obj["question"] as? JsonPrimitive)?.contentOrNull?.trim()
        // 没有问题正文的条目直接丢掉 —— 显示一个空标题的输入框只会让人困惑
        if (questionText.isNullOrEmpty()) return null

        val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: "q${index + 1}"   // 模型漏给 id 时兜底，否则答案无法对应回问题

        val options = parseOptions(obj["options"])

        // 控件类型的确定顺序：control → 旧字段 selection_type → 按有无 options 猜
        val declared = (obj["control"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
        val legacy = (obj["selection_type"] as? JsonPrimitive)?.contentOrNull
            ?.let { AskUserControls.fromLegacy(it) }

        val control = when {
            declared != null && declared in AskUserControls.ALL -> declared
            legacy != null -> legacy
            options.isNotEmpty() -> AskUserControls.RADIO   // 有选项但没说类型 → 单选（最常见）
            else -> AskUserControls.TEXT
        }.let { c ->
            // 需要选项的控件却没有选项 → 退化为文本输入。
            // 不退化的后果是弹窗里一个可点项都没有，用户只能取消。
            if (c in NEEDS_OPTIONS && options.isEmpty()) AskUserControls.TEXT else c
        }

        return AskUserQuestion(
            id = id,
            question = questionText,
            control = control,
            options = options,
            placeholder = (obj["placeholder"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
            defaultValue = (obj["default"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
            required = parseBoolean(obj["required"]) ?: false,
        )
    }

    private val NEEDS_OPTIONS = setOf(
        AskUserControls.RADIO,
        AskUserControls.CHECKBOX,
        AskUserControls.SELECT,
        AskUserControls.BUTTONS,
    )

    private fun parseOptions(element: JsonElement?): List<AskUserOption> {
        val arr = element as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            when (item) {
                // 形式一：纯字符串 "选项A"
                is JsonPrimitive -> item.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                    AskUserOption(value = it, label = it, description = null)
                }

                // 形式二：对象 {"value":"v","label":"L","description":"D"}
                is JsonObject -> {
                    val label = (item["label"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: (item["value"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    // value 缺省时用 label —— 答案里存的是什么不重要，重要的是与选项一一对应
                    val value = (item["value"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: label
                    AskUserOption(
                        value = value,
                        label = label,
                        description = (item["description"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
                    )
                }

                else -> null
            }
        }
    }

    /** `required` 可能是布尔，也可能是字符串 "true"（模型两种写法都出现过） */
    private fun parseBoolean(element: JsonElement?): Boolean? {
        val p = element as? JsonPrimitive ?: return null
        return p.booleanOrNull ?: p.contentOrNull?.toBooleanStrictOrNull()
    }

    /** 默认高度用模板的 1/3 */
    const val DEFAULT_FRACTION = 1f / 3f

    private fun parseSize(raw: String?): Float = when (raw?.trim()) {
        "2/4", "1/2" -> 0.5f
        "3/4" -> 0.75f
        else -> DEFAULT_FRACTION
    }
}
