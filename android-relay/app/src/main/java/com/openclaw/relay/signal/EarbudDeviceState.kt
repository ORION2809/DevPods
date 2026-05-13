package com.openclaw.relay.signal

import kotlinx.serialization.Serializable

@Serializable
data class EarbudDeviceState(
    val providerId: String,
    val deviceId: String?,
    val displayName: String?,
    val connectionState: ConnectionState,
    val battery: EarbudBatteryState?,
    val earState: EarState?,
    val audioRouteState: AudioRouteState?,
    val capabilityProfile: EarbudCapabilityProfile,
    val confidence: SignalConfidence,
    val lastSeenAtMs: Long = System.currentTimeMillis(),
)

@Serializable
data class EarbudBatteryState(
    val leftPercent: Int?,
    val rightPercent: Int?,
    val casePercent: Int?,
    val isLow: Boolean = false,
)

@Serializable
data class EarState(
    val leftInEar: Boolean?,
    val rightInEar: Boolean?,
    val anyInEar: Boolean = (leftInEar == true) || (rightInEar == true),
)

@Serializable
data class AudioRouteState(
    val isReadyForSpeechCapture: Boolean,
    val selectedDeviceName: String?,
    val selectedDeviceType: String?,
    val communicationDeviceName: String?,
    val communicationDeviceType: String?,
)

enum class ConnectionState {
    CONNECTED,
    DISCONNECTED,
    CONNECTING,
    UNKNOWN,
}
