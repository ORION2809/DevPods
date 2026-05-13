package com.openclaw.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaySignalMessagingTest {
    @Test
    fun `ready signal path reflects the last verified physical provider`() {
        val state = RelayUiState(
            signalProviderSummary = RelaySignalProviderSummary(
                lastPhysicalWakeProvider = AndroidMediaSessionSignalProvider.observe(),
            ),
        )

        assertTrue(describeReadySignalPath(state).contains("Android media controls"))
    }

    @Test
    fun `manual tap messaging stays explicit about non physical verification`() {
        val state = RelayUiState(
            lastWakeSignal = RelayWakeSignal(
                trigger = RelayTapTestFactory.EVENT_NAME,
                source = "manual_tap_test",
                sourceLabel = "Tap test button",
                provider = ManualTapTestSignalProvider.observe(),
            ),
        )

        assertTrue(describeWakeVerification(state).contains("does not verify physical earbud delivery"))
    }

    @Test
    fun `observed provider formatting includes confidence and physical hints`() {
        val summary = RelaySignalProviderSummary(
            observedProviders = listOf(
                AndroidMediaSessionSignalProvider.observe(),
                ManualTapTestSignalProvider.observe(),
            ),
        )

        assertEquals(
            "Android media controls (proven, physical), Tap Test relay path (observed)",
            formatObservedProviders(summary),
        )
    }
}