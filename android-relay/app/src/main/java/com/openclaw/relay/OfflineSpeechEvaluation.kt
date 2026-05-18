package com.openclaw.relay

import java.io.File
import java.security.MessageDigest

enum class SpeechInputMode {
    PLATFORM,
    PLATFORM_ON_DEVICE,
    SHERPA_EVALUATION,
}

data class OfflineSpeechModelSpec(
    val engineId: String,
    val modelRootPath: String,
    val modelVersion: String,
    val requiredFiles: List<String> = SHERPA_STREAMING_REQUIRED_FILES,
    val expectedSha256: String? = null,
) {
    companion object {
        val SHERPA_STREAMING_REQUIRED_FILES = listOf(
            "tokens.txt",
            "encoder.onnx",
            "decoder.onnx",
            "joiner.onnx",
        )
    }
}

data class OfflineSpeechModelStatus(
    val modelRootPath: String,
    val modelVersion: String,
    val requiredFiles: List<String>,
    val missingFiles: List<String>,
    val totalBytes: Long,
    val expectedSha256: String? = null,
    val actualSha256: String? = null,
    val rawAudioRequired: Boolean = false,
) {
    val isComplete: Boolean
        get() = missingFiles.isEmpty()

    val checksumMatches: Boolean
        get() = expectedSha256.isNullOrBlank() || expectedSha256.equals(actualSha256, ignoreCase = true)
}

data class OfflineSpeechReadiness(
    val requestedMode: SpeechInputMode,
    val activeEngineId: String,
    val offlineEvaluationEnabled: Boolean,
    val nativeDependencyLinked: Boolean,
    val canRunOffline: Boolean,
    val modelPresent: Boolean,
    val modelVersion: String? = null,
    val modelFootprintBytes: Long = 0L,
    val missingModelFiles: List<String> = emptyList(),
    val failureReasons: List<String> = emptyList(),
) {
    companion object {
        private const val PLATFORM_ENGINE_ID = "platform_speech_recognizer"
        private const val SHERPA_ENGINE_ID = "sherpa_streaming"

        fun evaluate(config: RelayConfig): OfflineSpeechReadiness {
            val spec = OfflineSpeechModelSpec(
                engineId = SHERPA_ENGINE_ID,
                modelRootPath = config.offlineSpeechModelPath,
                modelVersion = config.offlineSpeechModelVersion.ifBlank { "unversioned" },
                expectedSha256 = config.offlineSpeechModelSha256.takeIf { it.isNotBlank() },
            )
            return evaluate(
                requestedMode = config.speechInputMode,
                spec = spec,
                nativeDependencyLinked = false,
            )
        }

        fun evaluate(
            requestedMode: SpeechInputMode,
            spec: OfflineSpeechModelSpec,
            nativeDependencyLinked: Boolean,
        ): OfflineSpeechReadiness {
            if (requestedMode == SpeechInputMode.PLATFORM || requestedMode == SpeechInputMode.PLATFORM_ON_DEVICE) {
                return OfflineSpeechReadiness(
                    requestedMode = requestedMode,
                    activeEngineId = if (requestedMode == SpeechInputMode.PLATFORM_ON_DEVICE) {
                        "platform_on_device_speech_recognizer"
                    } else {
                        PLATFORM_ENGINE_ID
                    },
                    offlineEvaluationEnabled = false,
                    nativeDependencyLinked = nativeDependencyLinked,
                    canRunOffline = false,
                    modelPresent = false,
                    failureReasons = listOf("offline_evaluation_disabled"),
                )
            }

            val modelStatus = OfflineSpeechModelManager.inspect(spec)
            val failures = buildList {
                if (!nativeDependencyLinked) add("sherpa_native_dependency_missing")
                if (!modelStatus.isComplete) add("offline_model_incomplete")
                if (!modelStatus.checksumMatches) add("offline_model_checksum_mismatch")
            }
            val canRun = failures.isEmpty()

            return OfflineSpeechReadiness(
                requestedMode = requestedMode,
                activeEngineId = if (canRun) spec.engineId else PLATFORM_ENGINE_ID,
                offlineEvaluationEnabled = true,
                nativeDependencyLinked = nativeDependencyLinked,
                canRunOffline = canRun,
                modelPresent = modelStatus.isComplete,
                modelVersion = spec.modelVersion,
                modelFootprintBytes = modelStatus.totalBytes,
                missingModelFiles = modelStatus.missingFiles,
                failureReasons = failures,
            )
        }
    }
}

object OfflineSpeechModelManager {
    fun inspect(spec: OfflineSpeechModelSpec): OfflineSpeechModelStatus {
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
        val actualSha256 = if (root.isDirectory && missingFiles.isEmpty() && !spec.expectedSha256.isNullOrBlank()) {
            hashRequiredFiles(root, spec.requiredFiles)
        } else {
            null
        }

        return OfflineSpeechModelStatus(
            modelRootPath = spec.modelRootPath,
            modelVersion = spec.modelVersion,
            requiredFiles = spec.requiredFiles,
            missingFiles = missingFiles,
            totalBytes = totalBytes,
            expectedSha256 = spec.expectedSha256,
            actualSha256 = actualSha256,
            rawAudioRequired = false,
        )
    }

    private fun hashRequiredFiles(root: File, requiredFiles: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        requiredFiles.sorted().forEach { relativePath ->
            digest.update(relativePath.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(File(root, relativePath).readBytes())
            digest.update(0)
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

internal object SpeechInputEngineFactory {
    fun create(context: android.content.Context, config: RelayConfig): SpeechInputEngine {
        val readiness = OfflineSpeechReadiness.evaluate(config)
        RelayStateStore.recordOfflineSpeechReadiness(readiness)
        return PlatformSpeechRecognizerEngine(
            recognizer = AndroidSpeechRecognizer(context),
            id = readiness.activeEngineId,
            defaultPreferOffline = config.speechInputMode == SpeechInputMode.PLATFORM_ON_DEVICE,
            defaultOnDeviceOnly = config.speechInputMode == SpeechInputMode.PLATFORM_ON_DEVICE,
        )
    }
}
