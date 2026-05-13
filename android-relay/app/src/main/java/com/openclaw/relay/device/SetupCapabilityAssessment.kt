package com.openclaw.relay.device

import com.openclaw.relay.signal.EarbudSignalEvent

private val directHardwareWakeProviders = setOf(
    "android_media_session",
    "librepods_airpods",
    "custom_firmware_ble",
)

data class SetupCapabilityAssessment(
    val deviceModel: String?,
    val phoneModel: String,
    val androidVersion: String,
    val observedEvents: List<EarbudSignalEvent>,
    val detectedProviders: Set<String> = emptySet(),
)

fun isDirectHardwareWakeProvider(providerId: String): Boolean = providerId in directHardwareWakeProviders

fun buildCapabilityEntryFromSetup(assessment: SetupCapabilityAssessment): DeviceCapabilityEntry {
    val observedProviders = linkedSetOf<String>().apply {
        addAll(assessment.detectedProviders)
        assessment.observedEvents.mapTo(this) { it.providerId }
    }.toList().sorted()

    val observedWakeProviders = assessment.observedEvents
        .filterIsInstance<EarbudSignalEvent.WakeGesture>()
        .map { it.providerId }
        .toSet()
    val observedInterruptProviders = assessment.observedEvents
        .filterIsInstance<EarbudSignalEvent.InterruptGesture>()
        .map { it.providerId }
        .toSet()
    val observedApprovalProviders = assessment.observedEvents
        .filterIsInstance<EarbudSignalEvent.ApprovalGesture>()
        .map { it.providerId }
        .toSet()

    val wakeGesture = when {
        observedWakeProviders.any(::isDirectHardwareWakeProvider) -> CapabilityStatus.PROVEN
        observedWakeProviders.isNotEmpty() -> CapabilityStatus.OBSERVED
        else -> CapabilityStatus.UNPROVEN
    }

    val interruptGesture = when {
        observedInterruptProviders.isNotEmpty() -> CapabilityStatus.PROVEN
        else -> CapabilityStatus.UNPROVEN
    }

    val approveRejectGesture = when {
        observedApprovalProviders.isNotEmpty() -> CapabilityStatus.PROVEN
        else -> CapabilityStatus.UNPROVEN
    }

    val earDetection = when {
        assessment.observedEvents.any { it is EarbudSignalEvent.EarStateChanged } -> CapabilityStatus.PROVEN
        "librepods_airpods" in assessment.detectedProviders -> CapabilityStatus.OBSERVED
        else -> CapabilityStatus.UNPROVEN
    }

    val batteryStatus = when {
        assessment.observedEvents.any { it is EarbudSignalEvent.BatteryChanged } -> CapabilityStatus.PROVEN
        "librepods_airpods" in assessment.detectedProviders -> CapabilityStatus.OBSERVED
        else -> CapabilityStatus.UNPROVEN
    }

    return DeviceCapabilityEntry(
        deviceModel = assessment.deviceModel?.takeIf { it.isNotBlank() } ?: fallbackDeviceModel(assessment.detectedProviders),
        phoneModel = assessment.phoneModel,
        androidVersion = assessment.androidVersion,
        providersObserved = observedProviders,
        wakeGesture = wakeGesture,
        interruptGesture = interruptGesture,
        approveRejectGesture = approveRejectGesture,
        earDetection = earDetection,
        batteryStatus = batteryStatus,
        sttAfterWake = CapabilityStatus.UNPROVEN,
        ttsInterruption = CapabilityStatus.UNPROVEN,
        notes = buildSetupNotes(
            observedProviders = observedProviders,
            observedWakeProviders = observedWakeProviders,
            observedInterruptProviders = observedInterruptProviders,
            observedApprovalProviders = observedApprovalProviders,
        ),
    )
}

private fun fallbackDeviceModel(detectedProviders: Set<String>): String {
    return when {
        "librepods_airpods" in detectedProviders -> "AirPods-class device"
        else -> "Generic Bluetooth"
    }
}

private fun buildSetupNotes(
    observedProviders: List<String>,
    observedWakeProviders: Set<String>,
    observedInterruptProviders: Set<String>,
    observedApprovalProviders: Set<String>,
): String {
    val notes = mutableListOf<String>()
    if (observedProviders.isNotEmpty()) {
        notes += "Observed providers: ${observedProviders.joinToString(", ")}."
    }
    if (observedWakeProviders.isEmpty()) {
        notes += "No wake gesture was observed during setup."
    } else if (observedWakeProviders.none(::isDirectHardwareWakeProvider)) {
        notes += "Wake was observed only through a fallback provider."
    }
    if (observedInterruptProviders.isEmpty()) {
        notes += "No interrupt gesture was observed during setup."
    }
    if (observedApprovalProviders.isEmpty()) {
        notes += "No approval or reject gesture was observed during setup."
    }
    return notes.joinToString(" ")
}