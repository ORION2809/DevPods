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
        assertEquals(0L, request.possibleCompleteSilenceMs)
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

    // ---- Workstream 9: Command-mode engine contract tests ----

    @Test
    fun `command benchmark engine enum covers all three candidates`() {
        val engines = CommandBenchmarkEngine.entries
        assertEquals(3, engines.size)
        assertTrue(engines.contains(CommandBenchmarkEngine.PLATFORM_STT))
        assertTrue(engines.contains(CommandBenchmarkEngine.PLATFORM_ON_DEVICE_STT))
        assertTrue(engines.contains(CommandBenchmarkEngine.SHERPA_STT))
    }

    @Test
    fun `command benchmark sample captures all required metrics`() {
        val sample = CommandBenchmarkSample(
            command = "run tests",
            engine = CommandBenchmarkEngine.SHERPA_STT,
            wakeToReadyMs = 320L,
            firstPartialMs = 450L,
            finalTranscriptMs = 780L,
            transcriptAccuracy = 0.94f,
            intentAccuracy = 0.91f,
            endpointDelayMs = 210L,
            noSpeechDetected = false,
            nativeFailureRecovered = true,
            cpuPercent = 12.5f,
            batteryDrainMah = 3.2f,
            thermalThrottled = false,
        )

        assertEquals("run tests", sample.command)
        assertEquals(CommandBenchmarkEngine.SHERPA_STT, sample.engine)
        assertEquals(320L, sample.wakeToReadyMs)
        assertEquals(450L, sample.firstPartialMs)
        assertEquals(780L, sample.finalTranscriptMs)
        assertEquals(0.94f, sample.transcriptAccuracy!!, 0.001f)
        assertEquals(0.91f, sample.intentAccuracy!!, 0.001f)
        assertEquals(210L, sample.endpointDelayMs)
        assertFalse(sample.noSpeechDetected)
        assertTrue(sample.nativeFailureRecovered)
        assertEquals(12.5f, sample.cpuPercent!!, 0.001f)
        assertEquals(3.2f, sample.batteryDrainMah!!, 0.001f)
        assertFalse(sample.thermalThrottled)
    }

    @Test
    fun `engine summary aggregates samples correctly`() {
        val samples = listOf(
            CommandBenchmarkSample(
                command = "status",
                engine = CommandBenchmarkEngine.PLATFORM_STT,
                wakeToReadyMs = 400L,
                finalTranscriptMs = 900L,
                intentAccuracy = 0.90f,
            ),
            CommandBenchmarkSample(
                command = "cancel",
                engine = CommandBenchmarkEngine.PLATFORM_STT,
                wakeToReadyMs = 500L,
                finalTranscriptMs = 1_000L,
                intentAccuracy = 0.92f,
            ),
        )

        val summary = SherpaCommandBenchmark.summarizeEngine(
            CommandBenchmarkEngine.PLATFORM_STT,
            samples,
        )

        assertEquals(CommandBenchmarkEngine.PLATFORM_STT, summary.engine)
        assertEquals(2, summary.sampleCount)
        assertEquals(450L, summary.medianWakeToReadyMs)
        assertEquals(950L, summary.medianFinalTranscriptMs)
        assertEquals(0.91f, summary.meanIntentAccuracy!!, 0.001f)
    }

    @Test
    fun `engine summary reports failures when samples insufficient`() {
        val samples = listOf(
            CommandBenchmarkSample(
                command = "status",
                engine = CommandBenchmarkEngine.SHERPA_STT,
                wakeToReadyMs = 400L,
            ),
        )

        val summary = SherpaCommandBenchmark.summarizeEngine(
            CommandBenchmarkEngine.SHERPA_STT,
            samples,
        )

        assertTrue(summary.failureReasons.isNotEmpty())
        assertTrue(summary.failureReasons.any { it.contains("insufficient_samples") })
    }

    @Test
    fun `sherpa engine capabilities declare on device support`() {
        val capabilities = SpeechEngineCapabilities(
            engineId = "sherpa_streaming",
            isAvailable = true,
            supportsPartialResults = true,
            supportsEndpointingHints = true,
            supportsOnDeviceRecognition = true,
            storesRawAudio = false,
        )

        assertEquals("sherpa_streaming", capabilities.engineId)
        assertTrue(capabilities.supportsOnDeviceRecognition)
    }

    @Test
    fun `promotion state ordering allows gating`() {
        // HIDDEN < DEVELOPER_DIAGNOSTIC < EXPERIMENTAL < PRODUCTION
        assertTrue(SherpaPromotionState.HIDDEN.ordinal < SherpaPromotionState.DEVELOPER_DIAGNOSTIC.ordinal)
        assertTrue(SherpaPromotionState.DEVELOPER_DIAGNOSTIC.ordinal < SherpaPromotionState.EXPERIMENTAL.ordinal)
        assertTrue(SherpaPromotionState.EXPERIMENTAL.ordinal < SherpaPromotionState.PRODUCTION.ordinal)
    }

    @Test
    fun `sherpa fallback to platform is immediate when benchmark gates fail`() {
        val config = RelayConfig(speechInputMode = SpeechInputMode.SHERPA_EVALUATION)
        val readiness = OfflineSpeechReadiness.evaluate(config)

        // When Sherpa is unavailable (no model/native), fallback is immediate
        assertEquals("platform_speech_recognizer", readiness.activeEngineId)
        assertFalse(readiness.canRunOffline)
    }
}
