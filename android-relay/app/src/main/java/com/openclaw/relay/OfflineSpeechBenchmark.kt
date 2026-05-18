package com.openclaw.relay

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
