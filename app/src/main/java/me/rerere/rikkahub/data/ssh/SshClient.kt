package me.rerere.rikkahub.data.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.SshAuthType
import me.rerere.rikkahub.data.model.SshCredentials
import me.rerere.rikkahub.data.model.SshHost
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import java.util.Vector

/** 命令执行结果 */
data class SshExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val elapsedMs: Long,
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut
}

/**
 * 基于 JSch 的极简 SSH 客户端。
 *
 * 设计取舍：
 * - **每次调用新建连接、用完即断**（不做连接池）：实现简单、无状态泄漏；
 *   SSH 握手开销对工具调用场景可接受。
 * - 输出读取采用 available() 轮询 + 整体超时，避免 read() 阻塞导致工具永久挂起。
 * - stderr 通过 ChannelExec.setErrStream 重定向到内存缓冲，避免双流死锁。
 */
object SshClient {

    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_CHUNK = 8192

    private fun buildJsch(credentials: SshCredentials): JSch {
        val jsch = JSch()
        if (credentials.authType == SshAuthType.PRIVATE_KEY && credentials.privateKey.isNotBlank()) {
            // 私钥从内存注入，不落盘
            jsch.addIdentity(
                "rikkahub-key",
                credentials.privateKey.toByteArray(Charsets.UTF_8),
                null,
                credentials.passphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8),
            )
        }
        return jsch
    }

    private fun openSession(host: SshHost, credentials: SshCredentials): Session {
        val jsch = buildJsch(credentials)
        val session = jsch.getSession(host.user, host.host, host.port)
        if (credentials.authType == SshAuthType.PASSWORD) {
            session.setPassword(credentials.password)
        }
        val props = Properties().apply {
            // 个人使用场景：跳过 known_hosts 校验（首次连接不会被交互式提示卡住）
            put("StrictHostKeyChecking", "no")
            put("PreferredAuthentications",
                if (credentials.authType == SshAuthType.PASSWORD) "password,keyboard-interactive,publickey"
                else "publickey,password,keyboard-interactive")
        }
        session.setConfig(props)
        session.timeout = CONNECT_TIMEOUT_MS
        session.connect(CONNECT_TIMEOUT_MS)
        return session
    }

    private fun wrap(e: Throwable): String = when (e) {
        is JSchException -> {
            val msg = e.message ?: e.toString()
            "SSH error: " + msg + hintFor(msg)
        }
        else -> "${e.javaClass.simpleName}: ${e.message ?: e.toString()}"
    }

    /** 针对常见失败给出可操作的提示，避免只抛一句 JSch 原文 */
    private fun hintFor(msg: String): String = when {
        msg.contains("invalid privatekey", ignoreCase = true) ->
            "\n\n提示：私钥无法解析。请确认粘贴的是**私钥**（-----BEGIN OPENSSH PRIVATE KEY----- " +
                "或 -----BEGIN RSA PRIVATE KEY----- 开头，含 BEGIN/END 两行），而不是 .pub 公钥；" +
                "也不要改动换行或首尾空格。若为 ssh-ed25519 私钥，需要应用内已内置 BouncyCastle 支持。"
        msg.contains("Auth fail", ignoreCase = true) ->
            "\n\n提示：认证失败。请核对用户名，以及该主机是否已把对应公钥写入 ~/.ssh/authorized_keys。"
        msg.contains("Connection refused", ignoreCase = true) ->
            "\n\n提示：端口无服务。若连本机 Termux，请确认 sshd 正在监听（termux 中执行 `sshd`）且端口正确。"
        msg.contains("timeout", ignoreCase = true) || msg.contains("timed out", ignoreCase = true) ->
            "\n\n提示：连接超时。确认地址与端口可达（同一局域网 / 热点）。"
        else -> ""
    }

    /** 连接测试：返回 null 表示成功，否则返回错误说明 */
    suspend fun test(host: SshHost, credentials: SshCredentials): String? = withContext(Dispatchers.IO) {
        var session: Session? = null
        try {
            session = openSession(host, credentials)
            val uname = runCommand(session, "uname -a 2>/dev/null || echo unknown", 10_000)
            if (uname.timedOut) "timeout" else null
        } catch (e: Throwable) {
            wrap(e)
        } finally {
            runCatching { session?.disconnect() }
        }
    }

    suspend fun exec(
        host: SshHost,
        credentials: SshCredentials,
        command: String,
        timeoutMs: Long,
    ): SshExecResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        var session: Session? = null
        try {
            session = openSession(host, credentials)
            runCommand(session, command, timeoutMs)
        } catch (e: Throwable) {
            SshExecResult(
                exitCode = -1,
                stdout = "",
                stderr = wrap(e),
                timedOut = false,
                elapsedMs = System.currentTimeMillis() - started,
            )
        } finally {
            runCatching { session?.disconnect() }
        }
    }

    private fun runCommand(session: Session, command: String, timeoutMs: Long): SshExecResult {
        val started = System.currentTimeMillis()
        var channel: ChannelExec? = null
        val errBuf = ByteArrayOutputStream()
        val outBuf = ByteArrayOutputStream()
        var timedOut = false
        try {
            channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            channel.setErrStream(errBuf, true)
            val stdout = channel.inputStream
            channel.connect(CONNECT_TIMEOUT_MS)

            val deadline = started + timeoutMs
            val buf = ByteArray(READ_CHUNK)
            while (true) {
                val available = runCatching { stdout.available() }.getOrDefault(0)
                if (available > 0) {
                    val n = stdout.read(buf)
                    if (n < 0) break
                    outBuf.write(buf, 0, n)
                } else {
                    if (channel.isClosed) break
                    if (System.currentTimeMillis() > deadline) {
                        timedOut = true
                        break
                    }
                    Thread.sleep(40)
                }
            }
            val exit = if (channel.isClosed) {
                runCatching { channel.getExitStatus() }.getOrDefault(-1)
            } else {
                -1
            }
            return SshExecResult(
                exitCode = exit,
                stdout = outBuf.toString(Charsets.UTF_8.name()),
                stderr = errBuf.toString(Charsets.UTF_8.name()),
                timedOut = timedOut,
                elapsedMs = System.currentTimeMillis() - started,
            )
        } finally {
            runCatching { channel?.disconnect() }
        }
    }

    /** 上传：本地文件 → 远程路径 */
    suspend fun upload(
        host: SshHost,
        credentials: SshCredentials,
        localPath: String,
        remotePath: String,
    ): String? = withContext(Dispatchers.IO) {
        var session: Session? = null
        var sftp: ChannelSftp? = null
        try {
            val local = File(localPath)
            if (!local.isFile) return@withContext "Local file not found: $localPath"
            session = openSession(host, credentials)
            sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(CONNECT_TIMEOUT_MS)
            FileInputStream(local).use { fis -> sftp.put(fis, remotePath) }
            null
        } catch (e: Throwable) {
            wrap(e)
        } finally {
            runCatching { sftp?.disconnect() }
            runCatching { session?.disconnect() }
        }
    }

    /** 下载：远程路径 → 本地文件 */
    suspend fun download(
        host: SshHost,
        credentials: SshCredentials,
        remotePath: String,
        localPath: String,
    ): String? = withContext(Dispatchers.IO) {
        var session: Session? = null
        var sftp: ChannelSftp? = null
        try {
            session = openSession(host, credentials)
            sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(CONNECT_TIMEOUT_MS)
            val local = File(localPath)
            local.parentFile?.mkdirs()
            FileOutputStream(local).use { fos -> sftp.get(remotePath, fos) }
            null
        } catch (e: Throwable) {
            wrap(e)
        } finally {
            runCatching { sftp?.disconnect() }
            runCatching { session?.disconnect() }
        }
    }

    /** 列出远程目录（SFTP） */
    suspend fun listRemote(
        host: SshHost,
        credentials: SshCredentials,
        remotePath: String,
    ): Pair<String?, List<String>> = withContext(Dispatchers.IO) {
        var session: Session? = null
        var sftp: ChannelSftp? = null
        try {
            session = openSession(host, credentials)
            sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(CONNECT_TIMEOUT_MS)
            val entries = mutableListOf<String>()
            @Suppress("UNCHECKED_CAST")
            val vector = sftp.ls(remotePath) as? Vector<*>
            vector?.forEach { obj ->
                val entry = obj as? ChannelSftp.LsEntry ?: return@forEach
                val attrs = entry.attrs
                entries.add(
                    buildString {
                        append(if (attrs.isDir) "d" else "-")
                        append(" ")
                        append(attrs.permissionsString ?: "")
                        append(" ")
                        append(attrs.size.toString().padStart(10))
                        append(" ")
                        append(entry.filename)
                    }
                )
            }
            null to entries
        } catch (e: Throwable) {
            wrap(e) to emptyList()
        } finally {
            runCatching { sftp?.disconnect() }
            runCatching { session?.disconnect() }
        }
    }

    /** 远程文件信息（是否存在/大小），用于上传下载前校验 */
    suspend fun statRemote(
        host: SshHost,
        credentials: SshCredentials,
        remotePath: String,
    ): Triple<String?, Boolean, Long> = withContext(Dispatchers.IO) {
        var session: Session? = null
        var sftp: ChannelSftp? = null
        try {
            session = openSession(host, credentials)
            sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(CONNECT_TIMEOUT_MS)
            val attrs = sftp.stat(remotePath)
            Triple<String?, Boolean, Long>(null, true, attrs.size)
        } catch (e: Throwable) {
            Triple<String?, Boolean, Long>(wrap(e), false, 0L)
        } finally {
            runCatching { sftp?.disconnect() }
            runCatching { session?.disconnect() }
        }
    }
}
