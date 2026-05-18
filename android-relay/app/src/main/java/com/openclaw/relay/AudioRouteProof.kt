package com.openclaw.relay

import java.security.MessageDigest

enum class AudioRouteProofState {
    ROUTE_UNKNOWN,
    ROUTE_PHONE_MIC,
    ROUTE_BLUETOOTH_REQUESTED,
    ROUTE_BLUETOOTH_ACTIVE,
    ROUTE_BLUETOOTH_SUSPECT,
    ROUTE_FAILED,
    ROUTE_RELEASED,
}

data class AudioRouteProof(
    val routeState: AudioRouteProofState = AudioRouteProofState.ROUTE_UNKNOWN,
    val routeRequestedAtMs: Long? = null,
    val routeReadyAtMs: Long? = null,
    val routeSettleMs: Long? = routeRequestedAtMs?.let { requested ->
        routeReadyAtMs?.let { ready -> (ready - requested).coerceAtLeast(0L) }
    },
    val selectedDeviceType: String? = null,
    val selectedDeviceHash: String? = null,
    val communicationDeviceCount: Int = 0,
)

internal fun buildAudioRouteProof(
    isActive: Boolean,
    isReadyForSpeechCapture: Boolean,
    status: String,
    selectedDeviceName: String?,
    selectedDeviceType: String?,
    communicationDeviceCount: Int,
    requestedAtMs: Long?,
    readyAtMs: Long?,
): AudioRouteProof {
    val routeState = classifyAudioRouteProofState(
        isActive = isActive,
        isReadyForSpeechCapture = isReadyForSpeechCapture,
        status = status,
        selectedDeviceType = selectedDeviceType,
    )
    return AudioRouteProof(
        routeState = routeState,
        routeRequestedAtMs = requestedAtMs,
        routeReadyAtMs = readyAtMs,
        selectedDeviceType = selectedDeviceType,
        selectedDeviceHash = hashRouteDevice(selectedDeviceType, selectedDeviceName),
        communicationDeviceCount = communicationDeviceCount,
    )
}

private fun classifyAudioRouteProofState(
    isActive: Boolean,
    isReadyForSpeechCapture: Boolean,
    status: String,
    selectedDeviceType: String?,
): AudioRouteProofState {
    if (!isActive) {
        return AudioRouteProofState.ROUTE_FAILED
    }

    if (isReadyForSpeechCapture) {
        return if (selectedDeviceType?.contains("Bluetooth", ignoreCase = true) == true ||
            selectedDeviceType?.contains("BLE", ignoreCase = true) == true) {
            AudioRouteProofState.ROUTE_BLUETOOTH_ACTIVE
        } else {
            AudioRouteProofState.ROUTE_PHONE_MIC
        }
    }

    if (status.contains("built-in audio", ignoreCase = true) ||
        status.contains("no communication microphone route", ignoreCase = true)) {
        return AudioRouteProofState.ROUTE_BLUETOOTH_SUSPECT
    }

    if (status.contains("request", ignoreCase = true)) {
        return AudioRouteProofState.ROUTE_BLUETOOTH_REQUESTED
    }

    return AudioRouteProofState.ROUTE_UNKNOWN
}

private fun hashRouteDevice(selectedDeviceType: String?, selectedDeviceName: String?): String? {
    val normalizedName = selectedDeviceName?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalized = "${selectedDeviceType.orEmpty().trim().lowercase()}|${normalizedName.lowercase()}"
    val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }.take(16)
}
