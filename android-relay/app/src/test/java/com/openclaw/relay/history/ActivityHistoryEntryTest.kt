package com.openclaw.relay.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityHistoryEntryTest {

    @Test
    fun `all activity event types include route and wrong mic variants`() {
        val types = ActivityEventType.entries
        assertEquals(13, types.size)
        assertTrue(types.contains(ActivityEventType.WAKE))
        assertTrue(types.contains(ActivityEventType.ROUTE_SETTLED))
        assertTrue(types.contains(ActivityEventType.WRONG_MIC_SUSPECTED))
    }

    @Test
    fun `activity entry stores type summary and detail`() {
        val entry = ActivityHistoryEntry(
            type = ActivityEventType.WRONG_MIC_SUSPECTED,
            summary = "Wrong microphone suspected",
            detail = "Bluetooth route appeared active but no microphone signal was detected.",
            timestampMs = 1_000L,
        )

        assertEquals(ActivityEventType.WRONG_MIC_SUSPECTED, entry.type)
        assertEquals("Wrong microphone suspected", entry.summary)
        assertEquals("Bluetooth route appeared active but no microphone signal was detected.", entry.detail)
        assertEquals(1_000L, entry.timestampMs)
    }

    @Test
    fun `route settled entry captures settle timing`() {
        val entry = ActivityHistoryEntry(
            type = ActivityEventType.ROUTE_SETTLED,
            summary = "Microphone route ready",
            detail = "Route settled in 120ms",
        )

        assertEquals(ActivityEventType.ROUTE_SETTLED, entry.type)
        assertEquals("Microphone route ready", entry.summary)
    }
}
