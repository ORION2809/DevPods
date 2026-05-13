package com.openclaw.relay.signal

/**
 * Conformance assertions that every [EarbudSignalProvider] must satisfy.
 * These are used in unit tests and runtime health checks.
 */
object ProviderConformance {

    data class ConformanceResult(
        val providerId: String,
        val passed: Boolean,
        val failures: List<String>,
    )

    fun assertProbe(result: EarbudSignalProvider.ProbeResult): List<String> {
        val failures = mutableListOf<String>()
        if (result.detectedGestures.any { it.confidence == SignalConfidence.PROVEN && !result.detectedDevice }) {
            failures += "PROVEN gestures without detectedDevice"
        }
        if (result.message.isBlank()) {
            failures += "probe message is blank"
        }
        return failures
    }

    fun assertCapabilityProfile(profile: EarbudCapabilityProfile): List<String> {
        val failures = mutableListOf<String>()
        if (profile.providerId.isBlank()) {
            failures += "providerId is blank"
        }
        if (profile.wakeGestures.values.any { it == CapabilityConfidence.PROVEN } &&
            Capability.WAKE_SINGLE_PRESS !in profile.capabilities &&
            Capability.WAKE_DOUBLE_PRESS !in profile.capabilities &&
            Capability.WAKE_LONG_PRESS !in profile.capabilities
        ) {
            failures += "PROVEN wake gesture but no WAKE_* capability declared"
        }
        return failures
    }

    fun assertDeviceState(state: EarbudDeviceState?): List<String> {
        val failures = mutableListOf<String>()
        if (state == null) return failures
        if (state.providerId.isBlank()) {
            failures += "deviceState providerId is blank"
        }
        if (state.connectionState == ConnectionState.CONNECTED && state.displayName.isNullOrBlank()) {
            failures += "CONNECTED device has blank displayName"
        }
        return failures
    }

    suspend fun runFullConformance(provider: EarbudSignalProvider): ConformanceResult {
        val failures = mutableListOf<String>()
        failures += assertCapabilityProfile(provider.capabilityProfile.value)
        failures += assertDeviceState(provider.deviceState.value)
        val probeResult = provider.probe()
        failures += assertProbe(probeResult)
        failures += assertCapabilityProfile(provider.capabilityProfile.value)
        return ConformanceResult(
            providerId = provider.providerId,
            passed = failures.isEmpty(),
            failures = failures,
        )
    }
}
