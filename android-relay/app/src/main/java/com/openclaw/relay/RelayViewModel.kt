package com.openclaw.relay

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.relay.device.DeviceCapabilityEntry
import com.openclaw.relay.device.SetupCapabilityAssessment
import com.openclaw.relay.device.buildCapabilityEntryFromSetup
import com.openclaw.relay.device.isDirectHardwareWakeProvider
import com.openclaw.relay.diagnostic.DiagnosticExport
import com.openclaw.relay.diagnostic.DiagnosticExportOptions
import com.openclaw.relay.history.ActivityEventType
import com.openclaw.relay.history.ActivityHistoryEntry
import com.openclaw.relay.history.ActivityHistoryStore
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RelayViewModel(
    private val pairingBridgeClient: BridgeClient = BridgeClient(),
) : ViewModel() {
    companion object {
        private const val TAG = "OpenClawRelay"
    }

    val state: StateFlow<RelayUiState> = RelayStateStore.state
    private var pairingVerificationJob: Job? = null

    // Calibration state
    private var calibrationEngine: com.openclaw.relay.calibration.EarbudCalibrationEngine? = null
    private var calibrationCollectionJob: Job? = null
    private var calibrationRegistry: com.openclaw.relay.signal.SignalProviderRegistry? = null
    internal val calibrationResults = mutableListOf<com.openclaw.relay.calibration.CalibratedGesture>()
    private var calibrationGestureIndex = 0
    private val defaultCalibrationGestures = listOf(
        com.openclaw.relay.signal.GestureType.SINGLE_PRESS,
        com.openclaw.relay.signal.GestureType.DOUBLE_PRESS,
        com.openclaw.relay.signal.GestureType.TRIPLE_PRESS,
        com.openclaw.relay.signal.GestureType.LONG_PRESS,
    )

    fun initialize(context: Context) {
        loadSavedConfig(context)

        // Load latest Sherpa benchmark promotion state on startup (P1-7)
        val promotionState = runCatching { OfflineSpeechEvaluation.resolvePromotionState(context) }.getOrDefault(SherpaPromotionState.HIDDEN)
        RelayStateStore.setSherpaPromotionState(promotionState)

        // R3: Downgrade SHERPA_EVALUATION to PLATFORM if promotion state is below EXPERIMENTAL
        val config = state.value.config
        if (config.speechInputMode == SpeechInputMode.SHERPA_EVALUATION &&
            promotionState.ordinal < SherpaPromotionState.EXPERIMENTAL.ordinal
        ) {
            RelayStateStore.updateConfig { it.copy(speechInputMode = SpeechInputMode.PLATFORM) }
            RelayConfigStorage.save(context, state.value.config)
            RelayStateStore.recordOfflineSpeechReadiness(OfflineSpeechReadiness.evaluate(state.value.config))
        }

        RelayStateStore.setCapabilityMatrix(com.openclaw.relay.device.DeviceProfileStorage.loadMatrix(context))

        val savedProfile = com.openclaw.relay.device.DeviceProfileStorage.loadDeviceCalibrationProfile(context)
        if (savedProfile != null) {
            val currentProvider = com.openclaw.relay.device.DeviceProfileStorage.loadMatrix(context)
                .entries.firstOrNull()?.providersObserved?.firstOrNull() ?: "unknown"
            val appVersion = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            } catch (_: Exception) { null }
            val androidVersion = android.os.Build.VERSION.RELEASE

            if (savedProfile.isInvalidated(
                    currentAppVersion = appVersion ?: "unknown",
                    currentAndroidVersion = androidVersion ?: "unknown",
                    currentProviderId = currentProvider,
                )
            ) {
                com.openclaw.relay.device.DeviceProfileStorage.recordCalibrationHistory(context, savedProfile)
                RelayStateStore.setCalibrationRequired(true)
                RelayStateStore.setError("Calibration profile invalidated by system or provider change. Please recalibrate.")
            } else {
                RelayStateStore.setCalibrationProfile(savedProfile)
                val persistedMissCount = com.openclaw.relay.device.DeviceProfileStorage.loadRuntimeMissCount(context)
                if (persistedMissCount > 0) {
                    RelayStateStore.setRuntimeMissCount(persistedMissCount)
                }
            }
        }

        val onboarding = UserOnboardingManager.load(context)
        RelayStateStore.setShowOnboarding(!onboarding.hasSeenOnboarding)
        if (onboarding.hasCompletedSetup) {
            val matrix = com.openclaw.relay.device.DeviceProfileStorage.loadMatrix(context)
            RelayStateStore.setSetupPhase(
                resolveStoredSetupPhase(context, matrix)
            )
        }
    }

    fun loadSavedConfig(context: Context) {
        val savedConfig = RelayConfigStorage.load(context) ?: return
        RelayStateStore.updateConfig { savedConfig }
        RelayStateStore.setPhoneMicFallback(savedConfig.phoneMicFallback)
        RelayStateStore.setAssistantFallback(savedConfig.assistantFallback)
        RelayStateStore.recordOfflineSpeechReadiness(OfflineSpeechReadiness.evaluate(savedConfig))
        RelayStateStore.clearError()
    }

    fun dismissOnboarding(context: Context) {
        UserOnboardingManager.markOnboardingSeen(context)
        RelayStateStore.setShowOnboarding(false)
    }

    fun updatePendingPairingUri(value: String) {
        RelayStateStore.setPendingPairingUri(value)
    }

    fun reportPairingScanError(message: String) {
        RelayStateStore.setError(message)
    }

    fun applyAutomationConfig(
        context: Context,
        bridgeBaseUrl: String?,
        relayToken: String?,
        workspace: String?,
    ) {
        val currentConfig = state.value.config
        val normalizedBridgeBaseUrl = when {
            bridgeBaseUrl == null -> currentConfig.bridgeBaseUrl
            bridgeBaseUrl.isBlank() -> currentConfig.bridgeBaseUrl
            else -> normalizeBridgeBaseUrl(bridgeBaseUrl)
        }

        if (bridgeBaseUrl != null && bridgeBaseUrl.isNotBlank() && normalizedBridgeBaseUrl == null) {
            RelayStateStore.setError("Bridge URL must start with http:// or https:// and include a host.")
            return
        }

        RelayStateStore.updateConfig {
            it.copy(
                bridgeBaseUrl = normalizedBridgeBaseUrl ?: it.bridgeBaseUrl,
                relayToken = relayToken?.trim() ?: it.relayToken,
                workspace = workspace?.trim()?.takeIf { value -> value.isNotBlank() } ?: it.workspace,
            )
        }
        RelayConfigStorage.save(context, state.value.config)
        RelayStateStore.clearError()
    }

    fun updateBridgeBaseUrl(context: Context, value: String) {
        val trimmedValue = value.trim()
        if (trimmedValue.isNotBlank()) {
            val normalizedBridgeBaseUrl = normalizeBridgeBaseUrl(trimmedValue)
            if (normalizedBridgeBaseUrl == null) {
                RelayStateStore.setError("Bridge URL must start with http:// or https:// and include a host.")
                return
            }

            RelayStateStore.updateConfig { it.copy(bridgeBaseUrl = normalizedBridgeBaseUrl) }
        } else {
            RelayStateStore.updateConfig { it.copy(bridgeBaseUrl = "") }
        }

        RelayConfigStorage.save(context, state.value.config)
        RelayStateStore.clearError()
    }

    fun updateRelayToken(context: Context, value: String) {
        RelayStateStore.updateConfig { it.copy(relayToken = value.trim()) }
        RelayConfigStorage.save(context, state.value.config)
        RelayStateStore.clearError()
    }

    fun updateWorkspace(context: Context, value: String) {
        RelayStateStore.updateConfig { it.copy(workspace = value.trim()) }
        RelayConfigStorage.save(context, state.value.config)
        RelayStateStore.clearError()
    }

    fun updateBluetoothRouting(context: Context, enabled: Boolean) {
        RelayStateStore.updateConfig { it.copy(useBluetoothRouting = enabled) }
        RelayConfigStorage.save(context, state.value.config)
        RelayStateStore.clearError()
    }

    fun updatePhoneMicFallback(context: Context, enabled: Boolean) {
        RelayStateStore.updateConfig { it.copy(phoneMicFallback = enabled) }
        RelayStateStore.setPhoneMicFallback(enabled)
        RelayConfigStorage.save(context, state.value.config)
        RelayStateStore.clearError()
    }

    fun updateAssistantFallback(context: Context, enabled: Boolean) {
        RelayStateStore.updateConfig { it.copy(assistantFallback = enabled) }
        RelayStateStore.setAssistantFallback(enabled)
        RelayConfigStorage.save(context, state.value.config)
        RelayStateStore.clearError()
    }

    fun updateSpeechInputMode(context: Context, mode: SpeechInputMode) {
        // P2-1: Centralize promotion gate for SHERPA_EVALUATION
        if (mode == SpeechInputMode.SHERPA_EVALUATION) {
            val promotionState = state.value.voiceDiagnostics.sherpaPromotionState
            if (promotionState.ordinal < SherpaPromotionState.EXPERIMENTAL.ordinal) {
                RelayStateStore.setError("Sherpa command recognition requires benchmark promotion state EXPERIMENTAL or higher.")
                return
            }
        }
        RelayStateStore.updateConfig { it.copy(speechInputMode = mode) }
        RelayStateStore.recordOfflineSpeechReadiness(OfflineSpeechReadiness.evaluate(state.value.config))
        RelayConfigStorage.save(context, state.value.config)
        RelayStateStore.clearError()
    }

    fun updateOfflineSpeechModel(
        context: Context,
        modelPath: String,
        modelVersion: String,
        modelSha256: String = "",
    ) {
        RelayStateStore.updateConfig {
            it.copy(
                offlineSpeechModelPath = modelPath.trim(),
                offlineSpeechModelVersion = modelVersion.trim(),
                offlineSpeechModelSha256 = modelSha256.trim(),
            )
        }
        RelayStateStore.recordOfflineSpeechReadiness(OfflineSpeechReadiness.evaluate(state.value.config))
        RelayConfigStorage.save(context, state.value.config)
        RelayStateStore.clearError()
    }

    fun forgetBridge(context: Context) {
        RelayConfigStorage.clear(context)
        RelayStateStore.resetPairing()
    }

    fun dismissError() {
        RelayStateStore.clearError()
    }

    fun importPairingUri(context: Context, value: String) {
        if (state.value.isImportingPairing) {
            RelayStateStore.setError("Pairing import already in progress. Please wait.")
            return
        }

        val trimmedValue = value.trim()
        val directRequest = parseRelayPairingRequestUri(trimmedValue)
        if (directRequest != null) {
            importPairingRequest(context, directRequest)
            return
        }

        val directConfig = parseRelayPairingUri(trimmedValue)
        if (directConfig != null) {
            applyImportedPairingConfig(context, directConfig)
            return
        }

        val pairingPageUrl = normalizeRelayPairingPageUrl(trimmedValue)
        if (pairingPageUrl == null) {
            RelayStateStore.setError("Invalid pairing input. Use a devpods://pair link or the desktop bridge pairing page URL.")
            return
        }

        RelayStateStore.setPendingPairingUri(pairingPageUrl)
        RelayStateStore.markPairingImportStarted()
        viewModelScope.launch {
            pairingBridgeClient.pairing(pairingPageUrl)
                .onSuccess { applyImportedPairingConfig(context, it.value) }
                .onFailure {
                    RelayStateStore.markPairingImportFinished()
                    RelayStateStore.setError(
                        "Could not import pairing from the desktop bridge. Check that the phone can reach the bridge pairing page.",
                    )
                }
        }
    }

    fun importPrivateNetworkBridge(context: Context, bridgeUrl: String) {
        if (state.value.isImportingPairing) {
            RelayStateStore.setError("Pairing import already in progress. Please wait.")
            return
        }

        val trimmedUrl = bridgeUrl.trim()
        if (trimmedUrl.isBlank()) {
            RelayStateStore.setError("Bridge URL is required.")
            return
        }

        val normalized = normalizeBridgeBaseUrl(trimmedUrl)
        if (normalized == null) {
            RelayStateStore.setError("Bridge URL must start with http:// or https:// and include a host.")
            return
        }

        val isDebug = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!isDebug && normalized.startsWith("http://", ignoreCase = true)) {
            RelayStateStore.setError("Release builds require HTTPS for remote bridges. Use https:// or enable a debug build.")
            return
        }

        RelayStateStore.setPendingPairingUri(normalized)
        RelayStateStore.markPairingImportStarted()

        viewModelScope.launch {
            // R1: Fetch /pairing first (unauthenticated), then use the token for authenticated /health
            pairingBridgeClient.pairing("$normalized/pairing")
                .onSuccess { pairingResult ->
                    val config = pairingResult.value
                    // Now verify the bridge with the extracted token
                    pairingBridgeClient.health(config)
                        .onSuccess { healthResult ->
                            if (healthResult.value.ok) {
                                applyImportedPairingConfig(context, config, healthResult)
                            } else {
                                RelayStateStore.markPairingImportFinished()
                                RelayStateStore.setError("Bridge responded but reports not ready (${healthResult.value.brainMode}).")
                            }
                        }
                        .onFailure {
                            RelayStateStore.markPairingImportFinished()
                            RelayStateStore.setError(
                                "Could not reach the bridge at $normalized. For private VPN or mesh networks, ensure the bridge URL is reachable from this device.",
                            )
                        }
                }
                .onFailure {
                    RelayStateStore.markPairingImportFinished()
                    RelayStateStore.setError(
                        "Bridge is reachable but pairing page did not return a valid config. For private networks, paste the devpods://pair link instead.",
                    )
                }
        }
    }

    fun selectDiscoveredBridge(context: Context, bridge: DiscoveredBridge) {
        val isDebug = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val pairingBaseUrl = bridge.pairingBaseUrl ?: run {
            // Release builds block cleartext HTTP; try HTTPS first.
            // Debug builds allow HTTP for trusted LAN testing.
            if (isDebug) {
                "http://${bridge.host}:${bridge.port}"
            } else {
                "https://${bridge.host}:${bridge.port}"
            }
        }
        if (!isDebug && pairingBaseUrl.startsWith("http://", ignoreCase = true)) {
            RelayStateStore.setError("Discovered bridge advertised HTTP, but release builds require HTTPS. Restart the bridge with --pairing-base-url https://...")
            return
        }
        val pairingPageUrl = normalizeRelayPairingPageUrl(pairingBaseUrl) ?: return

        RelayStateStore.setPendingPairingUri(pairingPageUrl)
        RelayStateStore.markPairingImportStarted()

        viewModelScope.launch {
            pairingBridgeClient.pairing(pairingPageUrl)
                .onSuccess { result ->
                    val config = result.value
                    if (!config.relayToken.isNullOrBlank()) {
                        applyImportedPairingConfig(context, config)
                        enableQuickStart(config)
                    } else {
                        // Need to verify with pairing code
                        val code = result.value.relayToken.takeIf { it.isNotBlank() }
                            ?: run {
                                RelayStateStore.markPairingImportFinished()
                                RelayStateStore.setError("Discovered bridge did not provide a pairing code.")
                                return@onSuccess
                            }
                        pairingBridgeClient.pairingVerify(pairingPageUrl, code)
                            .onSuccess { verifyResult ->
                                val verifiedConfig = config.copy(relayToken = verifyResult.value)
                                applyImportedPairingConfig(context, verifiedConfig)
                                enableQuickStart(verifiedConfig)
                            }
                            .onFailure {
                                RelayStateStore.markPairingImportFinished()
                                RelayStateStore.setError("Could not verify pairing with discovered bridge.")
                            }
                    }
                }
                .onFailure {
                    RelayStateStore.markPairingImportFinished()
                    RelayStateStore.setError("Could not connect to discovered bridge at $pairingBaseUrl.")
                }
        }
    }

    private fun enableQuickStart(config: RelayConfig) {
        viewModelScope.launch {
            try {
                val url = java.net.URL("${config.bridgeBaseUrl.trimEnd('/')}/sessions/${config.sessionId}/quick-start")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Authorization", "Bearer ${config.relayToken}")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.outputStream.use { it.write("{}".toByteArray()) }
                val responseCode = connection.responseCode
                connection.disconnect()
                if (responseCode in 200..299) {
                    RelayStateStore.setQuickStartEnabled(true)
                    RelayStateStore.setSetupPhase(SetupPhase.QUICK_START)
                }
            } catch (_: Exception) {
                // Ignore quick-start enable failures; bridge works normally without it
            }
        }
    }

    private fun importPairingRequest(context: Context, request: RelayPairingRequest) {
        if (!request.relayToken.isNullOrBlank()) {
            applyImportedPairingConfig(context, request.toRelayConfig())
            return
        }

        val pairingCode = request.pairingCode
        if (pairingCode.isNullOrBlank()) {
            applyImportedPairingConfig(context, request.toRelayConfig())
            return
        }

        RelayStateStore.setPendingPairingUri(request.pairingPageUrl)
        RelayStateStore.markPairingImportStarted()
        viewModelScope.launch {
            pairingBridgeClient.pairingVerify(request.pairingPageUrl, pairingCode)
                .onSuccess { result ->
                    applyImportedPairingConfig(context, request.toRelayConfig(result.value))
                }
                .onFailure {
                    RelayStateStore.markPairingImportFinished()
                    RelayStateStore.setError(
                        "Could not verify the desktop pairing code. Re-open the bridge pairing page and try importing again.",
                    )
                }
        }
    }

    private fun applyImportedPairingConfig(
        context: Context,
        config: RelayConfig,
        alreadyFetchedHealth: TimedBridgeResult<BridgeHealthResponse>? = null,
    ) {
        RelayStateStore.updateConfig {
            it.copy(
                bridgeBaseUrl = config.bridgeBaseUrl,
                relayToken = config.relayToken,
                workspace = config.workspace,
            )
        }
        RelayStateStore.clearPendingPairingUri()
        RelayConfigStorage.save(context, state.value.config)
        RelayStateStore.recordImportedPairing()

        // P2-3: If health was already fetched during import, use it directly instead of re-verifying.
        if (alreadyFetchedHealth != null) {
            if (isCurrentConfig(config)) {
                RelayStateStore.recordHealth(alreadyFetchedHealth.value, alreadyFetchedHealth.durationMs)
            }
            return
        }

        pairingVerificationJob?.cancel()
        pairingVerificationJob = viewModelScope.launch {
            pairingBridgeClient.health(config)
                .onSuccess {
                    if (isCurrentConfig(config)) {
                        RelayStateStore.recordHealth(it.value, it.durationMs)
                    }
                }
                .onFailure {
                    if (isCurrentConfig(config)) {
                        RelayStateStore.recordImportedPairingVerificationFailure(config.bridgeBaseUrl)
                    }
                }
        }
    }

    private fun isCurrentConfig(config: RelayConfig): Boolean {
        val currentConfig = state.value.config
        return currentConfig.bridgeBaseUrl == config.bridgeBaseUrl &&
            currentConfig.relayToken == config.relayToken &&
            currentConfig.workspace == config.workspace
    }

    override fun onCleared() {
        pairingVerificationJob?.cancel()
        calibrationCollectionJob?.cancel()
        calibrationEngine?.cancelCalibration()
        super.onCleared()
    }

    fun startRelay(context: Context) {
        if (!state.value.config.isPaired()) {
            RelayStateStore.setError("Pair the desktop bridge before starting the relay.")
            return
        }

        ContextCompat.startForegroundService(context, RelayService.intent(context, RelayService.ACTION_START_RELAY))
    }

    fun stopRelay(context: Context) {
        val intent = RelayService.intent(context, RelayService.ACTION_STOP_RELAY)
        if (!state.value.isServiceRunning) {
            context.stopService(intent)
            return
        }

        context.startService(intent)
    }

    fun checkHealth(context: Context) {
        if (!state.value.config.isPaired()) {
            RelayStateStore.setError("Pair the desktop bridge before checking health.")
            return
        }

        ContextCompat.startForegroundService(context, RelayService.intent(context, RelayService.ACTION_CHECK_HEALTH))
    }

    fun quickStatus(context: Context) {
        if (!state.value.config.isPaired()) {
            RelayStateStore.setError("Pair the desktop bridge before requesting quick status.")
            return
        }

        ContextCompat.startForegroundService(context, RelayService.intent(context, RelayService.ACTION_QUICK_STATUS))
    }

    fun wakeAndListen(context: Context) {
        if (!state.value.config.isPaired()) {
            RelayStateStore.setError("Pair the desktop bridge before starting a live session.")
            return
        }

        val intent = RelayService.intent(context, RelayService.ACTION_WAKE_AND_LISTEN)
            .putExtra(RelayService.EXTRA_TRIGGER, "android_push_to_talk")
        ContextCompat.startForegroundService(context, intent)
    }

    fun testSpeaker(context: Context) {
        ContextCompat.startForegroundService(context, RelayService.intent(context, RelayService.ACTION_TEST_SPEAKER))
    }

    fun startVoiceProofRun() {
        RelayStateStore.startVoiceProofRun()
    }

    fun resetVoiceProofRun() {
        RelayStateStore.resetVoiceProofRun()
    }

    fun runAudioRouteProbe(context: Context) {
        ContextCompat.startForegroundService(context, RelayService.intent(context, RelayService.ACTION_AUDIO_ROUTE_PROBE))
    }

    fun tapTest(context: Context) {
        if (!state.value.config.isPaired()) {
            RelayStateStore.setError("Pair the desktop bridge before running a tap test.")
            return
        }

        ContextCompat.startForegroundService(context, RelayService.intent(context, RelayService.ACTION_TAP_TEST))
    }

    fun approve(context: Context) {
        if (!state.value.config.isPaired()) {
            RelayStateStore.setError("Pair the desktop bridge before approving actions.")
            return
        }

        ActivityHistoryStore.add(
            context,
            ActivityHistoryEntry(
                type = ActivityEventType.APPROVAL_APPROVED,
                summary = "Action approved",
            ),
        )
        RelayStateStore.recordActivityHistory(ActivityHistoryStore.load(context))
        ContextCompat.startForegroundService(context, RelayService.intent(context, RelayService.ACTION_APPROVE))
    }

    fun reject(context: Context) {
        if (!state.value.config.isPaired()) {
            RelayStateStore.setError("Pair the desktop bridge before rejecting actions.")
            return
        }

        ActivityHistoryStore.add(
            context,
            ActivityHistoryEntry(
                type = ActivityEventType.APPROVAL_REJECTED,
                summary = "Action rejected",
            ),
        )
        RelayStateStore.recordActivityHistory(ActivityHistoryStore.load(context))
        ContextCompat.startForegroundService(context, RelayService.intent(context, RelayService.ACTION_REJECT))
    }

    fun cancel(context: Context) {
        if (!state.value.config.isPaired()) {
            RelayStateStore.setError("Pair the desktop bridge before cancelling actions.")
            return
        }

        ContextCompat.startForegroundService(context, RelayService.intent(context, RelayService.ACTION_CANCEL))
    }

    fun retryQueuedBridgeEvents(context: Context) {
        if (!state.value.config.isPaired()) {
            RelayStateStore.setError("Pair the desktop bridge before retrying queued commands.")
            return
        }

        ContextCompat.startForegroundService(context, RelayService.intent(context, RelayService.ACTION_RETRY_QUEUE))
    }

    fun discardQueuedBridgeEvents(context: Context) {
        if (!state.value.isServiceRunning) {
            RelayStateStore.setBridgeQueueState(BridgeQueueState())
            RelayStateStore.clearError()
            return
        }

        context.startService(RelayService.intent(context, RelayService.ACTION_DISCARD_QUEUE))
    }

    fun dispatchAutomationAction(
        context: Context,
        serviceAction: String,
        bridgeBaseUrl: String?,
        relayToken: String?,
        workspace: String?,
        trigger: String?,
        eventName: String?,
        utterance: String?,
        pendingActionId: String?,
    ) {
        applyAutomationConfig(context, bridgeBaseUrl, relayToken, workspace)

        val intent = RelayService.intent(context, serviceAction).apply {
            bridgeBaseUrl?.let { putExtra(RelayService.EXTRA_BRIDGE_BASE_URL, it) }
            relayToken?.let { putExtra(RelayService.EXTRA_RELAY_TOKEN, it) }
            workspace?.let { putExtra(RelayService.EXTRA_WORKSPACE, it) }
            trigger?.let { putExtra(RelayService.EXTRA_TRIGGER, it) }
            eventName?.let { putExtra(RelayService.EXTRA_EVENT_NAME, it) }
            utterance?.let { putExtra(RelayService.EXTRA_UTTERANCE, it) }
            pendingActionId?.let { putExtra(RelayService.EXTRA_PENDING_ACTION_ID, it) }
        }

        Log.i(
            TAG,
            "automation dispatch action=$serviceAction event=${eventName ?: "none"} workspace=${workspace ?: state.value.config.workspace}",
        )

        if (serviceAction == RelayService.ACTION_STOP_RELAY) {
            if (!state.value.isServiceRunning) {
                context.stopService(intent)
                return
            }

            context.startService(intent)
            return
        }

        if (!state.value.isServiceRunning || serviceAction == RelayService.ACTION_START_RELAY) {
            ContextCompat.startForegroundService(context, intent)
            return
        }

        context.startService(intent)
    }

    fun startSetup(context: Context) {
        RelayStateStore.setSetupPhase(SetupPhase.PAIRING)
        val matrix = com.openclaw.relay.device.DeviceProfileStorage.loadMatrix(context)
        RelayStateStore.setCapabilityMatrix(matrix)
        // Start mDNS discovery if not already paired
        if (!RelayStateStore.state.value.config.isPaired()) {
            val intent = RelayService.intent(context, RelayService.ACTION_START_RELAY)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    fun skipSetup(context: Context) {
        UserOnboardingManager.markSetupSkipped(context)
        RelayStateStore.setSetupPhase(SetupPhase.NOT_STARTED)
        RelayStateStore.dismissSetupWizard()
    }

    fun probeDevice(context: Context) {
        RelayStateStore.setSetupPhase(SetupPhase.DEVICE_PROBE)
        viewModelScope.launch {
            Log.i(TAG, "setup probe started")
            val registry = com.openclaw.relay.signal.SignalProviderRegistry(context)
            registry.start()

            val observedEvents = mutableListOf<com.openclaw.relay.signal.EarbudSignalEvent>()

            val observationJob = launch {
                registry.allEvents.collect { event ->
                    observedEvents += event
                }
            }

            try {
                val probeResults = registry.probeAll()
                val librePodsResult = probeResults["librepods_airpods"]

                kotlinx.coroutines.delay(8_000)
                observationJob.cancel()

                val detectedDeviceModel = registry.getLibrePodsProvider().deviceState.value?.displayName
                val detectedProviders = buildSet {
                    if (librePodsResult?.detectedDevice == true || detectedDeviceModel != null) {
                        add("librepods_airpods")
                    }
                }

                val phoneModel = android.os.Build.MODEL ?: "Unknown"
                val androidVersion = android.os.Build.VERSION.RELEASE ?: "Unknown"

                val entry = buildCapabilityEntryFromSetup(
                    SetupCapabilityAssessment(
                        deviceModel = detectedDeviceModel,
                        phoneModel = phoneModel,
                        androidVersion = androidVersion,
                        observedEvents = observedEvents.toList(),
                        detectedProviders = detectedProviders,
                    )
                )

                if (!persistCapabilityObservation(context, entry)) {
                    return@launch
                }
                RelayStateStore.setSetupPhase(SetupPhase.GESTURE_TEST)
                Log.i(
                    TAG,
                    "setup probe saved deviceModel=${entry.deviceModel} phoneModel=${entry.phoneModel} providers=${entry.providersObserved.joinToString(",")} wake=${entry.wakeGesture} interrupt=${entry.interruptGesture} approval=${entry.approveRejectGesture} ear=${entry.earDetection} battery=${entry.batteryStatus}",
                )
            } finally {
                observationJob.cancel()
                registry.stop()
                Log.i(TAG, "setup probe cleaned up")
            }
        }
    }

    fun testWake(context: Context) {
        viewModelScope.launch {
            Log.i(TAG, "setup wake test started")
            val previousWake = state.value.lastWakeSignal
            var physicalWakeObserved = false
            var wakeProviderId: String? = null
            val deadline = System.currentTimeMillis() + 10_000

            RelayStateStore.setSetupTestState(
                SetupTestState(
                    isRunning = true,
                    secondsRemaining = 10,
                    statusLabel = "Waiting for earbud signal",
                ),
            )

            try {
                while (System.currentTimeMillis() < deadline) {
                    val remaining = ((deadline - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)
                    val currentWake = state.value.lastWakeSignal

                    if (currentWake != null && currentWake != previousWake &&
                        isDirectHardwareWakeProvider(currentWake.provider.providerId)
                    ) {
                        physicalWakeObserved = true
                        wakeProviderId = currentWake.provider.providerId
                        RelayStateStore.setSetupTestState(
                            SetupTestState(
                                isRunning = true,
                                secondsRemaining = remaining,
                                statusLabel = "Signal received",
                                providerName = currentWake.provider.providerLabel,
                                confidence = currentWake.provider.confidence.name.lowercase(),
                                mappedEvent = currentWake.trigger,
                            ),
                        )
                        kotlinx.coroutines.delay(800)
                        break
                    }

                    RelayStateStore.setSetupTestState(
                        SetupTestState(
                            isRunning = true,
                            secondsRemaining = remaining,
                            statusLabel = "Waiting for earbud signal",
                        ),
                    )
                    kotlinx.coroutines.delay(200)
                }

                val matrix = com.openclaw.relay.device.DeviceProfileStorage.loadMatrix(context)
                val currentDevice = com.openclaw.relay.device.DeviceProfileStorage.getCurrentDevice(context)
                if (currentDevice != null) {
                    val entry = matrix.findEntry(currentDevice.first, currentDevice.second)
                    if (entry != null) {
                        val wakeStatus = when {
                            physicalWakeObserved && isDirectHardwareWakeProvider(wakeProviderId ?: "") ->
                                com.openclaw.relay.device.CapabilityStatus.PROVEN
                            physicalWakeObserved ->
                                com.openclaw.relay.device.CapabilityStatus.FALLBACK_PROVEN
                            else -> entry.wakeGesture
                        }
                        val updated = entry.copy(wakeGesture = wakeStatus)
                        if (!persistCapabilityObservation(context, updated)) {
                            return@launch
                        }
                    }
                }
                RelayStateStore.setSetupPhase(SetupPhase.STT_TEST)
                Log.i(TAG, "setup wake test completed physicalWakeObserved=$physicalWakeObserved wakeProviderId=$wakeProviderId")
            } finally {
                RelayStateStore.setSetupTestState(SetupTestState())
            }
        }
    }

    fun testStt(context: Context) {
        viewModelScope.launch {
            Log.i(TAG, "setup stt test started")
            RelayStateStore.startVoiceProofRun(targetSessionCount = 1)
            val previousTranscript = state.value.lastTranscript
            val previousWake = state.value.lastWakeSignal

            var physicalWakeObserved = false
            var wakeProviderId: String? = null
            var transcriptAfterPhysicalWake = false
            val deadline = System.currentTimeMillis() + 15_000

            RelayStateStore.setSetupTestState(
                SetupTestState(
                    isRunning = true,
                    secondsRemaining = 15,
                    statusLabel = "Waiting for physical wake",
                ),
            )

            try {
                while (System.currentTimeMillis() < deadline) {
                    val remaining = ((deadline - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)
                    val currentTranscript = state.value.lastTranscript
                    val currentWake = state.value.lastWakeSignal

                    if (!physicalWakeObserved && currentWake != null && currentWake != previousWake &&
                        isDirectHardwareWakeProvider(currentWake.provider.providerId)
                    ) {
                        physicalWakeObserved = true
                        wakeProviderId = currentWake.provider.providerId
                        Log.i(TAG, "setup stt test: physical wake observed from ${currentWake.provider.providerId}")
                        RelayStateStore.setSetupTestState(
                            SetupTestState(
                                isRunning = true,
                                secondsRemaining = remaining,
                                statusLabel = "Wake observed · listening for speech",
                                providerName = currentWake.provider.providerLabel,
                                confidence = currentWake.provider.confidence.name.lowercase(),
                                mappedEvent = currentWake.trigger,
                            ),
                        )
                    }

                    if (physicalWakeObserved && currentTranscript.isNotBlank() && currentTranscript != previousTranscript) {
                        transcriptAfterPhysicalWake = true
                        RelayStateStore.setSetupTestState(
                            SetupTestState(
                                isRunning = true,
                                secondsRemaining = remaining,
                                statusLabel = "Transcript captured",
                                mappedEvent = currentTranscript.take(40),
                            ),
                        )
                        kotlinx.coroutines.delay(800)
                        break
                    }

                    if (!physicalWakeObserved) {
                        RelayStateStore.setSetupTestState(
                            SetupTestState(
                                isRunning = true,
                                secondsRemaining = remaining,
                                statusLabel = "Waiting for physical wake",
                            ),
                        )
                    }
                    kotlinx.coroutines.delay(200)
                }

                val proofRun = state.value.voiceDiagnostics.voiceProofRun
                val proofSummary = proofRun.summary
                val sttStatus = when {
                    proofRun.status == com.openclaw.relay.VoiceProofRunStatus.PASSED && !proofSummary.hasBlockingFailures ->
                        com.openclaw.relay.device.CapabilityStatus.PROVEN
                    proofSummary.sttSuccessCount > 0 ->
                        com.openclaw.relay.device.CapabilityStatus.OBSERVED
                    else ->
                        com.openclaw.relay.device.CapabilityStatus.UNPROVEN
                }
                val ttsInterruptStatus = when {
                    proofSummary.interruptionTargetMetCount >= 5 && !proofSummary.hasBlockingFailures ->
                        com.openclaw.relay.device.CapabilityStatus.PROVEN
                    proofRun.ttsInterruptions.isNotEmpty() ->
                        com.openclaw.relay.device.CapabilityStatus.OBSERVED
                    else ->
                        com.openclaw.relay.device.CapabilityStatus.UNPROVEN
                }

                var updatedEntry: DeviceCapabilityEntry? = null
                val matrix = com.openclaw.relay.device.DeviceProfileStorage.loadMatrix(context)
                val currentDevice = com.openclaw.relay.device.DeviceProfileStorage.getCurrentDevice(context)
                if (currentDevice != null) {
                    val entry = matrix.findEntry(currentDevice.first, currentDevice.second)
                    if (entry != null) {
                        val failureNote = if (proofSummary.hasBlockingFailures) {
                            "Proof run ${proofRun.status.name.lowercase()} with blocking failures: ${proofSummary.failureReasons.joinToString(", ")}."
                        } else if (proofRun.status != com.openclaw.relay.VoiceProofRunStatus.PASSED) {
                            "Proof run ${proofRun.status.name.lowercase()}."
                        } else {
                            entry.notes
                        }
                        val wakeStatus = when {
                            physicalWakeObserved && isDirectHardwareWakeProvider(wakeProviderId ?: "") ->
                                com.openclaw.relay.device.CapabilityStatus.PROVEN
                            physicalWakeObserved ->
                                com.openclaw.relay.device.CapabilityStatus.FALLBACK_PROVEN
                            else -> entry.wakeGesture
                        }
                        val updated = entry.copy(
                            wakeGesture = wakeStatus,
                            sttAfterWake = sttStatus,
                            ttsInterruption = ttsInterruptStatus,
                            notes = failureNote,
                        )
                        updatedEntry = updated
                        if (!persistCapabilityObservation(context, updated)) {
                            return@launch
                        }
                    }
                }

                val setupEntry = updatedEntry ?: loadCurrentCapabilityEntry(context)
                val calibrationReady = !state.value.calibrationRequired || state.value.calibrationProfile?.isReadyForRuntime() == true
                val setupPassed = isProofRunCompleteAndProven(proofRun) && setupEntry?.qualifiesForCompleteSetup() == true && calibrationReady
                RelayStateStore.setSetupPhase(
                    if (setupPassed) SetupPhase.COMPLETE_PROVEN else SetupPhase.COMPLETE_DEGRADED
                )
                Log.i(TAG, "setup stt test completed physicalWakeObserved=$physicalWakeObserved transcriptAfterPhysicalWake=$transcriptAfterPhysicalWake proofRun=${proofRun.status} hasBlockingFailures=${proofSummary.hasBlockingFailures} setupPassed=$setupPassed")
            } finally {
                RelayStateStore.setSetupTestState(SetupTestState())
            }
        }
    }

    fun exportDiagnostics(context: Context, options: DiagnosticExportOptions = DiagnosticExportOptions()) {
        DiagnosticExport.share(context, state.value, options, RelayStateStore.voiceDiagnosticsExportSummary())
    }

    fun previewDiagnostics(context: Context, options: DiagnosticExportOptions = DiagnosticExportOptions()): String {
        return DiagnosticExport.toJson(context, state.value, options, RelayStateStore.voiceDiagnosticsExportSummary())
    }

    fun completeSetup(context: Context) {
        UserOnboardingManager.markSetupCompleted(context)
        val proofRun = state.value.voiceDiagnostics.voiceProofRun

        // Build real route proof from proof run telemetry and update saved profile
        val calibrationProfile = state.value.calibrationProfile
        if (calibrationProfile != null) {
            val routeProof = buildRouteProofFromVoiceProofRun(proofRun)
            val updatedProfile = calibrationProfile.copy(routeProof = routeProof)
            com.openclaw.relay.device.DeviceProfileStorage.saveDeviceCalibrationProfile(context, updatedProfile)
            com.openclaw.relay.device.DeviceProfileStorage.recordCalibrationHistory(context, updatedProfile)
            RelayStateStore.setCalibrationProfile(updatedProfile)
        }

        val calibrationReady = state.value.calibrationProfile?.isReadyForRuntime() == true
        val setupPassed = isProofRunCompleteAndProven(proofRun) && loadCurrentCapabilityEntry(context)?.qualifiesForCompleteSetup() == true && calibrationReady
        RelayStateStore.setSetupPhase(
            if (setupPassed) SetupPhase.COMPLETE_PROVEN else SetupPhase.COMPLETE_DEGRADED
        )
        RelayStateStore.dismissSetupWizard()
        ActivityHistoryStore.add(
            context,
            ActivityHistoryEntry(
                type = ActivityEventType.SETUP_COMPLETED,
                summary = "Device setup completed",
            ),
        )
        RelayStateStore.recordActivityHistory(ActivityHistoryStore.load(context))
    }

    fun resetSetup(context: Context) {
        UserOnboardingManager.resetSetup(context)
        RelayStateStore.setSetupPhase(SetupPhase.NOT_STARTED)
    }

    // --- Calibration ---

    fun startCalibration(context: Context) {
        RelayStateStore.setSetupPhase(SetupPhase.CALIBRATION)
        calibrationResults.clear()
        calibrationGestureIndex = 0

        val registry = com.openclaw.relay.signal.SignalProviderRegistry(context)
        registry.start()
        calibrationRegistry = registry

        val engine = com.openclaw.relay.calibration.EarbudCalibrationEngine(registry.allEvents)
        calibrationEngine = engine

        calibrationCollectionJob?.cancel()
        calibrationCollectionJob = viewModelScope.launch {
            engine.sessionState.collect { session ->
                RelayStateStore.setCalibrationSession(session)
                if (session != null && isTerminalCalibrationStatus(session.status)) {
                    val result = engine.buildResult()
                    if (result != null) {
                        calibrationResults.add(result)
                    }
                    delay(500)
                    advanceCalibrationGesture(engine)
                }
            }
        }

        advanceCalibrationGesture(engine)
    }

    private fun isTerminalCalibrationStatus(status: com.openclaw.relay.calibration.CalibrationSessionStatus): Boolean {
        return status == com.openclaw.relay.calibration.CalibrationSessionStatus.COMPLETE ||
            status == com.openclaw.relay.calibration.CalibrationSessionStatus.TIMEOUT ||
            status == com.openclaw.relay.calibration.CalibrationSessionStatus.UNSUPPORTED
    }

    private fun advanceCalibrationGesture(engine: com.openclaw.relay.calibration.EarbudCalibrationEngine) {
        if (calibrationGestureIndex >= defaultCalibrationGestures.size) {
            // All gestures attempted; emit a terminal COMPLETE state so UI shows "Continue"
            RelayStateStore.setCalibrationSession(
                com.openclaw.relay.calibration.CalibrationSessionState(
                    sessionId = "calibration-done",
                    requestedGesture = com.openclaw.relay.signal.GestureType.UNKNOWN,
                    status = com.openclaw.relay.calibration.CalibrationSessionStatus.COMPLETE,
                    attemptNumber = calibrationGestureIndex,
                )
            )
            return
        }
        val gesture = defaultCalibrationGestures[calibrationGestureIndex]
        val sessionId = "calibration-${System.currentTimeMillis()}"
        engine.startGestureCalibration(sessionId, gesture)
        calibrationGestureIndex++
    }

    fun skipCalibration() {
        calibrationCollectionJob?.cancel()
        calibrationEngine?.cancelCalibration()
        calibrationEngine = null
        calibrationRegistry?.stop()
        calibrationRegistry = null
        calibrationResults.clear()
        calibrationGestureIndex = 0
        RelayStateStore.setCalibrationRequired(true)
        RelayStateStore.setCalibrationSession(null)
        RelayStateStore.setSetupPhase(SetupPhase.GESTURE_TEST)
    }

    fun setGestureAction(gestureType: com.openclaw.relay.signal.GestureType, action: com.openclaw.relay.calibration.GestureAction) {
        val profile = state.value.calibrationProfile ?: return
        val calibratedGesture = profile.calibratedGestures.find { it.requestedGesture == gestureType }

        // Reject mapping to unsupported, observed-only, or ambiguous gestures
        if (calibratedGesture == null || calibratedGesture.confidence != com.openclaw.relay.calibration.CalibrationConfidence.PROVEN) {
            RelayStateStore.setError("Cannot map action to a gesture that is not proven.")
            return
        }

        // Reject approval/reject mappings for ambiguous or fallback-only signals
        if ((action == com.openclaw.relay.calibration.GestureAction.APPROVE || action == com.openclaw.relay.calibration.GestureAction.REJECT)
            && calibratedGesture.collisionWith != null
        ) {
            RelayStateStore.setError("Cannot map approval or reject to an ambiguous gesture.")
            return
        }

        val currentMap = profile.gestureActionMap.mappings.toMutableMap()
        currentMap[gestureType] = action
        RelayStateStore.setGestureActionMap(com.openclaw.relay.calibration.GestureActionMap(currentMap.toMap()))
    }

    fun completeGestureMapping(context: Context) {
        val profile = state.value.calibrationProfile
        if (profile != null) {
            // Require at least one proven gesture mapped to WAKE_AND_LISTEN
            val hasWakeMapping = profile.gestureActionMap.mappings.any { it.value == com.openclaw.relay.calibration.GestureAction.WAKE_AND_LISTEN }
            if (!hasWakeMapping) {
                RelayStateStore.setError("Please assign a proven gesture to Wake & Listen before continuing.")
                return
            }
            com.openclaw.relay.device.DeviceProfileStorage.saveDeviceCalibrationProfile(context, profile)
            com.openclaw.relay.device.DeviceProfileStorage.recordCalibrationHistory(context, profile)
        }
        RelayStateStore.setSetupPhase(SetupPhase.GESTURE_TEST)
    }

    fun finishCalibration(context: Context) {
        calibrationCollectionJob?.cancel()
        calibrationEngine?.cancelCalibration()
        calibrationEngine = null
        calibrationRegistry?.stop()
        calibrationRegistry = null

        val profile = buildCalibrationProfile(context)
        if (profile != null) {
            com.openclaw.relay.device.DeviceProfileStorage.saveDeviceCalibrationProfile(context, profile)
            com.openclaw.relay.device.DeviceProfileStorage.setCurrentDeviceHash(context, profile.deviceAddressHash)
            RelayStateStore.setCalibrationProfile(profile)
            // Link profile to current capability entry and update proven statuses
            linkCalibrationProfileToCapabilityEntry(context, profile)
            com.openclaw.relay.device.DeviceProfileStorage.recordCalibrationHistory(context, profile)
        } else {
            RelayStateStore.setCalibrationRequired(true)
        }
        RelayStateStore.setCalibrationSession(null)
        val nextPhase = if (profile?.provenGestures()?.isNotEmpty() == true) {
            SetupPhase.GESTURE_MAPPING
        } else {
            SetupPhase.GESTURE_TEST
        }
        RelayStateStore.setSetupPhase(nextPhase)
    }

    private fun linkCalibrationProfileToCapabilityEntry(
        context: Context,
        profile: com.openclaw.relay.calibration.EarbudCalibrationProfile,
    ) {
        val matrix = com.openclaw.relay.device.DeviceProfileStorage.loadMatrix(context)
        val currentDevice = com.openclaw.relay.device.DeviceProfileStorage.getCurrentDevice(context)
        if (currentDevice != null) {
            val entry = matrix.findEntry(currentDevice.first, currentDevice.second)
            if (entry != null) {
                val updated = com.openclaw.relay.device.updateCapabilityEntryFromCalibration(entry, profile)
                com.openclaw.relay.device.DeviceProfileStorage.saveMatrix(
                    context,
                    matrix.copy(entries = matrix.entries.map { if (it == entry) updated else it })
                )
                RelayStateStore.setCapabilityMatrix(com.openclaw.relay.device.DeviceProfileStorage.loadMatrix(context))
            }
        }
    }

    fun resetCalibration(context: Context) {
        val existingProfile = state.value.calibrationProfile
        if (existingProfile != null) {
            com.openclaw.relay.device.DeviceProfileStorage.recordCalibrationHistory(context, existingProfile)
        }
        calibrationCollectionJob?.cancel()
        calibrationEngine?.cancelCalibration()
        calibrationEngine = null
        calibrationRegistry?.stop()
        calibrationRegistry = null
        calibrationResults.clear()
        calibrationGestureIndex = 0
        com.openclaw.relay.device.DeviceProfileStorage.clearDeviceCalibrationProfile(context)
        com.openclaw.relay.device.DeviceProfileStorage.clearRuntimeMissCount(context)
        RelayStateStore.clearCalibration()
    }

    private fun buildCalibrationProfile(
        context: Context,
    ): com.openclaw.relay.calibration.EarbudCalibrationProfile? {
        if (calibrationResults.isEmpty()) return null

        val currentDevice = com.openclaw.relay.device.DeviceProfileStorage.getCurrentDevice(context)
        val matrix = com.openclaw.relay.device.DeviceProfileStorage.loadMatrix(context)
        val entry = currentDevice?.let { matrix.findEntry(it.first, it.second) }

        // Detect collisions
        val resultsWithCollisions = calibrationResults.map { gesture ->
            val collision = detectCollision(gesture, calibrationResults)
            if (collision != null) {
                gesture.copy(confidence = com.openclaw.relay.calibration.CalibrationConfidence.AMBIGUOUS, collisionWith = collision)
            } else {
                gesture
            }
        }

        // Action map starts empty; user assigns actions in GESTURE_MAPPING phase
        val actionMap = com.openclaw.relay.calibration.GestureActionMap()

        val deviceModel = entry?.deviceModel ?: currentDevice?.first ?: "Unknown"
        val phoneModel = currentDevice?.second ?: android.os.Build.MODEL ?: "Unknown"
        val currentDeviceState = state.value.currentDeviceState
        val deviceId = currentDeviceState?.deviceId
        val deviceAddressHash = if (!deviceId.isNullOrBlank()) {
            com.openclaw.relay.device.DeviceProfileStorage.hashDeviceIdentity(deviceId)
        } else {
            com.openclaw.relay.device.DeviceProfileStorage.hashDeviceIdentity(deviceModel)
        }
        val isModelScoped = deviceId.isNullOrBlank()

        // Derive timing thresholds from actual calibration observations
        val pressDurations = calibrationResults.mapNotNull { it.pressDurationMs }
        val interTapIntervals = calibrationResults.mapNotNull { it.interTapIntervalMs }
        val avgPressDuration = if (pressDurations.isNotEmpty()) pressDurations.average().toLong() else 0L
        val avgInterTap = if (interTapIntervals.isNotEmpty()) interTapIntervals.average().toLong() else 0L
        val derivedLongPressThreshold = if (pressDurations.size >= 2) {
            // Conservative: at least 1.5x average press duration, bounded between 500ms and 1200ms
            avgPressDuration.coerceIn(300L, 800L) * 15 / 10
        } else 700L
        val derivedMultiTapWindow = if (interTapIntervals.size >= 2) {
            // Conservative: 1.2x average inter-tap interval, bounded between 250ms and 600ms
            avgInterTap.coerceIn(200L, 500L) * 12 / 10
        } else 400L

        return com.openclaw.relay.calibration.EarbudCalibrationProfile(
            profileId = "profile-${System.currentTimeMillis()}",
            deviceModel = deviceModel,
            deviceAddressHash = deviceAddressHash,
            phoneModel = phoneModel,
            androidVersion = android.os.Build.VERSION.RELEASE ?: "Unknown",
            providerId = entry?.providersObserved?.firstOrNull() ?: "unknown",
            calibratedGestures = resultsWithCollisions,
            gestureActionMap = actionMap,
            routeProof = null,
            isModelScoped = isModelScoped,
            longPressThresholdMs = derivedLongPressThreshold.coerceIn(500L, 1200L),
            multiTapWindowMs = derivedMultiTapWindow.coerceIn(250L, 600L),
            appVersion = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            } catch (_: Exception) { null },
            providerIdAtCreation = entry?.providersObserved?.firstOrNull(),
            runtimeMissCountAtCreation = state.value.runtimeMissCount,
        )
    }

    private fun detectCollision(
        gesture: com.openclaw.relay.calibration.CalibratedGesture,
        all: List<com.openclaw.relay.calibration.CalibratedGesture>,
    ): com.openclaw.relay.signal.GestureType? {
        val fingerprint = gesture.fingerprint ?: return null
        return all.find { other ->
            other.requestedGesture != gesture.requestedGesture &&
                other.confidence != com.openclaw.relay.calibration.CalibrationConfidence.UNSUPPORTED &&
                fingerprintsCollide(fingerprint, other.fingerprint)
        }?.requestedGesture
    }

    private fun fingerprintsCollide(
        a: com.openclaw.relay.calibration.SignalFingerprint?,
        b: com.openclaw.relay.calibration.SignalFingerprint?,
    ): Boolean {
        if (a == null || b == null) return false
        if (a.providerId != b.providerId) return false

        // If both have keyCodes (MediaSession path), compare underlying raw signal.
        if (a.keyCode != null && b.keyCode != null) {
            return a.keyCode == b.keyCode &&
                (a.keyAction == null || b.keyAction == null || a.keyAction == b.keyAction)
        }

        // Fallback for non-MediaSession providers
        return a.gestureType == b.gestureType
    }

    // --- Settings APIs ---

    fun loadNotificationPreferences() {
        val config = RelayStateStore.state.value.config
        if (!config.isPaired()) return
        viewModelScope.launch {
            pairingBridgeClient.getNotificationPreferences(config)
                .onSuccess { RelayStateStore.setNotificationPreference(it.value) }
                .onFailure { Log.w(TAG, "Failed to load notification preferences", it) }
        }
    }

    fun saveNotificationPreferences(preference: NotificationPreference) {
        val config = RelayStateStore.state.value.config
        if (!config.isPaired()) return
        viewModelScope.launch {
            pairingBridgeClient.setNotificationPreferences(config, preference)
                .onSuccess { RelayStateStore.setNotificationPreference(it.value) }
                .onFailure { Log.w(TAG, "Failed to save notification preferences", it) }
        }
    }

    fun loadReminders() {
        val config = RelayStateStore.state.value.config
        if (!config.isPaired()) return
        viewModelScope.launch {
            pairingBridgeClient.listReminders(config)
                .onSuccess { RelayStateStore.setReminders(it.value.reminders) }
                .onFailure { Log.w(TAG, "Failed to load reminders", it) }
        }
    }

    fun cancelReminder(reminderId: String) {
        val config = RelayStateStore.state.value.config
        if (!config.isPaired()) return
        viewModelScope.launch {
            pairingBridgeClient.cancelReminder(config, reminderId)
                .onSuccess {
                    if (it.value) {
                        RelayStateStore.removeReminder(reminderId)
                    }
                }
                .onFailure { Log.w(TAG, "Failed to cancel reminder", it) }
        }
    }

    fun createReminder(summary: String, dueAtMs: Long) {
        val config = RelayStateStore.state.value.config
        if (!config.isPaired()) {
            RelayStateStore.setError("Pair the desktop bridge before creating reminders.")
            return
        }
        val reminder = Reminder(
            id = "reminder-${System.currentTimeMillis()}",
            sessionId = config.sessionId,
            summary = summary,
            createdAtMs = System.currentTimeMillis(),
            dueAtMs = dueAtMs,
        )
        viewModelScope.launch {
            pairingBridgeClient.createReminder(config, reminder)
                .onSuccess { RelayStateStore.addReminder(it.value) }
                .onFailure { Log.w(TAG, "Failed to create reminder", it) }
        }
    }

    fun runSherpaBenchmark(context: Context, samples: List<CommandBenchmarkSample>) {
        val includeRawTranscript = state.value.config.benchmarkDiagnosticsEnabled
        val report = OfflineSpeechEvaluation.evaluateCommandBenchmark(samples, context, includeRawTranscript)
        RelayStateStore.recordSherpaBenchmarkReport(report)
    }

    /**
     * Runs an interactive Sherpa benchmark by dispatching each sample collection to the
     * service-owned [RelayService.collectBenchmarkSample]. The ViewModel waits on the
     * service-reported result instead of polling global transcript state.
     */
    fun runInteractiveSherpaBenchmark(context: Context) {
        val commands = SherpaCommandBenchmarkSet.COMMANDS
        val engines = listOf(
            CommandBenchmarkEngine.SHERPA_STT,
            CommandBenchmarkEngine.PLATFORM_STT,
            CommandBenchmarkEngine.PLATFORM_ON_DEVICE_STT,
        )
        val collectedSamples = mutableListOf<CommandBenchmarkSample>()
        val originalMode = state.value.config.speechInputMode

        viewModelScope.launch {
            RelayStateStore.startBenchmarkSession(commands.size * engines.size, commands.first(), engines.first())

            try {
                for (engine in engines) {
                    val expectedRuntimeId = when (engine) {
                        CommandBenchmarkEngine.SHERPA_STT -> "sherpa_streaming"
                        CommandBenchmarkEngine.PLATFORM_STT -> "platform_speech_recognizer"
                        CommandBenchmarkEngine.PLATFORM_ON_DEVICE_STT -> "platform_on_device_speech_recognizer"
                    }

                    val targetMode = when (engine) {
                        CommandBenchmarkEngine.SHERPA_STT -> SpeechInputMode.SHERPA_EVALUATION
                        CommandBenchmarkEngine.PLATFORM_ON_DEVICE_STT -> SpeechInputMode.PLATFORM_ON_DEVICE
                        else -> SpeechInputMode.PLATFORM
                    }
                    if (state.value.config.speechInputMode != targetMode) {
                        RelayStateStore.updateConfig { it.copy(speechInputMode = targetMode) }
                        RelayConfigStorage.save(context, state.value.config)
                    }

                    // Wait for service to recreate engine and report the expected runtime ID
                    val engineSwitchStartedAt = System.currentTimeMillis()
                    while (System.currentTimeMillis() - engineSwitchStartedAt < 5_000L) {
                        if (RelayStateStore.state.value.currentSpeechEngineId == expectedRuntimeId) break
                        kotlinx.coroutines.delay(100)
                    }
                    val actualEngineId = RelayStateStore.state.value.currentSpeechEngineId
                    if (actualEngineId != expectedRuntimeId) {
                        Log.w(TAG, "Engine switch did not complete in time for $engine (expected=$expectedRuntimeId, actual=$actualEngineId)")
                    }

                    for ((index, command) in commands.withIndex()) {
                        RelayStateStore.advanceBenchmarkCommand(
                            index = index + (engines.indexOf(engine) * commands.size),
                            command = command,
                            engine = engine,
                            samplesCollected = collectedSamples.size,
                        )

                        // Clear any stale benchmark sample before dispatching
                        RelayStateStore.clearBenchmarkSample()

                        // Dispatch service-owned benchmark collection
                        val intent = RelayService.intent(context, RelayService.ACTION_COLLECT_BENCHMARK_SAMPLE).apply {
                            putExtra(RelayService.EXTRA_BENCHMARK_COMMAND, command)
                            putExtra(RelayService.EXTRA_BENCHMARK_ENGINE, engine.name)
                        }
                        ContextCompat.startForegroundService(context, intent)

                        // Wait for the service to record the sample result
                        val sampleStartedAt = System.currentTimeMillis()
                        var sample: CommandBenchmarkSample? = null
                        while (System.currentTimeMillis() - sampleStartedAt < 20_000L) {
                            sample = RelayStateStore.state.value.lastBenchmarkSample
                            if (sample != null) break
                            kotlinx.coroutines.delay(200)
                        }

                        if (sample == null) {
                            Log.w(TAG, "Benchmark sample timed out for command='$command' engine=$engine")
                            collectedSamples.add(
                                CommandBenchmarkSample(
                                    command = command,
                                    engine = engine,
                                    noSpeechDetected = true,
                                ),
                            )
                        } else {
                            collectedSamples.add(sample)
                            RelayStateStore.recordBenchmarkTranscript(
                                transcript = sample.transcriptText.orEmpty(),
                                latencyMs = sample.finalTranscriptMs,
                            )
                            RelayStateStore.clearBenchmarkSample()
                        }

                        kotlinx.coroutines.delay(800)
                    }
                }

                val includeRawTranscript = state.value.config.benchmarkDiagnosticsEnabled
                val report = OfflineSpeechEvaluation.evaluateCommandBenchmark(collectedSamples, context, includeRawTranscript)
                RelayStateStore.recordSherpaBenchmarkReport(report)
                RelayStateStore.setSherpaPromotionState(report.promotionState)
            } finally {
                if (state.value.config.speechInputMode != originalMode) {
                    RelayStateStore.updateConfig { it.copy(speechInputMode = originalMode) }
                    RelayConfigStorage.save(context, state.value.config)
                }
                RelayStateStore.finishBenchmarkSession()
            }
        }
    }

    fun toggleOfflineCommandRecognition(context: Context, enabled: Boolean) {
        val promotionState = state.value.voiceDiagnostics.sherpaPromotionState
        if (enabled && promotionState.ordinal < SherpaPromotionState.EXPERIMENTAL.ordinal) {
            RelayStateStore.setError("Sherpa command recognition requires benchmark promotion state EXPERIMENTAL or higher.")
            return
        }
        val newMode = if (enabled) SpeechInputMode.SHERPA_EVALUATION else SpeechInputMode.PLATFORM
        RelayStateStore.updateConfig { it.copy(speechInputMode = newMode) }
        RelayStateStore.recordOfflineSpeechReadiness(OfflineSpeechReadiness.evaluate(state.value.config))
        RelayConfigStorage.save(context, state.value.config)
    }

    fun updateLatencyFlag(context: Context, copy: (RelayConfig) -> RelayConfig) {
        RelayStateStore.updateConfig(copy)
        RelayConfigStorage.save(context, state.value.config)
    }

    fun loadLearnedPhrases() {
        val config = RelayStateStore.state.value.config
        if (!config.isPaired()) return
        viewModelScope.launch {
            pairingBridgeClient.listHabits(config)
                .onSuccess { RelayStateStore.setLearnedPhrases(it.value.phrases) }
                .onFailure { Log.w(TAG, "Failed to load learned phrases", it) }
        }
    }

    fun deleteLearnedPhrase(phrase: String) {
        val config = RelayStateStore.state.value.config
        if (!config.isPaired()) return
        viewModelScope.launch {
            pairingBridgeClient.deleteHabit(config, phrase)
                .onSuccess {
                    if (it.value) {
                        RelayStateStore.removeLearnedPhrase(phrase)
                    }
                }
                .onFailure { Log.w(TAG, "Failed to delete learned phrase", it) }
        }
    }

    fun saveLearnedPhrase(phrase: LearnedPhrase) {
        val config = RelayStateStore.state.value.config
        if (!config.isPaired()) return
        viewModelScope.launch {
            pairingBridgeClient.createHabit(config, phrase)
                .onSuccess { loadLearnedPhrases() }
                .onFailure { Log.w(TAG, "Failed to save learned phrase", it) }
        }
    }

    fun resetLearnedPhrases() {
        val config = RelayStateStore.state.value.config
        if (!config.isPaired()) return
        val phrases = RelayStateStore.state.value.learnedPhrases.map { it.phrase }
        viewModelScope.launch {
            phrases.forEach { phrase ->
                pairingBridgeClient.deleteHabit(config, phrase)
                    .onFailure { Log.w(TAG, "Failed to delete learned phrase during reset", it) }
            }
            RelayStateStore.setLearnedPhrases(emptyList())
        }
    }

    fun loadNudgePolicy() {
        val config = RelayStateStore.state.value.config
        if (!config.isPaired()) return
        viewModelScope.launch {
            pairingBridgeClient.getNudgePolicy(config)
                .onSuccess { RelayStateStore.setNudgePolicy(it.value) }
                .onFailure { Log.w(TAG, "Failed to load nudge policy", it) }
        }
    }

    fun saveNudgePolicy(policy: NudgePolicy) {
        val config = RelayStateStore.state.value.config
        if (!config.isPaired()) return
        viewModelScope.launch {
            pairingBridgeClient.setNudgePolicy(config, policy)
                .onSuccess { RelayStateStore.setNudgePolicy(it.value) }
                .onFailure { Log.w(TAG, "Failed to save nudge policy", it) }
        }
    }

    private fun persistCapabilityObservation(
        context: Context,
        entry: DeviceCapabilityEntry,
    ): Boolean {
        val persisted = com.openclaw.relay.device.DeviceProfileStorage.recordObservation(context, entry)
        if (!persisted) {
            Log.w(TAG, "Failed to persist device capability observation for deviceModel=${entry.deviceModel} phoneModel=${entry.phoneModel}")
            RelayStateStore.setError(
                "DevPods could not save the device capability profile. Retry setup after restarting the app or freeing some storage.",
            )
            return false
        }

        RelayStateStore.setCapabilityMatrix(com.openclaw.relay.device.DeviceProfileStorage.loadMatrix(context))
        RelayStateStore.clearError()
        return true
    }

    private fun resolveStoredSetupPhase(
        context: Context,
        matrix: com.openclaw.relay.device.DeviceCapabilityMatrix,
    ): SetupPhase {
        val currentEntry = loadCurrentCapabilityEntry(context, matrix)
        val profileReady = RelayStateStore.state.value.calibrationProfile?.isReadyForRuntime() == true
        return if (currentEntry?.qualifiesForCompleteSetup() == true && profileReady) {
            SetupPhase.COMPLETE_PROVEN
        } else {
            SetupPhase.COMPLETE_DEGRADED
        }
    }

    private fun loadCurrentCapabilityEntry(
        context: Context,
        matrix: com.openclaw.relay.device.DeviceCapabilityMatrix = com.openclaw.relay.device.DeviceProfileStorage.loadMatrix(context),
    ): DeviceCapabilityEntry? {
        val currentDevice = com.openclaw.relay.device.DeviceProfileStorage.getCurrentDevice(context)
        if (currentDevice != null) {
            return matrix.findEntry(currentDevice.first, currentDevice.second)
        }

        return matrix.entries.singleOrNull()
    }

    private fun buildRouteProofFromVoiceProofRun(proofRun: VoiceProofRun): com.openclaw.relay.calibration.RouteProofResult {
        val summary = proofRun.summary
        val sttSuccess = summary.sttSuccessCount > 0
        val ttsSuccess = proofRun.ttsInterruptions.any { it.ttsStoppedAtMs != null }
        val bargeInSuccess = summary.interruptionTargetMetCount > 0
        val isSuccess = sttSuccess && ttsSuccess && bargeInSuccess && !summary.hasBlockingFailures
        val failureReasons = summary.failureReasons.toMutableList()
        if (!sttSuccess) failureReasons.add("stt_not_proven")
        if (!ttsSuccess) failureReasons.add("tts_not_proven")
        if (!bargeInSuccess) failureReasons.add("barge_in_not_proven")
        return com.openclaw.relay.calibration.RouteProofResult(
            sttSuccess = sttSuccess,
            ttsSuccess = ttsSuccess,
            bargeInSuccess = bargeInSuccess,
            isSuccess = isSuccess,
            testedAtMs = System.currentTimeMillis(),
            failureReasons = failureReasons,
        )
    }

    private fun isProofRunCompleteAndProven(proofRun: VoiceProofRun): Boolean {
        val proofSummary = proofRun.summary
        return proofRun.status == VoiceProofRunStatus.PASSED && !proofSummary.hasBlockingFailures
    }

    private fun DeviceCapabilityEntry.qualifiesForCompleteSetup(): Boolean {
        return wakeGesture == com.openclaw.relay.device.CapabilityStatus.PROVEN &&
            sttAfterWake == com.openclaw.relay.device.CapabilityStatus.PROVEN &&
            ttsInterruption == com.openclaw.relay.device.CapabilityStatus.PROVEN
    }
}
