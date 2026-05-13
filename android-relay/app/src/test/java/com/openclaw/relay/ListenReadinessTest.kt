package com.openclaw.relay

import com.openclaw.relay.signal.ConnectionState
import com.openclaw.relay.signal.EarbudBatteryState
import com.openclaw.relay.signal.EarbudDeviceState
import com.openclaw.relay.signal.EarState
import com.openclaw.relay.signal.EarbudCapabilityProfile
import com.openclaw.relay.signal.SignalConfidence
import com.openclaw.relay.signal.ListenReadiness
import com.openclaw.relay.signal.computeListenReadiness
import org.junit.Assert.assertEquals
import org.junit.Test

class ListenReadinessTest {

    @Test
    fun `ready when device connected, in ear, route ok, stt available`() {
        val deviceState = createDeviceState(
            connectionState = ConnectionState.CONNECTED,
            earState = EarState(leftInEar = true, rightInEar = true),
        )
        val audioRoute = RelayAudioRouteSnapshot(isReadyForSpeechCapture = true)
        val result = computeListenReadiness(deviceState, audioRoute, useBluetoothRouting = true, speechRecognitionAvailable = true)
        assertEquals(ListenReadiness.READY, result.readiness)
    }

    @Test
    fun `blocked when stt unavailable`() {
        val result = computeListenReadiness(null, RelayAudioRouteSnapshot(), useBluetoothRouting = true, speechRecognitionAvailable = false)
        assertEquals(ListenReadiness.BLOCKED, result.readiness)
    }

    @Test
    fun `blocked when device disconnected`() {
        val deviceState = createDeviceState(connectionState = ConnectionState.DISCONNECTED)
        val result = computeListenReadiness(deviceState, RelayAudioRouteSnapshot(isReadyForSpeechCapture = true), useBluetoothRouting = true, speechRecognitionAvailable = true)
        assertEquals(ListenReadiness.BLOCKED, result.readiness)
    }

    @Test
    fun `blocked when buds out of ear`() {
        val deviceState = createDeviceState(
            connectionState = ConnectionState.CONNECTED,
            earState = EarState(leftInEar = false, rightInEar = false),
        )
        val result = computeListenReadiness(deviceState, RelayAudioRouteSnapshot(isReadyForSpeechCapture = true), useBluetoothRouting = true, speechRecognitionAvailable = true)
        assertEquals(ListenReadiness.BLOCKED, result.readiness)
    }

    @Test
    fun `degraded when mic route not confirmed`() {
        val deviceState = createDeviceState(
            connectionState = ConnectionState.CONNECTED,
            earState = EarState(leftInEar = true, rightInEar = false),
        )
        val audioRoute = RelayAudioRouteSnapshot(isReadyForSpeechCapture = false)
        val result = computeListenReadiness(deviceState, audioRoute, useBluetoothRouting = true, speechRecognitionAvailable = true)
        assertEquals(ListenReadiness.DEGRADED, result.readiness)
    }

    @Test
    fun `degraded when low battery`() {
        val deviceState = createDeviceState(
            connectionState = ConnectionState.CONNECTED,
            earState = EarState(leftInEar = true, rightInEar = false),
            battery = EarbudBatteryState(leftPercent = 10, rightPercent = 100, casePercent = null, isLow = true),
        )
        val result = computeListenReadiness(deviceState, RelayAudioRouteSnapshot(isReadyForSpeechCapture = true), useBluetoothRouting = true, speechRecognitionAvailable = true)
        assertEquals(ListenReadiness.DEGRADED, result.readiness)
    }

    @Test
    fun `ready when bluetooth routing disabled`() {
        val deviceState = createDeviceState(connectionState = ConnectionState.CONNECTED)
        val result = computeListenReadiness(deviceState, RelayAudioRouteSnapshot(isReadyForSpeechCapture = false), useBluetoothRouting = false, speechRecognitionAvailable = true)
        assertEquals(ListenReadiness.READY, result.readiness)
    }

    @Test
    fun `degraded when no device state but route ok`() {
        val result = computeListenReadiness(null, RelayAudioRouteSnapshot(isReadyForSpeechCapture = true), useBluetoothRouting = true, speechRecognitionAvailable = true)
        assertEquals(ListenReadiness.DEGRADED, result.readiness)
    }

    private fun createDeviceState(
        connectionState: ConnectionState = ConnectionState.CONNECTED,
        earState: EarState? = null,
        battery: EarbudBatteryState? = null,
    ): EarbudDeviceState {
        return EarbudDeviceState(
            providerId = "test",
            deviceId = "test_device",
            displayName = "Test Device",
            connectionState = connectionState,
            battery = battery,
            earState = earState,
            audioRouteState = null,
            capabilityProfile = EarbudCapabilityProfile(
                providerId = "test",
                deviceModel = null,
                capabilities = emptyList(),
                wakeGestures = emptyMap(),
                interruptGestures = emptyMap(),
                approvalGestures = emptyMap(),
            ),
            confidence = SignalConfidence.PROVEN,
        )
    }
}
