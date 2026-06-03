package com.openclaw.relay.calibration

import com.openclaw.relay.signal.EarbudSignalEvent
import com.openclaw.relay.signal.GestureType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val TAG = "EarbudCalibration"

/**
 * Calibration engine that learns how a user's earbuds produce signals.
 *
 * Opens a short listening window for one requested gesture, collects events
 * from all active providers, normalizes them into [SignalFingerprint]s,
 * and scores repeatability, collisions, and confidence.
 *
 * This engine listens to provider output only — it does not scan Bluetooth,
 * own MediaSession, or duplicate vendor protocol code.
 */
class EarbudCalibrationEngine(
    private val allProviderEvents: Flow<EarbudSignalEvent>,
    private val clock: () -> Long = System::currentTimeMillis,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private var scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var activeJob: kotlinx.coroutines.Job? = null

    private val _sessionState = MutableStateFlow<CalibrationSessionState?>(null)
    val sessionState: StateFlow<CalibrationSessionState?> = _sessionState.asStateFlow()

    private var calibrationStartMs: Long = 0L

    /** Start calibration for a single gesture. Call [buildResult] to collect the outcome. */
    fun startGestureCalibration(
        sessionId: String,
        requestedGesture: GestureType,
        maxAttempts: Int = 3,
        timeoutPerAttemptMs: Long = 8_000L,
    ) {
        cancelCalibration()
        calibrationStartMs = clock()

        activeJob = scope.launch {
            val observations = mutableListOf<SignalFingerprint>()
            var attempt = 1
            var status = CalibrationSessionStatus.WAITING

            while (attempt <= maxAttempts && status != CalibrationSessionStatus.COMPLETE) {
                _sessionState.value = CalibrationSessionState(
                    sessionId = sessionId,
                    requestedGesture = requestedGesture,
                    status = CalibrationSessionStatus.WAITING,
                    attemptNumber = attempt,
                )

                val attemptObservation = runAttempt(requestedGesture, timeoutPerAttemptMs)

                if (attemptObservation != null) {
                    observations.add(attemptObservation)
                    _sessionState.value = CalibrationSessionState(
                        sessionId = sessionId,
                        requestedGesture = requestedGesture,
                        status = CalibrationSessionStatus.DETECTED,
                        observations = observations.toList(),
                        attemptNumber = attempt,
                    )

                    // Require 2 successful observations out of maxAttempts for PROVEN
                    if (observations.size >= 2) {
                        status = CalibrationSessionStatus.COMPLETE
                    } else if (attempt < maxAttempts) {
                        status = CalibrationSessionStatus.RETRY
                        delay(1_000L) // brief pause before next attempt prompt
                    } else {
                        status = CalibrationSessionStatus.COMPLETE
                    }
                } else {
                    // Timeout on this attempt
                    if (attempt >= maxAttempts) {
                        status = CalibrationSessionStatus.TIMEOUT
                    } else {
                        status = CalibrationSessionStatus.RETRY
                        delay(1_000L)
                    }
                }
                attempt++
            }

            // Emit final terminal state
            val finalStatus = when {
                status == CalibrationSessionStatus.COMPLETE && observations.isEmpty() -> CalibrationSessionStatus.TIMEOUT
                status == CalibrationSessionStatus.COMPLETE -> CalibrationSessionStatus.COMPLETE
                else -> status
            }
            _sessionState.value = CalibrationSessionState(
                sessionId = sessionId,
                requestedGesture = requestedGesture,
                status = finalStatus,
                observations = observations.toList(),
                attemptNumber = attempt - 1,
            )
        }
    }

    fun cancelCalibration() {
        activeJob?.cancel()
        activeJob = null
    }

    /**
     * Build a [CalibratedGesture] from the current session state.
     *
     * Call this after the session reaches a terminal status (COMPLETE, TIMEOUT, etc.).
     */
    fun buildResult(): CalibratedGesture? {
        val state = _sessionState.value ?: return null
        val observations = state.observations

        if (observations.isEmpty()) {
            return CalibratedGesture(
                requestedGesture = state.requestedGesture,
                confidence = CalibrationConfidence.UNSUPPORTED,
            )
        }

        // Pick the most common fingerprint
        val dominant = observations
            .groupBy { it.providerId to it.gestureType }
            .maxByOrNull { it.value.size }
            ?.value
            ?.firstOrNull()

        val confidence = when {
            observations.size >= 2 && dominant != null -> CalibrationConfidence.PROVEN
            observations.size == 1 && dominant != null -> CalibrationConfidence.OBSERVED
            else -> CalibrationConfidence.UNSUPPORTED
        }

        return CalibratedGesture(
            requestedGesture = state.requestedGesture,
            fingerprint = dominant,
            budSide = dominant?.budSide,
            detectionLatencyMs = dominant?.detectionLatencyMs,
            pressDurationMs = dominant?.pressDurationMs,
            interTapIntervalMs = dominant?.interTapIntervalMs,
            repeatCount = observations.size,
            failureCount = state.attemptNumber - observations.size,
            confidence = confidence,
            lastObservedAtMs = clock(),
        )
    }

    /**
     * Detect collisions between two calibrated gestures.
     *
     * Returns the colliding gesture type if their fingerprints are too similar,
     * or null if they are distinct.
     */
    fun detectCollision(
        gesture: CalibratedGesture,
        others: List<CalibratedGesture>,
    ): GestureType? {
        val fingerprint = gesture.fingerprint ?: return null
        return others.find { other ->
            other.requestedGesture != gesture.requestedGesture &&
                other.confidence != CalibrationConfidence.UNSUPPORTED &&
                fingerprintsCollide(fingerprint, other.fingerprint)
        }?.requestedGesture
    }

    private suspend fun runAttempt(
        requestedGesture: GestureType,
        timeoutMs: Long,
    ): SignalFingerprint? {
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            var result: EarbudSignalEvent? = null
            allProviderEvents
                .filter { isRelevantEvent(it, requestedGesture) }
                .take(1)
                .collect { event ->
                    result = event
                }
            normalizeEvent(result)
        }
    }

    private fun isRelevantEvent(event: EarbudSignalEvent, requestedGesture: GestureType): Boolean {
        return when (event) {
            is EarbudSignalEvent.WakeGesture -> event.gestureType == requestedGesture
            is EarbudSignalEvent.InterruptGesture -> event.gestureType == requestedGesture
            is EarbudSignalEvent.ApprovalGesture -> {
                // Map approval/reject to appropriate gesture types
                when (requestedGesture) {
                    GestureType.DOUBLE_PRESS -> true
                    GestureType.TRIPLE_PRESS -> true
                    else -> false
                }
            }
            else -> false
        }
    }

    private fun normalizeEvent(event: EarbudSignalEvent?): SignalFingerprint? {
        val latencyMs = event?.timestamp?.let { it - calibrationStartMs }
        return when (event) {
            is EarbudSignalEvent.WakeGesture -> SignalFingerprint(
                providerId = event.providerId,
                gestureType = event.gestureType,
                keyCode = event.keyCode,
                keyAction = event.keyAction,
                budSide = event.budSide,
                detectionLatencyMs = latencyMs,
                pressDurationMs = event.pressDurationMs,
                interTapIntervalMs = event.interTapIntervalMs,
            )
            is EarbudSignalEvent.InterruptGesture -> SignalFingerprint(
                providerId = event.providerId,
                gestureType = event.gestureType,
                keyCode = event.keyCode,
                keyAction = event.keyAction,
                budSide = event.budSide,
                detectionLatencyMs = latencyMs,
                pressDurationMs = event.pressDurationMs,
                interTapIntervalMs = event.interTapIntervalMs,
            )
            is EarbudSignalEvent.ApprovalGesture -> SignalFingerprint(
                providerId = event.providerId,
                gestureType = event.gestureType,
                keyCode = event.keyCode,
                keyAction = event.keyAction,
                detectionLatencyMs = latencyMs,
                pressDurationMs = event.pressDurationMs,
                interTapIntervalMs = event.interTapIntervalMs,
            )
            else -> null
        }
    }

    private fun fingerprintsCollide(a: SignalFingerprint?, b: SignalFingerprint?): Boolean {
        if (a == null || b == null) return false
        if (a.providerId != b.providerId) return false

        // If both have keyCodes (MediaSession path), compare underlying raw signal.
        // Same keyCode across different requested gesture types means collision.
        if (a.keyCode != null && b.keyCode != null) {
            return a.keyCode == b.keyCode &&
                (a.keyAction == null || b.keyAction == null || a.keyAction == b.keyAction)
        }

        // Fallback for non-MediaSession providers: compare gesture type
        return a.gestureType == b.gestureType
    }
}
