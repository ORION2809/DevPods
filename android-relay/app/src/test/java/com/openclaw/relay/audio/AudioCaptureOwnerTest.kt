package com.openclaw.relay.audio

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCaptureOwnerTest {

    @Test
    fun `acquire returns lease when mic is free`() = runTest {
        val owner = AudioCaptureOwner()
        val lease = owner.acquire(CaptureOwner.PLATFORM_SPEECH_RECOGNIZER, "session-1")

        assertNotNull(lease)
        assertEquals(CaptureOwner.PLATFORM_SPEECH_RECOGNIZER, lease!!.owner)
        assertEquals("session-1", lease.sessionId)
        assertFalse(lease.isReleased)
    }

    @Test
    fun `acquire returns null when mic is already owned`() = runTest {
        val owner = AudioCaptureOwner()
        val lease1 = owner.acquire(CaptureOwner.PLATFORM_SPEECH_RECOGNIZER, "session-1")
        assertNotNull(lease1)

        val lease2 = owner.acquire(CaptureOwner.SHERPA_STT, "session-2")
        assertNull(lease2)
    }

    @Test
    fun `release allows next owner to acquire`() = runTest {
        val owner = AudioCaptureOwner()
        val lease1 = owner.acquire(CaptureOwner.PLATFORM_SPEECH_RECOGNIZER, "session-1")
        assertNotNull(lease1)

        lease1!!.release()
        assertTrue(lease1.isReleased)

        val lease2 = owner.acquire(CaptureOwner.SHERPA_STT, "session-2")
        assertNotNull(lease2)
        assertEquals(CaptureOwner.SHERPA_STT, lease2!!.owner)
    }

    @Test
    fun `currentOwner returns the active owner`() = runTest {
        val owner = AudioCaptureOwner()
        assertNull(owner.currentOwner())

        val lease = owner.acquire(CaptureOwner.SHERPA_VAD, "vad-session")
        assertNotNull(lease)

        assertEquals(CaptureOwner.SHERPA_VAD, owner.currentOwner())

        lease!!.release()
        assertNull(owner.currentOwner())
    }

    @Test
    fun `currentOwnerName returns display name`() = runTest {
        val owner = AudioCaptureOwner()
        assertNull(owner.currentOwnerName())

        owner.acquire(CaptureOwner.AUDIO_RECORD_ROUTE_PROBE, "probe-session")
        assertEquals("Route Probe", owner.currentOwnerName())
    }

    @Test
    fun `double release is safe`() = runTest {
        val owner = AudioCaptureOwner()
        val lease = owner.acquire(CaptureOwner.PLATFORM_SPEECH_RECOGNIZER, "session-1")
        assertNotNull(lease)

        lease!!.release()
        assertTrue(lease.isReleased)

        lease.release()
        assertTrue(lease.isReleased)

        val nextLease = owner.acquire(CaptureOwner.SHERPA_STT, "session-2")
        assertNotNull(nextLease)
    }

    @Test
    fun `STT cannot start during route probe`() = runTest {
        val owner = AudioCaptureOwner()
        val probeLease = owner.acquire(CaptureOwner.AUDIO_RECORD_ROUTE_PROBE, "probe-1")
        assertNotNull(probeLease)

        val sttLease = owner.acquire(CaptureOwner.SHERPA_STT, "stt-1")
        assertNull(sttLease)

        probeLease!!.release()

        val sttLease2 = owner.acquire(CaptureOwner.SHERPA_STT, "stt-1")
        assertNotNull(sttLease2)
    }

    @Test
    fun `VAD cannot start during platform STT`() = runTest {
        val owner = AudioCaptureOwner()
        val sttLease = owner.acquire(CaptureOwner.PLATFORM_SPEECH_RECOGNIZER, "stt-1")
        assertNotNull(sttLease)

        val vadLease = owner.acquire(CaptureOwner.SHERPA_VAD, "vad-1")
        assertNull(vadLease)
    }

    @Test
    fun `probe cannot start during VAD`() = runTest {
        val owner = AudioCaptureOwner()
        val vadLease = owner.acquire(CaptureOwner.SHERPA_VAD, "vad-1")
        assertNotNull(vadLease)

        val probeLease = owner.acquire(CaptureOwner.AUDIO_RECORD_ROUTE_PROBE, "probe-1")
        assertNull(probeLease)
    }

    @Test
    fun `all four owners are distinct`() = runTest {
        val owners = CaptureOwner.values()
        assertEquals(4, owners.size)
        assertEquals("Platform STT", CaptureOwner.PLATFORM_SPEECH_RECOGNIZER.displayName)
        assertEquals("Route Probe", CaptureOwner.AUDIO_RECORD_ROUTE_PROBE.displayName)
        assertEquals("Sherpa VAD", CaptureOwner.SHERPA_VAD.displayName)
        assertEquals("Sherpa STT", CaptureOwner.SHERPA_STT.displayName)
    }
}
