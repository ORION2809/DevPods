package com.openclaw.relay

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechRecognitionErrorPolicyTest {
    @Test
    fun `busy style errors request recognizer reset`() {
        assertTrue(classifySpeechRecognizerError(SpeechRecognizer.ERROR_AUDIO).shouldResetSession)
        assertTrue(classifySpeechRecognizerError(SpeechRecognizer.ERROR_CLIENT).shouldResetSession)
        assertTrue(classifySpeechRecognizerError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY).shouldResetSession)
        assertTrue(classifySpeechRecognizerError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED).shouldResetSession)
        assertTrue(classifySpeechRecognizerError(SpeechRecognizer.ERROR_TOO_MANY_REQUESTS).shouldResetSession)
    }

    @Test
    fun `timeout style errors do not request recognizer reset`() {
        assertFalse(classifySpeechRecognizerError(SpeechRecognizer.ERROR_NO_MATCH).shouldResetSession)
        assertFalse(classifySpeechRecognizerError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT).shouldResetSession)
        assertFalse(classifySpeechRecognizerError(SpeechRecognizer.ERROR_NETWORK).shouldResetSession)
    }

    @Test
    fun `empty final recognition results close session as no speech`() {
        val failure = emptySpeechRecognitionResultFailure()

        assertEquals(SpeechEndpointReason.NO_SPEECH, failure.endpointReason)
        assertEquals(SpeechRecognizer.ERROR_NO_MATCH, failure.errorCode)
        assertFalse(failure.shouldResetSession)
    }
}
