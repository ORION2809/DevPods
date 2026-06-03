package com.openclaw.relay

/**
 * Short rolling history and export-ready summaries for voice diagnostics.
 *
 * Keeps a bounded in-memory window of recent sessions so the UI and
 * diagnostic export can show trends without persisting raw audio or
 * full transcripts.
 */
class VoiceDiagnosticsStore(
    private val maxSpeechSessions: Int = 50,
    private val maxVadObservations: Int = 50,
    private val maxTtsPlaybacks: Int = 50,
    private val maxAudioProbes: Int = 20,
    private val maxInterruptions: Int = 20,
) {
    private val speechSessionHistory = ArrayDeque<SpeechSessionMetrics>(maxSpeechSessions)
    private val vadObservationHistory = ArrayDeque<PlatformVadObservation>(maxVadObservations)
    private val ttsPlaybackHistory = ArrayDeque<TtsPlaybackMetrics>(maxTtsPlaybacks)
    private val audioProbeHistory = ArrayDeque<AudioProbeMetrics>(maxAudioProbes)
    private val interruptionHistory = ArrayDeque<TtsInterruptionMetrics>(maxInterruptions)

    fun recordSpeechSession(metrics: SpeechSessionMetrics) {
        speechSessionHistory.addLast(metrics)
        if (speechSessionHistory.size > maxSpeechSessions) {
            speechSessionHistory.removeFirst()
        }
        vadObservationHistory.addLast(metrics.toPlatformVadObservation())
        if (vadObservationHistory.size > maxVadObservations) {
            vadObservationHistory.removeFirst()
        }
    }

    fun recordTtsPlayback(metrics: TtsPlaybackMetrics) {
        ttsPlaybackHistory.addLast(metrics)
        if (ttsPlaybackHistory.size > maxTtsPlaybacks) {
            ttsPlaybackHistory.removeFirst()
        }
    }

    fun recordAudioProbe(metrics: AudioProbeMetrics) {
        audioProbeHistory.addLast(metrics)
        if (audioProbeHistory.size > maxAudioProbes) {
            audioProbeHistory.removeFirst()
        }
    }

    fun recordTtsInterruption(metrics: TtsInterruptionMetrics) {
        interruptionHistory.addLast(metrics)
        if (interruptionHistory.size > maxInterruptions) {
            interruptionHistory.removeFirst()
        }
    }

    fun recentSpeechSessions(): List<SpeechSessionMetrics> = speechSessionHistory.toList()

    fun recentVadObservations(): List<PlatformVadObservation> = vadObservationHistory.toList()

    fun recentTtsPlaybacks(): List<TtsPlaybackMetrics> = ttsPlaybackHistory.toList()

    fun recentAudioProbes(): List<AudioProbeMetrics> = audioProbeHistory.toList()

    fun recentInterruptions(): List<TtsInterruptionMetrics> = interruptionHistory.toList()

    fun speechSessionCount(): Int = speechSessionHistory.size

    fun ttsPlaybackCount(): Int = ttsPlaybackHistory.size

    fun audioProbeCount(): Int = audioProbeHistory.size

    fun interruptionCount(): Int = interruptionHistory.size

    fun clear() {
        speechSessionHistory.clear()
        vadObservationHistory.clear()
        ttsPlaybackHistory.clear()
        audioProbeHistory.clear()
        interruptionHistory.clear()
    }

    /**
     * Aggregate summary suitable for diagnostic export.
     * No raw audio, no transcript text, no device names.
     */
    fun exportSummary(): VoiceDiagnosticsExportSummary {
        val sessions = speechSessionHistory.toList()
        val vadList = vadObservationHistory.toList()
        val ttsList = ttsPlaybackHistory.toList()
        val probes = audioProbeHistory.toList()
        val interrupts = interruptionHistory.toList()

        val completedSessions = sessions.filter {
            it.endpointReason != SpeechEndpointReason.LISTENING
        }

        val finalSessions = completedSessions.filter {
            it.endpointReason == SpeechEndpointReason.FINAL
        }

        val errorSessions = completedSessions.filter {
            it.endpointReason != SpeechEndpointReason.FINAL
        }

        val routeSuccesses = completedSessions.filter {
            it.routeProof.routeState == AudioRouteProofState.ROUTE_BLUETOOTH_ACTIVE ||
                it.routeProof.routeState == AudioRouteProofState.ROUTE_PHONE_MIC
        }

        val wrongMicSuspicions = vadList.filter { it.wrongMicSuspected }

        return VoiceDiagnosticsExportSummary(
            totalSpeechSessions = sessions.size,
            completedSessions = completedSessions.size,
            successfulFinals = finalSessions.size,
            errorSessions = errorSessions.size,
            routeSuccessCount = routeSuccesses.size,
            routeFailureCount = completedSessions.size - routeSuccesses.size,
            wrongMicSuspectedCount = wrongMicSuspicions.size,
            speechDetectionRatePercent = percent(finalSessions.size, sessions.size),
            routeSuccessRatePercent = percent(routeSuccesses.size, completedSessions.size),
            averageRouteSettleMs = sessions.mapNotNull { it.routeSettleMs }.averageOrNull(),
            averageReadyToSpeechStartMs = sessions.mapNotNull { it.readyToSpeechStartMs }.averageOrNull(),
            averageFinalizationDelayMs = sessions.mapNotNull { it.finalizationDelayMs }.averageOrNull(),
            peakRmsDb = sessions.mapNotNull { it.rmsPeakDb }.maxOrNull(),
            ttsPlaybackCount = ttsList.size,
            ttsErrorCount = ttsList.count { it.event == TtsPlaybackEvent.ERROR },
            averageTtsStartDelayMs = ttsList.mapNotNull { it.startDelayMs }.averageOrNull(),
            audioProbeCount = probes.size,
            audioProbeSuccessCount = probes.count { it.initStatus == AudioProbeInitStatus.STARTED },
            interruptionCount = interrupts.size,
            interruptionTargetMetCount = interrupts.count { it.targetMet },
            recentEndpointReasons = completedSessions
                .takeLast(10)
                .map { it.endpointReason.name.lowercase() },
            recentErrorCodes = errorSessions
                .takeLast(10)
                .mapNotNull { it.errorCode },
            // Workstream 0: latency summary aggregates
            latencySummary = if (sessions.size >= 2) buildLatencySummary(sessions) else null,
        )
    }

    private fun buildLatencySummary(sessions: List<SpeechSessionMetrics>): LatencySummary {
        val gestureToRouteReq = sessions.mapNotNull { it.gestureReceivedToRouteRequestedMs }
        val routeReqToReady = sessions.mapNotNull { it.routeRequestedToRouteReadyMs }
        val gestureToListening = sessions.mapNotNull { it.gestureReceivedToListeningStartedMs }
        val recognizerCreate = sessions.mapNotNull { it.recognizerCreateMs }
        val listeningToReady = sessions.mapNotNull { it.listeningStartedToReadyMs }
        val readyToFirstRms = sessions.mapNotNull { it.readyToFirstRmsMs }
        val readyToSpeechStart = sessions.mapNotNull { it.readyToSpeechStartMs }
        val speechStartToPartial = sessions.mapNotNull { it.speechStartToPartialMs }
        val finalizationDelay = sessions.mapNotNull { it.finalizationDelayMs }
        val finalToBridgeReq = sessions.mapNotNull { it.finalTranscriptToBridgeRequestMs }
        val bridgeDuration = sessions.mapNotNull { it.bridgeRequestDurationMs }
        val bridgeRespToTtsReq = sessions.mapNotNull { it.bridgeResponseToTtsRequestedMs }
        val ttsReqToStart = sessions.mapNotNull { it.ttsRequestToStartMs }

        return LatencySummary(
            gestureReceivedToRouteRequestedP50 = gestureToRouteReq.percentile(50),
            gestureReceivedToRouteRequestedP95 = gestureToRouteReq.percentile(95),
            routeRequestedToRouteReadyP50 = routeReqToReady.percentile(50),
            routeRequestedToRouteReadyP95 = routeReqToReady.percentile(95),
            gestureReceivedToListeningStartedP50 = gestureToListening.percentile(50),
            gestureReceivedToListeningStartedP95 = gestureToListening.percentile(95),
            recognizerCreateP50 = recognizerCreate.percentile(50),
            recognizerCreateP95 = recognizerCreate.percentile(95),
            listeningStartedToReadyP50 = listeningToReady.percentile(50),
            listeningStartedToReadyP95 = listeningToReady.percentile(95),
            readyToFirstRmsP50 = readyToFirstRms.percentile(50),
            readyToFirstRmsP95 = readyToFirstRms.percentile(95),
            readyToSpeechStartP50 = readyToSpeechStart.percentile(50),
            readyToSpeechStartP95 = readyToSpeechStart.percentile(95),
            speechStartToPartialP50 = speechStartToPartial.percentile(50),
            speechStartToPartialP95 = speechStartToPartial.percentile(95),
            finalizationDelayP50 = finalizationDelay.percentile(50),
            finalizationDelayP95 = finalizationDelay.percentile(95),
            finalTranscriptToBridgeRequestP50 = finalToBridgeReq.percentile(50),
            finalTranscriptToBridgeRequestP95 = finalToBridgeReq.percentile(95),
            bridgeRequestDurationP50 = bridgeDuration.percentile(50),
            bridgeRequestDurationP95 = bridgeDuration.percentile(95),
            bridgeResponseToTtsRequestedP50 = bridgeRespToTtsReq.percentile(50),
            bridgeResponseToTtsRequestedP95 = bridgeRespToTtsReq.percentile(95),
            ttsRequestToStartP50 = ttsReqToStart.percentile(50),
            ttsRequestToStartP95 = ttsReqToStart.percentile(95),
        )
    }
}

data class VoiceDiagnosticsExportSummary(
    val totalSpeechSessions: Int = 0,
    val completedSessions: Int = 0,
    val successfulFinals: Int = 0,
    val errorSessions: Int = 0,
    val routeSuccessCount: Int = 0,
    val routeFailureCount: Int = 0,
    val wrongMicSuspectedCount: Int = 0,
    val speechDetectionRatePercent: Int = 0,
    val routeSuccessRatePercent: Int = 0,
    val averageRouteSettleMs: Double? = null,
    val averageReadyToSpeechStartMs: Double? = null,
    val averageFinalizationDelayMs: Double? = null,
    val peakRmsDb: Float? = null,
    val ttsPlaybackCount: Int = 0,
    val ttsErrorCount: Int = 0,
    val averageTtsStartDelayMs: Double? = null,
    val audioProbeCount: Int = 0,
    val audioProbeSuccessCount: Int = 0,
    val interruptionCount: Int = 0,
    val interruptionTargetMetCount: Int = 0,
    val recentEndpointReasons: List<String> = emptyList(),
    val recentErrorCodes: List<Int> = emptyList(),
    val latencySummary: LatencySummary? = null,
)

data class LatencySummary(
    val gestureReceivedToRouteRequestedP50: Long? = null,
    val gestureReceivedToRouteRequestedP95: Long? = null,
    val routeRequestedToRouteReadyP50: Long? = null,
    val routeRequestedToRouteReadyP95: Long? = null,
    val gestureReceivedToListeningStartedP50: Long? = null,
    val gestureReceivedToListeningStartedP95: Long? = null,
    val recognizerCreateP50: Long? = null,
    val recognizerCreateP95: Long? = null,
    val listeningStartedToReadyP50: Long? = null,
    val listeningStartedToReadyP95: Long? = null,
    val readyToFirstRmsP50: Long? = null,
    val readyToFirstRmsP95: Long? = null,
    val readyToSpeechStartP50: Long? = null,
    val readyToSpeechStartP95: Long? = null,
    val speechStartToPartialP50: Long? = null,
    val speechStartToPartialP95: Long? = null,
    val finalizationDelayP50: Long? = null,
    val finalizationDelayP95: Long? = null,
    val finalTranscriptToBridgeRequestP50: Long? = null,
    val finalTranscriptToBridgeRequestP95: Long? = null,
    val bridgeRequestDurationP50: Long? = null,
    val bridgeRequestDurationP95: Long? = null,
    val bridgeResponseToTtsRequestedP50: Long? = null,
    val bridgeResponseToTtsRequestedP95: Long? = null,
    val ttsRequestToStartP50: Long? = null,
    val ttsRequestToStartP95: Long? = null,
)

private fun percent(numerator: Int, denominator: Int): Int {
    if (denominator <= 0) return 0
    return ((numerator.toDouble() / denominator.toDouble()) * 100).toInt()
}

private fun List<Long>.averageOrNull(): Double? {
    if (isEmpty()) return null
    return average()
}

private fun List<Long>.percentile(p: Int): Long? {
    if (isEmpty()) return null
    val sorted = sorted()
    val index = kotlin.math.ceil((p / 100.0) * sorted.size).toInt().coerceAtMost(sorted.size) - 1
    return sorted.getOrNull(index.coerceAtLeast(0))
}
