package com.openclaw.relay

import com.openclaw.relay.sherpa.SherpaFeatureFlags
import com.openclaw.relay.sherpa.SherpaModelSpec
import com.openclaw.relay.sherpa.SherpaModelType
import com.openclaw.relay.sherpa.SherpaReadiness
import com.openclaw.relay.sherpa.SherpaRuntimeAvailability
import com.openclaw.relay.sherpa.SttReadiness
import com.openclaw.relay.sherpa.VadReadiness
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
    val sherpaReadiness: SherpaReadiness? = null,
    val sherpaFeatureFlags: SherpaFeatureFlags = SherpaFeatureFlags.DEFAULT,
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

            val runtimeAvailability = SherpaRuntimeAvailability.probe()
            val vadSpec = if (config.offlineSpeechModelPath.isNotBlank()) {
                SherpaModelSpec.SILERO_VAD_V4.copy(installRoot = config.offlineSpeechModelPath)
            } else {
                null
            }
            val sttSpec = SherpaModelSpec(
                id = SHERPA_ENGINE_ID,
                type = SherpaModelType.STREAMING_STT,
                version = config.offlineSpeechModelVersion.ifBlank { "unversioned" },
                requiredFiles = OfflineSpeechModelSpec.SHERPA_STREAMING_REQUIRED_FILES,
                installRoot = config.offlineSpeechModelPath,
            )

            val sherpaReadiness = SherpaReadiness(
                vadReadiness = VadReadiness.evaluate(runtimeAvailability, vadSpec),
                sttReadiness = SttReadiness.evaluate(runtimeAvailability, sttSpec),
                runtimeAvailability = runtimeAvailability,
            )

            val sherpaFeatureFlags = SherpaFeatureFlags.fromConfig(
                runtimeEnabled = config.sherpaRuntimeEnabled,
                vadEnabled = config.sherpaVadDiagnosticsEnabled,
                sttEnabled = config.sherpaSttExperimentalEnabled,
                downloadsEnabled = config.sherpaModelDownloadsEnabled,
            )

            val nativeDependencyLinked = runtimeAvailability.isNativeLibraryLoadable
            val sttModelReady = sherpaReadiness.sttReady

            val legacyResult = evaluate(
                requestedMode = config.speechInputMode,
                spec = spec,
                nativeDependencyLinked = nativeDependencyLinked,
                sherpaReadiness = sherpaReadiness,
                sttModelReady = sttModelReady,
            )

            return legacyResult.copy(
                sherpaFeatureFlags = sherpaFeatureFlags,
            )
        }

        fun evaluate(
            requestedMode: SpeechInputMode,
            spec: OfflineSpeechModelSpec,
            nativeDependencyLinked: Boolean,
            sherpaReadiness: SherpaReadiness? = null,
            sttModelReady: Boolean? = null,
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
                    sherpaReadiness = sherpaReadiness,
                )
            }

            val modelStatus = OfflineSpeechModelManager.inspect(spec)
            val modelReady = modelStatus.isComplete && modelStatus.checksumMatches
            val sttReady = sttModelReady ?: modelReady
            val failures = buildList {
                if (!nativeDependencyLinked) add("sherpa_native_dependency_missing")
                if (!modelStatus.isComplete) add("offline_model_incomplete")
                if (!modelStatus.checksumMatches) add("offline_model_checksum_mismatch")
                if (sttModelReady != null && !sttReady) add("sherpa_stt_model_not_ready")
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
                sherpaReadiness = sherpaReadiness,
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
    fun create(
        context: android.content.Context,
        config: RelayConfig,
        captureOwner: com.openclaw.relay.audio.AudioCaptureOwner? = null,
    ): SpeechInputEngine {
        val readiness = OfflineSpeechReadiness.evaluate(config)
        RelayStateStore.recordOfflineSpeechReadiness(readiness)

        return when (config.speechInputMode) {
            SpeechInputMode.SHERPA_EVALUATION -> {
                if (readiness.canRunOffline && captureOwner != null) {
                    val sttSpec = com.openclaw.relay.sherpa.SherpaModelSpec(
                        id = "sherpa_streaming_en_zipformer",
                        type = com.openclaw.relay.sherpa.SherpaModelType.STREAMING_STT,
                        version = config.offlineSpeechModelVersion.ifBlank { "unversioned" },
                        requiredFiles = OfflineSpeechModelSpec.SHERPA_STREAMING_REQUIRED_FILES,
                        installRoot = config.offlineSpeechModelPath,
                    )
                    com.openclaw.relay.speech.SherpaSpeechInputEngine(
                        context = context,
                        captureOwner = captureOwner,
                        modelSpecProvider = { sttSpec },
                        modelStatusProvider = {
                            com.openclaw.relay.sherpa.SherpaModelInspector.inspect(sttSpec)
                        },
                        runtimeAvailabilityProvider = {
                            com.openclaw.relay.sherpa.SherpaRuntimeAvailability.probe()
                        },
                    )
                } else {
                    // Fallback to platform with diagnostic visibility
                    RelayStateStore.setError(
                        "Offline speech mode is selected but unavailable: ${readiness.failureReasons.joinToString(", ")}. " +
                            "Falling back to platform recognizer.",
                    )
                    PlatformSpeechRecognizerEngine(
                        recognizer = AndroidSpeechRecognizer(context),
                        id = readiness.activeEngineId,
                    )
                }
            }

            SpeechInputMode.PLATFORM_ON_DEVICE -> PlatformSpeechRecognizerEngine(
                recognizer = AndroidSpeechRecognizer(context),
                id = readiness.activeEngineId,
                defaultPreferOffline = true,
                defaultOnDeviceOnly = true,
            )

            SpeechInputMode.PLATFORM -> PlatformSpeechRecognizerEngine(
                recognizer = AndroidSpeechRecognizer(context),
                id = readiness.activeEngineId,
            )
        }
    }
}

// ---- Workstream 9: Command-mode evaluation report ----

object OfflineSpeechEvaluation {
    /**
     * Runs the full command benchmark set across all candidate engines and produces a structured
     * report with an explicit promotion recommendation.
     *
     * @param samples Per-command, per-engine benchmark samples. Callers should collect these
     *   by running each command through the three engine paths (platform STT, platform on-device
     *   STT, Sherpa STT).
     * @param context Android context used to store the benchmark artifact.
     */
    fun evaluateCommandBenchmark(
        samples: List<CommandBenchmarkSample>,
        context: android.content.Context? = null,
        includeRawTranscript: Boolean = false,
    ): SherpaCommandBenchmarkReport {
        val report = SherpaCommandBenchmark.runBenchmark(samples)
        val reportToStore = if (includeRawTranscript) {
            report
        } else {
            report.copy(
                samples = report.samples.map { it.copy(transcriptText = null) },
            )
        }
        val artifactPath = context?.let { SherpaBenchmarkArtifactStore.store(it, reportToStore) }
        return reportToStore.copy(artifactPath = artifactPath)
    }

    /**
     * Computes the effective promotion state from the latest stored benchmark artifact,
     * or [SherpaPromotionState.HIDDEN] if no artifact exists.
     */
    fun resolvePromotionState(context: android.content.Context): SherpaPromotionState {
        return resolvePromotionState(context.filesDir)
    }

    fun resolvePromotionState(filesDir: java.io.File): SherpaPromotionState {
        val latest = SherpaBenchmarkArtifactStore.latestArtifact(filesDir)
        return latest?.promotionState ?: SherpaPromotionState.HIDDEN
    }
}
