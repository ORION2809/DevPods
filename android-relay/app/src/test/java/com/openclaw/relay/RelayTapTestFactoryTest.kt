package com.openclaw.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayTapTestFactoryTest {
    @Test
    fun `tap test wake signal is labeled for manual validation`() {
        val wakeSignal = RelayTapTestFactory.createWakeSignal()

        assertEquals("tap_test_button", wakeSignal.trigger)
        assertEquals("manual_tap_test", wakeSignal.source)
        assertEquals("Tap test button", wakeSignal.sourceLabel)
        assertEquals("Manual UI trigger", wakeSignal.keyLabel)
        assertEquals(ManualTapTestSignalProvider.providerId, wakeSignal.provider.providerId)
        assertEquals(ManualTapTestSignalProvider.providerLabel, wakeSignal.provider.providerLabel)
        assertEquals(RelaySignalConfidence.OBSERVED, wakeSignal.provider.confidence)
        assertEquals(false, wakeSignal.provider.isPhysicalInput)
    }

    @Test
    fun `tap test wake signal timestamp stays within the current time window`() {
        val before = System.currentTimeMillis()
        val wakeSignal = RelayTapTestFactory.createWakeSignal()
        val after = System.currentTimeMillis()

        assertTrue(wakeSignal.receivedAtMs in before..after)
    }
}