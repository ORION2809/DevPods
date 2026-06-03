package com.openclaw.relay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
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
import com.openclaw.relay.ui.components.CardTone
import com.openclaw.relay.ui.components.ChipStyle
import com.openclaw.relay.ui.components.DevPodsButton
import com.openclaw.relay.ui.components.DevPodsCard
import com.openclaw.relay.ui.components.DevPodsChip
import com.openclaw.relay.ui.components.DevPodsModalOverlay
import com.openclaw.relay.ui.components.DevPodsSmallButton
import com.openclaw.relay.ui.theme.DevPodsColor
import com.openclaw.relay.ui.theme.DevPodsSpacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

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
    onDismissApprovalDetail: () -> Unit = {},
    onShowApprovalDetail: () -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Activity feed — always rendered underneath
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = DevPodsSpacing.screenX),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            item {
                Text(
                    text = "Activity",
                    style = MaterialTheme.typography.headlineSmall,
                    color = DevPodsColor.Ink,
                    modifier = Modifier.semantics { heading() },
                )
            }

            if (state.pendingApprovalRequest != null) {
                item {
                    ApprovalPendingCard(
                        state = state,
                        onApprove = onApprove,
                        onReject = onReject,
                        onExpand = onShowApprovalDetail,
                    )
                }
                item {
                    ConversationCard(state)
                }
                item {
                    TimelineCard(state)
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

        // Approval detail modal overlay
        if (showApprovalDetail && state.pendingApprovalRequest != null) {
            DevPodsModalOverlay(onDismiss = onDismissApprovalDetail) {
                ApprovalDetailSheet(
                    state = state,
                    onApprove = onApprove,
                    onReject = onReject,
                )
            }
        }
    }
}

@Composable
private fun ApprovalPendingCard(
    state: RelayUiState,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onExpand: () -> Unit = {},
) {
    val request = state.pendingApprovalRequest
    val summary = state.pendingApprovalSummary ?: "Action requires approval"
    val isHardApproval = request?.riskClass == "hard_approval"

    DevPodsCard(
        accentColor = if (isHardApproval) DevPodsColor.Red else DevPodsColor.Amber,
        tone = if (isHardApproval) CardTone.Danger else CardTone.Normal,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onExpand),
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
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = DevPodsColor.Muted,
            )
            Row(
                modifier = Modifier.semantics { contentDescription = "Approve or reject the request" },
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
        tone = if (isHardApproval) CardTone.Danger else CardTone.Modal,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DevPodsSpacing.screenX),
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
                modifier = Modifier.semantics { heading() },
            )

            Text(
                text = "This will push the current branch to the configured remote. It may publish code outside this machine.",
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
                    text = "Remote repository receives your branch updates.",
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
                modifier = Modifier.semantics { contentDescription = "Approve or reject the request" },
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
                modifier = Modifier.semantics { heading() },
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
                modifier = Modifier.semantics { heading() },
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
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = DevPodsColor.Teal,
                    modifier = Modifier.size(48.dp),
                )
            }
            Text(
                text = "No approvals pending",
                style = MaterialTheme.typography.titleLarge,
                color = DevPodsColor.Ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
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
                modifier = Modifier.semantics { heading() },
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
                modifier = Modifier.semantics { heading() },
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
                modifier = Modifier.semantics { heading() },
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                entries.reversed().forEach { entry ->
                    val (icon, tint, label) = when (entry.type) {
                        ActivityEventType.WAKE -> Triple(Icons.Default.Check, DevPodsColor.Teal, "Wake")
                        ActivityEventType.TRANSCRIPT -> Triple(Icons.Default.Check, DevPodsColor.Teal, "Speech")
                        ActivityEventType.APPROVAL_REQUESTED -> Triple(Icons.Default.ErrorOutline, DevPodsColor.Amber, "Approval requested")
                        ActivityEventType.APPROVAL_APPROVED -> Triple(Icons.Default.CheckCircle, DevPodsColor.Teal, "Approved")
                        ActivityEventType.APPROVAL_REJECTED -> Triple(Icons.Default.Close, DevPodsColor.Red, "Rejected")
                        ActivityEventType.APPROVAL_EXPIRED -> Triple(Icons.Default.Timer, DevPodsColor.Amber, "Expired")
                        ActivityEventType.QUEUED -> Triple(Icons.Default.History, DevPodsColor.Muted, "Queued")
                        ActivityEventType.RETRIED -> Triple(Icons.Default.Refresh, DevPodsColor.Blue, "Retried")
                        ActivityEventType.DISCARDED -> Triple(Icons.Default.Close, DevPodsColor.Red, "Discarded")
                        ActivityEventType.SETUP_COMPLETED -> Triple(Icons.Default.CheckCircle, DevPodsColor.Teal, "Setup complete")
                        ActivityEventType.ERROR -> Triple(Icons.Default.ErrorOutline, DevPodsColor.Red, "Error")
                        ActivityEventType.ROUTE_SETTLED -> Triple(Icons.Default.CheckCircle, DevPodsColor.Teal, "Route ready")
                        ActivityEventType.WRONG_MIC_SUSPECTED -> Triple(Icons.Default.ErrorOutline, DevPodsColor.Amber, "Wrong mic")
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(16.dp),
                        )
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
                modifier = Modifier.semantics { heading() },
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
