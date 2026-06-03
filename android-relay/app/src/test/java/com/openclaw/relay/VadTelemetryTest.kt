package com.openclaw.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VadTelemetryTest {

    @Test
    fun `empty telemetry has zero rates and null averages`() {
        val telemetry = VadTelemetry()

        assertEquals(0f, telemetry.speechDetectionRate(), 0.001f)
        assertEquals(0f, telemetry.wrongMicSuspicionRate(), 0.001f)
        assertNull(telemetry.averageSpeechStartDelayMs())
        assertNull(telemetry.peakRmsDb())
        assertEquals(VadTrend.INSUFFICIENT_DATA, telemetry.trend())
    }

    @Test
    fun `records observations and computes speech detection rate`() {
        val telemetry = VadTelemetry()

        telemetry.record(vad(speechDetected = true))
        telemetry.record(vad(speechDetected = true))
        telemetry.record(vad(speechDetected = false))

        assertEquals(3, telemetry.observationCount())
        assertEquals(0.666f, telemetry.speechDetectionRate(), 0.01f)
    }

    @Test
    fun `computes wrong mic suspicion rate`() {
        val telemetry = VadTelemetry()

        repeat(8) { telemetry.record(vad(wrongMicSuspected = false)) }
        repeat(2) { telemetry.record(vad(wrongMicSuspected = true)) }

        assertEquals(0.2f, telemetry.wrongMicSuspicionRate(), 0.001f)
    }

    @Test
    fun `averages delays correctly`() {
        val telemetry = VadTelemetry()

        telemetry.record(vad(speechStartDelayMs = 100L))
        telemetry.record(vad(speechStartDelayMs = 200L))
        telemetry.record(vad(speechStartDelayMs = 300L))

        assertEquals(200.0, telemetry.averageSpeechStartDelayMs()!!, 0.01)
    }

    @Test
    fun `finds peak rms`() {
        val telemetry = VadTelemetry()

        telemetry.record(vad(rmsPeakDb = -10f))
        telemetry.record(vad(rmsPeakDb = -3f))
        telemetry.record(vad(rmsPeakDb = -7f))

        assertEquals(-3f, telemetry.peakRmsDb()!!, 0.01f)
    }

    @Test
    fun `endpoint distribution counts reasons`() {
        val telemetry = VadTelemetry()

        telemetry.record(vad(endpointReason = SpeechEndpointReason.FINAL))
        telemetry.record(vad(endpointReason = SpeechEndpointReason.FINAL))
        telemetry.record(vad(endpointReason = SpeechEndpointReason.NO_SPEECH))
        telemetry.record(vad(endpointReason = SpeechEndpointReason.TIMEOUT))

        val distribution = telemetry.endpointDistribution()
        assertEquals(2, distribution[SpeechEndpointReason.FINAL])
        assertEquals(1, distribution[SpeechEndpointReason.NO_SPEECH])
        assertEquals(1, distribution[SpeechEndpointReason.TIMEOUT])
    }

    @Test
    fun `trend detects declining speech detection`() {
        val telemetry = VadTelemetry()

        // First half: mostly speech detected
        repeat(10) { telemetry.record(vad(speechDetected = true)) }
        // Second half: mostly no speech
        repeat(10) { telemetry.record(vad(speechDetected = false)) }

        assertEquals(VadTrend.SPEECH_DETECTION_DECLINING, telemetry.trend())
    }

    @Test
    fun `trend detects rising wrong mic suspicion`() {
        val telemetry = VadTelemetry()

        // First half: no wrong mic, all speech detected
        repeat(10) { telemetry.record(vad(wrongMicSuspected = false, speechDetected = true)) }
        // Second half: rising wrong mic suspicion, but speech detection stays similar
        repeat(5) { telemetry.record(vad(wrongMicSuspected = false, speechDetected = true)) }
        repeat(5) { telemetry.record(vad(wrongMicSuspected = true, speechDetected = true)) }

        assertEquals(VadTrend.WRONG_MIC_SUSPICION_RISING, telemetry.trend())
    }

    @Test
    fun `trend reports stable when rates are similar`() {
        val telemetry = VadTelemetry()

        repeat(10) { telemetry.record(vad(speechDetected = true)) }
        repeat(10) { telemetry.record(vad(speechDetected = true)) }

        assertEquals(VadTrend.STABLE, telemetry.trend())
    }

    @Test
    fun `max observations window is enforced`() {
        val telemetry = VadTelemetry(maxObservations = 5)

        repeat(10) { telemetry.record(vad(speechDetected = true)) }

        assertEquals(5, telemetry.observationCount())
    }

    private fun vad(
        speechDetected: Boolean = true,
        wrongMicSuspected: Boolean = false,
        speechStartDelayMs: Long? = null,
        rmsPeakDb: Float? = null,
        endpointReason: SpeechEndpointReason = SpeechEndpointReason.FINAL,
    ): PlatformVadObservation =
        PlatformVadObservation(
            speechDetected = speechDetected,
            wrongMicSuspected = wrongMicSuspected,
            speechStartDelayMs = speechStartDelayMs,
            rmsPeakDb = rmsPeakDb,
            endpointReason = endpointReason,
        )
}
