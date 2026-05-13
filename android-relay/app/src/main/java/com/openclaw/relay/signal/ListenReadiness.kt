package com.openclaw.relay.signal

import com.openclaw.relay.RelayAudioRouteSnapshot

enum class ListenReadiness {
    READY,
    DEGRADED,
    BLOCKED,
}

data class ListenReadinessDetail(
    val readiness: ListenReadiness,
    val reason: String,
    val userFacingMessage: String,
)

fun computeListenReadiness(
    deviceState: EarbudDeviceState?,
    audioRoute: RelayAudioRouteSnapshot,
    useBluetoothRouting: Boolean,
    speechRecognitionAvailable: Boolean,
): ListenReadinessDetail {
    if (!speechRecognitionAvailable) {
        return ListenReadinessDetail(
            readiness = ListenReadiness.BLOCKED,
            reason = "stt_unavailable",
            userFacingMessage = "Speech recognition is not available on this device.",
        )
    }

    if (deviceState == null) {
        return if (audioRoute.isReadyForSpeechCapture) {
            ListenReadinessDetail(
                readiness = ListenReadiness.DEGRADED,
                reason = "no_device_state_but_route_ok",
                userFacingMessage = "No earbud status available, but the microphone route is ready.",
            )
        } else {
            ListenReadinessDetail(
                readiness = ListenReadiness.BLOCKED,
                reason = "no_device_and_no_route",
                userFacingMessage = "No earbud detected and microphone route is not ready.",
            )
        }
    }

    if (deviceState.connectionState != ConnectionState.CONNECTED) {
        return ListenReadinessDetail(
            readiness = ListenReadiness.BLOCKED,
            reason = "device_disconnected",
            userFacingMessage = "Earbuds are disconnected.",
        )
    }

    val earState = deviceState.earState
    if (earState != null && !earState.anyInEar) {
        return ListenReadinessDetail(
            readiness = ListenReadiness.BLOCKED,
            reason = "buds_out_of_ear",
            userFacingMessage = "Place at least one earbud in your ear to continue.",
        )
    }

    if (useBluetoothRouting && !audioRoute.isReadyForSpeechCapture) {
        return ListenReadinessDetail(
            readiness = ListenReadiness.DEGRADED,
            reason = "mic_route_not_confirmed",
            userFacingMessage = "Earbuds are connected, but the microphone route is not confirmed. The phone microphone may be used instead.",
        )
    }

    val battery = deviceState.battery
    if (battery != null && battery.isLow) {
        return ListenReadinessDetail(
            readiness = ListenReadiness.DEGRADED,
            reason = "low_battery",
            userFacingMessage = "Earbud battery is low; wake may be unreliable.",
        )
    }

    return ListenReadinessDetail(
        readiness = ListenReadiness.READY,
        reason = "ready",
        userFacingMessage = "Ready to listen.",
    )
}
