package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

private const val TAG = "CodeTools"

/** 单文件上限，避免把超大文件塞进内存 */
private const val MAX_CODE_BYTES = 512L * 1024

/** 公共下载目录（与 FileTools / DownloadTools / GitTools 一致） */
private const val PUBLIC_ROOT = "/storage/emulated/0/Download"

/**
 * 语法检查 + 代码质量检查（「编程开发工具」下的两项）。
 *
 * ── 语法检查（[createSyntaxCheckTool]）──
 * **只解析，不执行**。这一点很关键：
 *  · JavaScript 用 `new Function(src)` —— ECMAScript 标准里这是「构造但不调用」，
 *    因此只做语法解析，**不会运行**用户的代码。
 *  · Python 用内置 `compile(src, '<string>', 'exec')` —— 纯编译检查语法，不执行。
 * 用户明确要求「只做语法检查不编译」，这里也不产出任何可执行产物。
 *
 * ── 质量检查（[createLintTool]）──
 * 一组与语言无关的实用规则（长行 / 尾随空白 / TODO / 调试残留 / 硬编码密钥 / 空 catch …），
 * 输出形如「第 N 行：什么问题」，全部通过则返回 ok。
 *
 * ── 支持范围 ──
 * JavaScript(TypeScript 近似) / Python / JSON / XML 用各自的真解析器；
 * 其它语言走**通用结构检查**（括号、引号、注释是否闭合）。
 * 所以「未知语言」也能给出有限但有价值的结论，而不是直接拒绝。
 */
fun createSyntaxCheckTool(context: Context): Tool = Tool(
    name = "syntax_check",
    description = """
        Check whether source code is syntactically valid. This PARSES ONLY — it never executes the code.
        Supports JavaScript, Python, JSON, XML, HTML natively (real parsers), and falls back to a generic
        structure check (balanced brackets/quotes/comments) for other languages.
        Returns {"ok": true} when there are no problems, otherwise a list of errors with line/column.
        Note: TypeScript is checked as JavaScript, so type annotations may be reported as errors.
        ES module syntax (import/export) at top level is also reported as an error — wrap or remove it.
    """.trimIndent().replace("\n", " "),
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("code", buildJsonObject {
                    put("type", "string")
                    put("description", "Source code text. Either this or path is required.")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Path to a source file (relative to the download root, or absolute)")
                })
                put("language", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional language override: js | python | json | xml | html | generic. " +
                        "Inferred from the file extension when omitted.")
                })
            },
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val codeArg = obj["code"]?.jsonPrimitive?.contentOrNull
        val pathArg = obj["path"]?.jsonPrimitive?.contentOrNull
        val langArg = obj["language"]?.jsonPrimitive?.contentOrNull

        val (code, inferredName) = loadCode(context, codeArg, pathArg)
        val language = langArg?.lowercase()?.takeIf { it.isNotBlank() }
            ?: languageOf(inferredName)

        val errors = when (language) {
            "js", "javascript", "typescript", "ts" -> checkJavaScript(code)
            "py", "python" -> checkPython(context, code)
            "json" -> checkJson(code)
            "xml" -> checkXml(code)
            "html" -> checkHtml(code)
            else -> checkStructure(code)
        }

        listOf(UIMessagePart.Text(
            buildJsonObject {
                put("ok", JsonPrimitive(errors.isEmpty()))
                put("language", JsonPrimitive(language))
                put("lines", JsonPrimitive(code.count { it == '\n' } + 1))
                if (errors.isNotEmpty()) {
                    put("errors", buildJsonArray {
                        errors.forEach { e ->
                            add(buildJsonObject {
                                put("line", JsonPrimitive(e.line))
                                e.column?.let { put("column", JsonPrimitive(it)) }
                                put("message", JsonPrimitive(e.message))
                            })
                        }
                    })
                }
            }.toString()
        ))
    },
)

/**
 * 代码质量检查（lint）。
 *
 * 检查项与语言基本无关，因此对所有语言生效；JSON/XML 这类数据文件同样适用
 * （例如尾随空白、超长行、文件末尾缺换行）。
 */
fun createLintTool(context: Context): Tool = Tool(
    name = "lint_code",
    description = """
        Lint source code for common quality problems. Returns {"ok": true} when clean, otherwise
        a list of issues with line numbers and rule names.
        Rules: line-too-long(>120), trailing-whitespace, mixed-indentation, tab-indentation,
        consecutive-blank-lines(>2), todo-comment, debug-statement, hardcoded-secret,
        empty-catch-block, missing-final-newline.
        This is a static text analysis — it does not compile, execute, or resolve imports.
    """.trimIndent().replace("\n", " "),
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("code", buildJsonObject {
                    put("type", "string")
                    put("description", "Source code text. Either this or path is required.")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Path to a source file (relative to the download root, or absolute)")
                })
                put("max_line_length", buildJsonObject {
                    put("type", "integer")
                    put("description", "Override the line length limit (default 120)")
                })
                put("rules", buildJsonObject {
                    put("type", "array")
                    put("description", "Optional subset of rule names to run. Omit to run all.")
                    put("items", buildJsonObject { put("type", "string") })
                })
            },
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val codeArg = obj["code"]?.jsonPrimitive?.contentOrNull
        val pathArg = obj["path"]?.jsonPrimitive?.contentOrNull
        val maxLen = (obj["max_line_length"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 120)
            .coerceIn(40, 500)
        val only = (obj["rules"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.filter { it.isNotBlank() }?.toSet()

        val (code, _) = loadCode(context, codeArg, pathArg)
        val issues = lint(code, maxLen, only)

        listOf(UIMessagePart.Text(
            buildJsonObject {
                put("ok", JsonPrimitive(issues.isEmpty()))
                put("issues_count", JsonPrimitive(issues.size))
                if (issues.isNotEmpty()) {
                    put("issues", buildJsonArray {
                        issues.forEach { i ->
                            add(buildJsonObject {
                                put("line", JsonPrimitive(i.line))
                                put("rule", JsonPrimitive(i.rule))
                                put("severity", JsonPrimitive(i.severity))
                                put("message", JsonPrimitive(i.message))
                                i.snippet?.let { put("snippet", JsonPrimitive(it)) }
                            })
                        }
                    })
                }
            }.toString()
        ))
    },
)

// ────────────────────────── 数据模型 ──────────────────────────

private data class SyntaxError(val line: Int, val column: Int?, val message: String)
private data class LintIssue(
    val line: Int,
    val rule: String,
    val severity: String,
    val message: String,
    val snippet: String? = null,
)

// ────────────────────────── 输入读取 ──────────────────────────

/** 读代码：优先 code 参数，其次 path。返回 (内容, 用于推断语言的文件名) */
private fun loadCode(context: Context, code: String?, path: String?): Pair<String, String> {
    code?.takeIf { it.isNotBlank() }?.let { return it to "" }
    if (path.isNullOrBlank()) {
        throw IllegalArgumentException("需要 code 或 path 之一")
    }
    val f = File(path).let { if (it.isAbsolute) it else File(PUBLIC_ROOT, path) }
    if (!f.isFile) {
        throw IllegalArgumentException("文件不存在：${f.absolutePath}")
    }
    if (f.length() > MAX_CODE_BYTES) {
        throw IllegalArgumentException("文件过大（${f.length()} 字节，上限 $MAX_CODE_BYTES）")
    }
    return f.readText() to f.name
}

private fun languageOf(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "js", "mjs", "cjs", "jsx" -> "js"
        "ts", "tsx" -> "typescript"
        "py", "pyw" -> "python"
        "json" -> "json"
        "xml", "plist", "svg", "xsd", "xsl" -> "xml"
        "html", "htm", "xhtml" -> "html"
        else -> "generic"
    }
}

// ────────────────────────── 语法检查：各语言 ──────────────────────────

/**
 * JavaScript / TypeScript：用 `new Function(src)` 只解析不执行。
 *
 * `new Function` 是 ECMAScript 标准的一部分，它的语义是「构造一个函数对象」——
 * 语法解析发生在构造阶段，而函数体**不会**被调用。因此这段代码不会被运行。
 *
 * 用 JsonPrimitive 把源码转成合法的 JS 字符串字面量（自动处理引号与换行转义），
 * 避免拼接注入。
 */
private fun checkJavaScript(code: String): List<SyntaxError> {
    val literal = JsonPrimitive(code).toString()
    var ctx: QuickJSContext? = null
    return try {
        ctx = QuickJSContext.create()
        ctx.evaluate("new Function($literal)")
        emptyList()
    } catch (t: Throwable) {
        listOf(parseJsError(t.message))
    } finally {
        runCatching { ctx?.destroy() }
    }
}

/** 从 QuickJS 的异常信息里提取行号与消息 */
private fun parseJsError(message: String?): SyntaxError {
    val raw = (message ?: "语法错误").trim()
    // QuickJS 的报错通常形如 "SyntaxError: unexpected token '}' at <eval> :3"
    val lineMatch = Regex(":(\\d+)\\s*$").find(raw)
    val line = lineMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val cleaned = raw
        .replace(Regex("\\s*at\\s+<eval>\\s*:\\d+\\s*$"), "")
        .replace(Regex("^SyntaxError:\\s*"), "SyntaxError: ")
    return SyntaxError(line = line, column = null, message = cleaned)
}

/**
 * Python：用内置 `compile()` —— 纯语法编译，不执行。
 *
 * Chaquopy 要求 Python 在主线程启动一次；之后可在后台线程使用。
 * 编译错误会带 "line N" 信息，从中提取行号。
 */
private fun checkPython(context: Context, code: String): List<SyntaxError> {
    ensurePythonStarted(context)
    return try {
        val py = Python.getInstance()
        py.getModule("builtins").callAttr("compile", code, "<syntax_check>", "exec")
        emptyList()
    } catch (t: Throwable) {
        val msg = t.message ?: "语法错误"
        // Python 的 SyntaxError 文本形如: File "<syntax_check>", line 3
        val line = Regex("line (\\d+)").find(msg)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val cleaned = msg.lines()
            .firstOrNull { it.contains("Error") || it.contains("error") }
            ?.trim()
            ?: msg.trim()
        listOf(SyntaxError(line = line, column = null, message = cleaned.take(400)))
    }
}

private fun ensurePythonStarted(context: Context) {
    if (Python.isStarted()) return
    runBlocking {
        withContext(Dispatchers.Main) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
    }
}

/** JSON：kotlinx-serialization 的严格解析 */
private fun checkJson(code: String): List<SyntaxError> {
    return try {
        me.rerere.rikkahub.utils.JsonInstant.parseToJsonElement(code)
        emptyList()
    } catch (t: Throwable) {
        val msg = (t.message ?: "JSON 解析失败").lineSequence().first().trim().take(300)
        // kotlinx 的报错里带 offset，换算成行号更有用
        val offset = Regex("offset=(\\d+)").find(t.message ?: "")?.groupValues?.get(1)?.toIntOrNull()
        val line = offset?.let { off ->
            var l = 1
            for (i in 0 until minOf(off, code.length)) if (code[i] == '\n') l++
            l
        } ?: 0
        listOf(SyntaxError(line = line, column = null, message = msg))
    }
}

/** XML：用 Android 内置的严格解析器（jsoup 对 XML 过于宽松，不适合做语法检查） */
private fun checkXml(code: String): List<SyntaxError> {
    return try {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        // 关闭外部实体解析（防 XXE）。用 runCatching 包裹：
        // 并非所有 Android 版本的解析器都支持这个 feature，
        // 若设置失败就让整段检查失败，会误报「XML 解析失败」——那是假阳性。
        runCatching {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        // 不设置 ErrorHandler：默认行为就是遇到错误抛 SAXParseException（带行号），正是我们要的
        factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(code.toByteArray(Charsets.UTF_8)))
        emptyList()
    } catch (t: Throwable) {
        val msg = (t.message ?: "XML 解析失败").trim().take(300)
        val line = Regex("line(?:Number)?[: ]+(\\d+)", RegexOption.IGNORE_CASE)
            .find(msg)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        listOf(SyntaxError(line = line, column = null, message = msg))
    }
}

/** HTML：jsoup 是容错解析器，只在结构严重异常时报告 */
private fun checkHtml(code: String): List<SyntaxError> {
    return try {
        val doc = Jsoup.parse(code)
        val errors = mutableListOf<SyntaxError>()
        // jsoup 的解析错误（如未闭合标签）通过 parser 的 errors 暴露较麻烦，
        // 这里做一个实用近似：统计主要标签的开闭配平
        val opened = Regex("<(div|span|p|table|ul|ol|li|form|section)\\b", RegexOption.IGNORE_CASE)
            .findAll(code).count()
        val closed = Regex("</(div|span|p|table|ul|ol|li|form|section)>", RegexOption.IGNORE_CASE)
            .findAll(code).count()
        if (opened != closed) {
            errors += SyntaxError(
                line = 0,
                column = null,
                message = "标签开闭数量不匹配（开 $opened / 闭 $closed）—— HTML 容错解析可继续，但结构可能有问题",
            )
        }
        if (doc.title().isBlank() && code.contains("<html", true)) {
            errors += SyntaxError(line = 0, column = null, message = "<html> 缺少 <title>")
        }
        errors
    } catch (t: Throwable) {
        listOf(SyntaxError(0, null, (t.message ?: "HTML 解析失败").take(300)))
    }
}

/**
 * 通用结构检查（用于没有内置解析器的语言）。
 *
 * 只检查**跨行也成立的强结构约束**：括号/方括号/花括号配平、字符串与注释是否闭合。
 * 不做关键字级别的判断 —— 那需要真正的词法/语法分析，文本近似会产生大量误报。
 */
private fun checkStructure(code: String): List<SyntaxError> {
    val errors = mutableListOf<SyntaxError>()
    val stack = ArrayDeque<Pair<Char, Int>>()
    var line = 1
    var i = 0
    var inString: Char? = null
    var inLineComment = false
    var inBlockComment = false

    while (i < code.length) {
        val c = code[i]
        val next = code.getOrNull(i + 1)

        if (c == '\n') {
            line++
            inLineComment = false
            i++
            continue
        }
        if (inLineComment) { i++; continue }

        if (inBlockComment) {
            if (c == '*' && next == '/') { inBlockComment = false; i += 2; continue }
            i++
            continue
        }
        if (inString != null) {
            if (c == '\\') { i += 2; continue }
            if (c == inString) inString = null
            i++
            continue
        }

        // 注释开始（支持 // 与 # 与 /* */）
        if (c == '/' && next == '/') { inLineComment = true; i += 2; continue }
        if (c == '/' && next == '*') { inBlockComment = true; i += 2; continue }
        if (c == '#') { inLineComment = true; i++; continue }

        if (c == '"' || c == '\'' || c == '`') { inString = c; i++; continue }

        if (c == '(' || c == '[' || c == '{') { stack.addLast(c to line); i++; continue }
        if (c == ')' || c == ']' || c == '}') {
            val open = when (c) { ')' -> '('; ']' -> '['; else -> '{' }
            val last = stack.removeLastOrNull()
            if (last == null) {
                errors += SyntaxError(line, null, "多余的闭合符号 '$c'（没有对应的 '$open'）")
            } else if (last.first != open) {
                errors += SyntaxError(line, null, "闭合符号不匹配：期望 '${last.first}' 的闭合，实际是 '$c'（'${last.first}' 在第 ${last.second} 行打开）")
            }
            i++
            continue
        }
        i++
    }

    if (inString != null) {
        errors += SyntaxError(line, null, "字符串未闭合（起始引号 '$inString'）")
    }
    if (inBlockComment) {
        errors += SyntaxError(line, null, "块注释未闭合（缺少 */）")
    }
    stack.forEach { (ch, ln) ->
        errors += SyntaxError(ln, null, "未闭合的 '$ch'")
    }
    return errors
}

// ────────────────────────── 质量检查（lint）──────────────────────────

private fun lint(code: String, maxLen: Int, only: Set<String>?): List<LintIssue> {
    val issues = mutableListOf<LintIssue>()
    fun enabled(rule: String) = only.isNullOrEmpty() || rule in only
    val lines = code.split('\n')

    var tabIndented = 0
    var spaceIndented = 0
    var blankRun = 0
    var emptyCatchPending = false

    lines.forEachIndexed { idx, raw ->
        val lineNo = idx + 1
        val line = raw.trimEnd('\r')

        // 1) 超长行
        if (enabled("line-too-long") && line.length > maxLen) {
            issues += LintIssue(lineNo, "line-too-long", "warning",
                "行长度 ${line.length} 超过 $maxLen", line.take(60) + "…")
        }
        // 2) 尾随空白
        if (enabled("trailing-whitespace") && raw != raw.trimEnd() && raw.isNotBlank()) {
            issues += LintIssue(lineNo, "trailing-whitespace", "info",
                "行尾有多余空白（${raw.length - raw.trimEnd().length} 个字符）")
        }
        // 3/4) 缩进统计
        if (raw.startsWith("\t")) tabIndented++
        else if (raw.startsWith(" ")) spaceIndented++

        // 5) 连续空行
        if (raw.isBlank()) {
            blankRun++
            if (enabled("consecutive-blank-lines") && blankRun == 3) {
                issues += LintIssue(lineNo, "consecutive-blank-lines", "info", "连续 3 行以上空行")
            }
        } else {
            blankRun = 0
        }

        // 6) TODO 类注释
        if (enabled("todo-comment")) {
            val m = Regex("\\b(TODO|FIXME|XXX|HACK)\\b").find(line)
            if (m != null) {
                issues += LintIssue(lineNo, "todo-comment", "info",
                    "未完成的标记 ${m.value}", line.trim().take(80))
            }
        }

        // 7) 调试残留
        if (enabled("debug-statement")) {
            val patterns = listOf(
                Regex("\\bconsole\\.(log|debug|trace)\\s*\\("),
                Regex("^\\s*print\\s*\\("),
                Regex("\\bSystem\\.out\\.print"),
                Regex("\\bdebugger\\s*;?\\s*$"),
                Regex("\\bvar_dump\\s*\\("),
            )
            val hit = patterns.firstOrNull { it.containsMatchIn(line) }
            if (hit != null) {
                issues += LintIssue(lineNo, "debug-statement", "warning",
                    "疑似调试输出残留", line.trim().take(80))
            }
        }

        // 8) 硬编码密钥
        if (enabled("hardcoded-secret")) {
            val m = Regex(
                """(?i)\b(password|passwd|pwd|secret|api[_-]?key|apikey|access[_-]?token|auth[_-]?token|private[_-]?key)\b\s*[:=]\s*["'][^"']{6,}["']"""
            ).find(line)
            if (m != null && !line.trimStart().startsWith("//") && !line.trimStart().startsWith("#")) {
                issues += LintIssue(lineNo, "hardcoded-secret", "error",
                    "疑似硬编码凭据（${m.groupValues[1]}）—— 建议改用配置或环境变量",
                    line.trim().take(60))
            }
        }

        // 9) 空 catch 块（本行出现 catch，下一非空行就是 }）
        if (enabled("empty-catch-block")) {
            if (Regex("\\bcatch\\s*\\([^)]*\\)\\s*\\{\\s*$").containsMatchIn(line)) {
                emptyCatchPending = true
            } else if (emptyCatchPending && line.trim() == "}") {
                issues += LintIssue(lineNo, "empty-catch-block", "warning",
                    "空 catch 块 —— 异常被静默吞掉，建议至少记录日志")
                emptyCatchPending = false
            } else if (emptyCatchPending && line.isNotBlank()) {
                emptyCatchPending = false
            }
        }
    }

    // 10) 混合缩进
    if (enabled("mixed-indentation") && tabIndented > 0 && spaceIndented > 0) {
        issues += LintIssue(0, "mixed-indentation", "warning",
            "同一文件混用 Tab 与空格缩进（Tab $tabIndented 行 / 空格 $spaceIndented 行）")
    }
    if (enabled("tab-indentation") && tabIndented > 0 && spaceIndented == 0) {
        issues += LintIssue(0, "tab-indentation", "info", "使用 Tab 缩进（共 $tabIndented 行）")
    }

    // 11) 文件末尾缺换行
    if (enabled("missing-final-newline") && code.isNotEmpty() && !code.endsWith("\n")) {
        issues += LintIssue(lines.size, "missing-final-newline", "info", "文件末尾缺少换行符")
    }

    return issues.sortedWith(compareBy({ it.line }, { it.rule }))
}
