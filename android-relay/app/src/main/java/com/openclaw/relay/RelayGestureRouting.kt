package com.openclaw.relay

import com.openclaw.relay.signal.GestureType

private val listeningWindowTriggers = setOf(
    "headset_button_single",
    "android_push_to_talk",
    "android_autonomy_interrupt",
)

private val interruptTriggers = setOf(
    "headset_button_single",
    "android_push_to_talk",
    "android_autonomy_interrupt",
    "left_long_press",
)

/**
 * Maps a raw provider gesture type to a schema-valid bridge event name.
 *
 * This is the single source of truth for how physical earbud gestures become
 * bridge events. All provider paths (MediaSession, LibrePods, generic Bluetooth)
 * must normalize through here before creating a [RelayWakeSignal].
 */
internal fun normalizeProviderEventToBridgeTrigger(
    gestureType: GestureType,
    isInterrupt: Boolean = false,
    isApproval: Boolean = false,
): String = when {
    isApproval -> "android_approve"
    isInterrupt -> "android_autonomy_interrupt"
    gestureType == GestureType.SINGLE_PRESS || gestureType == GestureType.LONG_PRESS -> "headset_button_single"
    else -> "headset_button_single"
}

internal fun shouldOpenListeningWindow(trigger: String): Boolean {
    return trigger in listeningWindowTriggers
}

internal fun shouldOpenListeningWindow(wakeSignal: RelayWakeSignal): Boolean {
    return shouldOpenListeningWindow(wakeSignal.trigger)
}

internal fun shouldScheduleAutonomyContinue(autonomy: BridgeAutonomyInstruction?): Boolean {
    return autonomy?.mode == "continue_on_silence"
        && autonomy.continueAfterMs != null
    && autonomy.continueAfterMs > 0
        && !autonomy.nextIntent.isNullOrBlank()
}

internal fun shouldInterruptImplementation(state: RelayUiState, trigger: String): Boolean {
    if (trigger !in interruptTriggers) {
        return false
    }

    val hasBackgroundImplementation = state.pendingActionId != null && state.pendingApprovalSummary == null
    return hasBackgroundImplementation || state.activeAutonomy != null
}

internal fun shouldInterruptImplementation(state: RelayUiState, wakeSignal: RelayWakeSignal): Boolean {
    return shouldInterruptImplementation(state, wakeSignal.trigger)
}
