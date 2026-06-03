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
    val sherpaRuntimeEnabled: Boolean = false,
    val sherpaVadDiagnosticsEnabled: Boolean = false,
    val sherpaSttExperimentalEnabled: Boolean = false,
    val sherpaModelDownloadsEnabled: Boolean = false,
    // Latency optimization feature flags
    val fastWakeEnabled: Boolean = false,
    val ttsWarmKeepaliveEnabled: Boolean = false,
    val speechRecognizerPrewarmEnabled: Boolean = false,
    val preferredProviderOrderingEnabled: Boolean = false,
    val speculativeRoutePrepareEnabled: Boolean = false,
    val bridgePrefetchOnWakeEnabled: Boolean = false,
    val eventStreamingEnabled: Boolean = false,
    val latencySummaryExportEnabled: Boolean = false,
    val remoteModeEnabled: Boolean = false,
    val benchmarkDiagnosticsEnabled: Boolean = false,
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
    val calibratedGestureType: com.openclaw.relay.signal.GestureType? = null,
    val matchedCalibratedAction: String? = null,
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
    val speechSessionState: SpeechSessionState = SpeechSessionState.IDLE,
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
    val calibrationProfile: com.openclaw.relay.calibration.EarbudCalibrationProfile? = null,
    val calibrationSession: com.openclaw.relay.calibration.CalibrationSessionState? = null,
    val calibrationRequired: Boolean = false,
    val recentUnmatchedSignals: List<UnmatchedSignalRecord> = emptyList(),
    val recentMatchedSignals: List<MatchedSignalRecord> = emptyList(),
    val runtimeMissCount: Int = 0,
    val outboxEvents: List<BridgeOutboxEvent> = emptyList(),
    val outboxCursor: String = "",
    val outboxBadgeCount: Int = 0,
    val notificationPreference: NotificationPreference? = null,
    val reminders: List<Reminder> = emptyList(),
    val activeLearningPrompt: BridgeOutboxEvent? = null,
    val discoveredBridges: List<DiscoveredBridge> = emptyList(),
    val isDiscovering: Boolean = false,
    val quickStartEnabled: Boolean = false,
    val nudgePolicy: NudgePolicy? = null,
    val learnedPhrases: List<LearnedPhrase> = emptyList(),
    val benchmarkSession: SherpaBenchmarkUiState? = null,
    val currentSpeechEngineId: String? = null,
    val lastBenchmarkSample: CommandBenchmarkSample? = null,
) {
    val pendingApprovalSummary: String?
        get() = pendingApprovalRequest?.summary

    val speakNowReadiness: com.openclaw.relay.signal.SpeakNowReadinessDetail
        get() = com.openclaw.relay.signal.computeSpeakNowReadiness(
            isServiceRunning = isServiceRunning,
            setupPhase = setupPhase,
            listenReadiness = listenReadiness,
            listenReadinessMessage = listenReadinessMessage,
            speechRecognitionAvailable = speechRecognitionAvailable,
            ttsReady = ttsReady,
            bridgeStatus = bridgeStatus,
            lastBridgeHealth = lastBridgeHealth,
            currentDeviceState = currentDeviceState,
            audioRoute = audioRoute,
            voiceProofRun = voiceDiagnostics.voiceProofRun,
            phoneMicFallback = phoneMicFallback,
            calibrationRequired = calibrationRequired,
            calibrationProfile = calibrationProfile,
        )
}

@Serializable
data class UnmatchedSignalRecord(
    val providerId: String,
    val gestureType: String,
    val keyCode: String? = null,
    val timestampMs: Long = System.currentTimeMillis(),
    val reason: String,
)

data class MatchedSignalRecord(
    val providerId: String,
    val gestureType: String,
    val action: String,
    val timestampMs: Long = System.currentTimeMillis(),
)

data class DiscoveredBridge(
    val name: String,
    val host: String,
    val port: Int,
    val pairingBaseUrl: String? = null,
    val version: String? = null,
)

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
    val features: List<String> = emptyList(),
)

@Serializable
const val RELAY_PROTOCOL_VERSION: String = "1.0"

const val EXPECTED_PROTOCOL_VERSION: Int = 1

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
    val protocolVersion: String = RELAY_PROTOCOL_VERSION,
    val idempotencyKey: String? = null,
)

data class TimedBridgeResult<T>(
    val value: T,
    val durationMs: Long,
)

@Serializable
data class BridgeOutboxEvent(
    val id: String,
    val sessionId: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val priority: String = "normal",
    val kind: String,
    val summary: String,
    val detail: String? = null,
    val actionId: String? = null,
)

@Serializable
data class BridgeOutboxPollResponse(
    val events: List<BridgeOutboxEvent>,
    val cursor: String? = null,
)

@Serializable
data class NotificationPreference(
    val sessionId: String,
    val style: String = "soft",
    val badgeEnabled: Boolean = true,
    val mutedKinds: List<String> = emptyList(),
    val softPingTtsEnabled: Boolean = true,
    val nudgeTtsEnabled: Boolean = true,
    val reminderTtsEnabled: Boolean = true,
    val showSensitiveInNotifications: Boolean = false,
    val wearApprovalEnabled: Boolean = true,
    val updatedAtMs: Long = System.currentTimeMillis(),
)

@Serializable
data class Reminder(
    val id: String,
    val sessionId: String,
    val summary: String,
    val createdAtMs: Long,
    val dueAtMs: Long,
    val ackedAtMs: Long? = null,
    val recurring: String = "none",
)

@Serializable
data class ReminderListResponse(
    val reminders: List<Reminder> = emptyList(),
)

@Serializable
data class LearnedPhrase(
    val phrase: String,
    val intent: String,
    val confirmationCount: Int = 0,
    val createdAtMs: Long = System.currentTimeMillis(),
    val lastConfirmedAtMs: Long? = null,
)

@Serializable
data class VoiceHabitSnapshot(
    val phrases: List<LearnedPhrase> = emptyList(),
)

@Serializable
data class NudgeThreshold(
    val type: String,
    val changedFilesMin: Int = 1,
    val staleBranchHours: Int = 24,
    val consecutiveTestFailures: Int = 2,
    val ciRedHours: Int = 1,
)

@Serializable
data class NudgePolicy(
    val sessionId: String,
    val enabled: Boolean = true,
    val mutedTypes: List<String> = emptyList(),
    val thresholds: List<NudgeThreshold> = emptyList(),
    val updatedAtMs: Long = System.currentTimeMillis(),
)

enum class SherpaPromotionState {
    HIDDEN,
    DEVELOPER_DIAGNOSTIC,
    EXPERIMENTAL,
    PRODUCTION,
}

enum class SetupPhase {
    NOT_STARTED,
    PAIRING,
    QUICK_START,
    DEVICE_PROBE,
    CALIBRATION,
    GESTURE_MAPPING,
    GESTURE_TEST,
    STT_TEST,
    COMPLETE_PROVEN,
    COMPLETE_DEGRADED,
}

data class SetupTestState(
    val isRunning: Boolean = false,
    val secondsRemaining: Int = 0,
    val statusLabel: String = "",
    val providerName: String = "",
    val confidence: String = "",
    val mappedEvent: String = "",
)

data class SherpaBenchmarkUiState(
    val isRunning: Boolean = false,
    val currentCommandIndex: Int = 0,
    val totalCommands: Int = 0,
    val currentCommand: String = "",
    val currentEngine: CommandBenchmarkEngine = CommandBenchmarkEngine.SHERPA_STT,
    val samplesCollected: Int = 0,
    val statusLabel: String = "",
    val lastTranscript: String = "",
    val lastSampleLatencyMs: Long? = null,
)
