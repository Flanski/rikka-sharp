package me.rerere.rikkahub.ui.components.askuser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.R
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.rikkahub.data.ai.tools.local.AskUserControls
import me.rerere.rikkahub.data.notification.AppNotification
import me.rerere.rikkahub.ui.components.notification.NotificationTemplateColors
import me.rerere.rikkahub.ui.components.notification.NotificationTemplateDialog
import me.rerere.rikkahub.ui.components.notification.TemplateDialogButton

/**
 * 一个待回答的问题（从工具参数解析出来的强类型模型）。
 *
 * 单独建模而不直接用 `JsonObject`：否则 UI 里到处 `jsonPrimitive?.contentOrNull`，
 * 又长又容易漏处理，解析一次之后渲染逻辑干净得多。
 */
data class AskUserQuestion(
    val id: String,
    val question: String,
    val control: String,
    val options: List<AskUserOption>,
    val placeholder: String?,
    val defaultValue: String?,
    val required: Boolean,
)

data class AskUserOption(
    val value: String,
    val label: String,
    val description: String?,
)

/**
 * `ask_user` 的提问弹窗。
 *
 * ── 用通知模板的样式 ──
 * 与授权弹窗共用 [NotificationTemplateDialog]，只是内容换成表单、高度可调。
 *
 * ── ★「单选 / 多选」必须一眼看懂 ──
 * 用户明确反馈过：现有的单选按钮**非常迷惑**，看不出到底是单选还是多选。因此这里做两件事：
 *  ① 每组标题旁**显式标注**「单选」/「多选」（见 [ControlBadge]）；
 *  ② 二者用**不同形状**的控件：单选是圆形 RadioButton，多选是方形 Checkbox ——
 *     形状差异比文字更快被识别。
 *
 * ── 提交后的返回值 ──
 * 序列化为 JSON：`{"q1":"选项A","q2":["a","b"],"q3":"自由文本","q4":true}`。
 * 单选/文本/下拉给字符串，多选给数组，confirm 给布尔 —— 让模型能直接结构化读取，
 * 不必再去解析自然语言。
 */
@Composable
fun AskUserDialog(
    questions: List<AskUserQuestion>,
    sizeFraction: Float,
    onCancel: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val colors = NotificationTemplateColors.of(AppNotification.Type.NORMAL)

    // 状态初始化放在 remember(questions) 的 calculation 里 —— 只在首次进入（或问题集变化）时执行一次。
    // 不放在 composition 体内：那样每次重组都会重跑，会覆盖用户已经填好的内容。
    val singleAnswers = remember(questions) {
        mutableStateMapOf<String, String>().apply {
            questions.forEach { q ->
                if (q.control != AskUserControls.CHECKBOX) {
                    q.defaultValue?.takeIf { it.isNotBlank() }?.let { put(q.id, it) }
                }
            }
        }
    }
    val multiAnswers = remember(questions) {
        mutableStateMapOf<String, MutableSet<String>>().apply {
            questions.forEach { q ->
                val initial = q.defaultValue?.takeIf { it.isNotBlank() }
                    ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?.toMutableSet()
                    ?: mutableSetOf()
                put(q.id, initial)
            }
        }
    }

    fun isAnswered(q: AskUserQuestion): Boolean = when (q.control) {
        AskUserControls.CHECKBOX -> !multiAnswers[q.id].isNullOrEmpty()
        else -> !singleAnswers[q.id].isNullOrBlank()
    }

    val missingRequired = questions.filter { it.required && !isAnswered(it) }

    fun buildAnswerJson(): String = buildJsonObject {
        questions.forEach { q ->
            when (q.control) {
                AskUserControls.CHECKBOX ->
                    put(q.id, buildJsonArray { multiAnswers[q.id].orEmpty().forEach { add(JsonPrimitive(it)) } })

                // confirm 给布尔，模型可直接用
                AskUserControls.CONFIRM ->
                    put(q.id, JsonPrimitive(singleAnswers[q.id] == "true"))

                else -> put(q.id, JsonPrimitive(singleAnswers[q.id].orEmpty()))
            }
        }
    }.toString()

    NotificationTemplateDialog(
        type = AppNotification.Type.NORMAL,
        title = stringResource(R.string.ask_user_dialog_title),
        icon = HugeIcons.BubbleChatQuestion,
        heightFraction = sizeFraction,
        onDismissRequest = { /* 必须作答或显式取消；不响应点外部 */ },
        footer = {
            TemplateDialogButton(
                text = stringResource(R.string.ask_user_cancel),
                onClick = onCancel,
                primary = false,
            )
            TemplateDialogButton(
                text = stringResource(R.string.ask_user_submit),
                onClick = { onSubmit(buildAnswerJson()) },
                primary = true,
                // 必答项没填就不让提交 —— 与其提交后让模型收到空串去猜，不如当场拦住
                enabled = missingRequired.isEmpty(),
            )
        },
    ) {
        questions.forEach { q ->
            QuestionBlock(q, colors, singleAnswers, multiAnswers)
        }
        if (missingRequired.isNotEmpty()) {
            Text(
                text = stringResource(R.string.ask_user_missing_required, missingRequired.size),
                color = Color(0xFFFFE08A),
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun QuestionBlock(
    question: AskUserQuestion,
    colors: NotificationTemplateColors.Palette,
    singleAnswers: MutableMap<String, String>,
    multiAnswers: MutableMap<String, MutableSet<String>>,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = question.question,
                color = colors.onContainer,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f, fill = false),
            )
            ControlBadge(question.control, question.required, colors)
        }

        when (question.control) {
            AskUserControls.RADIO -> RadioGroup(question, colors, singleAnswers)
            AskUserControls.SELECT -> SelectGroup(question, colors, singleAnswers)
            AskUserControls.CHECKBOX -> CheckboxGroup(question, colors, multiAnswers)
            AskUserControls.BUTTONS -> ButtonGroup(question, colors, singleAnswers)
            AskUserControls.CONFIRM -> ConfirmGroup(question, colors, singleAnswers)
            AskUserControls.PASSWORD -> AnswerTextField(question, colors, singleAnswers, isPassword = true)
            AskUserControls.TEXTAREA -> AnswerTextField(question, colors, singleAnswers, multiLine = true)
            else -> AnswerTextField(question, colors, singleAnswers)
        }
    }
}

/**
 * 控件类型标记。
 *
 * ★正是为了解决「看不出是单选还是多选」这个具体反馈：
 * 把「单选」/「多选」直接写出来，而不是指望用户从控件形状自己推断。
 * 「必填」也一并标出，免得填完才被拦住。
 */
@Composable
private fun ControlBadge(
    control: String,
    required: Boolean,
    colors: NotificationTemplateColors.Palette,
) {
    val label = when (control) {
        AskUserControls.RADIO -> stringResource(R.string.ask_user_badge_single)
        AskUserControls.SELECT -> stringResource(R.string.ask_user_badge_single)
        AskUserControls.CHECKBOX -> stringResource(R.string.ask_user_badge_multi)
        AskUserControls.BUTTONS -> stringResource(R.string.ask_user_badge_tap)
        AskUserControls.CONFIRM -> stringResource(R.string.ask_user_badge_yesno)
        AskUserControls.PASSWORD -> stringResource(R.string.ask_user_badge_password)
        AskUserControls.TEXTAREA -> stringResource(R.string.ask_user_badge_multiline)
        else -> stringResource(R.string.ask_user_badge_fill)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Badge(label, colors)
        if (required) Badge(stringResource(R.string.ask_user_badge_required), colors)
    }
}

@Composable
private fun Badge(text: String, colors: NotificationTemplateColors.Palette) {
    Box(
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = text, color = colors.onContainer, fontSize = 11.sp)
    }
}

@Composable
private fun RadioGroup(
    question: AskUserQuestion,
    colors: NotificationTemplateColors.Palette,
    answers: MutableMap<String, String>,
) {
    Column {
        question.options.forEach { opt ->
            val selected = answers[question.id] == opt.value
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected, onClick = { answers[question.id] = opt.value })
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // onClick = null：点击由外层 selectable 处理，避免同一个动作触发两次
                RadioButton(selected = selected, onClick = null)
                OptionLabels(opt, colors)
            }
        }
    }
}

@Composable
private fun CheckboxGroup(
    question: AskUserQuestion,
    colors: NotificationTemplateColors.Palette,
    answers: MutableMap<String, MutableSet<String>>,
) {
    val picked = answers.getOrPut(question.id) { mutableSetOf() }
    Column {
        question.options.forEach { opt ->
            val checked = opt.value in picked
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = checked,
                        onValueChange = { on -> if (on) picked.add(opt.value) else picked.remove(opt.value) },
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                OptionLabels(opt, colors)
            }
        }
    }
}

/** 下拉选择：选项多时用它，一排 radio 会撑得很长 */
@Composable
private fun SelectGroup(
    question: AskUserQuestion,
    colors: NotificationTemplateColors.Palette,
    answers: MutableMap<String, String>,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = question.options
        .firstOrNull { it.value == answers[question.id] }?.label
        ?: (question.placeholder ?: stringResource(R.string.ask_user_select_placeholder))

    Box {
        // 用 OutlinedTextField(readOnly) 而不是 ExposedDropdownMenuBox：
        // 后者 API 仍是实验性的，而这里只需要「点一下弹出列表」。
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                // readOnly 字段没有文本选择需求，所以整块可点开是安全的
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = { expanded = true },
                ),
            trailingIcon = {
                Text(text = "▾", color = colors.onContainer, modifier = Modifier.padding(end = 12.dp))
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            question.options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt.label) },
                    onClick = {
                        answers[question.id] = opt.value
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ButtonGroup(
    question: AskUserQuestion,
    colors: NotificationTemplateColors.Palette,
    answers: MutableMap<String, String>,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        question.options.forEach { opt ->
            TemplateDialogButton(
                text = opt.label,
                onClick = { answers[question.id] = opt.value },
                primary = answers[question.id] == opt.value,
            )
        }
    }
}

@Composable
private fun ConfirmGroup(
    question: AskUserQuestion,
    colors: NotificationTemplateColors.Palette,
    answers: MutableMap<String, String>,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TemplateDialogButton(
            text = stringResource(R.string.ask_user_yes),
            onClick = { answers[question.id] = "true" },
            primary = answers[question.id] == "true",
        )
        TemplateDialogButton(
            text = stringResource(R.string.ask_user_no),
            onClick = { answers[question.id] = "false" },
            primary = answers[question.id] == "false",
        )
    }
}

@Composable
private fun AnswerTextField(
    question: AskUserQuestion,
    colors: NotificationTemplateColors.Palette,
    answers: MutableMap<String, String>,
    multiLine: Boolean = false,
    isPassword: Boolean = false,
) {
    OutlinedTextField(
        value = answers[question.id].orEmpty(),
        onValueChange = { answers[question.id] = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = question.placeholder?.let { { Text(it) } },
        singleLine = !multiLine,
        maxLines = if (multiLine) 6 else 1,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text,
        ),
    )
}

@Composable
private fun OptionLabels(opt: AskUserOption, colors: NotificationTemplateColors.Palette) {
    Column(modifier = Modifier.widthIn(min = 0.dp)) {
        Text(text = opt.label, color = colors.onContainer, fontSize = 14.sp)
        opt.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                color = colors.onContainer.copy(alpha = 0.7f),
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
    }
}
