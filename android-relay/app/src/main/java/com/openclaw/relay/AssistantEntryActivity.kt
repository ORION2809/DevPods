package com.openclaw.relay

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat

internal const val ACTION_VOICE_ASSIST = "android.intent.action.VOICE_ASSIST"

internal fun relayServiceActionForAssistantLaunch(action: String?): String? {
    return when (action) {
        Intent.ACTION_ASSIST,
        ACTION_VOICE_ASSIST,
        Intent.ACTION_VOICE_COMMAND -> RelayService.ACTION_ASSIST_LONG_PRESS
        else -> null
    }
}

class AssistantEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleAssistantIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAssistantIntent(intent)
    }

    private fun handleAssistantIntent(intent: Intent?) {
        val serviceAction = relayServiceActionForAssistantLaunch(intent?.action)
        if (serviceAction != null) {
            ContextCompat.startForegroundService(
                this,
                RelayService.intent(this, serviceAction)
                    .putExtra(RelayService.EXTRA_TRIGGER, intent?.action ?: ACTION_VOICE_ASSIST),
            )
        }

        finish()
    }
}