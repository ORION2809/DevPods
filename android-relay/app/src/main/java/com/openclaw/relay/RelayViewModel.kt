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
import kotlinx.coroutines.launch

class RelayViewModel(
    private val pairingBridgeClient: BridgeClient = BridgeClient(),
) : ViewModel() {
    companion object {
        private const val TAG = "OpenClawRelay"
    }

    val state: StateFlow<RelayUiState> = RelayStateStore.state
    private var pairingVerificationJob: Job? = null

    fun initialize(context: Context) {
        loadSavedConfig(context)
        RelayStateStore.setCapabilityMatrix(com.openclaw.relay.device.DeviceProfileStorage.loadMatrix(context))

        val onboarding = UserOnboardingManager.load(context)
        RelayStateStore.setShowOnboarding(!onboarding.hasSeenOnboarding)
        if (onboarding.hasCompletedSetup) {
            RelayStateStore.setSetupPhase(SetupPhase.COMPLETE)
        }
    }

    fun loadSavedConfig(context: Context) {
        val savedConfig = RelayConfigStorage.load(context) ?: return
        RelayStateStore.updateConfig { savedConfig }
        RelayStateStore.setPhoneMicFallback(savedConfig.phoneMicFallback)
        RelayStateStore.setAssistantFallback(savedConfig.assistantFallback)
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

    private fun applyImportedPairingConfig(context: Context, config: RelayConfig) {
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
                        val updated = entry.copy(
                            wakeGesture = if (physicalWakeObserved) {
                                com.openclaw.relay.device.CapabilityStatus.PROVEN
                            } else {
                                entry.wakeGesture
                            },
                        )
                        if (!persistCapabilityObservation(context, updated)) {
                            return@launch
                        }
                    }
                }
                RelayStateStore.setSetupPhase(SetupPhase.STT_TEST)
                Log.i(TAG, "setup wake test completed physicalWakeObserved=$physicalWakeObserved")
            } finally {
                RelayStateStore.setSetupTestState(SetupTestState())
            }
        }
    }

    fun testStt(context: Context) {
        viewModelScope.launch {
            Log.i(TAG, "setup stt test started")
            val previousTranscript = state.value.lastTranscript
            val previousWake = state.value.lastWakeSignal

            var physicalWakeObserved = false
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

                val sttProven = transcriptAfterPhysicalWake

                val matrix = com.openclaw.relay.device.DeviceProfileStorage.loadMatrix(context)
                val currentDevice = com.openclaw.relay.device.DeviceProfileStorage.getCurrentDevice(context)
                if (currentDevice != null) {
                    val entry = matrix.findEntry(currentDevice.first, currentDevice.second)
                    if (entry != null) {
                        val updated = entry.copy(
                            wakeGesture = if (physicalWakeObserved) {
                                com.openclaw.relay.device.CapabilityStatus.PROVEN
                            } else {
                                entry.wakeGesture
                            },
                            sttAfterWake = if (sttProven) {
                                com.openclaw.relay.device.CapabilityStatus.PROVEN
                            } else {
                                entry.sttAfterWake
                            },
                        )
                        if (!persistCapabilityObservation(context, updated)) {
                            return@launch
                        }
                    }
                }
                RelayStateStore.setSetupPhase(SetupPhase.COMPLETE)
                Log.i(TAG, "setup stt test completed physicalWakeObserved=$physicalWakeObserved transcriptAfterPhysicalWake=$transcriptAfterPhysicalWake sttProven=$sttProven")
            } finally {
                RelayStateStore.setSetupTestState(SetupTestState())
            }
        }
    }

    fun exportDiagnostics(context: Context, options: DiagnosticExportOptions = DiagnosticExportOptions()) {
        DiagnosticExport.share(context, state.value, options)
    }

    fun previewDiagnostics(context: Context, options: DiagnosticExportOptions = DiagnosticExportOptions()): String {
        return DiagnosticExport.toJson(context, state.value, options)
    }

    fun completeSetup(context: Context) {
        UserOnboardingManager.markSetupCompleted(context)
        RelayStateStore.setSetupPhase(SetupPhase.COMPLETE)
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
}
