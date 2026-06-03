package com.openclaw.relay

/**
 * Aggregates VAD-like observations over time to detect trends
 * in speech detection, route quality, and endpoint reliability.
 */
class VadTelemetry(
    private val maxObservations: Int = 100,
) {
    private val observations = ArrayDeque<PlatformVadObservation>(maxObservations)

    fun record(observation: PlatformVadObservation) {
        observations.addLast(observation)
        if (observations.size > maxObservations) {
            observations.removeFirst()
        }
    }

    fun recentObservations(): List<PlatformVadObservation> = observations.toList()

    fun observationCount(): Int = observations.size

    fun speechDetectionRate(): Float {
        if (observations.isEmpty()) return 0f
        val detected = observations.count { it.speechDetected }
        return detected.toFloat() / observations.size.toFloat()
    }

    fun wrongMicSuspicionRate(): Float {
        if (observations.isEmpty()) return 0f
        val suspected = observations.count { it.wrongMicSuspected }
        return suspected.toFloat() / observations.size.toFloat()
    }

    fun averageSpeechStartDelayMs(): Double? {
        val delays = observations.mapNotNull { it.speechStartDelayMs }
        if (delays.isEmpty()) return null
        return delays.average()
    }

    fun averagePartialAfterSpeechStartMs(): Double? {
        val delays = observations.mapNotNull { it.partialAfterSpeechStartMs }
        if (delays.isEmpty()) return null
        return delays.average()
    }

    fun averageFinalizationDelayMs(): Double? {
        val delays = observations.mapNotNull { it.finalizationDelayMs }
        if (delays.isEmpty()) return null
        return delays.average()
    }

    fun peakRmsDb(): Float? {
        return observations.mapNotNull { it.rmsPeakDb }.maxOrNull()
    }

    fun averageRmsPeakDb(): Double? {
        val peaks = observations.mapNotNull { it.rmsPeakDb }
        if (peaks.isEmpty()) return null
        return peaks.average()
    }

    fun endpointDistribution(): Map<SpeechEndpointReason, Int> {
        return observations.groupingBy { it.endpointReason }.eachCount()
    }

    fun clear() {
        observations.clear()
    }

    /**
     * Returns a simple trend indicator comparing the first half of observations
     * to the second half. Useful for spotting degradation after device or OS changes.
     */
    fun trend(): VadTrend {
        val list = observations.toList()
        if (list.size < 10) return VadTrend.INSUFFICIENT_DATA

        val midpoint = list.size / 2
        val firstHalf = list.take(midpoint)
        val secondHalf = list.drop(midpoint)

        val firstSpeechRate = firstHalf.count { it.speechDetected }.toFloat() / firstHalf.size
        val secondSpeechRate = secondHalf.count { it.speechDetected }.toFloat() / secondHalf.size

        val firstWrongMicRate = firstHalf.count { it.wrongMicSuspected }.toFloat() / firstHalf.size
        val secondWrongMicRate = secondHalf.count { it.wrongMicSuspected }.toFloat() / secondHalf.size

        return when {
            secondSpeechRate < firstSpeechRate * 0.8f -> VadTrend.SPEECH_DETECTION_DECLINING
            secondWrongMicRate > firstWrongMicRate * 1.5f && secondWrongMicRate > 0.1f ->
                VadTrend.WRONG_MIC_SUSPICION_RISING
            secondSpeechRate > firstSpeechRate * 1.1f -> VadTrend.SPEECH_DETECTION_IMPROVING
            else -> VadTrend.STABLE
        }
    }
}

enum class VadTrend {
    INSUFFICIENT_DATA,
    STABLE,
    SPEECH_DETECTION_IMPROVING,
    SPEECH_DETECTION_DECLINING,
    WRONG_MIC_SUSPICION_RISING,
}
