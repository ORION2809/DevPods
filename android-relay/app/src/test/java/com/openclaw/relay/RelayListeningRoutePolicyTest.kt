package com.openclaw.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayListeningRoutePolicyTest {
    @Test
    fun `bluetooth routing uses retry based settle delays`() {
        assertEquals(listOf(100L, 200L, 400L), listeningRouteSettleDelays(useBluetoothRouting = true))
        assertEquals(emptyList<Long>(), listeningRouteSettleDelays(useBluetoothRouting = false))
    }

    @Test
    fun `ready communication route allows listening immediately`() {
        val decision = decideListeningRouteAfterSettle(
            useBluetoothRouting = true,
            routeSnapshot = RelayAudioRouteSnapshot(
                isReadyForSpeechCapture = true,
                status = "Headset microphone route ready",
            ),
        )

        assertTrue(decision.shouldStartListening)
        assertEquals("", decision.errorMessage)
    }

    @Test
    fun `unready route retries until the final attempt`() {
        val intermediateCheck = assessListeningRouteCheck(
            useBluetoothRouting = true,
            routeSnapshot = RelayAudioRouteSnapshot(
                isReadyForSpeechCapture = false,
                status = "Headset output is visible, but the microphone route stayed on built-in audio",
            ),
            attemptIndex = 1,
            totalAttempts = 4,
        )
        val finalCheck = assessListeningRouteCheck(
            useBluetoothRouting = true,
            routeSnapshot = RelayAudioRouteSnapshot(
                isReadyForSpeechCapture = false,
                status = "Headset output is visible, but the microphone route stayed on built-in audio",
            ),
            attemptIndex = 3,
            totalAttempts = 4,
        )

        assertFalse(intermediateCheck.shouldStartListening)
        assertTrue(intermediateCheck.shouldRetry)
        assertFalse(finalCheck.shouldStartListening)
        assertFalse(finalCheck.shouldRetry)
        assertTrue(finalCheck.errorMessage.contains("built-in audio"))
    }

    @Test
    fun `built in audio fallback blocks listening with actionable message`() {
        val decision = decideListeningRouteAfterSettle(
            useBluetoothRouting = true,
            routeSnapshot = RelayAudioRouteSnapshot(
                isReadyForSpeechCapture = false,
                status = "Headset output is visible, but the microphone route stayed on built-in audio",
            ),
        )

        assertFalse(decision.shouldStartListening)
        assertTrue(decision.errorMessage.contains("built-in audio"))
    }

    @Test
    fun `inactive headset microphone route blocks listening with fallback guidance`() {
        val decision = decideListeningRouteAfterSettle(
            useBluetoothRouting = true,
            routeSnapshot = RelayAudioRouteSnapshot(
                isReadyForSpeechCapture = false,
                status = "Headset output is visible, but no communication microphone route is active",
            ),
        )

        assertFalse(decision.shouldStartListening)
        assertTrue(decision.errorMessage.contains("never became active"))
        assertTrue(decision.errorMessage.contains("device assistant fallback"))
    }
}