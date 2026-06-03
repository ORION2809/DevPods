package com.openclaw.relay.device

import com.openclaw.relay.signal.EarbudSignalEvent

private val directHardwareWakeProviders = setOf(
    "librepods_airpods",
    "custom_firmware_ble",
)

private val fallbackWakeProviders = setOf(
    "android_media_session",
    "assistant_entry",
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
        observedWakeProviders.any { it in fallbackWakeProviders } -> CapabilityStatus.FALLBACK_PROVEN
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

/**
 * Update a capability entry to reflect calibration profile results.
 *
 * Gesture proven status is promoted only when the calibration profile has:
 * - A proven gesture mapped to the corresponding action
 * - Successful route proof (for wake/interrupt)
 */
fun updateCapabilityEntryFromCalibration(
    entry: DeviceCapabilityEntry,
    profile: com.openclaw.relay.calibration.EarbudCalibrationProfile,
): DeviceCapabilityEntry {
    if (!profile.isReadyForRuntime()) {
        // Profile is not runtime-ready; do not promote capabilities
        return entry.copy(
            calibrationProfileId = profile.profileId,
            notes = entry.notes + " Calibration profile exists but is not runtime-ready.",
        )
    }

    val provenGestures = profile.provenGestures()
    val actionMap = profile.gestureActionMap

    val hasWakeGesture = provenGestures.any {
        val action = actionMap.actionFor(it.requestedGesture)
        action == com.openclaw.relay.calibration.GestureAction.WAKE_AND_LISTEN
    }
    val hasInterruptGesture = provenGestures.any {
        val action = actionMap.actionFor(it.requestedGesture)
        action == com.openclaw.relay.calibration.GestureAction.INTERRUPT
    }
    val hasApprovalGesture = provenGestures.any {
        val action = actionMap.actionFor(it.requestedGesture)
        action == com.openclaw.relay.calibration.GestureAction.APPROVE ||
            action == com.openclaw.relay.calibration.GestureAction.REJECT
    }

    val wakeGesture = if (hasWakeGesture) CapabilityStatus.PROVEN else entry.wakeGesture
    val interruptGesture = if (hasInterruptGesture) CapabilityStatus.PROVEN else entry.interruptGesture
    val approveRejectGesture = if (hasApprovalGesture) CapabilityStatus.PROVEN else entry.approveRejectGesture

    return entry.copy(
        wakeGesture = wakeGesture,
        interruptGesture = interruptGesture,
        approveRejectGesture = approveRejectGesture,
        calibrationProfileId = profile.profileId,
        notes = buildString {
            append(entry.notes)
            append(" Calibration proven: wake=$hasWakeGesture, interrupt=$hasInterruptGesture, approve=$hasApprovalGesture.")
        },
    )
}