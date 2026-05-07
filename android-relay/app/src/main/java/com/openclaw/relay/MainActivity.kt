package com.openclaw.relay

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Date

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
                    onTestSpeaker = { relayViewModel.testSpeaker(context) },
                    onTapTest = { relayViewModel.tapTest(context) },
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
    onTestSpeaker: () -> Unit,
    onTapTest: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onCancel: () -> Unit,
) {
    var showAdvancedSettings by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val primaryState = derivePrimaryState(state)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("DevPods Relay", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Android-first voice relay for headset controls, developer commands, and short spoken replies.",
            style = MaterialTheme.typography.bodyMedium,
        )

        RelayStatusCard(title = "Current state") {
            Text(primaryState.first, style = MaterialTheme.typography.titleLarge)
            Text(primaryState.second)
            Text("Bridge: ${state.bridgeStatus}")
            Text("Audio route: ${state.audioRoute.status}")
        }

        RelayStatusCard(title = "Primary actions") {
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
                Button(onClick = onTestSpeaker, modifier = Modifier.weight(1f)) {
                    Text("Speaker Test")
                }
                Button(onClick = onTapTest, modifier = Modifier.weight(1f)) {
                    Text("Tap Test")
                }
                Button(
                    onClick = { showAdvancedSettings = !showAdvancedSettings },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (showAdvancedSettings) "Hide Settings" else "Advanced")
                }
            }
        }

        if (showAdvancedSettings) {
            RelayStatusCard(title = "Advanced settings") {
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
            }
        }

        RelayStatusCard(title = "Readiness") {
            Text("Speech recognition: ${if (state.speechRecognitionAvailable) "Ready" else "Unavailable"}")
            Text("Text to speech: ${if (state.ttsReady) "Ready" else "Starting"}")
            Text("Route status: ${state.audioRoute.status}")
            Text("Selected device: ${formatRouteDevice(state.audioRoute)}")
            Text("Available devices: ${state.audioRoute.availableDevices}")
        }

        RelayStatusCard(title = "Hardware verification") {
            val wakeSignal = state.lastWakeSignal
            Text("Wake source: ${wakeSignal?.sourceLabel ?: "No wake signal captured yet"}")
            Text("Trigger: ${wakeSignal?.trigger ?: "none"}")
            Text("Media key: ${wakeSignal?.keyLabel ?: "none"}")
            Text("Controller package: ${wakeSignal?.controllerPackage ?: "unknown"}")
            Text("Observed at: ${formatTimestamp(context, wakeSignal?.receivedAtMs)}")
            Text(
                when (wakeSignal?.source) {
                    "physical_media_button" -> "Physical headset media-button delivery has been observed on this device."
                    "manual_tap_test" -> "Tap Test reached the relay path. Use a real earbud press to verify actual hardware delivery."
                    else -> "Physical headset wake is not verified yet. Use Tap Test for UI and log validation, or a real earbud press to confirm hardware delivery."
                },
            )
        }

        RelayStatusCard(title = "Approval controls") {
            Button(onClick = onRequestPermissions, modifier = Modifier.weight(1f)) {
                Text("Pending action: ${state.pendingActionId ?: "none"}")
            }

            Text("Summary: ${state.pendingApprovalSummary ?: "none"}")
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
        }

        RelayStatusCard(title = "Service") {
            Text("Running: ${state.isServiceRunning}")
            Text("Listening: ${state.isListening}")
            Text("Thinking: ${state.isAwaitingBridgeResponse}")
            Text("Speaking: ${state.isSpeaking}")
            Text("Last headset event: ${state.lastHeadsetEvent ?: "none"}")
        }

        RelayStatusCard(title = "Autonomy") {
            val autonomy = state.activeAutonomy
            Text("Phase: ${autonomy?.phase ?: "none"}")
            Text("Mode: ${autonomy?.mode ?: "none"}")
            Text("Summary: ${autonomy?.summary ?: "none"}")
            Text("Next step: ${autonomy?.nextStep ?: "none"}")
            Text("Continue after: ${autonomy?.continueAfterMs?.toString() ?: "-"} ms")
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

        RelayStatusCard(title = "Diagnostics") {
            Text("Health: ${state.latency.lastHealthMs?.toString() ?: "-"} ms")
            Text("Bridge command: ${state.latency.lastBridgeCommandMs?.toString() ?: "-"} ms")
            Text("Speech started at: ${state.latency.lastSpeechStartedAtMs?.toString() ?: "-"}")
            Text("Last speech error: ${state.lastSpeechError ?: "none"}")
            Text("Last speaker error: ${state.lastTtsError ?: "none"}")
        }

        if (!state.errorMessage.isNullOrBlank()) {
            RelayStatusCard(title = "Error") {
                Text(state.errorMessage)
            }
        }
    }
}

private fun derivePrimaryState(state: RelayUiState): Pair<String, String> {
    return when {
        !state.errorMessage.isNullOrBlank() -> "Attention needed" to state.errorMessage
        !state.speechRecognitionAvailable -> "Speech unavailable" to "Enable speech recognition on this device before using headset wake or push-to-talk."
        !state.ttsReady -> "Speaker warming up" to "Text-to-speech is still initializing. Use Speaker Test when it becomes ready."
        state.isListening -> "Listening" to "Speak a short developer request through the active headset or the phone microphone."
        state.isAwaitingBridgeResponse -> "Thinking" to "DevPods Bridge is processing the current request."
        state.isSpeaking -> "Speaking" to "A spoken reply is currently playing through the selected communication route."
        state.pendingApprovalSummary != null -> "Approval required" to state.pendingApprovalSummary
        state.isServiceRunning && state.bridgeStatus.startsWith("Healthy") -> "Ready" to "Relay is running and the bridge is responding."
        state.isServiceRunning -> "Relay running" to "Use Health to confirm the bridge before starting a live session."
        else -> "Start the relay" to "Grant permissions, verify the bridge, then start DevPods Relay."
    }
}

private fun formatRouteDevice(snapshot: RelayAudioRouteSnapshot): String {
    return listOfNotNull(snapshot.selectedDeviceType, snapshot.selectedDeviceName).joinToString(separator = " · ")
        .ifBlank { "none" }
}

private fun formatTimestamp(context: android.content.Context, value: Long?): String {
    if (value == null) {
        return "never"
    }

    return DateFormat.getTimeFormat(context).format(Date(value))
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