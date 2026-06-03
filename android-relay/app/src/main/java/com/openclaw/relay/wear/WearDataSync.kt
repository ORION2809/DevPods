package com.openclaw.relay.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.openclaw.relay.RelayStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val TAG = "WearDataSync"
private const val WEAR_PATH_STATE = "/devpods/state"

/**
 * Syncs DevPods relay state to the Wearable Data Layer so the Wear OS tile
 * can display current phase, pending approvals, and countdowns.
 */
class WearDataSync(context: Context) {
    private val dataClient = Wearable.getDataClient(context)
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        syncScope.launch {
            RelayStateStore.state
                .map { state ->
                    val wearApprovalEnabled = state.notificationPreference?.wearApprovalEnabled ?: true
                    val phase = when {
                        state.pendingApprovalRequest != null && wearApprovalEnabled -> "approval_pending"
                        state.isListening -> "listening"
                        state.isSpeaking -> "responding"
                        else -> "idle"
                    }
                    val approvalRequest = state.pendingApprovalRequest?.takeIf { wearApprovalEnabled }
                    val expiryMs = approvalRequest?.let {
                        (state.pendingApprovalReceivedAtMs ?: System.currentTimeMillis()) + it.expiresInMs
                    } ?: 0L
                    val actionId = state.pendingActionId?.takeIf { wearApprovalEnabled } ?: ""
                    val summary = approvalRequest?.summary
                        ?: state.lastResponseSpeak.take(60).ifEmpty { "Ready" }
                    WearTileState(phase, summary, actionId, expiryMs)
                }
                .distinctUntilChanged()
                .collectLatest { tileState ->
                    pushState(tileState)
                }
        }
    }

    private suspend fun pushState(tileState: WearTileState) {
        try {
            val request = PutDataMapRequest.create(WEAR_PATH_STATE).apply {
                dataMap.putString("phase", tileState.phase)
                dataMap.putString("summary", tileState.summary)
                dataMap.putString("actionId", tileState.actionId)
                dataMap.putLong("expiryMs", tileState.expiryMs)
            }
            dataClient.putDataItem(request.asPutDataRequest()).await()
        } catch (e: Exception) {
            Log.d(TAG, "Wear sync failed (watch may not be connected): ${e.message}")
        }
    }

    private data class WearTileState(
        val phase: String,
        val summary: String,
        val actionId: String,
        val expiryMs: Long,
    )
}
