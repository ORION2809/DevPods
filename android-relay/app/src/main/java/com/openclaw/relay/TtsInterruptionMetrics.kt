package com.openclaw.relay

enum class TtsInterruptionReason {
    BARGE_IN,
    STOP_REQUESTED,
    SUPERSEDED,
    SERVICE_DESTROYED,
}

data class TtsInterruptionMetrics(
    val interruptionId: String,
    val reason: TtsInterruptionReason,
    val requestedAtMs: Long,
    val ttsStoppedAtMs: Long? = null,
    val listeningStartedAtMs: Long? = null,
) {
    val ttsStopLatencyMs: Long?
        get() = ttsStoppedAtMs?.let { (it - requestedAtMs).coerceAtLeast(0L) }

    val bargeInLatencyMs: Long?
        get() = listeningStartedAtMs?.let { (it - requestedAtMs).coerceAtLeast(0L) }

    val targetMet: Boolean
        get() = (ttsStopLatencyMs?.let { it <= TARGET_LATENCY_MS } == true) &&
            (bargeInLatencyMs?.let { it <= TARGET_LATENCY_MS } == true)

    companion object {
        const val TARGET_LATENCY_MS: Long = 250L
    }
}

class TtsInterruptionMetricsRecorder(
    interruptionId: String,
    reason: TtsInterruptionReason,
    requestedAtMs: Long,
) {
    private var metrics = TtsInterruptionMetrics(
        interruptionId = interruptionId,
        reason = reason,
        requestedAtMs = requestedAtMs,
    )

    fun markTtsStopped(nowMs: Long) {
        metrics = metrics.copy(ttsStoppedAtMs = nowMs)
    }

    fun markListeningStarted(nowMs: Long) {
        metrics = metrics.copy(listeningStartedAtMs = nowMs)
    }

    fun snapshot(): TtsInterruptionMetrics = metrics
}
