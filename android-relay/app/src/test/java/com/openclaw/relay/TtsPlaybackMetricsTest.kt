package com.openclaw.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPlaybackMetricsTest {
    @Test
    fun `recorder captures playback lifecycle and stop latency`() {
        val recorder = TtsPlaybackMetricsRecorder(
            utteranceId = "relay-1",
            textLength = 21,
            requestedAtMs = 1_000L,
        )

        recorder.markStarted(1_080L)
        recorder.markStopped(1_130L, requestedAtMs = 1_120L)

        val metrics = recorder.snapshot()

        assertEquals(TtsPlaybackEvent.STOPPED, metrics.event)
        assertEquals(80L, metrics.startDelayMs)
        assertEquals(50L, metrics.playbackDurationMs)
        assertEquals(10L, metrics.stopLatencyMs)
    }

    @Test
    fun `completed playback records duration without stop latency`() {
        val recorder = TtsPlaybackMetricsRecorder(
            utteranceId = "relay-2",
            textLength = 12,
            requestedAtMs = 5_000L,
        )

        recorder.markStarted(5_040L)
        recorder.markDone(5_900L)

        val metrics = recorder.snapshot()

        assertEquals(TtsPlaybackEvent.DONE, metrics.event)
        assertEquals(40L, metrics.startDelayMs)
        assertEquals(860L, metrics.playbackDurationMs)
        assertTrue(metrics.stopLatencyMs == null)
    }
}
