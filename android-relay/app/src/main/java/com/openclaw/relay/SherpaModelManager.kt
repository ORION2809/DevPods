package com.openclaw.relay

import com.openclaw.relay.sherpa.SherpaModelInspector
import com.openclaw.relay.sherpa.SherpaModelSpec
import com.openclaw.relay.sherpa.SherpaModelStatus
import com.openclaw.relay.sherpa.SherpaModelType
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

private const val SHERPA_INSTALL_METADATA_FILE = ".install-metadata.properties"
private const val SHERPA_TEMP_DIR_NAME = ".tmp"

interface SherpaModelDownloadTransport {
    fun contentLength(url: URL): Long
    fun download(url: URL, dest: File, onChunk: (bytes: Long) -> Unit)
}

object HttpSherpaModelDownloadTransport : SherpaModelDownloadTransport {
    override fun contentLength(url: URL): Long {
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "HEAD"
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        return try {
            conn.contentLengthLong
        } finally {
            conn.disconnect()
        }
    }

    override fun download(url: URL, dest: File, onChunk: (bytes: Long) -> Unit) {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Accept", "application/octet-stream")
        conn.instanceFollowRedirects = true

        try {
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        onChunk(read.toLong())
                    }
                    output.flush()
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Downloads, caches, and verifies Sherpa-ONNX models.
 *
 * Keeps model risk isolated from the main app by managing the model directory
 * independently and validating checksums before marking a model ready.
 *
 * Supports both the legacy [OfflineSpeechModelSpec] and the new
 * type-aware [SherpaModelSpec] with per-file checksums.
 */
class SherpaModelManager(
    private val modelCacheDir: File,
    private val downloadTransport: SherpaModelDownloadTransport = HttpSherpaModelDownloadTransport,
    private val usableSpaceProvider: (File) -> Long = { file -> file.usableSpace },
    private val tempDirNameProvider: () -> String = { "install-${System.currentTimeMillis()}" },
) {
    init {
        if (!modelCacheDir.exists()) {
            modelCacheDir.mkdirs()
        }
    }

    /**
     * Checks whether a legacy model spec is satisfied by the current cache.
     */
    fun status(spec: OfflineSpeechModelSpec): OfflineSpeechModelStatus {
        return OfflineSpeechModelManager.inspect(spec)
    }

    /**
     * Checks whether a type-aware Sherpa model spec is satisfied by the current cache.
     */
    fun status(spec: SherpaModelSpec): SherpaModelStatus {
        return SherpaModelInspector.inspect(spec)
    }

    /**
     * Downloads missing required files for a model spec.
     *
     * @param spec Model specification including required files and expected checksum.
     * @param baseUrl Base URL where model files are hosted (e.g., "https://example.com/models/v1").
     * @param onProgress Optional progress callback: (downloadedBytes, totalBytes) -> Unit.
     * @return Result containing the verified model status, or an exception on failure.
     */
    fun download(
        spec: OfflineSpeechModelSpec,
        baseUrl: String,
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null,
    ): Result<OfflineSpeechModelStatus> {
        return try {
            val root = File(spec.modelRootPath)
            if (!root.exists()) {
                root.mkdirs()
            }

            val existingStatus = OfflineSpeechModelManager.inspect(spec)
            if (existingStatus.isComplete && existingStatus.checksumMatches) {
                return Result.success(existingStatus)
            }

            val filesToDownload = existingStatus.missingFiles.ifEmpty {
                // If nothing is missing but checksum mismatched, re-download everything
                spec.requiredFiles
            }

            var totalDownloaded = 0L
            var totalExpected = 0L

            // Pre-flight HEAD requests to compute total size
            for (fileName in filesToDownload) {
                val url = URL("$baseUrl/${fileName}")
                val length = try {
                    downloadTransport.contentLength(url)
                } catch (error: Exception) {
                    return Result.failure(
                        IllegalStateException(
                            "Failed to inspect model file '$fileName' from $url: ${error.message}",
                            error,
                        ),
                    )
                }
                if (length > 0) {
                    totalExpected += length
                }
            }

            for (fileName in filesToDownload) {
                val url = URL("$baseUrl/${fileName}")
                val dest = File(root, fileName)
                try {
                    downloadFile(url, dest) { bytes ->
                        totalDownloaded += bytes
                        onProgress?.invoke(totalDownloaded, totalExpected)
                    }
                } catch (error: Exception) {
                    return Result.failure(
                        IllegalStateException(
                            "Failed to download model file '$fileName' from $url: ${error.message}",
                            error,
                        ),
                    )
                }
            }

            val finalStatus = OfflineSpeechModelManager.inspect(spec)
            if (!finalStatus.isComplete) {
                return Result.failure(
                    IllegalStateException("Model download incomplete: ${finalStatus.missingFiles.joinToString(", ")}"),
                )
            }
            if (!finalStatus.checksumMatches) {
                return Result.failure(
                    IllegalStateException("Model checksum mismatch after download. Expected ${spec.expectedSha256}, got ${finalStatus.actualSha256}"),
                )
            }

            Result.success(finalStatus)
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    /**
     * Downloads missing required files for a type-aware Sherpa model spec.
     *
     * @param spec Type-aware model specification with per-file checksums.
     * @param onProgress Optional progress callback: (downloadedBytes, totalBytes) -> Unit.
     * @return Result containing the verified model status, or an exception on failure.
     */
    fun download(
        spec: SherpaModelSpec,
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null,
    ): Result<SherpaModelStatus> {
        if (spec.requiredFiles.any { spec.resolveDownloadUrl(it).isNullOrBlank() }) {
            return Result.failure(
                IllegalArgumentException("No download URL configured for one or more files in model ${spec.id}"),
            )
        }

        val tempDirName = tempDirNameProvider()

        return try {
            val existingStatus = SherpaModelInspector.inspect(spec)
            if (existingStatus.isReady) {
                return Result.success(existingStatus)
            }

            val totalExpected = resolveTotalExpectedBytes(spec)
            val availableBytes = usableSpaceProvider(modelCacheDir)
            if (totalExpected > 0 && availableBytes in 0 until totalExpected) {
                return Result.failure(
                    IllegalStateException(
                        "Low storage for model ${spec.id}: requires $totalExpected bytes, only $availableBytes bytes available.",
                    ),
                )
            }

            val tempInstallRoot = File(File(modelCacheDir, SHERPA_TEMP_DIR_NAME), tempDirName)
            val tempModelRoot = File(tempInstallRoot, spec.id)
            tempModelRoot.mkdirs()

            var totalDownloaded = 0L

            spec.requiredFiles.forEach { fileName ->
                val url = URL(requireNotNull(spec.resolveDownloadUrl(fileName)))
                val dest = File(tempModelRoot, fileName)
                downloadTransport.download(url, dest) { bytes ->
                    totalDownloaded += bytes
                    onProgress?.invoke(totalDownloaded, totalExpected)
                }

                val expectedFileChecksum = spec.expectedSha256ByFile[fileName]
                if (expectedFileChecksum != null) {
                    val actualChecksum = hashFile(dest)
                    if (!actualChecksum.equals(expectedFileChecksum, ignoreCase = true)) {
                        throw IllegalStateException("Model checksum mismatch for $fileName")
                    }
                }
            }

            writeInstallMetadata(tempModelRoot, spec)

            val tempSpec = spec.copy(installRoot = tempInstallRoot.absolutePath)
            val finalStatus = SherpaModelInspector.inspect(tempSpec)
            if (!finalStatus.isComplete) {
                throw IllegalStateException("Model download incomplete: ${finalStatus.missingFiles.joinToString(", ")}")
            }
            if (!finalStatus.checksumMatches && spec.expectedSha256ByFile.isNotEmpty()) {
                throw IllegalStateException("Model checksum mismatch after download.")
            }

            replaceInstalledModel(tempModelRoot, File(spec.modelRootPath))
            deleteRecursivelyIfExists(tempInstallRoot)

            Result.success(SherpaModelInspector.inspect(spec))
        } catch (error: Exception) {
            deleteRecursivelyIfExists(File(File(modelCacheDir, SHERPA_TEMP_DIR_NAME), tempDirName))
            Result.failure(error)
        }
    }

    /**
     * Removes all cached models to free storage.
     */
    fun clearCache(): Boolean {
        return modelCacheDir.deleteRecursively()
    }

    /**
     * Lists installed model versions by scanning subdirectories.
     */
    fun installedModels(type: SherpaModelType? = null): List<InstalledSherpaModel> {
        if (!modelCacheDir.isDirectory) return emptyList()
        return modelCacheDir.listFiles()
            ?.filter { it.isDirectory && it.name != SHERPA_TEMP_DIR_NAME }
            ?.map { dir ->
                val metadata = readInstallMetadata(dir)
                val versionFile = File(dir, "version.txt")
                val version = metadata?.version ?: versionFile.takeIf { it.isFile }?.readText()?.trim() ?: "unknown"
                val totalBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                InstalledSherpaModel(
                    path = dir.absolutePath,
                    version = version,
                    totalBytes = totalBytes,
                    modelId = metadata?.modelId ?: dir.name,
                    modelType = metadata?.modelType,
                )
            }
            ?.filter { installed -> type == null || installed.modelType == type }
            ?: emptyList()
    }

    private fun resolveTotalExpectedBytes(spec: SherpaModelSpec): Long {
        if (spec.totalBytes > 0) {
            return spec.totalBytes
        }

        return spec.requiredFiles.sumOf { fileName ->
            val url = URL(requireNotNull(spec.resolveDownloadUrl(fileName)))
            downloadTransport.contentLength(url).coerceAtLeast(0L)
        }
    }

    private fun downloadFile(
        url: URL,
        dest: File,
        onChunk: (bytes: Long) -> Unit,
    ) {
        dest.parentFile?.mkdirs()
        downloadTransport.download(url, dest, onChunk)
    }

    private fun replaceInstalledModel(tempModelRoot: File, finalModelRoot: File) {
        finalModelRoot.parentFile?.mkdirs()

        val backupRoot = if (finalModelRoot.exists()) {
            File(File(modelCacheDir, SHERPA_TEMP_DIR_NAME), "backup-${finalModelRoot.name}-${System.nanoTime()}")
        } else {
            null
        }

        try {
            if (backupRoot != null) {
                movePath(finalModelRoot, backupRoot)
            }

            movePath(tempModelRoot, finalModelRoot)
            deleteRecursivelyIfExists(backupRoot)
        } catch (error: Exception) {
            if (finalModelRoot.exists()) {
                deleteRecursivelyIfExists(finalModelRoot)
            }
            if (backupRoot != null && backupRoot.exists()) {
                try {
                    movePath(backupRoot, finalModelRoot)
                } catch (restoreError: Exception) {
                    throw IllegalStateException(
                        "Failed to restore model from backup at ${backupRoot.absolutePath}: ${restoreError.message}",
                        restoreError,
                    )
                }
            }
            throw error
        }
    }

    private fun movePath(source: File, target: File) {
        target.parentFile?.mkdirs()
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun writeInstallMetadata(modelRoot: File, spec: SherpaModelSpec) {
        File(modelRoot, "version.txt").writeText(spec.version)

        val properties = Properties().apply {
            setProperty("modelId", spec.id)
            setProperty("modelType", spec.type.name)
            setProperty("version", spec.version)
        }
        File(modelRoot, SHERPA_INSTALL_METADATA_FILE).outputStream().use { output ->
            properties.store(output, null)
        }
    }

    private fun readInstallMetadata(modelRoot: File): InstalledSherpaModelMetadata? {
        val metadataFile = File(modelRoot, SHERPA_INSTALL_METADATA_FILE)
        if (!metadataFile.isFile) {
            return null
        }

        val properties = Properties()
        metadataFile.inputStream().use { input ->
            properties.load(input)
        }

        val modelId = properties.getProperty("modelId") ?: return null
        val modelType = properties.getProperty("modelType")
            ?.let { value -> runCatching { SherpaModelType.valueOf(value) }.getOrNull() }
        val version = properties.getProperty("version") ?: "unknown"
        return InstalledSherpaModelMetadata(
            modelId = modelId,
            modelType = modelType,
            version = version,
        )
    }

    private fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            updateDigestFromStream(digest, input)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun updateDigestFromStream(digest: MessageDigest, input: InputStream) {
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) {
                return
            }
            digest.update(buffer, 0, read)
        }
    }

    private fun deleteRecursivelyIfExists(target: File?) {
        if (target != null && target.exists()) {
            target.deleteRecursively()
        }
    }
}

data class InstalledSherpaModel(
    val path: String,
    val version: String,
    val totalBytes: Long,
    val modelId: String? = null,
    val modelType: SherpaModelType? = null,
)

private data class InstalledSherpaModelMetadata(
    val modelId: String,
    val modelType: SherpaModelType?,
    val version: String,
)
