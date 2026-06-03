package com.openclaw.relay.sherpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SherpaModelInspectorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `inspect reports missing files for nonexistent directory`() {
        val spec = SherpaModelSpec.SILERO_VAD_V4.copy(
            installRoot = tempFolder.root.absolutePath,
        )
        val status = SherpaModelInspector.inspect(spec)

        assertFalse(status.isComplete)
        assertFalse(status.isReady)
        assertEquals(listOf("silero_vad.onnx"), status.missingFiles)
        assertEquals(0L, status.totalBytes)
    }

    @Test
    fun `inspect reports complete when all VAD files present`() {
        val modelDir = tempFolder.newFolder("silero_vad_v4")
        File(modelDir, "silero_vad.onnx").writeBytes(ByteArray(100))

        val spec = SherpaModelSpec.SILERO_VAD_V4.copy(
            installRoot = tempFolder.root.absolutePath,
        )
        val status = SherpaModelInspector.inspect(spec)

        assertTrue(status.isComplete)
        assertTrue(status.missingFiles.isEmpty())
        assertTrue(status.totalBytes > 0)
    }

    @Test
    fun `inspect reports missing STT files`() {
        val modelDir = tempFolder.newFolder("sherpa_streaming_en_zipformer")
        File(modelDir, "tokens.txt").writeText("a b c")

        val spec = SherpaModelSpec.SHERPA_STREAMING_EN.copy(
            installRoot = tempFolder.root.absolutePath,
        )
        val status = SherpaModelInspector.inspect(spec)

        assertFalse(status.isComplete)
        assertEquals(listOf("encoder.onnx", "decoder.onnx", "joiner.onnx"), status.missingFiles)
    }

    @Test
    fun `inspect validates per-file checksums`() {
        val modelDir = tempFolder.newFolder("silero_vad_v4")
        val vadFile = File(modelDir, "silero_vad.onnx")
        vadFile.writeBytes(ByteArray(100))

        val spec = SherpaModelSpec(
            id = "silero_vad_v4",
            type = SherpaModelType.SILERO_VAD,
            version = "v4.0.0",
            requiredFiles = listOf("silero_vad.onnx"),
            expectedSha256ByFile = mapOf("silero_vad.onnx" to "deadbeef"),
            installRoot = tempFolder.root.absolutePath,
        )
        val status = SherpaModelInspector.inspect(spec)

        assertTrue(status.isComplete)
        assertFalse(status.checksumMatches)
        assertFalse(status.isReady)
    }

    @Test
    fun `inspected complete model with matching checksums is ready`() {
        val modelDir = tempFolder.newFolder("silero_vad_v4")
        val vadFile = File(modelDir, "silero_vad.onnx")
        vadFile.writeBytes(ByteArray(100))

        val actualHash = SherpaModelInspector.inspect(
            SherpaModelSpec.SILERO_VAD_V4.copy(installRoot = tempFolder.root.absolutePath),
        ).actualSha256ByFile["silero_vad.onnx"]!!

        val spec = SherpaModelSpec(
            id = "silero_vad_v4",
            type = SherpaModelType.SILERO_VAD,
            version = "v4.0.0",
            requiredFiles = listOf("silero_vad.onnx"),
            expectedSha256ByFile = mapOf("silero_vad.onnx" to actualHash),
            installRoot = tempFolder.root.absolutePath,
        )
        val status = SherpaModelInspector.inspect(spec)

        assertTrue(status.isComplete)
        assertTrue(status.checksumMatches)
        assertTrue(status.isReady)
    }

    @Test
    fun `STT model requires all four files`() {
        val modelDir = tempFolder.newFolder("sherpa_streaming_en_zipformer")
        File(modelDir, "tokens.txt").writeText("hello world")
        File(modelDir, "encoder.onnx").writeBytes(ByteArray(100))

        val spec = SherpaModelSpec.SHERPA_STREAMING_EN.copy(
            installRoot = tempFolder.root.absolutePath,
        )
        val status = SherpaModelInspector.inspect(spec)

        assertFalse(status.isComplete)
        assertEquals(listOf("decoder.onnx", "joiner.onnx"), status.missingFiles)
        assertTrue(status.rawAudioRequired)
    }
}