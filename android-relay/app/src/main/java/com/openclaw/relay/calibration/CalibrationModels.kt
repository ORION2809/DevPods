package com.openclaw.relay.calibration

import com.openclaw.relay.signal.BudSide
import com.openclaw.relay.signal.GestureType
import kotlinx.serialization.Serializable

/**
 * Confidence levels for a calibrated gesture.
 *
 * - PROVEN: repeatable, unambiguous, route-proof passed
 * - OBSERVED: detected but not yet repeatable or route not proven
 * - AMBIGUOUS: collides with another gesture's signal fingerprint
 * - UNSUPPORTED: never detected during calibration
 */
@Serializable
enum class CalibrationConfidence {
    PROVEN,
    OBSERVED,
    AMBIGUOUS,
    UNSUPPORTED,
}

/**
 * User-assignable actions for a calibrated gesture.
 */
@Serializable
enum class GestureAction {
    WAKE_AND_LISTEN,
    INTERRUPT,
    APPROVE,
    REJECT,
    NONE,
}

/**
 * Normalized signal fingerprint derived from a raw provider event.
 *
 * This stores only redacted metadata — never raw audio or MAC addresses.
 */
@Serializable
data class SignalFingerprint(
    val providerId: String,
    val gestureType: GestureType,
    val keyCode: String? = null,
    val keyAction: String? = null,
    val budSide: BudSide? = null,
    val detectionLatencyMs: Long? = null,
    val pressDurationMs: Long? = null,
    val interTapIntervalMs: Long? = null,
)

/**
 * Result of calibrating a single requested gesture.
 */
@Serializable
data class CalibratedGesture(
    val requestedGesture: GestureType,
    val fingerprint: SignalFingerprint? = null,
    val budSide: BudSide? = null,
    val detectionLatencyMs: Long? = null,
    val pressDurationMs: Long? = null,
    val interTapIntervalMs: Long? = null,
    val repeatCount: Int = 0,
    val failureCount: Int = 0,
    val confidence: CalibrationConfidence = CalibrationConfidence.UNSUPPORTED,
    val collisionWith: GestureType? = null,
    val lastObservedAtMs: Long? = null,
)

/**
 * User-configured mapping from proven gestures to product actions.
 */
@Serializable
data class GestureActionMap(
    val mappings: Map<GestureType, GestureAction> = emptyMap(),
    val sideMappings: Map<String, GestureAction> = emptyMap(),
) {
    fun actionFor(gesture: GestureType, budSide: BudSide? = null): GestureAction? {
        if (budSide != null) {
            sideMappings["${gesture.name}_${budSide.name}"]?.let { return it }
        }
        return mappings[gesture]
    }

    fun assignedGestures(): Set<GestureType> = mappings.keys

    fun isAssigned(gesture: GestureType): Boolean = gesture in mappings

    fun isSideAssigned(gesture: GestureType, budSide: BudSide): Boolean =
        "${gesture.name}_${budSide.name}" in sideMappings
}

/**
 * Persistent calibration profile for a specific earbud + phone combination.
 */
@Serializable
data class EarbudCalibrationProfile(
    val profileId: String,
    val deviceModel: String,
    val deviceAddressHash: String,
    val phoneModel: String,
    val androidVersion: String,
    val providerId: String,
    val calibratedGestures: List<CalibratedGesture> = emptyList(),
    val gestureActionMap: GestureActionMap = GestureActionMap(),
    val routeProof: RouteProofResult? = null,
    val isModelScoped: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val longPressThresholdMs: Long = 700L,
    val multiTapWindowMs: Long = 400L,
    val appVersion: String? = null,
    val providerIdAtCreation: String? = null,
    val runtimeMissCountAtCreation: Int = 0,
) {
    val confidence: CalibrationConfidence
        get() = when {
            calibratedGestures.any { it.confidence == CalibrationConfidence.PROVEN } -> CalibrationConfidence.PROVEN
            calibratedGestures.any { it.confidence == CalibrationConfidence.OBSERVED } -> CalibrationConfidence.OBSERVED
            calibratedGestures.any { it.confidence == CalibrationConfidence.AMBIGUOUS } -> CalibrationConfidence.AMBIGUOUS
            else -> CalibrationConfidence.UNSUPPORTED
        }

    fun findGesture(gestureType: GestureType): CalibratedGesture? {
        return calibratedGestures.find { it.requestedGesture == gestureType }
    }

    fun provenGestures(): List<CalibratedGesture> {
        return calibratedGestures.filter { it.confidence == CalibrationConfidence.PROVEN }
    }

    /** Gestures that are repeatable but may not have route proof yet. */
    fun repeatableGestures(): List<CalibratedGesture> {
        return calibratedGestures.filter { it.confidence == CalibrationConfidence.PROVEN }
    }

    /** Fully proven gestures: repeatable AND route proof passed. */
    fun fullyProvenGestures(): List<CalibratedGesture> {
        if (routeProof?.isSuccess != true) return emptyList()
        return provenGestures()
    }

    fun hasProvenWakeGesture(): Boolean {
        val wakeActions = setOf(GestureAction.WAKE_AND_LISTEN, GestureAction.INTERRUPT)
        return provenGestures().any { gestureActionMap.actionFor(it.requestedGesture) in wakeActions }
    }

    fun hasProvenApprovalGesture(): Boolean {
        val approvalActions = setOf(GestureAction.APPROVE, GestureAction.REJECT)
        return provenGestures().any { gestureActionMap.actionFor(it.requestedGesture) in approvalActions }
    }

    fun isReadyForRuntime(): Boolean {
        return confidence == CalibrationConfidence.PROVEN &&
            routeProof?.isSuccess == true &&
            hasProvenWakeGesture()
    }

    fun isInvalidated(currentAppVersion: String, currentAndroidVersion: String, currentProviderId: String): Boolean {
        return appVersion != null && appVersion != currentAppVersion ||
            androidVersion != currentAndroidVersion ||
            providerIdAtCreation != null && providerIdAtCreation != currentProviderId
    }
}

/**
 * Result of the speech route proof that runs after gesture calibration.
 */
@Serializable
data class RouteProofResult(
    val sttSuccess: Boolean = false,
    val ttsSuccess: Boolean = false,
    val bargeInSuccess: Boolean = false,
    val isSuccess: Boolean = false,
    val testedAtMs: Long = System.currentTimeMillis(),
    val failureReasons: List<String> = emptyList(),
)

/**
 * In-memory state of an active calibration session.
 */
data class CalibrationSessionState(
    val sessionId: String,
    val requestedGesture: GestureType,
    val status: CalibrationSessionStatus = CalibrationSessionStatus.WAITING,
    val observations: List<SignalFingerprint> = emptyList(),
    val attemptNumber: Int = 1,
    val startedAtMs: Long = System.currentTimeMillis(),
)

enum class CalibrationSessionStatus {
    WAITING,
    DETECTED,
    RETRY,
    TIMEOUT,
    UNSUPPORTED,
    AMBIGUOUS,
    COMPLETE,
}
