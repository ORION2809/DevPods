package com.openclaw.relay

enum class VoiceProofRunStatus {
    NOT_STARTED,
    RUNNING,
    PASSED,
    FAILED,
}

data class VoiceProofRunSession(
    val sessionId: String,
    val engineId: String,
    val capturedAtMs: Long,
    val endpointReason: SpeechEndpointReason,
    val routeState: AudioRouteProofState,
    val routeSettleMs: Long? = null,
    val speechDetected: Boolean = false,
    val wrongMicSuspected: Boolean = false,
    val finalTranscriptLength: Int = 0,
    val rmsFrameCount: Int = 0,
    val rmsPeakDb: Float? = null,
    val ttsStopLatencyMs: Long? = null,
) {
    val isCompleted: Boolean
        get() = endpointReason != SpeechEndpointReason.LISTENING

    val routeSucceeded: Boolean
        get() = routeState == AudioRouteProofState.ROUTE_BLUETOOTH_ACTIVE ||
            routeState == AudioRouteProofState.ROUTE_PHONE_MIC

    val sttSucceeded: Boolean
        get() = endpointReason == SpeechEndpointReason.FINAL && speechDetected && finalTranscriptLength > 0

    val sessionSucceeded: Boolean
        get() = isCompleted && routeSucceeded && sttSucceeded && !wrongMicSuspected
}

data class VoiceProofRunSummary(
    val targetSessionCount: Int = VoiceProofRun.DEFAULT_TARGET_SESSION_COUNT,
    val observedSessionCount: Int = 0,
    val completedSessionCount: Int = 0,
    val successfulSessionCount: Int = 0,
    val routeSuccessCount: Int = 0,
    val routeFailureCount: Int = 0,
    val sttSuccessCount: Int = 0,
    val wrongMicSuspectedCount: Int = 0,
    val interruptionTargetMetCount: Int = 0,
    val audioProbeSuccessCount: Int = 0,
    val reliabilityPercent: Int = 0,
    val failureReasons: List<String> = emptyList(),
)

data class VoiceProofRun(
    val runId: String = "",
    val status: VoiceProofRunStatus = VoiceProofRunStatus.NOT_STARTED,
    val targetSessionCount: Int = DEFAULT_TARGET_SESSION_COUNT,
    val startedAtMs: Long? = null,
    val finishedAtMs: Long? = null,
    val sessions: List<VoiceProofRunSession> = emptyList(),
    val ttsInterruptions: List<TtsInterruptionMetrics> = emptyList(),
    val lastAudioProbe: AudioProbeMetrics? = null,
) {
    val summary: VoiceProofRunSummary
        get() = summarizeVoiceProofRun(
            targetSessionCount = targetSessionCount,
            sessions = sessions,
            ttsInterruptions = ttsInterruptions,
            lastAudioProbe = lastAudioProbe,
        )

    fun recordSpeechSession(metrics: SpeechSessionMetrics): VoiceProofRun {
        if (status != VoiceProofRunStatus.RUNNING) {
            return this
        }

        val session = metrics.toVoiceProofRunSession()
        val replaced = sessions
            .filterNot { it.sessionId == session.sessionId }
            .plus(session)
            .takeLast(targetSessionCount)

        val nextStatus = resolveStatus(targetSessionCount, replaced)
        return copy(
            status = nextStatus,
            finishedAtMs = if (nextStatus == VoiceProofRunStatus.RUNNING) null else session.capturedAtMs,
            sessions = replaced,
        )
    }

    fun recordAudioProbe(metrics: AudioProbeMetrics): VoiceProofRun {
        if (status == VoiceProofRunStatus.NOT_STARTED) {
            return this
        }

        return copy(lastAudioProbe = metrics)
    }

    fun recordTtsInterruption(metrics: TtsInterruptionMetrics): VoiceProofRun {
        if (status == VoiceProofRunStatus.NOT_STARTED) {
            return this
        }

        val replaced = ttsInterruptions
            .filterNot { it.interruptionId == metrics.interruptionId }
            .plus(metrics)
            .takeLast(targetSessionCount)
        return copy(ttsInterruptions = replaced)
    }

    companion object {
        const val DEFAULT_TARGET_SESSION_COUNT: Int = 20

        fun start(
            runId: String,
            targetSessionCount: Int = DEFAULT_TARGET_SESSION_COUNT,
            startedAtMs: Long,
        ): VoiceProofRun =
            VoiceProofRun(
                runId = runId,
                status = VoiceProofRunStatus.RUNNING,
                targetSessionCount = targetSessionCount.coerceAtLeast(1),
                startedAtMs = startedAtMs,
            )
    }
}

fun SpeechSessionMetrics.toVoiceProofRunSession(): VoiceProofRunSession {
    val vad = toPlatformVadObservation()
    return VoiceProofRunSession(
        sessionId = sessionId,
        engineId = engineId,
        capturedAtMs = listOfNotNull(finalAtMs, errorAtMs, interruptedAtMs, ttsDoneAtMs, listeningStartedAtMs, startedAtMs)
            .maxOrNull() ?: startedAtMs,
        endpointReason = endpointReason,
        routeState = routeProof.routeState,
        routeSettleMs = routeSettleMs,
        speechDetected = vad.speechDetected,
        wrongMicSuspected = vad.wrongMicSuspected,
        finalTranscriptLength = finalTranscriptLength,
        rmsFrameCount = rmsFrameCount,
        rmsPeakDb = rmsPeakDb,
        ttsStopLatencyMs = null,
    )
}

private fun resolveStatus(
    targetSessionCount: Int,
    sessions: List<VoiceProofRunSession>,
): VoiceProofRunStatus {
    val completed = sessions.filter { it.isCompleted }
    if (completed.size < targetSessionCount) {
        return VoiceProofRunStatus.RUNNING
    }

    return if (completed.all { it.sessionSucceeded }) {
        VoiceProofRunStatus.PASSED
    } else {
        VoiceProofRunStatus.FAILED
    }
}

private fun summarizeVoiceProofRun(
    targetSessionCount: Int,
    sessions: List<VoiceProofRunSession>,
    ttsInterruptions: List<TtsInterruptionMetrics>,
    lastAudioProbe: AudioProbeMetrics?,
): VoiceProofRunSummary {
    val completed = sessions.filter { it.isCompleted }
    val routeFailureCount = completed.count { !it.routeSucceeded }
    val wrongMicCount = completed.count { it.wrongMicSuspected }
    val sttFailureCount = completed.count { !it.sttSucceeded }
    val failureReasons = buildSet {
        if (routeFailureCount > 0) add("route_failed")
        if (wrongMicCount > 0) add("wrong_mic_suspected")
        if (sttFailureCount > 0) add("stt_failed")
        if (lastAudioProbe != null && lastAudioProbe.initStatus != AudioProbeInitStatus.STARTED) {
            add("audio_probe_${lastAudioProbe.initStatus.name.lowercase()}")
        }
    }.toList()

    return VoiceProofRunSummary(
        targetSessionCount = targetSessionCount,
        observedSessionCount = sessions.size,
        completedSessionCount = completed.size,
        successfulSessionCount = completed.count { it.sessionSucceeded },
        routeSuccessCount = completed.count { it.routeSucceeded },
        routeFailureCount = routeFailureCount,
        sttSuccessCount = completed.count { it.sttSucceeded },
        wrongMicSuspectedCount = wrongMicCount,
        interruptionTargetMetCount = ttsInterruptions.count { it.targetMet },
        audioProbeSuccessCount = if (lastAudioProbe?.initStatus == AudioProbeInitStatus.STARTED) 1 else 0,
        reliabilityPercent = if (targetSessionCount == 0) {
            0
        } else {
            (completed.count { it.sessionSucceeded } * 100) / targetSessionCount
        },
        failureReasons = failureReasons,
    )
}
