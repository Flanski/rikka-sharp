package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.rikkahub.data.model.SshAuthType
import me.rerere.rikkahub.data.model.SshHost
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.data.ssh.SshClient
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.java.KoinJavaComponent

/**
 * SSH 客户端：管理可供 AI 使用的远程主机。
 *
 * 凭据与主机一一绑定（见 [SshHost] 与 [SshHostRepository]），
 * AI 侧只能通过主机别名引用，避免把一台主机的密钥/口令用到另一台上。
 */
@Composable
fun SettingSshPage() {
    val repository = remember {
        KoinJavaComponent.get<SshHostRepository>(SshHostRepository::class.java)
    }
    val hosts by repository.hosts.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()

    var editorTarget by remember { mutableStateOf<SshHost?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var busyHostId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("SSH 客户端") },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = {
                        editorTarget = null
                        showEditor = true
                    }) {
                        Icon(HugeIcons.Add01, contentDescription = "新增主机")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = padding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "在这里配置远程主机与凭据，AI 即可通过 SSH 执行命令、上传下载文件。" +
                        "凭据（密码/私钥）由系统 Keystore 加密后保存在本机，且与主机一一绑定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (hosts.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "尚未配置主机。点右上角 + 新增，或让 AI 提示你添加。",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            items(hosts, key = { it.id }) { host ->
                SshHostCard(
                    host = host,
                    credentialsReady = repository.hasUsableCredentials(host),
                    busy = busyHostId == host.id,
                    onEdit = {
                        editorTarget = host
                        showEditor = true
                    },
                    onDelete = {
                        repository.delete(host.id)
                        message = "已删除" to host.name
                    },
                    onTest = {
                        busyHostId = host.id
                        scope.launch {
                            val creds = repository.resolveCredentials(host)
                            val err = withContext(Dispatchers.IO) {
                                SshClient.test(host, creds)
                            }
                            busyHostId = null
                            message = if (err == null) {
                                "连接成功" to "${host.user}@${host.host}:${host.port}"
                            } else {
                                "连接失败" to err
                            }
                        }
                    },
                )
            }
        }
    }

    if (showEditor) {
        SshHostEditorDialog(
            origin = editorTarget,
            onDismiss = { showEditor = false },
            onSave = { host, password, privateKey, passphrase ->
                repository.upsert(host, password, privateKey, passphrase)
                showEditor = false
                message = "已保存" to host.name
            },
        )
    }

    message?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = {
                TextButton(onClick = { message = null }) { Text("好") }
            },
            title = { Text(title) },
            text = {
                Text(body, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            },
        )
    }
}

@Composable
private fun SshHostCard(
    host: SshHost,
    credentialsReady: Boolean,
    busy: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    host.name.ifBlank { "(未命名)" },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (!credentialsReady) {
                    Text(
                        "凭据未配置",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                "${host.user}@${host.host}:${host.port}",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "认证：" + if (host.authType == SshAuthType.PRIVATE_KEY) "私钥" else "密码" +
                    if (host.defaultCwd.isNotBlank()) " · 默认目录：${host.defaultCwd}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (host.note.isNotBlank()) {
                Text(
                    host.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onTest, enabled = !busy && credentialsReady) {
                    Text(if (busy) "测试中…" else "测试连接")
                }
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun SshHostEditorDialog(
    origin: SshHost?,
    onDismiss: () -> Unit,
    onSave: (SshHost, String?, String?, String?) -> Unit,
) {
    val base = origin ?: SshHost()
    var name by remember { mutableStateOf(base.name) }
    var host by remember { mutableStateOf(base.host) }
    var port by remember { mutableStateOf(base.port.toString()) }
    var user by remember { mutableStateOf(base.user) }
    var authType by remember { mutableStateOf(base.authType) }
    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var note by remember { mutableStateOf(base.note) }
    var defaultCwd by remember { mutableStateOf(base.defaultCwd) }
    var error by remember { mutableStateOf<String?>(null) }

    val isNew = origin == null
    // 编辑已有主机时，留空表示「保持原凭据不变」
    val credentialHint = if (isNew) "" else "（留空则保持不变）"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新增 SSH 主机" else "编辑 SSH 主机") },
        confirmButton = {
            TextButton(onClick = {
                val portValue = port.trim().toIntOrNull()
                when {
                    name.isBlank() -> error = "请填写别名（AI 用它引用该主机）"
                    host.isBlank() -> error = "请填写主机地址"
                    user.isBlank() -> error = "请填写用户名"
                    portValue == null || portValue !in 1..65535 -> error = "端口无效"
                    isNew && authType == SshAuthType.PASSWORD && password.isEmpty() ->
                        error = "请填写密码"
                    isNew && authType == SshAuthType.PRIVATE_KEY && privateKey.isBlank() ->
                        error = "请粘贴私钥内容"
                    else -> onSave(
                        base.copy(
                            name = name.trim(),
                            host = host.trim(),
                            port = portValue,
                            user = user.trim(),
                            authType = authType,
                            note = note.trim(),
                            defaultCwd = defaultCwd.trim(),
                        ),
                        password.takeIf { it.isNotEmpty() || isNew },
                        privateKey.takeIf { it.isNotEmpty() || isNew },
                        passphrase.takeIf { it.isNotEmpty() || isNew },
                    )
                }
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("别名 (AI 引用用，如 prod-web)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("主机地址 (IP 或域名)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter { c -> c.isDigit() } },
                        label = { Text("端口") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it },
                        label = { Text("用户名") },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { authType = SshAuthType.PASSWORD }) {
                        Text(
                            if (authType == SshAuthType.PASSWORD) "● 密码" else "○ 密码",
                            color = if (authType == SshAuthType.PASSWORD)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { authType = SshAuthType.PRIVATE_KEY }) {
                        Text(
                            if (authType == SshAuthType.PRIVATE_KEY) "● 私钥" else "○ 私钥",
                            color = if (authType == SshAuthType.PRIVATE_KEY)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (authType == SshAuthType.PASSWORD) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码 $credentialHint") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = privateKey,
                        onValueChange = { privateKey = it },
                        label = { Text("私钥内容 (PEM/OpenSSH) $credentialHint") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("私钥口令（可选）$credentialHint") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                OutlinedTextField(
                    value = defaultCwd,
                    onValueChange = { defaultCwd = it },
                    label = { Text("默认工作目录（可选，如 /srv/app）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（写清用途/系统，便于 AI 选对主机）") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )

                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
    )
}
