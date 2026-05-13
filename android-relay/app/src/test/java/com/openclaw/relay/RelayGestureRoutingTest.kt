package com.openclaw.relay

import com.openclaw.relay.signal.GestureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayGestureRoutingTest {
    @Test
    fun `wake triggers open the listening window`() {
        assertTrue(shouldOpenListeningWindow("headset_button_single"))
        assertTrue(shouldOpenListeningWindow("android_push_to_talk"))
        assertTrue(shouldOpenListeningWindow("android_autonomy_interrupt"))
    }

    @Test
    fun `normalizeProviderEvent maps gesture types to schema valid bridge events`() {
        assertEquals("headset_button_single", normalizeProviderEventToBridgeTrigger(GestureType.SINGLE_PRESS))
        assertEquals("headset_button_single", normalizeProviderEventToBridgeTrigger(GestureType.LONG_PRESS))
        assertEquals("headset_button_single", normalizeProviderEventToBridgeTrigger(GestureType.DOUBLE_PRESS))
        assertEquals("headset_button_single", normalizeProviderEventToBridgeTrigger(GestureType.TRIPLE_PRESS))

        assertEquals("android_autonomy_interrupt", normalizeProviderEventToBridgeTrigger(GestureType.SINGLE_PRESS, isInterrupt = true))
        assertEquals("android_autonomy_interrupt", normalizeProviderEventToBridgeTrigger(GestureType.DOUBLE_PRESS, isInterrupt = true))

        assertEquals("android_approve", normalizeProviderEventToBridgeTrigger(GestureType.TRIPLE_PRESS, isApproval = true))
    }

    @Test
    fun `signal based routing stays provider agnostic`() {
        val androidMediaSignal = RelayWakeSignal(
            trigger = "headset_button_single",
            source = "physical_media_button",
            sourceLabel = "Physical headset media button",
            provider = AndroidMediaSessionSignalProvider.observe(),
        )
        val librePodsSignal = RelayWakeSignal(
            trigger = "headset_button_single",
            source = "librepods_native",
            sourceLabel = "LibrePods native stem press",
            provider = LibrePodsAirPodsSignalProvider.observe(
                confidence = RelaySignalConfidence.OBSERVED,
                deviceLabel = "AirPods Pro 2",
            ),
        )

        assertTrue(shouldOpenListeningWindow(androidMediaSignal))
        assertTrue(shouldOpenListeningWindow(librePodsSignal))
        assertFalse(shouldInterruptImplementation(RelayUiState(), librePodsSignal))
    }

    @Test
    fun `unproven and status triggers stay bridge only`() {
        assertFalse(shouldOpenListeningWindow("triple_tap_right"))
        assertFalse(shouldOpenListeningWindow("left_long_press"))
        assertFalse(shouldOpenListeningWindow("approve_right_double_tap"))
        assertFalse(shouldOpenListeningWindow("reject_left_double_tap"))
        assertFalse(shouldOpenListeningWindow("both_hold_cancel"))
    }

    @Test
    fun `interrupt routing activates for running work or autonomy`() {
        assertTrue(
            shouldInterruptImplementation(
                RelayUiState(
                    pendingActionId = "action_1",
                    pendingApprovalRequest = null,
                ),
                "headset_button_single",
            ),
        )
        assertTrue(
            shouldInterruptImplementation(
                RelayUiState(
                    activeAutonomy = BridgeAutonomyInstruction(
                        phase = "report",
                        mode = "continue_on_silence",
                        summary = "Tests finished.",
                        nextStep = "Refresh the repo status.",
                        continueAfterMs = 4000,
                        nextIntent = "quick_status",
                    ),
                ),
                "android_push_to_talk",
            ),
        )
        assertFalse(
            shouldInterruptImplementation(
                RelayUiState(
                    pendingActionId = "action_2",
                    pendingApprovalRequest = BridgeApprovalRequest(
                        actionType = "test",
                        summary = "Run workspace tests",
                        riskClass = "approval_required",
                        expiresInMs = 12000,
                    ),
                ),
                "headset_button_single",
            ),
        )
    }

    @Test
    fun `autonomy continue scheduling only activates for complete continue on silence instructions`() {
        assertTrue(
            shouldScheduleAutonomyContinue(
                BridgeAutonomyInstruction(
                    phase = "plan",
                    mode = "continue_on_silence",
                    summary = "Plan updated.",
                    nextStep = "Read current developer status.",
                    continueAfterMs = 4000,
                    nextIntent = "quick_status",
                ),
            ),
        )
        assertFalse(
            shouldScheduleAutonomyContinue(
                BridgeAutonomyInstruction(
                    phase = "plan",
                    mode = "awaiting_user_input",
                    summary = "Plan updated.",
                    nextStep = "Read current developer status.",
                    continueAfterMs = 4000,
                    nextIntent = "quick_status",
                ),
            ),
        )
        assertFalse(
            shouldScheduleAutonomyContinue(
                BridgeAutonomyInstruction(
                    phase = "plan",
                    mode = "continue_on_silence",
                    summary = "Plan updated.",
                    nextStep = "Read current developer status.",
                    continueAfterMs = 0,
                    nextIntent = "quick_status",
                ),
            ),
        )
        assertFalse(shouldScheduleAutonomyContinue(null))
    }
}
