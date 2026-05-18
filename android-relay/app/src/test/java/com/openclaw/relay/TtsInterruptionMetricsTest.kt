package com.openclaw.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsInterruptionMetricsTest {
    @Test
    fun `recorder captures stop and listening latency for barge in`() {
        val recorder = TtsInterruptionMetricsRecorder(
            interruptionId = "interrupt-1",
            reason = TtsInterruptionReason.BARGE_IN,
            requestedAtMs = 1_000L,
        )

        recorder.markTtsStopped(1_120L)
        recorder.markListeningStarted(1_210L)

        val metrics = recorder.snapshot()

        assertEquals("interrupt-1", metrics.interruptionId)
        assertEquals(TtsInterruptionReason.BARGE_IN, metrics.reason)
        assertEquals(120L, metrics.ttsStopLatencyMs)
        assertEquals(210L, metrics.bargeInLatencyMs)
        assertTrue(metrics.targetMet)
    }

    @Test
    fun `interruption misses target when listening starts too late`() {
        val recorder = TtsInterruptionMetricsRecorder(
            interruptionId = "interrupt-2",
            reason = TtsInterruptionReason.STOP_REQUESTED,
            requestedAtMs = 2_000L,
        )

        recorder.markTtsStopped(2_260L)
        recorder.markListeningStarted(2_400L)

        val metrics = recorder.snapshot()

        assertEquals(260L, metrics.ttsStopLatencyMs)
        assertEquals(400L, metrics.bargeInLatencyMs)
        assertFalse(metrics.targetMet)
    }

    @Test
    fun `voice proof run counts interruption target hits`() {
        val fast = TtsInterruptionMetrics(
            interruptionId = "interrupt-fast",
            reason = TtsInterruptionReason.BARGE_IN,
            requestedAtMs = 1_000L,
            ttsStoppedAtMs = 1_100L,
            listeningStartedAtMs = 1_200L,
        )
        val slow = fast.copy(
            interruptionId = "interrupt-slow",
            requestedAtMs = 2_000L,
            ttsStoppedAtMs = 2_310L,
            listeningStartedAtMs = 2_420L,
        )

        val run = VoiceProofRun.start("proof-interruption", targetSessionCount = 2, startedAtMs = 1_000L)
            .recordTtsInterruption(fast)
            .recordTtsInterruption(slow)

        assertEquals(2, run.ttsInterruptions.size)
        assertEquals(1, run.summary.interruptionTargetMetCount)
    }
}
