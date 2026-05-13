package com.openclaw.relay.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * Returns the remaining milliseconds until [deadlineAtMs], updating every second.
 * If [deadlineAtMs] is null, returns null.
 */
@Composable
fun liveCountdownRemaining(deadlineAtMs: Long?): Long? {
    if (deadlineAtMs == null) return null
    var remaining by remember(deadlineAtMs) { mutableLongStateOf(deadlineAtMs - System.currentTimeMillis()) }

    LaunchedEffect(deadlineAtMs) {
        while (remaining > 0) {
            delay(1000)
            remaining = deadlineAtMs - System.currentTimeMillis()
        }
        remaining = 0
    }

    return remaining.coerceAtLeast(0)
}
