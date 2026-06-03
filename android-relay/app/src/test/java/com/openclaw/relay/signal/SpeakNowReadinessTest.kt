package com.openclaw.relay.signal

import com.openclaw.relay.AudioRouteProofState
import com.openclaw.relay.BridgeHealthResponse
import com.openclaw.relay.RelayAudioRouteSnapshot
import com.openclaw.relay.SetupPhase
import com.openclaw.relay.SpeechEndpointReason
import com.openclaw.relay.VoiceProofRun
import com.openclaw.relay.VoiceProofRunSession
import com.openclaw.relay.VoiceProofRunStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakNowReadinessTest {

    private fun healthyBridge(): BridgeHealthResponse = BridgeHealthResponse(
        ok = true,
        brainMode = "local",
        openclawReady = false,
    )

    private fun unhealthyBridge(): BridgeHealthResponse = BridgeHealthResponse(
        ok = false,
        brainMode = "local",
        openclawReady = false,
    )

    @Test
    fun `blocked when service not running`() {
        val result = computeSpeakNowReadiness(
            isServiceRunning = false,
            setupPhase = SetupPhase.COMPLETE_PROVEN,
            listenReadiness = ListenReadiness.READY,
            listenReadinessMessage = "Ready to listen.",
            speechRecognitionAvailable = true,
            ttsReady = true,
            bridgeStatus = "Healthy",
            lastBridgeHealth = healthyBridge(),
            currentDeviceState = null,
            audioRoute = RelayAudioRouteSnapshot(),
            voiceProofRun = VoiceProofRun(),
            phoneMicFallback = false,
        )
        assertEquals(SpeakNowReadiness.BLOCKED, result.readiness)
        assertEquals("service_not_running", result.reason)
    }

    @Test
    fun `blocked when setup not started`() {
        val result = computeSpeakNowReadiness(
            isServiceRunning = true,
            setupPhase = SetupPhase.NOT_STARTED,
            listenReadiness = ListenReadiness.READY,
            listenReadinessMessage = "Ready to listen.",
            speechRecognitionAvailable = true,
            ttsReady = true,
            bridgeStatus = "Healthy",
            lastBridgeHealth = healthyBridge(),
            currentDeviceState = null,
            audioRoute = RelayAudioRouteSnapshot(),
            voiceProofRun = VoiceProofRun(),
            phoneMicFallback = false,
        )
        assertEquals(SpeakNowReadiness.BLOCKED, result.readiness)
        assertEquals("setup_not_started", result.reason)
    }

    @Test
    fun `blocked when setup incomplete`() {
        val result = computeSpeakNowReadiness(
            isServiceRunning = true,
            setupPhase = SetupPhase.PAIRING,
            listenReadiness = ListenReadiness.READY,
            listenReadinessMessage = "Ready to listen.",
            speechRecognitionAvailable = true,
            ttsReady = true,
            bridgeStatus = "Healthy",
            lastBridgeHealth = healthyBridge(),
            currentDeviceState = null,
            audioRoute = RelayAudioRouteSnapshot(),
            voiceProofRun = VoiceProofRun(),
            phoneMicFallback = false,
        )
        assertEquals(SpeakNowReadiness.BLOCKED, result.readiness)
        assertEquals("setup_incomplete", result.reason)
    }

    @Test
    fun `blocked when stt unavailable`() {
        val result = computeSpeakNowReadiness(
            isServiceRunning = true,
            setupPhase = SetupPhase.COMPLETE_PROVEN,
            listenReadiness = ListenReadiness.BLOCKED,
            listenReadinessMessage = "Speech recognition unavailable.",
            speechRecognitionAvailable = false,
            ttsReady = true,
            bridgeStatus = "Healthy",
            lastBridgeHealth = healthyBridge(),
            currentDeviceState = null,
            audioRoute = RelayAudioRouteSnapshot(),
            voiceProofRun = VoiceProofRun(),
            phoneMicFallback = false,
        )
        assertEquals(SpeakNowReadiness.BLOCKED, result.readiness)
        assertEquals("stt_unavailable", result.reason)
    }

    @Test
    fun `blocked when listen readiness blocked`() {
        val result = computeSpeakNowReadiness(
            isServiceRunning = true,
            setupPhase = SetupPhase.COMPLETE_PROVEN,
            listenReadiness = ListenReadiness.BLOCKED,
            listenReadinessMessage = "Earbuds are disconnected.",
            speechRecognitionAvailable = true,
            ttsReady = true,
            bridgeStatus = "Healthy",
            lastBridgeHealth = healthyBridge(),
            currentDeviceState = null,
            audioRoute = RelayAudioRouteSnapshot(),
            voiceProofRun = VoiceProofRun(),
            phoneMicFallback = false,
        )
        assertEquals(SpeakNowReadiness.BLOCKED, result.readiness)
        assertEquals("listen_readiness_blocked", result.reason)
    }

    @Test
    fun `blocked when bridge unhealthy`() {
        val result = computeSpeakNowReadiness(
            isServiceRunning = true,
            setupPhase = SetupPhase.COMPLETE_PROVEN,
            listenReadiness = ListenReadiness.READY,
            listenReadinessMessage = "Ready to listen.",
            speechRecognitionAvailable = true,
            ttsReady = true,
            bridgeStatus = "Unavailable",
            lastBridgeHealth = unhealthyBridge(),
            currentDeviceState = null,
            audioRoute = RelayAudioRouteSnapshot(),
            voiceProofRun = VoiceProofRun(),
            phoneMicFallback = false,
        )
        assertEquals(SpeakNowReadiness.BLOCKED, result.readiness)
        assertEquals("bridge_unhealthy", result.reason)
    }

    @Test
    fun `blocked when proof has blocking failures`() {
        val proofRun = VoiceProofRun(
            status = VoiceProofRunStatus.FAILED,
            sessions = listOf(
                VoiceProofRunSession(
                    sessionId = "fail-1",
                    engineId = "platform",
                    capturedAtMs = System.currentTimeMillis(),
                    endpointReason = SpeechEndpointReason.AUDIO_ERROR,
                    routeState = AudioRouteProofState.ROUTE_BLUETOOTH_SUSPECT,
                    wrongMicSuspected = true,
                )
            ),
        )
        val result = computeSpeakNowReadiness(
            isServiceRunning = true,
            setupPhase = SetupPhase.COMPLETE_PROVEN,
            listenReadiness = ListenReadiness.READY,
            listenReadinessMessage = "Ready to listen.",
            speechRecognitionAvailable = true,
            ttsReady = true,
            bridgeStatus = "Healthy",
            lastBridgeHealth = healthyBridge(),
            currentDeviceState = null,
            audioRoute = RelayAudioRouteSnapshot(),
            voiceProofRun = proofRun,
            phoneMicFallback = false,
        )
        assertEquals(SpeakNowReadiness.BLOCKED, result.readiness)
        assertEquals("blocking_proof_failures", result.reason)
    }

    @Test
    fun `degraded when setup complete degraded`() {
        val result = computeSpeakNowReadiness(
            isServiceRunning = true,
            setupPhase = SetupPhase.COMPLETE_DEGRADED,
            listenReadiness = ListenReadiness.READY,
            listenReadinessMessage = "Ready to listen.",
            speechRecognitionAvailable = true,
            ttsReady = true,
            bridgeStatus = "Healthy",
            lastBridgeHealth = healthyBridge(),
            currentDeviceState = null,
            audioRoute = RelayAudioRouteSnapshot(),
            voiceProofRun = VoiceProofRun(),
            phoneMicFallback = false,
        )
        assertEquals(SpeakNowReadiness.DEGRADED, result.readiness)
        assertEquals("setup_degraded", result.reason)
    }

    @Test
    fun `degraded when listen readiness degraded`() {
        val result = computeSpeakNowReadiness(
            isServiceRunning = true,
            setupPhase = SetupPhase.COMPLETE_PROVEN,
            listenReadiness = ListenReadiness.DEGRADED,
            listenReadinessMessage = "Low battery.",
            speechRecognitionAvailable = true,
            ttsReady = true,
            bridgeStatus = "Healthy",
            lastBridgeHealth = healthyBridge(),
            currentDeviceState = null,
            audioRoute = RelayAudioRouteSnapshot(),
            voiceProofRun = VoiceProofRun(),
            phoneMicFallback = false,
        )
        assertEquals(SpeakNowReadiness.DEGRADED, result.readiness)
        assertEquals("listen_readiness_degraded", result.reason)
    }

    @Test
    fun `degraded when tts not ready`() {
        val result = computeSpeakNowReadiness(
            isServiceRunning = true,
            setupPhase = SetupPhase.COMPLETE_PROVEN,
            listenReadiness = ListenReadiness.READY,
            listenReadinessMessage = "Ready to listen.",
            speechRecognitionAvailable = true,
            ttsReady = false,
            bridgeStatus = "Healthy",
            lastBridgeHealth = healthyBridge(),
            currentDeviceState = null,
            audioRoute = RelayAudioRouteSnapshot(),
            voiceProofRun = VoiceProofRun(),
            phoneMicFallback = false,
        )
        assertEquals(SpeakNowReadiness.DEGRADED, result.readiness)
        assertEquals("tts_not_ready", result.reason)
    }

    @Test
    fun `degraded when proof is stale`() {
        val now = 10_000_000L
        val oldProof = VoiceProofRun(
            status = VoiceProofRunStatus.PASSED,
            finishedAtMs = now - (8L * 24 * 60 * 60 * 1000),
            sessions = listOf(
                VoiceProofRunSession(
                    sessionId = "old-1",
                    engineId = "platform",
                    capturedAtMs = now,
                    endpointReason = SpeechEndpointReason.FINAL,
                    routeState = AudioRouteProofState.ROUTE_BLUETOOTH_ACTIVE,
                    speechDetected = true,
                    finalTranscriptLength = 12,
                )
            ),
        )
        val result = computeSpeakNowReadiness(
            isServiceRunning = true,
            setupPhase = SetupPhase.COMPLETE_PROVEN,
            listenReadiness = ListenReadiness.READY,
            listenReadinessMessage = "Ready to listen.",
            speechRecognitionAvailable = true,
            ttsReady = true,
            bridgeStatus = "Healthy",
            lastBridgeHealth = healthyBridge(),
            currentDeviceState = null,
            audioRoute = RelayAudioRouteSnapshot(),
            voiceProofRun = oldProof,
            phoneMicFallback = false,
            clock = { now },
        )
        assertEquals(SpeakNowReadiness.DEGRADED, result.readiness)
        assertEquals("proof_stale", result.reason)
    }

    @Test
    fun `degraded when device mismatch`() {
        val proofRun = VoiceProofRun(
            status = VoiceProofRunStatus.PASSED,
            sessions = listOf(
                VoiceProofRunSession(
                    sessionId = "match-1",
                    engineId = "platform",
                    capturedAtMs = System.currentTimeMillis(),
                    endpointReason = SpeechEndpointReason.FINAL,
                    routeState = AudioRouteProofState.ROUTE_BLUETOOTH_ACTIVE,
                    routeSelectedDeviceType = "Bluetooth",
                    speechDetected = true,
                    finalTranscriptLength = 12,
                )
            ),
        )
        val result = computeSpeakNowReadiness(
            isServiceRunning = true,
            setupPhase = SetupPhase.COMPLETE_PROVEN,
            listenReadiness = ListenReadiness.READY,
            listenReadinessMessage = "Ready to listen.",
            speechRecognitionAvailable = true,
            ttsReady = true,
            bridgeStatus = "Healthy",
            lastBridgeHealth = healthyBridge(),
            currentDeviceState = null,
            audioRoute = RelayAudioRouteSnapshot(selectedDeviceType = "Wired"),
            voiceProofRun = proofRun,
            phoneMicFallback = false,
        )
        assertEquals(SpeakNowReadiness.DEGRADED, result.readiness)
        assertEquals("device_mismatch", result.reason)
    }

    @Test
    fun `speak now when all conditions met`() {
        val proofRun = VoiceProofRun(
            status = VoiceProofRunStatus.PASSED,
            finishedAtMs = System.currentTimeMillis(),
            sessions = listOf(
                VoiceProofRunSession(
                    sessionId = "good-1",
                    engineId = "platform",
                    capturedAtMs = System.currentTimeMillis(),
                    endpointReason = SpeechEndpointReason.FINAL,
                    routeState = AudioRouteProofState.ROUTE_BLUETOOTH_ACTIVE,
                    routeSelectedDeviceType = "Bluetooth",
                    speechDetected = true,
                    finalTranscriptLength = 12,
                )
            ),
        )
        val result = computeSpeakNowReadiness(
            isServiceRunning = true,
            setupPhase = SetupPhase.COMPLETE_PROVEN,
            listenReadiness = ListenReadiness.READY,
            listenReadinessMessage = "Ready to listen.",
            speechRecognitionAvailable = true,
            ttsReady = true,
            bridgeStatus = "Healthy",
            lastBridgeHealth = healthyBridge(),
            currentDeviceState = null,
            audioRoute = RelayAudioRouteSnapshot(selectedDeviceType = "Bluetooth"),
            voiceProofRun = proofRun,
            phoneMicFallback = false,
        )
        assertEquals(SpeakNowReadiness.SPEAK_NOW, result.readiness)
        assertEquals("ready", result.reason)
    }

    @Test
    fun `speak now when no proof sessions exist`() {
        val result = computeSpeakNowReadiness(
            isServiceRunning = true,
            setupPhase = SetupPhase.COMPLETE_PROVEN,
            listenReadiness = ListenReadiness.READY,
            listenReadinessMessage = "Ready to listen.",
            speechRecognitionAvailable = true,
            ttsReady = true,
            bridgeStatus = "Healthy",
            lastBridgeHealth = healthyBridge(),
            currentDeviceState = null,
            audioRoute = RelayAudioRouteSnapshot(),
            voiceProofRun = VoiceProofRun(),
            phoneMicFallback = false,
        )
        assertEquals(SpeakNowReadiness.SPEAK_NOW, result.readiness)
    }

    @Test
    fun `blocked when calibration required`() {
        val result = computeSpeakNowReadiness(
            isServiceRunning = true,
            setupPhase = SetupPhase.COMPLETE_PROVEN,
            listenReadiness = ListenReadiness.READY,
            listenReadinessMessage = "Ready to listen.",
            speechRecognitionAvailable = true,
            ttsReady = true,
            bridgeStatus = "Healthy",
            lastBridgeHealth = healthyBridge(),
            currentDeviceState = null,
            audioRoute = RelayAudioRouteSnapshot(),
            voiceProofRun = VoiceProofRun(),
            phoneMicFallback = false,
            calibrationRequired = true,
        )
        assertEquals(SpeakNowReadiness.BLOCKED, result.readiness)
        assertEquals("calibration_required", result.reason)
    }

    @Test
    fun `blocked when calibration profile exists but not ready`() {
        val profile = com.openclaw.relay.calibration.EarbudCalibrationProfile(
            profileId = "test",
            deviceModel = "Test",
            deviceAddressHash = "abc",
            phoneModel = "Phone",
            androidVersion = "15",
            providerId = "test",
            calibratedGestures = emptyList(),
        )
        val result = computeSpeakNowReadiness(
            isServiceRunning = true,
            setupPhase = SetupPhase.COMPLETE_PROVEN,
            listenReadiness = ListenReadiness.READY,
            listenReadinessMessage = "Ready to listen.",
            speechRecognitionAvailable = true,
            ttsReady = true,
            bridgeStatus = "Healthy",
            lastBridgeHealth = healthyBridge(),
            currentDeviceState = null,
            audioRoute = RelayAudioRouteSnapshot(),
            voiceProofRun = VoiceProofRun(),
            phoneMicFallback = false,
            calibrationRequired = false,
            calibrationProfile = profile,
        )
        assertEquals(SpeakNowReadiness.BLOCKED, result.readiness)
        assertEquals("calibration_required", result.reason)
    }

    @Test
    fun `speak now when calibration profile is ready`() {
        val profile = com.openclaw.relay.calibration.EarbudCalibrationProfile(
            profileId = "test",
            deviceModel = "Test",
            deviceAddressHash = "abc",
            phoneModel = "Phone",
            androidVersion = "15",
            providerId = "test",
            calibratedGestures = listOf(
                com.openclaw.relay.calibration.CalibratedGesture(
                    requestedGesture = com.openclaw.relay.signal.GestureType.SINGLE_PRESS,
                    confidence = com.openclaw.relay.calibration.CalibrationConfidence.PROVEN,
                )
            ),
            gestureActionMap = com.openclaw.relay.calibration.GestureActionMap(
                mappings = mapOf(
                    com.openclaw.relay.signal.GestureType.SINGLE_PRESS to com.openclaw.relay.calibration.GestureAction.WAKE_AND_LISTEN
                )
            ),
            routeProof = com.openclaw.relay.calibration.RouteProofResult(isSuccess = true),
        )
        val result = computeSpeakNowReadiness(
            isServiceRunning = true,
            setupPhase = SetupPhase.COMPLETE_PROVEN,
            listenReadiness = ListenReadiness.READY,
            listenReadinessMessage = "Ready to listen.",
            speechRecognitionAvailable = true,
            ttsReady = true,
            bridgeStatus = "Healthy",
            lastBridgeHealth = healthyBridge(),
            currentDeviceState = null,
            audioRoute = RelayAudioRouteSnapshot(),
            voiceProofRun = VoiceProofRun(),
            phoneMicFallback = false,
            calibrationRequired = false,
            calibrationProfile = profile,
        )
        assertEquals(SpeakNowReadiness.SPEAK_NOW, result.readiness)
        assertEquals("ready", result.reason)
    }
}
