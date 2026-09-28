package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File

/**
 * 文件操作工具 — 统一 file 工具，通过 action 参数选择操作。
 */
fun createFileTools(workspaceDir: String = "/storage/emulated/0/Download"): List<Tool> {
    val defaultDir = workspaceDir

    fun resolveFile(path: String): File {
        val f = File(path)
        if (f.exists() || path.startsWith("/")) return f
        val fallback = File(defaultDir, path).normalize()
        val canonicalDownload = File(defaultDir).canonicalPath
        val canonicalFallback = fallback.canonicalPath
        return if (canonicalFallback.startsWith(canonicalDownload)) fallback
        else File(defaultDir, fallback.name).normalize()
    }

    fun resolveDestPath(path: String): File {
        val f = File(path)
        if (path.startsWith("/")) return f
        val fallback = File(defaultDir, path).normalize()
        val canonicalDownload = File(defaultDir).canonicalPath
        val canonicalFallback = fallback.canonicalPath
        return if (canonicalFallback.startsWith(canonicalDownload)) fallback
        else File(defaultDir, fallback.name).normalize()
    }

    return listOf(
        Tool(
            name = "file",
            description = buildString {
                appendLine("File operations: read, write, patch, list, search, copy, move, mkdir, delete.")
                appendLine()
                appendLine("Use this tool for all file system operations — reading code, writing files, searching content, and managing directories.")
                appendLine()
                appendLine("When to use:")
                appendLine("- Read file contents with optional offset/limit pagination")
                appendLine("- Write or overwrite files with text content")
                appendLine("- Patch files using surgical find-and-replace (old_string → new_string)")
                appendLine("- List directory contents with file sizes")
                appendLine("- Search files by name glob or text/regex content search")
                appendLine("- Copy, move, create directories, or delete files/directories")
                appendLine()
                appendLine("When NOT to use:")
                appendLine("- Shell commands (use execute_command)")
                appendLine()
                appendLine("Args:")
                appendLine("- action: read|write|patch|list|search|copy|move|mkdir|delete")
                appendLine("- path: File path (read, write, patch, mkdir, delete)")
                appendLine("- source/destination: Source and dest paths (copy, move)")
                appendLine("- content: Text content to write (write)")
                appendLine("- old_string/new_string/replace_all: Find-and-replace (patch)")
                appendLine("- offset/limit: Line range for paginated read (read)")
                appendLine("- dir: Directory to list (list, default: ${defaultDir})")
                appendLine("- mode/pattern/root: Search parameters (search)")
                appendLine("- file_pattern/use_regex/context: Advanced search options")
                appendLine()
                appendLine("Absolute paths work as-is. Relative paths resolve to ${defaultDir}.")
            },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                add("read"); add("write"); add("list"); add("search")
                                add("copy"); add("move"); add("mkdir"); add("delete"); add("patch")
                            })
                            put("description", "Operation to perform")
                        })
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "File or directory path. Used by: read, write, list, mkdir, delete")
                        })
                        put("source", buildJsonObject {
                            put("type", "string")
                            put("description", "Source path. Used by: copy, move")
                        })
                        put("destination", buildJsonObject {
                            put("type", "string")
                            put("description", "Destination path. Used by: copy, move")
                        })
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "Text content to write. Used by: write")
                        })
                        put("offset", buildJsonObject {
                            put("type", "integer")
                            put("description", "Starting line (1-indexed). Used by: read (default: 1)")
                        })
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put("description", "Max lines to read. Used by: read (default: 2000)")
                        })
                        put("dir", buildJsonObject {
                            put("type", "string")
                            put("description", "Directory to list. Used by: list (default: ${defaultDir})")
                        })
                        // search params
                        put("mode", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray { add("name"); add("content") })
                            put("description", "Search mode. Used by: search (default: name)")
                        })
                        put("pattern", buildJsonObject {
                            put("type", "string")
                            put("description", "Search pattern (glob for name mode, text/regex for content mode). Used by: search")
                        })
                        put("root", buildJsonObject {
                            put("type", "string")
                            put("description", "Directory to search under. Used by: search")
                        })
                        put("max_results", buildJsonObject {
                            put("type", "integer")
                            put("description", "Max results. Used by: search (default: 20, max: 100)")
                        })
                        put("old_string", buildJsonObject {
                            put("type", "string")
                            put("description", "Exact text to find and replace. Used by: patch. Must be unique in file (set replace_all=true for all occurrences).")
                        })
                        put("new_string", buildJsonObject {
                            put("type", "string")
                            put("description", "Replacement text. Used by: patch (default: empty string = delete matched text)")
                        })
                        put("replace_all", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Replace all occurrences instead of requiring unique match. Used by: patch (default: false)")
                        })
                        put("type_filter", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray { add("all"); add("file"); add("dir") })
                            put("description", "Filter by type. Used by: search mode=name")
                        })
                        put("file_pattern", buildJsonObject {
                            put("type", "string")
                            put("description", "Only search files matching this glob. Used by: search mode=content")
                        })
                        put("use_regex", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Use regex for pattern. Used by: search mode=content")
                        })
                        put("context", buildJsonObject {
                            put("type", "integer")
                            put("description", "Context lines before/after match. Used by: search mode=content (default: 2)")
                        })
                    },
                    required = listOf("action"),
                )
            },
            execute = { args ->
                val obj = args.jsonObject
                val action = obj["action"]?.jsonPrimitive?.contentOrNull ?: error("action required")

                when (action) {
                    "read" -> {
                        val path = obj["path"]?.jsonPrimitive?.content ?: error("path required")
                        val file = resolveFile(path)
                        if (!file.exists()) error("File not found: $path")
                        if (!file.canRead()) error("Cannot read file: $path")
                        if (file.isDirectory) {
                            val listing = file.listFiles()?.map { f ->
                                val icon = if (f.isDirectory) "📁" else "📄"
                                val size = if (f.isFile) " (${formatSize(f.length())})" else ""
                                "$icon ${f.name}$size"
                            }?.joinToString("\n") ?: "(empty)"
                            listOf(UIMessagePart.Text("[${file.absolutePath}] 目录内容:\n$listing"))
                        } else {
                            if (file.length() > 5 * 1024 * 1024) error("文件超过 5MB，为防止内存溢出无法读取: $path")
                            val offset = obj["offset"]?.jsonPrimitive?.intOrNull ?: 1
                            val limit = obj["limit"]?.jsonPrimitive?.intOrNull ?: 2000
                            val lines = file.readLines()
                            val totalLines = lines.size
                            val startIdx = (offset - 1).coerceIn(0, totalLines - 1)
                            val endIdx = (startIdx + limit).coerceAtMost(totalLines)
                            val selected = lines.subList(startIdx, endIdx)
                            val result = buildString {
                                selected.forEachIndexed { idx, line ->
                                    appendLine("${startIdx + idx + 1}|$line")
                                }
                                if (endIdx < totalLines) {
                                    appendLine("... (${totalLines - endIdx} more lines, total $totalLines)")
                                }
                            }
                            listOf(UIMessagePart.Text(result))
                        }
                    }
                    "write" -> {
                        val rawPath = obj["path"]?.jsonPrimitive?.content ?: error("path required")
                        val content = obj["content"]?.jsonPrimitive?.content ?: error("content required")
                        val path = resolveDestPath(rawPath)
                        path.parentFile?.mkdirs()
                        path.writeText(content)
                        listOf(UIMessagePart.Text("OK: wrote ${content.length} bytes to ${path.absolutePath}"))
                    }
                    "list" -> {
                        val dirPath = obj["dir"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: defaultDir
                        val dir = resolveFile(dirPath)
                        if (!dir.exists()) error("Directory not found: $dirPath")
                        if (!dir.isDirectory) error("Not a directory: $dirPath")
                        val entries = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
                        val listing = buildString {
                            appendLine("Contents of ${dir.absolutePath} (${entries.size} items):")
                            appendLine()
                            for (f in entries) {
                                val prefix = if (f.isDirectory) "📁" else "📄"
                                val size = if (f.isFile) " (${formatSize(f.length())})" else ""
                                appendLine("$prefix ${f.name}$size")
                            }
                        }
                        listOf(UIMessagePart.Text(listing))
                    }
                    "copy" -> {
                        val source = obj["source"]?.jsonPrimitive?.content ?: error("source required")
                        val dest = obj["destination"]?.jsonPrimitive?.content ?: error("destination required")
                        val srcFile = resolveFile(source)
                        if (!srcFile.exists()) error("Source not found: $source")
                        val dstFile = resolveDestPath(dest)
                        dstFile.parentFile?.mkdirs()
                        if (srcFile.isDirectory) {
                            srcFile.copyRecursively(dstFile, overwrite = true)
                        } else {
                            srcFile.copyTo(dstFile, overwrite = true)
                        }
                        listOf(UIMessagePart.Text("OK: copied $source → $dest"))
                    }
                    "move" -> {
                        val source = obj["source"]?.jsonPrimitive?.content ?: error("source required")
                        val dest = obj["destination"]?.jsonPrimitive?.content ?: error("destination required")
                        val srcFile = resolveFile(source)
                        if (!srcFile.exists()) error("Source not found: $source")
                        val dstFile = resolveDestPath(dest)
                        dstFile.parentFile?.mkdirs()
                        if (!srcFile.renameTo(dstFile)) {
                            if (srcFile.isDirectory) {
                                srcFile.copyRecursively(dstFile, overwrite = true)
                                srcFile.deleteRecursively()
                            } else {
                                srcFile.copyTo(dstFile, overwrite = true)
                                srcFile.delete()
                            }
                        }
                        listOf(UIMessagePart.Text("OK: moved $source → $dest"))
                    }
                    "mkdir" -> {
                        val path = obj["path"]?.jsonPrimitive?.content ?: error("path required")
                        val dir = resolveDestPath(path)
                        if (dir.exists() && dir.isDirectory) {
                            listOf(UIMessagePart.Text("Directory already exists: $path"))
                        } else {
                            val created = dir.mkdirs()
                            if (created) listOf(UIMessagePart.Text("OK: created directory $path"))
                            else error("Failed to create directory: $path")
                        }
                    }
                    "delete" -> {
                        val path = obj["path"]?.jsonPrimitive?.content ?: error("path required")
                        val file = resolveFile(path)
                        if (!file.exists()) error("File not found: $path")
                        // ★权限不足时给出**可执行的**指引，而不是只丢一句 "no write permission"。
                        //   用户实际反馈过：`file` 删不掉 /storage/emulated/0/... 下的文件，
                        //   但 SSH（Termux）上传到同一路径却成功 ——
                        //   说明不是文件的问题，而是**本应用缺少公共存储的写权限**。
                        //
                        // ★注意：这里**只改错误文案，不动删除流程**。
                        //   我第一版写成了 `if (!file.canWrite() && !file.delete())` —— 那会先试着删一次，
                        //   若删成功，下面的第二次 delete 必然返回 false → 误报 "Failed to delete"。
                        //   （对目录还更糟：`delete()` 只能删空目录。）
                        if (!file.canWrite()) error(explainNoWriteAccess(path))

                        val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
                        if (deleted) {
                            listOf(UIMessagePart.Text("OK: deleted ${if (file.isDirectory) "directory" else "file"} $path"))
                        } else {
                            // 走到这里说明 canWrite() 说能写、实际却删不掉（少见但确实有，
                            // 例如文件被其它进程占用、或权限位与挂载点不一致）。
                            // 错误里带上权限说明，省得用户再猜一轮。
                            error("Failed to delete: $path\n" + explainNoWriteAccess(path))
                        }
                    }
                    "patch" -> {
                        val path = obj["path"]?.jsonPrimitive?.content ?: error("path required")
                        val oldText = obj["old_string"]?.jsonPrimitive?.content ?: error("old_string required")
                        val newText = obj["new_string"]?.jsonPrimitive?.content ?: ""
                        val replaceAll = obj["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                        val file = resolveFile(path)
                        if (!file.exists()) error("File not found: $path")
                        val content = file.readText()

                        // 1. Exact match
                        val count = content.windowedSequence(oldText.length).count { it == oldText }
                        if (count > 0) {
                            if (count > 1 && !replaceAll) error("Found $count matches — set replace_all=true or make old_string more specific")
                            val updated = if (replaceAll) content.replace(oldText, newText)
                            else content.replaceFirst(oldText, newText)
                            file.writeText(updated)
                            listOf(UIMessagePart.Text("Patched $path: $count replacement(s)"))
                        } else {
                            // 2. Fuzzy: line-by-line trimmed matching
                            val contentLines = content.lines()
                            val oldLines = oldText.lines().map { it.trim() }
                            val newLines = newText.lines()
                            val fCount = (0..contentLines.size - oldLines.size).count { i ->
                                contentLines.subList(i, i + oldLines.size).map { it.trim() } == oldLines
                            }
                            if (fCount == 0) error("old_string not found in $path (checked exact and whitespace-normalized)")
                            if (fCount > 1 && !replaceAll) error("Found $fCount fuzzy matches — set replace_all=true or make old_string more specific")

                            // Apply replacement by finding first (or all) matching line window
                            val resultLines = mutableListOf<String>()
                            var cursor = 0
                            var matchCount = 0
                            while (cursor <= contentLines.size - oldLines.size) {
                                val window = contentLines.subList(cursor, cursor + oldLines.size)
                                if (window.map { it.trim() } == oldLines) {
                                    resultLines.addAll(newLines)
                                    cursor += oldLines.size
                                    matchCount++
                                    if (!replaceAll) {
                                        resultLines.addAll(contentLines.drop(cursor))
                                        cursor = contentLines.size
                                        break
                                    }
                                } else {
                                    resultLines.add(contentLines[cursor])
                                    cursor++
                                }
                            }
                            if (cursor < contentLines.size) {
                                resultLines.addAll(contentLines.drop(cursor))
                            }
                            file.writeText(resultLines.joinToString("\n"))
                            val resultText = if (replaceAll) "Patched $path: $fCount fuzzy replacements"
                            else "Patched $path: 1 fuzzy replacement"
                            listOf(UIMessagePart.Text(resultText))
                        }
                    }
                    "search" -> {
                        val mode = obj["mode"]?.jsonPrimitive?.contentOrNull ?: "name"
                        val pattern = obj["pattern"]?.jsonPrimitive?.contentOrNull ?: error("pattern required")
                        val root = obj["root"]?.jsonPrimitive?.contentOrNull ?: defaultDir
                        val maxResults = (obj["max_results"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 100)
                        val rootDir = File(root)
                        if (!rootDir.exists()) error("Directory not found: $root")
                        if (!rootDir.isDirectory) error("Not a directory: $root")

                        if (mode == "name") {
                            val typeFilter = obj["type_filter"]?.jsonPrimitive?.contentOrNull ?: "all"
                            val regex = pattern.toGlobRegex()
                            val results = mutableListOf<String>()
                            rootDir.walkTopDown().forEach { f ->
                                if (results.size >= maxResults) return@forEach
                                if (typeFilter == "file" && f.isDirectory) return@forEach
                                if (typeFilter == "dir" && f.isFile) return@forEach
                                if (regex.matches(f.name)) {
                                    val icon = if (f.isDirectory) "📁" else "📄"
                                    val size = if (f.isFile) " (${formatSize(f.length())})" else ""
                                    results.add("$icon ${f.absolutePath}$size")
                                }
                            }
                            if (results.isEmpty()) {
                                listOf(UIMessagePart.Text("No files matching '$pattern' found under $root"))
                            } else {
                                listOf(UIMessagePart.Text("Found ${results.size} result(s) for '$pattern':\n${results.joinToString("\n")}"))
                            }
                        } else {
                            val fileGlob = obj["file_pattern"]?.jsonPrimitive?.contentOrNull ?: ""
                            val useRegex = obj["use_regex"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                            val contextLines = (obj["context"]?.jsonPrimitive?.intOrNull ?: 2).coerceIn(0, 10)
                            val cMaxResults = maxResults.coerceAtMost(200)
                            val searchRegex = if (useRegex) {
                                try { Regex(pattern, setOf(RegexOption.IGNORE_CASE)) }
                                catch (e: Exception) { error("Invalid regex: ${e.message}") }
                            } else {
                                Regex(Regex.escape(pattern), RegexOption.IGNORE_CASE)
                            }
                            val fileFilter = if (fileGlob.isNotBlank()) fileGlob.toGlobRegexForFile() else null
                            val results = mutableListOf<String>()
                            val sb = StringBuilder()
                            try {
                                rootDir.walkTopDown()
                                    .filter { it.isFile && it.length() > 0 && it.length() <= 512_000 }
                                    .filter { f -> fileFilter?.matches(f.name) ?: true }
                                    .forEach { file ->
                                        if (results.size >= cMaxResults) return@forEach
                                        try {
                                            val lines = file.readLines()
                                            var matchCount = 0
                                            lines.forEachIndexed { lineNum, line ->
                                                if (matchCount >= 5 || results.size >= cMaxResults) return@forEachIndexed
                                                if (searchRegex.containsMatchIn(line)) {
                                                    matchCount++
                                                    results.add("${file.absolutePath}:${lineNum + 1}")
                                                    for (c in (lineNum - contextLines).coerceAtLeast(0) until lineNum) {
                                                        sb.appendLine("  ${c + 1}: ${lines[c].take(120)}")
                                                    }
                                                    sb.appendLine("→ ${lineNum + 1}: ${line.take(120)}")
                                                    for (c in (lineNum + 1)..(lineNum + contextLines).coerceAtMost(lines.size - 1)) {
                                                        sb.appendLine("  ${c + 1}: ${lines[c].take(120)}")
                                                    }
                                                    sb.appendLine()
                                                }
                                            }
                                        } catch (_: Exception) { }
                                    }
                            } catch (_: Exception) { }
                            if (results.isEmpty()) {
                                listOf(UIMessagePart.Text("No matches found for '$pattern' under $root${if (fileGlob.isNotBlank()) " in files matching '$fileGlob'" else ""}"))
                            } else {
                                listOf(UIMessagePart.Text("Found ${results.size} match(es) for '$pattern'${if (fileGlob.isNotBlank()) " in $fileGlob files" else ""}:\n${sb.toString().take(15000)}"))
                            }
                        }
                    }
                    else -> error("Unknown action: $action")
                }
            },
        ),
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}

private fun String.toGlobRegex(): Regex {
    val pattern = Regex.escape(this)
        .replace("\\*", ".*")
        .replace("\\?", ".")
    return Regex("^${pattern}$", RegexOption.IGNORE_CASE)
}

private fun String.toGlobRegexForFile(): Regex {
    val pattern = Regex.escape(this)
        .replace("\\*", ".*")
        .replace("\\?", ".")
    return Regex("^${pattern}$", RegexOption.IGNORE_CASE)
}

/**
 * 写权限不足时的**可执行**说明。
 *
 * ── 为什么需要它 ──
 * 原来的错误只有一句 `Cannot delete (no write permission): <path>`。
 * 用户看到后无法判断：
 *  · 是文件本身有问题？还是应用的权限问题？
 *  · 该去哪里解决？
 *
 * 实测反馈印证了这个困惑：同一个路径 `file` 工具删不掉，而 **SSH（Termux）上传却成功** ——
 * 说明文件没问题，是**本应用**缺少公共存储的写权限。这类情况必须说清楚。
 *
 * ── 权限模型（Android 11 起变了）──
 *  · Android 11+：读改 `/storage/emulated/0` 下的任意文件需要
 *    **MANAGE_EXTERNAL_STORAGE**（"所有文件访问"）。它是**特殊权限**，
 *    只能在系统设置里手动开启，运行时权限对话框里没有它。
 *  · Android 10 及以下：走传统的 `WRITE_EXTERNAL_STORAGE` 运行时权限。
 *
 * 应用私有目录（`filesDir` 等）**不受**这些限制，永远可写 ——
 * 所以提示里也给出「改用私有目录」这条退路。
 */
private fun explainNoWriteAccess(path: String): String {
    val allFilesAccess = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)
    } else {
        // API 30 以下没有「所有文件访问」这个概念，走的是传统存储权限；
        // 这里不冒充知道结果，按"未知"处理（措辞上不把话说死）。
        false
    }

    val suffix = if (allFilesAccess) {
        "本应用**已**获得「所有文件访问」权限，因此更可能是文件系统本身的限制" +
            "（例如该路径由其它应用创建、或位于不可写的挂载上）。"
    } else {
        "本应用**没有**「所有文件访问」权限。请到「系统设置 → 应用 → 本应用 → 权限」中开启" +
            "「所有文件访问」/「管理所有文件」；Android 10 及以下请开启「存储」权限。"
    }

    return buildString {
        append("无法写入：$path\n")
        append(suffix)
        append("\n提示：应用私有目录（如 filesDir 下的路径）不受此限制，可直接读写；")
        append("也可以改用 SSH 工具操作公共目录（它不受应用的存储权限约束）。")
    }
}
