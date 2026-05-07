package com.openclaw.relay

import org.junit.Assert.assertEquals
import org.junit.Test

class RelayTapTestFactoryTest {
    @Test
    fun `tap test wake signal is labeled for manual validation`() {
        val wakeSignal = RelayTapTestFactory.createWakeSignal()

        assertEquals("tap_test_button", wakeSignal.trigger)
        assertEquals("manual_tap_test", wakeSignal.source)
        assertEquals("Tap test button", wakeSignal.sourceLabel)
        assertEquals("Manual UI trigger", wakeSignal.keyLabel)
    }
}