package com.openclaw.relay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.openclaw.relay.RelayUiState
import com.openclaw.relay.ui.components.ButtonStyle
import com.openclaw.relay.ui.components.ChipStyle
import com.openclaw.relay.ui.components.DevPodsButton
import com.openclaw.relay.ui.components.DevPodsCard
import com.openclaw.relay.ui.components.DevPodsChip
import com.openclaw.relay.ui.theme.DevPodsColor

@Composable
fun HelpScreen(
    onRetryBridge: () -> Unit = {},
    onOpenPairingHelp: () -> Unit = {},
    onRepairMicPermission: () -> Unit = {},
    onUsePhoneMicFallback: () -> Unit = {},
    onExportDiagnostics: () -> Unit = {},
    onEnableDevMode: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onNotNow: () -> Unit = {},
    onShareDiagnostics: () -> Unit = {},
    onPreviewDiagnostics: () -> Unit = {},
    onUpdateGuide: () -> Unit = {},
    onContinueAnyway: () -> Unit = {},
    modifier: Modifier = Modifier,
    isBridgeUnreachable: Boolean = false,
    microphoneAllowed: Boolean = false,
    notificationsAllowed: Boolean = false,
    speechEngineAvailable: Boolean = false,
    showPermissionModal: Boolean = false,
    isDevModeEnabled: Boolean = false,
    diagnosticsIncludePhoneModel: Boolean = true,
    diagnosticsIncludeCapabilityMatrix: Boolean = true,
    diagnosticsIncludeErrorCategories: Boolean = true,
    diagnosticsIncludeRawRoute: Boolean = false,
    appVersion: String = "",
    bridgeVersion: String = "",
    protocolStatus: String = "",
    showVersionMismatch: Boolean = false,
    onDiagnosticsPhoneModelChanged: (Boolean) -> Unit = {},
    onDiagnosticsCapabilityMatrixChanged: (Boolean) -> Unit = {},
    onDiagnosticsErrorCategoriesChanged: (Boolean) -> Unit = {},
    onDiagnosticsRawRouteChanged: (Boolean) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Help",
            style = MaterialTheme.typography.headlineSmall,
            color = DevPodsColor.Ink,
        )

        // A. Bridge unreachable
        if (isBridgeUnreachable) {
            DevPodsCard(accentColor = DevPodsColor.Red) {
                Text(
                    text = "Bridge unreachable",
                    style = MaterialTheme.typography.titleMedium,
                    color = DevPodsColor.Red,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Make sure your computer and phone are on the same network, then retry.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DevPodsColor.Ink,
                )
                Spacer(modifier = Modifier.height(12.dp))
                DevPodsButton(
                    text = "Retry bridge",
                    onClick = onRetryBridge,
                    style = ButtonStyle.Primary,
                )
            }
        }

        // B. Recovery actions
        DevPodsCard(accentColor = DevPodsColor.Teal) {
            Text(
                text = "Recovery actions",
                style = MaterialTheme.typography.titleMedium,
                color = DevPodsColor.Ink,
            )
            Spacer(modifier = Modifier.height(12.dp))
            RecoveryActionRow(
                label = "Open pairing help",
                onClick = onOpenPairingHelp,
            )
            RecoveryActionRow(
                label = "Repair microphone permission",
                onClick = onRepairMicPermission,
            )
            RecoveryActionRow(
                label = "Use phone microphone fallback",
                onClick = onUsePhoneMicFallback,
            )
        }

        // C. Permissions
        DevPodsCard(accentColor = DevPodsColor.Blue) {
            Text(
                text = "Permissions",
                style = MaterialTheme.typography.titleMedium,
                color = DevPodsColor.Ink,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = buildString {
                    append("Microphone ")
                    append(if (microphoneAllowed) "allowed" else "denied")
                    append(" \u00b7 Notifications ")
                    append(if (notificationsAllowed) "allowed" else "denied")
                    append(" \u00b7 Speech engine ")
                    append(if (speechEngineAvailable) "available" else "unavailable")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = DevPodsColor.Muted,
            )
        }

        // D. Permission modal (shown as a card)
        if (showPermissionModal) {
            DevPodsCard(accentColor = DevPodsColor.Red) {
                DevPodsChip(text = "Permission needed", style = ChipStyle.Error)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Allow microphone access",
                    style = MaterialTheme.typography.titleMedium,
                    color = DevPodsColor.Ink,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "DevPods needs microphone access to capture your command after a wake gesture. You can still pair the bridge without it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DevPodsColor.Muted,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DevPodsButton(
                        text = "Open settings",
                        onClick = onOpenSettings,
                        modifier = Modifier.weight(1f),
                    )
                    DevPodsButton(
                        text = "Not now",
                        onClick = onNotNow,
                        modifier = Modifier.weight(1f),
                        style = ButtonStyle.Ghost,
                    )
                }
            }
        }

        // E. Diagnostics preview
        DevPodsCard(accentColor = DevPodsColor.Blue) {
            Text(
                text = "Share diagnostics",
                style = MaterialTheme.typography.titleMedium,
                color = DevPodsColor.Ink,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Choose what support can see. Tokens, bridge URLs, workspace names, and identifiers stay redacted.",
                style = MaterialTheme.typography.bodyMedium,
                color = DevPodsColor.Muted,
            )
            Spacer(modifier = Modifier.height(12.dp))
            CheckboxRow(
                label = "Include phone model",
                subtitle = "Example: RMX3990, Android 16.",
                checked = diagnosticsIncludePhoneModel,
                onCheckedChange = onDiagnosticsPhoneModelChanged,
            )
            CheckboxRow(
                label = "Include capability matrix",
                subtitle = "Wake, STT, approval, battery support status.",
                checked = diagnosticsIncludeCapabilityMatrix,
                onCheckedChange = onDiagnosticsCapabilityMatrixChanged,
            )
            CheckboxRow(
                label = "Include recent error categories",
                subtitle = "Error types, not raw secrets or URLs.",
                checked = diagnosticsIncludeErrorCategories,
                onCheckedChange = onDiagnosticsErrorCategoriesChanged,
            )
            CheckboxRow(
                label = "Include raw route detail",
                subtitle = "Off by default for privacy.",
                checked = diagnosticsIncludeRawRoute,
                onCheckedChange = onDiagnosticsRawRouteChanged,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DevPodsButton(
                    text = "Share",
                    onClick = onShareDiagnostics,
                    modifier = Modifier.weight(1f),
                )
                DevPodsButton(
                    text = "Preview",
                    onClick = onPreviewDiagnostics,
                    modifier = Modifier.weight(1f),
                    style = ButtonStyle.Secondary,
                )
            }
        }

        // F. Accessibility card
        DevPodsCard(accentColor = DevPodsColor.Teal) {
            Text(
                text = "Accessibility",
                style = MaterialTheme.typography.titleMedium,
                color = DevPodsColor.Ink,
            )
            Spacer(modifier = Modifier.height(12.dp))
            AccessibilityRow(
                label = "Large text preview",
                status = "Ready",
                statusStyle = ChipStyle.Success,
            )
            AccessibilityRow(
                label = "Reduced motion",
                status = "Supported",
                statusStyle = ChipStyle.Success,
            )
            AccessibilityRow(
                label = "Screen reader labels",
                status = "Needs pass",
                statusStyle = ChipStyle.Warning,
            )
        }

        // G. Localization card
        DevPodsCard(accentColor = DevPodsColor.Blue) {
            Text(
                text = "Localization",
                style = MaterialTheme.typography.titleMedium,
                color = DevPodsColor.Ink,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DevPodsChip(text = "English", style = ChipStyle.Info)
                DevPodsChip(text = "Future locales", style = ChipStyle.Muted)
            }
        }

        // H. Version mismatch
        if (showVersionMismatch) {
            DevPodsCard(accentColor = DevPodsColor.Amber) {
                DevPodsChip(text = "Update recommended", style = ChipStyle.Warning)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Bridge is behind the app",
                    style = MaterialTheme.typography.titleMedium,
                    color = DevPodsColor.Ink,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Some relay features need bridge protocol 0.3.0 or newer.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DevPodsColor.Muted,
                )
                Spacer(modifier = Modifier.height(12.dp))
                VersionRow(label = "Android app", value = appVersion)
                VersionRow(label = "Desktop bridge", value = bridgeVersion)
                VersionRow(label = "Protocol", value = protocolStatus)
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DevPodsButton(
                        text = "Update guide",
                        onClick = onUpdateGuide,
                        modifier = Modifier.weight(1f),
                        style = ButtonStyle.Primary,
                    )
                    DevPodsButton(
                        text = "Continue",
                        onClick = onContinueAnyway,
                        modifier = Modifier.weight(1f),
                        style = ButtonStyle.Secondary,
                    )
                }
            }
        }

        // I. Recovery footer
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DevPodsButton(
                text = "Export diagnostics",
                onClick = onExportDiagnostics,
                modifier = Modifier.weight(1f),
                style = ButtonStyle.Secondary,
            )
            DevPodsButton(
                text = if (isDevModeEnabled) "Dev mode on" else "Enable Dev mode",
                onClick = onEnableDevMode,
                modifier = Modifier.weight(1f),
                style = if (isDevModeEnabled) ButtonStyle.Ghost else ButtonStyle.Primary,
            )
        }
    }
}

@Composable
private fun RecoveryActionRow(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = DevPodsColor.Teal,
        )
    }
}

@Composable
private fun CheckboxRow(
    label: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .heightIn(min = 52.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Custom checkbox matching prototype style
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (checked) DevPodsColor.Teal else DevPodsColor.Surface2)
                .border(
                    1.dp,
                    if (checked) DevPodsColor.Teal else DevPodsColor.Muted.copy(alpha = 0.3f),
                    RoundedCornerShape(8.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text(
                    text = "\u2713",
                    style = MaterialTheme.typography.labelSmall,
                    color = DevPodsColor.White,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = DevPodsColor.Ink,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = DevPodsColor.Muted,
                )
            }
        }
    }
}

@Composable
private fun AccessibilityRow(
    label: String,
    status: String,
    statusStyle: ChipStyle,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = DevPodsColor.Ink,
        )
        DevPodsChip(text = status, style = statusStyle, showDot = false)
    }
}

@Composable
private fun VersionRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = DevPodsColor.Ink,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = DevPodsColor.Muted,
        )
    }
}
