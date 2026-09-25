package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * 面向 LLM 的网络检索工具集（全部无需 API Key）。
 *
 * 解决的问题：原始 HTML / 裸文本对 LLM 不友好 —— 导航广告噪音、编码错乱、
 * 一次返回过长导致上下文浪费。
 *
 * 三个工具：
 *  - fetch_url        抓网页 → 正文抽取 → Markdown，支持分页续读
 *  - github_search    封装 GitHub Search API（repositories / code / issues）
 *  - wikipedia_search 封装 Wikipedia API（search / summary）
 */

private const val BROWSER_UA =
    "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

private const val DEFAULT_MAX_CHARS = 20_000
private const val HARD_MAX_CHARS = 100_000

private val researchHttp: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()
}

/** 简易 HTTP 结果 */
private class HttpResult(
    val status: Int,
    val headers: Headers,
    val bytes: ByteArray,
) {
    fun text(): String = String(bytes, Charsets.UTF_8)
}

private fun httpGet(
    url: String,
    accept: String,
    timeoutSeconds: Long = 30,
    headers: Map<String, String> = emptyMap(),
): HttpResult = withContextBlocking {
    val client = researchHttp.newBuilder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()
    val builder = Request.Builder()
        .url(url)
        .header("User-Agent", BROWSER_UA)
        .header("Accept", accept)
        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        .get()
    headers.forEach { (k, v) -> builder.header(k, v) }
    val resp = client.newCall(builder.build()).execute()
    resp.use {
        HttpResult(it.code, it.headers, it.body?.bytes() ?: ByteArray(0))
    }
}

/** 在 IO 线程执行阻塞代码（execute 本身是 suspend，可直接用 withContext） */
private inline fun <T> withContextBlocking(block: () -> T): T = block()

// ══════════════════════════════════════════════════════════════
// 编码探测
// ══════════════════════════════════════════════════════════════

private val META_CHARSET = Regex(
    """<meta[^>]+charset\s*=\s*["']?\s*([A-Za-z0-9_\-]+)""",
    RegexOption.IGNORE_CASE
)
private val CT_CHARSET = Regex("""charset\s*=\s*["']?([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE)

/**
 * 字节 → 字符串。优先级：
 * 1) HTTP Content-Type 的 charset
 * 2) BOM
 * 3) HTML 内 <meta charset>（仅扫描前 8KB）
 * 4) 严格 UTF-8 解码成功 → UTF-8；否则回退 GB18030（中文站常见）
 */
private fun decodeBody(bytes: ByteArray, contentType: String?): Pair<String, String> {
    contentType?.let { ct ->
        CT_CHARSET.find(ct)?.groupValues?.get(1)?.let { name ->
            runCatching { Charset.forName(name.trim()) }.getOrNull()?.let { cs ->
                return runCatching { String(bytes, cs) to cs.name() }
                    .getOrElse { String(bytes, Charsets.UTF_8) to "UTF-8" }
            }
        }
    }
    if (bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
    ) {
        return String(bytes, 3, bytes.size - 3, Charsets.UTF_8) to "UTF-8(BOM)"
    }
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE) to "UTF-16LE"
    }
    val head = String(bytes, 0, minOf(bytes.size, 8192), Charsets.ISO_8859_1)
    META_CHARSET.find(head)?.groupValues?.get(1)?.let { name ->
        runCatching { Charset.forName(name.trim()) }.getOrNull()?.let { cs ->
            if (!cs.name().equals("UTF-8", ignoreCase = true)) {
                return String(bytes, cs) to cs.name()
            }
        }
    }
    val strict = runCatching {
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()
    if (strict != null) return strict to "UTF-8"
    return runCatching { String(bytes, Charset.forName("GB18030")) to "GB18030" }
        .getOrElse { String(bytes, Charsets.ISO_8859_1) to "ISO-8859-1" }
}

// ══════════════════════════════════════════════════════════════
// HTML → Markdown
// ══════════════════════════════════════════════════════════════

private object HtmlToMarkdown {

    /** 无文本价值 / 会污染正文的标签 */
    private val DROP_TAGS = setOf(
        "script", "style", "noscript", "iframe", "svg", "canvas", "head", "meta",
        "link", "template", "form", "input", "select", "textarea", "button",
        "object", "embed", "noscript"
    )

    private val ROOT_SELECTORS = listOf(
        "article", "main", "[role=main]", "#content", "#main",
        ".post-content", ".article-content", ".entry-content", ".markdown-body", "body"
    )

    private val NOISE_SELECTORS = listOf(
        "nav", "footer", "aside", "header",
        "[role=navigation]", "[role=banner]", "[role=contentinfo]", "[role=complementary]",
        ".nav", ".navbar", ".navigation", ".menu", ".footer", ".site-footer",
        ".header", ".site-header", ".sidebar", ".aside", ".advertisement", ".ads",
        ".ad", ".cookie", ".consent", ".popup", ".modal", ".share", ".social",
        ".comment", ".comments", ".related", ".recommend", ".breadcrumb", ".pagination"
    )

    fun convert(html: String, baseUrl: String, keepNoise: Boolean): String {
        val doc = Jsoup.parse(html, baseUrl)
        doc.select(DROP_TAGS.joinToString(",")).remove()
        if (!keepNoise) {
            NOISE_SELECTORS.forEach { sel -> runCatching { doc.select(sel).remove() } }
        }
        val root = ROOT_SELECTORS.firstNotNullOfOrNull { sel ->
            runCatching { doc.selectFirst(sel) }.getOrNull()
        } ?: doc.body() ?: return ""
        val sb = StringBuilder()
        renderChildren(root, sb, 0)
        return normalize(sb.toString())
    }

    private fun renderChildren(el: Element, sb: StringBuilder, depth: Int) {
        for (child in el.childNodes()) renderNode(child, sb, depth)
    }

    private fun renderNode(node: Node, sb: StringBuilder, depth: Int) {
        when (node) {
            is TextNode -> {
                val t = node.text().replace(Regex("[\\t\\r\\n ]+"), " ")
                if (t.isNotEmpty()) sb.append(t)
            }

            is Element -> renderElement(node, sb, depth)
            else -> Unit
        }
    }

    private fun renderElement(el: Element, sb: StringBuilder, depth: Int) {
        val tag = el.tagName().lowercase()
        when (tag) {
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val level = tag[1].digitToInt()
                sb.append("\n\n").append("#".repeat(level)).append(" ")
                renderChildren(el, sb, depth)
                sb.append("\n\n")
            }

            "p" -> {
                sb.append("\n\n"); renderChildren(el, sb, depth); sb.append("\n\n")
            }

            "br" -> sb.append("\n")

            "hr" -> sb.append("\n\n---\n\n")

            "strong", "b" -> {
                sb.append("**"); renderChildren(el, sb, depth); sb.append("**")
            }

            "em", "i" -> {
                sb.append("*"); renderChildren(el, sb, depth); sb.append("*")
            }

            "del", "s", "strike" -> {
                sb.append("~~"); renderChildren(el, sb, depth); sb.append("~~")
            }

            "code" -> {
                val inPre = (el.parent() as? Element)?.tagName()?.equals("pre", true) == true
                if (inPre) {
                    renderChildren(el, sb, depth)
                } else {
                    sb.append('`'); renderChildren(el, sb, depth); sb.append('`')
                }
            }

            "pre" -> {
                sb.append("\n\n```\n")
                sb.append(el.wholeText().trimEnd())
                sb.append("\n```\n\n")
            }

            "blockquote" -> {
                val inner = StringBuilder()
                renderChildren(el, inner, depth)
                val quoted = normalize(inner.toString()).lines()
                    .filter { it.isNotBlank() }
                    .joinToString("\n") { "> $it" }
                sb.append("\n\n").append(quoted).append("\n\n")
            }

            "a" -> {
                val href = el.absUrl("href").ifBlank { el.attr("href") }
                val inner = StringBuilder()
                renderChildren(el, inner, depth)
                val label = normalize(inner.toString()).trim()
                when {
                    href.isBlank() || href.startsWith("#") -> sb.append(label.ifBlank { href })
                    label.isBlank() -> sb.append("<").append(href).append(">")
                    else -> sb.append('[').append(label).append("](").append(href).append(')')
                }
            }

            "img" -> {
                val src = el.absUrl("src").ifBlank { el.attr("src") }
                if (src.isNotBlank() && !src.startsWith("data:")) {
                    sb.append("![").append(el.attr("alt")).append("](").append(src).append(')')
                }
            }

            "ul" -> renderList(el, sb, depth, ordered = false)
            "ol" -> renderList(el, sb, depth, ordered = true)

            "table" -> renderTable(el, sb)

            "div", "section", "article", "main", "figure", "figcaption", "details", "summary" -> {
                sb.append("\n"); renderChildren(el, sb, depth); sb.append("\n")
            }

            else -> renderChildren(el, sb, depth)
        }
    }

    private fun renderList(el: Element, sb: StringBuilder, depth: Int, ordered: Boolean) {
        sb.append("\n")
        val indent = "  ".repeat(depth)
        var idx = 1
        for (li in el.children()) {
            if (!li.tagName().equals("li", true)) continue
            sb.append(indent).append(if (ordered) "${idx}. " else "- ")
            var hadBlockChild = false
            for (child in li.childNodes()) {
                if (child is Element && child.tagName().lowercase() in listOf("ul", "ol")) {
                    renderList(child, sb, depth + 1, child.tagName().equals("ol", true))
                    hadBlockChild = true
                } else {
                    renderNode(child, sb, depth + 1)
                }
            }
            if (!hadBlockChild && (sb.isEmpty() || sb.last() != '\n')) sb.append("\n")
            idx++
        }
        sb.append("\n")
    }

    private fun renderTable(table: Element, sb: StringBuilder) {
        val rows = table.select("tr")
        if (rows.isEmpty()) return
        sb.append("\n\n")
        var headerDone = false
        for (row in rows) {
            val cells = row.select("th,td")
            if (cells.isEmpty()) continue
            sb.append("| ")
            cells.forEach { c ->
                val cell = StringBuilder()
                renderChildren(c, cell, 0)
                sb.append(
                    normalize(cell.toString())
                        .replace("|", "\\|")
                        .replace('\n', ' ')
                        .trim()
                )
                sb.append(" | ")
            }
            sb.append("\n")
            if (!headerDone && (row.select("th").isNotEmpty() || rows.size > 1)) {
                sb.append("|").append(" --- |".repeat(cells.size)).append("\n")
                headerDone = true
            }
        }
        sb.append("\n")
    }

    private fun normalize(s: String): String {
        var t = s.replace('\u00A0', ' ')
        t = t.replace(Regex("[ \\t]+"), " ")
        t = t.replace(Regex(" *\\n *"), "\n")
        t = t.replace(Regex("\\n{3,}"), "\n\n")
        return t.lines().joinToString("\n") { it.trimEnd() }.trim()
    }
}

// ══════════════════════════════════════════════════════════════
// 工具 1：fetch_url
// ══════════════════════════════════════════════════════════════

fun createFetchUrlTool(): Tool = Tool(
    name = "fetch_url",
    description = """
        Fetch a web page and turn it into clean, LLM-friendly text (Markdown by default).
        Prefer this over web_fetch when you need readable page content: it removes navigation,
        ads and scripts, picks the main article body, auto-detects the character encoding
        (UTF-8 / GBK / GB18030 / UTF-16 …) and supports paging through long pages.

        Args:
        - url: absolute URL including https:// (required)
        - format: "markdown" (default) | "text" | "html"
        - max_chars: max characters returned by this call (default 20000, hard cap 100000)
        - start_index: character offset to start from (default 0) — use it to keep reading
        - keep_noise: true to keep nav/footer/sidebar (default false)
        - timeout: seconds (default 30, max 60)

        Response (JSON):
        - url, status, content_type, charset, format, title
        - text: the requested slice
        - total_chars, start_index, end_index, has_more
        - links: up to 30 links on the page (url + anchor text) for follow-up calls
        - note: guidance (e.g. how to continue when has_more is true)

        Tips:
        - has_more == true  →  call again with start_index = end_index
        - For JS-rendered sites that return little text, try another source or the page's API.
    """.trimIndent().replace("\n", " "),
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute URL, e.g. https://example.com/article")
                })
                put("format", buildJsonObject {
                    put("type", "string")
                    put("description", "markdown (default) | text | html")
                })
                put("max_chars", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max characters to return (default 20000, hard cap 100000)")
                })
                put("start_index", buildJsonObject {
                    put("type", "integer")
                    put("description", "Character offset to start from (default 0)")
                })
                put("keep_noise", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Keep nav/footer/sidebar (default false)")
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put("description", "Timeout seconds (default 30, max 60)")
                })
            },
            required = listOf("url"),
        )
    },
    execute = { args -> runFetchUrl(args) },
)

private suspend fun runFetchUrl(args: JsonElement): List<UIMessagePart> {
    val obj = args.jsonObject
    val urlStr = obj["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (urlStr.isEmpty()) error("url is required")
    val format = (obj["format"]?.jsonPrimitive?.contentOrNull ?: "markdown").lowercase()
    val maxChars = (obj["max_chars"]?.jsonPrimitive?.intOrNull ?: DEFAULT_MAX_CHARS)
        .coerceIn(200, HARD_MAX_CHARS)
    val startIndex = (obj["start_index"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
    val keepNoise = obj["keep_noise"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
    val timeout = (obj["timeout"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(5, 60).toLong()

    val res = withContext(Dispatchers.IO) {
        httpGet(
            url = urlStr,
            accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            timeoutSeconds = timeout
        )
    }
    val contentType = res.headers["Content-Type"]
    val (decoded, charset) = decodeBody(res.bytes, contentType)

    val looksHtml = contentType?.contains("html", ignoreCase = true) == true ||
        decoded.trimStart().startsWith("<")

    var title = ""
    var links: List<Pair<String, String>> = emptyList()
    val text: String
    if (looksHtml) {
        val doc = Jsoup.parse(decoded, urlStr)
        title = doc.title()
        links = runCatching {
            doc.select("a[href]").mapNotNull { a ->
                val abs = a.absUrl("href")
                val label = a.text().trim()
                if (abs.startsWith("http") && label.isNotEmpty()) abs to label else null
            }.distinctBy { it.first }.take(30)
        }.getOrElse { emptyList() }
        text = when (format) {
            "html" -> decoded
            "text" -> Jsoup.parse(decoded, urlStr).also {
                it.select("script,style,noscript").remove()
            }.text()
            else -> HtmlToMarkdown.convert(decoded, urlStr, keepNoise)
        }
    } else {
        text = decoded
    }

    val total = text.length
    val end = minOf(startIndex + maxChars, total)
    val slice = if (startIndex >= total) "" else text.substring(startIndex, end)
    val hasMore = end < total

    val payload = buildJsonObject {
        put("url", JsonPrimitive(urlStr))
        put("status", JsonPrimitive(res.status))
        put("content_type", JsonPrimitive(contentType ?: ""))
        put("charset", JsonPrimitive(charset))
        put("format", JsonPrimitive(if (looksHtml) format else "raw"))
        put("title", JsonPrimitive(title))
        put("text", JsonPrimitive(slice))
        put("total_chars", JsonPrimitive(total))
        put("start_index", JsonPrimitive(startIndex))
        put("end_index", JsonPrimitive(end))
        put("has_more", JsonPrimitive(hasMore))
        if (links.isNotEmpty()) {
            put("links", buildJsonArray {
                links.forEach { (u, l) ->
                    add(buildJsonObject {
                        put("url", JsonPrimitive(u))
                        put("text", JsonPrimitive(l))
                    })
                }
            })
        }
        when {
            res.status !in 200..299 ->
                put("note", JsonPrimitive("HTTP ${res.status} — check the URL, or the site may block non-browser clients."))
            hasMore ->
                put("note", JsonPrimitive("Truncated. Call fetch_url again with start_index=$end to continue."))
            total == 0 ->
                put("note", JsonPrimitive("No text extracted — the page may be JS-rendered or block scrapers."))
        }
    }
    return listOf(UIMessagePart.Text(payload.toString()))
}

// ══════════════════════════════════════════════════════════════
// 工具 2：github_search
// ══════════════════════════════════════════════════════════════

fun createGithubSearchTool(): Tool = Tool(
    name = "github_search",
    description = """
        Search GitHub through the official Search API (works without a key; an optional token
        raises the rate limit). Results are trimmed into compact, LLM-friendly JSON.

        Use it to find open-source projects, code samples or issues — far more reliable than a
        generic web search for programming questions.

        Args:
        - type: "repositories" (default) | "code" | "issues"
        - query: keywords, supports GitHub qualifiers, e.g.
                 "kotlin coroutines", "repo:square/okhttp", "language:rust stars:>500",
                 "in:readme android ssh", "is:issue is:open label:bug"
        - sort: repositories → stars|forks|updated|best_match ; issues → created|updated|comments
        - order: desc (default) | asc
        - per_page: 1-50 (default 10)
        - language: convenience filter (repositories only), e.g. "kotlin"
        - min_stars: convenience filter (repositories only), e.g. 500
        - token: optional GitHub token for higher rate limits

        Response (JSON): {type, query, total_count, returned, items:[…]}
        - repositories: full_name, html_url, description, stars, forks, open_issues,
          language, updated_at, archived, license, topics
        - code: repository, path, html_url, snippet
        - issues: title, html_url, state, created_at, updated_at, comments,
          is_pull_request, body (first 800 chars)
    """.trimIndent().replace("\n", " "),
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("type", buildJsonObject {
                    put("type", "string")
                    put("description", "repositories (default) | code | issues")
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Search keywords with optional GitHub qualifiers")
                })
                put("sort", buildJsonObject {
                    put("type", "string")
                    put("description", "stars|forks|updated|best_match (repos) or created|updated|comments (issues)")
                })
                put("order", buildJsonObject {
                    put("type", "string")
                    put("description", "desc (default) | asc")
                })
                put("per_page", buildJsonObject {
                    put("type", "integer")
                    put("description", "1-50 (default 10)")
                })
                put("language", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional language filter (repositories)")
                })
                put("min_stars", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional minimum stars (repositories)")
                })
                put("token", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional GitHub token to raise rate limits")
                })
            },
            required = listOf("query"),
        )
    },
    execute = { args -> runGithubSearch(args) },
)

private suspend fun runGithubSearch(args: JsonElement): List<UIMessagePart> {
    val obj = args.jsonObject
    val type = (obj["type"]?.jsonPrimitive?.contentOrNull ?: "repositories").lowercase()
    if (type !in listOf("repositories", "code", "issues")) {
        return listOf(UIMessagePart.Text(
            buildJsonObject {
                put("error", JsonPrimitive("invalid type"))
                put("hint", JsonPrimitive("type must be repositories | code | issues"))
            }.toString()
        ))
    }
    var query = obj["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (query.isEmpty()) error("query is required")
    val sort = obj["sort"]?.jsonPrimitive?.contentOrNull
    val order = obj["order"]?.jsonPrimitive?.contentOrNull
    val perPage = (obj["per_page"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 50)
    val token = obj["token"]?.jsonPrimitive?.contentOrNull

    if (type == "repositories") {
        obj["language"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?.let { query += " language:$it" }
        obj["min_stars"]?.jsonPrimitive?.intOrNull?.let { query += " stars:>=$it" }
    }

    val url = buildString {
        append("https://api.github.com/search/").append(type)
        append("?q=").append(java.net.URLEncoder.encode(query, "UTF-8"))
        append("&per_page=").append(perPage)
        if (!sort.isNullOrBlank()) append("&sort=").append(sort)
        if (!order.isNullOrBlank()) append("&order=").append(order)
    }

    val extra = buildMap {
        put("Accept", "application/vnd.github+json")
        put("X-GitHub-Api-Version", "2022-11-28")
        if (!token.isNullOrBlank()) put("Authorization", "Bearer $token")
    }
    val res = withContext(Dispatchers.IO) {
        httpGet(url, accept = "application/vnd.github+json", timeoutSeconds = 30, headers = extra)
    }
    val remaining = res.headers["X-RateLimit-Remaining"]
    val bodyText = res.text()
    val json = runCatching { Json.parseToJsonElement(bodyText).jsonObject }.getOrNull()

    if (res.status !in 200..299 || json == null) {
        val msg = json?.get("message")?.jsonPrimitive?.contentOrNull ?: bodyText.take(500)
        return listOf(UIMessagePart.Text(
            buildJsonObject {
                put("error", JsonPrimitive("HTTP ${res.status}"))
                put("message", JsonPrimitive(msg))
                if (!remaining.isNullOrBlank()) put("rate_limit_remaining", JsonPrimitive(remaining))
                put("hint", JsonPrimitive(
                    if (res.status == 403 || res.status == 429)
                        "Rate limited. Pass the optional `token` (GitHub PAT) and retry."
                    else "Check query syntax (GitHub search qualifiers)."
                ))
            }.toString()
        ))
    }

    val rawItems: JsonArray = runCatching {
        json["items"] as? JsonArray
    }.getOrNull() ?: JsonArray(emptyList())

    val trimmed = buildJsonArray {
        rawItems.forEach { el ->
            val it = runCatching { el.jsonObject }.getOrNull() ?: return@forEach
            fun s(k: String) = it[k]?.jsonPrimitive?.contentOrNull
            fun n(k: String) = it[k]?.jsonPrimitive?.intOrNull
            when (type) {
                "repositories" -> add(buildJsonObject {
                    put("full_name", JsonPrimitive(s("full_name") ?: ""))
                    put("html_url", JsonPrimitive(s("html_url") ?: ""))
                    put("description", JsonPrimitive((s("description") ?: "").take(400)))
                    put("stars", JsonPrimitive(n("stargazers_count") ?: 0))
                    put("forks", JsonPrimitive(n("forks_count") ?: 0))
                    put("open_issues", JsonPrimitive(n("open_issues_count") ?: 0))
                    put("language", JsonPrimitive(s("language") ?: ""))
                    put("updated_at", JsonPrimitive((s("updated_at") ?: "").take(10)))
                    put("archived", JsonPrimitive(s("archived") == "true"))
                    runCatching {
                        it["license"]?.jsonObject?.get("spdx_id")?.jsonPrimitive?.contentOrNull
                    }.getOrNull()?.let { lic -> put("license", JsonPrimitive(lic)) }
                    runCatching {
                        (it["topics"] as? JsonArray)?.let { arr ->
                            put("topics", buildJsonArray {
                                arr.take(8).forEach { t ->
                                    t.jsonPrimitive.contentOrNull?.let { v -> add(JsonPrimitive(v)) }
                                }
                            })
                        }
                    }
                })

                "code" -> add(buildJsonObject {
                    put("repository", JsonPrimitive(s("repository") ?: ""))
                    put("path", JsonPrimitive(s("path") ?: ""))
                    put("html_url", JsonPrimitive(s("html_url") ?: ""))
                })

                else -> add(buildJsonObject {
                    put("title", JsonPrimitive(s("title") ?: ""))
                    put("html_url", JsonPrimitive(s("html_url") ?: ""))
                    put("state", JsonPrimitive(s("state") ?: ""))
                    put("created_at", JsonPrimitive((s("created_at") ?: "").take(10)))
                    put("updated_at", JsonPrimitive((s("updated_at") ?: "").take(10)))
                    put("comments", JsonPrimitive(n("comments") ?: 0))
                    put("is_pull_request", JsonPrimitive(it.containsKey("pull_request")))
                    put("body", JsonPrimitive((s("body") ?: "").take(800)))
                })
            }
        }
    }

    return listOf(UIMessagePart.Text(
        buildJsonObject {
            put("type", JsonPrimitive(type))
            put("query", JsonPrimitive(query))
            put("total_count", JsonPrimitive(json["total_count"]?.jsonPrimitive?.intOrNull ?: 0))
            put("returned", JsonPrimitive(trimmed.size))
            put("items", trimmed)
            if (!remaining.isNullOrBlank()) put("rate_limit_remaining", JsonPrimitive(remaining))
        }.toString()
    ))
}

// ══════════════════════════════════════════════════════════════
// 工具 3：wikipedia_search
// ══════════════════════════════════════════════════════════════

fun createWikipediaSearchTool(): Tool = Tool(
    name = "wikipedia_search",
    description = """
        Query Wikipedia (no key needed). Two modes:
        - mode="search" (default): full-text search → titles + snippets
        - mode="summary": plain-text extract of one exact article title

        Use it for factual background, definitions and biographies, or to resolve an entity
        before asserting anything. Prefer it over a generic web search for encyclopedic facts.

        Args:
        - query: keywords (mode=search) or exact title (mode=summary)
        - lang: language code, default "zh" (also en, ja, ko, fr, de …)
        - mode: "search" (default) | "summary"
        - limit: 1-20, search mode only (default 5)
        - max_chars: max extract chars in summary mode (default 4000)

        Response (JSON):
        - search:  {lang, mode:"search", query, total, items:[{title, snippet, size, url}], hint}
        - summary: {lang, mode:"summary", title, description, extract, extract_total_chars, url}
    """.trimIndent().replace("\n", " "),
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Search keywords (mode=search) or exact title (mode=summary)")
                })
                put("lang", buildJsonObject {
                    put("type", "string")
                    put("description", "Language code, default zh (en / ja / ko / fr / de …)")
                })
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("description", "search (default) | summary")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "1-20 results for search mode (default 5)")
                })
                put("max_chars", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max extract chars in summary mode (default 4000)")
                })
            },
            required = listOf("query"),
        )
    },
    execute = { args -> runWikipediaSearch(args) },
)

private suspend fun runWikipediaSearch(args: JsonElement): List<UIMessagePart> {
    val obj = args.jsonObject
    val query = obj["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (query.isEmpty()) error("query is required")
    val lang = (obj["lang"]?.jsonPrimitive?.contentOrNull ?: "zh")
        .let { Regex("[A-Za-z\\-]+").find(it)?.value ?: "zh" }
    val mode = (obj["mode"]?.jsonPrimitive?.contentOrNull ?: "search").lowercase()

    if (mode == "summary") {
        val enc = java.net.URLEncoder.encode(query.replace(' ', '_'), "UTF-8")
        val url = "https://$lang.wikipedia.org/api/rest_v1/page/summary/$enc"
        val res = withContext(Dispatchers.IO) {
            httpGet(url, accept = "application/json", timeoutSeconds = 30)
        }
        val bodyText = res.text()
        val json = runCatching { Json.parseToJsonElement(bodyText).jsonObject }.getOrNull()
        if (res.status !in 200..299 || json == null) {
            return listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("error", JsonPrimitive("HTTP ${res.status}"))
                    put("query", JsonPrimitive(query))
                    put("hint", JsonPrimitive(
                        "No article with this exact title in the '$lang' wiki. Try mode=search to find the right title."
                    ))
                }.toString()
            ))
        }
        val maxChars = (obj["max_chars"]?.jsonPrimitive?.intOrNull ?: 4000).coerceIn(200, 20000)
        val extract = json["extract"]?.jsonPrimitive?.contentOrNull ?: ""
        return listOf(UIMessagePart.Text(
            buildJsonObject {
                put("lang", JsonPrimitive(lang))
                put("mode", JsonPrimitive("summary"))
                put("title", JsonPrimitive(json["title"]?.jsonPrimitive?.contentOrNull ?: query))
                put("description", JsonPrimitive(json["description"]?.jsonPrimitive?.contentOrNull ?: ""))
                put("extract", JsonPrimitive(extract.take(maxChars)))
                put("extract_total_chars", JsonPrimitive(extract.length))
                runCatching {
                    json["content_urls"]?.jsonObject?.get("desktop")?.jsonObject
                        ?.get("page")?.jsonPrimitive?.contentOrNull
                }.getOrNull()?.let { put("url", JsonPrimitive(it)) }
            }.toString()
        ))
    }

    val limit = (obj["limit"]?.jsonPrimitive?.intOrNull ?: 5).coerceIn(1, 20)
    val url = "https://$lang.wikipedia.org/w/api.php?action=query&list=search" +
        "&format=json&utf8=1&srlimit=$limit" +
        "&srsearch=" + java.net.URLEncoder.encode(query, "UTF-8")
    val res = withContext(Dispatchers.IO) {
        httpGet(url, accept = "application/json", timeoutSeconds = 30)
    }
    val bodyText = res.text()
    val json = runCatching { Json.parseToJsonElement(bodyText).jsonObject }.getOrNull()
    val arr: JsonArray = runCatching {
        json?.get("query")?.jsonObject?.get("search") as? JsonArray
    }.getOrNull() ?: JsonArray(emptyList())

    val items = buildJsonArray {
        arr.forEach { el ->
            val it = runCatching { el.jsonObject }.getOrNull() ?: return@forEach
            val title = it["title"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            add(buildJsonObject {
                put("title", JsonPrimitive(title))
                put("snippet", JsonPrimitive(
                    (it["snippet"]?.jsonPrimitive?.contentOrNull ?: "")
                        .replace(Regex("<[^>]+>"), "").trim()
                ))
                put("size", JsonPrimitive(it["size"]?.jsonPrimitive?.intOrNull ?: 0))
                put("url", JsonPrimitive(
                    "https://$lang.wikipedia.org/wiki/" +
                        java.net.URLEncoder.encode(title.replace(' ', '_'), "UTF-8")
                ))
            })
        }
    }

    return listOf(UIMessagePart.Text(
        buildJsonObject {
            put("lang", JsonPrimitive(lang))
            put("mode", JsonPrimitive("search"))
            put("query", JsonPrimitive(query))
            put("total", JsonPrimitive(
                runCatching {
                    json?.get("query")?.jsonObject?.get("searchinfo")?.jsonObject
                        ?.get("totalhits")?.jsonPrimitive?.intOrNull
                }.getOrNull() ?: 0
            ))
            put("items", items)
            put("hint", JsonPrimitive(
                "Use mode=summary with an exact title to read the full plain-text extract."
            ))
        }.toString()
    ))
}
