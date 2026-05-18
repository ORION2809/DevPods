package com.openclaw.relay

enum class MediaButtonAction {
    DOWN,
    UP,
    UNKNOWN,
}

data class MediaButtonEventTelemetry(
    val keyCode: Int,
    val keyLabel: String,
    val action: MediaButtonAction,
    val repeatCount: Int,
    val mapping: String,
    val accepted: Boolean,
    val debounced: Boolean = false,
    val receivedAtMs: Long,
    val routeState: AudioRouteProofState = AudioRouteProofState.ROUTE_UNKNOWN,
    val serviceRunning: Boolean = false,
)

class MediaButtonEventDebouncer(
    private val minDuplicateIntervalMs: Long = 120L,
) {
    private var lastKeyCode: Int? = null
    private var lastAction: MediaButtonAction? = null
    private var lastAcceptedAtMs: Long? = null

    fun accepts(keyCode: Int, action: MediaButtonAction, receivedAtMs: Long): Boolean {
        val duplicate = lastKeyCode == keyCode &&
            lastAction == action &&
            lastAcceptedAtMs?.let { previous -> receivedAtMs - previous < minDuplicateIntervalMs } == true
        if (duplicate) {
            return false
        }

        lastKeyCode = keyCode
        lastAction = action
        lastAcceptedAtMs = receivedAtMs
        return true
    }
}

data class ForegroundControlSnapshot(
    val hasPushToTalk: Boolean = false,
    val hasRetryQueue: Boolean = false,
    val hasCancelCurrentAction: Boolean = false,
    val hasStopRelay: Boolean = false,
) {
    val actionCount: Int
        get() = listOf(hasPushToTalk, hasRetryQueue, hasCancelCurrentAction, hasStopRelay).count { it }

    val missingRequiredActions: List<String>
        get() = buildList {
            if (!hasPushToTalk) add("push_to_talk")
            if (!hasRetryQueue) add("retry_queue")
            if (!hasCancelCurrentAction) add("cancel_current_action")
            if (!hasStopRelay) add("stop_relay")
        }

    companion object {
        fun requiredRelayControls(): ForegroundControlSnapshot =
            ForegroundControlSnapshot(
                hasPushToTalk = true,
                hasRetryQueue = true,
                hasCancelCurrentAction = true,
                hasStopRelay = true,
            )
    }
}

data class RelayForegroundServiceSnapshot(
    val isForegroundActive: Boolean = false,
    val foregroundServiceTypeMask: Int = 0,
    val mediaSessionReady: Boolean = false,
    val notificationControls: ForegroundControlSnapshot = ForegroundControlSnapshot(),
    val lastStartAction: String? = null,
    val updatedAtMs: Long = 0L,
    val restoredAfterRestart: Boolean = false,
    val recoveryReason: String? = null,
) {
    val missingControls: List<String>
        get() = notificationControls.missingRequiredActions

    val isComplete: Boolean
        get() = isForegroundActive && mediaSessionReady && missingControls.isEmpty()
}

data class RelayServiceRecoveryPlan(
    val shouldRestartForeground: Boolean,
    val restoreListening: Boolean,
    val restoreSpeaking: Boolean,
    val restoreAwaitingBridge: Boolean,
    val clearPendingTransientAction: Boolean,
    val clearTransientAutonomy: Boolean,
    val reason: String,
)

object RelayServiceRecoveryPolicy {
    fun plan(previous: RelayUiState): RelayServiceRecoveryPlan {
        val hadTransientVoiceState = previous.isListening ||
            previous.isSpeaking ||
            previous.isAwaitingBridgeResponse ||
            previous.activeAutonomy != null

        return RelayServiceRecoveryPlan(
            shouldRestartForeground = previous.isServiceRunning,
            restoreListening = false,
            restoreSpeaking = false,
            restoreAwaitingBridge = false,
            clearPendingTransientAction = hadTransientVoiceState && previous.pendingApprovalRequest == null,
            clearTransientAutonomy = previous.activeAutonomy != null,
            reason = if (hadTransientVoiceState) {
                "safe_idle_after_process_restart"
            } else {
                "restore_foreground_only"
            },
        )
    }
}

object MediaButtonDiagnostics {
    const val KEYCODE_HEADSETHOOK = 79
    const val KEYCODE_MEDIA_PLAY_PAUSE = 85
    const val KEYCODE_MEDIA_STOP = 86
    const val KEYCODE_MEDIA_NEXT = 87
    const val KEYCODE_MEDIA_PREVIOUS = 88
    const val KEYCODE_MEDIA_PLAY = 126

    fun normalize(
        keyCode: Int,
        action: MediaButtonAction,
        repeatCount: Int,
        receivedAtMs: Long,
        routeState: AudioRouteProofState = AudioRouteProofState.ROUTE_UNKNOWN,
        serviceRunning: Boolean = false,
        acceptedOverride: Boolean? = null,
        debounced: Boolean = false,
    ): MediaButtonEventTelemetry {
        val supported = keyCode in setOf(
            KEYCODE_HEADSETHOOK,
            KEYCODE_MEDIA_PLAY_PAUSE,
            KEYCODE_MEDIA_PLAY,
            KEYCODE_MEDIA_NEXT,
            KEYCODE_MEDIA_PREVIOUS,
            KEYCODE_MEDIA_STOP,
        )
        val accepted = acceptedOverride ?: (supported && repeatCount == 0)
        return MediaButtonEventTelemetry(
            keyCode = keyCode,
            keyLabel = keyLabel(keyCode),
            action = action,
            repeatCount = repeatCount,
            mapping = mappingFor(keyCode),
            accepted = accepted,
            debounced = debounced,
            receivedAtMs = receivedAtMs,
            routeState = routeState,
            serviceRunning = serviceRunning,
        )
    }

    fun keyLabel(keyCode: Int): String = when (keyCode) {
        KEYCODE_HEADSETHOOK -> "HEADSETHOOK"
        KEYCODE_MEDIA_PLAY_PAUSE -> "MEDIA_PLAY_PAUSE"
        KEYCODE_MEDIA_PLAY -> "MEDIA_PLAY"
        KEYCODE_MEDIA_STOP -> "MEDIA_STOP"
        KEYCODE_MEDIA_NEXT -> "MEDIA_NEXT_TRACK"
        KEYCODE_MEDIA_PREVIOUS -> "MEDIA_PREVIOUS_TRACK"
        else -> "KEYCODE_$keyCode"
    }

    private fun mappingFor(keyCode: Int): String = when (keyCode) {
        KEYCODE_MEDIA_NEXT -> "interrupt_double_press"
        KEYCODE_MEDIA_PREVIOUS -> "approval_triple_press"
        KEYCODE_MEDIA_STOP -> "stop_relay"
        KEYCODE_HEADSETHOOK,
        KEYCODE_MEDIA_PLAY_PAUSE,
        KEYCODE_MEDIA_PLAY,
        -> "defer_to_tap_detector"
        else -> "unsupported"
    }
}
