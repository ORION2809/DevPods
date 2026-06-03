package com.openclaw.relay.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConformanceTest {

    @Test
    fun `assertProbe flags proven gestures without detectedDevice`() {
        val result = EarbudSignalProvider.ProbeResult(
            success = true,
            detectedDevice = false,
            detectedGestures = listOf(
                EarbudSignalProvider.GestureDetected(
                    gestureType = GestureType.SINGLE_PRESS,
                    budSide = null,
                    confidence = SignalConfidence.PROVEN,
                ),
            ),
            message = "test",
        )
        val failures = ProviderConformance.assertProbe(result)
        assertTrue(failures.isNotEmpty())
        assertTrue(failures.any { it.contains("PROVEN gestures without detectedDevice") })
    }

    @Test
    fun `assertProbe allows empty message`() {
        val result = EarbudSignalProvider.ProbeResult(
            success = true,
            detectedDevice = false,
            detectedGestures = emptyList(),
            message = "",
        )
        val failures = ProviderConformance.assertProbe(result)
        assertTrue(failures.any { it.contains("message is blank") })
    }

    @Test
    fun `assertCapabilityProfile flags blank providerId`() {
        val profile = EarbudCapabilityProfile(
            providerId = "",
            deviceModel = null,
            capabilities = emptyList(),
            wakeGestures = emptyMap(),
            interruptGestures = emptyMap(),
            approvalGestures = emptyMap(),
        )
        val failures = ProviderConformance.assertCapabilityProfile(profile)
        assertTrue(failures.any { it.contains("providerId is blank") })
    }

    @Test
    fun `assertCapabilityProfile flags proven wake without declared capability`() {
        val profile = EarbudCapabilityProfile(
            providerId = "test",
            deviceModel = null,
            capabilities = emptyList(),
            wakeGestures = mapOf(GestureType.SINGLE_PRESS to CapabilityConfidence.PROVEN),
            interruptGestures = emptyMap(),
            approvalGestures = emptyMap(),
        )
        val failures = ProviderConformance.assertCapabilityProfile(profile)
        assertTrue(failures.any { it.contains("PROVEN wake gesture but no WAKE_") })
    }

    @Test
    fun `assertDeviceState flags connected with blank displayName`() {
        val state = EarbudDeviceState(
            providerId = "test",
            deviceId = "id",
            displayName = "",
            connectionState = ConnectionState.CONNECTED,
            battery = null,
            earState = null,
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
        val failures = ProviderConformance.assertDeviceState(state)
        assertTrue(failures.any { it.contains("CONNECTED device has blank displayName") })
    }

    @Test
    fun `assertDeviceState passes for valid state`() {
        val state = EarbudDeviceState(
            providerId = "test",
            deviceId = "id",
            displayName = "Test Buds",
            connectionState = ConnectionState.CONNECTED,
            battery = null,
            earState = null,
            audioRouteState = null,
            capabilityProfile = EarbudCapabilityProfile(
                providerId = "test",
                deviceModel = null,
                capabilities = listOf(Capability.WAKE_SINGLE_PRESS),
                wakeGestures = emptyMap(),
                interruptGestures = emptyMap(),
                approvalGestures = emptyMap(),
            ),
            confidence = SignalConfidence.PROVEN,
        )
        val failures = ProviderConformance.assertDeviceState(state)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `assertProbe passes for valid probe`() {
        val result = EarbudSignalProvider.ProbeResult(
            success = true,
            detectedDevice = true,
            detectedGestures = listOf(
                EarbudSignalProvider.GestureDetected(
                    gestureType = GestureType.SINGLE_PRESS,
                    budSide = null,
                    confidence = SignalConfidence.OBSERVED,
                ),
            ),
            message = "Device found",
        )
        val failures = ProviderConformance.assertProbe(result)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `assertProbe passes for provider with speculative candidate support`() {
        val result = EarbudSignalProvider.ProbeResult(
            success = true,
            detectedDevice = true,
            detectedGestures = listOf(
                EarbudSignalProvider.GestureDetected(
                    gestureType = GestureType.SINGLE_PRESS,
                    budSide = null,
                    confidence = SignalConfidence.OBSERVED,
                ),
                EarbudSignalProvider.GestureDetected(
                    gestureType = GestureType.DOUBLE_PRESS,
                    budSide = null,
                    confidence = SignalConfidence.OBSERVED,
                ),
                EarbudSignalProvider.GestureDetected(
                    gestureType = GestureType.LONG_PRESS,
                    budSide = null,
                    confidence = SignalConfidence.OBSERVED,
                ),
            ),
            message = "Media session active with candidate and confirmed gesture support",
        )
        val failures = ProviderConformance.assertProbe(result)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `assertCapabilityProfile passes for provider with wake and interrupt capabilities`() {
        val profile = EarbudCapabilityProfile(
            providerId = "test",
            deviceModel = null,
            capabilities = listOf(
                Capability.WAKE_SINGLE_PRESS,
                Capability.WAKE_LONG_PRESS,
                Capability.INTERRUPT_DOUBLE_PRESS,
                Capability.APPROVE_DOUBLE_PRESS,
            ),
            wakeGestures = mapOf(
                GestureType.SINGLE_PRESS to CapabilityConfidence.PROVEN,
                GestureType.LONG_PRESS to CapabilityConfidence.PROVEN,
            ),
            interruptGestures = mapOf(GestureType.DOUBLE_PRESS to CapabilityConfidence.PROVEN),
            approvalGestures = mapOf(GestureType.TRIPLE_PRESS to CapabilityConfidence.PROVEN),
            supportsBatteryStatus = false,
            supportsEarDetection = false,
            supportsAudioRouteControl = false,
        )
        val failures = ProviderConformance.assertCapabilityProfile(profile)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `assertCapabilityProfile flags missing wake capability for proven single press`() {
        val profile = EarbudCapabilityProfile(
            providerId = "test",
            deviceModel = null,
            capabilities = listOf(Capability.INTERRUPT_DOUBLE_PRESS),
            wakeGestures = mapOf(GestureType.SINGLE_PRESS to CapabilityConfidence.PROVEN),
            interruptGestures = emptyMap(),
            approvalGestures = emptyMap(),
        )
        val failures = ProviderConformance.assertCapabilityProfile(profile)
        assertTrue(failures.any { it.contains("PROVEN wake gesture but no WAKE_") })
    }
}
