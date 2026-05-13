package com.openclaw.relay

interface EarbudSignalProvider {
    val providerId: String
    val providerLabel: String
    val isPhysicalInput: Boolean
    val defaultConfidence: RelaySignalConfidence
}

enum class RelaySignalConfidence(val label: String) {
    PROVEN("proven"),
    OBSERVED("observed"),
    INFERRED("inferred"),
    UNPROVEN("unproven"),
}

data class RelayObservedSignalProvider(
    val providerId: String,
    val providerLabel: String,
    val confidence: RelaySignalConfidence,
    val deviceLabel: String? = null,
    val isPhysicalInput: Boolean = false,
)

data class RelaySignalProviderSummary(
    val lastProvider: RelayObservedSignalProvider? = null,
    val lastPhysicalWakeProvider: RelayObservedSignalProvider? = null,
    val observedProviders: List<RelayObservedSignalProvider> = emptyList(),
) {
    val hasVerifiedPhysicalWake: Boolean
        get() = lastPhysicalWakeProvider != null
}

object AndroidMediaSessionSignalProvider : EarbudSignalProvider {
    override val providerId: String = "android_media_session"
    override val providerLabel: String = "Android media controls"
    override val isPhysicalInput: Boolean = true
    override val defaultConfidence: RelaySignalConfidence = RelaySignalConfidence.PROVEN
}

object AssistantEntrySignalProvider : EarbudSignalProvider {
    override val providerId: String = "assistant_entry"
    override val providerLabel: String = "Device assistant"
    override val isPhysicalInput: Boolean = false
    override val defaultConfidence: RelaySignalConfidence = RelaySignalConfidence.PROVEN
}

object ManualPushToTalkSignalProvider : EarbudSignalProvider {
    override val providerId: String = "manual_push_to_talk"
    override val providerLabel: String = "Push to talk"
    override val isPhysicalInput: Boolean = false
    override val defaultConfidence: RelaySignalConfidence = RelaySignalConfidence.PROVEN
}

object ManualTapTestSignalProvider : EarbudSignalProvider {
    override val providerId: String = "manual_tap_test"
    override val providerLabel: String = "Tap Test relay path"
    override val isPhysicalInput: Boolean = false
    override val defaultConfidence: RelaySignalConfidence = RelaySignalConfidence.OBSERVED
}

object DebugAutomationSignalProvider : EarbudSignalProvider {
    override val providerId: String = "debug_automation"
    override val providerLabel: String = "Debug automation"
    override val isPhysicalInput: Boolean = false
    override val defaultConfidence: RelaySignalConfidence = RelaySignalConfidence.INFERRED
}

object LibrePodsAirPodsSignalProvider : EarbudSignalProvider {
    override val providerId: String = "librepods_airpods"
    override val providerLabel: String = "LibrePods native"
    override val isPhysicalInput: Boolean = true
    override val defaultConfidence: RelaySignalConfidence = RelaySignalConfidence.UNPROVEN
}

object CustomFirmwareBleSignalProvider : EarbudSignalProvider {
    override val providerId: String = "custom_firmware_ble"
    override val providerLabel: String = "Custom firmware BLE"
    override val isPhysicalInput: Boolean = true
    override val defaultConfidence: RelaySignalConfidence = RelaySignalConfidence.UNPROVEN
}

fun EarbudSignalProvider.observe(
    confidence: RelaySignalConfidence = defaultConfidence,
    deviceLabel: String? = null,
): RelayObservedSignalProvider {
    return RelayObservedSignalProvider(
        providerId = providerId,
        providerLabel = providerLabel,
        confidence = confidence,
        deviceLabel = deviceLabel,
        isPhysicalInput = isPhysicalInput,
    )
}

internal fun recordObservedProvider(
    summary: RelaySignalProviderSummary,
    provider: RelayObservedSignalProvider,
): RelaySignalProviderSummary {
    val observedProviders = mergeObservedProviders(summary.observedProviders, provider)
    val lastPhysicalWakeProvider = if (provider.isPhysicalInput && provider.confidence == RelaySignalConfidence.PROVEN) {
        provider
    } else {
        summary.lastPhysicalWakeProvider
    }

    return summary.copy(
        lastProvider = provider,
        lastPhysicalWakeProvider = lastPhysicalWakeProvider,
        observedProviders = observedProviders,
    )
}

private fun mergeObservedProviders(
    existing: List<RelayObservedSignalProvider>,
    incoming: RelayObservedSignalProvider,
): List<RelayObservedSignalProvider> {
    val mutableProviders = existing.toMutableList()
    val existingIndex = mutableProviders.indexOfFirst { it.providerId == incoming.providerId }
    if (existingIndex >= 0) {
        mutableProviders[existingIndex] = incoming
    } else {
        mutableProviders.add(incoming)
    }
    return mutableProviders.toList()
}