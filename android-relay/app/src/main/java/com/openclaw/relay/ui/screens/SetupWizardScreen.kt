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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.dp
import com.openclaw.relay.DiscoveredBridge
import com.openclaw.relay.RelayConfig
import com.openclaw.relay.SetupPhase
import com.openclaw.relay.SetupTestState
import com.openclaw.relay.calibration.CalibrationSessionState
import com.openclaw.relay.calibration.CalibrationSessionStatus
import com.openclaw.relay.calibration.CalibrationConfidence
import com.openclaw.relay.calibration.EarbudCalibrationProfile
import com.openclaw.relay.calibration.GestureAction
import com.openclaw.relay.calibration.GestureActionMap
import com.openclaw.relay.signal.GestureType
import com.openclaw.relay.isPaired
import com.openclaw.relay.ui.components.ButtonStyle
import com.openclaw.relay.ui.components.DevPodsAppIconTile
import com.openclaw.relay.ui.components.DevPodsButton
import com.openclaw.relay.ui.components.DevPodsCard
import com.openclaw.relay.ui.components.DevPodsChip
import com.openclaw.relay.ui.components.DevPodsHeroCard
import com.openclaw.relay.ui.components.DevPodsMarkTealDark
import com.openclaw.relay.ui.components.DevPodsSmallButton
import com.openclaw.relay.ui.components.SetupProgressBar
import com.openclaw.relay.ui.components.Waveform
import com.openclaw.relay.ui.theme.DevPodsColor
import com.openclaw.relay.ui.theme.DevPodsSpacing
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

@Composable
@Suppress("UNUSED_PARAMETER")
fun SetupWizardScreen(
    phase: SetupPhase,
    testState: SetupTestState = SetupTestState(),
    bridgeStatus: String = "Unknown",
    config: RelayConfig = RelayConfig(),
    errorMessage: String?,
    userFacingErrorMessage: String?,
    discoveredBridges: List<DiscoveredBridge> = emptyList(),
    isDiscovering: Boolean = false,
    onStartSetup: () -> Unit,
    onSkipSetup: () -> Unit,
    onScanQr: () -> Unit = {},
    onImportLink: () -> Unit = {},
    onSelectDiscoveredBridge: (DiscoveredBridge) -> Unit = {},
    onProbeDevice: () -> Unit,
    onTestWake: () -> Unit,
    onTestStt: () -> Unit,
    onCompleteSetup: () -> Unit,
    onRetry: () -> Unit,
    calibrationSession: CalibrationSessionState? = null,
    calibrationProfile: com.openclaw.relay.calibration.EarbudCalibrationProfile? = null,
    onSkipCalibration: () -> Unit = {},
    onFinishCalibration: () -> Unit = {},
    onGestureActionSelected: (com.openclaw.relay.signal.GestureType, com.openclaw.relay.calibration.GestureAction) -> Unit = { _, _ -> },
    onCompleteGestureMapping: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DevPodsSpacing.screenX, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Progress — normalized to 4 steps with solid teal track
        // Step 1: Pairing / Quick Start
        // Step 2: Device Probe
        // Step 3: Calibration + Gesture Mapping + Gesture Test
        // Step 4: STT Test + Complete
        val stepNumber = when (phase) {
            SetupPhase.NOT_STARTED -> 0
            SetupPhase.PAIRING -> 1
            SetupPhase.QUICK_START -> 1
            SetupPhase.DEVICE_PROBE -> 2
            SetupPhase.CALIBRATION -> 3
            SetupPhase.GESTURE_MAPPING -> 3
            SetupPhase.GESTURE_TEST -> 3
            SetupPhase.STT_TEST -> 4
            SetupPhase.COMPLETE_PROVEN -> 4
            SetupPhase.COMPLETE_DEGRADED -> 4
        }
        val progress = stepNumber / 4f
        SetupProgressBar(progress = progress, modifier = Modifier.fillMaxWidth())
        Text(
            text = "Step $stepNumber of 4",
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
                        modifier = Modifier.semantics { heading() },
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
            SetupPhase.QUICK_START -> {
                Text(
                    text = "Quick Start",
                    style = MaterialTheme.typography.headlineSmall,
                    color = DevPodsColor.Ink,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = "Your DevPods bridge is connected. You can use read-only commands and push-to-talk right away. Full gesture control requires calibration.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DevPodsColor.Muted,
                )
                DevPodsButton(
                    text = "Continue",
                    onClick = onProbeDevice,
                    modifier = Modifier.fillMaxWidth(),
                    style = ButtonStyle.Primary,
                )
            }
            SetupPhase.NOT_STARTED -> {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    DevPodsAppIconTile(
                        modifier = Modifier.size(84.dp),
                    )
                }
                Text(
                    text = "Device Setup",
                    style = MaterialTheme.typography.headlineMedium,
                    color = DevPodsColor.Ink,
                    modifier = Modifier.semantics { heading() },
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
                    title = "Calibrate gestures",
                    description = "Learn how your specific earbuds send signals so DevPods can reliably detect taps, holds, and presses.",
                )
                SetupStepCard(
                    stepNumber = 4,
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
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = "Make sure the desktop bridge is running on your computer and both devices are on the same Wi-Fi network. If you already imported a pairing link, tap Continue.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DevPodsColor.Muted,
                )

                // Live status indicator — shows current pairing/bridge state
                val statusLabel = when {
                    bridgeStatus.startsWith("Healthy", ignoreCase = true) ->
                        bridgeStatus
                    bridgeStatus.startsWith("Pairing saved", ignoreCase = true) ->
                        bridgeStatus
                    config.isPaired() ->
                        "Paired · ${config.bridgeBaseUrl}"
                    else ->
                        "Not paired"
                }
                val statusChipStyle = when {
                    bridgeStatus.startsWith("Healthy", ignoreCase = true) ->
                        com.openclaw.relay.ui.components.ChipStyle.Success
                    bridgeStatus.startsWith("Pairing saved", ignoreCase = true) ->
                        com.openclaw.relay.ui.components.ChipStyle.Success
                    config.isPaired() ->
                        com.openclaw.relay.ui.components.ChipStyle.Info
                    else ->
                        com.openclaw.relay.ui.components.ChipStyle.Muted
                }
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    DevPodsChip(
                        text = statusLabel,
                        style = statusChipStyle,
                    )
                }

                Text(
                    text = if (config.isPaired()) {
                        "Success: your bridge is connected. Tap Continue to proceed."
                    } else {
                        "Import a pairing link to connect, or discover nearby bridges on your network."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (config.isPaired()) DevPodsColor.Teal else DevPodsColor.Muted,
                )

                // Discovered bridges from mDNS
                if (!config.isPaired() && discoveredBridges.isNotEmpty()) {
                    Text(
                        text = "Found on this network",
                        style = MaterialTheme.typography.labelLarge,
                        color = DevPodsColor.Ink,
                    )
                    for (bridge in discoveredBridges) {
                        DevPodsCard(accentColor = DevPodsColor.Teal) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = bridge.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = DevPodsColor.Ink,
                                )
                                Text(
                                    text = bridge.pairingBaseUrl ?: "${bridge.host}:${bridge.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = DevPodsColor.Muted,
                                )
                                DevPodsSmallButton(
                                    text = "Connect",
                                    onClick = { onSelectDiscoveredBridge(bridge) },
                                    style = ButtonStyle.Primary,
                                )
                            }
                        }
                    }
                }

                if (!config.isPaired() && isDiscovering && discoveredBridges.isEmpty()) {
                    Text(
                        text = "Searching for DevPods bridges on your network...",
                        style = MaterialTheme.typography.bodySmall,
                        color = DevPodsColor.Muted,
                    )
                }

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
                    text = if (config.isPaired()) "Continue" else "Skip for now",
                    onClick = onProbeDevice,
                    modifier = Modifier.fillMaxWidth(),
                    style = if (config.isPaired()) ButtonStyle.Primary else ButtonStyle.Ghost,
                )
            }

            SetupPhase.DEVICE_PROBE -> {
                Text(
                    text = "Step 2 of 4: Probing earbud capabilities",
                    style = MaterialTheme.typography.headlineSmall,
                    color = DevPodsColor.Ink,
                    modifier = Modifier.semantics { heading() },
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

            SetupPhase.CALIBRATION -> {
                Text(
                    text = "Step 3 of 4: Calibrate your earbuds",
                    style = MaterialTheme.typography.headlineSmall,
                    color = DevPodsColor.Ink,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = "Tap or press your earbuds when prompted. DevPods learns the exact signal pattern so it can reliably detect your gestures.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DevPodsColor.Muted,
                )

                val session = calibrationSession
                val gestureLabel = when (session?.requestedGesture) {
                    com.openclaw.relay.signal.GestureType.SINGLE_PRESS -> "Single-tap your earbud"
                    com.openclaw.relay.signal.GestureType.DOUBLE_PRESS -> "Double-tap your earbud"
                    com.openclaw.relay.signal.GestureType.TRIPLE_PRESS -> "Triple-tap your earbud"
                    com.openclaw.relay.signal.GestureType.LONG_PRESS -> "Long-press your earbud"
                    else -> "Perform the gesture"
                }

                val statusLabel = when (session?.status) {
                    CalibrationSessionStatus.WAITING -> "Listening…"
                    CalibrationSessionStatus.DETECTED -> "Detected!"
                    CalibrationSessionStatus.RETRY -> "Try again"
                    CalibrationSessionStatus.TIMEOUT -> "No signal detected. Check your earbuds are connected."
                    CalibrationSessionStatus.UNSUPPORTED -> "Gesture not detected"
                    CalibrationSessionStatus.AMBIGUOUS -> "Ambiguous signal"
                    CalibrationSessionStatus.COMPLETE -> "Calibration complete"
                    null -> "Getting ready…"
                }

                val statusColor = when (session?.status) {
                    CalibrationSessionStatus.WAITING -> DevPodsColor.Mint
                    CalibrationSessionStatus.DETECTED -> DevPodsColor.Teal
                    CalibrationSessionStatus.COMPLETE -> DevPodsColor.Teal
                    CalibrationSessionStatus.RETRY -> DevPodsColor.Amber
                    CalibrationSessionStatus.TIMEOUT -> DevPodsColor.Red
                    CalibrationSessionStatus.UNSUPPORTED -> DevPodsColor.Red
                    CalibrationSessionStatus.AMBIGUOUS -> DevPodsColor.Amber
                    null -> DevPodsColor.Muted
                }

                DevPodsCard(accentColor = statusColor) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = gestureLabel,
                            style = MaterialTheme.typography.titleMedium,
                            color = DevPodsColor.Ink,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            text = "Attempt ${session?.attemptNumber ?: 1} of 3",
                            style = MaterialTheme.typography.bodySmall,
                            color = DevPodsColor.Muted,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(statusColor, CircleShape),
                            )
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = DevPodsColor.Ink,
                            )
                        }
                    }
                }

                if (session?.status == CalibrationSessionStatus.COMPLETE) {
                    DevPodsButton(
                        text = "Continue",
                        onClick = onFinishCalibration,
                        modifier = Modifier.fillMaxWidth(),
                        style = ButtonStyle.Primary,
                    )
                }
            }

            SetupPhase.GESTURE_MAPPING -> {
                Text(
                    text = "Step 3 of 4: Map gestures to actions",
                    style = MaterialTheme.typography.headlineSmall,
                    color = DevPodsColor.Ink,
                    modifier = Modifier.semantics { heading() },
                )
                val profile = calibrationProfile
                val confidenceLabel = if (profile?.routeProof?.isSuccess == true) "proven" else "detected"
                Text(
                    text = "Assign what each $confidenceLabel gesture does. You can change these later in Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DevPodsColor.Muted,
                )

                GestureMappingCard(
                    profile = calibrationProfile,
                    onActionSelected = onGestureActionSelected,
                )

                Spacer(modifier = Modifier.height(8.dp))

                DevPodsButton(
                    text = "Continue",
                    onClick = onCompleteGestureMapping,
                    modifier = Modifier.fillMaxWidth(),
                    style = ButtonStyle.Primary,
                )
            }

            SetupPhase.GESTURE_TEST -> {
                Text(
                    text = "Step 3 of 4: Test wake gesture",
                    style = MaterialTheme.typography.headlineSmall,
                    color = DevPodsColor.Ink,
                    modifier = Modifier.semantics { heading() },
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
            }

            SetupPhase.STT_TEST -> {
                Text(
                    text = "Step 4 of 4: Test speech capture",
                    style = MaterialTheme.typography.headlineSmall,
                    color = DevPodsColor.Ink,
                    modifier = Modifier.semantics { heading() },
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

            SetupPhase.COMPLETE_PROVEN -> {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    DevPodsMarkTealDark(
                        modifier = Modifier.size(88.dp),
                    )
                }
                Text(
                    text = "Setup complete",
                    style = MaterialTheme.typography.headlineMedium,
                    color = DevPodsColor.Teal,
                    modifier = Modifier.semantics { heading() },
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

            SetupPhase.COMPLETE_DEGRADED -> {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    DevPodsMarkTealDark(
                        modifier = Modifier.size(88.dp),
                    )
                }
                Text(
                    text = "Setup incomplete",
                    style = MaterialTheme.typography.headlineMedium,
                    color = DevPodsColor.Amber,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = "Some proof steps failed. A degraded profile was saved. You can still use DevPods, but reliability depends on fallback paths. Rerun setup after fixing the issue.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DevPodsColor.Muted,
                )
                DevPodsButton(
                    text = "Continue anyway",
                    onClick = onCompleteSetup,
                    modifier = Modifier.fillMaxWidth(),
                    style = ButtonStyle.Primary,
                )
                DevPodsButton(
                    text = "Retry failed steps",
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                    style = ButtonStyle.Ghost,
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
        modifier = modifier.fillMaxWidth().semantics { contentDescription = "Step $stepNumber, $title" },
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

@Composable
private fun GestureMappingCard(
    profile: EarbudCalibrationProfile?,
    onActionSelected: (GestureType, GestureAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val provenGestures = profile?.calibratedGestures?.filter {
        it.confidence == CalibrationConfidence.PROVEN
    } ?: emptyList()

    if (provenGestures.isEmpty()) {
        Text(
            text = "No proven gestures to map. Go back and retry calibration.",
            style = MaterialTheme.typography.bodyMedium,
            color = DevPodsColor.Muted,
        )
        return
    }

    val actions = listOf(
        GestureAction.WAKE_AND_LISTEN to "Wake & Listen",
        GestureAction.INTERRUPT to "Interrupt",
        GestureAction.APPROVE to "Approve",
        GestureAction.REJECT to "Reject",
        GestureAction.NONE to "No Action",
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        provenGestures.forEach { calibratedGesture ->
            val gestureType = calibratedGesture.requestedGesture
            val gestureLabel = when (gestureType) {
                GestureType.SINGLE_PRESS -> "Single Tap"
                GestureType.DOUBLE_PRESS -> "Double Tap"
                GestureType.TRIPLE_PRESS -> "Triple Tap"
                GestureType.LONG_PRESS -> "Long Press"
                else -> gestureType.name
            }
            val currentAction = profile?.gestureActionMap?.actionFor(gestureType)
            DevPodsCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = gestureLabel,
                        style = MaterialTheme.typography.titleSmall,
                        color = DevPodsColor.Ink,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        actions.forEach { (action, label) ->
                            val isSelected = currentAction == action
                            DevPodsSmallButton(
                                text = label,
                                onClick = { onActionSelected(gestureType, action) },
                                modifier = Modifier.weight(1f),
                                style = if (isSelected) ButtonStyle.Primary else ButtonStyle.Secondary,
                            )
                        }
                    }
                }
            }
        }
    }
}
