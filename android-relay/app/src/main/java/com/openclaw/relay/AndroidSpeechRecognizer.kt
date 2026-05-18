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
    val errorCode: Int? = null,
    val endpointReason: SpeechEndpointReason = SpeechEndpointReason.UNKNOWN_ERROR,
)

internal fun classifySpeechRecognizerError(error: Int): SpeechRecognitionFailure {
    return when (error) {
        SpeechRecognizer.ERROR_AUDIO -> SpeechRecognitionFailure(
            message = "Microphone error. Check audio permissions and the active communication route.",
            shouldResetSession = true,
            errorCode = error,
            endpointReason = SpeechEndpointReason.AUDIO_ERROR,
        )

        SpeechRecognizer.ERROR_CLIENT -> SpeechRecognitionFailure(
            message = "Speech recognition client error. Resetting the microphone session for the next attempt.",
            shouldResetSession = true,
            errorCode = error,
            endpointReason = SpeechEndpointReason.CLIENT_ERROR,
        )

        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechRecognitionFailure(
            message = "Microphone permission is missing.",
            shouldResetSession = false,
            errorCode = error,
            endpointReason = SpeechEndpointReason.PERMISSION_DENIED,
        )

        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> SpeechRecognitionFailure(
            message = "Speech recognition network error. Check connectivity.",
            shouldResetSession = false,
            errorCode = error,
            endpointReason = SpeechEndpointReason.NETWORK_ERROR,
        )

        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> SpeechRecognitionFailure(
            message = "No speech was detected. Try again.",
            shouldResetSession = false,
            errorCode = error,
            endpointReason = if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                SpeechEndpointReason.TIMEOUT
            } else {
                SpeechEndpointReason.NO_SPEECH
            },
        )

        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechRecognitionFailure(
            message = "Speech recognition was still busy. Resetting the microphone session for the next attempt.",
            shouldResetSession = true,
            errorCode = error,
            endpointReason = SpeechEndpointReason.RECOGNIZER_BUSY,
        )

        SpeechRecognizer.ERROR_SERVER -> SpeechRecognitionFailure(
            message = "Speech recognition server error.",
            shouldResetSession = false,
            errorCode = error,
            endpointReason = SpeechEndpointReason.UNKNOWN_ERROR,
        )

        else -> SpeechRecognitionFailure(
            message = "Speech recognition failed with error code $error.",
            shouldResetSession = false,
            errorCode = error,
            endpointReason = SpeechEndpointReason.UNKNOWN_ERROR,
        )
    }
}

internal class AndroidSpeechRecognizer(context: Context) {
    private val appContext = context.applicationContext
    private var speechRecognizer: SpeechRecognizer? = null

    fun isRecognitionAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    fun isOnDeviceRecognitionAvailable(): Boolean = SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)

    fun startListening(
        completeSilenceMs: Long = 750L,
        minimumLengthMs: Long = 300L,
        preferOffline: Boolean = false,
        onDeviceOnly: Boolean = false,
        onPartialTranscript: (String) -> Unit,
        onFinalTranscript: (String) -> Unit,
        onError: (SpeechRecognitionFailure) -> Unit,
        onRecognizerCreated: () -> Unit = {},
        onListeningStarted: () -> Unit = {},
        onReadyForSpeech: () -> Unit = {},
        onBeginningOfSpeech: () -> Unit = {},
        onRmsChanged: (Float) -> Unit = {},
        onEndOfSpeech: () -> Unit = {},
    ) {
        if (!isRecognitionAvailable()) {
            onError(
                SpeechRecognitionFailure(
                    message = "Speech recognition is unavailable on this device.",
                    shouldResetSession = false,
                    endpointReason = SpeechEndpointReason.UNKNOWN_ERROR,
                ),
            )
            return
        }

        if (onDeviceOnly && !isOnDeviceRecognitionAvailable()) {
            onError(
                SpeechRecognitionFailure(
                    message = "On-device speech recognition is unavailable on this device.",
                    shouldResetSession = false,
                    endpointReason = SpeechEndpointReason.UNKNOWN_ERROR,
                ),
            )
            return
        }

        val recognizer = speechRecognizer ?: createRecognizer(onDeviceOnly)
            .also { speechRecognizer = it }
        onRecognizerCreated()

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                onReadyForSpeech()
            }

            override fun onBeginningOfSpeech() {
                onBeginningOfSpeech()
            }

            override fun onRmsChanged(rmsdB: Float) {
                onRmsChanged(rmsdB)
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                onEndOfSpeech()
            }

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
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline || onDeviceOnly)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, completeSilenceMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, minimumLengthMs)
        }

        recognizer.startListening(intent)
        onListeningStarted()
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

    private fun createRecognizer(onDeviceOnly: Boolean): SpeechRecognizer =
        if (onDeviceOnly) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        } else {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }
}
