package com.openclaw.relay.signal

import kotlinx.serialization.Serializable

@Serializable
data class EarbudCapabilityProfile(
    val providerId: String,
    val deviceModel: String?,
    val capabilities: List<Capability>,
    val wakeGestures: Map<GestureType, CapabilityConfidence>,
    val interruptGestures: Map<GestureType, CapabilityConfidence>,
    val approvalGestures: Map<GestureType, CapabilityConfidence>,
    val supportsEarDetection: Boolean = false,
    val supportsBatteryStatus: Boolean = false,
    val supportsAudioRouteControl: Boolean = false,
)

@Serializable
enum class Capability {
    WAKE_SINGLE_PRESS,
    WAKE_DOUBLE_PRESS,
    WAKE_TRIPLE_PRESS,
    WAKE_LONG_PRESS,
    INTERRUPT_SINGLE_PRESS,
    INTERRUPT_DOUBLE_PRESS,
    INTERRUPT_TRIPLE_PRESS,
    INTERRUPT_LONG_PRESS,
    APPROVE_DOUBLE_PRESS,
    REJECT_TRIPLE_PRESS,
    EAR_DETECTION,
    BATTERY_STATUS,
    AUDIO_ROUTE_CONTROL,
    STEM_PRESS_EVENTS,
    IN_EAR_DETECTION,
    LID_STATE,
}

@Serializable
enum class CapabilityConfidence {
    PROVEN,
    SUPPORTED_BUT_NOT_OBSERVED,
    UNSUPPORTED,
    UNKNOWN,
}

fun EarbudCapabilityProfile.hasProvenWake(): Boolean {
    return wakeGestures.values.any { it == CapabilityConfidence.PROVEN }
}

fun EarbudCapabilityProfile.hasProvenInterrupt(): Boolean {
    return interruptGestures.values.any { it == CapabilityConfidence.PROVEN }
}

fun EarbudCapabilityProfile.hasProvenApproval(): Boolean {
    return approvalGestures.values.any { it == CapabilityConfidence.PROVEN }
}
