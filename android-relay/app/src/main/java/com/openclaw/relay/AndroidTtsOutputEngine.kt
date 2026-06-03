package com.openclaw.relay

internal class AndroidTtsOutputEngine(
    private val speaker: AndroidTtsSpeaker,
) : WarmableSpeechOutputEngine {
    override val id: String = "android_tts_output"

    override suspend fun speak(request: TtsRequest, callbacks: TtsCallbacks) {
        speaker.speak(
            text = request.text,
            utteranceId = request.utteranceId,
            queueMode = request.queueMode,
            onComplete = callbacks.onComplete,
        )
    }

    override suspend fun warm(): Boolean {
        return speaker.warm()
    }

    override suspend fun stop(reason: TtsStopReason) {
        speaker.stop()
    }

    override fun close() {
        speaker.close()
    }
}
