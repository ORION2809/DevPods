package com.openclaw.relay

import com.openclaw.relay.audio.AudioCaptureOwner
import com.openclaw.relay.audio.CaptureOwner
import kotlinx.coroutines.delay

internal class PlatformSpeechRecognizerEngine(
    private val recognizer: AndroidSpeechRecognizer,
    override val id: String = "platform_speech_recognizer",
    private val defaultPreferOffline: Boolean = false,
    private val defaultOnDeviceOnly: Boolean = false,
    private val captureOwner: AudioCaptureOwner? = null,
) : SpeechInputEngine {
    private var activeLease: com.openclaw.relay.audio.CaptureLease? = null

    private var lastRequestedOnDeviceOnly: Boolean = defaultOnDeviceOnly

    override fun capabilities(): SpeechEngineCapabilities =
        SpeechEngineCapabilities(
            engineId = id,
            isAvailable = if (defaultOnDeviceOnly) {
                recognizer.isOnDeviceRecognitionAvailable()
            } else {
                recognizer.isRecognitionAvailable()
            },
            supportsPartialResults = true,
            supportsEndpointingHints = true,
            supportsOnDeviceRecognition = recognizer.isOnDeviceRecognitionAvailable(),
            storesRawAudio = false,
        )

    override fun prepare(request: SpeechSessionRequest): Boolean {
        val onDeviceOnly = request.onDeviceOnly || defaultOnDeviceOnly
        lastRequestedOnDeviceOnly = onDeviceOnly
        return recognizer.prepare(onDeviceOnly = onDeviceOnly)
    }

    override suspend fun start(request: SpeechSessionRequest, callbacks: SpeechCallbacks) {
        if (captureOwner != null) {
            val lease = captureOwner.acquire(CaptureOwner.PLATFORM_SPEECH_RECOGNIZER, request.sessionId)
            if (lease == null) {
                callbacks.onError(
                    SpeechRecognitionFailure(
                        message = "Microphone is busy: ${captureOwner.currentOwnerName()}",
                        shouldResetSession = false,
                        errorCode = null,
                        endpointReason = SpeechEndpointReason.RECOGNIZER_BUSY,
                    ),
                )
                return
            }
            activeLease = lease
        }

        try {
            val onDeviceOnly = request.onDeviceOnly || defaultOnDeviceOnly
            // Only reset if mode changed or an error previously demanded reset
            if (onDeviceOnly != lastRequestedOnDeviceOnly) {
                recognizer.resetSession()
            }
            lastRequestedOnDeviceOnly = onDeviceOnly
            recognizer.startListening(
                completeSilenceMs = request.completeSilenceMs,
                possibleCompleteSilenceMs = request.possibleCompleteSilenceMs,
                minimumLengthMs = request.minimumLengthMs,
                preferOffline = request.preferOffline || defaultPreferOffline,
                onDeviceOnly = onDeviceOnly,
                onPartialTranscript = callbacks.onPartialTranscript,
                onFinalTranscript = callbacks.onFinalTranscript,
                onError = { failure ->
                    if (failure.shouldResetSession) {
                        recognizer.resetSession()
                    }
                    callbacks.onError(failure)
                },
                onRecognizerCreated = callbacks.onRecognizerCreated,
                onListeningStarted = callbacks.onListeningStarted,
                onReadyForSpeech = callbacks.onReadyForSpeech,
                onBeginningOfSpeech = callbacks.onBeginningOfSpeech,
                onRmsChanged = callbacks.onRmsChanged,
                onEndOfSpeech = callbacks.onEndOfSpeech,
            )
        } catch (e: Exception) {
            activeLease?.release()
            activeLease = null
            recognizer.resetSession()
            callbacks.onError(
                SpeechRecognitionFailure(
                    message = "Failed to start platform recognizer: ${e.message}",
                    shouldResetSession = false,
                    errorCode = null,
                    endpointReason = SpeechEndpointReason.AUDIO_ERROR,
                ),
            )
        }
    }

    override suspend fun stop(reason: SpeechStopReason) {
        if (reason == SpeechStopReason.ENGINE_RESET) {
            recognizer.resetSession()
        } else {
            recognizer.stopListening()
        }
        activeLease?.release()
        activeLease = null
    }

    override fun destroy() {
        recognizer.destroy()
        activeLease?.release()
        activeLease = null
    }
}
