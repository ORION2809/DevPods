package com.openclaw.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRouteSessionTest {

    @Test
    fun `new session has requested time but no settle or release`() {
        val session = beginAudioRouteSession("route-1", clock = { 1_000L })

        assertEquals("route-1", session.sessionId)
        assertEquals(1_000L, session.requestedAtMs)
        assertNull(session.settledAtMs)
        assertNull(session.releasedAtMs)
        assertFalse(session.isSettled)
        assertFalse(session.isReleased)
        assertNull(session.settleDurationMs)
        assertNull(session.activeDurationMs)
    }

    @Test
    fun `markSettled records settle time and snapshot`() {
        val session = beginAudioRouteSession("route-2", clock = { 1_000L })
            .markSettled(
                settledAtMs = 1_180L,
                snapshot = RelayAudioRouteSnapshot(
                    isActive = true,
                    isReadyForSpeechCapture = true,
                    status = "Headset ready",
                ),
            )

        assertTrue(session.isSettled)
        assertEquals(180L, session.settleDurationMs)
        assertEquals("Headset ready", session.snapshot.status)
        assertEquals(1, session.settleAttempts)
    }

    @Test
    fun `markReleased records release time and computes active duration`() {
        val session = beginAudioRouteSession("route-3", clock = { 1_000L })
            .markSettled(
                settledAtMs = 1_100L,
                snapshot = RelayAudioRouteSnapshot(isActive = true),
            )
            .markReleased(releasedAtMs = 1_500L)

        assertTrue(session.isReleased)
        assertEquals(400L, session.activeDurationMs)
    }

    @Test
    fun `markSettleAttempt increments attempt counter without settling`() {
        val session = beginAudioRouteSession("route-4", clock = { 1_000L })
            .markSettleAttempt()
            .markSettleAttempt()

        assertFalse(session.isSettled)
        assertEquals(2, session.settleAttempts)
    }

    @Test
    fun `negative settle duration is coerced to zero`() {
        val session = beginAudioRouteSession("route-5", clock = { 1_000L })
            .markSettled(
                settledAtMs = 500L,
                snapshot = RelayAudioRouteSnapshot(isActive = true),
            )

        assertEquals(0L, session.settleDurationMs)
    }

    @Test
    fun `speculative route preparation session tracks candidate not capture`() {
        val session = beginAudioRouteSession("speculative-candidate-1", clock = { 2_000L })
            .markSettled(
                settledAtMs = 2_150L,
                snapshot = RelayAudioRouteSnapshot(
                    isActive = true,
                    isReadyForSpeechCapture = true,
                    status = "Speculative route ready",
                ),
            )

        assertTrue(session.isSettled)
        assertEquals(150L, session.settleDurationMs)
        assertEquals("Speculative route ready", session.snapshot.status)
        assertTrue(session.snapshot.isReadyForSpeechCapture)
        assertFalse(session.isReleased)
    }

    @Test
    fun `speculative route session can be released without ever capturing audio`() {
        val session = beginAudioRouteSession("speculative-candidate-2", clock = { 3_000L })
            .markSettled(
                settledAtMs = 3_200L,
                snapshot = RelayAudioRouteSnapshot(
                    isActive = true,
                    isReadyForSpeechCapture = true,
                    status = "Route prepared",
                ),
            )
            .markReleased(releasedAtMs = 3_500L)

        assertTrue(session.isReleased)
        assertEquals(300L, session.activeDurationMs)
    }
}
