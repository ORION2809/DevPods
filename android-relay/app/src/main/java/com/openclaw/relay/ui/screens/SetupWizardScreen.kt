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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.dp
import com.openclaw.relay.SetupPhase
import com.openclaw.relay.SetupTestState
import com.openclaw.relay.ui.components.ButtonStyle
import com.openclaw.relay.ui.components.DevPodsButton
import com.openclaw.relay.ui.components.DevPodsCard
import com.openclaw.relay.ui.components.DevPodsChip
import com.openclaw.relay.ui.components.DevPodsHeroCard
import com.openclaw.relay.ui.components.DevPodsSmallButton
import com.openclaw.relay.ui.components.QueueMeter
import com.openclaw.relay.ui.components.Waveform
import com.openclaw.relay.ui.theme.DevPodsColor

@Composable
@Suppress("UNUSED_PARAMETER")
fun SetupWizardScreen(
    phase: SetupPhase,
    testState: SetupTestState = SetupTestState(),
    errorMessage: String?,
    userFacingErrorMessage: String?,
    onStartSetup: () -> Unit,
    onSkipSetup: () -> Unit,
    onScanQr: () -> Unit = {},
    onImportLink: () -> Unit = {},
    onProbeDevice: () -> Unit,
    onTestWake: () -> Unit,
    onTestStt: () -> Unit,
    onCompleteSetup: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(DevPodsColor.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Progress
        val stepNumber = when (phase) {
            SetupPhase.NOT_STARTED -> 0
            SetupPhase.PAIRING -> 1
            SetupPhase.DEVICE_PROBE -> 2
            SetupPhase.GESTURE_TEST -> 3
            SetupPhase.STT_TEST -> 4
            SetupPhase.COMPLETE -> 4
        }
        val progress = stepNumber / 4f
        QueueMeter(progress = progress, modifier = Modifier.fillMaxWidth())
        Text(
            text = if (phase == SetupPhase.COMPLETE) "Step 4 of 4" else "Step $stepNumber of 4",
            style = MaterialTheme.typography.labelMedium,
            color = DevPodsColor.Muted,
        )

        // Error banner
        if (!userFacingErrorMessage.isNullOrBlank()) {
            DevPodsCard(accentColor = DevPodsColor.Red) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Something went wrong",
                        style = MaterialTheme.typography.titleMedium,
                        color = DevPodsColor.Red,
                    )
                    Text(
                        text = userFacingErrorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = DevPodsColor.Ink2,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DevPodsSmallButton(
                            text = "Retry",
                            onClick = onRetry,
                            modifier = Modifier.weight(1f),
                        )
                        DevPodsSmallButton(
                            text = "Skip setup",
                            onClick = onSkipSetup,
                            modifier = Modifier.weight(1f),
                            style = ButtonStyle.Ghost,
                        )
                    }
                }
            }
        }

        when (phase) {
            SetupPhase.NOT_STARTED -> {
                Text(
                    text = "Device Setup",
                    style = MaterialTheme.typography.headlineMedium,
                    color = DevPodsColor.Ink,
                )
                Text(
                    text = "Let's verify your earbuds work with DevPods. This takes about one minute.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DevPodsColor.Muted,
                )

                SetupStepCard(
                    stepNumber = 1,
                    title = "Pair the bridge",
                    description = "Connect to the DevPods desktop app on your computer. You'll scan a QR code or paste a link.",
                )
                SetupStepCard(
                    stepNumber = 2,
                    title = "Probe your earbuds",
                    description = "Detect what your earbuds can do. Keep them connected via Bluetooth and in your ears.",
                )
                SetupStepCard(
                    stepNumber = 3,
                    title = "Test wake and listen",
                    description = "Tap your earbuds to wake the app, then speak a short phrase.",
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DevPodsButton(
                        text = "Start Setup",
                        onClick = onStartSetup,
                        modifier = Modifier.weight(1f),
                        style = ButtonStyle.Primary,
                    )
                    DevPodsButton(
                        text = "Skip",
                        onClick = onSkipSetup,
                        modifier = Modifier.weight(1f),
                        style = ButtonStyle.Secondary,
                    )
                }
            }

            SetupPhase.PAIRING -> {
                Text(
                    text = "Step 1 of 4: Bridge pairing",
                    style = MaterialTheme.typography.headlineSmall,
                    color = DevPodsColor.Ink,
                )
                Text(
                    text = "Make sure the desktop bridge is running on your computer and both devices are on the same Wi-Fi network. If you already imported a pairing link, tap Continue.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DevPodsColor.Muted,
                )
                Text(
                    text = "Success looks like: the status below changes to 'Pairing saved' or 'Healthy'.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DevPodsColor.Teal,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DevPodsButton(
                        text = "Scan QR",
                        onClick = onScanQr,
                        modifier = Modifier.weight(1f),
                        style = ButtonStyle.Primary,
                    )
                    DevPodsButton(
                        text = "Paste link",
                        onClick = onImportLink,
                        modifier = Modifier.weight(1f),
                        style = ButtonStyle.Secondary,
                    )
                }
                DevPodsButton(
                    text = "Continue",
                    onClick = onProbeDevice,
                    modifier = Modifier.fillMaxWidth(),
                    style = ButtonStyle.Primary,
                )
            }

            SetupPhase.DEVICE_PROBE -> {
                Text(
                    text = "Step 2 of 4: Probing earbud capabilities",
                    style = MaterialTheme.typography.headlineSmall,
                    color = DevPodsColor.Ink,
                )
                Text(
                    text = "Hold on while we detect connected earbuds and their supported gestures. Keep your earbuds in your ears and connected via Bluetooth.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DevPodsColor.Muted,
                )
                Text(
                    text = "Success looks like: the Device Status card shows your earbud model and battery level.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DevPodsColor.Teal,
                )
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    DevPodsChip(
                        text = "Probing…",
                        style = com.openclaw.relay.ui.components.ChipStyle.Info,
                    )
                }
            }

            SetupPhase.GESTURE_TEST -> {
                Text(
                    text = "Step 3 of 4: Test wake gesture",
                    style = MaterialTheme.typography.headlineSmall,
                    color = DevPodsColor.Ink,
                )
                Text(
                    text = "After starting the test, tap your earbuds (single or double tap). The app records whether the signal arrived.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DevPodsColor.Muted,
                )

                DevPodsHeroCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Waveform(isAnimating = testState.isRunning)
                        Text(
                            text = if (testState.isRunning) "00:${testState.secondsRemaining.toString().padStart(2, '0')}" else "00:10",
                            style = MaterialTheme.typography.headlineMedium,
                            color = if (testState.isRunning) DevPodsColor.Mint else DevPodsColor.Muted,
                        )
                        Text(
                            text = testState.statusLabel.ifBlank { "Tap Start, then tap your earbuds" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = DevPodsColor.Muted,
                        )
                        if (testState.providerName.isNotBlank()) {
                            Text(
                                text = "Provider: ${testState.providerName} · ${testState.confidence}",
                                style = MaterialTheme.typography.bodySmall,
                                color = DevPodsColor.Teal,
                            )
                        }
                        if (testState.mappedEvent.isNotBlank()) {
                            Text(
                                text = "Event: ${testState.mappedEvent}",
                                style = MaterialTheme.typography.bodySmall,
                                color = DevPodsColor.Teal,
                            )
                        }
                    }
                }

                if (!testState.isRunning) {
                    DevPodsButton(
                        text = "Start 10-second wake test",
                        onClick = onTestWake,
                        modifier = Modifier.fillMaxWidth(),
                        style = ButtonStyle.Primary,
                    )
                }
                DevPodsButton(
                    text = "Use assistant fallback",
                    onClick = { /* assistant fallback path */ },
                    modifier = Modifier.fillMaxWidth(),
                    style = ButtonStyle.Ghost,
                )
            }

            SetupPhase.STT_TEST -> {
                Text(
                    text = "Step 4 of 4: Test speech capture",
                    style = MaterialTheme.typography.headlineSmall,
                    color = DevPodsColor.Ink,
                )
                Text(
                    text = "After starting the test, tap your earbuds to wake the app, then say 'hello DevPods'. The test passes only if the physical tap starts the listening session.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DevPodsColor.Muted,
                )
                Text(
                    text = "Success looks like: a transcript appears and you hear a spoken response from the bridge.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DevPodsColor.Teal,
                )

                DevPodsHeroCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Waveform(isAnimating = testState.isRunning)
                        if (testState.isRunning) {
                            Text(
                                text = "00:${testState.secondsRemaining.toString().padStart(2, '0')}",
                                style = MaterialTheme.typography.headlineMedium,
                                color = DevPodsColor.Mint,
                            )
                        }
                        Text(
                            text = testState.statusLabel.ifBlank { "Tap Start, then tap and speak" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = DevPodsColor.Muted,
                        )
                        if (testState.providerName.isNotBlank()) {
                            Text(
                                text = "Provider: ${testState.providerName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = DevPodsColor.Teal,
                            )
                        }
                        if (testState.mappedEvent.isNotBlank()) {
                            Text(
                                text = "Transcript: ${testState.mappedEvent}",
                                style = MaterialTheme.typography.bodySmall,
                                color = DevPodsColor.Teal,
                            )
                        }
                    }
                }

                if (!testState.isRunning) {
                    DevPodsButton(
                        text = "Start speech test",
                        onClick = onTestStt,
                        modifier = Modifier.fillMaxWidth(),
                        style = ButtonStyle.Primary,
                    )
                }
            }

            SetupPhase.COMPLETE -> {
                Text(
                    text = "Setup complete",
                    style = MaterialTheme.typography.headlineMedium,
                    color = DevPodsColor.Teal,
                )
                Text(
                    text = "Your device profile has been saved. You can rerun setup anytime from the device card.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DevPodsColor.Muted,
                )
                DevPodsButton(
                    text = "Done",
                    onClick = onCompleteSetup,
                    modifier = Modifier.fillMaxWidth(),
                    style = ButtonStyle.Primary,
                )
            }
        }
    }
}

@Composable
private fun SetupStepCard(
    stepNumber: Int,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    DevPodsCard(
        modifier = modifier.fillMaxWidth(),
        accentColor = DevPodsColor.Teal,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(DevPodsColor.TealSoft, androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stepNumber.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = DevPodsColor.Teal,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = DevPodsColor.Ink,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = DevPodsColor.Muted,
                )
            }
        }
    }
}
