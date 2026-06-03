package com.openclaw.relay.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import com.openclaw.relay.ui.components.DevPodsToggle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openclaw.relay.LearnedPhrase
import com.openclaw.relay.NotificationPreference
import com.openclaw.relay.NudgePolicy
import com.openclaw.relay.NudgeThreshold
import com.openclaw.relay.Reminder
import com.openclaw.relay.ui.components.ButtonStyle
import com.openclaw.relay.ui.components.DevPodsButton
import com.openclaw.relay.ui.components.DevPodsCard
import com.openclaw.relay.ui.components.DevPodsSmallButton
import com.openclaw.relay.ui.theme.DevPodsColor
import com.openclaw.relay.ui.theme.DevPodsSpacing
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

@Composable
fun SettingsScreen(
    notificationPreference: NotificationPreference?,
    reminders: List<Reminder>,
    learnedPhrases: List<LearnedPhrase>,
    nudgePolicy: NudgePolicy?,
    quickStartEnabled: Boolean,
    setupPhase: String,
    onNotificationStyleChanged: (String) -> Unit,
    onToggleBadge: (Boolean) -> Unit,
    onToggleSoftPingTts: (Boolean) -> Unit,
    onToggleNudgeTts: (Boolean) -> Unit,
    onToggleReminderTts: (Boolean) -> Unit,
    onToggleShowSensitive: (Boolean) -> Unit,
    onToggleWearApproval: (Boolean) -> Unit,
    onCancelReminder: (String) -> Unit,
    onCreateReminder: (String, Long) -> Unit,
    onDeleteLearnedPhrase: (String) -> Unit,
    onUpdateLearnedPhraseIntent: (LearnedPhrase, String) -> Unit,
    onResetLearnedPhrases: () -> Unit,
    onToggleNudgeEnabled: (Boolean) -> Unit,
    onToggleNudgeTypeMuted: (String, Boolean) -> Unit,
    onNudgeThresholdsChanged: (List<NudgeThreshold>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAddReminderDialog by remember { mutableStateOf(false) }

    BackHandler(onBack = onDismiss)
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = DevPodsSpacing.screenX),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(modifier = Modifier.height(8.dp)) }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                DevPodsSmallButton(
                    text = "Close",
                    onClick = onDismiss,
                    style = ButtonStyle.Secondary,
                )
            }
        }

        item {
            SettingsSectionCard(title = "Setup") {
                Text(
                    text = "Quick start: ${if (quickStartEnabled) "Enabled" else "Off"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val setupStatus = when (setupPhase) {
                    "COMPLETE_PROVEN" -> "Fully verified"
                    "COMPLETE_DEGRADED" -> "Partially verified"
                    "NOT_STARTED" -> "Not started"
                    else -> "In progress"
                }
                Text(
                    text = "Setup status: $setupStatus",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Notification Preferences
        item {
            SettingsSectionCard(title = "Notifications") {
                val prefs = notificationPreference ?: NotificationPreference(sessionId = "")

                StyleSelector(
                    currentStyle = prefs.style,
                    onStyleSelected = onNotificationStyleChanged,
                )

                Spacer(modifier = Modifier.height(8.dp))

                ToggleRow(
                    text = "Badge on app icon",
                    checked = prefs.badgeEnabled,
                    onCheckedChange = onToggleBadge,
                )
                ToggleRow(
                    text = "Speak soft pings",
                    checked = prefs.softPingTtsEnabled,
                    onCheckedChange = onToggleSoftPingTts,
                )
                ToggleRow(
                    text = "Speak nudges",
                    checked = prefs.nudgeTtsEnabled,
                    onCheckedChange = onToggleNudgeTts,
                )
                ToggleRow(
                    text = "Speak reminders",
                    checked = prefs.reminderTtsEnabled,
                    onCheckedChange = onToggleReminderTts,
                )
                ToggleRow(
                    text = "Show sensitive in notifications",
                    checked = prefs.showSensitiveInNotifications,
                    onCheckedChange = onToggleShowSensitive,
                )
                ToggleRow(
                    text = "Approve from watch",
                    checked = prefs.wearApprovalEnabled,
                    onCheckedChange = onToggleWearApproval,
                )
            }
        }

        // Nudge Policy
        item {
            SettingsSectionCard(title = "Workspace Nudges") {
                val policy = nudgePolicy ?: NudgePolicy(sessionId = "")

                ToggleRow(
                    text = "Enable nudges",
                    checked = policy.enabled,
                    onCheckedChange = onToggleNudgeEnabled,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Muted nudge types:",
                    style = MaterialTheme.typography.bodySmall,
                )

                val nudgeTypes = listOf(
                    "uncommitted_files" to "Uncommitted files",
                    "stale_branch" to "Stale branch",
                    "ci_red" to "CI red",
                    "tests_failing" to "Tests failing",
                    "ready_to_push" to "Ready to push",
                )

                nudgeTypes.forEach { (type, label) ->
                    ToggleRow(
                        text = label,
                        checked = type !in policy.mutedTypes,
                        onCheckedChange = { enabled -> onToggleNudgeTypeMuted(type, !enabled) },
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Thresholds:",
                    style = MaterialTheme.typography.bodySmall,
                )

                val thresholds = effectiveNudgeThresholds(policy)
                thresholds.forEach { threshold ->
                    ThresholdEditor(
                        threshold = threshold,
                        thresholds = thresholds,
                        onThresholdsChanged = onNudgeThresholdsChanged,
                    )
                }
            }
        }

        // Reminders
        item {
            SettingsSectionCard(title = "Reminders") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${reminders.size} active",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    DevPodsSmallButton(
                        text = "Add",
                        onClick = { showAddReminderDialog = true },
                        style = ButtonStyle.Secondary,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (reminders.isEmpty()) {
                    Text(
                        text = "No active reminders. Tap Add to create one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    reminders.forEach { reminder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = reminder.summary,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = "Due: ${formatTimestamp(reminder.dueAtMs)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DevPodsButton(
                                text = "Cancel",
                                onClick = { onCancelReminder(reminder.id) },
                            )
                        }
                    }
                }
            }
        }

        if (showAddReminderDialog) {
            item {
                AddReminderDialog(
                    onConfirm = { summary, dueAtMs ->
                        onCreateReminder(summary, dueAtMs)
                        showAddReminderDialog = false
                    },
                    onDismiss = { showAddReminderDialog = false },
                )
            }
        }

        // Learned Phrases
        item {
            SettingsSectionCard(title = "Learned Phrases") {
                if (learnedPhrases.isEmpty()) {
                    Text(
                        text = "No learned phrases yet. Confirm suggestions from the bridge to build your habit model.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    DevPodsButton(
                        text = "Reset all",
                        onClick = onResetLearnedPhrases,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    learnedPhrases.forEach { phrase ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "\"${phrase.phrase}\"",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = "Intent: ${phrase.intent} - Confirmed ${phrase.confirmationCount} time${if (phrase.confirmationCount == 1) "" else "s"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DevPodsButton(
                                    text = "Change",
                                    onClick = { onUpdateLearnedPhraseIntent(phrase, nextIntent(phrase.intent)) },
                                )
                                DevPodsButton(
                                    text = "Delete",
                                    onClick = { onDeleteLearnedPhrase(phrase.phrase) },
                                )
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun ThresholdEditor(
    threshold: NudgeThreshold,
    thresholds: List<NudgeThreshold>,
    onThresholdsChanged: (List<NudgeThreshold>) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            text = labelForNudgeType(threshold.type),
            style = MaterialTheme.typography.bodyMedium,
        )
        when (threshold.type) {
            "uncommitted_files" -> StepperRow(
                label = "Changed files",
                value = threshold.changedFilesMin,
                minValue = 0,
                onValueChanged = { value ->
                    onThresholdsChanged(replaceThreshold(thresholds, threshold.copy(changedFilesMin = value)))
                },
            )
            "stale_branch" -> StepperRow(
                label = "Hours since commit",
                value = threshold.staleBranchHours,
                minValue = 0,
                onValueChanged = { value ->
                    onThresholdsChanged(replaceThreshold(thresholds, threshold.copy(staleBranchHours = value)))
                },
            )
            "tests_failing" -> StepperRow(
                label = "Consecutive failures",
                value = threshold.consecutiveTestFailures,
                minValue = 1,
                onValueChanged = { value ->
                    onThresholdsChanged(replaceThreshold(thresholds, threshold.copy(consecutiveTestFailures = value)))
                },
            )
            "ci_red" -> StepperRow(
                label = "CI red hours",
                value = threshold.ciRedHours,
                minValue = 0,
                onValueChanged = { value ->
                    onThresholdsChanged(replaceThreshold(thresholds, threshold.copy(ciRedHours = value)))
                },
            )
            "ready_to_push" -> Text(
                text = "Clean branch ahead of remote and not behind.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: Int,
    minValue: Int,
    onValueChanged: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label: $value",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DevPodsButton(
                text = "-",
                onClick = { onValueChanged(maxOf(minValue, value - 1)) },
            )
            DevPodsButton(
                text = "+",
                onClick = { onValueChanged(value + 1) },
            )
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    DevPodsCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(DevPodsSpacing.cardX),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp).semantics { heading() },
            )
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp)
            .semantics { contentDescription = text },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        DevPodsToggle(
            checked = checked,
            onCheckedChange = onCheckedChange,
            contentDescription = text,
        )
    }
}

@Composable
private fun StyleSelector(
    currentStyle: String,
    onStyleSelected: (String) -> Unit,
) {
    Column {
        Text(
            text = "Notification style",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("aggressive" to "Aggressive", "soft" to "Soft", "silent_with_badge" to "Silent").forEach { (style, label) ->
                DevPodsButton(
                    text = if (currentStyle == style) "$label *" else label,
                    onClick = { onStyleSelected(style) },
                )
            }
        }
    }
}

private fun formatTimestamp(ms: Long): String {
    val diffMs = ms - System.currentTimeMillis()
    val diffMin = diffMs / 60_000
    return when {
        diffMin < 0 -> "Overdue"
        diffMin < 60 -> "in $diffMin min"
        diffMin < 24 * 60 -> "in ${diffMin / 60} hr"
        else -> "in ${diffMin / (24 * 60)} days"
    }
}

private fun effectiveNudgeThresholds(policy: NudgePolicy): List<NudgeThreshold> {
    if (policy.thresholds.isNotEmpty()) return policy.thresholds
    return listOf(
        NudgeThreshold(type = "uncommitted_files", changedFilesMin = 10),
        NudgeThreshold(type = "stale_branch", staleBranchHours = 24),
        NudgeThreshold(type = "tests_failing", consecutiveTestFailures = 2),
        NudgeThreshold(type = "ci_red", ciRedHours = 1),
        NudgeThreshold(type = "ready_to_push", changedFilesMin = 0),
    )
}

private fun replaceThreshold(thresholds: List<NudgeThreshold>, updated: NudgeThreshold): List<NudgeThreshold> {
    return thresholds.map { if (it.type == updated.type) updated else it }
}

private fun labelForNudgeType(type: String): String {
    return when (type) {
        "uncommitted_files" -> "Uncommitted files"
        "stale_branch" -> "Stale branch"
        "tests_failing" -> "Tests failing"
        "ci_red" -> "CI red"
        "ready_to_push" -> "Ready to push"
        else -> type
    }
}

private fun nextIntent(current: String): String {
    val intents = listOf(
        "quick_status",
        "summarize_diff",
        "latest_ci_failure",
        "run_tests",
        "create_commit_message",
        "commit_staged",
        "open_file",
        "push",
        "deploy",
        "delete",
        "revert",
    )
    val index = intents.indexOf(current).takeIf { it >= 0 } ?: 0
    return intents[(index + 1) % intents.size]
}

@Composable
private fun AddReminderDialog(
    onConfirm: (String, Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var summary by remember { mutableStateOf("") }
    var minutesAhead by remember { mutableStateOf("30") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add reminder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text("What to remind") },
                    singleLine = true,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = minutesAhead,
                    onValueChange = { minutesAhead = it.filter { c -> c.isDigit() } },
                    label = { Text("Minutes from now") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            DevPodsButton(
                text = "Add",
                onClick = {
                    val mins = minutesAhead.toIntOrNull() ?: 30
                    if (summary.isNotBlank()) {
                        onConfirm(summary, System.currentTimeMillis() + mins * 60_000L)
                    }
                },
                enabled = summary.isNotBlank() && (minutesAhead.toIntOrNull() ?: 0) > 0,
            )
        },
        dismissButton = {
            DevPodsButton(
                text = "Cancel",
                onClick = onDismiss,
                style = ButtonStyle.Secondary,
            )
        },
    )
}
