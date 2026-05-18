package com.openclaw.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayCommandAuthTest {
    @Test
    fun `relay actions require an internal command token`() {
        assertTrue(relayCommandRequiresAuth(RelayService.ACTION_START_RELAY))
        assertTrue(relayCommandRequiresAuth(RelayService.ACTION_WAKE_AND_LISTEN))
        assertTrue(relayCommandRequiresAuth(RelayService.ACTION_ASSIST_LONG_PRESS))
        assertTrue(relayCommandRequiresAuth(RelayService.ACTION_APPROVE))
        assertTrue(relayCommandRequiresAuth(RelayService.ACTION_AUDIO_ROUTE_PROBE))
        assertFalse(relayCommandRequiresAuth("androidx.media3.session.MediaSessionService"))
        assertFalse(relayCommandRequiresAuth(null))
    }

    @Test
    fun `token validation rejects unauthorized relay commands`() {
        assertTrue(isAuthorizedRelayCommand(RelayService.ACTION_CHECK_HEALTH, "secret", "secret"))
        assertFalse(isAuthorizedRelayCommand(RelayService.ACTION_CHECK_HEALTH, null, "secret"))
        assertFalse(isAuthorizedRelayCommand(RelayService.ACTION_CHECK_HEALTH, "wrong", "secret"))
        assertTrue(isAuthorizedRelayCommand("androidx.media3.session.MediaSessionService", null, "secret"))
    }
}
