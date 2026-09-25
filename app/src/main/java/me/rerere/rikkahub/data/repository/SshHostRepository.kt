package me.rerere.rikkahub.data.repository

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.model.SshAuthType
import me.rerere.rikkahub.data.model.SshCredentials
import me.rerere.rikkahub.data.model.SshHost
import me.rerere.rikkahub.data.ssh.SshCrypto
import java.io.File

/**
 * SSH 主机与凭据的本地仓库。
 *
 * - 持久化：`filesDir/ssh_hosts.json`（凭据字段已用 Android Keystore 加密）
 * - 内存中持有 [StateFlow]，供 UI 与工具读取
 * - **凭据与主机一一绑定**：工具侧只能用别名/id 引用主机，[resolveCredentials] 负责取出
 *   该主机自己的凭据，从设计上防止 A 主机的密钥被用到 B 主机。
 */
class SshHostRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val file: File get() = File(context.filesDir, "ssh_hosts.json")

    private val _hosts = MutableStateFlow<List<SshHost>>(emptyList())
    val hosts: StateFlow<List<SshHost>> = _hosts.asStateFlow()

    init {
        reload()
    }

    @Synchronized
    fun reload() {
        _hosts.value = runCatching {
            val f = file
            if (!f.isFile) emptyList()
            else json.decodeFromString(ListSerializer(SshHost.serializer()), f.readText())
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun persist(list: List<SshHost>) {
        runCatching { file.writeText(json.encodeToString(ListSerializer(SshHost.serializer()), list)) }
        _hosts.value = list
    }

    /**
     * 新增或更新一台主机。
     *
     * 明文凭据为 null 时**保持原有密文不变**（用于只修改备注、工作目录等场景）。
     * 传入空串则视为清除该凭据。
     */
    @Synchronized
    fun upsert(
        host: SshHost,
        password: String? = null,
        privateKey: String? = null,
        passphrase: String? = null,
    ): SshHost {
        val merged = host.copy(
            name = host.name.trim(),
            host = host.host.trim(),
            user = host.user.trim(),
            port = if (host.port in 1..65535) host.port else 22,
            passwordEnc = password?.let { SshCrypto.encrypt(it) } ?: host.passwordEnc,
            privateKeyEnc = privateKey?.let { SshCrypto.encrypt(it) } ?: host.privateKeyEnc,
            passphraseEnc = passphrase?.let { SshCrypto.encrypt(it) } ?: host.passphraseEnc,
        )
        val current = _hosts.value.toMutableList()
        val idx = current.indexOfFirst { it.id == merged.id }
        if (idx >= 0) current[idx] = merged else current.add(merged)
        persist(current)
        return merged
    }

    @Synchronized
    fun delete(id: String) {
        persist(_hosts.value.filterNot { it.id == id })
    }

    /**
     * 按引用查找主机：支持 **别名**（name，忽略大小写）或 **id**。
     * 这是工具层唯一的“入口”，不接受 host/user 等散装参数。
     */
    fun findByRef(ref: String?): SshHost? {
        if (ref.isNullOrBlank()) return null
        val key = ref.trim()
        return _hosts.value.firstOrNull { it.id == key }
            ?: _hosts.value.firstOrNull { it.name.equals(key, ignoreCase = true) }
            ?: _hosts.value.firstOrNull { it.name.equals(key.substringBefore('@'), ignoreCase = true) }
    }

    /** 取出某主机绑定的明文凭据（仅内存使用） */
    fun resolveCredentials(host: SshHost): SshCredentials = SshCredentials(
        authType = host.authType,
        password = SshCrypto.decrypt(host.passwordEnc),
        privateKey = SshCrypto.decrypt(host.privateKeyEnc),
        passphrase = SshCrypto.decrypt(host.passphraseEnc),
    )

    /** 某主机的凭据是否已配置完整 */
    fun hasUsableCredentials(host: SshHost): Boolean {
        val creds = resolveCredentials(host)
        return when (creds.authType) {
            SshAuthType.PASSWORD -> creds.password.isNotEmpty()
            SshAuthType.PRIVATE_KEY -> creds.privateKey.isNotEmpty()
        }
    }
}
