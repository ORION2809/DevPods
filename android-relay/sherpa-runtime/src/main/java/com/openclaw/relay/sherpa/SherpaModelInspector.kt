package com.openclaw.relay.sherpa

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

data class SherpaModelStatus(
    val modelId: String,
    val modelType: SherpaModelType,
    val modelVersion: String,
    val requiredFiles: List<String>,
    val missingFiles: List<String>,
    val totalBytes: Long,
    val expectedSha256ByFile: Map<String, String> = emptyMap(),
    val actualSha256ByFile: Map<String, String> = emptyMap(),
    val expectedCombinedSha256: String? = null,
    val actualCombinedSha256: String? = null,
    val checksumMatches: Boolean = false,
    val rawAudioRequired: Boolean = false,
) {
    val isComplete: Boolean
        get() = missingFiles.isEmpty()

    val isReady: Boolean
        get() = isComplete && checksumMatches
}

object SherpaModelInspector {
    fun inspect(spec: SherpaModelSpec): SherpaModelStatus {
        val root = File(spec.modelRootPath)
        val missingFiles = if (!root.isDirectory) {
            spec.requiredFiles
        } else {
            spec.requiredFiles.filterNot { File(root, it).isFile }
        }

        val totalBytes = if (root.isDirectory) {
            root.walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }
        } else {
            0L
        }

        val actualSha256ByFile = if (root.isDirectory && missingFiles.isEmpty()) {
            spec.requiredFiles.associateWith { fileName ->
                hashFile(File(root, fileName))
            }
        } else {
            emptyMap()
        }

        val actualCombinedSha256 = if (root.isDirectory && missingFiles.isEmpty()) {
            hashFiles(spec.requiredFiles.map { fileName -> File(root, fileName) })
        } else {
            null
        }

        val fileChecksMatch = if (spec.expectedSha256ByFile.isEmpty()) {
            true
        } else {
            spec.expectedSha256ByFile.all { (fileName, expected) ->
                actualSha256ByFile[fileName]?.equals(expected, ignoreCase = true) == true
            }
        }

        val combinedCheckMatches = spec.expectedCombinedSha256
            ?.equals(actualCombinedSha256, ignoreCase = true)
            ?: true

        return SherpaModelStatus(
            modelId = spec.id,
            modelType = spec.type,
            modelVersion = spec.version,
            requiredFiles = spec.requiredFiles,
            missingFiles = missingFiles,
            totalBytes = totalBytes,
            expectedSha256ByFile = spec.expectedSha256ByFile,
            actualSha256ByFile = actualSha256ByFile,
            expectedCombinedSha256 = spec.expectedCombinedSha256,
            actualCombinedSha256 = actualCombinedSha256,
            checksumMatches = fileChecksMatch && combinedCheckMatches,
            rawAudioRequired = spec.type == SherpaModelType.STREAMING_STT,
        )
    }

    private fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            updateDigestFromStream(digest, input)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hashFiles(files: List<File>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { file ->
            file.inputStream().use { input ->
                updateDigestFromStream(digest, input)
            }
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
}