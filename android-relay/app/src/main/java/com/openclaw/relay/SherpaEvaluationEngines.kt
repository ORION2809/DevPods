package com.openclaw.relay

internal class SherpaSpeechInputEngine(
    private val readiness: OfflineSpeechReadiness,
) : SpeechInputEngine {
    override val id: String = "sherpa_streaming"

    override fun capabilities(): SpeechEngineCapabilities =
        SpeechEngineCapabilities(
            engineId = id,
            isAvailable = readiness.canRunOffline,
            supportsPartialResults = true,
            supportsEndpointingHints = true,
            supportsOnDeviceRecognition = true,
            storesRawAudio = false,
        )

    override suspend fun start(request: SpeechSessionRequest, callbacks: SpeechCallbacks) {
        if (!readiness.canRunOffline) {
            callbacks.onError(
                SpeechRecognitionFailure(
                    message = "Offline speech evaluation is unavailable: ${readiness.failureReasons.joinToString(", ")}",
                    shouldResetSession = false,
                    errorCode = null,
                    endpointReason = SpeechEndpointReason.CLIENT_ERROR,
                )
            )
            return
        }

        callbacks.onError(
            SpeechRecognitionFailure(
                message = "Sherpa speech evaluation is configured but the native runtime adapter is not linked in this build.",
                shouldResetSession = false,
                errorCode = null,
                endpointReason = SpeechEndpointReason.CLIENT_ERROR,
            )
        )
    }

    override suspend fun stop(reason: SpeechStopReason) = Unit

    override fun destroy() = Unit
}

internal class SherpaVadProbe(
    private val nativeDependencyLinked: Boolean,
) : VadProbe {
    override val id: String = "sherpa_silero_vad"

    override suspend fun start(request: VadProbeRequest, callbacks: VadCallbacks) {
        if (!nativeDependencyLinked) {
            callbacks.onError("Sherpa VAD evaluation is unavailable until the native dependency is linked.")
            return
        }

        callbacks.onError("Sherpa VAD native runtime adapter is not linked in this build.")
    }

    override suspend fun stop() = Unit
}
