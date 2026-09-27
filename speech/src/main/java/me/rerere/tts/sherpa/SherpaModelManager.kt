package me.rerere.tts.sherpa

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

private const val TAG = "SherpaModelManager"

/** 模型安装进度 */
sealed interface SherpaInstallProgress {
    /** 下载中；total 为 0 表示服务器未给出长度 */
    data class Downloading(val bytes: Long, val total: Long) : SherpaInstallProgress {
        val fraction: Float get() = if (total > 0) (bytes.toFloat() / total) else 0f
    }

    /** 解压中 */
    data class Extracting(val entriesDone: Int, val bytesWritten: Long) : SherpaInstallProgress

    data class Done(val modelDir: File) : SherpaInstallProgress
}

/**
 * sherpa-onnx 模型的下载 / 解压 / 管理。
 *
 * 为什么需要它：模型包是 `.tar.bz2`，而 **Android 没有内置 bz2 解压**
 * （`java.util.zip` 只有 zip/gzip），因此引入 commons-compress。
 *
 * 为什么要流式：模型包 115MB、解压后约 160MB。把整个包读进内存再解压
 * （项目里技能包的做法）在这里会直接 OOM，所以全程流式处理。
 */
class SherpaModelManager(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // 115MB 下载可能超过默认读超时，放宽到 5 分钟
        .readTimeout(5, TimeUnit.MINUTES)
        .callTimeout(30, TimeUnit.MINUTES)
        .build()

    /** 所有模型的根目录 */
    val rootDir: File get() = File(context.filesDir, ROOT_DIR_NAME)

    private val tmpDir: File get() = File(rootDir, ".tmp")

    /** 某模型解压后的目录 */
    fun modelDir(model: SherpaModelInfo): File = File(rootDir, model.id)

    /**
     * 共用资源目录（tokens.txt + lexicon.txt + 4 个 .fst）。
     *
     * 只有 [ModelPackage.RAW_ONNX] 模型需要它 —— 官方 tar.bz2 包自带这些文件。
     * 之所以共用：所有 `vits-zh-hf-*` 模型的这几个文件**逐字节相同**
     * （同一份 config.json 与同一套 pypinyin 词典生成，已用 md5 逐一验证），
     * 所以不随每个模型重复分发，避免 30 × 2.9MB 的冗余。
     */
    val sharedDir: File get() = File(rootDir, SherpaModelCatalog.SHARED_DIR_NAME)

    fun isInstalled(model: SherpaModelInfo): Boolean {
        val dir = modelDir(model)
        if (!dir.isDirectory) return false
        // 判定「已安装」需要同时满足：有 .onnx，且能取到 tokens.txt
        // （RAW_ONNX 的 tokens 来自共用目录；半途解压的目录两者都缺，不算已装）
        val hasModel = dir.listFiles { f -> f.isFile && f.name.endsWith(".onnx") }?.isNotEmpty() == true
        if (!hasModel) return false
        return File(dir, "tokens.txt").isFile || File(sharedDir, "tokens.txt").isFile
    }

    fun listInstalled(): List<SherpaModelInfo> = SherpaModelCatalog.ALL.filter { isInstalled(it) }

    /** 已安装模型的占用空间（字节） */
    fun installedSize(model: SherpaModelInfo): Long = modelDir(model).walkBottomUp()
        .filter { it.isFile }
        .sumOf { it.length() }

    /** 注意：suspend —— 删除后需要拿 native 锁释放 OfflineTts 实例 */
    suspend fun delete(model: SherpaModelInfo): Boolean {
        val dir = modelDir(model)
        if (!dir.exists()) return true
        return dir.deleteRecursively().also {
            Log.i(TAG, "delete ${model.id}: $it")
            if (it) onModelsChanged()
        }
    }

    /**
     * 下载并安装模型。整个过程在 IO 线程；[onProgress] 会在 IO 线程回调，
     * 调用方若需更新 UI 请自行切回主线程。
     */
    suspend fun install(
        model: SherpaModelInfo,
        onProgress: (SherpaInstallProgress) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        if (isInstalled(model)) {
            onProgress(SherpaInstallProgress.Done(modelDir(model)))
            return@withContext Result.success(modelDir(model))
        }
        when (model.pkg) {
            ModelPackage.RAW_ONNX -> installRawOnnx(model, onProgress)
            ModelPackage.TARBZ2 -> installFromArchive(model, onProgress)
        }
    }

    /** 官方 tar.bz2 包的安装流程：下载 → 解压 → 探测目录 → 落位 */
    private suspend fun installFromArchive(
        model: SherpaModelInfo,
        onProgress: (SherpaInstallProgress) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        val staging = File(tmpDir, model.id)
        try {
            if (isInstalled(model)) {
                onProgress(SherpaInstallProgress.Done(modelDir(model)))
                return@withContext Result.success(modelDir(model))
            }

            // 空间检查：压缩包 + 解压后 + 余量
            rootDir.mkdirs()
            val need = model.sizeBytes + model.extractedBytes + 64L * 1024 * 1024
            val free = rootDir.usableSpace
            if (free in 1 until need) {
                return@withContext Result.failure(
                    IOException("存储空间不足：需要约 ${need / 1048576}MB，可用 ${free / 1048576}MB")
                )
            }

            staging.deleteRecursively()
            staging.mkdirs()
            val archive = File(staging, "model.tar.bz2")

            // ── 1) 下载 ──
            download(model, archive, onProgress)

            // ── 2) 解压到 staging/extracted ──
            val extractTo = File(staging, "extracted").apply { mkdirs() }
            extractTarBz2(archive, extractTo, onProgress)

            // ── 3) 探测模型所在目录（tar 可能带一层顶层目录）──
            val payloadDir = findModelRoot(extractTo)
                ?: return@withContext Result.failure(
                    IOException("解压后未找到 .onnx 模型文件，压缩包可能已损坏")
                )

            // ── 4) 落位 ──
            val finalDir = modelDir(model)
            finalDir.deleteRecursively()
            finalDir.parentFile?.mkdirs()
            if (!payloadDir.renameTo(finalDir)) {
                // 跨文件系统或失败时退化为复制
                payloadDir.copyRecursively(finalDir, overwrite = true)
                payloadDir.deleteRecursively()
            }

            if (!isInstalled(model)) {
                return@withContext Result.failure(IOException("安装校验失败：缺少 .onnx 或 tokens.txt"))
            }

            archive.delete()
            staging.deleteRecursively()
            onModelsChanged()
            Log.i(TAG, "install ${model.id} -> ${finalDir.absolutePath}")
            onProgress(SherpaInstallProgress.Done(finalDir))
            Result.success(finalDir)
        } catch (e: Throwable) {
            Log.e(TAG, "install ${model.id} failed", e)
            runCatching { staging.deleteRecursively() }
            Result.failure(e)
        }
    }

    private fun download(
        model: SherpaModelInfo,
        target: File,
        onProgress: (SherpaInstallProgress) -> Unit,
    ) = downloadUrl(model.url, model.sizeBytes, target, onProgress)

    /**
     * 实际下载逻辑。把 url 抽出来是为了让「共用资源包」也能复用同一套
     * 进度上报与超时处理（它不对应任何 [SherpaModelInfo]）。
     */
    private fun downloadUrl(
        url: String,
        fallbackSize: Long,
        target: File,
        onProgress: (SherpaInstallProgress) -> Unit,
    ) {
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("下载失败：HTTP ${resp.code}")
            }
            val body = resp.body
            val total = body.contentLength().takeIf { it > 0 } ?: fallbackSize
            body.byteStream().use { input ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var written = 0L
                    var lastReported = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        written += n
                        // 每 512KB 上报一次，避免过于频繁地触发重组
                        if (written - lastReported >= 512 * 1024) {
                            lastReported = written
                            onProgress(SherpaInstallProgress.Downloading(written, total))
                        }
                    }
                    out.flush()
                    onProgress(SherpaInstallProgress.Downloading(written, total))
                }
            }
        }
    }

    private fun extractTarBz2(
        archive: File,
        destDir: File,
        onProgress: (SherpaInstallProgress) -> Unit,
    ) {
        var entries = 0
        var bytes = 0L
        val destPath = destDir.canonicalPath

        archive.inputStream().let { raw: InputStream ->
            BufferedInputStream(raw, 256 * 1024).let { buffered ->
                BZip2CompressorInputStream(buffered, true).let { bz ->
                    TarArchiveInputStream(bz).use { tar ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val entry = tar.nextEntry ?: break
                            val name = entry.name
                            // 防路径穿越：拒绝绝对路径与 ..
                            if (name.contains("..") || name.startsWith("/")) {
                                Log.w(TAG, "skip suspicious entry: $name")
                                continue
                            }
                            val outFile = File(destDir, name)
                            if (!outFile.canonicalPath.startsWith(destPath)) {
                                Log.w(TAG, "skip out-of-tree entry: $name")
                                continue
                            }
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                                continue
                            }
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out ->
                                while (true) {
                                    val n = tar.read(buf)
                                    if (n <= 0) break
                                    out.write(buf, 0, n)
                                    bytes += n
                                }
                            }
                            entries++
                            onProgress(SherpaInstallProgress.Extracting(entries, bytes))
                        }
                    }
                }
            }
        }
        Log.i(TAG, "extract done: entries=$entries bytes=$bytes")
    }

    /** 在解压结果里找到包含 .onnx 的目录 */
    /**
     * 裸 onnx 模型的安装流程（上游未发布的 30 个角色）。
     *
     * 与官方 tar.bz2 的差别：
     *  · 只下载单个 .onnx，无需解压
     *  · tokens / lexicon / fst 走**共用资源**（[ensureSharedResources]），不随模型分发
     *
     * 已验证：multi 类型（如卡芙卡 sid=10）与 single 类型（如纳西妲 sid=0）
     * 都能被 sherpa-onnx 加载，并用**中文**正常合成
     * 　（生成时用的是 `zh_ja_mixture_cleaners`，故日语角色模型也能读中文）。
     */
    private suspend fun installRawOnnx(
        model: SherpaModelInfo,
        onProgress: (SherpaInstallProgress) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        val staging = File(tmpDir, model.id)
        try {
            rootDir.mkdirs()
            val need = model.sizeBytes + SHARED_RESERVE_BYTES + 64L * 1024 * 1024
            val free = rootDir.usableSpace
            if (free in 1 until need) {
                return@withContext Result.failure(
                    IOException("存储空间不足：需要约 ${need / 1048576}MB，可用 ${free / 1048576}MB")
                )
            }

            staging.deleteRecursively()
            staging.mkdirs()
            val onnx = File(staging, "${model.id}.onnx")

            // 1) 下载 onnx
            download(model, onnx, onProgress)

            // 2) 确保共用资源（仅首次真正下载，之后直接返回）
            ensureSharedResources(onProgress)

            // 3) 落位
            val finalDir = modelDir(model)
            finalDir.deleteRecursively()
            finalDir.parentFile?.mkdirs()
            finalDir.mkdirs()
            val dest = File(finalDir, onnx.name)
            if (!onnx.renameTo(dest)) {
                onnx.copyTo(dest, overwrite = true)
            }

            if (!isInstalled(model)) {
                return@withContext Result.failure(IOException("安装校验失败：缺少 .onnx 或共用资源"))
            }

            staging.deleteRecursively()
            onModelsChanged()
            Log.i(TAG, "install raw ${model.id} -> ${finalDir.absolutePath}")
            onProgress(SherpaInstallProgress.Done(finalDir))
            Result.success(finalDir)
        } catch (e: Throwable) {
            Log.e(TAG, "install raw ${model.id} failed", e)
            runCatching { staging.deleteRecursively() }
            Result.failure(e)
        }
    }

    /** 确保共用资源就位；已就位则立即返回 */
    private fun ensureSharedResources(onProgress: (SherpaInstallProgress) -> Unit) {
        val dir = sharedDir
        if (File(dir, "tokens.txt").isFile && File(dir, "lexicon.txt").isFile) return

        dir.mkdirs()
        val archive = File(tmpDir, "shared.tar.bz2")
        archive.parentFile?.mkdirs()
        downloadUrl(SherpaModelCatalog.SHARED_ARCHIVE_URL, 1L * 1024 * 1024, archive, onProgress)

        val stage = File(tmpDir, "shared_extract")
        stage.deleteRecursively()
        stage.mkdirs()
        extractTarBz2(archive, stage, onProgress)

        // 共用资源包里没有 .onnx，故不能用 findModelRoot，改按 tokens.txt 定位
        val payload = findDirContaining(stage, "tokens.txt")
            ?: throw IOException("共用资源包解压后未找到 tokens.txt")
        payload.listFiles()?.forEach { f ->
            val dst = File(dir, f.name)
            if (dst.exists()) dst.deleteRecursively()
            if (!f.renameTo(dst)) f.copyRecursively(dst, overwrite = true)
        }
        archive.delete()
        stage.deleteRecursively()
        Log.i(TAG, "shared resources ready: ${dir.absolutePath}")
    }

    /** 在 [root] 及其子目录里找第一个包含指定文件名的目录 */
    private fun findDirContaining(root: File, fileName: String): File? {
        if (File(root, fileName).isFile) return root
        val sub = root.listFiles { f -> f.isDirectory } ?: return null
        sub.firstOrNull { File(it, fileName).isFile }?.let { return it }
        for (d in sub) {
            val deeper = d.listFiles { f -> f.isDirectory } ?: continue
            deeper.firstOrNull { File(it, fileName).isFile }?.let { return it }
        }
        return null
    }

    private fun findModelRoot(root: File): File? {
        if (hasOnnx(root)) return root
        val sub = root.listFiles { f -> f.isDirectory } ?: return null
        sub.firstOrNull { hasOnnx(it) }?.let { return it }
        for (d in sub) {
            val deeper = d.listFiles { f -> f.isDirectory } ?: continue
            deeper.firstOrNull { hasOnnx(it) }?.let { return it }
        }
        return null
    }

    private fun hasOnnx(dir: File): Boolean =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".onnx") }?.isNotEmpty() == true

    companion object {
        const val ROOT_DIR_NAME = "tts_models"

        /** 共用资源（tokens/lexicon/fst）解压后约 2.9MB，留 4MB 余量做空间检查 */
        private const val SHARED_RESERVE_BYTES = 4L * 1024 * 1024

        /**
         * 模型目录变化后让 provider 的 OfflineTts 缓存失效。
         * 由于缓存是进程级的（换模型/删模型后旧实例已无效），这里统一通知。
         */
        private suspend fun onModelsChanged() {
            runCatching { me.rerere.tts.provider.providers.SherpaTtsCache.invalidateAll() }
        }
    }
}
