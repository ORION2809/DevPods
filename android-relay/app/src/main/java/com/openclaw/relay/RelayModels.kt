package com.openclaw.relay

import kotlinx.serialization.Serializable

data class RelayConfig(
    val bridgeBaseUrl: String = "",
    val relayToken: String = "",
    val workspace: String = "current_repo",
    val sessionId: String = "android-relay",
    val useBluetoothRouting: Boolean = true,
    val phoneMicFallback: Boolean = false,
    val assistantFallback: Boolean = true,
    val speechInputMode: SpeechInputMode = SpeechInputMode.PLATFORM,
    val offlineSpeechModelPath: String = "",
    val offlineSpeechModelVersion: String = "",
    val offlineSpeechModelSha256: String = "",
)

fun RelayConfig.isPaired(): Boolean = bridgeBaseUrl.trim().isNotBlank()

data class RelayLatencySnapshot(
    val lastHealthMs: Long? = null,
    val lastBridgeCommandMs: Long? = null,
    val lastSpeechStartedAtMs: Long? = null,
)

data class RelayWakeSignal(
    val trigger: String,
    val source: String,
    val sourceLabel: String,
    val provider: RelayObservedSignalProvider,
    val keyLabel: String? = null,
    val controllerPackage: String? = null,
    val receivedAtMs: Long = System.currentTimeMillis(),
    val hardwareContext: com.openclaw.relay.signal.HardwareContext? = null,
)

data class RelayAudioRouteSnapshot(
    val isActive: Boolean = false,
    val isReadyForSpeechCapture: Boolean = false,
    val isPhoneMicFallback: Boolean = false,
    val status: String = "Not verified",
    val selectedDeviceName: String? = null,
    val selectedDeviceType: String? = null,
    val communicationDeviceName: String? = null,
    val communicationDeviceType: String? = null,
    val availableDevices: String = "none",
    val proof: AudioRouteProof = AudioRouteProof(),
)

data class BridgeQueueState(
    val queuedCount: Int = 0,
    val retryAttempt: Int = 0,
    val nextRetryMs: Long? = null,
    val retryAtMs: Long? = null,
)

data class AutonomyUiState(
    val phase: String = "",
    val nextStep: String? = null,
    val countdownMs: Long? = null,
    val autonomyContinueAtMs: Long? = null,
    val canStop: Boolean = false,
)

data class RelayUiState(
    val config: RelayConfig = RelayConfig(),
    val pendingPairingUri: String = "",
    val isImportingPairing: Boolean = false,
    val isServiceRunning: Boolean = false,
    val isListening: Boolean = false,
    val isAwaitingBridgeResponse: Boolean = false,
    val isSpeaking: Boolean = false,
    val lastHeadsetEvent: String? = null,
    val lastWakeSignal: RelayWakeSignal? = null,
    val signalProviderSummary: RelaySignalProviderSummary = RelaySignalProviderSummary(),
    val bridgeStatus: String = "Unknown",
    val speechRecognitionAvailable: Boolean = false,
    val ttsReady: Boolean = false,
    val audioRoute: RelayAudioRouteSnapshot = RelayAudioRouteSnapshot(),
    val lastTranscript: String = "",
    val partialTranscript: String = "",
    val lastResponseSpeak: String = "",
    val lastResponseDisplay: String = "",
    val lastResponseStatus: String? = null,
    val pendingActionId: String? = null,
    val pendingApprovalRequest: BridgeApprovalRequest? = null,
    val pendingApprovalReceivedAtMs: Long? = null,
    val activeAutonomy: BridgeAutonomyInstruction? = null,
    val latency: RelayLatencySnapshot = RelayLatencySnapshot(),
    val lastSpeechError: String? = null,
    val lastTtsError: String? = null,
    val errorMessage: String? = null,
    val listenReadiness: com.openclaw.relay.signal.ListenReadiness = com.openclaw.relay.signal.ListenReadiness.BLOCKED,
    val listenReadinessMessage: String = "",
    val currentDeviceState: com.openclaw.relay.signal.EarbudDeviceState? = null,
    val capabilityMatrix: com.openclaw.relay.device.DeviceCapabilityMatrix = com.openclaw.relay.device.DeviceCapabilityMatrix(),
    val showSetupWizard: Boolean = false,
    val setupPhase: SetupPhase = SetupPhase.NOT_STARTED,
    val userFacingErrorMessage: String? = null,
    val showOnboarding: Boolean = false,
    val phoneMicFallback: Boolean = false,
    val assistantFallback: Boolean = true,
    val bridgeQueueState: BridgeQueueState = BridgeQueueState(),
    val autonomyUiState: AutonomyUiState = AutonomyUiState(),
    val lastBridgeHealth: BridgeHealthResponse? = null,
    val setupTestState: SetupTestState = SetupTestState(),
    val activityHistory: List<com.openclaw.relay.history.ActivityHistoryEntry> = emptyList(),
    val providerHealth: List<com.openclaw.relay.signal.ProviderHealthUi> = emptyList(),
    val preferredProviderId: String? = null,
    val voiceDiagnostics: VoiceDiagnosticsSnapshot = VoiceDiagnosticsSnapshot(),
) {
    val pendingApprovalSummary: String?
        get() = pendingApprovalRequest?.summary
}

@Serializable
data class BridgeApprovalRequest(
    val actionType: String,
    val summary: String,
    val riskClass: String,
    val expiresInMs: Int,
)

@Serializable
data class BridgeAutonomyInstruction(
    val phase: String,
    val mode: String,
    val summary: String,
    val nextStep: String? = null,
    val continueAfterMs: Int? = null,
    val nextIntent: String? = null,
)

@Serializable
data class BridgeJarvisResponse(
    val speak: String,
    val display: String? = null,
    val requiresApproval: Boolean,
    val approvalRequest: BridgeApprovalRequest? = null,
    val actionId: String? = null,
    val status: String,
    val nextState: String,
    val followUpHint: String? = null,
    val autonomy: BridgeAutonomyInstruction? = null,
)

@Serializable
data class BridgeHealthResponse(
    val ok: Boolean,
    val brainMode: String,
    val openclawTransport: String? = null,
    val openclawRewritePolicy: String? = null,
    val openclawReady: Boolean,
    val bridgeVersion: String = "1.0.0",
    val protocolVersion: Int = 1,
    val minAppVersion: String = BuildConfig.VERSION_NAME,
)

@Serializable
data class RelayBridgeEvent(
    val source: String = "android_relay",
    val sessionId: String,
    val workspace: String,
    val device: String = "both_buds",
    val event: String,
    val timestamp: Long,
    val utterance: String? = null,
    val pendingActionId: String? = null,
    val profile: String? = "default",
    val hardwareContext: com.openclaw.relay.signal.HardwareContext? = null,
)

data class TimedBridgeResult<T>(
    val value: T,
    val durationMs: Long,
)

enum class SetupPhase {
    NOT_STARTED,
    PAIRING,
    DEVICE_PROBE,
    GESTURE_TEST,
    STT_TEST,
    COMPLETE,
}

data class SetupTestState(
    val isRunning: Boolean = false,
    val secondsRemaining: Int = 0,
    val statusLabel: String = "",
    val providerName: String = "",
    val confidence: String = "",
    val mappedEvent: String = "",
)
