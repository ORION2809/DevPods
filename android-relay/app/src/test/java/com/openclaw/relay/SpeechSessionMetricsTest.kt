package com.openclaw.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSessionMetricsTest {
    @Test
    fun `recorder captures endpoint timing without storing transcript text`() {
        val recorder = SpeechSessionMetricsRecorder(
            sessionId = "speech-1",
            engineId = "platform_speech_recognizer",
            wakeSignal = "left_double_tap",
            startedAtMs = 1_000L,
        )

        recorder.markRouteRequested(1_010L)
        recorder.markRouteReady(
            nowMs = 1_080L,
            routeSnapshot = RelayAudioRouteSnapshot(
                isActive = true,
                isReadyForSpeechCapture = true,
                status = "Headset microphone route ready",
            ),
        )
        recorder.markRecognizerCreated(1_100L)
        recorder.markListeningStarted(1_120L)
        recorder.markReadyForSpeech(1_160L)
        recorder.markRmsChanged(1_170L, -6f)
        recorder.markRmsChanged(1_180L, -2f)
        recorder.markBeginningOfSpeech(1_200L)
        recorder.markPartial(1_320L, "git")
        recorder.markPartial(1_360L, "git status")
        recorder.markEndOfSpeech(1_600L)
        recorder.markFinal(1_720L, "git status")

        val metrics = recorder.snapshot()

        assertEquals("speech-1", metrics.sessionId)
        assertEquals("platform_speech_recognizer", metrics.engineId)
        assertEquals("left_double_tap", metrics.wakeSignal)
        assertEquals(70L, metrics.routeSettleMs)
        assertEquals(2, metrics.rmsFrameCount)
        assertEquals(-2f, metrics.rmsPeakDb)
        assertEquals(2, metrics.partialCount)
        assertEquals(SpeechEndpointReason.FINAL, metrics.endpointReason)
        assertEquals(10L, metrics.readyToFirstRmsMs)
        assertEquals(40L, metrics.readyToSpeechStartMs)
        assertEquals(120L, metrics.speechStartToPartialMs)
        assertEquals(120L, metrics.finalizationDelayMs)
        assertEquals(10, metrics.finalTranscriptLength)
        assertNull(metrics.finalTranscriptPreview)
    }

    @Test
    fun `platform VAD observation flags speech and wrong mic suspicion`() {
        val noAudioMetrics = SpeechSessionMetrics(
            sessionId = "speech-2",
            engineId = "platform_speech_recognizer",
            startedAtMs = 1_000L,
            routeProof = AudioRouteProof(routeState = AudioRouteProofState.ROUTE_BLUETOOTH_ACTIVE),
            readyForSpeechAtMs = 1_050L,
            rmsFrameCount = 0,
            endpointReason = SpeechEndpointReason.NO_SPEECH,
        )
        val speechMetrics = noAudioMetrics.copy(
            beginSpeechAtMs = 1_100L,
            firstPartialAtMs = 1_180L,
            rmsFrameCount = 3,
            rmsPeakDb = -4f,
            endpointReason = SpeechEndpointReason.FINAL,
        )

        val noAudioObservation = noAudioMetrics.toPlatformVadObservation()
        val speechObservation = speechMetrics.toPlatformVadObservation()

        assertFalse(noAudioObservation.speechDetected)
        assertTrue(noAudioObservation.wrongMicSuspected)
        assertTrue(speechObservation.speechDetected)
        assertFalse(speechObservation.wrongMicSuspected)
        assertEquals(50L, speechObservation.speechStartDelayMs)
        assertEquals(80L, speechObservation.partialAfterSpeechStartMs)
    }

    @Test
    fun `error endpoint records code and reset class`() {
        val recorder = SpeechSessionMetricsRecorder(
            sessionId = "speech-3",
            engineId = "platform_speech_recognizer",
            startedAtMs = 2_000L,
        )

        recorder.markError(
            nowMs = 2_300L,
            errorCode = 8,
            reason = SpeechEndpointReason.RECOGNIZER_BUSY,
        )

        val metrics = recorder.snapshot()

        assertEquals(8, metrics.errorCode)
        assertEquals(SpeechEndpointReason.RECOGNIZER_BUSY, metrics.endpointReason)
        assertEquals(300L, metrics.totalSessionMs)
    }
}
