package com.openclaw.relay.ui.screens

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
import androidx.compose.material3.Switch
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
import com.openclaw.relay.SherpaBenchmarkUiState
import com.openclaw.relay.SherpaPromotionState
import com.openclaw.relay.ui.components.ButtonStyle
import com.openclaw.relay.ui.components.DevPodsCard
import com.openclaw.relay.ui.components.DevPodsSmallButton
import com.openclaw.relay.ui.theme.DevPodsColor
import com.openclaw.relay.ui.theme.DevPodsSpacing
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeveloperModeScreen(
    config: RelayConfig,
    bridgeStatus: String,
    audioRoute: RelayAudioRouteSnapshot,
    lastWake: RelayWakeSignal?,
    latency: RelayLatencySnapshot,
    isServiceRunning: Boolean,
    sherpaPromotionState: SherpaPromotionState,
    offlineCommandRecognitionEnabled: Boolean,
    benchmarkSession: SherpaBenchmarkUiState? = null,
    onRequestPermissions: () -> Unit,
    onStartRelay: () -> Unit,
    onStopRelay: () -> Unit,
    onCheckHealth: () -> Unit,
    onQuickStatus: () -> Unit,
    onWakeAndListen: () -> Unit,
    onTestSpeaker: () -> Unit,
    onTapTest: () -> Unit,
    onRunSherpaBenchmark: () -> Unit,
    onToggleOfflineCommandRecognition: (Boolean) -> Unit,
    onToggleFastWake: (Boolean) -> Unit = {},
    onToggleTtsWarmKeepalive: (Boolean) -> Unit = {},
    onToggleSpeechRecognizerPrewarm: (Boolean) -> Unit = {},
    onTogglePreferredProviderOrdering: (Boolean) -> Unit = {},
    onToggleSpeculativeRoutePrepare: (Boolean) -> Unit = {},
    onToggleBridgePrefetchOnWake: (Boolean) -> Unit = {},
    onToggleEventStreaming: (Boolean) -> Unit = {},
    onToggleLatencySummaryExport: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(DevPodsSpacing.screenX),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Bridge configuration
        DevPodsCard(accentColor = DevPodsColor.Amber) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Bridge configuration",
                    style = MaterialTheme.typography.titleMedium,
                    color = DevPodsColor.Ink,
                    modifier = Modifier.semantics { heading() },
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
                    modifier = Modifier.semantics { heading() },
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
                        val style = when (label) {
                            "Start" -> ButtonStyle.Primary
                            "Stop" -> ButtonStyle.Danger
                            else -> ButtonStyle.Ghost
                        }
                        DevPodsSmallButton(
                            text = label,
                            onClick = action,
                            style = style,
                        )
                    }
                }
            }
        }

        // Sherpa evaluation controls
        DevPodsCard(accentColor = DevPodsColor.Blue) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Sherpa command-mode evaluation",
                    style = MaterialTheme.typography.titleMedium,
                    color = DevPodsColor.Ink,
                    modifier = Modifier.semantics { heading() },
                )

                DevPodsSmallButton(
                    text = "Run Sherpa benchmark",
                    onClick = onRunSherpaBenchmark,
                    style = ButtonStyle.Primary,
                )

                if (benchmarkSession != null && benchmarkSession.isRunning) {
                    Text(
                        text = "Benchmark: ${benchmarkSession.currentCommandIndex + 1}/${benchmarkSession.totalCommands}",
                        style = MaterialTheme.typography.bodySmall,
                        color = DevPodsColor.Ink,
                    )
                    Text(
                        text = "Command: ${benchmarkSession.currentCommand}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DevPodsColor.Ink,
                    )
                    Text(
                        text = "Engine: ${benchmarkSession.currentEngine.name.lowercase().replaceFirstChar { char -> char.uppercase() }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = DevPodsColor.Muted,
                    )
                    Text(
                        text = "Status: ${benchmarkSession.statusLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = DevPodsColor.Muted,
                    )
                    if (benchmarkSession.lastTranscript.isNotBlank()) {
                        Text(
                            text = "Transcript: ${benchmarkSession.lastTranscript}",
                            style = MaterialTheme.typography.bodySmall,
                            color = DevPodsColor.Teal,
                        )
                    }
                    if (benchmarkSession.lastSampleLatencyMs != null) {
                        Text(
                            text = "Latency: ${benchmarkSession.lastSampleLatencyMs}ms",
                            style = MaterialTheme.typography.bodySmall,
                            color = DevPodsColor.Muted,
                        )
                    }
                }

                Text(
                    text = "Promotion state: ${sherpaPromotionState.name.lowercase().replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = DevPodsColor.Muted,
                )

                if (sherpaPromotionState.ordinal >= SherpaPromotionState.EXPERIMENTAL.ordinal) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "Offline command recognition toggle"
                            },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Offline command recognition",
                                style = MaterialTheme.typography.bodyMedium,
                                color = DevPodsColor.Ink,
                            )
                            Text(
                                text = "Experimental \u00b7 Use Sherpa for DevPods voice commands",
                                style = MaterialTheme.typography.bodySmall,
                                color = DevPodsColor.Muted,
                            )
                        }
                        Switch(
                            checked = offlineCommandRecognitionEnabled,
                            onCheckedChange = onToggleOfflineCommandRecognition,
                        )
                    }
                } else {
                    Text(
                        text = "Offline command recognition is hidden until benchmark gates pass (EXPERIMENTAL or PRODUCTION).",
                        style = MaterialTheme.typography.bodySmall,
                        color = DevPodsColor.Muted,
                    )
                }
            }
        }

        // Latency optimization flags
        DevPodsCard(accentColor = DevPodsColor.Blue) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Latency optimizations",
                    style = MaterialTheme.typography.titleMedium,
                    color = DevPodsColor.Ink,
                    modifier = Modifier.semantics { heading() },
                )
                LatencyToggleRow(
                    label = "Fast wake path",
                    subtitle = "Start STT immediately without waiting for bridge TTS.",
                    enabled = config.fastWakeEnabled,
                    onToggle = onToggleFastWake,
                )
                LatencyToggleRow(
                    label = "TTS warm keepalive",
                    subtitle = "Silently warm TTS engine every 3 minutes when idle.",
                    enabled = config.ttsWarmKeepaliveEnabled,
                    onToggle = onToggleTtsWarmKeepalive,
                )
                LatencyToggleRow(
                    label = "Speech recognizer prewarm",
                    subtitle = "Prepare STT session on earbud connect.",
                    enabled = config.speechRecognizerPrewarmEnabled,
                    onToggle = onToggleSpeechRecognizerPrewarm,
                )
                LatencyToggleRow(
                    label = "Preferred provider ordering",
                    subtitle = "Probe calibrated provider first on startup.",
                    enabled = config.preferredProviderOrderingEnabled,
                    onToggle = onTogglePreferredProviderOrdering,
                )
                LatencyToggleRow(
                    label = "Speculative route preparation",
                    subtitle = "Warm Bluetooth route on first tap candidate.",
                    enabled = config.speculativeRoutePrepareEnabled,
                    onToggle = onToggleSpeculativeRoutePrepare,
                )
                LatencyToggleRow(
                    label = "Bridge prefetch on wake",
                    subtitle = "Non-executing workspace cache warm on wake/candidate.",
                    enabled = config.bridgePrefetchOnWakeEnabled,
                    onToggle = onToggleBridgePrefetchOnWake,
                )
                LatencyToggleRow(
                    label = "Event streaming",
                    subtitle = "Use NDJSON streaming for lower perceived latency.",
                    enabled = config.eventStreamingEnabled,
                    onToggle = onToggleEventStreaming,
                )
                LatencyToggleRow(
                    label = "Latency summary export",
                    subtitle = "Include latency aggregates in diagnostic exports.",
                    enabled = config.latencySummaryExportEnabled,
                    onToggle = onToggleLatencySummaryExport,
                )
            }
        }

        // Raw state summary
        DevPodsCard(accentColor = DevPodsColor.Blue) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Raw state summary",
                    style = MaterialTheme.typography.titleMedium,
                    color = DevPodsColor.Ink,
                    modifier = Modifier.semantics { heading() },
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
private fun LatencyToggleRow(
    label: String,
    subtitle: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!enabled) }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = DevPodsColor.Ink,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = DevPodsColor.Muted,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
        )
    }
}

@Composable
private fun LabeledValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().semantics { contentDescription = "$label, $value" }) {
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
