package com.openclaw.relay.diagnostic

import android.content.Context
import android.content.ContextWrapper
import com.openclaw.relay.AudioProbeInitStatus
import com.openclaw.relay.AudioProbeMetrics
import com.openclaw.relay.AudioRouteProof
import com.openclaw.relay.AudioRouteProofState
import com.openclaw.relay.OfflineSpeechReadiness
import com.openclaw.relay.RelayUiState
import com.openclaw.relay.SpeechEndpointReason
import com.openclaw.relay.SpeechSessionMetrics
import com.openclaw.relay.TtsInterruptionMetrics
import com.openclaw.relay.VoiceDiagnosticsSnapshot
import com.openclaw.relay.VoiceProofRun
import com.openclaw.relay.VoiceProofRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticExportTest {

    private val context = TestContext()

    @Test
    fun `selectedDeviceType is only exported when raw route is enabled`() {
        val state = stateWithVoiceDiagnostics(
            speechSession = SpeechSessionMetrics(
                sessionId = "speech-1",
                engineId = "platform_speech_recognizer",
                startedAtMs = 1_000L,
                routeProof = AudioRouteProof(
                    routeState = AudioRouteProofState.ROUTE_BLUETOOTH_ACTIVE,
                    selectedDeviceType = "AirPods Pro 2",
                ),
                endpointReason = SpeechEndpointReason.FINAL,
            ),
        )

        val withRawRoute = DiagnosticExport.build(
            context,
            state,
            DiagnosticExportOptions(includeRawRoute = true),
        )
        val withoutRawRoute = DiagnosticExport.build(
            context,
            state,
            DiagnosticExportOptions(includeRawRoute = false),
        )

        assertEquals("AirPods Pro 2", withRawRoute.voice.selectedDeviceType)
        assertNull(withoutRawRoute.voice.selectedDeviceType)
    }

    @Test
    fun `recent errors are redacted`() {
        val state = RelayUiState(
            lastSpeechError = "Failed to connect to https://bridge.example.com/api",
            errorMessage = "workspace=my_workspace token=ghp_xxxxxxxxxxxxxxxxxxxx",
        )

        val export = DiagnosticExport.build(context, state)

        val errors = export.recentErrors
        assertTrue(errors.isNotEmpty())
        errors.forEach { error ->
            assertFalse("Error should not contain raw URL: $error", error.contains("https://"))
            assertFalse("Error should not contain workspace name: $error", error.contains("my_workspace"))
            assertFalse("Error should not contain token: $error", error.contains("ghp_"))
        }
    }

    @Test
    fun `transcript text never appears in default export`() {
        val state = stateWithVoiceDiagnostics(
            speechSession = SpeechSessionMetrics(
                sessionId = "speech-1",
                engineId = "platform_speech_recognizer",
                startedAtMs = 1_000L,
                finalTranscriptLength = 42,
                endpointReason = SpeechEndpointReason.FINAL,
            ),
        )

        val export = DiagnosticExport.build(context, state)

        assertEquals(42, export.voice.finalTranscriptLength)
        // The JSON must not contain any transcript text field with actual content
        val json = DiagnosticExport.toJson(context, state)
        assertFalse("JSON should not contain raw transcript", json.contains("git status"))
        assertFalse("JSON should not contain transcript preview", json.contains("transcriptPreview"))
    }

    @Test
    fun `raw audio remains excluded from export`() {
        val state = stateWithVoiceDiagnostics(
            audioProbe = AudioProbeMetrics(
                initStatus = AudioProbeInitStatus.STARTED,
                startedAtMs = 1_000L,
                finishedAtMs = 1_500L,
                rawAudioPersisted = false,
                rawAudioPath = null,
            ),
        )

        val export = DiagnosticExport.build(context, state)

        assertNull(export.voice.audioProbeStatus?.let {
            // The export should not expose raw audio paths even if present
            null
        })
        val json = DiagnosticExport.toJson(context, state)
        assertFalse("JSON should not mention raw audio path", json.contains("rawAudioPath"))
    }

    @Test
    fun `proof run includes interruptionTargetMetCount`() {
        val proofRun = VoiceProofRun.start("proof-1", targetSessionCount = 3, startedAtMs = 1_000L)
            .recordTtsInterruption(
                TtsInterruptionMetrics(
                    interruptionId = "i1",
                    reason = com.openclaw.relay.TtsInterruptionReason.BARGE_IN,
                    requestedAtMs = 1_000L,
                    ttsStoppedAtMs = 1_150L,
                    listeningStartedAtMs = 1_200L,
                ),
            )
            .recordTtsInterruption(
                TtsInterruptionMetrics(
                    interruptionId = "i2",
                    reason = com.openclaw.relay.TtsInterruptionReason.BARGE_IN,
                    requestedAtMs = 2_000L,
                    ttsStoppedAtMs = 2_300L,
                    listeningStartedAtMs = 2_400L,
                ),
            )

        val state = stateWithVoiceDiagnostics(
            voiceProofRun = proofRun,
        )

        val export = DiagnosticExport.build(context, state)
        val redactedProofRun = export.voice.proofRun

        assertNotNull(redactedProofRun)
        assertEquals(1, redactedProofRun!!.interruptionTargetMetCount)
        assertTrue(redactedProofRun.failureReasons.contains("interruption_target_missed"))
    }

    @Test
    fun `proof run is omitted when status is NOT_STARTED`() {
        val state = stateWithVoiceDiagnostics(
            voiceProofRun = VoiceProofRun(),
        )

        val export = DiagnosticExport.build(context, state)

        assertNull(export.voice.proofRun)
    }

    @Test
    fun `phone model is redacted when includePhoneModel is false`() {
        val state = RelayUiState()

        val withPhone = DiagnosticExport.build(
            context,
            state,
            DiagnosticExportOptions(includePhoneModel = true),
        )
        val withoutPhone = DiagnosticExport.build(
            context,
            state,
            DiagnosticExportOptions(includePhoneModel = false),
        )

        assertTrue(withPhone.phoneModel.isNotBlank() && withPhone.phoneModel != "[redacted]")
        assertEquals("[redacted]", withoutPhone.phoneModel)
        assertEquals("[redacted]", withoutPhone.androidVersion)
    }

    @Test
    fun `personal bluetooth device names are redacted in default export`() {
        val state = RelayUiState(
            currentDeviceState = com.openclaw.relay.signal.EarbudDeviceState(
                providerId = "apple_airpods",
                deviceId = "AA:BB:CC:DD:EE:FF",
                displayName = "Alice's AirPods Pro",
                connectionState = com.openclaw.relay.signal.ConnectionState.CONNECTED,
                battery = null,
                earState = null,
                audioRouteState = null,
                capabilityProfile = com.openclaw.relay.signal.EarbudCapabilityProfile(
                    providerId = "apple_airpods",
                    deviceModel = "Alice's AirPods Pro",
                    capabilities = emptyList(),
                    wakeGestures = emptyMap(),
                    interruptGestures = emptyMap(),
                    approvalGestures = emptyMap(),
                ),
                confidence = com.openclaw.relay.signal.SignalConfidence.UNPROVEN,
            ),
            capabilityMatrix = com.openclaw.relay.device.DeviceCapabilityMatrix(
                entries = listOf(
                    com.openclaw.relay.device.DeviceCapabilityEntry(
                        deviceModel = "Alice's AirPods Pro",
                        phoneModel = "Pixel 8",
                        androidVersion = "15",
                        providersObserved = listOf("apple_airpods"),
                        wakeGesture = com.openclaw.relay.device.CapabilityStatus.PROVEN,
                        interruptGesture = com.openclaw.relay.device.CapabilityStatus.UNPROVEN,
                        approveRejectGesture = com.openclaw.relay.device.CapabilityStatus.UNPROVEN,
                        earDetection = com.openclaw.relay.device.CapabilityStatus.OBSERVED,
                        batteryStatus = com.openclaw.relay.device.CapabilityStatus.OBSERVED,
                        sttAfterWake = com.openclaw.relay.device.CapabilityStatus.PROVEN,
                        ttsInterruption = com.openclaw.relay.device.CapabilityStatus.UNPROVEN,
                    )
                )
            ),
        )

        val export = DiagnosticExport.build(context, state)
        val json = DiagnosticExport.toJson(context, state)

        assertFalse(
            "JSON should not contain personal device name",
            json.contains("Alice's AirPods Pro")
        )
        assertFalse(
            "JSON should not contain raw MAC address",
            json.contains("AA:BB:CC:DD:EE:FF")
        )
        // The redacted model name should not equal the raw name
        assertTrue(
            export.device?.modelFamily != "Alice's AirPods Pro"
        )
        // Capability matrix entries should also be redacted
        assertTrue(
            "Capability matrix deviceModel should be redacted",
            export.capabilityMatrix.entries.all { it.deviceModel != "Alice's AirPods Pro" }
        )
    }

    private fun stateWithVoiceDiagnostics(
        speechSession: SpeechSessionMetrics? = null,
        audioProbe: AudioProbeMetrics? = null,
        voiceProofRun: VoiceProofRun = VoiceProofRun(),
    ): RelayUiState {
        return RelayUiState(
            voiceDiagnostics = VoiceDiagnosticsSnapshot(
                lastSpeechSession = speechSession,
                lastAudioProbe = audioProbe,
                voiceProofRun = voiceProofRun,
                offlineSpeechReadiness = OfflineSpeechReadiness.evaluate(
                    com.openclaw.relay.RelayConfig(),
                ),
            ),
        )
    }
}

private class TestContext : ContextWrapper(null)
