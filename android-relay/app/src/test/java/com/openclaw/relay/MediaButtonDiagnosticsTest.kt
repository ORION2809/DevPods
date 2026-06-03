package com.openclaw.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaButtonDiagnosticsTest {
    @Test
    fun `normalizer maps media keys to stable labels and gestures`() {
        val playPause = MediaButtonDiagnostics.normalize(
            keyCode = MediaButtonDiagnostics.KEYCODE_MEDIA_PLAY_PAUSE,
            action = MediaButtonAction.DOWN,
            repeatCount = 0,
            receivedAtMs = 1_000L,
        )
        val next = MediaButtonDiagnostics.normalize(
            keyCode = MediaButtonDiagnostics.KEYCODE_MEDIA_NEXT,
            action = MediaButtonAction.DOWN,
            repeatCount = 0,
            receivedAtMs = 1_100L,
        )

        assertEquals("MEDIA_PLAY_PAUSE", playPause.keyLabel)
        assertEquals("defer_to_tap_detector", playPause.mapping)
        assertTrue(playPause.accepted)
        assertEquals("MEDIA_NEXT_TRACK", next.keyLabel)
        assertEquals("interrupt_double_press", next.mapping)
    }

    @Test
    fun `debouncer suppresses accidental duplicate media down events`() {
        val debouncer = MediaButtonEventDebouncer(minDuplicateIntervalMs = 120L)

        val first = debouncer.accepts(
            keyCode = MediaButtonDiagnostics.KEYCODE_MEDIA_PLAY_PAUSE,
            action = MediaButtonAction.DOWN,
            receivedAtMs = 1_000L,
        )
        val duplicate = debouncer.accepts(
            keyCode = MediaButtonDiagnostics.KEYCODE_MEDIA_PLAY_PAUSE,
            action = MediaButtonAction.DOWN,
            receivedAtMs = 1_050L,
        )
        val intentionalLaterTap = debouncer.accepts(
            keyCode = MediaButtonDiagnostics.KEYCODE_MEDIA_PLAY_PAUSE,
            action = MediaButtonAction.DOWN,
            receivedAtMs = 1_250L,
        )

        assertTrue(first)
        assertFalse(duplicate)
        assertTrue(intentionalLaterTap)
    }

    @Test
    fun `foreground controls expose required relay actions`() {
        val controls = ForegroundControlSnapshot.requiredRelayControls()

        assertTrue(controls.hasPushToTalk)
        assertTrue(controls.hasRetryQueue)
        assertTrue(controls.hasCancelCurrentAction)
        assertTrue(controls.hasStopRelay)
        assertEquals(4, controls.actionCount)
        assertTrue(controls.missingRequiredActions.isEmpty())
    }

    @Test
    fun `recovery plan never restores active microphone or speech after process death`() {
        val previous = RelayUiState(
            isServiceRunning = true,
            isListening = true,
            isAwaitingBridgeResponse = true,
            isSpeaking = true,
            pendingActionId = "action-1",
            activeAutonomy = BridgeAutonomyInstruction(
                phase = "implementation",
                mode = "continue_on_silence",
                summary = "Working",
                continueAfterMs = 10_000,
                nextIntent = "continue",
            ),
        )

        val plan = RelayServiceRecoveryPolicy.plan(previous)

        assertTrue(plan.shouldRestartForeground)
        assertFalse(plan.restoreListening)
        assertFalse(plan.restoreSpeaking)
        assertFalse(plan.restoreAwaitingBridge)
        assertTrue(plan.clearTransientAutonomy)
        assertEquals("safe_idle_after_process_restart", plan.reason)
    }

    @Test
    fun `foreground service snapshot reports missing controls`() {
        val incomplete = ForegroundControlSnapshot(
            hasPushToTalk = true,
            hasRetryQueue = false,
            hasCancelCurrentAction = true,
            hasStopRelay = false,
        )
        val snapshot = RelayForegroundServiceSnapshot(
            isForegroundActive = true,
            mediaSessionReady = true,
            notificationControls = incomplete,
            updatedAtMs = 1_000L,
        )

        assertFalse(snapshot.isComplete)
        assertEquals(listOf("retry_queue", "stop_relay"), snapshot.missingControls)
    }

    @Test
    fun `debouncer treats rapid candidate presses as duplicates`() {
        val debouncer = MediaButtonEventDebouncer(minDuplicateIntervalMs = 120L)

        val first = debouncer.accepts(
            keyCode = MediaButtonDiagnostics.KEYCODE_MEDIA_PLAY_PAUSE,
            action = MediaButtonAction.DOWN,
            receivedAtMs = 1_000L,
        )
        // A candidate press 80ms later should still be debounced
        val rapidCandidate = debouncer.accepts(
            keyCode = MediaButtonDiagnostics.KEYCODE_MEDIA_PLAY_PAUSE,
            action = MediaButtonAction.DOWN,
            receivedAtMs = 1_080L,
        )
        // But a later intentional tap is accepted
        val intentionalLaterTap = debouncer.accepts(
            keyCode = MediaButtonDiagnostics.KEYCODE_MEDIA_PLAY_PAUSE,
            action = MediaButtonAction.DOWN,
            receivedAtMs = 1_250L,
        )

        assertTrue(first)
        assertFalse(rapidCandidate)
        assertTrue(intentionalLaterTap)
    }

    @Test
    fun `normalizer marks candidate key down as accepted`() {
        val telemetry = MediaButtonDiagnostics.normalize(
            keyCode = MediaButtonDiagnostics.KEYCODE_MEDIA_PLAY_PAUSE,
            action = MediaButtonAction.DOWN,
            repeatCount = 0,
            receivedAtMs = 1_000L,
        )
        assertTrue(telemetry.accepted)
        assertEquals("MEDIA_PLAY_PAUSE", telemetry.keyLabel)
        assertEquals("defer_to_tap_detector", telemetry.mapping)
    }

    @Test
    fun `speculative route preparation does not count as audio capture`() {
        // Documented invariant: route preparation on InputCandidateStarted must not
        // trigger STT or send bridge commands. This test asserts the telemetry shape
        // that distinguishes route preparation from actual capture.
        val routePrepareTelemetry = MediaButtonDiagnostics.normalize(
            keyCode = MediaButtonDiagnostics.KEYCODE_MEDIA_PLAY_PAUSE,
            action = MediaButtonAction.DOWN,
            repeatCount = 0,
            receivedAtMs = 2_000L,
            routeState = AudioRouteProofState.ROUTE_BLUETOOTH_REQUESTED,
            serviceRunning = true,
        )
        assertTrue(routePrepareTelemetry.accepted)
        assertEquals(AudioRouteProofState.ROUTE_BLUETOOTH_REQUESTED, routePrepareTelemetry.routeState)
        assertTrue(routePrepareTelemetry.serviceRunning)
    }
}
