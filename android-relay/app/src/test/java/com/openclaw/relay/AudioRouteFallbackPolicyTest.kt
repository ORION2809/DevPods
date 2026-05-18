package com.openclaw.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRouteFallbackPolicyTest {
    @Test
    fun `ready headset route is used without fallback changes`() {
        val snapshot = RelayAudioRouteSnapshot(
            isActive = true,
            isReadyForSpeechCapture = true,
            status = "Headset microphone route ready",
            selectedDeviceName = "realme Buds Air7",
            selectedDeviceType = "Bluetooth SCO",
            proof = AudioRouteProof(routeState = AudioRouteProofState.ROUTE_BLUETOOTH_ACTIVE),
        )

        val result = AudioRouteFallbackPolicy.resolve(
            requestedRoute = snapshot,
            allowPhoneMicFallback = false,
        )

        assertEquals(AudioRouteFallbackDecision.USE_REQUESTED_ROUTE, result.decision)
        assertSame(snapshot, result.routeSnapshot)
        assertFalse(result.shouldClearRequestedRoute)
        assertNull(result.blockingMessage)
    }

    @Test
    fun `failed headset route blocks listening when phone fallback is disabled`() {
        val snapshot = failedRouteSnapshot()

        val result = AudioRouteFallbackPolicy.resolve(
            requestedRoute = snapshot,
            allowPhoneMicFallback = false,
        )

        assertEquals(AudioRouteFallbackDecision.BLOCK_LISTENING, result.decision)
        assertSame(snapshot, result.routeSnapshot)
        assertTrue(result.shouldClearRequestedRoute)
        assertEquals(
            "Bluetooth routing failed. Enable phone microphone fallback or check the connected audio device.",
            result.blockingMessage,
        )
    }

    @Test
    fun `failed headset route becomes explicit phone microphone fallback when enabled`() {
        val snapshot = failedRouteSnapshot()

        val result = AudioRouteFallbackPolicy.resolve(
            requestedRoute = snapshot,
            allowPhoneMicFallback = true,
        )

        assertEquals(AudioRouteFallbackDecision.USE_PHONE_MIC_FALLBACK, result.decision)
        assertTrue(result.shouldClearRequestedRoute)
        assertTrue(result.routeSnapshot.isActive)
        assertTrue(result.routeSnapshot.isReadyForSpeechCapture)
        assertTrue(result.routeSnapshot.isPhoneMicFallback)
        assertEquals("Phone microphone fallback active after headset route failed: No communication headset detected", result.routeSnapshot.status)
        assertEquals("Phone microphone", result.routeSnapshot.selectedDeviceType)
        assertEquals("Bluetooth A2DP realme Buds Air7", result.routeSnapshot.availableDevices)
        assertEquals(AudioRouteProofState.ROUTE_PHONE_MIC, result.routeSnapshot.proof.routeState)
        assertEquals(100L, result.routeSnapshot.proof.routeRequestedAtMs)
        assertEquals(240L, result.routeSnapshot.proof.routeReadyAtMs)
        assertEquals(140L, result.routeSnapshot.proof.routeSettleMs)
        assertEquals(2, result.routeSnapshot.proof.communicationDeviceCount)
    }

    @Test
    fun `active but non capture route uses phone fallback when enabled`() {
        val snapshot = RelayAudioRouteSnapshot(
            isActive = true,
            isReadyForSpeechCapture = false,
            status = "Headset output is visible, but no communication microphone route is active",
            selectedDeviceName = "realme Buds Air7",
            selectedDeviceType = "Bluetooth A2DP",
            availableDevices = "Bluetooth A2DP realme Buds Air7",
            proof = AudioRouteProof(
                routeState = AudioRouteProofState.ROUTE_BLUETOOTH_SUSPECT,
                routeRequestedAtMs = 10L,
                routeReadyAtMs = 60L,
                selectedDeviceType = "Bluetooth A2DP",
                selectedDeviceHash = "abc123",
                communicationDeviceCount = 1,
            ),
        )

        val result = AudioRouteFallbackPolicy.resolve(
            requestedRoute = snapshot,
            allowPhoneMicFallback = true,
        )

        assertEquals(AudioRouteFallbackDecision.USE_PHONE_MIC_FALLBACK, result.decision)
        assertTrue(result.routeSnapshot.isPhoneMicFallback)
        assertEquals(AudioRouteProofState.ROUTE_PHONE_MIC, result.routeSnapshot.proof.routeState)
        assertNull(result.blockingMessage)
    }

    @Test
    fun `active but non capture route can continue through settle window`() {
        val snapshot = RelayAudioRouteSnapshot(
            isActive = true,
            isReadyForSpeechCapture = false,
            status = "Headset output is visible, but no communication microphone route is active",
            proof = AudioRouteProof(routeState = AudioRouteProofState.ROUTE_BLUETOOTH_REQUESTED),
        )

        val result = AudioRouteFallbackPolicy.resolve(
            requestedRoute = snapshot,
            allowPhoneMicFallback = true,
            allowRouteSettle = true,
        )

        assertEquals(AudioRouteFallbackDecision.USE_REQUESTED_ROUTE, result.decision)
        assertSame(snapshot, result.routeSnapshot)
        assertFalse(result.shouldClearRequestedRoute)
    }

    @Test
    fun `blocking fallback message tells user where to recover`() {
        val message = resolveUserFacingError(
            "Bluetooth routing failed. Enable phone microphone fallback or check the connected audio device.",
        )

        assertEquals(
            "Earbud microphone routing failed. Enable Phone microphone fallback in Device settings, or reconnect your earbuds and try again.",
            message,
        )
    }

    private fun failedRouteSnapshot(): RelayAudioRouteSnapshot =
        RelayAudioRouteSnapshot(
            isActive = false,
            isReadyForSpeechCapture = false,
            status = "No communication headset detected",
            selectedDeviceName = "realme Buds Air7",
            selectedDeviceType = "Bluetooth A2DP",
            availableDevices = "Bluetooth A2DP realme Buds Air7",
            proof = AudioRouteProof(
                routeState = AudioRouteProofState.ROUTE_FAILED,
                routeRequestedAtMs = 100L,
                routeReadyAtMs = 240L,
                selectedDeviceType = "Bluetooth A2DP",
                selectedDeviceHash = "abc123",
                communicationDeviceCount = 2,
            ),
        )
}
