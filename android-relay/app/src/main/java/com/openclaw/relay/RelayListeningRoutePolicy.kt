package com.openclaw.relay

internal data class RelayListeningRouteDecision(
    val shouldStartListening: Boolean,
    val errorMessage: String,
)

internal data class RelayListeningRouteCheckResult(
    val shouldStartListening: Boolean,
    val shouldRetry: Boolean,
    val errorMessage: String,
)

internal fun listeningRouteSettleDelays(useBluetoothRouting: Boolean): List<Long> {
    if (!useBluetoothRouting) {
        return emptyList()
    }

    return listOf(100L, 200L, 400L)
}

internal fun decideListeningRouteAfterSettle(
    useBluetoothRouting: Boolean,
    routeSnapshot: RelayAudioRouteSnapshot,
): RelayListeningRouteDecision {
    if (!useBluetoothRouting || routeSnapshot.isReadyForSpeechCapture) {
        return RelayListeningRouteDecision(
            shouldStartListening = true,
            errorMessage = "",
        )
    }

    val message = when {
        routeSnapshot.status.equals("No communication headset detected", ignoreCase = true) -> {
            "No communication headset microphone is available. Try the supported wake path again or use the device assistant fallback."
        }

        routeSnapshot.status.contains("no communication microphone route is active", ignoreCase = true) -> {
            "Headset output is visible, but the headset microphone route never became active. Try the supported wake path again or use the device assistant fallback."
        }

        routeSnapshot.status.contains("built-in audio", ignoreCase = true) -> {
            "Headset output is visible, but the microphone route stayed on built-in audio. Try the supported wake path again or use the device assistant fallback."
        }

        else -> {
            "Microphone route is not ready yet. Try the supported wake path again or use the device assistant fallback."
        }
    }

    return RelayListeningRouteDecision(
        shouldStartListening = false,
        errorMessage = message,
    )
}

internal fun assessListeningRouteCheck(
    useBluetoothRouting: Boolean,
    routeSnapshot: RelayAudioRouteSnapshot,
    attemptIndex: Int,
    totalAttempts: Int,
): RelayListeningRouteCheckResult {
    val decision = decideListeningRouteAfterSettle(
        useBluetoothRouting = useBluetoothRouting,
        routeSnapshot = routeSnapshot,
    )
    if (decision.shouldStartListening) {
        return RelayListeningRouteCheckResult(
            shouldStartListening = true,
            shouldRetry = false,
            errorMessage = "",
        )
    }

    return RelayListeningRouteCheckResult(
        shouldStartListening = false,
        shouldRetry = attemptIndex < totalAttempts - 1,
        errorMessage = decision.errorMessage,
    )
}