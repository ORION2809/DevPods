package com.openclaw.relay

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `sherpa evaluation fallback reports native and model blockers`() {
        val readiness = OfflineSpeechReadiness.evaluate(
            requestedMode = SpeechInputMode.SHERPA_EVALUATION,
            spec = OfflineSpeechModelSpec(
                engineId = "sherpa_streaming",
                modelRootPath = "",
                modelVersion = "missing",
            ),
            nativeDependencyLinked = false,
        )

        assertEquals("platform_speech_recognizer", readiness.activeEngineId)
        assertFalse(readiness.canRunOffline)
        assertEquals(
            listOf("sherpa_native_dependency_missing", "offline_model_incomplete"),
            readiness.failureReasons,
        )
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

    @Test
    fun `sherpa evaluation with native linked and model ready enables offline`() {
        val modelRoot = temporaryFolder.newFolder("sherpa-ready").apply {
            File(this, "tokens.txt").writeText("a b c")
            File(this, "encoder.onnx").writeBytes(ByteArray(100))
            File(this, "decoder.onnx").writeBytes(ByteArray(100))
            File(this, "joiner.onnx").writeBytes(ByteArray(100))
        }
        val spec = OfflineSpeechModelSpec(
            engineId = "sherpa_streaming",
            modelRootPath = modelRoot.absolutePath,
            modelVersion = "ready",
        )

        val readiness = OfflineSpeechReadiness.evaluate(
            requestedMode = SpeechInputMode.SHERPA_EVALUATION,
            spec = spec,
            nativeDependencyLinked = true,
            sttModelReady = true,
        )

        assertEquals("sherpa_streaming", readiness.activeEngineId)
        assertTrue(readiness.canRunOffline)
        assertTrue(readiness.nativeDependencyLinked)
        assertTrue(readiness.modelPresent)
        assertTrue(readiness.failureReasons.isEmpty())
    }

    @Test
    fun `sherpa evaluation with missing native library falls back with typed reason`() {
        val modelRoot = temporaryFolder.newFolder("sherpa-no-native").apply {
            File(this, "tokens.txt").writeText("a b c")
            File(this, "encoder.onnx").writeBytes(ByteArray(100))
            File(this, "decoder.onnx").writeBytes(ByteArray(100))
            File(this, "joiner.onnx").writeBytes(ByteArray(100))
        }
        val spec = OfflineSpeechModelSpec(
            engineId = "sherpa_streaming",
            modelRootPath = modelRoot.absolutePath,
            modelVersion = "ready",
        )

        val readiness = OfflineSpeechReadiness.evaluate(
            requestedMode = SpeechInputMode.SHERPA_EVALUATION,
            spec = spec,
            nativeDependencyLinked = false,
            sttModelReady = true,
        )

        assertEquals("platform_speech_recognizer", readiness.activeEngineId)
        assertFalse(readiness.canRunOffline)
        assertFalse(readiness.nativeDependencyLinked)
        assertTrue(readiness.failureReasons.contains("sherpa_native_dependency_missing"))
    }

    @Test
    fun `sherpa evaluation with missing model falls back with typed reason`() {
        val readiness = OfflineSpeechReadiness.evaluate(
            requestedMode = SpeechInputMode.SHERPA_EVALUATION,
            spec = OfflineSpeechModelSpec(
                engineId = "sherpa_streaming",
                modelRootPath = "",
                modelVersion = "missing",
            ),
            nativeDependencyLinked = true,
            sttModelReady = false,
        )

        assertEquals("platform_speech_recognizer", readiness.activeEngineId)
        assertFalse(readiness.canRunOffline)
        assertTrue(readiness.failureReasons.contains("sherpa_stt_model_not_ready"))
    }

    @Test
    fun `sherpa evaluation with checksum mismatch falls back with typed reason`() {
        val modelRoot = temporaryFolder.newFolder("sherpa-bad-checksum").apply {
            File(this, "tokens.txt").writeText("a b c")
            File(this, "encoder.onnx").writeBytes(ByteArray(100))
            File(this, "decoder.onnx").writeBytes(ByteArray(100))
            File(this, "joiner.onnx").writeBytes(ByteArray(100))
        }
        val spec = OfflineSpeechModelSpec(
            engineId = "sherpa_streaming",
            modelRootPath = modelRoot.absolutePath,
            modelVersion = "checksum-test",
            expectedSha256 = "this-will-not-match",
        )

        val readiness = OfflineSpeechReadiness.evaluate(
            requestedMode = SpeechInputMode.SHERPA_EVALUATION,
            spec = spec,
            nativeDependencyLinked = true,
            sttModelReady = false,
        )

        assertEquals("platform_speech_recognizer", readiness.activeEngineId)
        assertFalse(readiness.canRunOffline)
        assertTrue(readiness.failureReasons.contains("offline_model_checksum_mismatch"))
    }

    // ---- Workstream 9: Command-mode evaluation tests ----

    @Test
    fun `command benchmark set contains expected DevPods commands`() {
        val commands = SherpaCommandBenchmarkSet.COMMANDS
        assertEquals(8, commands.size)
        assertTrue(commands.contains("status"))
        assertTrue(commands.contains("what changed"))
        assertTrue(commands.contains("run tests"))
        assertTrue(commands.contains("open the main file"))
        assertTrue(commands.contains("latest CI failure"))
        assertTrue(commands.contains("write a commit message"))
        assertTrue(commands.contains("push"))
        assertTrue(commands.contains("cancel"))
    }

    @Test
    fun `command benchmark produces hidden promotion when no sherpa samples`() {
        val platformSamples = SherpaCommandBenchmarkSet.COMMANDS.map { command ->
            CommandBenchmarkSample(
                command = command,
                engine = CommandBenchmarkEngine.PLATFORM_STT,
                wakeToReadyMs = 400L,
                finalTranscriptMs = 900L,
                intentAccuracy = 0.95f,
            )
        }
        val report = SherpaCommandBenchmark.runBenchmark(platformSamples)

        assertEquals(SherpaPromotionState.HIDDEN, report.promotionState)
        assertTrue(report.promotionReasons.contains("sherpa_summary_missing"))
    }

    @Test
    fun `command benchmark produces developer diagnostic when sherpa fails internal gates`() {
        val samples = SherpaCommandBenchmarkSet.COMMANDS.flatMap { command ->
            listOf(
                CommandBenchmarkSample(
                    command = command,
                    engine = CommandBenchmarkEngine.PLATFORM_STT,
                    wakeToReadyMs = 400L,
                    finalTranscriptMs = 900L,
                    intentAccuracy = 0.95f,
                ),
                CommandBenchmarkSample(
                    command = command,
                    engine = CommandBenchmarkEngine.SHERPA_STT,
                    wakeToReadyMs = 1_500L, // too slow
                    finalTranscriptMs = 2_000L,
                    intentAccuracy = 0.95f,
                    endpointDelayMs = 300L,
                ),
            )
        }
        val report = SherpaCommandBenchmark.runBenchmark(samples)

        assertEquals(SherpaPromotionState.DEVELOPER_DIAGNOSTIC, report.promotionState)
        assertTrue(report.promotionReasons.any { it.contains("wake_to_ready") })
    }

    @Test
    fun `command benchmark promotes to experimental when sherpa beats platform`() {
        val samples = SherpaCommandBenchmarkSet.COMMANDS.flatMap { command ->
            listOf(
                CommandBenchmarkSample(
                    command = command,
                    engine = CommandBenchmarkEngine.PLATFORM_STT,
                    wakeToReadyMs = 500L,
                    finalTranscriptMs = 1_100L,
                    intentAccuracy = 0.88f,
                    endpointDelayMs = 250L,
                ),
                CommandBenchmarkSample(
                    command = command,
                    engine = CommandBenchmarkEngine.SHERPA_STT,
                    wakeToReadyMs = 450L,
                    finalTranscriptMs = 900L,
                    intentAccuracy = 0.92f,
                    endpointDelayMs = 200L,
                ),
            )
        }
        val report = SherpaCommandBenchmark.runBenchmark(samples)

        assertEquals(SherpaPromotionState.EXPERIMENTAL, report.promotionState)
        assertTrue(report.promotionReasons.contains("sherpa_passes_minimum_gates"))
    }

    @Test
    fun `command benchmark stays experimental if not better than platform on device`() {
        val samples = SherpaCommandBenchmarkSet.COMMANDS.flatMap { command ->
            listOf(
                CommandBenchmarkSample(
                    command = command,
                    engine = CommandBenchmarkEngine.PLATFORM_STT,
                    wakeToReadyMs = 500L,
                    finalTranscriptMs = 1_100L,
                    intentAccuracy = 0.88f,
                    endpointDelayMs = 250L,
                ),
                CommandBenchmarkSample(
                    command = command,
                    engine = CommandBenchmarkEngine.PLATFORM_ON_DEVICE_STT,
                    wakeToReadyMs = 300L,
                    finalTranscriptMs = 700L,
                    intentAccuracy = 0.96f,
                    endpointDelayMs = 150L,
                ),
                CommandBenchmarkSample(
                    command = command,
                    engine = CommandBenchmarkEngine.SHERPA_STT,
                    wakeToReadyMs = 450L,
                    finalTranscriptMs = 900L,
                    intentAccuracy = 0.92f,
                    endpointDelayMs = 200L,
                ),
            )
        }
        val report = SherpaCommandBenchmark.runBenchmark(samples)

        assertEquals(SherpaPromotionState.EXPERIMENTAL, report.promotionState)
        assertTrue(report.promotionReasons.contains("sherpa_not_better_than_platform_on_device"))
    }

    @Test
    fun `command benchmark promotes to production when strict gates met`() {
        val samples = SherpaCommandBenchmarkSet.COMMANDS.flatMap { command ->
            listOf(
                CommandBenchmarkSample(
                    command = command,
                    engine = CommandBenchmarkEngine.PLATFORM_STT,
                    wakeToReadyMs = 500L,
                    finalTranscriptMs = 1_100L,
                    intentAccuracy = 0.88f,
                    endpointDelayMs = 250L,
                ),
                CommandBenchmarkSample(
                    command = command,
                    engine = CommandBenchmarkEngine.SHERPA_STT,
                    wakeToReadyMs = 400L,
                    finalTranscriptMs = 800L,
                    intentAccuracy = 0.96f,
                    noSpeechDetected = false,
                    endpointDelayMs = 180L,
                ),
            )
        }
        val report = SherpaCommandBenchmark.runBenchmark(samples)

        assertEquals(SherpaPromotionState.PRODUCTION, report.promotionState)
        assertTrue(report.promotionReasons.contains("sherpa_meets_production_gates"))
    }

    @Test
    fun `evaluateCommandBenchmark stores artifact when context provided`() {
        val filesDir = temporaryFolder.newFolder("files")
        val samples = SherpaCommandBenchmarkSet.COMMANDS.flatMap { command ->
            listOf(
                CommandBenchmarkSample(
                    command = command,
                    engine = CommandBenchmarkEngine.SHERPA_STT,
                    wakeToReadyMs = 400L,
                    finalTranscriptMs = 800L,
                    intentAccuracy = 0.96f,
                    endpointDelayMs = 180L,
                ),
            )
        }

        val report = OfflineSpeechEvaluation.evaluateCommandBenchmark(samples)
        val artifactPath = SherpaBenchmarkArtifactStore.store(report, filesDir)

        assertNotNull(artifactPath)
        val artifactFile = File(artifactPath)
        assertTrue(artifactFile.exists())
        assertTrue(artifactFile.readText().contains("sherpa-cmd-bench"))
    }

    @Test
    fun `latestArtifact returns null when no artifacts exist`() {
        val filesDir = temporaryFolder.newFolder("empty-files")
        val latest = SherpaBenchmarkArtifactStore.latestArtifact(filesDir)
        assertNull(latest)
    }

    @Test
    fun `resolvePromotionState returns hidden when no artifact exists`() {
        val filesDir = temporaryFolder.newFolder("empty-files")
        val state = OfflineSpeechEvaluation.resolvePromotionState(filesDir)
        assertEquals(SherpaPromotionState.HIDDEN, state)
    }

    @Test
    fun `resolvePromotionState returns state from latest artifact`() {
        val filesDir = temporaryFolder.newFolder("files")
        val samples = SherpaCommandBenchmarkSet.COMMANDS.flatMap { command ->
            listOf(
                CommandBenchmarkSample(
                    command = command,
                    engine = CommandBenchmarkEngine.SHERPA_STT,
                    wakeToReadyMs = 400L,
                    finalTranscriptMs = 800L,
                    intentAccuracy = 0.96f,
                    endpointDelayMs = 180L,
                ),
            )
        }
        val report = SherpaCommandBenchmark.runBenchmark(samples)
        SherpaBenchmarkArtifactStore.store(report, filesDir)

        val resolved = OfflineSpeechEvaluation.resolvePromotionState(filesDir)
        assertEquals(SherpaPromotionState.PRODUCTION, resolved)
    }
}
