package com.openclaw.relay.device

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class DeviceCapabilityMatrix(
    val entries: List<DeviceCapabilityEntry> = emptyList(),
) {
    fun findEntry(deviceModel: String, phoneModel: String): DeviceCapabilityEntry? {
        return entries.find { it.deviceModel.equals(deviceModel, ignoreCase = true) && it.phoneModel.equals(phoneModel, ignoreCase = true) }
    }

    fun upsert(entry: DeviceCapabilityEntry): DeviceCapabilityMatrix {
        val mutable = entries.toMutableList()
        val index = mutable.indexOfFirst {
            it.deviceModel.equals(entry.deviceModel, ignoreCase = true) && it.phoneModel.equals(entry.phoneModel, ignoreCase = true)
        }
        if (index >= 0) {
            mutable[index] = entry
        } else {
            mutable.add(entry)
        }
        return copy(entries = mutable)
    }
}

@Serializable
data class DeviceCapabilityEntry(
    val deviceModel: String,
    val phoneModel: String,
    val androidVersion: String,
    val providersObserved: List<String>,
    val wakeGesture: CapabilityStatus,
    val interruptGesture: CapabilityStatus,
    val approveRejectGesture: CapabilityStatus,
    val earDetection: CapabilityStatus,
    val batteryStatus: CapabilityStatus,
    val sttAfterWake: CapabilityStatus,
    val ttsInterruption: CapabilityStatus,
    val notes: String = "",
    val observedAtMs: Long = System.currentTimeMillis(),
)

@Serializable
enum class CapabilityStatus {
    PROVEN,
    OBSERVED,
    UNSUPPORTED,
    UNPROVEN,
}

fun DeviceCapabilityEntry.toDisplayString(): String {
    return buildString {
        appendLine("$deviceModel on $phoneModel (Android $androidVersion)")
        appendLine("Wake: ${wakeGesture.name.lowercase().replaceFirstChar { it.uppercase() }}")
        appendLine("Interrupt: ${interruptGesture.name.lowercase().replaceFirstChar { it.uppercase() }}")
        appendLine("Approve/Reject: ${approveRejectGesture.name.lowercase().replaceFirstChar { it.uppercase() }}")
        appendLine("Ear detection: ${earDetection.name.lowercase().replaceFirstChar { it.uppercase() }}")
        appendLine("Battery: ${batteryStatus.name.lowercase().replaceFirstChar { it.uppercase() }}")
        appendLine("STT after wake: ${sttAfterWake.name.lowercase().replaceFirstChar { it.uppercase() }}")
        appendLine("TTS interrupt: ${ttsInterruption.name.lowercase().replaceFirstChar { it.uppercase() }}")
        if (notes.isNotBlank()) {
            appendLine("Notes: $notes")
        }
    }
}
