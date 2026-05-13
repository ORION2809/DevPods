package com.openclaw.relay

import android.speech.SpeechRecognizer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechRecognitionErrorPolicyTest {
    @Test
    fun `busy style errors request recognizer reset`() {
        assertTrue(classifySpeechRecognizerError(SpeechRecognizer.ERROR_AUDIO).shouldResetSession)
        assertTrue(classifySpeechRecognizerError(SpeechRecognizer.ERROR_CLIENT).shouldResetSession)
        assertTrue(classifySpeechRecognizerError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY).shouldResetSession)
    }

    @Test
    fun `timeout style errors do not request recognizer reset`() {
        assertFalse(classifySpeechRecognizerError(SpeechRecognizer.ERROR_NO_MATCH).shouldResetSession)
        assertFalse(classifySpeechRecognizerError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT).shouldResetSession)
        assertFalse(classifySpeechRecognizerError(SpeechRecognizer.ERROR_NETWORK).shouldResetSession)
    }
}