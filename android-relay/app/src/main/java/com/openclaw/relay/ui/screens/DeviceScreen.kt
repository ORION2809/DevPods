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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.openclaw.relay.RelayUiState
import com.openclaw.relay.SetupPhase
import com.openclaw.relay.device.CapabilityStatus
import com.openclaw.relay.device.DeviceCapabilityEntry
import com.openclaw.relay.isPaired
import com.openclaw.relay.ui.components.ButtonStyle
import com.openclaw.relay.ui.components.ChipStyle
import com.openclaw.relay.ui.components.DevPodsButton
import com.openclaw.relay.ui.components.DevPodsCard
import com.openclaw.relay.ui.components.DevPodsChip
import com.openclaw.relay.ui.components.DevPodsSmallButton
import com.openclaw.relay.signal.ProviderHealthUi
import com.openclaw.relay.ui.theme.DevPodsColor
import com.openclaw.relay.ui.theme.PillShape

@Composable
fun DeviceScreen(
    uiState: RelayUiState,
    onScanQr: () -> Unit,
    onImportLink: () -> Unit,
    onResumeSetup: () -> Unit,
    onSkipSetup: () -> Unit,
    onReRunSetup: () -> Unit,
    onResetSetup: () -> Unit,
    onRePair: () -> Unit,
    onForgetBridge: () -> Unit,
    onToggleBluetoothRouting: () -> Unit,
    onTogglePhoneMicFallback: () -> Unit,
    onToggleAssistantFallback: () -> Unit,
    onTestVoice: () -> Unit,
    onRepairMic: () -> Unit,
    modifier: Modifier = Modifier,
    capabilityEntry: DeviceCapabilityEntry? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Device",
            style = MaterialTheme.typography.headlineSmall,
            color = DevPodsColor.Ink,
        )

        // A. Provider status
        ProviderStatusCard(
            providerHealth = uiState.providerHealth,
            preferredProviderId = uiState.preferredProviderId,
        )

        // B. Earbuds not verified state
        if (capabilityEntry == null) {
            DevPodsCard(accentColor = DevPodsColor.Amber) {
                Text(
                    text = "Earbuds not verified yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = DevPodsColor.Ink,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Connect your earbuds, then run setup to learn which controls are dependable on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DevPodsColor.Muted,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DevPodsChip(text = "Wake unverified", style = ChipStyle.Warning)
                    DevPodsChip(text = "Speech unknown", style = ChipStyle.Muted)
                }
            }
        }

        // B. Pair your desktop bridge
        DevPodsCard(accentColor = DevPodsColor.Teal) {
            Text(
                text = "Pair your desktop bridge",
                style = MaterialTheme.typography.titleMedium,
                color = DevPodsColor.Ink,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QrCodePlaceholder()
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = if (uiState.config.isPaired()) "Paired" else "Not paired",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (uiState.config.isPaired()) DevPodsColor.Teal else DevPodsColor.Red,
                    )
                    Text(
                        text = "Bridge page reachable on same Wi-Fi or USB reverse.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DevPodsColor.Muted,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DevPodsButton(
                        text = "Scan QR",
                        onClick = onScanQr,
                        style = ButtonStyle.Primary,
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            DevPodsButton(
                text = "Import link",
                onClick = onImportLink,
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Secondary,
            )
        }

        val pairingError = uiState.userFacingErrorMessage
            ?.takeIf { it.contains("pair", ignoreCase = true) || it.contains("QR", ignoreCase = true) }
        if (pairingError != null) {
            QrScanFailureCard(
                message = pairingError,
                onScanQr = onScanQr,
                onImportLink = onImportLink,
            )
        }

        // C. Run guided setup button (prototype shows this as a standalone button)
        if (uiState.setupPhase != SetupPhase.COMPLETE) {
            DevPodsButton(
                text = "Run guided setup",
                onClick = onResumeSetup,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // D. Setup lifecycle states
        when {
            uiState.setupPhase == SetupPhase.COMPLETE -> {
                SetupCompleteCard()
            }
            uiState.setupPhase == SetupPhase.NOT_STARTED && capabilityEntry != null -> {
                SetupPausedCard(
                    onResumeSetup = onResumeSetup,
                    onSkipSetup = onSkipSetup,
                )
            }
            else -> {
                // Show setup tools when setup is in progress or not started
                SetupToolsCard(
                    onReRunSetup = onReRunSetup,
                    onResetSetup = onResetSetup,
                )
            }
        }

        // E. Capability summary
        CapabilitySummaryCard(capabilityEntry = capabilityEntry)

        // F. Bridge management
        BridgeManagementCard(
            uiState = uiState,
            onRePair = onRePair,
            onForgetBridge = onForgetBridge,
        )

        // G. Listening fallbacks
        ListeningFallbacksCard(
            bluetoothRoutingEnabled = uiState.config.useBluetoothRouting,
            phoneMicFallbackEnabled = uiState.phoneMicFallback,
            assistantFallbackEnabled = uiState.assistantFallback,
            onToggleBluetoothRouting = onToggleBluetoothRouting,
            onTogglePhoneMicFallback = onTogglePhoneMicFallback,
            onToggleAssistantFallback = onToggleAssistantFallback,
        )

        // H. Speech and output
        SpeechAndOutputCard(
            onTestVoice = onTestVoice,
            onRepairMic = onRepairMic,
        )
    }
}

@Composable
private fun QrScanFailureCard(
    message: String,
    onScanQr: () -> Unit,
    onImportLink: () -> Unit,
) {
    DevPodsCard(accentColor = DevPodsColor.Red) {
        DevPodsChip(text = "Scan failed", style = ChipStyle.Error)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = when {
                message.contains("cancel", ignoreCase = true) -> "Scan cancelled"
                message.contains("expired", ignoreCase = true) -> "Pairing code expired"
                message.contains("unreachable", ignoreCase = true) -> "Bridge unreachable"
                else -> "QR code not recognized"
            },
            style = MaterialTheme.typography.titleMedium,
            color = DevPodsColor.Ink,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = DevPodsColor.Muted,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DevPodsButton(
                text = "Scan again",
                onClick = onScanQr,
                modifier = Modifier.weight(1f),
            )
            DevPodsButton(
                text = "Paste link",
                onClick = onImportLink,
                modifier = Modifier.weight(1f),
                style = ButtonStyle.Secondary,
            )
        }
    }
}

@Composable
private fun QrCodePlaceholder() {
    Box(
        modifier = Modifier
            .size(112.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(DevPodsColor.TealSoft)
            .border(1.dp, DevPodsColor.Teal.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(3) { col ->
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(
                                    if ((row + col) % 2 == 0) DevPodsColor.Teal
                                    else DevPodsColor.TealSoft
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupPausedCard(
    onResumeSetup: () -> Unit,
    onSkipSetup: () -> Unit,
) {
    DevPodsCard(accentColor = DevPodsColor.Amber) {
        DevPodsChip(text = "Setup paused", style = ChipStyle.Warning)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Resume wake test",
            style = MaterialTheme.typography.titleMedium,
            color = DevPodsColor.Ink,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "You paired the bridge, but did not finish device verification.",
            style = MaterialTheme.typography.bodyMedium,
            color = DevPodsColor.Muted,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DevPodsButton(
                text = "Resume",
                onClick = onResumeSetup,
                modifier = Modifier.weight(1f),
            )
            DevPodsButton(
                text = "Skip for now",
                onClick = onSkipSetup,
                modifier = Modifier.weight(1f),
                style = ButtonStyle.Ghost,
            )
        }
    }
}

@Composable
private fun SetupCompleteCard() {
    DevPodsCard(accentColor = DevPodsColor.Teal) {
        DevPodsChip(text = "Setup complete", style = ChipStyle.Success)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Ready with fallback",
            style = MaterialTheme.typography.titleMedium,
            color = DevPodsColor.Ink,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Wake is observed through Android media controls. Assistant fallback stays visible until direct controls are proven.",
            style = MaterialTheme.typography.bodyMedium,
            color = DevPodsColor.Muted,
        )
    }
}

@Composable
private fun SetupToolsCard(
    onReRunSetup: () -> Unit,
    onResetSetup: () -> Unit,
) {
    DevPodsCard(accentColor = DevPodsColor.Blue) {
        Text(
            text = "Setup tools",
            style = MaterialTheme.typography.titleMedium,
            color = DevPodsColor.Ink,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Reset saved profile, re-run setup, or view capability details.",
            style = MaterialTheme.typography.bodyMedium,
            color = DevPodsColor.Muted,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DevPodsSmallButton(
                text = "Re-run",
                onClick = onReRunSetup,
                style = ButtonStyle.Secondary,
            )
            DevPodsSmallButton(
                text = "Reset",
                onClick = onResetSetup,
                style = ButtonStyle.Danger,
            )
        }
    }
}

@Composable
private fun CapabilitySummaryCard(capabilityEntry: DeviceCapabilityEntry?) {
    DevPodsCard(accentColor = DevPodsColor.Teal) {
        Text(
            text = "Capability summary",
            style = MaterialTheme.typography.titleMedium,
            color = DevPodsColor.Ink,
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (capabilityEntry == null) {
            Text(
                text = "No capability data yet. Run setup to probe your device.",
                style = MaterialTheme.typography.bodyMedium,
                color = DevPodsColor.Muted,
            )
        } else {
            CapabilityRow(
                label = "Wake from earbuds",
                status = capabilityEntry.wakeGesture,
            )
            CapabilityRow(
                label = "Interrupt during reply",
                status = capabilityEntry.interruptGesture,
            )
            CapabilityRow(
                label = "Approve by gesture",
                status = capabilityEntry.approveRejectGesture,
            )
            CapabilityRow(
                label = "In-ear detection",
                status = capabilityEntry.earDetection,
            )
            CapabilityRow(
                label = "Battery reporting",
                status = capabilityEntry.batteryStatus,
            )
            CapabilityRow(
                label = "Speech after wake",
                status = capabilityEntry.sttAfterWake,
            )
        }
    }
}

@Composable
private fun CapabilityRow(
    label: String,
    status: CapabilityStatus,
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
        when (status) {
            CapabilityStatus.PROVEN, CapabilityStatus.OBSERVED -> {
                DevPodsChip(text = "Observed", style = ChipStyle.Warning, showDot = false)
            }
            CapabilityStatus.UNPROVEN -> {
                DevPodsChip(text = "Not yet proven", style = ChipStyle.Muted, showDot = false)
            }
            CapabilityStatus.UNSUPPORTED -> {
                DevPodsChip(text = "Unsupported", style = ChipStyle.Error, showDot = false)
            }
        }
    }
}

@Composable
private fun BridgeManagementCard(
    uiState: RelayUiState,
    onRePair: () -> Unit,
    onForgetBridge: () -> Unit,
) {
    val config = uiState.config
    if (config.isPaired()) {
        DevPodsCard {
            Text(
                text = "Paired bridge",
                style = MaterialTheme.typography.titleMedium,
                color = DevPodsColor.Ink,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Desktop bridge at ${config.bridgeBaseUrl}. Last health check succeeded recently.",
                style = MaterialTheme.typography.bodyMedium,
                color = DevPodsColor.Muted,
            )
            Spacer(modifier = Modifier.height(8.dp))
            BridgeDetailRow(
                label = "Bridge running",
                value = if (uiState.bridgeStatus.startsWith("Healthy", ignoreCase = true)) "OK" else "Check",
                isGood = uiState.bridgeStatus.startsWith("Healthy", ignoreCase = true),
            )
            BridgeDetailRow(
                label = "Same network",
                value = "Reachable",
                isGood = true,
            )
            BridgeDetailRow(
                label = "Protocol version",
                value = uiState.bridgeStatus,
                isGood = true,
                isChip = true,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DevPodsButton(
                    text = "Re-pair",
                    onClick = onRePair,
                    modifier = Modifier.weight(1f),
                    style = ButtonStyle.Secondary,
                )
                DevPodsButton(
                    text = "Forget",
                    onClick = onForgetBridge,
                    modifier = Modifier.weight(1f),
                    style = ButtonStyle.Danger,
                )
            }
        }
    } else {
        DevPodsCard(accentColor = DevPodsColor.Amber) {
            Text(
                text = "Pairing issue",
                style = MaterialTheme.typography.titleMedium,
                color = DevPodsColor.Ink,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "This pairing code expired. Re-open the desktop pairing page and scan again.",
                style = MaterialTheme.typography.bodyMedium,
                color = DevPodsColor.Muted,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DevPodsButton(
                    text = "Re-pair",
                    onClick = onRePair,
                    modifier = Modifier.weight(1f),
                )
                DevPodsButton(
                    text = "Forget",
                    onClick = onForgetBridge,
                    modifier = Modifier.weight(1f),
                    style = ButtonStyle.Danger,
                )
            }
        }
    }
}

@Composable
private fun BridgeDetailRow(
    label: String,
    value: String,
    isGood: Boolean,
    isChip: Boolean = false,
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
        if (isChip) {
            DevPodsChip(
                text = value,
                style = if (isGood) ChipStyle.Success else ChipStyle.Warning,
                showDot = false,
            )
        } else {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isGood) DevPodsColor.Teal else DevPodsColor.Red,
            )
        }
    }
}

@Composable
private fun ListeningFallbacksCard(
    bluetoothRoutingEnabled: Boolean,
    phoneMicFallbackEnabled: Boolean,
    assistantFallbackEnabled: Boolean,
    onToggleBluetoothRouting: () -> Unit,
    onTogglePhoneMicFallback: () -> Unit,
    onToggleAssistantFallback: () -> Unit,
) {
    DevPodsCard {
        Text(
            text = "Listening fallbacks",
            style = MaterialTheme.typography.titleMedium,
            color = DevPodsColor.Ink,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Use these when Bluetooth routing or direct wake is unreliable.",
            style = MaterialTheme.typography.bodyMedium,
            color = DevPodsColor.Muted,
        )
        Spacer(modifier = Modifier.height(12.dp))
        ToggleRow(
            label = "Bluetooth mic routing",
            subtitle = "Prefer earbud microphone when available.",
            enabled = bluetoothRoutingEnabled,
            onToggle = onToggleBluetoothRouting,
        )
        ToggleRow(
            label = "Phone microphone fallback",
            subtitle = "Use phone mic if headset route fails.",
            enabled = phoneMicFallbackEnabled,
            onToggle = onTogglePhoneMicFallback,
        )
        ToggleRow(
            label = "Assistant long-press fallback",
            subtitle = "Open DevPods through the system assistant gesture.",
            enabled = assistantFallbackEnabled,
            onToggle = onToggleAssistantFallback,
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    subtitle: String? = null,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .heightIn(min = 52.dp)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        Box(
            modifier = Modifier
                .width(48.dp)
                .height(30.dp)
                .clip(PillShape)
                .background(if (enabled) DevPodsColor.Teal.copy(alpha = 0.18f) else DevPodsColor.Surface2)
                .border(
                    1.dp,
                    DevPodsColor.White.copy(alpha = 0.78f),
                    PillShape,
                ),
            contentAlignment = if (enabled) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .padding(3.dp)
                    .size(22.dp)
                    .clip(PillShape)
                    .background(if (enabled) DevPodsColor.Teal else DevPodsColor.Muted)
                    .shadow(4.dp, PillShape),
            )
        }
    }
}

@Composable
private fun ProviderStatusCard(
    providerHealth: List<ProviderHealthUi>,
    preferredProviderId: String?,
) {
    DevPodsCard(accentColor = DevPodsColor.Teal) {
        Text(
            text = "Earbud providers",
            style = MaterialTheme.typography.titleMedium,
            color = DevPodsColor.Ink,
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (providerHealth.isEmpty()) {
            Text(
                text = "No providers active yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = DevPodsColor.Muted,
            )
        } else {
            providerHealth.forEach { health ->
                val isPreferred = health.providerId == preferredProviderId
                ProviderHealthRow(health, isPreferred)
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun ProviderHealthRow(
    health: ProviderHealthUi,
    isPreferred: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = health.providerLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = DevPodsColor.Ink,
                )
                if (isPreferred) {
                    Spacer(modifier = Modifier.width(6.dp))
                    DevPodsChip(text = "Active", style = ChipStyle.Success, showDot = false)
                }
            }
            if (health.deviceName != null) {
                Text(
                    text = health.deviceName + if (health.isConnected) " · Connected" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = DevPodsColor.Muted,
                )
            }
            if (health.lastError != null) {
                Text(
                    text = health.lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = DevPodsColor.Red,
                )
            }
        }
        val chipStyle = when (health.status) {
            "running" -> ChipStyle.Success
            "blocked_permission" -> ChipStyle.Warning
            "failed" -> ChipStyle.Error
            else -> ChipStyle.Muted
        }
        DevPodsChip(
            text = health.status.replace("_", " "),
            style = chipStyle,
            showDot = false,
        )
    }
}

@Composable
private fun SpeechAndOutputCard(
    onTestVoice: () -> Unit,
    onRepairMic: () -> Unit,
) {
    DevPodsCard(accentColor = DevPodsColor.Blue) {
        Text(
            text = "Speech and output",
            style = MaterialTheme.typography.titleMedium,
            color = DevPodsColor.Ink,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Test speaker, change voice route, and repair speech engine availability.",
            style = MaterialTheme.typography.bodyMedium,
            color = DevPodsColor.Muted,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DevPodsButton(
                text = "Test voice",
                onClick = onTestVoice,
                modifier = Modifier.weight(1f),
                style = ButtonStyle.Secondary,
            )
            DevPodsButton(
                text = "Repair mic",
                onClick = onRepairMic,
                modifier = Modifier.weight(1f),
                style = ButtonStyle.Secondary,
            )
        }
    }
}
