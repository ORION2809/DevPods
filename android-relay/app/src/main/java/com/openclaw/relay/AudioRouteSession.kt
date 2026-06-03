package com.openclaw.relay

/**
 * Explicit route session capturing request, settle, snapshot, and release.
 *
 * This makes BluetoothAudioRouter testable without touching the Android AudioManager
 * directly, and gives every listening session a clear route lifecycle record.
 */
data class AudioRouteSession(
    val sessionId: String,
    val requestedAtMs: Long,
    val settledAtMs: Long? = null,
    val releasedAtMs: Long? = null,
    val snapshot: RelayAudioRouteSnapshot = RelayAudioRouteSnapshot(),
    val settleAttempts: Int = 0,
) {
    val isSettled: Boolean
        get() = settledAtMs != null

    val isReleased: Boolean
        get() = releasedAtMs != null

    val settleDurationMs: Long?
        get() = settledAtMs?.let { settled ->
            (settled - requestedAtMs).coerceAtLeast(0L)
        }

    val activeDurationMs: Long?
        get() = if (settledAtMs != null && releasedAtMs != null) {
            (releasedAtMs - settledAtMs).coerceAtLeast(0L)
        } else {
            null
        }

    fun markSettled(settledAtMs: Long, snapshot: RelayAudioRouteSnapshot): AudioRouteSession {
        return copy(
            settledAtMs = settledAtMs,
            snapshot = snapshot,
            settleAttempts = settleAttempts + 1,
        )
    }

    fun markReleased(releasedAtMs: Long): AudioRouteSession {
        return copy(releasedAtMs = releasedAtMs)
    }

    fun markSettleAttempt(): AudioRouteSession {
        return copy(settleAttempts = settleAttempts + 1)
    }
}

/**
 * Creates a new route session for a listening request.
 */
internal fun beginAudioRouteSession(
    sessionId: String,
    clock: () -> Long = System::currentTimeMillis,
): AudioRouteSession = AudioRouteSession(
    sessionId = sessionId,
    requestedAtMs = clock(),
)
