package com.openclaw.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaySignalProviderSummaryTest {
    @Test
    fun `manual validation does not erase last verified physical provider`() {
        val physicalSummary = recordObservedProvider(
            RelaySignalProviderSummary(),
            AndroidMediaSessionSignalProvider.observe(),
        )

        val summaryAfterTapTest = recordObservedProvider(
            physicalSummary,
            ManualTapTestSignalProvider.observe(),
        )

        assertEquals(ManualTapTestSignalProvider.providerId, summaryAfterTapTest.lastProvider?.providerId)
        assertEquals(AndroidMediaSessionSignalProvider.providerId, summaryAfterTapTest.lastPhysicalWakeProvider?.providerId)
        assertTrue(summaryAfterTapTest.hasVerifiedPhysicalWake)
    }

    @Test
    fun `provider observations stay deduplicated by provider id`() {
        val firstObservation = AndroidMediaSessionSignalProvider.observe(deviceLabel = "Headset hook")
        val updatedObservation = AndroidMediaSessionSignalProvider.observe(deviceLabel = "Play or pause")

        val summary = recordObservedProvider(
            recordObservedProvider(RelaySignalProviderSummary(), firstObservation),
            updatedObservation,
        )

        assertEquals(1, summary.observedProviders.size)
        assertEquals("Play or pause", summary.observedProviders.single().deviceLabel)
    }

    @Test
    fun `unproven physical providers do not mark hardware wake as verified`() {
        val summary = recordObservedProvider(
            RelaySignalProviderSummary(),
            LibrePodsAirPodsSignalProvider.observe(),
        )

        assertEquals(LibrePodsAirPodsSignalProvider.providerId, summary.lastProvider?.providerId)
        assertEquals(null, summary.lastPhysicalWakeProvider)
        assertTrue(!summary.hasVerifiedPhysicalWake)
    }

    @Test
    fun `downgrading a proven physical provider keeps the last verified physical provider`() {
        val provenSummary = recordObservedProvider(
            RelaySignalProviderSummary(),
            AndroidMediaSessionSignalProvider.observe(),
        )

        val downgradedSummary = recordObservedProvider(
            provenSummary,
            AndroidMediaSessionSignalProvider.observe(confidence = RelaySignalConfidence.OBSERVED),
        )

        assertEquals(AndroidMediaSessionSignalProvider.providerId, downgradedSummary.lastProvider?.providerId)
        assertEquals(AndroidMediaSessionSignalProvider.providerId, downgradedSummary.lastPhysicalWakeProvider?.providerId)
        assertEquals(RelaySignalConfidence.PROVEN, downgradedSummary.lastPhysicalWakeProvider?.confidence)
    }
}
