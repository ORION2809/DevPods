package com.openclaw.relay

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.LANG_MISSING_DATA
import android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class AndroidTtsSpeaker(
    context: Context,
    private val onError: (String) -> Unit = {},
    private val onReadyChanged: (Boolean) -> Unit = {},
    private val onSpeakingChanged: (Boolean) -> Unit = {},
) {
    private val applicationContext = context.applicationContext
    private var textToSpeech: TextToSpeech? = null
    private var ready = false

    init {
        textToSpeech = TextToSpeech(applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            onReadyChanged(ready)
            if (!ready) {
                onError("Text-to-speech initialization failed with status $status.")
                return@TextToSpeech
            }

            val languageResult = textToSpeech?.setLanguage(Locale.US)
            if (languageResult == LANG_MISSING_DATA || languageResult == LANG_NOT_SUPPORTED) {
                ready = false
                onReadyChanged(false)
                onError("US English text-to-speech data is unavailable on this device.")
                return@TextToSpeech
            }

            textToSpeech?.setSpeechRate(1.05f)
            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    onSpeakingChanged(true)
                }

                override fun onDone(utteranceId: String?) {
                    onSpeakingChanged(false)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    onSpeakingChanged(false)
                    this@AndroidTtsSpeaker.onError("Text-to-speech playback failed.")
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    onSpeakingChanged(false)
                    this@AndroidTtsSpeaker.onError("Text-to-speech playback failed with error code $errorCode.")
                }
            })
        }
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) {
            if (text.isNotBlank() && !ready) {
                onError("Text-to-speech is not ready yet.")
            }
            return
        }

        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "relay-${System.currentTimeMillis()}")
    }

    fun stop() {
        onSpeakingChanged(false)
        textToSpeech?.stop()
    }

    fun close() {
        onSpeakingChanged(false)
        onReadyChanged(false)
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
    }
}