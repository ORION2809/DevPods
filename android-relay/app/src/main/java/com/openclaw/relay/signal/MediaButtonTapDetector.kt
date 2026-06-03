package com.openclaw.relay.signal

import android.os.Handler
import android.os.Looper

/**
 * Timing metadata for a detected gesture.
 *
 * - [pressDurationMs]: Time from button down to button up for the last tap in the sequence.
 *   For long press, this reflects how long the button was held.
 * - [interTapIntervalMs]: Time between the last two button-down events.
 *   Relevant for double/triple press gestures.
 */
data class GestureTiming(
    val pressDurationMs: Long? = null,
    val interTapIntervalMs: Long? = null,
)

/**
 * Detects single, double, triple, and long press gestures from media button events.
 *
 * Timing:
 * - Multi-tap window: 400ms. If no second/third tap arrives within 400ms of the
 *   previous tap, the pending gesture is emitted.
 * - Long-press threshold: 700ms. If the button is held longer than 700ms,
 *   a [GestureType.LONG_PRESS] is emitted immediately.
 */
class MediaButtonTapDetector(
    var longPressThresholdMs: Long = 700L,
    var multiTapWindowMs: Long = 400L,
    private val onGesture: (GestureType, GestureTiming) -> Unit,
    private val onCandidate: ((GestureType) -> Unit)? = null,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private var tapCount = 0
    private var longPressFired = false
    private var lastDownAtMs = 0L
    private var pressDurationMs = 0L
    private var interTapIntervalMs = 0L

    private val timeoutRunnable = Runnable {
        if (!longPressFired) {
            val gesture = when {
                tapCount >= 3 -> GestureType.TRIPLE_PRESS
                tapCount == 2 -> GestureType.DOUBLE_PRESS
                else -> GestureType.SINGLE_PRESS
            }
            tapCount = 0
            onGesture(gesture, GestureTiming(pressDurationMs, interTapIntervalMs))
        }
    }

    private val longPressRunnable = Runnable {
        longPressFired = true
        tapCount = 0
        handler.removeCallbacks(timeoutRunnable)
        onGesture(GestureType.LONG_PRESS, GestureTiming(longPressThresholdMs, null))
    }

    /** Call on every ACTION_DOWN (repeatCount == 0). */
    fun onButtonDown() {
        val now = System.currentTimeMillis()
        if (tapCount > 0) {
            interTapIntervalMs = now - lastDownAtMs
        }
        lastDownAtMs = now
        longPressFired = false
        tapCount++
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(longPressRunnable)
        handler.postDelayed(longPressRunnable, longPressThresholdMs)
        // Emit speculative candidate on first down to allow route preparation
        if (tapCount == 1) {
            onCandidate?.invoke(GestureType.SINGLE_PRESS)
        }
    }

    /** Call on every ACTION_UP. */
    fun onButtonUp() {
        handler.removeCallbacks(longPressRunnable)
        if (!longPressFired) {
            pressDurationMs = System.currentTimeMillis() - lastDownAtMs
            handler.removeCallbacks(timeoutRunnable)
            handler.postDelayed(timeoutRunnable, multiTapWindowMs)
        } else {
            longPressFired = false
        }
    }
}
