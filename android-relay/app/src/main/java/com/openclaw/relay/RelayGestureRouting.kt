package com.openclaw.relay

private val listeningWindowTriggers = setOf(
    "headset_button_single",
    "triple_tap_right",
    "android_push_to_talk",
)

internal fun shouldOpenListeningWindow(trigger: String): Boolean {
    return trigger in listeningWindowTriggers
}

internal fun shouldScheduleAutonomyContinue(autonomy: BridgeAutonomyInstruction?): Boolean {
    return autonomy?.mode == "continue_on_silence"
        && autonomy.continueAfterMs != null
        && !autonomy.nextIntent.isNullOrBlank()
}

internal fun shouldInterruptImplementation(state: RelayUiState, trigger: String): Boolean {
    if (!shouldOpenListeningWindow(trigger)) {
        return false
    }

    val hasBackgroundImplementation = state.pendingActionId != null && state.pendingApprovalSummary == null
    return hasBackgroundImplementation || state.activeAutonomy != null
}