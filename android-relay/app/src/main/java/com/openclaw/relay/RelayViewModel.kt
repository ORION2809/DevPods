package com.openclaw.relay

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

class RelayViewModel : ViewModel() {
    companion object {
        private const val TAG = "OpenClawRelay"
    }

    val state: StateFlow<RelayUiState> = RelayStateStore.state

    fun applyAutomationConfig(
        bridgeBaseUrl: String?,
        relayToken: String?,
        workspace: String?,
    ) {
        RelayStateStore.updateConfig { current ->
            current.copy(
                bridgeBaseUrl = bridgeBaseUrl?.takeIf { value -> value.isNotBlank() } ?: current.bridgeBaseUrl,
                relayToken = relayToken ?: current.relayToken,
                workspace = workspace?.takeIf { value -> value.isNotBlank() } ?: current.workspace,
            )
        }
    }

    fun updateBridgeBaseUrl(value: String) {
        RelayStateStore.updateConfig { it.copy(bridgeBaseUrl = value) }
    }

    fun updateRelayToken(value: String) {
        RelayStateStore.updateConfig { it.copy(relayToken = value) }
    }

    fun updateWorkspace(value: String) {
        RelayStateStore.updateConfig { it.copy(workspace = value) }
    }

    fun startRelay(context: Context) {
        ContextCompat.startForegroundService(context, RelayService.intent(context, RelayService.ACTION_START_RELAY))
    }

    fun stopRelay(context: Context) {
        val intent = RelayService.intent(context, RelayService.ACTION_STOP_RELAY)
        if (!state.value.isServiceRunning) {
            context.stopService(intent)
            return
        }

        context.startService(intent)
    }

    fun checkHealth(context: Context) {
        ContextCompat.startForegroundService(context, RelayService.intent(context, RelayService.ACTION_CHECK_HEALTH))
    }

    fun quickStatus(context: Context) {
        ContextCompat.startForegroundService(context, RelayService.intent(context, RelayService.ACTION_QUICK_STATUS))
    }

    fun wakeAndListen(context: Context) {
        val intent = RelayService.intent(context, RelayService.ACTION_WAKE_AND_LISTEN)
            .putExtra(RelayService.EXTRA_TRIGGER, "android_push_to_talk")
        ContextCompat.startForegroundService(context, intent)
    }

    fun approve(context: Context) {
        ContextCompat.startForegroundService(context, RelayService.intent(context, RelayService.ACTION_APPROVE))
    }

    fun reject(context: Context) {
        ContextCompat.startForegroundService(context, RelayService.intent(context, RelayService.ACTION_REJECT))
    }

    fun cancel(context: Context) {
        ContextCompat.startForegroundService(context, RelayService.intent(context, RelayService.ACTION_CANCEL))
    }

    fun dispatchAutomationAction(
        context: Context,
        serviceAction: String,
        bridgeBaseUrl: String?,
        relayToken: String?,
        workspace: String?,
        trigger: String?,
        eventName: String?,
        utterance: String?,
        pendingActionId: String?,
    ) {
        applyAutomationConfig(bridgeBaseUrl, relayToken, workspace)

        val intent = RelayService.intent(context, serviceAction).apply {
            bridgeBaseUrl?.let { putExtra(RelayService.EXTRA_BRIDGE_BASE_URL, it) }
            relayToken?.let { putExtra(RelayService.EXTRA_RELAY_TOKEN, it) }
            workspace?.let { putExtra(RelayService.EXTRA_WORKSPACE, it) }
            trigger?.let { putExtra(RelayService.EXTRA_TRIGGER, it) }
            eventName?.let { putExtra(RelayService.EXTRA_EVENT_NAME, it) }
            utterance?.let { putExtra(RelayService.EXTRA_UTTERANCE, it) }
            pendingActionId?.let { putExtra(RelayService.EXTRA_PENDING_ACTION_ID, it) }
        }

        Log.i(
            TAG,
            "automation dispatch action=$serviceAction event=${eventName ?: "none"} workspace=${workspace ?: state.value.config.workspace}",
        )

        if (serviceAction == RelayService.ACTION_STOP_RELAY) {
            if (!state.value.isServiceRunning) {
                context.stopService(intent)
                return
            }

            context.startService(intent)
            return
        }

        if (!state.value.isServiceRunning || serviceAction == RelayService.ACTION_START_RELAY) {
            ContextCompat.startForegroundService(context, intent)
            return
        }

        context.startService(intent)
    }
}