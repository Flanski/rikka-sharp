package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonElement
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
import me.rerere.rikkahub.data.model.SshHost
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.data.ssh.SshClient

/**
 * SSH 客户端工具集（执行命令 / 上传 / 下载 / 列目录）。
 *
 * **安全设计（刻意为之）**：
 * 工具参数**只接受主机别名**（`host`），不接受 host / port / user / password / private_key
 * 这些散装字段。凭据与主机在 [SshHostRepository] 里一一绑定，因此
 * 「用 A 主机的密钥去连 B 主机」在参数层面就不可能发生。
 * 主机不存在时返回错误并附上可用主机清单，引导模型自我纠正。
 */

private const val MAX_OUTPUT_CHARS = 100_000

private fun hostProperty(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

fun createSshTools(repository: SshHostRepository): List<Tool> = listOf(
    createSshHostsTool(repository),
    createSshExecTool(repository),
    createSshUploadTool(repository),
    createSshDownloadTool(repository),
    createSshLsTool(repository),
)

// ────────────────────────── 公共辅助 ──────────────────────────

/** 主机解析失败时的统一错误（附可用清单，帮助模型纠正） */
private fun unknownHostError(repo: SshHostRepository, ref: String?): String = buildJsonObject {
    put("error", JsonPrimitive("unknown_host"))
    put("requested", JsonPrimitive(ref ?: ""))
    val available = repo.hosts.value
    put("available_hosts", buildJsonArray {
        available.forEach { h -> add(JsonPrimitive(h.name.ifBlank { h.id })) }
    })
    put(
        "hint",
        JsonPrimitive(
            if (available.isEmpty())
                "No SSH host is configured yet. Ask the user to add one in Settings → SSH 客户端."
            else "Call ssh_hosts to list hosts, then retry with an exact name."
        )
    )
}.toString()

private fun credentialsMissingError(host: SshHost): String = buildJsonObject {
    put("error", JsonPrimitive("credentials_missing"))
    put("host", JsonPrimitive(host.name))
    put("auth", JsonPrimitive(host.authType.name.lowercase()))
    put(
        "hint",
        JsonPrimitive(
            "This host has no usable credentials stored. Ask the user to (re)enter the " +
                (if (host.authType.name == "PRIVATE_KEY") "private key" else "password") +
                " in Settings → SSH 客户端."
        )
    )
}.toString()

private fun textResult(json: JsonElement): List<UIMessagePart> =
    listOf(UIMessagePart.Text(json.toString()))

private fun truncate(s: String): String =
    if (s.length <= MAX_OUTPUT_CHARS) s else s.take(MAX_OUTPUT_CHARS) + "\n...[truncated]"

// ────────────────────────── 1. ssh_hosts ──────────────────────────

private fun createSshHostsTool(repo: SshHostRepository): Tool = Tool(
    name = "ssh_hosts",
    description = """
        List the SSH hosts saved on this device, together with their bound credential type.
        Call this FIRST before using ssh_exec / ssh_upload / ssh_download / ssh_ls.

        Credentials are bound to each host, so you never pass a user name, password or private
        key — you only pass the host name from this list. Pick the right host by reading its
        "note" field; if it is unclear which host to use, ask the user instead of guessing.

        Args: none
        Response: {count, hosts:[{name, host, port, user, auth, note, default_cwd, credentials_ready}]}

        Note: auth is "password" or "private_key"; credentials_ready=false means the user must
        fill in the credential before this host can be used.
    """.trimIndent().replace("\n", " "),
    needsApproval = { false },
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = { runSshHosts(repo) },
)

private fun runSshHosts(repo: SshHostRepository): List<UIMessagePart> {
    val hosts = repo.hosts.value
    val payload = buildJsonObject {
        put("count", JsonPrimitive(hosts.size))
        put("hosts", buildJsonArray {
            hosts.forEach { h ->
                add(buildJsonObject {
                    h.toSafeSummary().forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                    put("credentials_ready", JsonPrimitive(repo.hasUsableCredentials(h)))
                })
            }
        })
        if (hosts.isEmpty()) {
            put(
                "hint",
                JsonPrimitive("No hosts configured. Ask the user to add one in Settings → SSH 客户端.")
            )
        } else {
            put(
                "hint",
                JsonPrimitive("Use the exact 'name' value as the `host` argument of ssh_exec / ssh_upload / ssh_download / ssh_ls.")
            )
        }
    }
    return textResult(payload)
}

// ────────────────────────── 2. ssh_exec ──────────────────────────

private fun createSshExecTool(repo: SshHostRepository): Tool = Tool(
    name = "ssh_exec",
    description = """
        Run one shell command on a configured SSH host and return stdout, stderr and the exit code.
        This is the usual way to work on a remote dev server (git, build, docker, ls, cat, grep …).

        Args:
        - host: host name exactly as returned by ssh_hosts (required)
        - command: shell command to run (required)
        - timeout: seconds, default 60, max 600 — raise it for builds or long-running jobs
        - cwd: optional working directory; overrides the host's default_cwd

        Response: {host, command, exit_code, ok, stdout, stderr, timed_out, elapsed_ms}
        stdout/stderr are truncated at 100000 characters. timed_out=true means the command was
        still running when the timeout hit (the remote process may keep running).

        Do NOT send interactive commands (vim, top, ssh into another host, password prompts) —
        they will block until timeout. Prefer non-interactive flags (e.g. `git --no-pager log`).
    """.trimIndent().replace("\n", " "),
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("host", hostProperty("Host name from ssh_hosts (required)"))
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to execute on the remote host")
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put("description", "Seconds, default 60, max 600")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional working directory; overrides the host default_cwd")
                })
            },
            required = listOf("host", "command"),
        )
    },
    execute = { args -> runSshExec(repo, args) },
)

private suspend fun runSshExec(repo: SshHostRepository, args: JsonElement): List<UIMessagePart> {
    val obj = args.jsonObject
    val ref = obj["host"]?.jsonPrimitive?.contentOrNull
    val command = obj["command"]?.jsonPrimitive?.contentOrNull
    if (command.isNullOrBlank()) error("command is required")
    val host = repo.findByRef(ref) ?: return listOf(UIMessagePart.Text(unknownHostError(repo, ref)))
    if (!repo.hasUsableCredentials(host)) return listOf(UIMessagePart.Text(credentialsMissingError(host)))

    val timeoutSec = (obj["timeout"]?.jsonPrimitive?.intOrNull ?: 60).coerceIn(1, 600)
    val cwd = obj["cwd"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: host.defaultCwd.takeIf { it.isNotBlank() }

    val effective = if (cwd.isNullOrBlank()) command else "cd ${shellQuote(cwd)} && $command"
    val credentials = repo.resolveCredentials(host)
    val result = SshClient.exec(host, credentials, effective, timeoutSec * 1000L)

    return textResult(buildJsonObject {
        put("host", JsonPrimitive(host.name))
        put("command", JsonPrimitive(command))
        if (!cwd.isNullOrBlank()) put("cwd", JsonPrimitive(cwd))
        put("exit_code", JsonPrimitive(result.exitCode))
        put("ok", JsonPrimitive(result.ok))
        put("stdout", JsonPrimitive(truncate(result.stdout)))
        put("stderr", JsonPrimitive(truncate(result.stderr)))
        put("timed_out", JsonPrimitive(result.timedOut))
        put("elapsed_ms", JsonPrimitive(result.elapsedMs))
    })
}

// ────────────────────────── 3. ssh_upload ──────────────────────────

private fun createSshUploadTool(repo: SshHostRepository): Tool = Tool(
    name = "ssh_upload",
    description = """
        Upload a local file to a configured SSH host over SFTP.

        Args:
        - host: host name from ssh_hosts (required)
        - local_path: absolute path of the local file (inside the app sandbox / workspace) (required)
        - remote_path: destination path on the remote host (required). If it ends with "/", the
          original file name is appended.

        Response: {host, local_path, remote_path, ok, error?}
        Local files usually live under /workspace (the assistant workspace) — build the artifact
        there first, then upload it.
    """.trimIndent().replace("\n", " "),
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("host", hostProperty("Host name from ssh_hosts (required)"))
                put("local_path", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute local file path")
                })
                put("remote_path", buildJsonObject {
                    put("type", "string")
                    put("description", "Destination path on the remote host")
                })
            },
            required = listOf("host", "local_path", "remote_path"),
        )
    },
    execute = { args -> runSshUpload(repo, args) },
)

private suspend fun runSshUpload(repo: SshHostRepository, args: JsonElement): List<UIMessagePart> {
    val obj = args.jsonObject
    val ref = obj["host"]?.jsonPrimitive?.contentOrNull
    val localPath = obj["local_path"]?.jsonPrimitive?.contentOrNull
    val remotePath = obj["remote_path"]?.jsonPrimitive?.contentOrNull
    if (localPath.isNullOrBlank()) error("local_path is required")
    if (remotePath.isNullOrBlank()) error("remote_path is required")

    val host = repo.findByRef(ref) ?: return listOf(UIMessagePart.Text(unknownHostError(repo, ref)))
    if (!repo.hasUsableCredentials(host)) return listOf(UIMessagePart.Text(credentialsMissingError(host)))

    val dest = if (remotePath.endsWith("/")) remotePath + localPath.substringAfterLast('/') else remotePath
    val err = SshClient.upload(host, repo.resolveCredentials(host), localPath, dest)

    return textResult(buildJsonObject {
        put("host", JsonPrimitive(host.name))
        put("local_path", JsonPrimitive(localPath))
        put("remote_path", JsonPrimitive(dest))
        put("ok", JsonPrimitive(err == null))
        if (err != null) put("error", JsonPrimitive(err))
    })
}

// ────────────────────────── 4. ssh_download ──────────────────────────

private fun createSshDownloadTool(repo: SshHostRepository): Tool = Tool(
    name = "ssh_download",
    description = """
        Download a file from a configured SSH host to the local device over SFTP.

        Args:
        - host: host name from ssh_hosts (required)
        - remote_path: file path on the remote host (required)
        - local_path: absolute local destination path (required); parent folders are created
          automatically

        Response: {host, remote_path, local_path, size_bytes, ok, error?}
        After downloading, read the file with workspace_read_file / file tools.
    """.trimIndent().replace("\n", " "),
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("host", hostProperty("Host name from ssh_hosts (required)"))
                put("remote_path", buildJsonObject {
                    put("type", "string")
                    put("description", "File path on the remote host")
                })
                put("local_path", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute local destination path")
                })
            },
            required = listOf("host", "remote_path", "local_path"),
        )
    },
    execute = { args -> runSshDownload(repo, args) },
)

private suspend fun runSshDownload(repo: SshHostRepository, args: JsonElement): List<UIMessagePart> {
    val obj = args.jsonObject
    val ref = obj["host"]?.jsonPrimitive?.contentOrNull
    val remotePath = obj["remote_path"]?.jsonPrimitive?.contentOrNull
    val localPath = obj["local_path"]?.jsonPrimitive?.contentOrNull
    if (remotePath.isNullOrBlank()) error("remote_path is required")
    if (localPath.isNullOrBlank()) error("local_path is required")

    val host = repo.findByRef(ref) ?: return listOf(UIMessagePart.Text(unknownHostError(repo, ref)))
    if (!repo.hasUsableCredentials(host)) return listOf(UIMessagePart.Text(credentialsMissingError(host)))

    val credentials = repo.resolveCredentials(host)
    val stat = SshClient.statRemote(host, credentials, remotePath)
    val err = SshClient.download(host, credentials, remotePath, localPath)

    return textResult(buildJsonObject {
        put("host", JsonPrimitive(host.name))
        put("remote_path", JsonPrimitive(remotePath))
        put("local_path", JsonPrimitive(localPath))
        put("size_bytes", JsonPrimitive(stat.third))
        put("ok", JsonPrimitive(err == null))
        if (err != null) put("error", JsonPrimitive(err))
    })
}

// ────────────────────────── 5. ssh_ls ──────────────────────────

private fun createSshLsTool(repo: SshHostRepository): Tool = Tool(
    name = "ssh_ls",
    description = """
        List a directory on a configured SSH host over SFTP (safer and faster than `ls -la`
        through ssh_exec when you only need names, sizes and permissions).

        Args:
        - host: host name from ssh_hosts (required)
        - path: remote directory, default "." (optional)

        Response: {host, path, count, entries:["d rwxr-xr-x      4096 dirname", "- rw-r--r--      1234 file.txt"]}
    """.trimIndent().replace("\n", " "),
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("host", hostProperty("Host name from ssh_hosts (required)"))
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Remote directory, default \".\"")
                })
            },
            required = listOf("host"),
        )
    },
    execute = { args -> runSshLs(repo, args) },
)

private suspend fun runSshLs(repo: SshHostRepository, args: JsonElement): List<UIMessagePart> {
    val obj = args.jsonObject
    val ref = obj["host"]?.jsonPrimitive?.contentOrNull
    val path = obj["path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "."

    val host = repo.findByRef(ref) ?: return listOf(UIMessagePart.Text(unknownHostError(repo, ref)))
    if (!repo.hasUsableCredentials(host)) return listOf(UIMessagePart.Text(credentialsMissingError(host)))

    val (err, entries) = SshClient.listRemote(host, repo.resolveCredentials(host), path)

    return textResult(buildJsonObject {
        put("host", JsonPrimitive(host.name))
        put("path", JsonPrimitive(path))
        put("count", JsonPrimitive(entries.size))
        put("entries", buildJsonArray { entries.forEach { add(JsonPrimitive(it)) } })
        if (err != null) put("error", JsonPrimitive(err))
    })
}

// ────────────────────────── 工具函数 ──────────────────────────

private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
