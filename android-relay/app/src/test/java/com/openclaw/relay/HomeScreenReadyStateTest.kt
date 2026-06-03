package com.openclaw.relay

import com.openclaw.relay.device.CapabilityStatus
import com.openclaw.relay.device.DeviceCapabilityEntry
import com.openclaw.relay.device.DeviceCapabilityMatrix
import com.openclaw.relay.signal.SpeakNowReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeScreenReadyStateTest {

    private fun createState(
        isServiceRunning: Boolean = true,
        bridgeStatus: String = "Healthy",
        setupPhase: SetupPhase = SetupPhase.COMPLETE_PROVEN,
        hasBlockingFailures: Boolean = false,
    ): RelayUiState {
        val sessions = if (hasBlockingFailures) {
            listOf(
                VoiceProofRunSession(
                    sessionId = "fail-1",
                    engineId = "platform",
                    capturedAtMs = System.currentTimeMillis(),
                    endpointReason = SpeechEndpointReason.AUDIO_ERROR,
                    routeState = AudioRouteProofState.ROUTE_BLUETOOTH_SUSPECT,
                    wrongMicSuspected = true,
                )
            )
        } else {
            emptyList()
        }
        return RelayUiState(
            isServiceRunning = isServiceRunning,
            bridgeStatus = bridgeStatus,
            speechRecognitionAvailable = true,
            ttsReady = true,
            setupPhase = setupPhase,
            listenReadiness = com.openclaw.relay.signal.ListenReadiness.READY,
            listenReadinessMessage = "Ready to listen.",
            lastBridgeHealth = BridgeHealthResponse(
                ok = bridgeStatus.startsWith("Healthy", ignoreCase = true),
                brainMode = "local",
                openclawReady = false,
            ),
            voiceDiagnostics = VoiceDiagnosticsSnapshot(
                voiceProofRun = VoiceProofRun(
                    status = if (hasBlockingFailures) VoiceProofRunStatus.FAILED else VoiceProofRunStatus.PASSED,
                    sessions = sessions,
                )
            ),
            capabilityMatrix = DeviceCapabilityMatrix(
                entries = listOf(
                    DeviceCapabilityEntry(
                        deviceModel = "Test",
                        phoneModel = "Phone",
                        androidVersion = "15",
                        providersObserved = emptyList(),
                        wakeGesture = CapabilityStatus.PROVEN,
                        interruptGesture = CapabilityStatus.UNPROVEN,
                        approveRejectGesture = CapabilityStatus.UNPROVEN,
                        earDetection = CapabilityStatus.UNPROVEN,
                        batteryStatus = CapabilityStatus.UNPROVEN,
                        sttAfterWake = CapabilityStatus.UNPROVEN,
                        ttsInterruption = CapabilityStatus.UNPROVEN,
                    )
                )
            )
        )
    }

    @Test
    fun `ready state is speak now when service running bridge healthy and proof passed`() {
        val state = createState()
        assertEquals(SpeakNowReadiness.SPEAK_NOW, state.speakNowReadiness.readiness)
        val isReady = state.speakNowReadiness.readiness != SpeakNowReadiness.BLOCKED
        assertTrue(isReady)
    }

    @Test
    fun `ready state is degraded when setup is degraded`() {
        val state = createState(setupPhase = SetupPhase.COMPLETE_DEGRADED)
        assertEquals(SpeakNowReadiness.DEGRADED, state.speakNowReadiness.readiness)
        val isReady = state.speakNowReadiness.readiness != SpeakNowReadiness.BLOCKED
        assertTrue(isReady)
    }

    @Test
    fun `ready state is blocked when proof has blocking failures`() {
        val state = createState(hasBlockingFailures = true)
        assertEquals(SpeakNowReadiness.BLOCKED, state.speakNowReadiness.readiness)
        val isReady = state.speakNowReadiness.readiness != SpeakNowReadiness.BLOCKED
        assertFalse(isReady)
    }

    @Test
    fun `ready state is blocked when bridge is unhealthy`() {
        val state = createState(bridgeStatus = "Unavailable")
        assertEquals(SpeakNowReadiness.BLOCKED, state.speakNowReadiness.readiness)
        val isReady = state.speakNowReadiness.readiness != SpeakNowReadiness.BLOCKED
        assertFalse(isReady)
    }

    @Test
    fun `ready state is blocked when service is not running`() {
        val state = createState(isServiceRunning = false)
        assertEquals(SpeakNowReadiness.BLOCKED, state.speakNowReadiness.readiness)
        val isReady = state.speakNowReadiness.readiness != SpeakNowReadiness.BLOCKED
        assertFalse(isReady)
    }
}
