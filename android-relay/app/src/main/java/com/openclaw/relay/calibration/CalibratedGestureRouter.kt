package com.openclaw.relay.calibration

import com.openclaw.relay.RelayStateStore
import com.openclaw.relay.signal.EarbudSignalEvent
import com.openclaw.relay.signal.GestureType

/**
 * Runtime gesture router that converts provider events into calibrated actions.
 *
 * Thin layer before existing relay gesture handling:
 * - Converts incoming event to [SignalFingerprint]
 * - Matches against active [EarbudCalibrationProfile]
 * - Dispatches mapped action if exactly one match exists
 * - Rejects ambiguous or unmapped signals
 */
class CalibratedGestureRouter(
    private val activeProfileProvider: () -> EarbudCalibrationProfile?,
) {

    /**
     * Routes a provider event through the active calibration profile.
     *
     * @return The mapped [GestureAction] if exactly one unambiguous mapping exists,
     *         or null if no profile is active, no mapping matches, or the signal is ambiguous.
     */
    fun route(event: EarbudSignalEvent): GestureAction? {
        val profile = activeProfileProvider() ?: return null
        if (!profile.isReadyForRuntime()) return null

        val fingerprint = event.toFingerprint() ?: return null

        // Find all calibrated gestures whose fingerprint matches the incoming event
        val matches = profile.calibratedGestures.filter { gesture ->
            gesture.confidence == CalibrationConfidence.PROVEN &&
                gesture.fingerprint?.matches(fingerprint) == true
        }

        return when (matches.size) {
            1 -> {
                val matchedGesture = matches.first()
                val action = profile.gestureActionMap.actionFor(
                    matchedGesture.requestedGesture,
                    fingerprint.budSide,
                )
                if (action != null) {
                    RelayStateStore.recordMatchedSignal(
                        providerId = fingerprint.providerId,
                        gestureType = matchedGesture.requestedGesture.name,
                        action = action.name,
                    )
                }
                action
            }
            else -> null // ambiguous or no match
        }
    }

    /**
     * Checks whether a specific event should be allowed to trigger an approval/reject action.
     *
     * Approval/reject must never be routed from an ambiguous signal.
     */
    fun canRouteApproval(event: EarbudSignalEvent): Boolean {
        return routeApprovalAction(event) != null
    }

    /**
     * Routes an approval event to its mapped action.
     *
     * @return [GestureAction.APPROVE] or [GestureAction.REJECT] if exactly one unambiguous
     *         approval mapping exists, or null if no profile is active, no mapping matches,
     *         or the signal is ambiguous.
     */
    fun routeApprovalAction(event: EarbudSignalEvent): GestureAction? {
        val profile = activeProfileProvider() ?: return null
        if (!profile.isReadyForRuntime()) return null
        val fingerprint = event.toFingerprint() ?: return null

        val approvalGestures = profile.calibratedGestures.filter { gesture ->
            val action = profile.gestureActionMap.actionFor(
                gesture.requestedGesture,
                gesture.fingerprint?.budSide,
            )
            action == GestureAction.APPROVE || action == GestureAction.REJECT
        }

        val matches = approvalGestures.filter { gesture ->
            gesture.confidence == CalibrationConfidence.PROVEN &&
                gesture.fingerprint?.matches(fingerprint) == true
        }

        // Only route if exactly one approval gesture matches
        return when (matches.size) {
            1 -> {
                val matchedGesture = matches.first()
                val action = profile.gestureActionMap.actionFor(
                    matchedGesture.requestedGesture,
                    fingerprint.budSide,
                )
                if (action != null) {
                    RelayStateStore.recordMatchedSignal(
                        providerId = fingerprint.providerId,
                        gestureType = matchedGesture.requestedGesture.name,
                        action = action.name,
                    )
                }
                action
            }
            else -> null
        }
    }

    private fun EarbudSignalEvent.toFingerprint(): SignalFingerprint? {
        return when (this) {
            is EarbudSignalEvent.WakeGesture -> SignalFingerprint(
                providerId = providerId,
                gestureType = gestureType,
                keyCode = keyCode,
                keyAction = keyAction,
                budSide = budSide,
                pressDurationMs = pressDurationMs,
                interTapIntervalMs = interTapIntervalMs,
            )
            is EarbudSignalEvent.InterruptGesture -> SignalFingerprint(
                providerId = providerId,
                gestureType = gestureType,
                keyCode = keyCode,
                keyAction = keyAction,
                budSide = budSide,
                pressDurationMs = pressDurationMs,
                interTapIntervalMs = interTapIntervalMs,
            )
            is EarbudSignalEvent.ApprovalGesture -> SignalFingerprint(
                providerId = providerId,
                gestureType = gestureType,
                keyCode = keyCode,
                keyAction = keyAction,
                pressDurationMs = pressDurationMs,
                interTapIntervalMs = interTapIntervalMs,
            )
            else -> null
        }
    }

    private fun SignalFingerprint.matches(other: SignalFingerprint): Boolean {
        if (providerId != other.providerId) return false

        // If both have keyCodes (MediaSession path), match on raw signal
        if (keyCode != null && other.keyCode != null) {
            return keyCode == other.keyCode &&
                (keyAction == null || other.keyAction == null || keyAction == other.keyAction) &&
                (budSide == null || other.budSide == null || budSide == other.budSide)
        }

        // Fallback for non-MediaSession providers
        return gestureType == other.gestureType &&
            (budSide == null || other.budSide == null || budSide == other.budSide)
    }
}
