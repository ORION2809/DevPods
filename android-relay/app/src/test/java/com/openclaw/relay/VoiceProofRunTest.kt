package com.openclaw.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceProofRunTest {
    @Test
    fun `records completed final session once and computes proof summary`() {
        val run = VoiceProofRun.start(
            runId = "proof-1",
            targetSessionCount = 2,
            startedAtMs = 1_000L,
        ).recordSpeechSession(successfulSpeechMetrics("speech-1"))

        assertEquals(1, run.sessions.size)
        assertEquals(1, run.summary.completedSessionCount)
        assertEquals(1, run.summary.successfulSessionCount)
        assertEquals(1, run.summary.routeSuccessCount)
        assertEquals(1, run.summary.sttSuccessCount)
        assertEquals(50, run.summary.reliabilityPercent)
        assertEquals(VoiceProofRunStatus.RUNNING, run.status)
        assertNull(run.finishedAtMs)
        assertEquals("synthetic_pcm", run.sessions.first().routeSelectedDeviceType)
        assertEquals(720L, run.sessions.first().durationMs)
    }

    @Test
    fun `replaces same session instead of double counting partial updates`() {
        val listeningMetrics = successfulSpeechMetrics("speech-1").copy(
            finalAtMs = null,
            endpointReason = SpeechEndpointReason.LISTENING,
        )
        val completedMetrics = successfulSpeechMetrics("speech-1")

        val run = VoiceProofRun.start("proof-2", targetSessionCount = 1, startedAtMs = 1_000L)
            .recordSpeechSession(listeningMetrics)
            .recordSpeechSession(completedMetrics)

        assertEquals(1, run.sessions.size)
        assertEquals(1, run.summary.completedSessionCount)
        assertEquals(1, run.summary.successfulSessionCount)
        assertEquals(VoiceProofRunStatus.PASSED, run.status)
        assertEquals(completedMetrics.finalAtMs, run.finishedAtMs)
    }

    @Test
    fun `failed completed run exposes no speech and wrong mic suspicion`() {
        val wrongMicMetrics = SpeechSessionMetrics(
            sessionId = "speech-1",
            engineId = "platform_speech_recognizer",
            startedAtMs = 1_000L,
            routeProof = AudioRouteProof(routeState = AudioRouteProofState.ROUTE_BLUETOOTH_ACTIVE),
            readyForSpeechAtMs = 1_050L,
            rmsFrameCount = 0,
            errorAtMs = 1_800L,
            endpointReason = SpeechEndpointReason.NO_SPEECH,
        )
        val routeFailedMetrics = SpeechSessionMetrics(
            sessionId = "speech-2",
            engineId = "platform_speech_recognizer",
            startedAtMs = 2_000L,
            routeProof = AudioRouteProof(routeState = AudioRouteProofState.ROUTE_FAILED),
            errorAtMs = 2_100L,
            endpointReason = SpeechEndpointReason.ROUTE_FAILED,
        )

        val run = VoiceProofRun.start("proof-3", targetSessionCount = 2, startedAtMs = 1_000L)
            .recordSpeechSession(wrongMicMetrics)
            .recordSpeechSession(routeFailedMetrics)

        assertEquals(VoiceProofRunStatus.FAILED, run.status)
        assertEquals(2, run.summary.completedSessionCount)
        assertEquals(0, run.summary.successfulSessionCount)
        assertEquals(1, run.summary.wrongMicSuspectedCount)
        assertEquals(1, run.summary.routeFailureCount)
        assertTrue(run.summary.failureReasons.contains("wrong_mic_suspected"))
        assertTrue(run.summary.failureReasons.contains("route_failed"))
    }

    @Test
    fun `audio probe metrics attach to the proof run without raw audio`() {
        val probe = AudioProbeMetrics(
            initStatus = AudioProbeInitStatus.STARTED,
            startedAtMs = 1_000L,
            finishedAtMs = 1_500L,
            audioSource = "VOICE_RECOGNITION",
            framesRead = 512,
            nonZeroFrames = 400,
            peakAmplitude = 0.42f,
            bluetoothRouteAtCapture = AudioRouteProof(routeState = AudioRouteProofState.ROUTE_BLUETOOTH_ACTIVE),
            rawAudioPersisted = false,
        )

        val run = VoiceProofRun.start("proof-4", targetSessionCount = 1, startedAtMs = 1_000L)
            .recordAudioProbe(probe)

        assertEquals(AudioProbeInitStatus.STARTED, run.lastAudioProbe?.initStatus)
        assertEquals(0.78125f, run.lastAudioProbe?.nonZeroFrameRatio)
        assertFalse(run.lastAudioProbe?.rawAudioPersisted ?: true)
    }

    @Test
    fun `proof summary hasBlockingFailures when any failure reason exists`() {
        val probe = AudioProbeMetrics(
            initStatus = AudioProbeInitStatus.STARTED,
            startedAtMs = 1_000L,
            finishedAtMs = 1_500L,
            framesRead = 100,
            nonZeroFrames = 0,
            peakAmplitude = 0f,
            bluetoothRouteAtCapture = AudioRouteProof(routeState = AudioRouteProofState.ROUTE_BLUETOOTH_ACTIVE),
            rawAudioPersisted = false,
        )

        val run = VoiceProofRun.start("proof-blocking", targetSessionCount = 1, startedAtMs = 1_000L)
            .recordAudioProbe(probe)
            .recordSpeechSession(successfulSpeechMetrics("speech-1"))

        assertEquals(VoiceProofRunStatus.PASSED, run.status)
        assertTrue(run.summary.hasBlockingFailures)
        assertTrue(run.summary.failureReasons.contains("audio_probe_no_signal"))
    }

    @Test
    fun `proof summary has no blocking failures when everything is clean`() {
        val run = VoiceProofRun.start("proof-clean", targetSessionCount = 1, startedAtMs = 1_000L)
            .recordSpeechSession(successfulSpeechMetrics("speech-1"))

        assertEquals(VoiceProofRunStatus.PASSED, run.status)
        assertFalse(run.summary.hasBlockingFailures)
        assertTrue(run.summary.failureReasons.isEmpty())
    }

    @Test
    fun `audio probe with no signal adds audio_probe_no_signal failure reason`() {
        val probe = AudioProbeMetrics(
            initStatus = AudioProbeInitStatus.STARTED,
            startedAtMs = 1_000L,
            finishedAtMs = 1_500L,
            framesRead = 100,
            nonZeroFrames = 0,
            peakAmplitude = 0f,
            bluetoothRouteAtCapture = AudioRouteProof(routeState = AudioRouteProofState.ROUTE_BLUETOOTH_ACTIVE),
            rawAudioPersisted = false,
        )

        val run = VoiceProofRun.start("proof-5", targetSessionCount = 1, startedAtMs = 1_000L)
            .recordAudioProbe(probe)
            .recordSpeechSession(successfulSpeechMetrics("speech-1"))

        assertTrue(run.summary.failureReasons.contains("audio_probe_no_signal"))
    }

    @Test
    fun `audio probe with read errors adds audio_probe_read_failed failure reason`() {
        val probe = AudioProbeMetrics(
            initStatus = AudioProbeInitStatus.STARTED,
            startedAtMs = 1_000L,
            finishedAtMs = 1_500L,
            framesRead = 100,
            nonZeroFrames = 50,
            readErrorCount = 3,
            peakAmplitude = 0.2f,
            bluetoothRouteAtCapture = AudioRouteProof(routeState = AudioRouteProofState.ROUTE_BLUETOOTH_ACTIVE),
            rawAudioPersisted = false,
        )

        val run = VoiceProofRun.start("proof-6", targetSessionCount = 1, startedAtMs = 1_000L)
            .recordAudioProbe(probe)
            .recordSpeechSession(successfulSpeechMetrics("speech-1"))

        assertTrue(run.summary.failureReasons.contains("audio_probe_read_failed"))
    }

    @Test
    fun `healthy audio probe adds neither no_signal nor read_failed`() {
        val probe = AudioProbeMetrics(
            initStatus = AudioProbeInitStatus.STARTED,
            startedAtMs = 1_000L,
            finishedAtMs = 1_500L,
            framesRead = 100,
            nonZeroFrames = 50,
            readErrorCount = 0,
            peakAmplitude = 0.2f,
            bluetoothRouteAtCapture = AudioRouteProof(routeState = AudioRouteProofState.ROUTE_BLUETOOTH_ACTIVE),
            rawAudioPersisted = false,
        )

        val run = VoiceProofRun.start("proof-7", targetSessionCount = 1, startedAtMs = 1_000L)
            .recordAudioProbe(probe)
            .recordSpeechSession(successfulSpeechMetrics("speech-1"))

        assertFalse(run.summary.failureReasons.contains("audio_probe_no_signal"))
        assertFalse(run.summary.failureReasons.contains("audio_probe_read_failed"))
    }

    private fun successfulSpeechMetrics(sessionId: String): SpeechSessionMetrics =
        SpeechSessionMetrics(
            sessionId = sessionId,
            engineId = "platform_speech_recognizer",
            startedAtMs = 1_000L,
            routeProof = AudioRouteProof(
                routeState = AudioRouteProofState.ROUTE_BLUETOOTH_ACTIVE,
                routeRequestedAtMs = 1_010L,
                routeReadyAtMs = 1_070L,
                selectedDeviceType = "synthetic_pcm",
            ),
            readyForSpeechAtMs = 1_100L,
            beginSpeechAtMs = 1_180L,
            firstRmsAtMs = 1_190L,
            rmsFrameCount = 4,
            rmsPeakDb = -4f,
            endSpeechAtMs = 1_600L,
            finalAtMs = 1_720L,
            endpointReason = SpeechEndpointReason.FINAL,
            finalTranscriptLength = 13,
        )
}
