package com.openclaw.relay

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SherpaBenchmarkArtifactRedactionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `default artifact storage redacts raw transcript text`() {
        val samples = listOf(
            CommandBenchmarkSample(
                command = "status",
                engine = CommandBenchmarkEngine.SHERPA_STT,
                transcriptText = "what is the current status",
                actualEngineId = "sherpa_streaming",
            ),
            CommandBenchmarkSample(
                command = "run tests",
                engine = CommandBenchmarkEngine.PLATFORM_STT,
                transcriptText = "run all tests now",
                actualEngineId = "platform_speech_recognizer",
            ),
        )

        val report = OfflineSpeechEvaluation.evaluateCommandBenchmark(
            samples = samples,
            context = null,
            includeRawTranscript = false,
        )

        // Store the report manually to inspect the serialized output
        val storedPath = SherpaBenchmarkArtifactStore.store(report, tempFolder.root)
        val fileContent = java.io.File(storedPath).readText()
        println("=== ARTIFACT CONTENT ===")
        println(fileContent)
        println("=== END ARTIFACT CONTENT ===")

        assertTrue("Artifact file should not be empty", fileContent.isNotBlank())
        assertFalse("Artifact should not contain raw transcript 'what is the current status'", fileContent.contains("what is the current status"))
        assertFalse("Artifact should not contain raw transcript 'run all tests now'\nActual content:\n$fileContent", fileContent.contains("run all tests now"))
        assertTrue("Artifact should still contain command names", fileContent.contains(""""command": "status"""))
        assertTrue("Artifact should still contain engine IDs", fileContent.contains(""""actualEngineId": "sherpa_streaming"""))
    }

    @Test
    fun `diagnostic mode preserves raw transcript text`() {
        val samples = listOf(
            CommandBenchmarkSample(
                command = "status",
                engine = CommandBenchmarkEngine.SHERPA_STT,
                transcriptText = "what is the current status",
                actualEngineId = "sherpa_streaming",
            ),
        )

        val report = OfflineSpeechEvaluation.evaluateCommandBenchmark(
            samples = samples,
            context = null,
            includeRawTranscript = true,
        )

        val storedPath = SherpaBenchmarkArtifactStore.store(report, tempFolder.root)
        val fileContent = java.io.File(storedPath).readText()

        assertTrue("Diagnostic artifact should contain raw transcript", fileContent.contains("what is the current status"))
    }
}
