package com.openclaw.relay

internal class AndroidTtsOutputEngine(
    private val speaker: AndroidTtsSpeaker,
) : SpeechOutputEngine {
    override val id: String = "android_tts_output"

    override suspend fun speak(request: TtsRequest, callbacks: TtsCallbacks) {
        speaker.speak(
            text = request.text,
            utteranceId = request.utteranceId,
            onComplete = callbacks.onComplete,
        )
    }

    override suspend fun stop(reason: TtsStopReason) {
        speaker.stop()
    }

    override fun close() {
        speaker.close()
    }
}
