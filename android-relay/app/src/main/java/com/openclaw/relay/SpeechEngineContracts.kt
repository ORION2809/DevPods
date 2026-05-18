package com.openclaw.relay

internal data class SpeechEngineCapabilities(
    val engineId: String,
    val isAvailable: Boolean,
    val supportsPartialResults: Boolean,
    val supportsEndpointingHints: Boolean,
    val supportsOnDeviceRecognition: Boolean,
    val storesRawAudio: Boolean,
)

internal data class SpeechSessionRequest(
    val sessionId: String,
    val wakeSignal: String? = null,
    val completeSilenceMs: Long = 750L,
    val minimumLengthMs: Long = 300L,
    val preferOffline: Boolean = false,
    val onDeviceOnly: Boolean = false,
)

internal data class SpeechCallbacks(
    val onPartialTranscript: (String) -> Unit = {},
    val onFinalTranscript: (String) -> Unit = {},
    val onError: (SpeechRecognitionFailure) -> Unit = {},
    val onRecognizerCreated: () -> Unit = {},
    val onListeningStarted: () -> Unit = {},
    val onReadyForSpeech: () -> Unit = {},
    val onBeginningOfSpeech: () -> Unit = {},
    val onRmsChanged: (Float) -> Unit = {},
    val onEndOfSpeech: () -> Unit = {},
)

internal enum class SpeechStopReason {
    STOP_REQUESTED,
    CANCELLED,
    ENGINE_RESET,
    SERVICE_DESTROYED,
}

internal interface SpeechInputEngine {
    val id: String
    fun capabilities(): SpeechEngineCapabilities
    suspend fun start(request: SpeechSessionRequest, callbacks: SpeechCallbacks)
    suspend fun stop(reason: SpeechStopReason)
    fun destroy()
}

internal data class VadProbeRequest(
    val sessionId: String,
    val durationMs: Long = 1_200L,
)

internal data class VadCallbacks(
    val onObservation: (PlatformVadObservation) -> Unit = {},
    val onError: (String) -> Unit = {},
)

internal interface VadProbe {
    val id: String
    suspend fun start(request: VadProbeRequest, callbacks: VadCallbacks)
    suspend fun stop()
}

internal object PlatformCallbackVadProbe {
    const val ID: String = "platform_callback_vad"

    fun observe(metrics: SpeechSessionMetrics): PlatformVadObservation =
        metrics.toPlatformVadObservation()
}

internal data class TtsRequest(
    val utteranceId: String,
    val text: String,
)

internal data class TtsCallbacks(
    val onMetrics: (TtsPlaybackMetrics) -> Unit = {},
    val onComplete: () -> Unit = {},
    val onError: (String) -> Unit = {},
)

internal enum class TtsStopReason {
    STOP_REQUESTED,
    BARGE_IN,
    SUPERSEDED,
    SERVICE_DESTROYED,
}

internal interface SpeechOutputEngine {
    val id: String
    suspend fun speak(request: TtsRequest, callbacks: TtsCallbacks)
    suspend fun stop(reason: TtsStopReason)
    fun close()
}
