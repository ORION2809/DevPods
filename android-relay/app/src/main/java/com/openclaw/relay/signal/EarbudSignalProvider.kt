package com.openclaw.relay.signal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface EarbudSignalProvider {
    val providerId: String
    val providerLabel: String
    val isPhysicalInput: Boolean
    val defaultConfidence: SignalConfidence

    val capabilityProfile: StateFlow<EarbudCapabilityProfile>
    val deviceState: StateFlow<EarbudDeviceState?>
    val events: Flow<EarbudSignalEvent>

    suspend fun start()
    suspend fun stop()
    suspend fun probe(): ProbeResult

    data class ProbeResult(
        val success: Boolean,
        val detectedDevice: Boolean,
        val detectedGestures: List<GestureDetected>,
        val message: String,
    )

    data class GestureDetected(
        val gestureType: GestureType,
        val budSide: BudSide?,
        val confidence: SignalConfidence,
    )
}

fun EarbudSignalProvider.toObservedProvider(
    confidence: SignalConfidence = defaultConfidence,
    deviceLabel: String? = null,
): ObservedSignalProvider = ObservedSignalProvider(
    providerId = providerId,
    providerLabel = providerLabel,
    confidence = confidence,
    deviceLabel = deviceLabel,
    isPhysicalInput = isPhysicalInput,
)

data class ObservedSignalProvider(
    val providerId: String,
    val providerLabel: String,
    val confidence: SignalConfidence,
    val deviceLabel: String? = null,
    val isPhysicalInput: Boolean = false,
)
