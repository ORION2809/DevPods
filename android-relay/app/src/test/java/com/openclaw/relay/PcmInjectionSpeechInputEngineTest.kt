package com.openclaw.relay

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmInjectionSpeechInputEngineTest {
    @Test
    fun `spoken pcm emits lifecycle rms partial and final callbacks without raw audio storage`() = runTest {
        val engine = PcmInjectionSpeechInputEngine()
        val events = mutableListOf<String>()
        val rmsFrames = mutableListOf<Float>()
        var finalTranscript: String? = null
        var failure: SpeechRecognitionFailure? = null

        engine.setScript(
            PcmInjectionScript(
                finalText = "run tests",
                partialText = "run",
                frames = PcmInjectionFixtures.spokenPhrase(durationMs = 360),
                frameDelayMs = 0,
            )
        )

        engine.start(
            SpeechSessionRequest(sessionId = "pcm-spoken"),
            SpeechCallbacks(
                onRecognizerCreated = { events += "created" },
                onListeningStarted = { events += "listening" },
                onReadyForSpeech = { events += "ready" },
                onBeginningOfSpeech = { events += "begin" },
                onRmsChanged = { rmsFrames += it },
                onPartialTranscript = {
                    events += "partial:$it"
                },
                onEndOfSpeech = { events += "end" },
                onFinalTranscript = {
                    events += "final:$it"
                    finalTranscript = it
                },
                onError = {
                    events += "error:${it.endpointReason}"
                    failure = it
                },
            ),
        )

        assertEquals("run tests", finalTranscript)
        assertNull(failure)
        assertTrue(events.indexOf("ready") < events.indexOf("begin"))
        assertTrue(events.indexOf("begin") < events.indexOf("partial:run"))
        assertTrue(events.indexOf("end") < events.indexOf("final:run tests"))
        assertTrue(rmsFrames.isNotEmpty())
        assertTrue(rmsFrames.any { it > PcmInjectionScript.DEFAULT_SPEECH_THRESHOLD_DB })
        assertFalse(engine.capabilities().storesRawAudio)
    }

    @Test
    fun `silence pcm completes as no speech and never emits final transcript`() = runTest {
        val engine = PcmInjectionSpeechInputEngine()
        var finalTranscript: String? = null
        var failure: SpeechRecognitionFailure? = null

        engine.setScript(
            PcmInjectionScript(
                finalText = "ignored",
                partialText = "ignored",
                frames = PcmInjectionFixtures.silence(durationMs = 360),
                frameDelayMs = 0,
            )
        )

        engine.start(
            SpeechSessionRequest(sessionId = "pcm-silence"),
            SpeechCallbacks(
                onFinalTranscript = { finalTranscript = it },
                onError = { failure = it },
            ),
        )

        assertNull(finalTranscript)
        assertEquals(SpeechEndpointReason.NO_SPEECH, failure?.endpointReason)
        assertEquals(PcmInjectionSpeechInputEngine.ERROR_NO_SPEECH, failure?.errorCode)
    }

    @Test
    fun `pcm injection rejects non sixteen kilohertz fixtures`() = runTest {
        val engine = PcmInjectionSpeechInputEngine()
        var failure: SpeechRecognitionFailure? = null

        engine.setScript(
            PcmInjectionScript(
                finalText = "run tests",
                partialText = "run",
                sampleRateHz = 44_100,
                frames = ShortArray(44_100 / 10) { 4_000.toShort() },
                frameDelayMs = 0,
            )
        )

        engine.start(
            SpeechSessionRequest(sessionId = "pcm-invalid-rate"),
            SpeechCallbacks(onError = { failure = it }),
        )

        assertEquals(SpeechEndpointReason.CLIENT_ERROR, failure?.endpointReason)
        assertEquals(PcmInjectionSpeechInputEngine.ERROR_UNSUPPORTED_FORMAT, failure?.errorCode)
    }
}
