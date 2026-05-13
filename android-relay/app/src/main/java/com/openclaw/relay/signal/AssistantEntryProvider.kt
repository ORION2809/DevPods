package com.openclaw.relay.signal

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

class AssistantEntryProvider : EarbudSignalProvider {
    override val providerId: String = "assistant_entry"
    override val providerLabel: String = "Device assistant"
    override val isPhysicalInput: Boolean = false
    override val defaultConfidence: SignalConfidence = SignalConfidence.PROVEN

    private val _capabilityProfile = MutableStateFlow(
        EarbudCapabilityProfile(
            providerId = providerId,
            deviceModel = null,
            capabilities = listOf(Capability.WAKE_LONG_PRESS, Capability.INTERRUPT_LONG_PRESS),
            wakeGestures = mapOf(GestureType.LONG_PRESS to CapabilityConfidence.PROVEN),
            interruptGestures = mapOf(GestureType.LONG_PRESS to CapabilityConfidence.PROVEN),
            approvalGestures = emptyMap(),
            supportsBatteryStatus = false,
            supportsEarDetection = false,
            supportsAudioRouteControl = false,
        )
    )
    override val capabilityProfile: StateFlow<EarbudCapabilityProfile> = _capabilityProfile.asStateFlow()

    private val _deviceState = MutableStateFlow<EarbudDeviceState?>(null)
    override val deviceState: StateFlow<EarbudDeviceState?> = _deviceState.asStateFlow()

    private val _events = Channel<EarbudSignalEvent>(Channel.BUFFERED)
    override val events: Flow<EarbudSignalEvent> = _events.receiveAsFlow()

    override suspend fun start() {
        _deviceState.value = EarbudDeviceState(
            providerId = providerId,
            deviceId = null,
            displayName = "Device assistant",
            connectionState = ConnectionState.CONNECTED,
            battery = null,
            earState = null,
            audioRouteState = null,
            capabilityProfile = _capabilityProfile.value,
            confidence = SignalConfidence.PROVEN,
        )
    }

    override suspend fun stop() {
        _deviceState.value = null
    }

    override suspend fun probe(): EarbudSignalProvider.ProbeResult {
        return EarbudSignalProvider.ProbeResult(
            success = true,
            detectedDevice = true,
            detectedGestures = listOf(
                EarbudSignalProvider.GestureDetected(
                    gestureType = GestureType.LONG_PRESS,
                    budSide = null,
                    confidence = SignalConfidence.PROVEN,
                )
            ),
            message = "Assistant entry is always available as a fallback.",
        )
    }

    fun emitWake(gestureType: GestureType = GestureType.LONG_PRESS, isInterrupt: Boolean = false) {
        val event = if (isInterrupt) {
            EarbudSignalEvent.InterruptGesture(
                providerId = providerId,
                deviceId = null,
                gestureType = gestureType,
                confidence = SignalConfidence.PROVEN,
            )
        } else {
            EarbudSignalEvent.WakeGesture(
                providerId = providerId,
                deviceId = null,
                gestureType = gestureType,
                confidence = SignalConfidence.PROVEN,
            )
        }
        _events.trySend(event)
    }
}
