package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.sherpa.ModelCategory
import me.rerere.tts.sherpa.SherpaInstallProgress
import me.rerere.tts.sherpa.SherpaModelInfo
import me.rerere.tts.sherpa.SherpaModelManager
import me.rerere.tts.sherpa.SherpaModelStore
import org.koin.androidx.compose.koinViewModel

/**
 * 本地 TTS 模型仓库。
 *
 * 与设置页里那个内嵌的短列表的区别：
 *  - **全量**：可一键从官方 release 拉取全部 VITS 模型（600+），并缓存到本地
 *  - **可搜索**：按名称过滤；按分类（中文/英文/多语言/已安装）筛选
 *  - **可刷新**：手动刷新清单（未认证接口限流 60 次/小时，故不做自动刷新）
 *  - **可管理**：下载（带进度）/ 删除 / 选为当前使用
 *
 * 只收录 **VITS** 系列：本项目的 TTS provider 目前只配置 `OfflineTtsVitsModelConfig`，
 * Kokoro / Matcha / Kitten 需要不同配置类与文件结构，列出来会让人下载后无法使用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SherpaModelStorePage() {
    val vm: SettingVM = koinViewModel()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val manager = remember { SherpaModelManager(context) }
    val store = remember { SherpaModelStore(context) }

    var query by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf<ModelCategory?>(null) }
    var onlyInstalled by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf(store.currentModels()) }
    var installingId by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<SherpaInstallProgress?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    val installedIds = remember(refreshKey) { manager.listInstalled().map { it.id }.toSet() }
    val provider = settings.getSelectedTTSProvider() as? TTSProviderSetting.SherpaOnnx

    // stringResource 是 @Composable，需在此一次性求值（LazyColumn 的 itemContent 是
    // @Composable 故也可用，但统一在这里取更省心）
    val installedLabel = stringResource(R.string.sherpa_store_installed)
    val downloadLabel = stringResource(R.string.sherpa_store_download)
    val deleteLabel = stringResource(R.string.sherpa_store_delete)
    val useLabel = stringResource(R.string.sherpa_store_use)
    val currentLabel = stringResource(R.string.sherpa_store_current)

    val visible = remember(models, query, categoryFilter, onlyInstalled, installedIds) {
        models.filter { m ->
            if (onlyInstalled && m.id !in installedIds) return@filter false
            if (categoryFilter != null && m.category != categoryFilter) return@filter false
            if (query.isNotBlank()) {
                val q = query.trim().lowercase()
                if (!m.displayName.lowercase().contains(q) && !m.fileName.lowercase().contains(q)) {
                    return@filter false
                }
            }
            true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sherpa_store_title)) },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
                actions = {
                    TextButton(
                        enabled = !refreshing,
                        onClick = {
                            refreshing = true
                            error = null
                            message = null
                            scope.launch {
                                runCatching { store.refresh() }
                                    .onSuccess {
                                        models = it
                                        message = context.getString(R.string.sherpa_store_refreshed, it.size)
                                    }
                                    .onFailure { error = it.message }
                                refreshing = false
                            }
                        }
                    ) {
                        if (refreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.sherpa_store_refresh))
                        }
                    }
                },
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.sherpa_store_search_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )

            // 分类筛选
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = categoryFilter == null && !onlyInstalled,
                    onClick = { categoryFilter = null; onlyInstalled = false },
                    label = { Text(stringResource(R.string.sherpa_store_filter_all)) },
                )
                FilterChip(
                    selected = categoryFilter == ModelCategory.CHINESE,
                    onClick = { categoryFilter = ModelCategory.CHINESE; onlyInstalled = false },
                    label = { Text(ModelCategory.CHINESE.label) },
                )
                FilterChip(
                    selected = onlyInstalled,
                    onClick = { onlyInstalled = !onlyInstalled; if (onlyInstalled) categoryFilter = null },
                    label = { Text(installedLabel) },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.sherpa_store_count, visible.size, models.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(visible, key = { it.id }) { model ->
                    SherpaModelRow(
                        model = model,
                        installed = model.id in installedIds,
                        isCurrent = provider?.modelId == model.id,
                        installing = installingId == model.id,
                        progress = progress,
                        enabled = installingId == null,
                        labels = RowLabels(
                            installed = installedLabel,
                            download = downloadLabel,
                            delete = deleteLabel,
                            use = useLabel,
                            current = currentLabel,
                        ),
                        onDownload = {
                            error = null; message = null
                            installingId = model.id; progress = null
                            scope.launch {
                                // 首次安装后自动选为当前模型并预热，省去两步操作
                                val res = manager.install(model) { progress = it }
                                res.onSuccess {
                                    val p = provider
                                    if (p != null) {
                                        val newList = settings.ttsProviders.map {
                                            if (it.id == p.id) (it as TTSProviderSetting.SherpaOnnx).copy(modelId = model.id) else it
                                        }
                                        vm.updateSettings(settings.copy(ttsProviders = newList))
                                    }
                                    message = context.getString(R.string.sherpa_store_install_done, model.displayName)
                                }.onFailure { error = it.message }
                                installingId = null; progress = null; refreshKey++
                            }
                        },
                        onDelete = {
                            error = null; message = null
                            scope.launch {
                                if (manager.delete(model)) {
                                    val p = provider
                                    if (p != null && p.modelId == model.id) {
                                        val newList = settings.ttsProviders.map {
                                            if (it.id == p.id) (it as TTSProviderSetting.SherpaOnnx).copy(modelId = "") else it
                                        }
                                        vm.updateSettings(settings.copy(ttsProviders = newList))
                                    }
                                } else {
                                    error = "删除失败：${model.displayName}"
                                }
                                refreshKey++
                            }
                        },
                        onUse = useModel@{
                            // 显式 label：同一函数传了多个 lambda，用 @SherpaModelRow 会有歧义
                            val p = provider ?: return@useModel
                            val newList = settings.ttsProviders.map {
                                if (it.id == p.id) (it as TTSProviderSetting.SherpaOnnx).copy(modelId = model.id) else it
                            }
                            vm.updateSettings(settings.copy(ttsProviders = newList))
                            message = context.getString(R.string.sherpa_store_selected, model.displayName)
                        },
                    )
                }
            }
        }
    }
}

private data class RowLabels(
    val installed: String,
    val download: String,
    val delete: String,
    val use: String,
    val current: String,
)

@Composable
private fun SherpaModelRow(
    model: SherpaModelInfo,
    installed: Boolean,
    isCurrent: Boolean,
    installing: Boolean,
    progress: SherpaInstallProgress?,
    enabled: Boolean,
    labels: RowLabels,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onUse: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        CardGroup {
            item(
                headlineContent = {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                supportingContent = {
                    Column {
                        Text(
                            text = buildString {
                                append(model.sizeMb.toInt())
                                append("MB")
                                if (model.speakers > 0) {
                                    append(" · ")
                                    append(model.speakers)
                                    append(" 音色")
                                }
                                append(" · ")
                                append(model.category.label)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (model.description.isNotBlank()) {
                            Text(
                                text = model.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (model.displayName != model.id) {
                            Text(
                                text = model.id,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        if (installing) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                            )
                            Text(
                                text = when (progress) {
                                    is SherpaInstallProgress.Downloading ->
                                        "${(progress.fraction * 100).toInt()}%  ${progress.bytes / 1048576}MB / ${progress.total / 1048576}MB"
                                    is SherpaInstallProgress.Extracting ->
                                        "解压中… ${progress.bytesWritten / 1048576}MB"
                                    else -> "准备中…"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when {
                            installing -> CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            isCurrent -> Text(
                                text = labels.current,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            installed -> {
                                TextButton(onClick = onUse) { Text(labels.use) }
                                TextButton(onClick = onDelete) { Text(labels.delete) }
                            }
                            else -> TextButton(onClick = onDownload, enabled = enabled) {
                                Text(labels.download)
                            }
                        }
                    }
                },
            )
        }
    }
}
