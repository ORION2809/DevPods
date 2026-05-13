package com.openclaw.relay.signal

import android.os.Handler
import android.os.Looper

/**
 * Detects single, double, triple, and long press gestures from media button events.
 *
 * Timing:
 * - Multi-tap window: 400ms. If no second/third tap arrives within 400ms of the
 *   previous tap, the pending gesture is emitted.
 * - Long-press threshold: 700ms. If the button is held longer than 700ms,
 *   a [GestureType.LONG_PRESS] is emitted immediately.
 */
class MediaButtonTapDetector(private val onGesture: (GestureType) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var tapCount = 0
    private var longPressFired = false

    private val timeoutRunnable = Runnable {
        if (!longPressFired) {
            val gesture = when {
                tapCount >= 3 -> GestureType.TRIPLE_PRESS
                tapCount == 2 -> GestureType.DOUBLE_PRESS
                else -> GestureType.SINGLE_PRESS
            }
            tapCount = 0
            onGesture(gesture)
        }
    }

    private val longPressRunnable = Runnable {
        longPressFired = true
        tapCount = 0
        handler.removeCallbacks(timeoutRunnable)
        onGesture(GestureType.LONG_PRESS)
    }

    /** Call on every ACTION_DOWN (repeatCount == 0). */
    fun onButtonDown() {
        longPressFired = false
        tapCount++
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(longPressRunnable)
        handler.postDelayed(longPressRunnable, 700L)
    }

    /** Call on every ACTION_UP. */
    fun onButtonUp() {
        handler.removeCallbacks(longPressRunnable)
        if (!longPressFired) {
            handler.removeCallbacks(timeoutRunnable)
            handler.postDelayed(timeoutRunnable, 400L)
        } else {
            longPressFired = false
        }
    }
}
