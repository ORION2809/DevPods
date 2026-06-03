package com.openclaw.relay

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

// Legacy benchmark structures kept for backward compatibility

data class OfflineSpeechBenchmarkSample(
    val sessionId: String,
    val engineId: String,
    val coldLoadMs: Long? = null,
    val warmStartMs: Long? = null,
    val firstPartialMs: Long? = null,
    val finalEndpointMs: Long? = null,
    val commandSucceeded: Boolean = false,
    val routeSucceeded: Boolean = false,
    val wrongRouteDetected: Boolean = false,
    val ttsInterruptionTargetMet: Boolean = false,
    val modelFootprintBytes: Long = 0L,
)

data class OfflineSpeechBenchmarkSummary(
    val sampleCount: Int,
    val engineId: String?,
    val reliabilityPercent: Int,
    val routeReliabilityPercent: Int,
    val medianFinalEndpointMs: Long?,
    val medianFirstPartialMs: Long?,
    val bargeInTargetMetCount: Int,
    val failureReasons: List<String>,
) {
    val promotionReady: Boolean
        get() = failureReasons.isEmpty()
}

object OfflineSpeechBenchmark {
    private const val REQUIRED_SAMPLE_COUNT = 20
    private const val REQUIRED_RELIABILITY_PERCENT = 95
    private const val REQUIRED_FINAL_ENDPOINT_MS = 900L

    fun summarize(samples: List<OfflineSpeechBenchmarkSample>): OfflineSpeechBenchmarkSummary {
        val reliabilityPercent = percent(samples.count { it.commandSucceeded }, samples.size)
        val routeReliabilityPercent = percent(samples.count { it.routeSucceeded }, samples.size)
        val medianFinalEndpointMs = samples.mapNotNull { it.finalEndpointMs }.median()
        val medianFirstPartialMs = samples.mapNotNull { it.firstPartialMs }.median()
        val failureReasons = buildList {
            if (samples.size < REQUIRED_SAMPLE_COUNT) add("insufficient_sample_count")
            if (reliabilityPercent < REQUIRED_RELIABILITY_PERCENT) add("reliability_below_95_percent")
            if (routeReliabilityPercent < REQUIRED_RELIABILITY_PERCENT) add("route_reliability_below_95_percent")
            if (medianFinalEndpointMs == null || medianFinalEndpointMs > REQUIRED_FINAL_ENDPOINT_MS) {
                add("median_final_endpoint_above_900ms")
            }
            if (samples.any { it.wrongRouteDetected }) add("wrong_route_detected")
            if (samples.any { !it.ttsInterruptionTargetMet }) add("barge_in_target_missed")
        }

        return OfflineSpeechBenchmarkSummary(
            sampleCount = samples.size,
            engineId = samples.firstOrNull()?.engineId,
            reliabilityPercent = reliabilityPercent,
            routeReliabilityPercent = routeReliabilityPercent,
            medianFinalEndpointMs = medianFinalEndpointMs,
            medianFirstPartialMs = medianFirstPartialMs,
            bargeInTargetMetCount = samples.count { it.ttsInterruptionTargetMet },
            failureReasons = failureReasons,
        )
    }

    private fun percent(count: Int, total: Int): Int =
        if (total <= 0) 0 else ((count.toFloat() / total.toFloat()) * 100).toInt()

    private fun List<Long>.median(): Long? {
        if (isEmpty()) return null
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2
        } else {
            sorted[middle]
        }
    }
}

// ---- Workstream 9: Sherpa Command-Mode Evaluation ----

@Serializable
enum class CommandBenchmarkEngine {
    PLATFORM_STT,
    PLATFORM_ON_DEVICE_STT,
    SHERPA_STT,
    ;

    /**
     * Returns true if the given runtime engine ID is acceptable for this benchmark engine.
     * This maps declared benchmark engines to actual recognizer instance IDs.
     */
    fun acceptsRuntimeId(runtimeId: String): Boolean = when (this) {
        PLATFORM_STT -> runtimeId == "platform_speech_recognizer"
        PLATFORM_ON_DEVICE_STT -> runtimeId == "platform_on_device_speech_recognizer"
        SHERPA_STT -> runtimeId == "sherpa_streaming"
    }
}

@Serializable
data class CommandBenchmarkSample(
    val command: String,
    val engine: CommandBenchmarkEngine,
    val wakeToReadyMs: Long? = null,
    val firstPartialMs: Long? = null,
    val finalTranscriptMs: Long? = null,
    val transcriptAccuracy: Float? = null,
    val intentAccuracy: Float? = null,
    val endpointDelayMs: Long? = null,
    val noSpeechDetected: Boolean = false,
    val nativeFailureRecovered: Boolean = true,
    val cpuPercent: Float? = null,
    val batteryDrainMah: Float? = null,
    val thermalThrottled: Boolean = false,
    val transcriptText: String? = null,
    val actualEngineId: String? = null,
)

@Serializable
data class CommandBenchmarkEngineSummary(
    val engine: CommandBenchmarkEngine,
    val sampleCount: Int,
    val medianWakeToReadyMs: Long? = null,
    val medianFirstPartialMs: Long? = null,
    val medianFinalTranscriptMs: Long? = null,
    val medianEndpointDelayMs: Long? = null,
    val meanTranscriptAccuracy: Float? = null,
    val meanIntentAccuracy: Float? = null,
    val noSpeechRatePercent: Int = 0,
    val nativeFailureRecoveryRatePercent: Int = 100,
    val thermalThrottleCount: Int = 0,
    val failureReasons: List<String> = emptyList(),
)

@Serializable
data class SherpaCommandBenchmarkReport(
    val runId: String,
    val runAtMs: Long,
    val commandSet: List<String>,
    val samples: List<CommandBenchmarkSample>,
    val engineSummaries: List<CommandBenchmarkEngineSummary>,
    val promotionState: SherpaPromotionState,
    val promotionReasons: List<String>,
    val artifactPath: String? = null,
)

object SherpaCommandBenchmarkSet {
    val COMMANDS = listOf(
        "status",
        "what changed",
        "run tests",
        "open the main file",
        "latest CI failure",
        "write a commit message",
        "push",
        "cancel",
    )
}

object SherpaCommandBenchmark {
    private const val REQUIRED_SAMPLE_COUNT_PER_ENGINE = 8 // one per command minimum
    private const val REQUIRED_TRANSCRIPT_ACCURACY = 0.90f
    private const val REQUIRED_INTENT_ACCURACY = 0.85f
    private const val MAX_MEDIAN_WAKE_TO_READY_MS = 1_200L
    private const val MAX_MEDIAN_FINAL_TRANSCRIPT_MS = 1_500L
    private const val MAX_MEDIAN_ENDPOINT_DELAY_MS = 900L
    private const val MAX_NO_SPEECH_RATE_PERCENT = 15
    private const val MIN_NATIVE_FAILURE_RECOVERY_PERCENT = 80

    fun runBenchmark(
        samples: List<CommandBenchmarkSample>,
    ): SherpaCommandBenchmarkReport {
        val runId = "sherpa-cmd-bench-${System.currentTimeMillis()}"
        val engineSummaries = CommandBenchmarkEngine.entries.map { engine ->
            summarizeEngine(engine, samples)
        }

        val (promotionState, promotionReasons) = computePromotionState(engineSummaries)

        return SherpaCommandBenchmarkReport(
            runId = runId,
            runAtMs = System.currentTimeMillis(),
            commandSet = SherpaCommandBenchmarkSet.COMMANDS,
            samples = samples,
            engineSummaries = engineSummaries,
            promotionState = promotionState,
            promotionReasons = promotionReasons,
        )
    }

    fun summarizeEngine(
        engine: CommandBenchmarkEngine,
        allSamples: List<CommandBenchmarkSample>,
    ): CommandBenchmarkEngineSummary {
        val engineSamples = allSamples.filter { it.engine == engine }
        val count = engineSamples.size
        val requiredCount = SherpaCommandBenchmarkSet.COMMANDS.size

        val medianWakeToReadyMs = engineSamples.mapNotNull { it.wakeToReadyMs }.median()
        val medianFirstPartialMs = engineSamples.mapNotNull { it.firstPartialMs }.median()
        val medianFinalTranscriptMs = engineSamples.mapNotNull { it.finalTranscriptMs }.median()
        val medianEndpointDelayMs = engineSamples.mapNotNull { it.endpointDelayMs }.median()
        val meanTranscriptAccuracy = engineSamples.mapNotNull { it.transcriptAccuracy }.averageOrNull()
        val meanIntentAccuracy = engineSamples.mapNotNull { it.intentAccuracy }.averageOrNull()
        val noSpeechRatePercent = percent(engineSamples.count { it.noSpeechDetected }, count)
        val nativeFailureRecoveryRatePercent = percent(
            engineSamples.count { it.nativeFailureRecovered },
            engineSamples.count { !it.nativeFailureRecovered || it.nativeFailureRecovered },
        )
        val thermalThrottleCount = engineSamples.count { it.thermalThrottled }
        val engineMismatchCount = engineSamples.count {
            it.actualEngineId != null && !engine.acceptsRuntimeId(it.actualEngineId)
        }

        val failureReasons = buildList {
            if (count < requiredCount) add("insufficient_samples: $count < $requiredCount")
            if (engineMismatchCount > 0) add("engine_mismatch:$engineMismatchCount")
            if (meanTranscriptAccuracy != null && meanTranscriptAccuracy < REQUIRED_TRANSCRIPT_ACCURACY) {
                add("transcript_accuracy_below_${(REQUIRED_TRANSCRIPT_ACCURACY * 100).toInt()}_percent")
            }
            if (meanIntentAccuracy != null && meanIntentAccuracy < REQUIRED_INTENT_ACCURACY) {
                add("intent_accuracy_below_${(REQUIRED_INTENT_ACCURACY * 100).toInt()}_percent")
            }
            if (medianWakeToReadyMs == null || medianWakeToReadyMs > MAX_MEDIAN_WAKE_TO_READY_MS) {
                add("wake_to_ready_above_${MAX_MEDIAN_WAKE_TO_READY_MS}ms")
            }
            if (medianFinalTranscriptMs == null || medianFinalTranscriptMs > MAX_MEDIAN_FINAL_TRANSCRIPT_MS) {
                add("final_transcript_above_${MAX_MEDIAN_FINAL_TRANSCRIPT_MS}ms")
            }
            if (medianEndpointDelayMs == null || medianEndpointDelayMs > MAX_MEDIAN_ENDPOINT_DELAY_MS) {
                add("endpoint_delay_above_${MAX_MEDIAN_ENDPOINT_DELAY_MS}ms")
            }
            if (noSpeechRatePercent > MAX_NO_SPEECH_RATE_PERCENT) {
                add("no_speech_rate_above_${MAX_NO_SPEECH_RATE_PERCENT}_percent")
            }
            if (nativeFailureRecoveryRatePercent < MIN_NATIVE_FAILURE_RECOVERY_PERCENT) {
                add("native_failure_recovery_below_${MIN_NATIVE_FAILURE_RECOVERY_PERCENT}_percent")
            }
        }

        return CommandBenchmarkEngineSummary(
            engine = engine,
            sampleCount = count,
            medianWakeToReadyMs = medianWakeToReadyMs,
            medianFirstPartialMs = medianFirstPartialMs,
            medianFinalTranscriptMs = medianFinalTranscriptMs,
            medianEndpointDelayMs = medianEndpointDelayMs,
            meanTranscriptAccuracy = meanTranscriptAccuracy,
            meanIntentAccuracy = meanIntentAccuracy,
            noSpeechRatePercent = noSpeechRatePercent,
            nativeFailureRecoveryRatePercent = nativeFailureRecoveryRatePercent,
            thermalThrottleCount = thermalThrottleCount,
            failureReasons = failureReasons,
        )
    }

    private fun computePromotionState(
        summaries: List<CommandBenchmarkEngineSummary>,
    ): Pair<SherpaPromotionState, List<String>> {
        val sherpaSummary = summaries.find { it.engine == CommandBenchmarkEngine.SHERPA_STT }
        val platformSummary = summaries.find { it.engine == CommandBenchmarkEngine.PLATFORM_STT }
        val platformOnDeviceSummary = summaries.find { it.engine == CommandBenchmarkEngine.PLATFORM_ON_DEVICE_STT }

        if (sherpaSummary == null || sherpaSummary.sampleCount == 0) {
            return SherpaPromotionState.HIDDEN to listOf("sherpa_summary_missing")
        }

        val reasons = mutableListOf<String>()

        // Gate 1: Sherpa must pass its own quality gates
        if (sherpaSummary.failureReasons.isNotEmpty()) {
            reasons.addAll(sherpaSummary.failureReasons.map { "sherpa:$it" })
            return SherpaPromotionState.DEVELOPER_DIAGNOSTIC to reasons
        }

        // Gate 2: Sherpa must be better than platform STT for DevPods commands
        val platform = platformSummary ?: platformOnDeviceSummary
        if (platform != null) {
            if (sherpaSummary.meanIntentAccuracy != null && platform.meanIntentAccuracy != null) {
                if (sherpaSummary.meanIntentAccuracy <= platform.meanIntentAccuracy) {
                    reasons.add("sherpa_intent_not_better_than_platform")
                }
            }
            if (sherpaSummary.medianFinalTranscriptMs != null && platform.medianFinalTranscriptMs != null) {
                if (sherpaSummary.medianFinalTranscriptMs >= platform.medianFinalTranscriptMs) {
                    reasons.add("sherpa_latency_not_better_than_platform")
                }
            }
        }

        if (reasons.isNotEmpty()) {
            return SherpaPromotionState.DEVELOPER_DIAGNOSTIC to reasons
        }

        // Gate 3: EXPERIMENTAL — Sherpa is better than platform but we want more data
        // We promote to EXPERIMENTAL if Sherpa beats platform and has no internal failures.
        // We promote to PRODUCTION only if it also beats platform on-device (if available) and
        // has been stable across multiple runs.
        val beatsPlatformOnDevice = if (platformOnDeviceSummary != null && platformOnDeviceSummary.sampleCount > 0) {
            val intentBetter = sherpaSummary.meanIntentAccuracy != null && platformOnDeviceSummary.meanIntentAccuracy != null &&
                sherpaSummary.meanIntentAccuracy > platformOnDeviceSummary.meanIntentAccuracy
            val latencyBetter = sherpaSummary.medianFinalTranscriptMs != null && platformOnDeviceSummary.medianFinalTranscriptMs != null &&
                sherpaSummary.medianFinalTranscriptMs < platformOnDeviceSummary.medianFinalTranscriptMs
            intentBetter && latencyBetter
        } else {
            true // if no on-device platform data, we can't require it
        }

        if (!beatsPlatformOnDevice) {
            reasons.add("sherpa_not_better_than_platform_on_device")
            return SherpaPromotionState.EXPERIMENTAL to reasons
        }

        // PRODUCTION requires strict margins
        val productionReady = sherpaSummary.meanIntentAccuracy != null &&
            sherpaSummary.meanIntentAccuracy >= 0.95f &&
            sherpaSummary.medianFinalTranscriptMs != null &&
            sherpaSummary.medianFinalTranscriptMs <= 1_000L &&
            sherpaSummary.noSpeechRatePercent <= 5

        return if (productionReady) {
            SherpaPromotionState.PRODUCTION to listOf("sherpa_meets_production_gates")
        } else {
            SherpaPromotionState.EXPERIMENTAL to listOf("sherpa_passes_minimum_gates")
        }
    }

    private fun percent(count: Int, total: Int): Int =
        if (total <= 0) 0 else ((count.toFloat() / total.toFloat()) * 100).toInt()

    private fun List<Long>.median(): Long? {
        if (isEmpty()) return null
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2
        } else {
            sorted[middle]
        }
    }

    private fun List<Float>.averageOrNull(): Float? {
        if (isEmpty()) return null
        return (sum() / size)
    }
}

object SherpaBenchmarkArtifactStore {
    private const val ARTIFACT_DIR = "sherpa_benchmarks"
    private const val ARTIFACT_FILENAME = "report.json"
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun store(context: Context, report: SherpaCommandBenchmarkReport): String {
        val dir = File(context.filesDir, ARTIFACT_DIR).apply { mkdirs() }
        val file = File(dir, "${report.runId}-$ARTIFACT_FILENAME")
        file.writeText(json.encodeToString(report))
        return file.absolutePath
    }

    fun store(report: SherpaCommandBenchmarkReport, filesDir: File): String {
        val dir = File(filesDir, ARTIFACT_DIR).apply { mkdirs() }
        val file = File(dir, "${report.runId}-$ARTIFACT_FILENAME")
        file.writeText(Json { prettyPrint = true; ignoreUnknownKeys = true }.encodeToString(report))
        return file.absolutePath
    }

    fun listArtifacts(context: Context): List<File> {
        val dir = File(context.filesDir, ARTIFACT_DIR)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.toList() ?: emptyList()
    }

    fun latestArtifact(context: Context): SherpaCommandBenchmarkReport? {
        return latestArtifact(context.filesDir)
    }

    fun latestArtifact(filesDir: File): SherpaCommandBenchmarkReport? {
        val dir = File(filesDir, ARTIFACT_DIR)
        if (!dir.isDirectory) return null
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.toList() ?: emptyList()
        val latest = files.sortedByDescending { it.lastModified() }.firstOrNull() ?: return null
        return runCatching {
            Json { ignoreUnknownKeys = true }.decodeFromString<SherpaCommandBenchmarkReport>(latest.readText())
        }.getOrNull()
    }
}
