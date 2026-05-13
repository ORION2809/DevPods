package com.openclaw.relay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openclaw.relay.RelayAudioRouteSnapshot
import com.openclaw.relay.RelayConfig
import com.openclaw.relay.RelayLatencySnapshot
import com.openclaw.relay.RelayWakeSignal
import com.openclaw.relay.ui.components.ButtonStyle
import com.openclaw.relay.ui.components.DevPodsCard
import com.openclaw.relay.ui.components.DevPodsSmallButton
import com.openclaw.relay.ui.theme.DevPodsColor

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeveloperModeScreen(
    config: RelayConfig,
    bridgeStatus: String,
    audioRoute: RelayAudioRouteSnapshot,
    lastWake: RelayWakeSignal?,
    latency: RelayLatencySnapshot,
    isServiceRunning: Boolean,
    onRequestPermissions: () -> Unit,
    onStartRelay: () -> Unit,
    onStopRelay: () -> Unit,
    onCheckHealth: () -> Unit,
    onQuickStatus: () -> Unit,
    onWakeAndListen: () -> Unit,
    onTestSpeaker: () -> Unit,
    onTapTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DevPodsColor.Background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Bridge configuration
        DevPodsCard(accentColor = DevPodsColor.Amber) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Bridge configuration",
                    style = MaterialTheme.typography.titleMedium,
                    color = DevPodsColor.Ink,
                )
                LabeledValue(label = "Base URL", value = config.bridgeBaseUrl.ifBlank { "\u2014" })
                var tokenVisible by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Token",
                        style = MaterialTheme.typography.bodySmall,
                        color = DevPodsColor.Muted,
                    )
                    Text(
                        text = if (tokenVisible) "Hide" else "Show",
                        style = MaterialTheme.typography.bodySmall,
                        color = DevPodsColor.Teal,
                        modifier = Modifier.clickable { tokenVisible = !tokenVisible },
                    )
                }
                Text(
                    text = if (tokenVisible) config.relayToken.ifBlank { "\u2014" } else "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DevPodsColor.Ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LabeledValue(label = "Workspace", value = config.workspace.ifBlank { "\u2014" })
            }
        }

        // Relay controls
        DevPodsCard(accentColor = DevPodsColor.Teal) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Relay controls",
                    style = MaterialTheme.typography.titleMedium,
                    color = DevPodsColor.Ink,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val buttons = listOf(
                        "Permissions" to onRequestPermissions,
                        "Start" to onStartRelay,
                        "Stop" to onStopRelay,
                        "Health" to onCheckHealth,
                        "Status" to onQuickStatus,
                        "PTT" to onWakeAndListen,
                        "Speaker" to onTestSpeaker,
                        "Tap Test" to onTapTest,
                    )
                    buttons.forEach { (label, action) ->
                        DevPodsSmallButton(
                            text = label,
                            onClick = action,
                            style = if (label == "Start") ButtonStyle.Primary else ButtonStyle.Secondary,
                        )
                    }
                }
            }
        }

        // Raw state summary
        DevPodsCard(accentColor = DevPodsColor.Blue) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Raw state summary",
                    style = MaterialTheme.typography.titleMedium,
                    color = DevPodsColor.Ink,
                )
                LabeledValue(label = "Bridge status", value = bridgeStatus)
                LabeledValue(
                    label = "Audio route",
                    value = audioRoute.status,
                )
                LabeledValue(
                    label = "Last wake",
                    value = lastWake?.sourceLabel ?: "\u2014",
                )
                LabeledValue(
                    label = "Latency health",
                    value = latency.lastHealthMs?.let { "${it}ms" } ?: "\u2014",
                )
                LabeledValue(
                    label = "Latency bridge",
                    value = latency.lastBridgeCommandMs?.let { "${it}ms" } ?: "\u2014",
                )
                LabeledValue(
                    label = "Service running",
                    value = if (isServiceRunning) "Yes" else "No",
                )
            }
        }
    }
}

@Composable
private fun LabeledValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = DevPodsColor.Muted,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = DevPodsColor.Ink2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
