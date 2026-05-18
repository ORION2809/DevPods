package com.openclaw.relay

enum class AudioRouteFallbackDecision {
    USE_REQUESTED_ROUTE,
    USE_PHONE_MIC_FALLBACK,
    BLOCK_LISTENING,
}

data class AudioRouteFallbackResult(
    val decision: AudioRouteFallbackDecision,
    val routeSnapshot: RelayAudioRouteSnapshot,
    val shouldClearRequestedRoute: Boolean,
    val blockingMessage: String? = null,
)

object AudioRouteFallbackPolicy {
    private const val PHONE_MIC_TYPE = "Phone microphone"

    fun resolve(
        requestedRoute: RelayAudioRouteSnapshot,
        allowPhoneMicFallback: Boolean,
        allowRouteSettle: Boolean = false,
    ): AudioRouteFallbackResult {
        if (requestedRoute.isActive && requestedRoute.isReadyForSpeechCapture) {
            return AudioRouteFallbackResult(
                decision = AudioRouteFallbackDecision.USE_REQUESTED_ROUTE,
                routeSnapshot = requestedRoute,
                shouldClearRequestedRoute = false,
            )
        }

        if (requestedRoute.isActive && allowRouteSettle) {
            return AudioRouteFallbackResult(
                decision = AudioRouteFallbackDecision.USE_REQUESTED_ROUTE,
                routeSnapshot = requestedRoute,
                shouldClearRequestedRoute = false,
            )
        }

        if (!allowPhoneMicFallback) {
            return AudioRouteFallbackResult(
                decision = AudioRouteFallbackDecision.BLOCK_LISTENING,
                routeSnapshot = requestedRoute,
                shouldClearRequestedRoute = true,
                blockingMessage = "Bluetooth routing failed. Enable phone microphone fallback or check the connected audio device.",
            )
        }

        return AudioRouteFallbackResult(
            decision = AudioRouteFallbackDecision.USE_PHONE_MIC_FALLBACK,
            routeSnapshot = requestedRoute.toPhoneMicFallbackSnapshot(),
            shouldClearRequestedRoute = true,
        )
    }

    private fun RelayAudioRouteSnapshot.toPhoneMicFallbackSnapshot(): RelayAudioRouteSnapshot {
        val reason = status.ifBlank { "Bluetooth routing failed" }
        return copy(
            isActive = true,
            isReadyForSpeechCapture = true,
            isPhoneMicFallback = true,
            status = "Phone microphone fallback active after headset route failed: $reason",
            selectedDeviceName = null,
            selectedDeviceType = PHONE_MIC_TYPE,
            communicationDeviceName = null,
            communicationDeviceType = PHONE_MIC_TYPE,
            proof = AudioRouteProof(
                routeState = AudioRouteProofState.ROUTE_PHONE_MIC,
                routeRequestedAtMs = proof.routeRequestedAtMs,
                routeReadyAtMs = proof.routeReadyAtMs,
                selectedDeviceType = PHONE_MIC_TYPE,
                selectedDeviceHash = null,
                communicationDeviceCount = proof.communicationDeviceCount,
            ),
        )
    }
}
