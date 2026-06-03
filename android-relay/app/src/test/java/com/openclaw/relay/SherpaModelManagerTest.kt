package com.openclaw.relay

import android.content.Context
import com.openclaw.relay.sherpa.SherpaModelSpec
import com.openclaw.relay.sherpa.SherpaModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URL
import java.security.MessageDigest

class SherpaModelManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `status reflects missing files for empty directory`() {
        val cacheDir = tempFolder.newFolder("cache")
        val manager = SherpaModelManager(cacheDir)
        val modelDir = tempFolder.newFolder("model-empty")

        val spec = OfflineSpeechModelSpec(
            engineId = "sherpa_streaming",
            modelRootPath = modelDir.absolutePath,
            modelVersion = "test-v1",
            requiredFiles = listOf("tokens.txt", "encoder.onnx"),
        )

        val status = manager.status(spec)
        assertFalse(status.isComplete)
        assertEquals(listOf("tokens.txt", "encoder.onnx"), status.missingFiles)
    }

    @Test
    fun `status reflects complete model when all files present`() {
        val cacheDir = tempFolder.newFolder("cache")
        val manager = SherpaModelManager(cacheDir)
        val modelDir = tempFolder.newFolder("model-complete")

        File(modelDir, "tokens.txt").writeText("a b c")
        File(modelDir, "encoder.onnx").writeBytes(ByteArray(100))

        val spec = OfflineSpeechModelSpec(
            engineId = "sherpa_streaming",
            modelRootPath = modelDir.absolutePath,
            modelVersion = "test-v1",
            requiredFiles = listOf("tokens.txt", "encoder.onnx"),
        )

        val status = manager.status(spec)
        assertTrue(status.isComplete)
        assertTrue(status.missingFiles.isEmpty())
        assertTrue(status.totalBytes > 0)
    }

    @Test
    fun `clearCache removes all models`() {
        val cacheDir = tempFolder.newFolder("cache")
        val manager = SherpaModelManager(cacheDir)

        File(cacheDir, "modelA").mkdir()
        File(cacheDir, "modelB").mkdir()

        assertTrue(cacheDir.listFiles()?.isNotEmpty() == true)
        assertTrue(manager.clearCache())
        assertFalse(cacheDir.exists())
    }

    @Test
    fun `installedModels lists versioned directories`() {
        val cacheDir = tempFolder.newFolder("cache")
        val manager = SherpaModelManager(cacheDir)

        val modelA = File(cacheDir, "modelA").apply { mkdir() }
        File(modelA, "version.txt").writeText("v1.0.0")
        File(modelA, "data.bin").writeBytes(ByteArray(500))

        val models = manager.installedModels()
        assertEquals(1, models.size)
        assertEquals("v1.0.0", models[0].version)
        assertEquals(506L, models[0].totalBytes)
    }

    @Test
    fun `installedModels returns empty for missing cache`() {
        val cacheDir = tempFolder.newFolder("cache")
        val manager = SherpaModelManager(cacheDir)
        assertTrue(manager.installedModels().isEmpty())
    }

    @Test
    fun `download writes install metadata and filters installed models by type`() {
        val cacheDir = tempFolder.newFolder("cache")
        val modelBytes = "silero-model".encodeToByteArray()
        val modelUrl = "https://github.com/devpods/models/silero_vad.onnx"
        val manager = SherpaModelManager(
            modelCacheDir = cacheDir,
            downloadTransport = FakeSherpaModelDownloadTransport(
                mapOf(modelUrl to modelBytes),
            ),
            usableSpaceProvider = { Long.MAX_VALUE },
            tempDirNameProvider = { "install-1" },
        )
        val spec = SherpaModelSpec(
            id = "silero_vad_v4",
            type = SherpaModelType.SILERO_VAD,
            version = "v4.0.0",
            requiredFiles = listOf("silero_vad.onnx"),
            downloadUrlByFile = mapOf("silero_vad.onnx" to modelUrl),
            expectedSha256ByFile = mapOf("silero_vad.onnx" to sha256(modelBytes)),
            totalBytes = modelBytes.size.toLong(),
            installRoot = cacheDir.absolutePath,
        )

        val result = manager.download(spec)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isReady)
        val installedModels = manager.installedModels(SherpaModelType.SILERO_VAD)
        assertEquals(1, installedModels.size)
        assertEquals("silero_vad_v4", installedModels[0].modelId)
        assertEquals(SherpaModelType.SILERO_VAD, installedModels[0].modelType)
        assertEquals("v4.0.0", installedModels[0].version)
        assertTrue(manager.installedModels(SherpaModelType.STREAMING_STT).isEmpty())
        assertTrue(File(cacheDir, "silero_vad_v4/.install-metadata.properties").isFile)
    }

    @Test
    fun `download preserves previous ready install when new payload fails checksum`() {
        val cacheDir = tempFolder.newFolder("cache")
        val goodBytes = "good-model".encodeToByteArray()
        val badBytes = "bad-model".encodeToByteArray()
        val modelUrl = "https://github.com/devpods/models/silero_vad.onnx"
        val successfulManager = SherpaModelManager(
            modelCacheDir = cacheDir,
            downloadTransport = FakeSherpaModelDownloadTransport(
                mapOf(modelUrl to goodBytes),
            ),
            usableSpaceProvider = { Long.MAX_VALUE },
            tempDirNameProvider = { "install-good" },
        )
        val baseSpec = SherpaModelSpec(
            id = "silero_vad_v4",
            type = SherpaModelType.SILERO_VAD,
            version = "v1.0.0",
            requiredFiles = listOf("silero_vad.onnx"),
            downloadUrlByFile = mapOf("silero_vad.onnx" to modelUrl),
            expectedSha256ByFile = mapOf("silero_vad.onnx" to sha256(goodBytes)),
            totalBytes = goodBytes.size.toLong(),
            installRoot = cacheDir.absolutePath,
        )
        assertTrue(successfulManager.download(baseSpec).isSuccess)

        val failingManager = SherpaModelManager(
            modelCacheDir = cacheDir,
            downloadTransport = FakeSherpaModelDownloadTransport(
                mapOf(modelUrl to badBytes),
            ),
            usableSpaceProvider = { Long.MAX_VALUE },
            tempDirNameProvider = { "install-bad" },
        )
        val updatedSpec = baseSpec.copy(
            version = "v2.0.0",
            expectedSha256ByFile = mapOf("silero_vad.onnx" to sha256("new-good-model".encodeToByteArray())),
        )

        val result = failingManager.download(updatedSpec)

        assertTrue(result.isFailure)
        val finalBytes = File(cacheDir, "silero_vad_v4/silero_vad.onnx").readBytes()
        assertTrue(finalBytes.contentEquals(goodBytes))
        assertEquals("v1.0.0", failingManager.installedModels().single().version)
        val tmpDir = File(cacheDir, ".tmp")
        assertTrue(!tmpDir.exists() || tmpDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `download fails preflight on low storage without leaving temp install`() {
        val cacheDir = tempFolder.newFolder("cache")
        val modelUrl = "https://github.com/devpods/models/silero_vad.onnx"
        val manager = SherpaModelManager(
            modelCacheDir = cacheDir,
            downloadTransport = FakeSherpaModelDownloadTransport(
                mapOf(modelUrl to "silero-model".encodeToByteArray()),
            ),
            usableSpaceProvider = { 0L },
            tempDirNameProvider = { "install-low-storage" },
        )
        val spec = SherpaModelSpec(
            id = "silero_vad_v4",
            type = SherpaModelType.SILERO_VAD,
            version = "v4.0.0",
            requiredFiles = listOf("silero_vad.onnx"),
            downloadUrlByFile = mapOf("silero_vad.onnx" to modelUrl),
            expectedSha256ByFile = mapOf("silero_vad.onnx" to sha256("silero-model".encodeToByteArray())),
            totalBytes = 12L,
            installRoot = cacheDir.absolutePath,
        )

        val result = manager.download(spec)

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
        assertTrue(result.exceptionOrNull()!!.message!!.contains("low storage", ignoreCase = true))
        assertFalse(File(cacheDir, "silero_vad_v4").exists())
        assertFalse(File(cacheDir, ".tmp/install-low-storage").exists())
    }

    @Test
    fun `download rejects model sources outside the trusted https allowlist`() {
        val cacheDir = tempFolder.newFolder("cache")
        val manager = SherpaModelManager(
            modelCacheDir = cacheDir,
            downloadTransport = FakeSherpaModelDownloadTransport(emptyMap()),
            usableSpaceProvider = { Long.MAX_VALUE },
            tempDirNameProvider = { "install-untrusted" },
        )
        val spec = SherpaModelSpec(
            id = "silero_vad_v4",
            type = SherpaModelType.SILERO_VAD,
            version = "v4.0.0",
            requiredFiles = listOf("silero_vad.onnx"),
            downloadUrlByFile = mapOf("silero_vad.onnx" to "http://example.com/silero_vad.onnx"),
            installRoot = cacheDir.absolutePath,
        )

        val result = manager.download(spec)

        assertTrue(result.isFailure)
        assertEquals(
            "No download URL configured for one or more files in model silero_vad_v4",
            result.exceptionOrNull()?.message,
        )
    }

    // ---- Benchmark artifact store tests ----

    @Test
    fun `benchmark artifact store writes report to files dir`() {
        val filesDir = tempFolder.newFolder("files")
        val report = SherpaCommandBenchmarkReport(
            runId = "test-run-1",
            runAtMs = 1_000L,
            commandSet = listOf("status"),
            samples = listOf(
                CommandBenchmarkSample(
                    command = "status",
                    engine = CommandBenchmarkEngine.SHERPA_STT,
                    wakeToReadyMs = 400L,
                ),
            ),
            engineSummaries = emptyList(),
            promotionState = SherpaPromotionState.EXPERIMENTAL,
            promotionReasons = listOf("test"),
        )

        val path = SherpaBenchmarkArtifactStore.store(report, filesDir)
        assertTrue(File(path).exists())
        assertTrue(path.contains("test-run-1"))
    }

    @Test
    fun `latestArtifact returns most recently stored report`() {
        val filesDir = tempFolder.newFolder("files")
        val oldReport = SherpaCommandBenchmarkReport(
            runId = "old-run",
            runAtMs = 1_000L,
            commandSet = listOf("status"),
            samples = emptyList(),
            engineSummaries = emptyList(),
            promotionState = SherpaPromotionState.HIDDEN,
            promotionReasons = listOf("old"),
        )
        val newReport = SherpaCommandBenchmarkReport(
            runId = "new-run",
            runAtMs = 2_000L,
            commandSet = listOf("status", "cancel"),
            samples = emptyList(),
            engineSummaries = emptyList(),
            promotionState = SherpaPromotionState.PRODUCTION,
            promotionReasons = listOf("new"),
        )

        SherpaBenchmarkArtifactStore.store(oldReport, filesDir)
        Thread.sleep(10) // ensure different modification times
        SherpaBenchmarkArtifactStore.store(newReport, filesDir)

        val latest = SherpaBenchmarkArtifactStore.latestArtifact(filesDir)
        assertNotNull(latest)
        assertEquals(SherpaPromotionState.PRODUCTION, latest!!.promotionState)
    }

    @Test
    fun `latestArtifact returns null for empty directory`() {
        val filesDir = tempFolder.newFolder("empty")
        val latest = SherpaBenchmarkArtifactStore.latestArtifact(filesDir)
        assertNull(latest)
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

private class FakeSherpaModelDownloadTransport(
    private val payloadsByUrl: Map<String, ByteArray>,
) : SherpaModelDownloadTransport {
    override fun contentLength(url: URL): Long {
        return payloadsByUrl[url.toString()]?.size?.toLong() ?: -1L
    }

    override fun download(url: URL, dest: File, onChunk: (Long) -> Unit) {
        val payload = requireNotNull(payloadsByUrl[url.toString()]) {
            "No fake payload configured for $url"
        }
        dest.parentFile?.mkdirs()
        dest.writeBytes(payload)
        onChunk(payload.size.toLong())
    }
}
