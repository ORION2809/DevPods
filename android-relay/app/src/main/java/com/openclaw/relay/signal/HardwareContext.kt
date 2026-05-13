package com.openclaw.relay.signal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HardwareContext(
    @SerialName("provider") val providerId: String,
    val wakeSource: String? = null,
    val deviceConfidence: String,
    val earState: String? = null,
    val batteryState: String? = null,
    val deviceModel: String? = null,
    val connectionState: String? = null,
)

fun EarbudDeviceState?.toHardwareContext(wakeGesture: EarbudSignalEvent.WakeGesture? = null): HardwareContext {
    if (this == null) {
        return HardwareContext(
            providerId = "unknown",
            deviceConfidence = "unproven",
        )
    }
    return HardwareContext(
        providerId = providerId,
        wakeSource = wakeGesture?.let {
            buildString {
                append(it.budSide?.name?.lowercase() ?: "unknown")
                append("_")
                append(it.gestureType.name.lowercase())
            }
        },
        deviceConfidence = confidence.name.lowercase(),
        earState = earState?.let {
            when {
                it.leftInEar == true && it.rightInEar == true -> "both_in_ear"
                it.leftInEar == true -> "left_in_ear"
                it.rightInEar == true -> "right_in_ear"
                else -> "out_of_ear"
            }
        },
        batteryState = battery?.let {
            when {
                it.isLow -> "low"
                else -> "ok"
            }
        },
        deviceModel = displayName,
        connectionState = connectionState.name.lowercase(),
    )
}
