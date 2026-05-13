package com.openclaw.relay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openclaw.relay.RelayUiState
import java.util.Locale
import com.openclaw.relay.ui.components.ButtonStyle
import com.openclaw.relay.ui.components.ChipStyle
import com.openclaw.relay.ui.components.CountdownRing
import com.openclaw.relay.ui.components.DevPodsButton
import com.openclaw.relay.ui.components.DevPodsCard
import com.openclaw.relay.ui.components.DevPodsChip
import com.openclaw.relay.ui.components.DevPodsHeroCard
import com.openclaw.relay.ui.components.DevPodsSmallButton
import com.openclaw.relay.ui.components.QueueMeter
import com.openclaw.relay.ui.components.Waveform
import com.openclaw.relay.ui.components.liveCountdownRemaining
import com.openclaw.relay.ui.theme.DevPodsColor

@Composable
fun HomeScreen(
    state: RelayUiState,
    onPairBridge: () -> Unit,
    onListenNow: () -> Unit,
    onCheckBridge: () -> Unit,
    onViewActivity: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onStop: () -> Unit,
    onRetryNow: () -> Unit,
    onDiscard: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Spacer(modifier = Modifier.height(8.dp)) }

        when {
            !state.userFacingErrorMessage.isNullOrBlank() -> {
                item { ErrorSection(state.userFacingErrorMessage, onDismissError) }
            }
            state.pendingApprovalRequest != null -> {
                item { ApprovalPendingSection(state, onApprove, onReject) }
                item { ConversationSection(state) }
            }
            state.isListening -> {
                item { ListeningSection(state) }
            }
            state.activeAutonomy != null -> {
                item { AutonomySection(state, onStop, onViewActivity) }
            }
            state.bridgeQueueState.queuedCount > 0 || state.isAwaitingBridgeResponse -> {
                item { BridgeQueueSection(state, onRetryNow, onDiscard) }
            }
            state.isServiceRunning && state.bridgeStatus.startsWith("Healthy", ignoreCase = true) -> {
                item { ReadySection(state, onListenNow, onCheckBridge, onViewActivity) }
            }
            else -> {
                item { HomeOnboardingSection(onPairBridge) }
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun HomeOnboardingSection(
    onPairBridge: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Talk to your workspace through ordinary earbuds.",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "Pair the desktop bridge, verify your earbuds, then speak short developer commands hands-free.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FeatureCard(
                title = "Pair",
                body = "Scan bridge QR",
                accentColor = DevPodsColor.Teal,
                modifier = Modifier.weight(1f),
            )
            FeatureCard(
                title = "Verify",
                body = "Prove wake and STT",
                accentColor = DevPodsColor.Amber,
                modifier = Modifier.weight(1f),
            )
            FeatureCard(
                title = "Approve",
                body = "Risky actions pause",
                accentColor = DevPodsColor.Blue,
                modifier = Modifier.weight(1f),
            )
        }

        DevPodsButton(
            text = "Pair your bridge",
            onClick = onPairBridge,
            modifier = Modifier.fillMaxWidth(),
            style = ButtonStyle.Primary,
        )
    }
}

@Composable
private fun FeatureCard(
    title: String,
    body: String,
    accentColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    DevPodsCard(
        modifier = modifier,
        accentColor = accentColor,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = DevPodsColor.Ink,
                textAlign = TextAlign.Center,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.labelSmall,
                color = DevPodsColor.Muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ReadySection(
    state: RelayUiState,
    onListenNow: () -> Unit,
    onCheckBridge: () -> Unit,
    onViewActivity: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            DevPodsHeroCard {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DevPodsChip(
                            text = "Ready",
                            style = ChipStyle.Success,
                        )
                        // Waveform positioned to the right in hero
                        Box(
                            modifier = Modifier
                                .width(80.dp)
                                .padding(start = 12.dp),
                        ) {
                            Waveform(
                                isAnimating = false,
                                modifier = Modifier.width(80.dp),
                            )
                        }
                    }
                    Text(
                        text = "Ready to listen",
                        style = MaterialTheme.typography.headlineSmall,
                        color = DevPodsColor.White,
                    )
                    Text(
                        text = "Tap your earbuds or use push-to-talk. Risky workspace actions will ask before continuing.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DevPodsColor.DarkPanelSubtle,
                        textAlign = TextAlign.Center,
                    )
                    // Spacer for buttons that overlap below
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
            // Buttons overlapping the bottom of hero card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .offset(y = 22.dp)
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            ) {
                DevPodsButton(
                    text = "Listen now",
                    onClick = onListenNow,
                    style = ButtonStyle.Primary,
                )
                DevPodsButton(
                    text = "Check bridge",
                    onClick = onCheckBridge,
                    style = ButtonStyle.Secondary,
                )
            }
        }

        // Add spacing to account for overlapping buttons
        Spacer(modifier = Modifier.height(22.dp))

        StatusChipsRow(state)

        if (state.lastTranscript.isNotBlank() || state.lastResponseDisplay.isNotBlank()) {
            LatestSessionCard(state, onViewActivity)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusChipsRow(state: RelayUiState) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val bridgeReachable = state.bridgeStatus.startsWith("Healthy", ignoreCase = true)
        DevPodsChip(
            text = if (bridgeReachable) "Bridge reachable" else "Bridge unreachable",
            style = if (bridgeReachable) ChipStyle.Success else ChipStyle.Error,
        )
        DevPodsChip(
            text = if (state.signalProviderSummary.hasVerifiedPhysicalWake) "Earbud wake observed" else "Earbud wake not verified",
            style = if (state.signalProviderSummary.hasVerifiedPhysicalWake) ChipStyle.Success else ChipStyle.Warning,
        )
        DevPodsChip(
            text = if (state.speechRecognitionAvailable) "Speech ready" else "Speech unavailable",
            style = if (state.speechRecognitionAvailable) ChipStyle.Success else ChipStyle.Error,
        )
        DevPodsChip(
            text = if (state.audioRoute.isReadyForSpeechCapture) "Using earbuds mic" else "Using phone mic",
            style = if (state.audioRoute.isReadyForSpeechCapture) ChipStyle.Success else ChipStyle.Info,
        )
    }
}

@Composable
private fun LatestSessionCard(
    state: RelayUiState,
    onViewActivity: () -> Unit,
) {
    DevPodsCard(
        accentColor = DevPodsColor.Blue,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Latest session",
                style = MaterialTheme.typography.titleMedium,
                color = DevPodsColor.Ink,
            )
            if (state.lastTranscript.isNotBlank()) {
                Text(
                    text = "You asked: \"${state.lastTranscript}\". DevPods replied: \"${state.lastResponseDisplay}\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DevPodsColor.Muted,
                )
            }
            DevPodsSmallButton(
                text = "View activity",
                onClick = onViewActivity,
                style = ButtonStyle.Ghost,
            )
        }
    }
}

@Composable
private fun ListeningSection(
    state: RelayUiState,
) {
    DevPodsHeroCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DevPodsChip(
                text = "Listening...",
                style = ChipStyle.Info,
            )
            Waveform(isAnimating = true)
            if (state.partialTranscript.isNotBlank()) {
                Text(
                    text = state.partialTranscript,
                    style = MaterialTheme.typography.bodyLarge,
                    color = DevPodsColor.White,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ApprovalPendingSection(
    state: RelayUiState,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    val request = state.pendingApprovalRequest
    val summary = state.pendingApprovalSummary ?: "Action requires approval"
    val isHardApproval = request?.riskClass == "hard_approval"
    val receivedAt = state.pendingApprovalReceivedAtMs ?: 0L
    val expiresAt = receivedAt + (request?.expiresInMs ?: 0)
    val remainingMs = liveCountdownRemaining(expiresAt.takeIf { it > receivedAt })
    val isExpired = remainingMs == 0L

    DevPodsCard(
        accentColor = if (isHardApproval) DevPodsColor.Red else DevPodsColor.Amber,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DevPodsChip(
                text = when {
                    isExpired -> "Expired"
                    isHardApproval -> "Hard approval"
                    else -> "Approval required"
                },
                style = when {
                    isExpired -> ChipStyle.Muted
                    isHardApproval -> ChipStyle.Error
                    else -> ChipStyle.Warning
                },
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.titleLarge,
                color = DevPodsColor.Ink,
            )
            Text(
                text = if (isExpired) {
                    "This request has expired. Re-run the command if you still want to proceed."
                } else {
                    "Review the details in Activity before confirming. Expires in ${(remainingMs!! / 1000)}s"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = DevPodsColor.Muted,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DevPodsButton(
                    text = "Approve",
                    onClick = onApprove,
                    style = ButtonStyle.Primary,
                    enabled = !isExpired,
                )
                DevPodsButton(
                    text = "Reject",
                    onClick = onReject,
                    style = ButtonStyle.Danger,
                )
            }
        }
    }
}

@Composable
private fun ConversationSection(
    state: RelayUiState,
) {
    if (state.lastTranscript.isBlank() && state.lastResponseDisplay.isBlank()) return

    DevPodsCard(
        accentColor = DevPodsColor.Blue,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Conversation",
                style = MaterialTheme.typography.titleMedium,
                color = DevPodsColor.Ink,
            )
            if (state.lastTranscript.isNotBlank()) {
                Text(
                    text = "Transcript: \"${state.lastTranscript}\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DevPodsColor.Muted,
                )
            }
            if (state.lastResponseDisplay.isNotBlank()) {
                Text(
                    text = "Spoken reply: \"${state.lastResponseDisplay}\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DevPodsColor.Muted,
                )
            }
        }
    }
}

@Composable
private fun AutonomySection(
    state: RelayUiState,
    onStop: () -> Unit,
    onViewActivity: () -> Unit,
) {
    val autonomy = state.activeAutonomy ?: return
    val uiState = state.autonomyUiState
    val remainingMs = liveCountdownRemaining(uiState.autonomyContinueAtMs)
    val countdownSeconds = (remainingMs ?: 0L) / 1000
    val progress = if (autonomy.continueAfterMs != null && autonomy.continueAfterMs > 0) {
        1f - ((remainingMs ?: 0L).toFloat() / autonomy.continueAfterMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    DevPodsCard(
        accentColor = DevPodsColor.Blue,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DevPodsChip(
                text = "Assistant working",
                style = ChipStyle.Info,
            )
            Text(
                text = autonomy.summary,
                style = MaterialTheme.typography.titleLarge,
                color = DevPodsColor.Ink,
            )
            if (autonomy.nextStep != null) {
                Text(
                    text = autonomy.nextStep,
                    style = MaterialTheme.typography.bodyMedium,
                    color = DevPodsColor.Muted,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "Next step",
                        style = MaterialTheme.typography.labelMedium,
                        color = DevPodsColor.Ink,
                    )
                    Text(
                        text = autonomy.nextStep ?: "Continuing...",
                        style = MaterialTheme.typography.bodySmall,
                        color = DevPodsColor.Muted,
                    )
                }

                CountdownRing(
                    seconds = countdownSeconds.toInt().coerceAtLeast(0),
                    progress = progress,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DevPodsSmallButton(
                    text = "Stop",
                    onClick = onStop,
                    style = ButtonStyle.Danger,
                )
                DevPodsSmallButton(
                    text = "View activity",
                    onClick = onViewActivity,
                    style = ButtonStyle.Ghost,
                )
            }
        }
    }
}

@Composable
private fun BridgeQueueSection(
    state: RelayUiState,
    onRetryNow: () -> Unit,
    onDiscard: () -> Unit,
) {
    val queue = state.bridgeQueueState

    DevPodsCard(
        accentColor = DevPodsColor.Amber,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DevPodsChip(
                text = "Bridge reconnecting",
                style = ChipStyle.Warning,
            )
            Text(
                text = "${queue.queuedCount} command${if (queue.queuedCount == 1) "" else "s"} queued",
                style = MaterialTheme.typography.titleLarge,
                color = DevPodsColor.Ink,
            )
            Text(
                text = "DevPods saved your latest requests because the desktop bridge is unreachable.",
                style = MaterialTheme.typography.bodyMedium,
                color = DevPodsColor.Muted,
            )
            QueueMeter(
                progress = (queue.retryAttempt.coerceAtLeast(1)).toFloat() / 5f,
                modifier = Modifier.fillMaxWidth(),
            )
            val remainingMs = liveCountdownRemaining(queue.retryAtMs)
            val nextRetrySec = ((remainingMs ?: 0L) / 1000).toInt()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = if (remainingMs != null && remainingMs > 0) "Retrying in" else "Retrying now",
                        style = MaterialTheme.typography.bodySmall,
                        color = DevPodsColor.Muted,
                    )
                    Text(
                        text = "$nextRetrySec seconds, attempt ${queue.retryAttempt.coerceAtLeast(1)} of 5",
                        style = MaterialTheme.typography.labelSmall,
                        color = DevPodsColor.Muted,
                    )
                }
                Text(
                    text = String.format(Locale.US, "%02d:%02d", nextRetrySec / 60, nextRetrySec % 60),
                    style = MaterialTheme.typography.titleMedium,
                    color = DevPodsColor.Ink,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DevPodsSmallButton(
                    text = "Retry now",
                    onClick = onRetryNow,
                    style = ButtonStyle.Primary,
                )
                DevPodsSmallButton(
                    text = "Discard",
                    onClick = onDiscard,
                    style = ButtonStyle.Danger,
                )
            }
        }
    }
}

@Composable
private fun ErrorSection(
    message: String,
    onDismissError: () -> Unit,
) {
    DevPodsCard(
        accentColor = DevPodsColor.Red,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DevPodsChip(
                text = "Error",
                style = ChipStyle.Error,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = DevPodsColor.Ink,
            )
            Text(
                text = "Check your connection, bridge status, and permissions, then try again.",
                style = MaterialTheme.typography.bodyMedium,
                color = DevPodsColor.Muted,
            )
            DevPodsSmallButton(
                text = "Dismiss",
                onClick = onDismissError,
                style = ButtonStyle.Ghost,
            )
        }
    }
}
