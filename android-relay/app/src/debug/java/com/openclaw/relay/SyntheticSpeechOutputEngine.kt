package com.openclaw.relay

import kotlinx.coroutines.delay

/**
 * Debug-only synthetic speech output engine for T1 emulator automation.
 *
 * Simulates TTS timing without speaking actual audio.
 * Supports scripted delays, failures, and barge-in timing.
 */
internal class SyntheticSpeechOutputEngine(
    override val id: String = "synthetic_tts_output",
) : SpeechOutputEngine {

    private var activeScript: SyntheticTtsScript? = null
    private var isSpeaking = false
    private var currentCallbacks: TtsCallbacks? = null
    private var currentRequest: TtsRequest? = null
    private var currentStartedAtMs: Long? = null

    override suspend fun speak(request: TtsRequest, callbacks: TtsCallbacks) {
        val script = activeScript ?: SyntheticTtsScript()

        if (script.shouldFail) {
            callbacks.onError("Synthetic TTS failure")
            return
        }

        isSpeaking = true
        currentCallbacks = callbacks
        currentRequest = request

        val now = System.currentTimeMillis()
        callbacks.onMetrics(
            TtsPlaybackMetrics(
                utteranceId = request.utteranceId,
                textLength = request.text.length,
                requestedAtMs = now,
                event = TtsPlaybackEvent.REQUESTED,
            )
        )

        delay(script.startDelayMs)

        if (!isSpeaking) return

        val startedAt = System.currentTimeMillis()
        currentStartedAtMs = startedAt
        callbacks.onMetrics(
            TtsPlaybackMetrics(
                utteranceId = request.utteranceId,
                textLength = request.text.length,
                requestedAtMs = now,
                startedAtMs = startedAt,
                event = TtsPlaybackEvent.STARTED,
            )
        )

        delay(script.speakDurationMs)

        if (!isSpeaking) return

        callbacks.onMetrics(
            TtsPlaybackMetrics(
                utteranceId = request.utteranceId,
                textLength = request.text.length,
                requestedAtMs = now,
                startedAtMs = startedAt,
                completedAtMs = System.currentTimeMillis(),
                event = TtsPlaybackEvent.DONE,
            )
        )
        callbacks.onComplete()
        isSpeaking = false
        currentCallbacks = null
        currentRequest = null
        currentStartedAtMs = null
    }

    override suspend fun stop(reason: TtsStopReason) {
        if (isSpeaking) {
            isSpeaking = false
            val request = currentRequest
            val callbacks = currentCallbacks
            val startedAt = currentStartedAtMs
            if (request != null && callbacks != null) {
                val now = System.currentTimeMillis()
                callbacks.onMetrics(
                    TtsPlaybackMetrics(
                        utteranceId = request.utteranceId,
                        textLength = request.text.length,
                        requestedAtMs = now,
                        startedAtMs = startedAt,
                        stoppedAtMs = now,
                        event = TtsPlaybackEvent.STOPPED,
                    )
                )
            }
            currentCallbacks = null
            currentRequest = null
            currentStartedAtMs = null
        }
    }

    override fun close() {
        isSpeaking = false
        currentCallbacks = null
        currentRequest = null
        currentStartedAtMs = null
    }

    fun setScript(script: SyntheticTtsScript) {
        activeScript = script
    }
}

data class SyntheticTtsScript(
    val startDelayMs: Long = 100,
    val speakDurationMs: Long = 800,
    val shouldFail: Boolean = false,
)
