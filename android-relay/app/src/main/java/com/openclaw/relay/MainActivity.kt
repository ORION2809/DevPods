package com.openclaw.relay

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val relayViewModel: RelayViewModel by viewModels()
    private var pendingAutomationIntent: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingAutomationIntent = intent

        setContent {
            MaterialTheme {
                val state by relayViewModel.state.collectAsState()
                val context = LocalContext.current
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                ) { }

                RelayScreen(
                    state = state,
                    onRequestPermissions = {
                        permissionLauncher.launch(buildRuntimePermissions())
                    },
                    onBridgeBaseUrlChanged = relayViewModel::updateBridgeBaseUrl,
                    onRelayTokenChanged = relayViewModel::updateRelayToken,
                    onWorkspaceChanged = relayViewModel::updateWorkspace,
                    onStartRelay = { relayViewModel.startRelay(context) },
                    onStopRelay = { relayViewModel.stopRelay(context) },
                    onCheckHealth = { relayViewModel.checkHealth(context) },
                    onQuickStatus = { relayViewModel.quickStatus(context) },
                    onWakeAndListen = { relayViewModel.wakeAndListen(context) },
                    onApprove = { relayViewModel.approve(context) },
                    onReject = { relayViewModel.reject(context) },
                    onCancel = { relayViewModel.cancel(context) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingAutomationIntent = intent
        dispatchPendingAutomationIntent()
    }

    override fun onResume() {
        super.onResume()
        dispatchPendingAutomationIntent()
    }

    private fun buildRuntimePermissions(): Array<String> {
        return buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }
    private fun dispatchPendingAutomationIntent() {
        val automationIntent = pendingAutomationIntent ?: return
        val serviceAction = automationIntent.getStringExtra(RelayService.EXTRA_SERVICE_ACTION) ?: return

        if (!isAutomationEnabled()) {
            clearAutomationExtras(automationIntent)
            pendingAutomationIntent = null
            return
        }

        relayViewModel.dispatchAutomationAction(
            context = this,
            serviceAction = serviceAction,
            bridgeBaseUrl = automationIntent.getStringExtra(RelayService.EXTRA_BRIDGE_BASE_URL),
            relayToken = automationIntent.getStringExtra(RelayService.EXTRA_RELAY_TOKEN),
            workspace = automationIntent.getStringExtra(RelayService.EXTRA_WORKSPACE),
            trigger = automationIntent.getStringExtra(RelayService.EXTRA_TRIGGER),
            eventName = automationIntent.getStringExtra(RelayService.EXTRA_EVENT_NAME),
            utterance = automationIntent.getStringExtra(RelayService.EXTRA_UTTERANCE),
            pendingActionId = automationIntent.getStringExtra(RelayService.EXTRA_PENDING_ACTION_ID),
        )

        clearAutomationExtras(automationIntent)
        pendingAutomationIntent = null
    }

    private fun isAutomationEnabled(): Boolean {
        return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun clearAutomationExtras(intent: Intent) {
        intent.removeExtra(RelayService.EXTRA_SERVICE_ACTION)
        intent.removeExtra(RelayService.EXTRA_BRIDGE_BASE_URL)
        intent.removeExtra(RelayService.EXTRA_RELAY_TOKEN)
        intent.removeExtra(RelayService.EXTRA_WORKSPACE)
        intent.removeExtra(RelayService.EXTRA_TRIGGER)
        intent.removeExtra(RelayService.EXTRA_EVENT_NAME)
        intent.removeExtra(RelayService.EXTRA_UTTERANCE)
        intent.removeExtra(RelayService.EXTRA_PENDING_ACTION_ID)
        setIntent(intent)
    }
}

@Composable
private fun RelayScreen(
    state: RelayUiState,
    onRequestPermissions: () -> Unit,
    onBridgeBaseUrlChanged: (String) -> Unit,
    onRelayTokenChanged: (String) -> Unit,
    onWorkspaceChanged: (String) -> Unit,
    onStartRelay: () -> Unit,
    onStopRelay: () -> Unit,
    onCheckHealth: () -> Unit,
    onQuickStatus: () -> Unit,
    onWakeAndListen: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("OpenClaw Relay for Android", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Low-latency transcript-first relay for ordinary Bluetooth earbuds.",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = state.config.bridgeBaseUrl,
            onValueChange = onBridgeBaseUrlChanged,
            label = { Text("Bridge base URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        OutlinedTextField(
            value = state.config.relayToken,
            onValueChange = onRelayTokenChanged,
            label = { Text("Relay bearer token") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        OutlinedTextField(
            value = state.config.workspace,
            onValueChange = onWorkspaceChanged,
            label = { Text("Workspace") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRequestPermissions, modifier = Modifier.weight(1f)) {
                Text("Permissions")
            }
            Button(onClick = onStartRelay, modifier = Modifier.weight(1f)) {
                Text("Start Relay")
            }
            Button(onClick = onStopRelay, modifier = Modifier.weight(1f)) {
                Text("Stop")
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCheckHealth, modifier = Modifier.weight(1f)) {
                Text("Health")
            }
            Button(onClick = onQuickStatus, modifier = Modifier.weight(1f)) {
                Text("Quick Status")
            }
            Button(onClick = onWakeAndListen, modifier = Modifier.weight(1f)) {
                Text("Push To Talk")
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onApprove, modifier = Modifier.weight(1f), enabled = state.pendingApprovalSummary != null) {
                Text("Approve")
            }
            Button(onClick = onReject, modifier = Modifier.weight(1f), enabled = state.pendingApprovalSummary != null) {
                Text("Reject")
            }
            Button(onClick = onCancel, modifier = Modifier.weight(1f), enabled = state.pendingActionId != null) {
                Text("Cancel")
            }
        }

        RelayStatusCard(title = "Service") {
            Text("Running: ${state.isServiceRunning}")
            Text("Listening: ${state.isListening}")
            Text("Last headset event: ${state.lastHeadsetEvent ?: "none"}")
            Text("Bridge: ${state.bridgeStatus}")
        }

        RelayStatusCard(title = "Latest Interaction") {
            Text("Partial transcript: ${state.partialTranscript.ifBlank { "none" }}")
            Spacer(modifier = Modifier.height(4.dp))
            Text("Transcript: ${state.lastTranscript.ifBlank { "none" }}")
            Spacer(modifier = Modifier.height(4.dp))
            Text("Speak: ${state.lastResponseSpeak.ifBlank { "none" }}")
            Text("Display: ${state.lastResponseDisplay.ifBlank { "none" }}")
            Text("Status: ${state.lastResponseStatus ?: "none"}")
        }

        RelayStatusCard(title = "Approval") {
            Text("Pending action id: ${state.pendingActionId ?: "none"}")
            Text("Pending summary: ${state.pendingApprovalSummary ?: "none"}")
        }

        RelayStatusCard(title = "Latency") {
            Text("Health: ${state.latency.lastHealthMs?.toString() ?: "-"} ms")
            Text("Bridge command: ${state.latency.lastBridgeCommandMs?.toString() ?: "-"} ms")
            Text("Speech started at: ${state.latency.lastSpeechStartedAtMs?.toString() ?: "-"}")
        }

        if (!state.errorMessage.isNullOrBlank()) {
            RelayStatusCard(title = "Error") {
                Text(state.errorMessage)
            }
        }
    }
}

@Composable
private fun RelayStatusCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}