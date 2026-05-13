package com.openclaw.relay

internal fun describeReadySignalPath(state: RelayUiState): String {
    val verifiedPhysicalProvider = state.signalProviderSummary.lastPhysicalWakeProvider
    return if (verifiedPhysicalProvider != null) {
        "Verified wake provider: ${verifiedPhysicalProvider.providerLabel} only. Keep the device assistant gesture available as the fallback control path while LibrePods-native input remains unverified on this device."
    } else {
        "No physical wake provider is verified yet. Use Tap Test to validate the relay path only, and keep the device assistant gesture available as the fallback control path."
    }
}

internal fun describeWakeVerification(state: RelayUiState): String {
    val wakeSignal = state.lastWakeSignal ?: return "Physical headset wake is not verified yet. Use Tap Test for relay-path validation, then verify a supported physical provider before widening product claims."
    return when (wakeSignal.provider.providerId) {
        AndroidMediaSessionSignalProvider.providerId -> "Physical headset wake is currently verified through Android media controls. Keep the device assistant gesture available as the fallback control path while LibrePods-native input remains unverified on this device."
        ManualTapTestSignalProvider.providerId -> "Tap Test proves the relay path and audible response only. It does not verify physical earbud delivery."
        DebugAutomationSignalProvider.providerId -> "Debug automation is synthetic. It does not verify a real headset wake path."
        AssistantEntrySignalProvider.providerId -> "The device assistant gesture is a fallback control path. It does not prove direct headset media-button delivery."
        LibrePodsAirPodsSignalProvider.providerId -> "LibrePods-native input has been observed, but it is not yet part of the declared supported wake contract."
        else -> "Physical headset wake is not verified yet. Use Tap Test for relay-path validation, then verify a supported physical provider before widening product claims."
    }
}

internal fun formatObservedProviders(summary: RelaySignalProviderSummary): String {
    if (summary.observedProviders.isEmpty()) {
        return "none"
    }

    return summary.observedProviders.joinToString(separator = ", ") { provider ->
        buildString {
            append(provider.providerLabel)
            append(" (")
            append(provider.confidence.label)
            if (provider.isPhysicalInput) {
                append(", physical")
            }
            append(")")
        }
    }
}

internal fun formatProviderLabel(provider: RelayObservedSignalProvider?): String {
    return provider?.providerLabel ?: "none"
}

internal fun formatProviderConfidence(provider: RelayObservedSignalProvider?): String {
    return provider?.confidence?.label ?: RelaySignalConfidence.UNPROVEN.label
}