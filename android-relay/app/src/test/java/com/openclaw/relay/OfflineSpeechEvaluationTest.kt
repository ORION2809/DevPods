package com.openclaw.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OfflineSpeechEvaluationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `default config keeps platform recognizer as active engine`() {
        val readiness = OfflineSpeechReadiness.evaluate(RelayConfig())

        assertEquals(SpeechInputMode.PLATFORM, readiness.requestedMode)
        assertEquals("platform_speech_recognizer", readiness.activeEngineId)
        assertFalse(readiness.offlineEvaluationEnabled)
        assertFalse(readiness.nativeDependencyLinked)
        assertFalse(readiness.canRunOffline)
        assertTrue(readiness.failureReasons.contains("offline_evaluation_disabled"))
    }

    @Test
    fun `sherpa evaluation stays disabled when native dependency is missing`() {
        val modelRoot = temporaryFolder.newFolder("sherpa-model").apply {
            File(this, "tokens.txt").writeText("a b c")
        }
        val spec = OfflineSpeechModelSpec(
            engineId = "sherpa_streaming",
            modelRootPath = modelRoot.absolutePath,
            modelVersion = "tiny-test",
            requiredFiles = listOf("tokens.txt"),
        )

        val readiness = OfflineSpeechReadiness.evaluate(
            requestedMode = SpeechInputMode.SHERPA_EVALUATION,
            spec = spec,
            nativeDependencyLinked = false,
        )

        assertEquals("platform_speech_recognizer", readiness.activeEngineId)
        assertFalse(readiness.canRunOffline)
        assertTrue(readiness.modelPresent)
        assertTrue(readiness.failureReasons.contains("sherpa_native_dependency_missing"))
    }

    @Test
    fun `model validation reports missing required files and footprint`() {
        val modelRoot = temporaryFolder.newFolder("partial-model").apply {
            File(this, "tokens.txt").writeText("tokens")
        }
        val spec = OfflineSpeechModelSpec(
            engineId = "sherpa_streaming",
            modelRootPath = modelRoot.absolutePath,
            modelVersion = "partial",
            requiredFiles = listOf("tokens.txt", "encoder.onnx", "decoder.onnx"),
        )

        val modelStatus = OfflineSpeechModelManager.inspect(spec)

        assertFalse(modelStatus.isComplete)
        assertEquals(listOf("encoder.onnx", "decoder.onnx"), modelStatus.missingFiles)
        assertTrue(modelStatus.totalBytes >= 6L)
        assertFalse(modelStatus.rawAudioRequired)
    }

    @Test
    fun `sherpa placeholders fail gracefully without native dependency`() {
        var speechError = ""
        val speechEngine = SherpaSpeechInputEngine(
            readiness = OfflineSpeechReadiness.evaluate(
                requestedMode = SpeechInputMode.SHERPA_EVALUATION,
                spec = OfflineSpeechModelSpec(
                    engineId = "sherpa_streaming",
                    modelRootPath = "",
                    modelVersion = "missing",
                ),
                nativeDependencyLinked = false,
            )
        )
        val vadProbe = SherpaVadProbe(nativeDependencyLinked = false)

        kotlinx.coroutines.test.runTest {
            speechEngine.start(
                request = SpeechSessionRequest(sessionId = "speech-offline"),
                callbacks = SpeechCallbacks(onError = { speechError = it.message }),
            )
            var vadError = ""
            vadProbe.start(
                request = VadProbeRequest(sessionId = "vad-offline"),
                callbacks = VadCallbacks(onError = { vadError = it }),
            )

            assertEquals("Offline speech evaluation is unavailable: sherpa_native_dependency_missing, offline_model_incomplete", speechError)
            assertEquals("Sherpa VAD evaluation is unavailable until the native dependency is linked.", vadError)
        }
    }

    @Test
    fun `offline benchmark promotion requires reliability latency and barge in targets`() {
        val passingSamples = (1..20).map { index ->
            OfflineSpeechBenchmarkSample(
                sessionId = "speech-$index",
                engineId = "sherpa_streaming",
                finalEndpointMs = 700L,
                commandSucceeded = true,
                routeSucceeded = true,
                ttsInterruptionTargetMet = true,
            )
        }
        val passingSummary = OfflineSpeechBenchmark.summarize(passingSamples)

        assertEquals(100, passingSummary.reliabilityPercent)
        assertTrue(passingSummary.promotionReady)

        val failingSummary = OfflineSpeechBenchmark.summarize(
            passingSamples.take(10) + (11..20).map { index ->
                passingSamples.last().copy(
                    sessionId = "speech-slow-$index",
                    finalEndpointMs = 1_250L,
                    commandSucceeded = index < 19,
                )
            }
        )

        assertFalse(failingSummary.promotionReady)
        assertTrue(failingSummary.failureReasons.contains("reliability_below_95_percent"))
        assertTrue(failingSummary.failureReasons.contains("median_final_endpoint_above_900ms"))
    }
}
