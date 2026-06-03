package com.openclaw.relay.wear

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable

private const val TAG = "WearApprovalActivity"
private const val PHONE_PATH = "/devpods/approval_action"

/**
 * Invisible activity launched by tile approval/reject clicks.
 * Sends the action to the phone via Wearable MessageClient and finishes immediately.
 */
class WearApprovalActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent.getStringExtra("action") ?: ""
        val actionId = intent.getStringExtra("actionId") ?: ""

        if (action.isNotBlank() && actionId.isNotBlank()) {
            sendActionToPhone(action, actionId)
        } else {
            Log.w(TAG, "Missing action or actionId")
        }

        finish()
    }

    private fun sendActionToPhone(action: String, actionId: String) {
        val payload = "$action:$actionId".toByteArray()
        Wearable.getMessageClient(this)
            .sendMessageToAllConnectedNodes(PHONE_PATH, payload)
            .addOnSuccessListener {
                Log.d(TAG, "Sent $action to phone")
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to send action to phone: ${e.message}")
            }
    }

    private fun MessageClient.sendMessageToAllConnectedNodes(path: String, data: ByteArray) =
        Wearable.getNodeClient(this@WearApprovalActivity).connectedNodes
            .continueWithTask { task ->
                val nodes = task.result ?: emptyList()
                if (nodes.isEmpty()) {
                    throw IllegalStateException("No connected nodes")
                }
                // Send to first connected node (the phone)
                sendMessage(nodes.first().id, path, data)
            }
}
