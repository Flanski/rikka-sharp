package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

/** `ask_user` 支持的回答控件。UI 侧与这里必须一一对应。 */
object AskUserControls {
    /** 单选（RadioButton）。**UI 上会明确标注「单选」** —— 这是用户明确反馈过的问题 */
    const val RADIO = "radio"

    /** 多选（Checkbox）。UI 上会明确标注「多选」 */
    const val CHECKBOX = "checkbox"

    /** 下拉选择（单选，选项多时用它，比一排 radio 省空间） */
    const val SELECT = "select"

    /** 单行文本 */
    const val TEXT = "text"

    /** 密码框（输入内容遮蔽） */
    const val PASSWORD = "password"

    /** 多行文本 */
    const val TEXTAREA = "textarea"

    /** 按钮组：一排按钮，点一下就提交（适合「是 / 否 / 取消」这类极短选择） */
    const val BUTTONS = "buttons"

    /** 布尔确认（是 / 否，渲染成两个按钮，但回答是 true/false） */
    const val CONFIRM = "confirm"

    val ALL = setOf(RADIO, CHECKBOX, SELECT, TEXT, PASSWORD, TEXTAREA, BUTTONS, CONFIRM)

    /** 旧字段 `selection_type` 到新 `control` 的映射（保持向后兼容） */
    fun fromLegacy(selectionType: String): String? = when (selectionType.lowercase()) {
        "single" -> RADIO
        "multi" -> CHECKBOX
        "text" -> TEXT
        else -> null
    }
}

/**
 * `ask_user` 工具的定义。
 *
 * ── ★它为什么只定义 schema、不实现 execute ──
 * 这个工具的正确执行方式是**暂停生成 → 弹出表单 → 用户填答 → 把答案作为工具结果返回给模型**。
 * 这条链路叫「人在回路」（HITL），由三层协作完成，**不在 execute 里**：
 *
 *  1. `GenerationHandler` 见到 `needsApproval == true` 且状态为 `Auto` → 标记为 `Pending` 并**跳出循环**
 *     （生成暂停，不自旋、不占资源）；
 *  2. UI（`ChatPage`）看到有工具处于 `Pending` → 弹出提问表单；
 *  3. 用户提交 → `ChatService.handleToolApproval(answer = ...)` → 状态变为 `Answered`
 *     → 恢复生成 → `GenerationHandler` 把 `answer` 作为**工具输出**交给模型。
 *
 * 因此这里的 `execute` 若被调用，说明**上面第 1 步没生效**（工具没被标记 Pending）。
 * 抛异常是刻意的：那种情况下**必须响**，而不是返回一个假的答案让模型以为用户说过话。
 * 错误信息写明了原因，便于定位。
 *
 * ── 本轮扩展（用户要求「魔改增强」）──
 * 除原有的 text/single/multi 之外，新增 select / password / textarea / buttons / confirm，
 * 并新增全局 `size` 控制弹窗高度（表单内容多时需要更大空间）。
 * 旧字段 `selection_type` 仍被接受（映射关系见 [AskUserControls.fromLegacy]）。
 */
internal fun buildAskUserTool(): Tool = Tool(
    name = "ask_user",
    description = """
        Ask the user one or more questions, then continue once they answer.
        The generation pauses while the form is shown, and their answers come back to you as the
        tool result — a JSON object mapping each question's `id` to its answer.

        PICK THE RIGHT CONTROL (`control` field) — this matters, users get confused otherwise:
          · "radio"    — choose exactly ONE from a short list (2-5 items). Shown as radio buttons
                         and LABELLED "single choice" in the UI.
          · "checkbox" — choose ANY NUMBER (including none). Shown as checkboxes and LABELLED
                         "multiple choice". Use this whenever more than one answer can apply.
          · "select"   — choose exactly ONE, but from a LONGER list (6+ items); rendered as a
                         dropdown to save space.
          · "buttons"  — a row of buttons; tapping one submits immediately. Best for 2-4 very short
                         options like yes/no/cancel where a tap is faster than select-then-confirm.
          · "text"     — single-line free text. Add `placeholder` as a hint.
          · "password" — like text, but the input is masked.
          · "textarea" — multi-line free text (long answers, notes, code).
          · "confirm"  — a yes/no decision; the answer comes back as true/false.

        DO NOT use "radio" when multiple answers are valid — that is the single most common way
        this tool confuses people. If several options could apply at once, use "checkbox".

        Set `size` to "2/4" or "3/4" when the form is long (many questions, or textareas);
        the default 1/3 of the screen is fine for one or two short questions.

        For multiple questions, provide one entry in `questions` per question.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("questions", buildJsonObject {
                    put("type", "array")
                    put("description", "The questions to ask. One entry per question.")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("id", buildJsonObject {
                                put("type", "string")
                                put("description", "Unique id; the answer is keyed by it in the result.")
                            })
                            put("question", buildJsonObject {
                                put("type", "string")
                                put("description", "The question text shown to the user. Be specific and self-contained — the user has not seen your reasoning.")
                            })
                            put("control", buildJsonObject {
                                put("type", "string")
                                put(
                                    "enum",
                                    buildJsonArray {
                                        AskUserControls.ALL.forEach { add(it) }
                                    }
                                )
                                put(
                                    "description",
                                    "radio (single choice, short list) | checkbox (multiple choice) | " +
                                        "select (single choice, long list) | buttons (2-4 short options, tap to submit) | " +
                                        "text (single line) | password (masked) | textarea (multi-line) | confirm (yes/no)"
                                )
                            })
                            put("options", buildJsonObject {
                                put("type", "array")
                                put("description", "Choices for radio / checkbox / select / buttons. Plain strings, or objects {\"value\":\"...\",\"label\":\"...\",\"description\":\"...\"}.")
                                put("items", buildJsonObject { put("type", "string") })
                            })
                            put("placeholder", buildJsonObject {
                                put("type", "string")
                                put("description", "Hint text for text / password / textarea.")
                            })
                            put("default", buildJsonObject {
                                put("type", "string")
                                put("description", "Optional pre-filled value.")
                            })
                            put("required", buildJsonObject {
                                put("type", "boolean")
                                put("description", "If true the user must answer this one before submitting. Default false.")
                            })
                            // 兼容旧字段（模型可能仍在用）
                            put("selection_type", buildJsonObject {
                                put("type", "string")
                                put("description", "DEPRECATED — use `control` instead. single→radio, multi→checkbox, text→text.")
                            })
                        })
                        put("required", buildJsonArray {
                            add("id")
                            add("question")
                        })
                    })
                })
                put("size", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("1/3")
                        add("2/4")
                        add("3/4")
                    })
                    put("description", "How much of the screen height the dialog takes. Use 2/4 or 3/4 for long forms.")
                })
            },
            required = listOf("questions")
        )
    },
    needsApproval = { true },
    execute = {
        // 走到这里说明 GenerationHandler 没把它标记为 Pending（见类注释）。
        // 刻意抛异常而不是返回占位答案 —— 后者会让模型以为用户真的回答过。
        error(
            "ask_user was executed directly instead of going through the human-in-the-loop flow. " +
                "This means it was not marked as Pending by GenerationHandler — " +
                "it should be shown as a dialog and answered by the user."
        )
    }
)
