package com.openclaw.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayGestureRoutingTest {
    @Test
    fun `wake triggers open the listening window`() {
        assertTrue(shouldOpenListeningWindow("headset_button_single"))
        assertTrue(shouldOpenListeningWindow("triple_tap_right"))
        assertTrue(shouldOpenListeningWindow("android_push_to_talk"))
    }

    @Test
    fun `status and approval triggers stay bridge only`() {
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
                    pendingApprovalSummary = null,
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
                    pendingApprovalSummary = "Run workspace tests",
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
        assertFalse(shouldScheduleAutonomyContinue(null))
    }
}