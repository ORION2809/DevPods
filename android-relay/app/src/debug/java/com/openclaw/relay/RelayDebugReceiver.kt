package com.openclaw.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RelayDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val relayAction = intent.getStringExtra(RelayService.EXTRA_SERVICE_ACTION)
            ?.takeIf { value -> value.isNotBlank() }
            ?: intent.getStringExtra(EXTRA_RELAY_ACTION)?.takeIf { value -> value.isNotBlank() }
            ?: return

        val activityIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(RelayService.EXTRA_SERVICE_ACTION, relayAction)
            .apply {
                copyExtra(intent, RelayService.EXTRA_BRIDGE_BASE_URL)
                copyExtra(intent, RelayService.EXTRA_RELAY_TOKEN)
                copyExtra(intent, RelayService.EXTRA_WORKSPACE)
                copyExtra(intent, RelayService.EXTRA_TRIGGER)
                copyExtra(intent, RelayService.EXTRA_EVENT_NAME)
                copyExtra(intent, RelayService.EXTRA_UTTERANCE)
                copyExtra(intent, RelayService.EXTRA_PENDING_ACTION_ID)
            }

        context.startActivity(activityIntent)
    }

    private fun Intent.copyExtra(source: Intent, key: String) {
        if (source.hasExtra(key)) {
            putExtra(key, source.getStringExtra(key))
        }
    }

    companion object {
        const val EXTRA_RELAY_ACTION = RelayService.EXTRA_SERVICE_ACTION
    }
}