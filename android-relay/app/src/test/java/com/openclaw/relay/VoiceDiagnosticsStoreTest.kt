package com.openclaw.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceDiagnosticsStoreTest {

    @Test
    fun `records speech sessions up to max window`() {
        val store = VoiceDiagnosticsStore(maxSpeechSessions = 3)

        store.recordSpeechSession(speechMetrics("speech-1"))
        store.recordSpeechSession(speechMetrics("speech-2"))
        store.recordSpeechSession(speechMetrics("speech-3"))
        store.recordSpeechSession(speechMetrics("speech-4"))

        val recent = store.recentSpeechSessions()
        assertEquals(3, recent.size)
        assertEquals("speech-2", recent[0].sessionId)
        assertEquals("speech-4", recent[2].sessionId)
    }

    @Test
    fun `export summary counts finals errors and routes`() {
        val store = VoiceDiagnosticsStore()

        store.recordSpeechSession(
            speechMetrics("speech-1").copy(
                finalAtMs = 1_500L,
                endpointReason = SpeechEndpointReason.FINAL,
            ),
        )
        store.recordSpeechSession(
            speechMetrics("speech-2").copy(
                errorAtMs = 1_800L,
                errorCode = 7,
                endpointReason = SpeechEndpointReason.NO_SPEECH,
            ),
        )
        store.recordSpeechSession(
            speechMetrics("speech-3").copy(
                routeProof = AudioRouteProof(routeState = AudioRouteProofState.ROUTE_FAILED),
                errorAtMs = 2_100L,
                endpointReason = SpeechEndpointReason.ROUTE_FAILED,
            ),
        )

        val summary = store.exportSummary()
        assertEquals(3, summary.totalSpeechSessions)
        assertEquals(1, summary.successfulFinals)
        assertEquals(2, summary.errorSessions)
        assertEquals(2, summary.routeSuccessCount)
        assertEquals(1, summary.routeFailureCount)
    }

    @Test
    fun `export summary flags wrong mic suspicion from vad`() {
        val store = VoiceDiagnosticsStore()

        store.recordSpeechSession(
            speechMetrics("speech-1").copy(
                routeProof = AudioRouteProof(routeState = AudioRouteProofState.ROUTE_BLUETOOTH_ACTIVE),
                readyForSpeechAtMs = 1_050L,
                beginSpeechAtMs = null,
                firstPartialAtMs = null,
                finalAtMs = null,
                rmsFrameCount = 0,
                rmsPeakDb = null,
                endpointReason = SpeechEndpointReason.NO_SPEECH,
            ),
        )

        val summary = store.exportSummary()
        assertEquals(1, summary.wrongMicSuspectedCount)
    }

    @Test
    fun `clear removes all history`() {
        val store = VoiceDiagnosticsStore()
        store.recordSpeechSession(speechMetrics("speech-1"))
        store.recordTtsPlayback(ttsMetrics("tts-1"))
        store.recordAudioProbe(probeMetrics())
        store.recordTtsInterruption(interruptionMetrics())

        store.clear()

        assertEquals(0, store.speechSessionCount())
        assertEquals(0, store.ttsPlaybackCount())
        assertEquals(0, store.audioProbeCount())
        assertEquals(0, store.interruptionCount())
    }

    @Test
    fun `export summary averages delays`() {
        val store = VoiceDiagnosticsStore()

        store.recordSpeechSession(
            speechMetrics("speech-1").copy(
                routeRequestedAtMs = 1_000L,
                routeReadyAtMs = 1_100L,
                readyForSpeechAtMs = 1_200L,
                beginSpeechAtMs = 1_300L,
                endSpeechAtMs = 1_500L,
                finalAtMs = 1_600L,
                endpointReason = SpeechEndpointReason.FINAL,
            ),
        )
        store.recordSpeechSession(
            speechMetrics("speech-2").copy(
                routeRequestedAtMs = 2_000L,
                routeReadyAtMs = 2_250L,
                readyForSpeechAtMs = 2_300L,
                beginSpeechAtMs = 2_500L,
                endSpeechAtMs = 2_800L,
                finalAtMs = 2_900L,
                endpointReason = SpeechEndpointReason.FINAL,
            ),
        )

        val summary = store.exportSummary()
        assertEquals(175.0, summary.averageRouteSettleMs ?: 0.0, 0.01)
        assertEquals(150.0, summary.averageReadyToSpeechStartMs ?: 0.0, 0.01)
        assertEquals(100.0, summary.averageFinalizationDelayMs ?: 0.0, 0.01)
    }

    @Test
    fun `tts and audio probe histories are bounded`() {
        val store = VoiceDiagnosticsStore(maxTtsPlaybacks = 2, maxAudioProbes = 2)

        repeat(5) { index ->
            store.recordTtsPlayback(ttsMetrics("tts-$index"))
            store.recordAudioProbe(probeMetrics())
        }

        assertEquals(2, store.ttsPlaybackCount())
        assertEquals(2, store.audioProbeCount())
    }

    private fun speechMetrics(sessionId: String): SpeechSessionMetrics =
        SpeechSessionMetrics(
            sessionId = sessionId,
            engineId = "platform_speech_recognizer",
            startedAtMs = 1_000L,
            routeProof = AudioRouteProof(routeState = AudioRouteProofState.ROUTE_BLUETOOTH_ACTIVE),
            readyForSpeechAtMs = 1_100L,
            beginSpeechAtMs = 1_200L,
            firstRmsAtMs = 1_250L,
            rmsFrameCount = 3,
            rmsPeakDb = -4f,
            endSpeechAtMs = 1_500L,
            finalAtMs = 1_720L,
            endpointReason = SpeechEndpointReason.FINAL,
            finalTranscriptLength = 10,
        )

    private fun ttsMetrics(utteranceId: String): TtsPlaybackMetrics =
        TtsPlaybackMetrics(
            utteranceId = utteranceId,
            textLength = 20,
            requestedAtMs = 1_000L,
            startedAtMs = 1_100L,
            completedAtMs = 1_500L,
            event = TtsPlaybackEvent.DONE,
        )

    private fun probeMetrics(): AudioProbeMetrics =
        AudioProbeMetrics(
            initStatus = AudioProbeInitStatus.STARTED,
            startedAtMs = 1_000L,
            finishedAtMs = 1_500L,
            framesRead = 512,
            nonZeroFrames = 400,
            peakAmplitude = 0.4f,
            bluetoothRouteAtCapture = AudioRouteProof(routeState = AudioRouteProofState.ROUTE_BLUETOOTH_ACTIVE),
        )

    private fun interruptionMetrics(): TtsInterruptionMetrics =
        TtsInterruptionMetrics(
            interruptionId = "interrupt-1",
            reason = TtsInterruptionReason.BARGE_IN,
            requestedAtMs = 1_000L,
            ttsStoppedAtMs = 1_100L,
            listeningStartedAtMs = 1_200L,
        )
}
