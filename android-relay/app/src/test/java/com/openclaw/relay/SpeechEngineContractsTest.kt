package com.openclaw.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechEngineContractsTest {
    @Test
    fun `speech session request preserves platform endpointing defaults`() {
        val request = SpeechSessionRequest(sessionId = "speech-1")

        assertEquals("speech-1", request.sessionId)
        assertEquals(750L, request.completeSilenceMs)
        assertEquals(300L, request.minimumLengthMs)
        assertFalse(request.preferOffline)
        assertFalse(request.onDeviceOnly)
    }

    @Test
    fun `speech engine capabilities describe replaceable platform baseline`() {
        val capabilities = SpeechEngineCapabilities(
            engineId = "platform_speech_recognizer",
            isAvailable = true,
            supportsPartialResults = true,
            supportsEndpointingHints = true,
            supportsOnDeviceRecognition = false,
            storesRawAudio = false,
        )

        assertEquals("platform_speech_recognizer", capabilities.engineId)
        assertTrue(capabilities.isAvailable)
        assertTrue(capabilities.supportsPartialResults)
        assertTrue(capabilities.supportsEndpointingHints)
        assertFalse(capabilities.supportsOnDeviceRecognition)
        assertFalse(capabilities.storesRawAudio)
    }

    @Test
    fun `platform callback VAD probe derives observation from speech metrics`() {
        val metrics = SpeechSessionMetrics(
            sessionId = "speech-2",
            engineId = "platform_speech_recognizer",
            startedAtMs = 1_000L,
            routeProof = AudioRouteProof(routeState = AudioRouteProofState.ROUTE_BLUETOOTH_ACTIVE),
            readyForSpeechAtMs = 1_050L,
            beginSpeechAtMs = 1_120L,
            firstPartialAtMs = 1_250L,
            endSpeechAtMs = 1_700L,
            finalAtMs = 1_820L,
            rmsFrameCount = 5,
            rmsPeakDb = -3f,
            endpointReason = SpeechEndpointReason.FINAL,
        )

        val observation = PlatformCallbackVadProbe.observe(metrics)

        assertTrue(observation.speechDetected)
        assertFalse(observation.wrongMicSuspected)
        assertEquals(70L, observation.speechStartDelayMs)
        assertEquals(130L, observation.partialAfterSpeechStartMs)
        assertEquals(580L, observation.speechEndDelayMs)
        assertEquals(120L, observation.finalizationDelayMs)
    }
}
