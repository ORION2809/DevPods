package com.openclaw.relay

import kotlinx.coroutines.delay

/**
 * Debug-only synthetic speech input engine for T1 emulator automation.
 *
 * Accepts scripted utterances and failure modes via [SyntheticSpeechScript].
 * Emits the same session lifecycle callbacks as the platform engine.
 */
internal class SyntheticSpeechInputEngine(
    override val id: String = "synthetic_speech_input",
) : SpeechInputEngine {

    private var activeScript: SyntheticSpeechScript? = null
    private var isRunning = false

    override fun capabilities(): SpeechEngineCapabilities =
        SpeechEngineCapabilities(
            engineId = id,
            isAvailable = true,
            supportsPartialResults = true,
            supportsEndpointingHints = true,
            supportsOnDeviceRecognition = true,
            storesRawAudio = false,
        )

    override fun prepare(request: SpeechSessionRequest): Boolean = true

    override suspend fun start(request: SpeechSessionRequest, callbacks: SpeechCallbacks) {
        val script = activeScript ?: SyntheticSpeechScript()
        isRunning = true

        callbacks.onRecognizerCreated()
        delay(script.delays.recognizerCreatedMs)

        if (!isRunning) return

        callbacks.onListeningStarted()
        delay(script.delays.listeningStartedMs)

        if (!isRunning) return

        callbacks.onReadyForSpeech()
        delay(script.delays.readyForSpeechMs)

        if (!isRunning) return

        when (script.mode) {
            SyntheticMode.FINAL_TRANSCRIPT -> {
                callbacks.onBeginningOfSpeech()
                delay(script.delays.beginningOfSpeechMs)

                if (!isRunning) return

                if (script.partialText.isNotBlank()) {
                    callbacks.onPartialTranscript(script.partialText)
                    delay(script.delays.partialTranscriptMs)
                }

                if (!isRunning) return

                callbacks.onRmsChanged(script.rmsDb)
                callbacks.onEndOfSpeech()
                delay(script.delays.endOfSpeechMs)

                if (!isRunning) return

                callbacks.onFinalTranscript(script.finalText)
            }

            SyntheticMode.NO_SPEECH -> {
                delay(script.delays.noSpeechTimeoutMs)
                if (isRunning) {
                    callbacks.onError(
                        SpeechRecognitionFailure(
                            message = "No speech detected within timeout",
                            shouldResetSession = true,
                            errorCode = 7,
                            endpointReason = SpeechEndpointReason.NO_SPEECH,
                        )
                    )
                }
            }

            SyntheticMode.RECOGNIZER_BUSY -> {
                callbacks.onError(
                    SpeechRecognitionFailure(
                        message = "Recognition service busy",
                        shouldResetSession = true,
                        errorCode = 8,
                        endpointReason = SpeechEndpointReason.RECOGNIZER_BUSY,
                    )
                )
            }

            SyntheticMode.ROUTE_LOST -> {
                callbacks.onError(
                    SpeechRecognitionFailure(
                        message = "Audio route lost during listening",
                        shouldResetSession = true,
                        errorCode = 3,
                        endpointReason = SpeechEndpointReason.ROUTE_FAILED,
                    )
                )
            }

            SyntheticMode.ENGINE_FAILURE -> {
                callbacks.onError(
                    SpeechRecognitionFailure(
                        message = "Synthetic engine failure",
                        shouldResetSession = true,
                        errorCode = 5,
                        endpointReason = SpeechEndpointReason.UNKNOWN_ERROR,
                    )
                )
            }
        }
    }

    override suspend fun stop(reason: SpeechStopReason) {
        isRunning = false
    }

    override fun destroy() {
        isRunning = false
    }

    fun setScript(script: SyntheticSpeechScript) {
        activeScript = script
    }
}

data class SyntheticSpeechScript(
    val mode: SyntheticMode = SyntheticMode.FINAL_TRANSCRIPT,
    val finalText: String = "run tests",
    val partialText: String = "run",
    val rmsDb: Float = -25f,
    val delays: SyntheticDelays = SyntheticDelays(),
)

data class SyntheticDelays(
    val recognizerCreatedMs: Long = 50,
    val listeningStartedMs: Long = 100,
    val readyForSpeechMs: Long = 150,
    val beginningOfSpeechMs: Long = 200,
    val partialTranscriptMs: Long = 300,
    val endOfSpeechMs: Long = 200,
    val noSpeechTimeoutMs: Long = 5000,
)

enum class SyntheticMode {
    FINAL_TRANSCRIPT,
    NO_SPEECH,
    RECOGNIZER_BUSY,
    ROUTE_LOST,
    ENGINE_FAILURE,
}
