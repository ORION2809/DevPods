package com.openclaw.relay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.openclaw.relay.device.DeviceCapabilityEntry
import com.openclaw.relay.ui.components.BottomNav
import com.openclaw.relay.ui.components.DevPodsScreenShell
import com.openclaw.relay.ui.components.DevPodsTab
import com.openclaw.relay.ui.screens.ActivityScreen
import com.openclaw.relay.ui.screens.DeveloperModeScreen
import com.openclaw.relay.ui.screens.DeviceScreen
import com.openclaw.relay.ui.screens.HelpScreen
import com.openclaw.relay.ui.screens.HomeScreen
import com.openclaw.relay.ui.screens.OnboardingScreen
import com.openclaw.relay.ui.screens.SettingsScreen
import com.openclaw.relay.ui.screens.SetupWizardScreen
import com.openclaw.relay.ui.theme.DevPodsColor
import com.openclaw.relay.diagnostic.DiagnosticExportOptions
import com.openclaw.relay.ui.theme.DevPodsTheme

// EXPECTED_PROTOCOL_VERSION moved to RelayModels.kt for shared access

class MainActivity : ComponentActivity() {
    private val relayViewModel: RelayViewModel by viewModels()
    private var pendingAutomationIntent: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        relayViewModel.initialize(this)
        pendingAutomationIntent = intent
        consumePairingDataIntent(intent)

        setContent {
            // TODO: Enable dark mode after refactoring ~180 hardcoded DevPodsColor.* references
            // across all screens to use MaterialTheme.colorScheme.*. See docs/50-android-ui-deep-dive-audit.md
            DevPodsTheme(darkTheme = false) {
                RelayApp(
                    relayViewModel = relayViewModel,
                    buildRuntimePermissions = ::buildRuntimePermissions,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingAutomationIntent = intent
        consumePairingDataIntent(intent)
        dispatchPendingAutomationIntent()
    }

    /**
     * Clear any stale pairing intent so a future deep-link import starts fresh.
     * Called after [consumePairingDataIntent] has processed a pairing link.
     */
    private fun clearPendingPairingIntent() {
        pendingAutomationIntent = null
    }

    override fun onResume() {
        super.onResume()
        dispatchPendingAutomationIntent()
    }

    private fun consumePairingDataIntent(intent: Intent?) {
        val data = intent?.dataString?.trim()?.takeIf { it.isNotBlank() } ?: return
        val isPairingIntent = data.startsWith("devpods://pair", ignoreCase = true) ||
            data.contains("/pairing", ignoreCase = true)
        if (!isPairingIntent) return

        try {
            relayViewModel.importPairingUri(this, data)
            Log.i("OpenClawRelay", "Consumed pairing intent: ${data.take(80)}")
        } catch (e: Exception) {
            Log.e("OpenClawRelay", "Failed to import pairing intent", e)
            relayViewModel.reportPairingScanError("Could not import pairing link. Please paste it manually.")
        } finally {
            // Always clear the intent data so it is never re-processed, and clear
            // pendingAutomationIntent so dispatchPendingAutomationIntent() stops
            // re-evaluating it on every onResume.
            intent.setData(null)
            clearPendingPairingIntent()
        }
    }

    private fun buildRuntimePermissions(): Array<String> {
        return buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    private fun dispatchPendingAutomationIntent() {
        val automationIntent = pendingAutomationIntent ?: return
        val serviceAction = automationIntent.getStringExtra(RelayService.EXTRA_SERVICE_ACTION) ?: return

        if (!isAutomationEnabled()) {
            clearAutomationExtras(automationIntent)
            pendingAutomationIntent = null
            return
        }

        relayViewModel.dispatchAutomationAction(
            context = this,
            serviceAction = serviceAction,
            bridgeBaseUrl = automationIntent.getStringExtra(RelayService.EXTRA_BRIDGE_BASE_URL),
            relayToken = automationIntent.getStringExtra(RelayService.EXTRA_RELAY_TOKEN),
            workspace = automationIntent.getStringExtra(RelayService.EXTRA_WORKSPACE),
            trigger = automationIntent.getStringExtra(RelayService.EXTRA_TRIGGER),
            eventName = automationIntent.getStringExtra(RelayService.EXTRA_EVENT_NAME),
            utterance = automationIntent.getStringExtra(RelayService.EXTRA_UTTERANCE),
            pendingActionId = automationIntent.getStringExtra(RelayService.EXTRA_PENDING_ACTION_ID),
        )

        clearAutomationExtras(automationIntent)
        pendingAutomationIntent = null
    }

    private fun isAutomationEnabled(): Boolean {
        return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun clearAutomationExtras(intent: Intent) {
        intent.removeExtra(RelayService.EXTRA_SERVICE_ACTION)
        intent.removeExtra(RelayService.EXTRA_BRIDGE_BASE_URL)
        intent.removeExtra(RelayService.EXTRA_RELAY_TOKEN)
        intent.removeExtra(RelayService.EXTRA_WORKSPACE)
        intent.removeExtra(RelayService.EXTRA_TRIGGER)
        intent.removeExtra(RelayService.EXTRA_EVENT_NAME)
        intent.removeExtra(RelayService.EXTRA_UTTERANCE)
        intent.removeExtra(RelayService.EXTRA_PENDING_ACTION_ID)
        setIntent(intent)
    }
}

@Composable
private fun RelayApp(
    relayViewModel: RelayViewModel,
    buildRuntimePermissions: () -> Array<String>,
) {
    val state by relayViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var selectedTab by rememberSaveable { mutableStateOf(DevPodsTab.Home) }
    var isDevMode by rememberSaveable { mutableStateOf(false) }
    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    var importLink by rememberSaveable { mutableStateOf(state.pendingPairingUri) }
    var diagnosticsExported by rememberSaveable { mutableStateOf(false) }
    var queuedActionsSent by rememberSaveable { mutableStateOf(false) }
    var diagnosticsIncludePhoneModel by rememberSaveable { mutableStateOf(true) }
    var diagnosticsIncludeCapabilityMatrix by rememberSaveable { mutableStateOf(true) }
    var diagnosticsIncludeErrorCategories by rememberSaveable { mutableStateOf(true) }
    var diagnosticsIncludeRawRoute by rememberSaveable { mutableStateOf(false) }
    var permissionRefreshTick by remember { mutableStateOf(0) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showApprovalDetail by rememberSaveable { mutableStateOf(false) }
    var showRemoteBridgeDialog by rememberSaveable { mutableStateOf(false) }
    var remoteBridgeUrl by rememberSaveable { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionRefreshTick++
    }

    val qrScannerLauncher = rememberLauncherForActivityResult(
        contract = ScanContract(),
    ) { result ->
        val contents = result.contents?.trim()
        if (contents.isNullOrBlank()) {
            relayViewModel.reportPairingScanError("QR scan cancelled or no code was detected. Try scanning again or paste the pairing link.")
        } else {
            relayViewModel.importPairingUri(context, contents)
            selectedTab = DevPodsTab.Device
        }
    }

    val requestPermissions = {
        permissionLauncher.launch(buildRuntimePermissions())
    }
    val scanQr = {
        qrScannerLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan the DevPods bridge QR")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .setBarcodeImageEnabled(false),
        )
    }
    val openImportDialog = {
        importLink = state.pendingPairingUri
        showImportDialog = true
    }

    LaunchedEffect(isDevMode) {
        if (!isDevMode && selectedTab == DevPodsTab.Dev) {
            selectedTab = DevPodsTab.Home
        }
    }

    LaunchedEffect(Unit) {
        relayViewModel.loadNotificationPreferences()
        relayViewModel.loadReminders()
        relayViewModel.loadLearnedPhrases()
        relayViewModel.loadNudgePolicy()
    }

    if (state.showOnboarding) {
        DevPodsScreenShell(
            isDevMode = false,
            note = "First launch \u00b7 Product promise before configuration.",
        ) { _ ->
            OnboardingScreen(
                onDismiss = {
                    relayViewModel.dismissOnboarding(context)
                    relayViewModel.startSetup(context)
                },
                onSkip = {
                    relayViewModel.dismissOnboarding(context)
                },
            )
        }
        return
    }

    if (state.showSetupWizard) {
        DevPodsScreenShell(
            isDevMode = false,
            note = "Setup \u00b7 Guided verification",
        ) { _ ->
            SetupWizardScreen(
                phase = state.setupPhase,
                testState = state.setupTestState,
                bridgeStatus = state.bridgeStatus,
                config = state.config,
                errorMessage = state.errorMessage,
                userFacingErrorMessage = state.userFacingErrorMessage,
                discoveredBridges = state.discoveredBridges,
                isDiscovering = state.isDiscovering,
                onStartSetup = { relayViewModel.startSetup(context) },
                onSkipSetup = {
                    relayViewModel.skipSetup(context)
                    selectedTab = DevPodsTab.Home
                },
                onScanQr = scanQr,
                onImportLink = openImportDialog,
                onSelectDiscoveredBridge = { bridge: com.openclaw.relay.DiscoveredBridge ->
                    relayViewModel.selectDiscoveredBridge(context, bridge)
                },
                onProbeDevice = { relayViewModel.probeDevice(context) },
                onTestWake = { relayViewModel.testWake(context) },
                onTestStt = { relayViewModel.testStt(context) },
                onCompleteSetup = {
                    relayViewModel.completeSetup(context)
                    selectedTab = DevPodsTab.Home
                },
                onRetry = { retrySetupPhase(state.setupPhase, relayViewModel, context) },
                calibrationSession = state.calibrationSession,
                calibrationProfile = state.calibrationProfile,
                onSkipCalibration = { relayViewModel.skipCalibration() },
                onFinishCalibration = { relayViewModel.finishCalibration(context) },
                onGestureActionSelected = { gestureType, action ->
                    relayViewModel.setGestureAction(gestureType, action)
                },
                onCompleteGestureMapping = {
                    relayViewModel.completeGestureMapping(context)
                },
            )
        }
        if (showImportDialog) {
            PairingImportDialog(
                value = importLink,
                onValueChange = { importLink = it },
                onDismiss = { showImportDialog = false },
                onImport = {
                    relayViewModel.importPairingUri(context, importLink)
                    showImportDialog = false
                },
            )
        }
        if (showRemoteBridgeDialog) {
            RemoteBridgeDialog(
                value = remoteBridgeUrl,
                onValueChange = { remoteBridgeUrl = it },
                onDismiss = { showRemoteBridgeDialog = false },
                onImport = {
                    relayViewModel.importPrivateNetworkBridge(context, remoteBridgeUrl)
                    showRemoteBridgeDialog = false
                },
            )
        }
        return
    }

    RelayAppShell(
        state = state,
        selectedTab = selectedTab,
        isDevMode = isDevMode,
        diagnosticsExported = diagnosticsExported,
        queuedActionsSent = queuedActionsSent,
        diagnosticsIncludePhoneModel = diagnosticsIncludePhoneModel,
        diagnosticsIncludeCapabilityMatrix = diagnosticsIncludeCapabilityMatrix,
        diagnosticsIncludeErrorCategories = diagnosticsIncludeErrorCategories,
        diagnosticsIncludeRawRoute = diagnosticsIncludeRawRoute,
        permissionRefreshTick = permissionRefreshTick,
        onTabSelected = { selectedTab = it },
        onRequestPermissions = requestPermissions,
        onPairBridge = { selectedTab = DevPodsTab.Device },
        onScanQr = scanQr,
        onImportLink = openImportDialog,
        onResumeSetup = { relayViewModel.startSetup(context) },
        onSkipSetup = { relayViewModel.skipSetup(context) },
        onReRunSetup = { relayViewModel.startSetup(context) },
        onResetSetup = { relayViewModel.resetSetup(context) },
        onRePair = scanQr,
        onForgetBridge = { relayViewModel.forgetBridge(context) },
        onToggleBluetoothRouting = {
            relayViewModel.updateBluetoothRouting(context, !state.config.useBluetoothRouting)
        },
        onTogglePhoneMicFallback = {
            relayViewModel.updatePhoneMicFallback(context, !state.phoneMicFallback)
        },
        onToggleAssistantFallback = {
            relayViewModel.updateAssistantFallback(context, !state.assistantFallback)
        },
        onRecalibrate = {
            relayViewModel.resetCalibration(context)
            relayViewModel.startCalibration(context)
        },
        onStartRelay = { relayViewModel.startRelay(context) },
        onStopRelay = { relayViewModel.stopRelay(context) },
        onCheckHealth = { relayViewModel.checkHealth(context) },
        onQuickStatus = { relayViewModel.quickStatus(context) },
        onWakeAndListen = { relayViewModel.wakeAndListen(context) },
        onTestSpeaker = { relayViewModel.testSpeaker(context) },
        onStartVoiceProofRun = { relayViewModel.startVoiceProofRun() },
        onResetVoiceProofRun = { relayViewModel.resetVoiceProofRun() },
        onRunAudioRouteProbe = { relayViewModel.runAudioRouteProbe(context) },
        onTapTest = { relayViewModel.tapTest(context) },
        onRunSherpaBenchmark = { relayViewModel.runInteractiveSherpaBenchmark(context) },
        onToggleOfflineCommandRecognition = { enabled ->
            relayViewModel.toggleOfflineCommandRecognition(context, enabled)
        },
        onApprove = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            relayViewModel.approve(context)
        },
        onReject = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            relayViewModel.reject(context)
        },
        // onCancel callback removed — cancel action is handled through service intents directly
        onRetryQueue = {
            relayViewModel.retryQueuedBridgeEvents(context)
            queuedActionsSent = true
        },
        onDiscardQueue = { relayViewModel.discardQueuedBridgeEvents(context) },
        onDismissError = { relayViewModel.dismissError() },
        onDismissDiagnostics = { diagnosticsExported = false },
        onDismissQueued = { queuedActionsSent = false },
        onExportDiagnostics = {
            relayViewModel.exportDiagnostics(
                context,
                options = diagnosticOptions(
                    diagnosticsIncludePhoneModel,
                    diagnosticsIncludeCapabilityMatrix,
                    diagnosticsIncludeErrorCategories,
                    diagnosticsIncludeRawRoute,
                ),
            )
            diagnosticsExported = true
        },
        onEnableDevMode = {
            isDevMode = true
            selectedTab = DevPodsTab.Dev
        },
        onOpenSettings = { context.openAppSettings() },
        onDiagnosticsPhoneModelChanged = { diagnosticsIncludePhoneModel = it },
        onDiagnosticsCapabilityMatrixChanged = { diagnosticsIncludeCapabilityMatrix = it },
        onDiagnosticsErrorCategoriesChanged = { diagnosticsIncludeErrorCategories = it },
        onDiagnosticsRawRouteChanged = { diagnosticsIncludeRawRoute = it },
        onOpenPreferences = { showSettings = true },
        showApprovalDetail = showApprovalDetail,
        onDismissApprovalDetail = { showApprovalDetail = false },
        onShowApprovalDetail = { showApprovalDetail = true },
        onToggleFastWake = { enabled ->
            relayViewModel.updateLatencyFlag(context) { it.copy(fastWakeEnabled = enabled) }
        },
        onToggleTtsWarmKeepalive = { enabled ->
            relayViewModel.updateLatencyFlag(context) { it.copy(ttsWarmKeepaliveEnabled = enabled) }
        },
        onToggleSpeechRecognizerPrewarm = { enabled ->
            relayViewModel.updateLatencyFlag(context) { it.copy(speechRecognizerPrewarmEnabled = enabled) }
        },
        onTogglePreferredProviderOrdering = { enabled ->
            relayViewModel.updateLatencyFlag(context) { it.copy(preferredProviderOrderingEnabled = enabled) }
        },
        onToggleSpeculativeRoutePrepare = { enabled ->
            relayViewModel.updateLatencyFlag(context) { it.copy(speculativeRoutePrepareEnabled = enabled) }
        },
        onToggleBridgePrefetchOnWake = { enabled ->
            relayViewModel.updateLatencyFlag(context) { it.copy(bridgePrefetchOnWakeEnabled = enabled) }
        },
        onToggleEventStreaming = { enabled ->
            relayViewModel.updateLatencyFlag(context) { it.copy(eventStreamingEnabled = enabled) }
        },
        onToggleLatencySummaryExport = { enabled ->
            relayViewModel.updateLatencyFlag(context) { it.copy(latencySummaryExportEnabled = enabled) }
        },
        onImportRemoteBridge = { showRemoteBridgeDialog = true },
    )

    if (showSettings) {
        val settingsModifier = Modifier.fillMaxSize().background(DevPodsColor.Background)
        SettingsScreen(
            notificationPreference = state.notificationPreference,
            reminders = state.reminders,
            learnedPhrases = state.learnedPhrases,
            nudgePolicy = state.nudgePolicy,
            quickStartEnabled = state.quickStartEnabled,
            setupPhase = state.setupPhase.name,
            onNotificationStyleChanged = { style ->
                val current = state.notificationPreference ?: NotificationPreference(sessionId = state.config.sessionId)
                relayViewModel.saveNotificationPreferences(current.copy(style = style))
            },
            onToggleBadge = { enabled ->
                val current = state.notificationPreference ?: NotificationPreference(sessionId = state.config.sessionId)
                relayViewModel.saveNotificationPreferences(current.copy(badgeEnabled = enabled))
            },
            onToggleSoftPingTts = { enabled ->
                val current = state.notificationPreference ?: NotificationPreference(sessionId = state.config.sessionId)
                relayViewModel.saveNotificationPreferences(current.copy(softPingTtsEnabled = enabled))
            },
            onToggleNudgeTts = { enabled ->
                val current = state.notificationPreference ?: NotificationPreference(sessionId = state.config.sessionId)
                relayViewModel.saveNotificationPreferences(current.copy(nudgeTtsEnabled = enabled))
            },
            onToggleReminderTts = { enabled ->
                val current = state.notificationPreference ?: NotificationPreference(sessionId = state.config.sessionId)
                relayViewModel.saveNotificationPreferences(current.copy(reminderTtsEnabled = enabled))
            },
            onToggleShowSensitive = { enabled ->
                val current = state.notificationPreference ?: NotificationPreference(sessionId = state.config.sessionId)
                relayViewModel.saveNotificationPreferences(current.copy(showSensitiveInNotifications = enabled))
            },
            onToggleWearApproval = { enabled ->
                val current = state.notificationPreference ?: NotificationPreference(sessionId = state.config.sessionId)
                relayViewModel.saveNotificationPreferences(current.copy(wearApprovalEnabled = enabled))
            },
            onCancelReminder = { relayViewModel.cancelReminder(it) },
            onCreateReminder = { summary, dueAtMs -> relayViewModel.createReminder(summary, dueAtMs) },
            onDeleteLearnedPhrase = { relayViewModel.deleteLearnedPhrase(it) },
            onUpdateLearnedPhraseIntent = { phrase, intent ->
                relayViewModel.saveLearnedPhrase(phrase.copy(intent = intent))
            },
            onResetLearnedPhrases = { relayViewModel.resetLearnedPhrases() },
            onToggleNudgeEnabled = { enabled ->
                val current = state.nudgePolicy ?: NudgePolicy(sessionId = state.config.sessionId)
                relayViewModel.saveNudgePolicy(current.copy(enabled = enabled))
            },
            onToggleNudgeTypeMuted = { type, muted ->
                val current = state.nudgePolicy ?: NudgePolicy(sessionId = state.config.sessionId)
                val updatedMuted = if (muted) current.mutedTypes + type else current.mutedTypes - type
                relayViewModel.saveNudgePolicy(current.copy(mutedTypes = updatedMuted))
            },
            onNudgeThresholdsChanged = { thresholds ->
                val current = state.nudgePolicy ?: NudgePolicy(sessionId = state.config.sessionId)
                relayViewModel.saveNudgePolicy(current.copy(thresholds = thresholds))
            },
            onDismiss = { showSettings = false },
            modifier = settingsModifier,
        )
    }

    if (showImportDialog) {
        PairingImportDialog(
            value = importLink,
            onValueChange = { importLink = it },
            onDismiss = { showImportDialog = false },
            onImport = {
                relayViewModel.importPairingUri(context, importLink)
                showImportDialog = false
            },
        )
    }
}

@Composable
private fun RelayAppShell(
    state: RelayUiState,
    selectedTab: DevPodsTab,
    isDevMode: Boolean,
    diagnosticsExported: Boolean,
    queuedActionsSent: Boolean,
    diagnosticsIncludePhoneModel: Boolean,
    diagnosticsIncludeCapabilityMatrix: Boolean,
    diagnosticsIncludeErrorCategories: Boolean,
    diagnosticsIncludeRawRoute: Boolean,
    permissionRefreshTick: Int,
    showApprovalDetail: Boolean,
    onTabSelected: (DevPodsTab) -> Unit,
    onRequestPermissions: () -> Unit,
    onPairBridge: () -> Unit,
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
    onStartRelay: () -> Unit,
    onStopRelay: () -> Unit,
    onCheckHealth: () -> Unit,
    onQuickStatus: () -> Unit,
    onWakeAndListen: () -> Unit,
    onTestSpeaker: () -> Unit,
    onStartVoiceProofRun: () -> Unit,
    onResetVoiceProofRun: () -> Unit,
    onRunAudioRouteProbe: () -> Unit,
    onTapTest: () -> Unit,
    onRunSherpaBenchmark: () -> Unit,
    onToggleOfflineCommandRecognition: (Boolean) -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onRetryQueue: () -> Unit,
    onDiscardQueue: () -> Unit,
    onDismissError: () -> Unit,
    onDismissDiagnostics: () -> Unit,
    onDismissQueued: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onEnableDevMode: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPreferences: () -> Unit,
    onDismissApprovalDetail: () -> Unit,
    onShowApprovalDetail: () -> Unit,
    onDiagnosticsPhoneModelChanged: (Boolean) -> Unit,
    onDiagnosticsCapabilityMatrixChanged: (Boolean) -> Unit,
    onDiagnosticsErrorCategoriesChanged: (Boolean) -> Unit,
    onDiagnosticsRawRouteChanged: (Boolean) -> Unit,
    onRecalibrate: () -> Unit = {},
    onImportRemoteBridge: () -> Unit = {},
    onToggleFastWake: (Boolean) -> Unit = {},
    onToggleTtsWarmKeepalive: (Boolean) -> Unit = {},
    onToggleSpeechRecognizerPrewarm: (Boolean) -> Unit = {},
    onTogglePreferredProviderOrdering: (Boolean) -> Unit = {},
    onToggleSpeculativeRoutePrepare: (Boolean) -> Unit = {},
    onToggleBridgePrefetchOnWake: (Boolean) -> Unit = {},
    onToggleEventStreaming: (Boolean) -> Unit = {},
    onToggleLatencySummaryExport: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val microphoneAllowed = remember(permissionRefreshTick) {
        hasPermission(context, Manifest.permission.RECORD_AUDIO)
    }
    val notificationsAllowed = remember(permissionRefreshTick) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
    }
    val appVersion = remember { appVersionName(context) }

    val note = when (selectedTab) {
        DevPodsTab.Home -> "Home answers: what state are we in, and what should I do next?"
        DevPodsTab.Activity -> "Approvals are global, visible, and consequence-led."
        DevPodsTab.Device -> "Device owns pairing, setup, and hardware truth."
        DevPodsTab.Help -> "Recovery is a core product surface, not an engineering afterthought."
        DevPodsTab.Dev -> "Developer Mode keeps QA controls without polluting Standard Mode."
    }

    DevPodsScreenShell(
        isDevMode = isDevMode,
        note = note,
        bottomBar = {
            BottomNav(
                selectedTab = selectedTab,
                isDevMode = isDevMode,
                onTabSelected = onTabSelected,
            )
        },
    ) { _ ->
        val contentModifier = Modifier.fillMaxSize()

        when (selectedTab) {
                DevPodsTab.Home -> HomeScreen(
                    state = state,
                    onPairBridge = onPairBridge,
                    onListenNow = onWakeAndListen,
                    onCheckBridge = onCheckHealth,
                    onViewActivity = { onTabSelected(DevPodsTab.Activity) },
                    onApprove = onApprove,
                    onReject = onReject,
                    onStop = onStopRelay,
                    onRetryNow = onRetryQueue,
                    onDiscard = onDiscardQueue,
                    onDismissError = onDismissError,
                    modifier = contentModifier,
                )

                DevPodsTab.Activity -> ActivityScreen(
                    state = state,
                    diagnosticsExported = diagnosticsExported,
                    queuedActionsSent = queuedActionsSent,
                    showApprovalDetail = showApprovalDetail,
                    onApprove = onApprove,
                    onReject = onReject,
                    onDismissDiagnostics = onDismissDiagnostics,
                    onDismissQueued = onDismissQueued,
                    onDismissApprovalDetail = onDismissApprovalDetail,
                    onShowApprovalDetail = onShowApprovalDetail,
                    modifier = contentModifier,
                )

                DevPodsTab.Device -> DeviceScreen(
                    uiState = state,
                    capabilityEntry = state.currentCapabilityEntry(),
                    onScanQr = onScanQr,
                    onImportLink = onImportLink,
                    onResumeSetup = onResumeSetup,
                    onSkipSetup = onSkipSetup,
                    onReRunSetup = onReRunSetup,
                    onResetSetup = onResetSetup,
                    onRePair = onRePair,
                    onForgetBridge = onForgetBridge,
                    onToggleBluetoothRouting = onToggleBluetoothRouting,
                    onTogglePhoneMicFallback = onTogglePhoneMicFallback,
                    onToggleAssistantFallback = onToggleAssistantFallback,
                    onTestVoice = onTestSpeaker,
                    onRepairMic = onRequestPermissions,
                    onRecalibrate = onRecalibrate,
                    onImportRemoteBridge = onImportRemoteBridge,
                    modifier = contentModifier,
                )

                DevPodsTab.Help -> HelpScreen(
                    isBridgeUnreachable = isBridgeUnreachable(state),
                    microphoneAllowed = microphoneAllowed,
                    notificationsAllowed = notificationsAllowed,
                    speechEngineAvailable = state.speechRecognitionAvailable,
                    voiceProofRun = state.voiceDiagnostics.voiceProofRun,
                    showPermissionModal = !microphoneAllowed,
                    isDevModeEnabled = isDevMode,
                    diagnosticsIncludePhoneModel = diagnosticsIncludePhoneModel,
                    diagnosticsIncludeCapabilityMatrix = diagnosticsIncludeCapabilityMatrix,
                    diagnosticsIncludeErrorCategories = diagnosticsIncludeErrorCategories,
                    diagnosticsIncludeRawRoute = diagnosticsIncludeRawRoute,
                    appVersion = appVersion,
                    bridgeVersion = state.lastBridgeHealth?.bridgeVersion ?: "Unknown",
                    protocolStatus = protocolStatus(state),
                    showVersionMismatch = showVersionMismatch(state, appVersion),
                    onDiagnosticsPhoneModelChanged = onDiagnosticsPhoneModelChanged,
                    onDiagnosticsCapabilityMatrixChanged = onDiagnosticsCapabilityMatrixChanged,
                    onDiagnosticsErrorCategoriesChanged = onDiagnosticsErrorCategoriesChanged,
                    onDiagnosticsRawRouteChanged = onDiagnosticsRawRouteChanged,
                    onRetryBridge = onCheckHealth,
                    onOpenPairingHelp = { onTabSelected(DevPodsTab.Device) },
                    onRepairMicPermission = onRequestPermissions,
                    onUsePhoneMicFallback = {
                        if (!state.phoneMicFallback) {
                            onTogglePhoneMicFallback()
                        }
                    },
                    onExportDiagnostics = onExportDiagnostics,
                    onEnableDevMode = onEnableDevMode,
                    onOpenSettings = onOpenSettings,
                    onOpenPreferences = onOpenPreferences,
                    onNotNow = onDismissError,
                    onShareDiagnostics = onExportDiagnostics,
                    onPreviewDiagnostics = onExportDiagnostics,
                    onUpdateGuide = { onTabSelected(DevPodsTab.Device) },
                    onContinueAnyway = onDismissError,
                    onStartVoiceProofRun = onStartVoiceProofRun,
                    onResetVoiceProofRun = onResetVoiceProofRun,
                    onRunAudioRouteProbe = onRunAudioRouteProbe,
                    modifier = contentModifier,
                )

                DevPodsTab.Dev -> DeveloperModeScreen(
                    config = state.config,
                    bridgeStatus = state.bridgeStatus,
                    audioRoute = state.audioRoute,
                    lastWake = state.lastWakeSignal,
                    latency = state.latency,
                    isServiceRunning = state.isServiceRunning,
                    sherpaPromotionState = state.voiceDiagnostics.sherpaPromotionState,
                    offlineCommandRecognitionEnabled = state.config.speechInputMode == SpeechInputMode.SHERPA_EVALUATION,
                    benchmarkSession = state.benchmarkSession,
                    onRequestPermissions = onRequestPermissions,
                    onStartRelay = onStartRelay,
                    onStopRelay = onStopRelay,
                    onCheckHealth = onCheckHealth,
                    onQuickStatus = onQuickStatus,
                    onWakeAndListen = onWakeAndListen,
                    onTestSpeaker = onTestSpeaker,
                    onTapTest = onTapTest,
                    onRunSherpaBenchmark = onRunSherpaBenchmark,
                    onToggleOfflineCommandRecognition = onToggleOfflineCommandRecognition,
                    onToggleFastWake = onToggleFastWake,
                    onToggleTtsWarmKeepalive = onToggleTtsWarmKeepalive,
                    onToggleSpeechRecognizerPrewarm = onToggleSpeechRecognizerPrewarm,
                    onTogglePreferredProviderOrdering = onTogglePreferredProviderOrdering,
                    onToggleSpeculativeRoutePrepare = onToggleSpeculativeRoutePrepare,
                    onToggleBridgePrefetchOnWake = onToggleBridgePrefetchOnWake,
                    onToggleEventStreaming = onToggleEventStreaming,
                    onToggleLatencySummaryExport = onToggleLatencySummaryExport,
                    modifier = contentModifier,
                )
            }
        }
    }

@Composable
private fun PairingImportDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import bridge link") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text("devpods://pair or pairing page URL") },
                singleLine = false,
            )
        },
        confirmButton = {
            TextButton(onClick = onImport) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun RemoteBridgeDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remote private network bridge") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text("https://host:port") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = onImport) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private fun retrySetupPhase(
    phase: SetupPhase,
    relayViewModel: RelayViewModel,
    context: Context,
) {
    when (phase) {
        SetupPhase.NOT_STARTED -> relayViewModel.startSetup(context)
        SetupPhase.PAIRING -> relayViewModel.checkHealth(context)
        SetupPhase.QUICK_START -> relayViewModel.checkHealth(context)
        SetupPhase.DEVICE_PROBE -> relayViewModel.probeDevice(context)
        SetupPhase.CALIBRATION -> relayViewModel.startCalibration(context)
        SetupPhase.GESTURE_MAPPING -> relayViewModel.startCalibration(context)
        SetupPhase.GESTURE_TEST -> relayViewModel.testWake(context)
        SetupPhase.STT_TEST -> relayViewModel.testStt(context)
        SetupPhase.COMPLETE_PROVEN -> relayViewModel.testStt(context)
        SetupPhase.COMPLETE_DEGRADED -> relayViewModel.testStt(context)
    }
}

private fun RelayUiState.currentCapabilityEntry(): DeviceCapabilityEntry? {
    val currentDeviceName = currentDeviceState?.displayName
    return when {
        !currentDeviceName.isNullOrBlank() -> capabilityMatrix.entries.lastOrNull {
            it.deviceModel.equals(currentDeviceName, ignoreCase = true)
        } ?: capabilityMatrix.entries.lastOrNull()
        else -> capabilityMatrix.entries.lastOrNull()
    }
}

private fun buildRecentEvents(state: RelayUiState): List<String> {
    return buildList {
        state.lastWakeSignal?.let { add("Wake captured from ${it.sourceLabel}.") }
        if (state.lastTranscript.isNotBlank()) add("Transcript captured: ${state.lastTranscript}")
        if (state.lastResponseStatus != null) add("Bridge returned ${state.lastResponseStatus}.")
        if (state.bridgeQueueState.queuedCount > 0) add("${state.bridgeQueueState.queuedCount} bridge command(s) queued.")
    }
}

private fun hasPermission(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

private fun isBridgeUnreachable(state: RelayUiState): Boolean {
    val message = state.userFacingErrorMessage.orEmpty()
    return message.contains("bridge", ignoreCase = true) &&
        !state.bridgeStatus.startsWith("Healthy", ignoreCase = true)
}

private fun protocolStatus(state: RelayUiState): String {
    val health = state.lastBridgeHealth ?: return "Unknown"
    return if (health.protocolVersion == EXPECTED_PROTOCOL_VERSION) {
        "Compatible"
    } else {
        "App expects v$EXPECTED_PROTOCOL_VERSION, bridge reports v${health.protocolVersion}"
    }
}

private fun showVersionMismatch(state: RelayUiState, appVersion: String): Boolean {
    val health = state.lastBridgeHealth ?: return false
    return health.protocolVersion != EXPECTED_PROTOCOL_VERSION ||
        compareSemver(appVersion, health.minAppVersion) < 0
}

private fun compareSemver(left: String, right: String): Int {
    val leftParts = left.split(".", "-").mapNotNull { it.toIntOrNull() }
    val rightParts = right.split(".", "-").mapNotNull { it.toIntOrNull() }
    val max = maxOf(leftParts.size, rightParts.size, 3)
    for (index in 0 until max) {
        val leftValue = leftParts.getOrElse(index) { 0 }
        val rightValue = rightParts.getOrElse(index) { 0 }
        if (leftValue != rightValue) return leftValue.compareTo(rightValue)
    }
    return 0
}

private fun diagnosticOptions(
    includePhoneModel: Boolean,
    includeCapabilityMatrix: Boolean,
    includeErrorCategories: Boolean,
    includeRawRoute: Boolean,
): DiagnosticExportOptions = DiagnosticExportOptions(
    includePhoneModel = includePhoneModel,
    includeCapabilityMatrix = includeCapabilityMatrix,
    includeErrorCategories = includeErrorCategories,
    includeRawRoute = includeRawRoute,
)

private fun appVersionName(context: Context): String {
    return runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: BuildConfig.VERSION_NAME
    }.getOrDefault(BuildConfig.VERSION_NAME)
}

private fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(intent)
}
