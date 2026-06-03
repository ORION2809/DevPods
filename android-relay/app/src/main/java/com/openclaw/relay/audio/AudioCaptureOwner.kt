package com.openclaw.relay.audio

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates microphone access among multiple capture consumers.
 *
 * Ensures that only one consumer owns the microphone at any time,
 * preventing conflicts between platform STT, Sherpa VAD, Sherpa STT,
 * and audio route probes.
 */
class AudioCaptureOwner {
    private val mutex = Mutex()
    private var currentLease: CaptureLeaseImpl? = null

    /**
     * Attempts to acquire the microphone for the given owner.
     *
     * @return A [CaptureLease] if acquired, or null if the mic is already owned.
     */
    suspend fun acquire(owner: CaptureOwner, sessionId: String): CaptureLease? {
        return mutex.withLock {
            if (currentLease != null && !currentLease!!.isReleased) {
                return@withLock null
            }
            val lease = CaptureLeaseImpl(owner, sessionId) { releaseLease(it) }
            currentLease = lease
            lease
        }
    }

    /**
     * Returns the current owner, or null if the mic is free.
     */
    fun currentOwner(): CaptureOwner? {
        val lease = currentLease
        return if (lease != null && !lease.isReleased) lease.owner else null
    }

    /**
     * Returns a typed failure reason when acquisition fails.
     */
    fun currentOwnerName(): String? {
        return currentOwner()?.displayName
    }

    private fun releaseLease(lease: CaptureLeaseImpl) {
        if (currentLease === lease) {
            currentLease = null
        }
    }
}

enum class CaptureOwner(val displayName: String) {
    PLATFORM_SPEECH_RECOGNIZER("Platform STT"),
    AUDIO_RECORD_ROUTE_PROBE("Route Probe"),
    SHERPA_VAD("Sherpa VAD"),
    SHERPA_STT("Sherpa STT"),
}

interface CaptureLease {
    val owner: CaptureOwner
    val sessionId: String
    fun release()
    val isReleased: Boolean
}

private class CaptureLeaseImpl(
    override val owner: CaptureOwner,
    override val sessionId: String,
    private val onRelease: (CaptureLeaseImpl) -> Unit,
) : CaptureLease {
    override var isReleased: Boolean = false
        private set

    override fun release() {
        if (!isReleased) {
            isReleased = true
            onRelease(this)
        }
    }
}
