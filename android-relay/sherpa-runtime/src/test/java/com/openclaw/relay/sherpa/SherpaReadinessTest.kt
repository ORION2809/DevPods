package com.openclaw.relay.sherpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SherpaReadinessTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val unavailableRuntime = SherpaRuntimeAvailability(
        isNativeLibraryLoadable = false,
        availableAbis = listOf("arm64-v8a"),
        runtimeVersion = null,
        failureReason = "sherpa_native_not_linked",
    )

    private val availableRuntime = SherpaRuntimeAvailability(
        isNativeLibraryLoadable = true,
        availableAbis = listOf("arm64-v8a"),
        runtimeVersion = "1.10.0",
        failureReason = null,
    )

    @Test
    fun `VAD readiness is independent from STT readiness - VAD ready, STT not`() {
        val vadDir = File(tempFolder.root, "silero_vad_v4")
        vadDir.mkdirs()
        File(vadDir, "silero_vad.onnx").writeBytes(ByteArray(100))

        val vadSpec = SherpaModelSpec(
            id = "silero_vad_v4",
            type = SherpaModelType.SILERO_VAD,
            version = "v4.0.0",
            requiredFiles = listOf("silero_vad.onnx"),
            installRoot = tempFolder.root.absolutePath,
        )

        val vadReadiness = VadReadiness.evaluate(availableRuntime, vadSpec)
        val sttReadiness = SttReadiness.evaluate(availableRuntime, null)

        assertTrue(vadReadiness.isReady)
        assertFalse(sttReadiness.isReady)
        assertEquals(listOf("sherpa_stt_model_not_configured"), sttReadiness.failureReasons)
    }

    @Test
    fun `VAD readiness is independent from STT readiness - STT ready, VAD not`() {
        val sttDir = File(tempFolder.root, "sherpa_streaming_en_zipformer")
        sttDir.mkdirs()
        File(sttDir, "tokens.txt").writeText("a b c")
        File(sttDir, "encoder.onnx").writeBytes(ByteArray(100))
        File(sttDir, "decoder.onnx").writeBytes(ByteArray(100))
        File(sttDir, "joiner.onnx").writeBytes(ByteArray(100))

        val sttSpec = SherpaModelSpec(
            id = "sherpa_streaming_en_zipformer",
            type = SherpaModelType.STREAMING_STT,
            version = "test-v1",
            requiredFiles = listOf("tokens.txt", "encoder.onnx", "decoder.onnx", "joiner.onnx"),
            installRoot = tempFolder.root.absolutePath,
        )

        val vadReadiness = VadReadiness.evaluate(availableRuntime, null)
        val sttReadiness = SttReadiness.evaluate(availableRuntime, sttSpec)

        assertFalse(vadReadiness.isReady)
        assertTrue(sttReadiness.isReady)
        assertEquals(listOf("sherpa_vad_model_not_configured"), vadReadiness.failureReasons)
    }

    @Test
    fun `neither ready without runtime even with models`() {
        val vadDir = File(tempFolder.root, "silero_vad_v4")
        vadDir.mkdirs()
        File(vadDir, "silero_vad.onnx").writeBytes(ByteArray(100))

        val vadSpec = SherpaModelSpec.SILERO_VAD_V4.copy(
            installRoot = tempFolder.root.absolutePath,
        )

        val vadReadiness = VadReadiness.evaluate(unavailableRuntime, vadSpec)

        assertFalse(vadReadiness.isReady)
        assertTrue(vadReadiness.failureReasons.contains("sherpa_runtime_unavailable"))
    }

    @Test
    fun `SherpaReadiness aggregates VAD and STT failures`() {
        val readiness = SherpaReadiness(
            vadReadiness = VadReadiness(
                isReady = false,
                modelPresent = false,
                failureReasons = listOf("sherpa_runtime_unavailable"),
            ),
            sttReadiness = SttReadiness(
                isReady = false,
                modelPresent = true,
                failureReasons = listOf("sherpa_stt_model_checksum_mismatch"),
            ),
            runtimeAvailability = unavailableRuntime,
        )

        assertFalse(readiness.vadReady)
        assertFalse(readiness.sttReady)
        assertTrue(readiness.failureReasons.contains("sherpa_runtime_unavailable"))
        assertTrue(readiness.failureReasons.contains("sherpa_stt_model_checksum_mismatch"))
    }

    @Test
    fun `checksum mismatch blocks readiness`() {
        val vadDir = File(tempFolder.root, "silero_vad_v4")
        vadDir.mkdirs()
        File(vadDir, "silero_vad.onnx").writeBytes(ByteArray(100))

        val spec = SherpaModelSpec(
            id = "silero_vad_v4",
            type = SherpaModelType.SILERO_VAD,
            version = "v4.0.0",
            requiredFiles = listOf("silero_vad.onnx"),
            expectedSha256ByFile = mapOf("silero_vad.onnx" to "deadbeef"),
            installRoot = tempFolder.root.absolutePath,
        )

        val vadReadiness = VadReadiness.evaluate(availableRuntime, spec)

        assertFalse(vadReadiness.isReady)
        assertFalse(vadReadiness.checksumMatches)
        assertTrue(vadReadiness.failureReasons.contains("sherpa_vad_model_checksum_mismatch"))
    }

    @Test
    fun `feature flags default to all off`() {
        val flags = SherpaFeatureFlags.DEFAULT

        assertFalse(flags.sherpaRuntimeEnabled)
        assertFalse(flags.sherpaVadDiagnosticsEnabled)
        assertFalse(flags.sherpaSttExperimentalEnabled)
        assertFalse(flags.sherpaModelDownloadsEnabled)
        assertFalse(flags.anySherpaEnabled)
    }

    @Test
    fun `feature flags with VAD only does not enable STT`() {
        val flags = SherpaFeatureFlags(
            sherpaRuntimeEnabled = true,
            sherpaVadDiagnosticsEnabled = true,
        )

        assertTrue(flags.anySherpaEnabled)
        assertFalse(flags.sherpaSttExperimentalEnabled)
        assertFalse(flags.sherpaModelDownloadsEnabled)
    }

    @Test
    fun `runtime availability can be constructed without Android context`() {
        val availability = SherpaRuntimeAvailability(
            isNativeLibraryLoadable = false,
            availableAbis = listOf("arm64-v8a"),
            runtimeVersion = null,
            failureReason = "sherpa_native_not_linked",
        )

        assertFalse(availability.isAvailable)
        assertNotNull(availability.failureReason)
    }

    @Test
    fun `available runtime is marked as available`() {
        val availability = SherpaRuntimeAvailability(
            isNativeLibraryLoadable = true,
            availableAbis = listOf("arm64-v8a"),
            runtimeVersion = "1.10.0",
            failureReason = null,
        )

        assertTrue(availability.isAvailable)
    }

    @Test
    fun `model spec convenience fields return correct paths`() {
        val spec = SherpaModelSpec.SILERO_VAD_V4.copy(
            installRoot = "/data/data/com.openclaw.relay/files/sherpa_models",
        )

        assertEquals(
            "/data/data/com.openclaw.relay/files/sherpa_models/silero_vad_v4",
            spec.modelRootPath,
        )
    }

    @Test
    fun `empty install root produces empty model root path`() {
        val spec = SherpaModelSpec.SILERO_VAD_V4.copy(installRoot = "")

        assertEquals("", spec.modelRootPath)
    }
}