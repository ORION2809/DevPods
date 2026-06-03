package com.openclaw.relay.wear

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.openclaw.relay.RelayService

private const val TAG = "WearApprovalListener"
private const val WEAR_PATH = "/devpods/approval_action"

/**
 * Receives approval/reject actions from the Wear OS tile and dispatches them
 * to RelayService with the correct pending action ID.
 */
class WearApprovalListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != WEAR_PATH) return

        val payload = String(messageEvent.data)
        val parts = payload.split(":", limit = 2)
        if (parts.size != 2) {
            Log.w(TAG, "Invalid payload: $payload")
            return
        }

        val action = parts[0]
        val actionId = parts[1]

        val relayAction = when (action) {
            "approve" -> RelayService.ACTION_APPROVE
            "reject" -> RelayService.ACTION_REJECT
            "cancel" -> RelayService.ACTION_CANCEL
            else -> {
                Log.w(TAG, "Unknown action: $action")
                return
            }
        }

        val intent = RelayService.intent(this, relayAction).apply {
            putExtra(RelayService.EXTRA_PENDING_ACTION_ID, actionId)
        }
        startService(intent)
        Log.i(TAG, "Dispatched $relayAction for actionId=$actionId from Wear tile")
    }
}
