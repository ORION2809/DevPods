package com.openclaw.relay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openclaw.relay.RelayUiState
import com.openclaw.relay.history.ActivityEventType
import com.openclaw.relay.ui.components.ButtonStyle
import com.openclaw.relay.ui.components.ChipStyle
import com.openclaw.relay.ui.components.DevPodsButton
import com.openclaw.relay.ui.components.DevPodsCard
import com.openclaw.relay.ui.components.DevPodsChip
import com.openclaw.relay.ui.components.DevPodsSmallButton
import com.openclaw.relay.ui.theme.DevPodsColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ActivityScreen(
    state: RelayUiState,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onDismissDiagnostics: () -> Unit,
    onDismissQueued: () -> Unit,
    modifier: Modifier = Modifier,
    diagnosticsExported: Boolean = false,
    queuedActionsSent: Boolean = false,
    showApprovalDetail: Boolean = false,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Spacer(modifier = Modifier.height(8.dp)) }

        item {
            Text(
                text = "Activity",
                style = MaterialTheme.typography.headlineSmall,
                color = DevPodsColor.Ink,
            )
        }

        if (state.pendingApprovalRequest != null) {
            if (showApprovalDetail) {
                item {
                    ApprovalDetailSheet(
                        state = state,
                        onApprove = onApprove,
                        onReject = onReject,
                    )
                }
            } else {
                item {
                    ApprovalPendingCard(
                        state = state,
                        onApprove = onApprove,
                        onReject = onReject,
                    )
                }
                item {
                    ConversationCard(state)
                }
                item {
                    TimelineCard(state)
                }
            }
        } else {
            item {
                EmptyApprovalsCard()
            }
        }

        if (queuedActionsSent) {
            item {
                QueuedActionsSentCard(onDismiss = onDismissQueued)
            }
        }

        if (diagnosticsExported) {
            item {
                DiagnosticsExportedCard(onDismiss = onDismissDiagnostics)
            }
        }

        if (state.activityHistory.isNotEmpty()) {
            item {
                ActivityHistoryCard(entries = state.activityHistory)
            }
        } else {
            item {
                NoRecentActivityCard()
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun ApprovalPendingCard(
    state: RelayUiState,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    val request = state.pendingApprovalRequest
    val summary = state.pendingApprovalSummary ?: "Action requires approval"
    val isHardApproval = request?.riskClass == "hard_approval"

    DevPodsCard(
        accentColor = if (isHardApproval) DevPodsColor.Red else DevPodsColor.Amber,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DevPodsChip(
                text = if (isHardApproval) "Hard approval" else "Approval required",
                style = if (isHardApproval) ChipStyle.Error else ChipStyle.Warning,
            )
            Text(
                text = "Review requested action",
                style = MaterialTheme.typography.titleLarge,
                color = DevPodsColor.Ink,
            )
            Text(
                text = summary,
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
private fun ApprovalDetailSheet(
    state: RelayUiState,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    val request = state.pendingApprovalRequest ?: return
    val isHardApproval = request.riskClass == "hard_approval"
    val expiresSec = request.expiresInMs / 1000

    DevPodsCard(
        accentColor = if (isHardApproval) DevPodsColor.Red else DevPodsColor.Amber,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Grabber
            Box(
                modifier = Modifier
                    .width(44.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(DevPodsColor.Line),
            )

            DevPodsChip(
                text = if (isHardApproval) "Hard approval" else "Approval required",
                style = if (isHardApproval) ChipStyle.Error else ChipStyle.Warning,
            )

            Text(
                text = request.summary,
                style = MaterialTheme.typography.headlineSmall,
                color = DevPodsColor.Ink,
                textAlign = TextAlign.Center,
            )

            Text(
                text = "This action requires your explicit confirmation before it can proceed.",
                style = MaterialTheme.typography.bodyMedium,
                color = DevPodsColor.Muted,
                textAlign = TextAlign.Center,
            )

            // Risk grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RiskGridItem(
                    label = "Action",
                    value = request.actionType,
                    modifier = Modifier.weight(1f),
                )
                RiskGridItem(
                    label = "Risk",
                    value = request.riskClass.replace("_", " ").replaceFirstChar { it.uppercase() },
                    modifier = Modifier.weight(1f),
                )
                RiskGridItem(
                    label = "Expires",
                    value = "${expiresSec}s",
                    modifier = Modifier.weight(1f),
                )
            }

            // Consequence row
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Consequence",
                    style = MaterialTheme.typography.labelMedium,
                    color = DevPodsColor.Ink,
                )
                Text(
                    text = "This will execute on your workspace and may change files or trigger deployments.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DevPodsColor.Muted,
                )
            }

            // Gesture row
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Gesture",
                    style = MaterialTheme.typography.labelMedium,
                    color = DevPodsColor.Ink,
                )
                Text(
                    text = "Right double tap approves, left double tap rejects.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DevPodsColor.Muted,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DevPodsButton(
                    text = "Approve",
                    onClick = onApprove,
                    style = ButtonStyle.Primary,
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
private fun RiskGridItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(DevPodsColor.Surface2)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = DevPodsColor.Muted,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = DevPodsColor.Ink,
        )
    }
}

@Composable
private fun ConversationCard(
    state: RelayUiState,
) {
    if (state.lastTranscript.isBlank() && state.lastResponseDisplay.isBlank()) return

    DevPodsCard(
        accentColor = DevPodsColor.Teal,
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
private fun TimelineCard(
    state: RelayUiState,
) {
    val receivedAt = state.pendingApprovalReceivedAtMs
    val timeText = receivedAt?.let {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it))
    } ?: "Just now"

    DevPodsCard(
        accentColor = DevPodsColor.Blue,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Timeline",
                style = MaterialTheme.typography.titleMedium,
                color = DevPodsColor.Ink,
            )
            Text(
                text = "Wake received \u2192 Listening started \u2192 Transcript captured \u2192 Approval requested at $timeText",
                style = MaterialTheme.typography.bodyMedium,
                color = DevPodsColor.Muted,
            )
        }
    }
}

@Composable
private fun EmptyApprovalsCard() {
    DevPodsCard(
        accentColor = DevPodsColor.Teal,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            colors = listOf(
                                DevPodsColor.White.copy(alpha = 0.86f),
                                DevPodsColor.Surface.copy(alpha = 0.46f),
                            ),
                        ),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "\u2713",
                    style = MaterialTheme.typography.headlineMedium,
                    color = DevPodsColor.Teal,
                )
            }
            Text(
                text = "No approvals pending",
                style = MaterialTheme.typography.titleLarge,
                color = DevPodsColor.Ink,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "When DevPods needs your confirmation, the request appears here and on Home.",
                style = MaterialTheme.typography.bodyMedium,
                color = DevPodsColor.Muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun QueuedActionsSentCard(
    onDismiss: () -> Unit,
) {
    DevPodsCard(
        accentColor = DevPodsColor.Teal,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            colors = listOf(
                                DevPodsColor.White.copy(alpha = 0.86f),
                                DevPodsColor.Surface.copy(alpha = 0.46f),
                            ),
                        ),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "\u2713",
                    style = MaterialTheme.typography.headlineMedium,
                    color = DevPodsColor.Teal,
                )
            }
            Text(
                text = "Queued actions sent",
                style = MaterialTheme.typography.titleLarge,
                color = DevPodsColor.Ink,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Your pending commands reached the desktop bridge. Risky actions will still ask for approval.",
                style = MaterialTheme.typography.bodyMedium,
                color = DevPodsColor.Muted,
                textAlign = TextAlign.Center,
            )
            DevPodsSmallButton(
                text = "Dismiss",
                onClick = onDismiss,
                style = ButtonStyle.Ghost,
            )
        }
    }
}

@Composable
private fun DiagnosticsExportedCard(
    onDismiss: () -> Unit,
) {
    DevPodsCard(
        accentColor = DevPodsColor.Teal,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            colors = listOf(
                                DevPodsColor.White.copy(alpha = 0.86f),
                                DevPodsColor.Surface.copy(alpha = 0.46f),
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "\u2713",
                    style = MaterialTheme.typography.headlineMedium,
                    color = DevPodsColor.Teal,
                )
            }
            Text(
                text = "Diagnostics exported",
                style = MaterialTheme.typography.titleLarge,
                color = DevPodsColor.Ink,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Redacted support report shared successfully. You can review the payload anytime before sending.",
                style = MaterialTheme.typography.bodyMedium,
                color = DevPodsColor.Muted,
                textAlign = TextAlign.Center,
            )
            DevPodsSmallButton(
                text = "Dismiss",
                onClick = onDismiss,
                style = ButtonStyle.Ghost,
            )
        }
    }
}

@Composable
private fun ActivityHistoryCard(
    entries: List<com.openclaw.relay.history.ActivityHistoryEntry>,
) {
    DevPodsCard(
        accentColor = DevPodsColor.Blue,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "History",
                style = MaterialTheme.typography.titleMedium,
                color = DevPodsColor.Ink,
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                entries.reversed().forEach { entry ->
                    val label = when (entry.type) {
                        ActivityEventType.WAKE -> "\u2713 Wake"
                        ActivityEventType.TRANSCRIPT -> "\u2713 Speech"
                        ActivityEventType.APPROVAL_REQUESTED -> "\u26A0 Approval requested"
                        ActivityEventType.APPROVAL_APPROVED -> "\u2713 Approved"
                        ActivityEventType.APPROVAL_REJECTED -> "\u2717 Rejected"
                        ActivityEventType.APPROVAL_EXPIRED -> "\u23F0 Expired"
                        ActivityEventType.QUEUED -> "\u23F3 Queued"
                        ActivityEventType.RETRIED -> "\u27F3 Retried"
                        ActivityEventType.DISCARDED -> "\u2717 Discarded"
                        ActivityEventType.SETUP_COMPLETED -> "\u2713 Setup complete"
                        ActivityEventType.ERROR -> "\u26A0 Error"
                    }
                    Text(
                        text = "$label: ${entry.summary}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DevPodsColor.Muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun NoRecentActivityCard() {
    DevPodsCard(
        accentColor = DevPodsColor.Blue,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "No recent activity yet",
                style = MaterialTheme.typography.titleLarge,
                color = DevPodsColor.Ink,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Wake DevPods or tap Push-to-talk. Your transcript, spoken reply, and timeline will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = DevPodsColor.Muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}
