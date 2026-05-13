package com.openclaw.relay

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

internal data class SpeechRecognitionFailure(
    val message: String,
    val shouldResetSession: Boolean,
)

internal fun classifySpeechRecognizerError(error: Int): SpeechRecognitionFailure {
    return when (error) {
        SpeechRecognizer.ERROR_AUDIO -> SpeechRecognitionFailure(
            message = "Microphone error. Check audio permissions and the active communication route.",
            shouldResetSession = true,
        )

        SpeechRecognizer.ERROR_CLIENT -> SpeechRecognitionFailure(
            message = "Speech recognition client error. Resetting the microphone session for the next attempt.",
            shouldResetSession = true,
        )

        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechRecognitionFailure(
            message = "Microphone permission is missing.",
            shouldResetSession = false,
        )

        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> SpeechRecognitionFailure(
            message = "Speech recognition network error. Check connectivity.",
            shouldResetSession = false,
        )

        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> SpeechRecognitionFailure(
            message = "No speech was detected. Try again.",
            shouldResetSession = false,
        )

        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechRecognitionFailure(
            message = "Speech recognition was still busy. Resetting the microphone session for the next attempt.",
            shouldResetSession = true,
        )

        SpeechRecognizer.ERROR_SERVER -> SpeechRecognitionFailure(
            message = "Speech recognition server error.",
            shouldResetSession = false,
        )

        else -> SpeechRecognitionFailure(
            message = "Speech recognition failed with error code $error.",
            shouldResetSession = false,
        )
    }
}

internal class AndroidSpeechRecognizer(context: Context) {
    private val appContext = context.applicationContext
    private var speechRecognizer: SpeechRecognizer? = null

    fun isRecognitionAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    fun startListening(
        onPartialTranscript: (String) -> Unit,
        onFinalTranscript: (String) -> Unit,
        onError: (SpeechRecognitionFailure) -> Unit,
    ) {
        if (!isRecognitionAvailable()) {
            onError(
                SpeechRecognitionFailure(
                    message = "Speech recognition is unavailable on this device.",
                    shouldResetSession = false,
                ),
            )
            return
        }

        val recognizer = speechRecognizer ?: SpeechRecognizer
            .createSpeechRecognizer(appContext)
            .also { speechRecognizer = it }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                val failure = classifySpeechRecognizerError(error)
                if (failure.shouldResetSession) {
                    destroyRecognizer()
                }

                onError(failure)
            }

            override fun onResults(results: Bundle?) {
                val transcript = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()

                if (transcript.isNotBlank()) {
                    onFinalTranscript(transcript)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()

                if (partial.isNotBlank()) {
                    onPartialTranscript(partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 750L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
        }

        recognizer.startListening(intent)
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
    }

    fun resetSession() {
        destroyRecognizer()
    }

    fun destroy() {
        destroyRecognizer()
    }

    private fun destroyRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}