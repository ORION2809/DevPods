package com.openclaw.relay

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayMediaSessionControllerTest {
    @Test
    fun `action down headset hook creates wake signal`() {
        val signal = createWakeSignalForMediaButton(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_HEADSETHOOK,
            controllerPackage = "com.android.bluetooth",
        )

        assertNotNull(signal)
        assertEquals("headset_button_single", signal?.trigger)
        assertEquals("physical_media_button", signal?.source)
        assertEquals("Physical headset media button", signal?.sourceLabel)
        assertEquals("Headset hook", signal?.keyLabel)
        assertEquals("com.android.bluetooth", signal?.controllerPackage)
    }

    @Test
    fun `action down play and play pause keys create wake signal`() {
        val playSignal = createWakeSignalForMediaButton(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_MEDIA_PLAY,
            controllerPackage = null,
        )
        val playPauseSignal = createWakeSignalForMediaButton(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            controllerPackage = null,
        )

        assertEquals("Play", playSignal?.keyLabel)
        assertEquals("Play or pause", playPauseSignal?.keyLabel)
        assertEquals("headset_button_single", playSignal?.trigger)
        assertEquals("headset_button_single", playPauseSignal?.trigger)
    }

    @Test
    fun `action up is ignored`() {
        val signal = createWakeSignalForMediaButton(
            action = KeyEvent.ACTION_UP,
            keyCode = KeyEvent.KEYCODE_HEADSETHOOK,
            controllerPackage = null,
        )

        assertNull(signal)
    }

    @Test
    fun `next media key maps to triple tap wake`() {
        val signal = createWakeSignalForMediaButton(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_MEDIA_NEXT,
            controllerPackage = null,
        )

        assertNotNull(signal)
        assertEquals("triple_tap_right", signal?.trigger)
        assertEquals("Next", signal?.keyLabel)
    }

    @Test
    fun `previous and pause media keys map to long press status`() {
        val previousSignal = createWakeSignalForMediaButton(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            controllerPackage = null,
        )
        val pauseSignal = createWakeSignalForMediaButton(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_MEDIA_PAUSE,
            controllerPackage = null,
        )

        assertEquals("left_long_press", previousSignal?.trigger)
        assertEquals("Previous", previousSignal?.keyLabel)
        assertEquals("left_long_press", pauseSignal?.trigger)
        assertEquals("Pause", pauseSignal?.keyLabel)
    }

    @Test
    fun `unsupported key is ignored`() {
        val signal = createWakeSignalForMediaButton(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_MEDIA_STOP,
            controllerPackage = null,
        )

        assertNull(signal)
    }

    @Test
    fun `wake signal timestamp is current`() {
        val before = System.currentTimeMillis()
        val signal = createWakeSignalForMediaButton(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            controllerPackage = null,
        )
        val after = System.currentTimeMillis()

        assertNotNull(signal)
        assertTrue(signal!!.receivedAtMs in before..after)
    }
}
