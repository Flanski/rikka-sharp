package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "DownloadTools"

/** 公共下载目录（与 FileTools 的默认目录保持一致） */
private const val PUBLIC_DOWNLOAD_DIR = "/storage/emulated/0/Download"

private val httpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        // 不限制整个调用时长：大文件下载可能持续很久，
        // 同步模式的等待由工具自身超时控制，异步模式则完全不受限
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()
}

/**
 * 下载类工具。
 *
 * ── 两种模式（用户要求）──
 * · **同步** `mode="sync"`（默认）：发起后等待，直接把结果返回给模型
 * · **异步** `mode="async"`：立即返回 taskId，模型继续输出；
 *   之后用 `download_status` 查询、`download_cancel` 取消
 *
 * 异步模式的意义：大文件下载（或慢网络）会长时间占住一次工具调用，
 * 而模型在这段时间里本来可以继续做别的事。
 *
 * ── 目标目录 ──
 * 默认写公共 `Download` 目录（与 `FileTools` 一致）；若该目录不可写
 * （未授予「所有文件访问权限」时常见），自动回退到 App 私有目录，
 * 并在返回结果里给出**实际路径** —— 避免模型以为文件在公共目录而去读失败。
 *
 * ── 关于 repository 下载 ──
 * 见 [repoDownloadTool] 的描述：这是**快照下载**（tarball），不是完整 git 克隆。
 */
/** 解压目录（taskId -> 路径）：让状态查询能报告解压结果 */
private val extractedDirHolder = java.util.concurrent.ConcurrentHashMap<String, String>()

fun createDownloadTools(context: Context): List<Tool> = listOf(
    httpDownloadTool(context),
    repoDownloadTool(context),
    downloadStatusTool(),
    downloadCancelTool(),
)

// ────────────────────────── 目标路径解析 ──────────────────────────

/**
 * 解析下载根目录。
 *
 * 优先公共 Download；不可写则回退到 `filesDir/downloads`。
 * 之所以要回退：App 虽在清单里声明了 MANAGE_EXTERNAL_STORAGE，
 * 但那是**特殊权限**，需要用户到系统设置里手动授予，未授予时写入会失败。
 */
private fun resolveRootDir(context: Context, subPath: String?): File {
    val candidates = buildList {
        add(File(PUBLIC_DOWNLOAD_DIR))
        add(File(context.filesDir, "downloads"))
    }
    val base = candidates.firstOrNull { dir ->
        runCatching {
            dir.mkdirs()
            dir.isDirectory && dir.canWrite()
        }.getOrDefault(false)
    } ?: File(context.filesDir, "downloads").apply { mkdirs() }

    return if (subPath.isNullOrBlank()) base
    else File(base, subPath).let { it.parentFile?.mkdirs(); it }
}

/** 从 URL 猜一个安全的文件名 */
private fun fileNameFromUrl(url: String): String {
    val clean = url.substringBefore('?').substringBefore('#')
    val raw = clean.substringAfterLast('/').ifBlank { "download" }
    // 去掉可能的路径穿越字符
    val safe = raw.replace(Regex("[^A-Za-z0-9._\\-]"), "_").take(120)
    return if (safe.contains('.')) safe else "$safe.bin"
}

// ────────────────────────── HTTP 下载 ──────────────────────────

private fun httpDownloadTool(context: Context): Tool = Tool(
    name = "http_download",
    description = """
        Download a file from an HTTP/HTTPS URL to the device.
        Use mode="sync" (default) to wait for completion and get the result,
        or mode="async" to start it in the background and poll later with download_status.
        The file is saved to the public Download folder when writable, otherwise to the app's private directory —
        the returned "path" field is always the real location.
        Note: sync mode is bounded by the tool execution timeout; for large files prefer mode="async".
    """.trimIndent().replace("\n", " "),
    // 下载不涉及危险操作，不需要用户授权（需要授权的是 shell / ssh 写操作 / 传感器）
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "The HTTP/HTTPS URL to download")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional destination path. May be a directory or a full file path, " +
                        "relative to the download root or absolute.")
                })
                put("filename", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional file name to use instead of deriving it from the URL")
                })
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("description", "\"sync\" (wait for result, default) or \"async\" (return task id immediately)")
                })
            },
            required = listOf("url"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val url = obj["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("url is required")
        val mode = obj["mode"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "sync"

        val dest = resolveDestination(
            context = context,
            path = obj["path"]?.jsonPrimitive?.contentOrNull,
            filename = obj["filename"]?.jsonPrimitive?.contentOrNull,
            fallbackName = fileNameFromUrl(url),
        )

        val task = DownloadTaskRegistry.create("http", url, dest.absolutePath)
        val job = DownloadTaskRegistry.scope.launch {
            runHttpDownload(task, url, dest)
        }
        DownloadTaskRegistry.attachJob(task.id, job)

        if (mode == "async") {
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("task_id", task.id)
                    put("state", "running")
                    put("path", dest.absolutePath)
                    put("hint", "Use download_status with this task_id to check progress.")
                }.toString()
            ))
        } else {
            job.join()
            listOf(UIMessagePart.Text(task.toJson().toString()))
        }
    },
)

private suspend fun runHttpDownload(task: DownloadTask, url: String, dest: File) {
    try {
        task.phase = "连接中"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "RikkaSharp")
            .build()

        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code} ${resp.message}")
            }
            val body = resp.body
            task.total = body.contentLength().takeIf { it > 0 } ?: -1L
            task.phase = "下载中"
            task.bytes = 0L

            dest.parentFile?.mkdirs()
            body.byteStream().use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        // 取消检查放在循环里，避免取消后还要读完整个响应
                        if (task.state == DownloadState.CANCELLED) return
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        task.bytes += n
                    }
                    out.flush()
                }
            }
        }
        task.state = DownloadState.DONE
        task.phase = "完成"
        Log.i(TAG, "downloaded ${task.bytes} bytes -> ${dest.absolutePath}")
    } catch (e: CancellationException) {
        task.state = DownloadState.CANCELLED
        task.phase = "已取消"
        throw e
    } catch (e: Throwable) {
        task.state = DownloadState.FAILED
        task.phase = "失败"
        task.error = "${e.javaClass.simpleName}: ${e.message}"
        Log.e(TAG, "download failed: $url", e)
    } finally {
        if (task.finishedAt == null) task.finishedAt = System.currentTimeMillis()
    }
}

/** 解析最终落盘路径 */
private fun resolveDestination(
    context: Context,
    path: String?,
    filename: String?,
    fallbackName: String,
): File {
    val name = filename?.takeIf { it.isNotBlank() } ?: fallbackName

    if (path.isNullOrBlank()) {
        return File(resolveRootDir(context, null), name)
    }

    val p = File(path)
    return when {
        // 绝对路径：直接使用（父目录不存在则创建）
        p.isAbsolute -> {
            val target = if (path.endsWith("/")) File(p, name) else p
            target.parentFile?.mkdirs()
            target
        }
        // 相对路径：以文件名结尾则视为文件，否则视为目录
        path.endsWith("/") || !p.name.contains('.') -> {
            val dir = resolveRootDir(context, path)
            dir.mkdirs()
            File(dir, name)
        }
        else -> resolveRootDir(context, path)
    }
}

// ────────────────────────── 仓库快照下载 ──────────────────────────

/**
 * 把常见的仓库引用解析成「代码快照压缩包」的直链。
 *
 * ★这是一个**快照下载**，不是完整 git 克隆：
 *   拿到的是某个分支当前状态的代码，不含 `.git` 目录，
 *   因此不能 commit / diff 历史 / push。
 *   之所以这样实现：Android 上既没有系统 git，App 里也没有可用的 git 实现，
 *   而 tarball 直链无需任何额外依赖就能拿到代码 —— 覆盖「下载仓库来读/改」的常见需求。
 *   若确实需要完整 git 操作，可以用 SSH 工具连到装有 git 的机器上执行。
 */
private fun repoArchiveUrl(repo: String, branch: String?): Pair<String, String> {
    val cleaned = repo.trim()
        .removePrefix("git@github.com:")
        .removeSuffix(".git")
        .replace(Regex("^https?://"), "")
        .removePrefix("www.")

    val parts = cleaned.split('/').filter { it.isNotBlank() }
    // 允许形如 github.com/owner/repo 或 owner/repo
    val (host, owner, name) = when {
        parts.size >= 3 -> Triple(parts[0].lowercase(), parts[1], parts[2])
        parts.size == 2 -> Triple("github.com", parts[0], parts[1])
        else -> throw IllegalArgumentException(
            "无法解析仓库地址：$repo（支持 owner/repo 或 https://github.com/owner/repo）"
        )
    }

    val ref = branch?.takeIf { it.isNotBlank() } ?: "HEAD"
    val refPath = if (ref == "HEAD") "HEAD" else "refs/heads/$ref"

    return when {
        host.contains("github") ->
            "https://github.com/$owner/$name/archive/$refPath.tar.gz" to "github"
        host.contains("gitee") ->
            "https://gitee.com/$owner/$name/repository/archive/${ref}.tar.gz" to "gitee"
        host.contains("gitlab") ->
            "https://gitlab.com/$owner/$name/-/archive/${ref}/${name}-${ref}.tar.gz" to "gitlab"
        else -> throw IllegalArgumentException("暂不支持的代码托管平台：$host")
    }
}

private fun repoDownloadTool(context: Context): Tool = Tool(
    name = "repo_download",
    description = """
        Download a repository's source code snapshot from GitHub / Gitee / GitLab.
        IMPORTANT: this downloads a tarball snapshot of one branch — there is no .git directory,
        so it is NOT a full git clone (no history, no commit/push). Use it to fetch code for reading or editing.
        If you need real git operations, use the SSH tools against a machine that has git installed.
        Supports owner/repo or a full URL. mode="sync" waits; mode="async" returns a task id for download_status.
    """.trimIndent().replace("\n", " "),
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("repo", buildJsonObject {
                    put("type", "string")
                    put("description", "Repository, e.g. \"torvalds/linux\" or \"https://github.com/torvalds/linux\"")
                })
                put("branch", buildJsonObject {
                    put("type", "string")
                    put("description", "Branch or tag name. Defaults to the repository's default branch (HEAD).")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional destination directory")
                })
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("description", "\"sync\" (default) or \"async\"")
                })
                put("extract", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to extract after download. Default true.")
                })
            },
            required = listOf("repo"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val repo = obj["repo"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("repo is required")
        val branch = obj["branch"]?.jsonPrimitive?.contentOrNull
        val mode = obj["mode"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "sync"
        val extract = obj["extract"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true

        val (url, host) = repoArchiveUrl(repo, branch)
        val name = (repo.substringAfterLast('/').removeSuffix(".git")).ifBlank { "repo" }
        // 未指定 path 时把压缩包放进下载根目录，文件名以仓库命名便于识别
        val userPath = obj["path"]?.jsonPrimitive?.contentOrNull
        val archiveName = "$name-${(branch ?: "HEAD").replace('/', '_')}.tar.gz"
        val dest = if (userPath.isNullOrBlank()) {
            File(resolveRootDir(context, null), archiveName)
        } else {
            resolveDestination(context, userPath, archiveName, archiveName)
        }

        val task = DownloadTaskRegistry.create("repo", url, dest.absolutePath)
        val job = DownloadTaskRegistry.scope.launch {
            runHttpDownload(task, url, dest)
            if (task.state == DownloadState.DONE && extract) {
                task.phase = "解压中"
                runCatching {
                    val outDir = File(dest.parentFile, dest.name.removeSuffix(".tar.gz"))
                    outDir.mkdirs()
                    extractTarGz(dest, outDir)
                    task.phase = "解压完成"
                    task.error = null
                    // 用 destPath 记下解压目录，便于模型直接使用
                    extractedDirHolder[task.id] = outDir.absolutePath
                }.onFailure {
                    task.state = DownloadState.FAILED
                    task.error = "解压失败：${it.message}"
                    task.phase = "解压失败"
                }
            }
        }
        DownloadTaskRegistry.attachJob(task.id, job)

        if (mode == "async") {
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("task_id", task.id)
                    put("state", "running")
                    put("host", host)
                    put("archive", dest.absolutePath)
                    put("hint", "Use download_status to check progress; the extracted directory will appear as \"extracted_to\".")
                }.toString()
            ))
        } else {
            job.join()
            listOf(UIMessagePart.Text(taskResultJson(task).toString()))
        }
    },
)

private fun taskResultJson(task: DownloadTask) = buildJsonObject {
    put("id", task.id)
    put("kind", task.kind)
    put("source", task.source)
    put("state", task.state.name.lowercase())
    put("phase", task.phase)
    put("bytes", task.bytes)
    put("total", task.total)
    put("elapsed_ms", task.elapsedMs)
    put("path", task.destPath)
    extractedDirHolder[task.id]?.let { put("extracted_to", it) }
    task.error?.let { put("error", it) }
}

/** 解压 .tar.gz，带路径穿越防护 */
private fun extractTarGz(archive: File, destDir: File) {
    val destCanonical = destDir.canonicalPath
    archive.inputStream().use { fin ->
        GzipCompressorInputStream(fin).use { gz ->
            TarArchiveInputStream(gz).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    val outFile = File(destDir, entry.name)
                    // 路径穿越防护：确保解压目标仍在 destDir 内
                    if (!outFile.canonicalPath.startsWith(destCanonical)) {
                        throw IOException("压缩包包含非法路径：${entry.name}")
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out -> tar.copyTo(out) }
                    }
                    entry = tar.nextEntry
                }
            }
        }
    }
}

// ────────────────────────── 查询 / 取消 ──────────────────────────

private fun downloadStatusTool(): Tool = Tool(
    name = "download_status",
    description = """
        Check the progress of download tasks started with mode="async".
        Pass a task_id to query one task, or omit it to list all recent tasks.
    """.trimIndent().replace("\n", " "),
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("task_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Task id returned by http_download/repo_download; omit to list all")
                })
            },
        )
    },
    execute = { args ->
        val id = args.jsonObject["task_id"]?.jsonPrimitive?.contentOrNull
        val text = if (!id.isNullOrBlank()) {
            val task = DownloadTaskRegistry.get(id)
            if (task == null) {
                buildJsonObject { put("error", "未找到任务：$id") }.toString()
            } else {
                taskResultJson(task).toString()
            }
        } else {
            buildJsonObject {
                put("tasks", buildJsonArray {
                    DownloadTaskRegistry.list().forEach { add(taskResultJson(it)) }
                })
            }.toString()
        }
        listOf(UIMessagePart.Text(text))
    },
)

private fun downloadCancelTool(): Tool = Tool(
    name = "download_cancel",
    description = "Cancel a running download task by id.",
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("task_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Task id to cancel")
                })
            },
            required = listOf("task_id"),
        )
    },
    execute = { args ->
        val id = args.jsonObject["task_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("task_id is required")
        val ok = DownloadTaskRegistry.cancel(id)
        listOf(UIMessagePart.Text(
            buildJsonObject {
                put("task_id", JsonPrimitive(id))
                put("cancelled", JsonPrimitive(ok))
                if (!ok) put("reason", JsonPrimitive("任务不存在或已结束"))
            }.toString()
        ))
    },
)
