package com.openclaw.relay

internal class PlatformSpeechRecognizerEngine(
    private val recognizer: AndroidSpeechRecognizer,
    override val id: String = "platform_speech_recognizer",
    private val defaultPreferOffline: Boolean = false,
    private val defaultOnDeviceOnly: Boolean = false,
) : SpeechInputEngine {
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

    override suspend fun start(request: SpeechSessionRequest, callbacks: SpeechCallbacks) {
        recognizer.resetSession()
        recognizer.startListening(
            completeSilenceMs = request.completeSilenceMs,
            minimumLengthMs = request.minimumLengthMs,
            preferOffline = request.preferOffline || defaultPreferOffline,
            onDeviceOnly = request.onDeviceOnly || defaultOnDeviceOnly,
            onPartialTranscript = callbacks.onPartialTranscript,
            onFinalTranscript = callbacks.onFinalTranscript,
            onError = callbacks.onError,
            onRecognizerCreated = callbacks.onRecognizerCreated,
            onListeningStarted = callbacks.onListeningStarted,
            onReadyForSpeech = callbacks.onReadyForSpeech,
            onBeginningOfSpeech = callbacks.onBeginningOfSpeech,
            onRmsChanged = callbacks.onRmsChanged,
            onEndOfSpeech = callbacks.onEndOfSpeech,
        )
    }

    override suspend fun stop(reason: SpeechStopReason) {
        if (reason == SpeechStopReason.ENGINE_RESET) {
            recognizer.resetSession()
        } else {
            recognizer.stopListening()
        }
    }

    override fun destroy() {
        recognizer.destroy()
    }
}
