package me.rerere.ai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

@Serializable
data class Tool(
    val name: String,
    val description: String,
    val parameters: () -> InputSchema? = { null },
    val systemPrompt: (model: Model, messages: List<UIMessage>) -> String = { _, _ -> "" },
    val needsApproval: (JsonElement) -> Boolean = { false },
    val execute: suspend (JsonElement) -> List<UIMessagePart>
) {
    /**
     * 实际发给模型 API 的参数 schema —— 在工具自身 schema 之上，**强制追加**两个字段：
     *
     *  · `intent`  —— 这次调用**要做什么操作**（一句话）
     *  · `purpose` —— **为什么要做**（一句话）
     *
     * ── 为什么集中在这里追加 ──
     * ① 只需改这一个函数 + 4 个 provider 的调用点，**57 个现有工具无需逐个修改**，
     *    将来新增工具也不会漏；
     * ② 两项都写进 `required`，由 **schema 层面强制**模型填写，
     *    而不是在提示词里「请求」它填 —— 后者的遵守率不稳定；
     * ③ 这两项会显示在**授权弹窗的详细内容区**，让用户在批准前
     *    一眼看清「AI 打算做什么、为什么」，而不是只看到一个工具名。
     *
     * 工具自身的 `execute` 照常会收到这两个键。各工具都用 `obj["自己的键"]` 取值，
     * 多余的键无害（已核查全部工具，没有遍历参数键的写法）。
     */
    fun apiParameters(): InputSchema? = when (val schema = parameters()) {
        is InputSchema.Obj -> InputSchema.Obj(
            properties = JsonObject(schema.properties + ACTION_DESCRIPTION_PROPERTIES),
            required = (schema.required.orEmpty() + TOOL_ACTION_DESCRIPTION_KEYS).distinct(),
        )
        // 原本没有参数的工具也要能填写这两项，否则会退回「无 schema」，模型无从填写
        null -> InputSchema.Obj(
            properties = JsonObject(ACTION_DESCRIPTION_PROPERTIES),
            required = TOOL_ACTION_DESCRIPTION_KEYS,
        )
    }
}

@Serializable
sealed class InputSchema {
    @Serializable
    @SerialName("object")
    data class Obj(
        val properties: JsonObject,
        val required: List<String>? = null,
    ) : InputSchema()
}

/**
 * 「行为说明」两个必填参数的键名。
 *
 * 授权弹窗会读取这两个键，把内容展示给用户；因此这里的名字
 * 与 UI 读写的键名必须一致，集中定义避免两边写错。
 */
val TOOL_ACTION_DESCRIPTION_KEYS: List<String> = listOf("intent", "purpose")

private val ACTION_DESCRIPTION_PROPERTIES: Map<String, JsonElement> = mapOf(
    "intent" to buildJsonObject {
        put("type", "string")
        put(
            "description",
            "REQUIRED(必填)。用一句简短的话说明**这次要执行什么操作**，使用用户的语言，" +
                "具体且如实。例如「列出 Download 目录下的文件」。这项会展示给用户确认，" +
                "不要写空泛的话，也不要省略。"
        )
    },
    "purpose" to buildJsonObject {
        put("type", "string")
        put(
            "description",
            "REQUIRED(必填)。用一句简短的话说明**为什么需要这个操作**——它服务于什么目的。" +
                "例如「确认安装包是否已下载完成，以决定是否需要重试」。会与 intent 一同展示给用户。"
        )
    },
)
