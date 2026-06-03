package com.openclaw.relay

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `recorder captures audio focus request result`() {
        val recorder = TtsPlaybackMetricsRecorder(
            utteranceId = "relay-focus",
            textLength = 10,
            requestedAtMs = 1_000L,
        )

        recorder.markFocusRequested(1_010L, AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

        val metrics = recorder.snapshot()
        assertEquals(1_010L, metrics.focusRequestedAtMs)
        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, metrics.focusResult)
        assertNull(metrics.focusLostAtMs)
        assertNull(metrics.focusLostReason)
        assertNull(metrics.focusGainedAtMs)
    }

    @Test
    fun `recorder captures transient focus loss`() {
        val recorder = TtsPlaybackMetricsRecorder(
            utteranceId = "relay-loss",
            textLength = 10,
            requestedAtMs = 1_000L,
        )

        recorder.markFocusRequested(1_010L, AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        recorder.markStarted(1_020L)
        recorder.markFocusLost(1_050L, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        val metrics = recorder.snapshot()
        assertEquals(1_050L, metrics.focusLostAtMs)
        assertEquals(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, metrics.focusLostReason)
        assertNull(metrics.focusGainedAtMs)
    }

    @Test
    fun `recorder captures focus gain after transient loss`() {
        val recorder = TtsPlaybackMetricsRecorder(
            utteranceId = "relay-gain",
            textLength = 10,
            requestedAtMs = 1_000L,
        )

        recorder.markFocusRequested(1_010L, AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        recorder.markFocusLost(1_030L, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        recorder.markFocusGained(1_060L)

        val metrics = recorder.snapshot()
        assertEquals(1_060L, metrics.focusGainedAtMs)
        assertEquals(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, metrics.focusLostReason)
    }

    @Test
    fun `recorder captures permanent focus loss`() {
        val recorder = TtsPlaybackMetricsRecorder(
            utteranceId = "relay-perm",
            textLength = 10,
            requestedAtMs = 1_000L,
        )

        recorder.markFocusRequested(1_010L, AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        recorder.markStarted(1_020L)
        recorder.markFocusLost(1_040L, AudioManager.AUDIOFOCUS_LOSS)
        recorder.markError(1_045L, errorCode = null)

        val metrics = recorder.snapshot()
        assertEquals(TtsPlaybackEvent.ERROR, metrics.event)
        assertEquals(1_040L, metrics.focusLostAtMs)
        assertEquals(AudioManager.AUDIOFOCUS_LOSS, metrics.focusLostReason)
    }
}
