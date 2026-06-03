package com.openclaw.relay.signal

import com.openclaw.relay.BridgeHealthResponse
import com.openclaw.relay.RelayAudioRouteSnapshot
import com.openclaw.relay.SetupPhase
import com.openclaw.relay.VoiceProofRun

enum class SpeakNowReadiness {
    SPEAK_NOW,
    DEGRADED,
    BLOCKED,
}

data class SpeakNowReadinessDetail(
    val readiness: SpeakNowReadiness,
    val userFacingMessage: String,
    val reason: String,
)

private const val MAX_PROOF_AGE_MS = 7L * 24 * 60 * 60 * 1000 // 7 days

fun computeSpeakNowReadiness(
    isServiceRunning: Boolean,
    setupPhase: SetupPhase,
    listenReadiness: ListenReadiness,
    listenReadinessMessage: String,
    speechRecognitionAvailable: Boolean,
    ttsReady: Boolean,
    bridgeStatus: String,
    lastBridgeHealth: BridgeHealthResponse?,
    currentDeviceState: EarbudDeviceState?,
    audioRoute: RelayAudioRouteSnapshot,
    voiceProofRun: VoiceProofRun,
    phoneMicFallback: Boolean,
    calibrationRequired: Boolean = false,
    calibrationProfile: com.openclaw.relay.calibration.EarbudCalibrationProfile? = null,
    clock: () -> Long = System::currentTimeMillis,
): SpeakNowReadinessDetail {
    if (!isServiceRunning) {
        return SpeakNowReadinessDetail(
            readiness = SpeakNowReadiness.BLOCKED,
            userFacingMessage = "Relay service is not running.",
            reason = "service_not_running",
        )
    }

    if (setupPhase == SetupPhase.NOT_STARTED) {
        return SpeakNowReadinessDetail(
            readiness = SpeakNowReadiness.BLOCKED,
            userFacingMessage = "Complete setup to start using DevPods.",
            reason = "setup_not_started",
        )
    }

    if (setupPhase == SetupPhase.PAIRING ||
        setupPhase == SetupPhase.DEVICE_PROBE ||
        setupPhase == SetupPhase.CALIBRATION ||
        setupPhase == SetupPhase.GESTURE_TEST ||
        setupPhase == SetupPhase.STT_TEST
    ) {
        return SpeakNowReadinessDetail(
            readiness = SpeakNowReadiness.BLOCKED,
            userFacingMessage = "Setup is in progress. Finish the setup wizard first.",
            reason = "setup_incomplete",
        )
    }

    if (!speechRecognitionAvailable) {
        return SpeakNowReadinessDetail(
            readiness = SpeakNowReadiness.BLOCKED,
            userFacingMessage = "Speech recognition is not available on this device.",
            reason = "stt_unavailable",
        )
    }

    if (listenReadiness == ListenReadiness.BLOCKED) {
        return SpeakNowReadinessDetail(
            readiness = SpeakNowReadiness.BLOCKED,
            userFacingMessage = listenReadinessMessage,
            reason = "listen_readiness_blocked",
        )
    }

    val bridgeHealthy = bridgeStatus.startsWith("Healthy", ignoreCase = true) &&
        (lastBridgeHealth?.ok != false)
    if (!bridgeHealthy) {
        return SpeakNowReadinessDetail(
            readiness = SpeakNowReadiness.BLOCKED,
            userFacingMessage = "Desktop bridge is not reachable. Check your connection.",
            reason = "bridge_unhealthy",
        )
    }

    if (voiceProofRun.summary.hasBlockingFailures) {
        return SpeakNowReadinessDetail(
            readiness = SpeakNowReadiness.BLOCKED,
            userFacingMessage = "Voice proof has blocking failures. Re-run setup to resolve.",
            reason = "blocking_proof_failures",
        )
    }

    if (setupPhase == SetupPhase.COMPLETE_DEGRADED) {
        return SpeakNowReadinessDetail(
            readiness = SpeakNowReadiness.DEGRADED,
            userFacingMessage = "Setup completed with degraded status. Some features may be limited.",
            reason = "setup_degraded",
        )
    }

    if (calibrationRequired || (calibrationProfile != null && !calibrationProfile.isReadyForRuntime())) {
        return SpeakNowReadinessDetail(
            readiness = SpeakNowReadiness.BLOCKED,
            userFacingMessage = "Earbud calibration incomplete. Run setup to calibrate your gestures.",
            reason = "calibration_required",
        )
    }

    if (listenReadiness == ListenReadiness.DEGRADED) {
        return SpeakNowReadinessDetail(
            readiness = SpeakNowReadiness.DEGRADED,
            userFacingMessage = listenReadinessMessage,
            reason = "listen_readiness_degraded",
        )
    }

    if (!ttsReady) {
        return SpeakNowReadinessDetail(
            readiness = SpeakNowReadiness.DEGRADED,
            userFacingMessage = "Text-to-speech is not ready. Responses will be displayed only.",
            reason = "tts_not_ready",
        )
    }

    val proofFinishedAtMs = voiceProofRun.finishedAtMs
    if (proofFinishedAtMs != null) {
        val proofAgeMs = clock() - proofFinishedAtMs
        if (proofAgeMs > MAX_PROOF_AGE_MS) {
            return SpeakNowReadinessDetail(
                readiness = SpeakNowReadiness.DEGRADED,
                userFacingMessage = "Voice proof results are stale. Consider re-running setup.",
                reason = "proof_stale",
            )
        }
    }

    val lastProvenDeviceType = voiceProofRun.sessions
        .lastOrNull { it.routeSucceeded }
        ?.routeSelectedDeviceType
    val currentDeviceType = audioRoute.selectedDeviceType
    if (lastProvenDeviceType != null && currentDeviceType != null &&
        !lastProvenDeviceType.equals(currentDeviceType, ignoreCase = true)
    ) {
        return SpeakNowReadinessDetail(
            readiness = SpeakNowReadiness.DEGRADED,
            userFacingMessage = "Current audio device differs from the proven device. Performance may vary.",
            reason = "device_mismatch",
        )
    }

    return SpeakNowReadinessDetail(
        readiness = SpeakNowReadiness.SPEAK_NOW,
        userFacingMessage = "Ready to listen.",
        reason = "ready",
    )
}
