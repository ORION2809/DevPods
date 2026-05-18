package com.openclaw.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioProbeMetricsTest {
    @Test
    fun `recorder stores amplitude summaries without raw audio`() {
        val recorder = AudioProbeMetricsRecorder(
            startedAtMs = 1_000L,
            routeSnapshot = RelayAudioRouteSnapshot(
                isActive = true,
                isReadyForSpeechCapture = true,
                status = "Headset microphone route ready",
                proof = AudioRouteProof(
                    routeState = AudioRouteProofState.ROUTE_BLUETOOTH_ACTIVE,
                    selectedDeviceType = "Bluetooth SCO",
                ),
            ),
        )

        recorder.markStarted(
            source = "VOICE_RECOGNITION",
            sampleRateHz = 16_000,
            windowSize = 512,
            minBufferSize = 1_024,
        )
        recorder.recordRead(shortArrayOf(0, 1_024, -2_048, 0), readSize = 4)
        recorder.recordRead(shortArrayOf(512, 0, 0, 0), readSize = 4)
        recorder.recordReadError()

        val metrics = recorder.finish(finishedAtMs = 1_350L)

        assertEquals(AudioProbeInitStatus.STARTED, metrics.initStatus)
        assertEquals("VOICE_RECOGNITION", metrics.audioSource)
        assertEquals(16_000, metrics.sampleRateHz)
        assertEquals(512, metrics.windowSize)
        assertEquals(8, metrics.framesRead)
        assertEquals(3, metrics.nonZeroFrames)
        assertEquals(1, metrics.readErrorCount)
        assertEquals(0.375f, metrics.nonZeroFrameRatio)
        assertTrue(metrics.peakAmplitude > 0.06f)
        assertTrue(metrics.noiseFloorEstimate > 0f)
        assertFalse(metrics.rawAudioPersisted)
        assertNull(metrics.rawAudioPath)
        assertEquals(AudioRouteProofState.ROUTE_BLUETOOTH_ACTIVE, metrics.bluetoothRouteAtCapture.routeState)
        assertEquals(350L, metrics.durationMs)
    }

    @Test
    fun `permission denied metrics stay privacy safe and empty`() {
        val metrics = AudioProbeMetrics.notStarted(
            status = AudioProbeInitStatus.PERMISSION_DENIED,
            routeSnapshot = RelayAudioRouteSnapshot(status = "Not verified"),
            startedAtMs = 2_000L,
            finishedAtMs = 2_000L,
            errorMessage = "Microphone permission denied",
        )

        assertEquals(AudioProbeInitStatus.PERMISSION_DENIED, metrics.initStatus)
        assertEquals("Microphone permission denied", metrics.errorMessage)
        assertEquals(0, metrics.framesRead)
        assertEquals(0f, metrics.nonZeroFrameRatio)
        assertFalse(metrics.rawAudioPersisted)
        assertNull(metrics.rawAudioPath)
    }
}
