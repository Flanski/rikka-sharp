package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * SSH 主机的认证方式。
 *
 * 设计原则（重要）：**凭据与主机一一绑定**。
 * AI 侧只允许用「主机别名」引用主机，绝不接受 host / user / 密码 / 私钥 分开传入，
 * 从根上避免把 A 主机的密钥或用户名密码用到 B 主机上。
 */
@Serializable
enum class SshAuthType {
    /** 用户名 + 密码 */
    PASSWORD,

    /** 用户名 + 私钥（PEM / OpenSSH 格式），可带口令 */
    PRIVATE_KEY,
}

/**
 * 一台 SSH 主机及其绑定的凭据。
 *
 * 敏感字段（[passwordEnc] / [privateKeyEnc] / [passphraseEnc]）在持久化时是
 * **Android Keystore AES/GCM 加密后的 Base64 串**，明文只在内存中短暂存在，
 * 由 [me.rerere.rikkahub.data.repository.SshHostRepository] 负责加解密。
 */
@Serializable
data class SshHost(
    val id: String = Uuid.random().toString(),

    /** 别名：AI 通过它引用主机（如 "prod-web"、"home-nas"）。同一设备内应唯一。 */
    val name: String = "",

    val host: String = "",

    val port: Int = 22,

    val user: String = "",

    val authType: SshAuthType = SshAuthType.PASSWORD,

    /** 加密后的密码（authType = PASSWORD） */
    val passwordEnc: String = "",

    /** 加密后的私钥内容（authType = PRIVATE_KEY，PEM / OpenSSH 文本） */
    val privateKeyEnc: String = "",

    /** 加密后的私钥口令（可选） */
    val passphraseEnc: String = "",

    /** 备注：用途、系统、机房等，供 AI 判断该连哪台 */
    val note: String = "",

    /** 该主机上的默认工作目录（执行命令时作为 cwd，可为空） */
    val defaultCwd: String = "",
) {
    /** 供 AI 与 UI 展示的安全摘要（绝不含密码/私钥） */
    fun toSafeSummary(): Map<String, String> = mapOf(
        "name" to name,
        "host" to host,
        "port" to port.toString(),
        "user" to user,
        "auth" to if (authType == SshAuthType.PRIVATE_KEY) "private_key" else "password",
        "note" to note,
        "default_cwd" to defaultCwd,
    )
}

/** 运行时凭据（仅内存，不序列化） */
data class SshCredentials(
    val authType: SshAuthType,
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
)
