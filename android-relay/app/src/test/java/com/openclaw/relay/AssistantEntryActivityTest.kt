package com.openclaw.relay

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantEntryActivityTest {
    @Test
    fun `assistant launches map to the relay long press action`() {
        assertEquals(
            RelayService.ACTION_ASSIST_LONG_PRESS,
            relayServiceActionForAssistantLaunch(Intent.ACTION_ASSIST),
        )
        assertEquals(
            RelayService.ACTION_ASSIST_LONG_PRESS,
            relayServiceActionForAssistantLaunch(ACTION_VOICE_ASSIST),
        )
        assertEquals(
            RelayService.ACTION_ASSIST_LONG_PRESS,
            relayServiceActionForAssistantLaunch(Intent.ACTION_VOICE_COMMAND),
        )
    }

    @Test
    fun `non assistant launches are ignored`() {
        assertNull(relayServiceActionForAssistantLaunch(Intent.ACTION_MAIN))
        assertNull(relayServiceActionForAssistantLaunch(null))
    }
}