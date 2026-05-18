package com.openclaw.relay

enum class SpeechEndpointReason {
    LISTENING,
    FINAL,
    NO_SPEECH,
    TIMEOUT,
    RECOGNIZER_BUSY,
    AUDIO_ERROR,
    CLIENT_ERROR,
    NETWORK_ERROR,
    PERMISSION_DENIED,
    ROUTE_FAILED,
    STOP_REQUESTED,
    CANCELLED,
    UNKNOWN_ERROR,
}

data class SpeechSessionMetrics(
    val sessionId: String,
    val engineId: String,
    val startedAtMs: Long,
    val wakeSignal: String? = null,
    val routeRequestedAtMs: Long? = null,
    val routeReadyAtMs: Long? = null,
    val routeProof: AudioRouteProof = AudioRouteProof(),
    val recognizerCreatedAtMs: Long? = null,
    val listeningStartedAtMs: Long? = null,
    val readyForSpeechAtMs: Long? = null,
    val beginSpeechAtMs: Long? = null,
    val firstRmsAtMs: Long? = null,
    val rmsFrameCount: Int = 0,
    val rmsPeakDb: Float? = null,
    val endSpeechAtMs: Long? = null,
    val firstPartialAtMs: Long? = null,
    val partialCount: Int = 0,
    val finalAtMs: Long? = null,
    val errorAtMs: Long? = null,
    val errorCode: Int? = null,
    val endpointReason: SpeechEndpointReason = SpeechEndpointReason.LISTENING,
    val finalTranscriptLength: Int = 0,
    val finalTranscriptPreview: String? = null,
    val ttsStartAtMs: Long? = null,
    val ttsDoneAtMs: Long? = null,
    val interruptedAtMs: Long? = null,
    val privacyLevel: String = "no_raw_audio_no_transcript",
) {
    val routeSettleMs: Long?
        get() = routeRequestedAtMs?.let { requested ->
            routeReadyAtMs?.let { ready -> (ready - requested).coerceAtLeast(0L) }
        } ?: routeProof.routeSettleMs

    val readyToFirstRmsMs: Long?
        get() = readyForSpeechAtMs?.let { ready -> firstRmsAtMs?.let { it - ready } }

    val readyToSpeechStartMs: Long?
        get() = readyForSpeechAtMs?.let { ready -> beginSpeechAtMs?.let { it - ready } }

    val speechStartToPartialMs: Long?
        get() = beginSpeechAtMs?.let { begin -> firstPartialAtMs?.let { it - begin } }

    val finalizationDelayMs: Long?
        get() = endSpeechAtMs?.let { end -> finalAtMs?.let { it - end } }

    val totalSessionMs: Long?
        get() = listOfNotNull(finalAtMs, errorAtMs, interruptedAtMs, ttsDoneAtMs).maxOrNull()?.let { it - startedAtMs }
}

data class PlatformVadObservation(
    val speechDetected: Boolean = false,
    val rmsPeakDb: Float? = null,
    val rmsFrameCount: Int = 0,
    val speechStartDelayMs: Long? = null,
    val partialAfterSpeechStartMs: Long? = null,
    val speechEndDelayMs: Long? = null,
    val finalizationDelayMs: Long? = null,
    val endpointReason: SpeechEndpointReason = SpeechEndpointReason.LISTENING,
    val wrongMicSuspected: Boolean = false,
)

data class VoiceDiagnosticsSnapshot(
    val lastSpeechSession: SpeechSessionMetrics? = null,
    val lastVadObservation: PlatformVadObservation? = null,
    val lastTtsPlayback: TtsPlaybackMetrics? = null,
    val lastTtsInterruption: TtsInterruptionMetrics? = null,
    val lastAudioProbe: AudioProbeMetrics? = null,
    val offlineSpeechReadiness: OfflineSpeechReadiness = OfflineSpeechReadiness.evaluate(RelayConfig()),
    val lastMediaButtonEvent: MediaButtonEventTelemetry? = null,
    val foregroundControls: ForegroundControlSnapshot = ForegroundControlSnapshot(),
    val foregroundService: RelayForegroundServiceSnapshot = RelayForegroundServiceSnapshot(),
    val voiceProofRun: VoiceProofRun = VoiceProofRun(),
)

class SpeechSessionMetricsRecorder(
    sessionId: String,
    engineId: String,
    startedAtMs: Long,
    wakeSignal: String? = null,
) {
    private var metrics = SpeechSessionMetrics(
        sessionId = sessionId,
        engineId = engineId,
        startedAtMs = startedAtMs,
        wakeSignal = wakeSignal,
    )

    fun markRouteRequested(nowMs: Long) {
        metrics = metrics.copy(routeRequestedAtMs = nowMs)
    }

    fun markRouteReady(nowMs: Long, routeSnapshot: RelayAudioRouteSnapshot) {
        metrics = metrics.copy(
            routeReadyAtMs = nowMs,
            routeProof = routeSnapshot.proof,
        )
    }

    fun markRecognizerCreated(nowMs: Long) {
        metrics = metrics.copy(recognizerCreatedAtMs = nowMs)
    }

    fun markListeningStarted(nowMs: Long) {
        metrics = metrics.copy(listeningStartedAtMs = nowMs)
    }

    fun markReadyForSpeech(nowMs: Long) {
        metrics = metrics.copy(readyForSpeechAtMs = nowMs)
    }

    fun markBeginningOfSpeech(nowMs: Long) {
        metrics = metrics.copy(beginSpeechAtMs = nowMs)
    }

    fun markRmsChanged(nowMs: Long, rmsDb: Float) {
        metrics = metrics.copy(
            firstRmsAtMs = metrics.firstRmsAtMs ?: nowMs,
            rmsFrameCount = metrics.rmsFrameCount + 1,
            rmsPeakDb = maxOf(metrics.rmsPeakDb ?: rmsDb, rmsDb),
        )
    }

    fun markEndOfSpeech(nowMs: Long) {
        metrics = metrics.copy(endSpeechAtMs = nowMs)
    }

    fun markPartial(nowMs: Long, transcript: String) {
        if (transcript.isBlank()) {
            return
        }

        metrics = metrics.copy(
            firstPartialAtMs = metrics.firstPartialAtMs ?: nowMs,
            partialCount = metrics.partialCount + 1,
        )
    }

    fun markFinal(nowMs: Long, transcript: String) {
        metrics = metrics.copy(
            finalAtMs = nowMs,
            endpointReason = SpeechEndpointReason.FINAL,
            finalTranscriptLength = transcript.length,
            // Privacy rule: never persist transcript text in metrics by default.
            finalTranscriptPreview = null,
        )
    }

    fun markError(nowMs: Long, errorCode: Int?, reason: SpeechEndpointReason) {
        metrics = metrics.copy(
            errorAtMs = nowMs,
            errorCode = errorCode,
            endpointReason = reason,
        )
    }

    fun markTtsStarted(nowMs: Long) {
        metrics = metrics.copy(ttsStartAtMs = nowMs)
    }

    fun markTtsDone(nowMs: Long) {
        metrics = metrics.copy(ttsDoneAtMs = nowMs)
    }

    fun markInterrupted(nowMs: Long, reason: SpeechEndpointReason = SpeechEndpointReason.CANCELLED) {
        metrics = metrics.copy(
            interruptedAtMs = nowMs,
            endpointReason = reason,
        )
    }

    fun snapshot(): SpeechSessionMetrics = metrics
}

fun SpeechSessionMetrics.toPlatformVadObservation(): PlatformVadObservation {
    val speechDetected = beginSpeechAtMs != null || firstPartialAtMs != null || finalAtMs != null
    val wrongMicSuspected = routeProof.routeState == AudioRouteProofState.ROUTE_BLUETOOTH_ACTIVE &&
        !speechDetected &&
        (rmsFrameCount == 0 || (rmsPeakDb ?: Float.NEGATIVE_INFINITY) < -45f)

    return PlatformVadObservation(
        speechDetected = speechDetected,
        rmsPeakDb = rmsPeakDb,
        rmsFrameCount = rmsFrameCount,
        speechStartDelayMs = readyToSpeechStartMs,
        partialAfterSpeechStartMs = speechStartToPartialMs,
        speechEndDelayMs = beginSpeechAtMs?.let { begin -> endSpeechAtMs?.let { it - begin } },
        finalizationDelayMs = finalizationDelayMs,
        endpointReason = endpointReason,
        wrongMicSuspected = wrongMicSuspected,
    )
}

enum class TtsPlaybackEvent {
    REQUESTED,
    STARTED,
    DONE,
    STOPPED,
    ERROR,
}

data class TtsPlaybackMetrics(
    val utteranceId: String,
    val textLength: Int,
    val requestedAtMs: Long,
    val startedAtMs: Long? = null,
    val completedAtMs: Long? = null,
    val stoppedAtMs: Long? = null,
    val stopRequestedAtMs: Long? = null,
    val errorAtMs: Long? = null,
    val errorCode: Int? = null,
    val event: TtsPlaybackEvent = TtsPlaybackEvent.REQUESTED,
) {
    val startDelayMs: Long?
        get() = startedAtMs?.let { it - requestedAtMs }

    val playbackDurationMs: Long?
        get() = startedAtMs?.let { started ->
            (completedAtMs ?: stoppedAtMs ?: errorAtMs)?.let { it - started }
        }

    val stopLatencyMs: Long?
        get() = stopRequestedAtMs?.let { requested -> stoppedAtMs?.let { it - requested } }
}

class TtsPlaybackMetricsRecorder(
    utteranceId: String,
    textLength: Int,
    requestedAtMs: Long,
) {
    private var metrics = TtsPlaybackMetrics(
        utteranceId = utteranceId,
        textLength = textLength,
        requestedAtMs = requestedAtMs,
    )

    fun markStarted(nowMs: Long) {
        metrics = metrics.copy(startedAtMs = nowMs, event = TtsPlaybackEvent.STARTED)
    }

    fun markDone(nowMs: Long) {
        metrics = metrics.copy(completedAtMs = nowMs, event = TtsPlaybackEvent.DONE)
    }

    fun markStopped(nowMs: Long, requestedAtMs: Long) {
        metrics = metrics.copy(
            stoppedAtMs = nowMs,
            stopRequestedAtMs = requestedAtMs,
            event = TtsPlaybackEvent.STOPPED,
        )
    }

    fun markError(nowMs: Long, errorCode: Int?) {
        metrics = metrics.copy(errorAtMs = nowMs, errorCode = errorCode, event = TtsPlaybackEvent.ERROR)
    }

    fun snapshot(): TtsPlaybackMetrics = metrics
}
