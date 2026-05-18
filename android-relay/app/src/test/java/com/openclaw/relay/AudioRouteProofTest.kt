package com.openclaw.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRouteProofTest {
    @Test
    fun `active headset route becomes bluetooth active with privacy safe hash`() {
        val proof = buildAudioRouteProof(
            isActive = true,
            isReadyForSpeechCapture = true,
            status = "Headset microphone route ready",
            selectedDeviceName = "realme Buds Air7",
            selectedDeviceType = "Bluetooth SCO",
            communicationDeviceCount = 2,
            requestedAtMs = 100L,
            readyAtMs = 180L,
        )

        assertEquals(AudioRouteProofState.ROUTE_BLUETOOTH_ACTIVE, proof.routeState)
        assertEquals(80L, proof.routeSettleMs)
        assertEquals("Bluetooth SCO", proof.selectedDeviceType)
        assertEquals(2, proof.communicationDeviceCount)
        assertTrue(proof.selectedDeviceHash?.length == 16)
        assertNotEquals("realme Buds Air7", proof.selectedDeviceHash)
    }

    @Test
    fun `built in fallback with visible headset becomes suspect`() {
        val proof = buildAudioRouteProof(
            isActive = true,
            isReadyForSpeechCapture = false,
            status = "Headset output is visible, but the microphone route stayed on built-in audio",
            selectedDeviceName = "RMX3990",
            selectedDeviceType = "Phone earpiece",
            communicationDeviceCount = 1,
            requestedAtMs = 100L,
            readyAtMs = 150L,
        )

        assertEquals(AudioRouteProofState.ROUTE_BLUETOOTH_SUSPECT, proof.routeState)
        assertEquals(50L, proof.routeSettleMs)
    }

    @Test
    fun `failed or unknown route does not create device hash`() {
        val proof = buildAudioRouteProof(
            isActive = false,
            isReadyForSpeechCapture = false,
            status = "No communication headset detected",
            selectedDeviceName = null,
            selectedDeviceType = null,
            communicationDeviceCount = 0,
            requestedAtMs = null,
            readyAtMs = null,
        )

        assertEquals(AudioRouteProofState.ROUTE_FAILED, proof.routeState)
        assertNull(proof.selectedDeviceHash)
        assertNull(proof.routeSettleMs)
    }
}
