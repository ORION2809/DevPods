package com.openclaw.relay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object RelayStateStore {
    const val RECALIBRATION_MISS_THRESHOLD = 5

    private val mutableState = MutableStateFlow(RelayUiState())

    val state: StateFlow<RelayUiState> = mutableState.asStateFlow()

    private val voiceDiagnosticsStore = VoiceDiagnosticsStore()
    private val vadTelemetry = VadTelemetry()

    fun voiceDiagnosticsStore(): VoiceDiagnosticsStore = voiceDiagnosticsStore
    fun vadTelemetry(): VadTelemetry = vadTelemetry

    fun updateConfig(transform: (RelayConfig) -> RelayConfig) {
        mutableState.update { current ->
            current.copy(config = transform(current.config))
        }
    }

    fun resetPairing() {
        mutableState.update { current ->
            val preservedConfig = RelayConfig(
                useBluetoothRouting = current.config.useBluetoothRouting,
                phoneMicFallback = current.config.phoneMicFallback,
                assistantFallback = current.config.assistantFallback,
                speechInputMode = current.config.speechInputMode,
                offlineSpeechModelPath = current.config.offlineSpeechModelPath,
                offlineSpeechModelVersion = current.config.offlineSpeechModelVersion,
                offlineSpeechModelSha256 = current.config.offlineSpeechModelSha256,
            )
            current.copy(
                config = preservedConfig,
                pendingPairingUri = "",
                isImportingPairing = false,
                bridgeStatus = "Not paired",
                lastBridgeHealth = null,
                errorMessage = null,
                userFacingErrorMessage = null,
            )
        }
    }

    fun setPendingPairingUri(value: String) {
        mutableState.update {
            it.copy(pendingPairingUri = value)
        }
    }

    fun markPairingImportStarted() {
        mutableState.update {
            it.copy(
                isImportingPairing = true,
                errorMessage = null,
                userFacingErrorMessage = null,
            )
        }
    }

    fun markPairingImportFinished() {
        mutableState.update {
            it.copy(isImportingPairing = false)
        }
    }

    fun clearPendingPairingUri() {
        mutableState.update {
            it.copy(pendingPairingUri = "")
        }
    }

    fun recordImportedPairing() {
        mutableState.update {
            it.copy(
                isImportingPairing = false,
                bridgeStatus = "Pairing saved · checking bridge reachability",
                errorMessage = null,
                userFacingErrorMessage = null,
            )
        }
    }

    fun recordImportedPairingVerificationFailure(bridgeBaseUrl: String) {
        val message = "Pairing was imported, but the phone could not reach $bridgeBaseUrl. Check USB reverse or LAN access, then tap Health."
        mutableState.update {
            it.copy(
                isImportingPairing = false,
                bridgeStatus = "Pairing saved · bridge unreachable",
                errorMessage = message,
                userFacingErrorMessage = resolveUserFacingError(message),
            )
        }
    }

    fun markServiceRunning(isRunning: Boolean) {
        mutableState.update {
            if (isRunning) {
                it.copy(
                    isServiceRunning = true,
                    pendingPairingUri = it.pendingPairingUri,
                    errorMessage = null,
                    userFacingErrorMessage = null,
                    lastSpeechError = null,
                    lastTtsError = null,
                )
            } else {
                voiceDiagnosticsStore.clear()
                vadTelemetry.clear()
                it.copy(
                    isServiceRunning = false,
                    isListening = false,
                    isAwaitingBridgeResponse = false,
                    isSpeaking = false,
                    pendingActionId = null,
                    pendingApprovalRequest = null,
                    pendingApprovalReceivedAtMs = null,
                    activeAutonomy = null,
                    autonomyUiState = AutonomyUiState(),
                    pendingPairingUri = it.pendingPairingUri,
                    errorMessage = null,
                    userFacingErrorMessage = null,
                    lastSpeechError = null,
                    lastTtsError = null,
                )
            }
        }
    }

    fun markListening(isListening: Boolean) {
        mutableState.update { it.copy(isListening = isListening) }
    }

    fun setSpeechSessionState(state: SpeechSessionState) {
        mutableState.update { it.copy(speechSessionState = state) }
    }

    fun markAwaitingBridgeResponse(isAwaiting: Boolean) {
        mutableState.update { it.copy(isAwaitingBridgeResponse = isAwaiting) }
    }

    fun markSpeaking(isSpeaking: Boolean) {
        mutableState.update { it.copy(isSpeaking = isSpeaking) }
    }

    fun setLastHeadsetEvent(value: String) {
        mutableState.update { it.copy(lastHeadsetEvent = value, errorMessage = null, userFacingErrorMessage = null) }
    }

    fun setWakeSignal(value: RelayWakeSignal) {
        mutableState.update {
            it.copy(
                lastHeadsetEvent = value.trigger,
                lastWakeSignal = value,
                signalProviderSummary = recordObservedProvider(it.signalProviderSummary, value.provider),
                errorMessage = null,
                userFacingErrorMessage = null,
            )
        }
    }

    fun recordActivityHistory(entries: List<com.openclaw.relay.history.ActivityHistoryEntry>) {
        mutableState.update {
            it.copy(activityHistory = entries)
        }
    }

    fun setSpeechRecognitionAvailable(isAvailable: Boolean) {
        mutableState.update { it.copy(speechRecognitionAvailable = isAvailable) }
    }

    fun setCurrentSpeechEngineId(engineId: String?) {
        mutableState.update { it.copy(currentSpeechEngineId = engineId) }
    }

    fun setTtsReady(isReady: Boolean) {
        mutableState.update { it.copy(ttsReady = isReady) }
    }

    fun setAudioRoute(snapshot: RelayAudioRouteSnapshot) {
        mutableState.update { it.copy(audioRoute = snapshot) }
    }

    fun recordSpeechSessionMetrics(metrics: SpeechSessionMetrics) {
        mutableState.update {
            val diagnostics = it.voiceDiagnostics
            it.copy(
                voiceDiagnostics = diagnostics.copy(
                    lastSpeechSession = metrics,
                    lastVadObservation = metrics.toPlatformVadObservation(),
                    voiceProofRun = diagnostics.voiceProofRun.recordSpeechSession(metrics),
                ),
            )
        }
        voiceDiagnosticsStore.recordSpeechSession(metrics)
        vadTelemetry.record(metrics.toPlatformVadObservation())
    }

    fun recordTtsPlaybackMetrics(metrics: TtsPlaybackMetrics) {
        mutableState.update {
            it.copy(
                voiceDiagnostics = it.voiceDiagnostics.copy(
                    lastTtsPlayback = metrics,
                ),
            )
        }
        voiceDiagnosticsStore.recordTtsPlayback(metrics)
    }

    fun recordTtsInterruptionMetrics(metrics: TtsInterruptionMetrics) {
        mutableState.update {
            val diagnostics = it.voiceDiagnostics
            it.copy(
                voiceDiagnostics = diagnostics.copy(
                    lastTtsInterruption = metrics,
                    voiceProofRun = diagnostics.voiceProofRun.recordTtsInterruption(metrics),
                ),
            )
        }
        voiceDiagnosticsStore.recordTtsInterruption(metrics)
    }

    fun recordAudioProbeMetrics(metrics: AudioProbeMetrics) {
        mutableState.update {
            val diagnostics = it.voiceDiagnostics
            it.copy(
                voiceDiagnostics = diagnostics.copy(
                    lastAudioProbe = metrics,
                    voiceProofRun = diagnostics.voiceProofRun.recordAudioProbe(metrics),
                ),
            )
        }
        voiceDiagnosticsStore.recordAudioProbe(metrics)
    }

    fun recordOfflineSpeechReadiness(readiness: OfflineSpeechReadiness) {
        mutableState.update {
            it.copy(
                voiceDiagnostics = it.voiceDiagnostics.copy(
                    offlineSpeechReadiness = readiness,
                ),
            )
        }
    }

    fun recordMediaButtonEvent(event: MediaButtonEventTelemetry) {
        mutableState.update {
            it.copy(
                voiceDiagnostics = it.voiceDiagnostics.copy(
                    lastMediaButtonEvent = event,
                ),
            )
        }
    }

    fun recordForegroundControls(controls: ForegroundControlSnapshot) {
        mutableState.update {
            val currentForeground = it.voiceDiagnostics.foregroundService
            it.copy(
                voiceDiagnostics = it.voiceDiagnostics.copy(
                    foregroundControls = controls,
                    foregroundService = currentForeground.copy(
                        notificationControls = controls,
                        updatedAtMs = System.currentTimeMillis(),
                    ),
                ),
            )
        }
    }

    fun recordForegroundServiceSnapshot(snapshot: RelayForegroundServiceSnapshot) {
        mutableState.update {
            it.copy(
                voiceDiagnostics = it.voiceDiagnostics.copy(
                    foregroundService = snapshot,
                    foregroundControls = snapshot.notificationControls,
                ),
            )
        }
    }

    fun applyServiceRecoveryPlan(plan: RelayServiceRecoveryPlan) {
        mutableState.update { current ->
            current.copy(
                isListening = if (plan.restoreListening) current.isListening else false,
                isSpeaking = if (plan.restoreSpeaking) current.isSpeaking else false,
                isAwaitingBridgeResponse = if (plan.restoreAwaitingBridge) current.isAwaitingBridgeResponse else false,
                pendingActionId = if (plan.clearPendingTransientAction) null else current.pendingActionId,
                activeAutonomy = if (plan.clearTransientAutonomy) null else current.activeAutonomy,
                autonomyUiState = if (plan.clearTransientAutonomy) AutonomyUiState() else current.autonomyUiState,
                voiceDiagnostics = current.voiceDiagnostics.copy(
                    foregroundService = current.voiceDiagnostics.foregroundService.copy(
                        restoredAfterRestart = plan.shouldRestartForeground,
                        recoveryReason = plan.reason,
                        updatedAtMs = System.currentTimeMillis(),
                    ),
                ),
            )
        }
    }

    fun startVoiceProofRun(targetSessionCount: Int = VoiceProofRun.DEFAULT_TARGET_SESSION_COUNT) {
        mutableState.update {
            it.copy(
                voiceDiagnostics = it.voiceDiagnostics.copy(
                    voiceProofRun = VoiceProofRun.start(
                        runId = "voice-proof-${System.currentTimeMillis()}",
                        targetSessionCount = targetSessionCount,
                        startedAtMs = System.currentTimeMillis(),
                    ),
                ),
            )
        }
    }

    fun resetVoiceProofRun() {
        mutableState.update {
            it.copy(
                voiceDiagnostics = it.voiceDiagnostics.copy(
                    voiceProofRun = VoiceProofRun(),
                ),
            )
        }
    }

    fun voiceDiagnosticsExportSummary(): VoiceDiagnosticsExportSummary {
        return voiceDiagnosticsStore.exportSummary()
    }

    fun setPartialTranscript(value: String) {
        mutableState.update { it.copy(partialTranscript = value, errorMessage = null, userFacingErrorMessage = null) }
    }

    fun setTranscript(value: String) {
        mutableState.update {
            it.copy(lastTranscript = value, partialTranscript = value, errorMessage = null, userFacingErrorMessage = null)
        }
    }

    fun recordHealth(result: BridgeHealthResponse, durationMs: Long) {
        val bridgeSummary = buildString {
            append(if (result.ok) "Healthy" else "Unavailable")
            append(" · brain=")
            append(result.brainMode)
            if (result.openclawTransport != null) {
                append(" · transport=")
                append(result.openclawTransport)
            }
        }

        mutableState.update {
            it.copy(
                bridgeStatus = bridgeSummary,
                latency = it.latency.copy(lastHealthMs = durationMs),
                lastBridgeHealth = result,
                errorMessage = null,
                userFacingErrorMessage = null,
            )
        }
    }

    fun recordResponse(response: BridgeJarvisResponse, durationMs: Long) {
        mutableState.update {
            it.copy(
                lastResponseSpeak = response.speak,
                lastResponseDisplay = response.display.orEmpty(),
                lastResponseStatus = response.status,
                isAwaitingBridgeResponse = false,
                pendingActionId = response.actionId?.takeIf {
                    response.requiresApproval
                        || response.nextState == "approval_pending"
                        || response.nextState == "queued"
                        || response.nextState == "running"
                },
                pendingApprovalRequest = response.approvalRequest?.takeIf {
                    response.requiresApproval || response.nextState == "approval_pending"
                },
                pendingApprovalReceivedAtMs = if (response.approvalRequest != null &&
                    (response.requiresApproval || response.nextState == "approval_pending")) {
                    System.currentTimeMillis()
                } else null,
                activeAutonomy = response.autonomy?.takeIf {
                    it.mode == "continue_on_silence" && it.continueAfterMs != null && !it.nextIntent.isNullOrBlank()
                },
                autonomyUiState = response.autonomy?.let { autonomy ->
                    AutonomyUiState(
                        phase = autonomy.phase,
                        nextStep = autonomy.nextStep,
                        countdownMs = autonomy.continueAfterMs?.toLong(),
                        canStop = true,
                    )
                } ?: AutonomyUiState(),
                latency = it.latency.copy(lastBridgeCommandMs = durationMs),
                errorMessage = null,
                userFacingErrorMessage = null,
            )
        }
    }

    fun markSpeechStarted(nowMs: Long) {
        mutableState.update { it.copy(latency = it.latency.copy(lastSpeechStartedAtMs = nowMs)) }
    }

    fun clearPendingAction() {
        mutableState.update { it.copy(pendingActionId = null, pendingApprovalRequest = null, pendingApprovalReceivedAtMs = null) }
    }

    fun clearAutonomy() {
        mutableState.update { it.copy(activeAutonomy = null, autonomyUiState = AutonomyUiState()) }
    }

    fun isPendingApprovalExpired(): Boolean {
        val state = mutableState.value
        val request = state.pendingApprovalRequest ?: return false
        val receivedAt = state.pendingApprovalReceivedAtMs ?: return false
        return System.currentTimeMillis() - receivedAt > request.expiresInMs
    }

    fun recordSpeechError(message: String) {
        mutableState.update {
            it.copy(
                lastSpeechError = message,
                isListening = false,
                isAwaitingBridgeResponse = false,
                errorMessage = message,
                userFacingErrorMessage = resolveUserFacingError(message),
            )
        }
    }

    fun recordTtsError(message: String) {
        mutableState.update {
            it.copy(
                lastTtsError = message,
                isSpeaking = false,
                errorMessage = message,
                userFacingErrorMessage = resolveUserFacingError(message),
            )
        }
    }

    fun setError(message: String) {
        mutableState.update {
            it.copy(
                isAwaitingBridgeResponse = false,
                errorMessage = message,
                userFacingErrorMessage = resolveUserFacingError(message),
            )
        }
    }

    fun clearError() {
        mutableState.update {
            it.copy(errorMessage = null, userFacingErrorMessage = null)
        }
    }

    fun clearListeningStartupErrors() {
        mutableState.update {
            it.copy(
                lastSpeechError = null,
                errorMessage = null,
                userFacingErrorMessage = null,
            )
        }
    }

    fun setListenReadiness(readiness: com.openclaw.relay.signal.ListenReadiness, message: String) {
        mutableState.update {
            it.copy(
                listenReadiness = readiness,
                listenReadinessMessage = message,
            )
        }
    }

    fun setCurrentDeviceState(state: com.openclaw.relay.signal.EarbudDeviceState?) {
        mutableState.update {
            it.copy(currentDeviceState = state)
        }
    }

    fun setCapabilityMatrix(matrix: com.openclaw.relay.device.DeviceCapabilityMatrix) {
        mutableState.update {
            it.copy(capabilityMatrix = matrix)
        }
    }

    fun setSetupPhase(phase: SetupPhase) {
        mutableState.update {
            it.copy(
                setupPhase = phase,
                showSetupWizard = phase != SetupPhase.NOT_STARTED && phase != SetupPhase.COMPLETE_PROVEN && phase != SetupPhase.COMPLETE_DEGRADED,
                setupTestState = SetupTestState(),
            )
        }
    }

    fun setSetupTestState(testState: SetupTestState) {
        mutableState.update { it.copy(setupTestState = testState) }
    }

    fun dismissSetupWizard() {
        mutableState.update {
            it.copy(showSetupWizard = false)
        }
    }

    fun showSetupWizard() {
        mutableState.update {
            it.copy(showSetupWizard = true)
        }
    }

    fun setShowOnboarding(show: Boolean) {
        mutableState.update {
            it.copy(showOnboarding = show)
        }
    }

    fun setPhoneMicFallback(enabled: Boolean) {
        mutableState.update {
            it.copy(phoneMicFallback = enabled)
        }
    }

    fun setAssistantFallback(enabled: Boolean) {
        mutableState.update {
            it.copy(assistantFallback = enabled)
        }
    }

    fun setBridgeQueueState(state: BridgeQueueState) {
        mutableState.update {
            it.copy(
                bridgeQueueState = state.copy(
                    retryAtMs = state.nextRetryMs?.let { ms -> System.currentTimeMillis() + ms }
                ),
            )
        }
    }

    fun setProviderHealth(health: List<com.openclaw.relay.signal.ProviderHealthUi>, preferredId: String?) {
        mutableState.update {
            it.copy(providerHealth = health, preferredProviderId = preferredId)
        }
    }

    fun setAutonomyUiState(state: AutonomyUiState) {
        mutableState.update {
            it.copy(
                autonomyUiState = state.copy(
                    autonomyContinueAtMs = state.countdownMs?.let { ms -> System.currentTimeMillis() + ms }
                ),
            )
        }
    }

    // --- Calibration state ---

    fun setCalibrationProfile(profile: com.openclaw.relay.calibration.EarbudCalibrationProfile?) {
        mutableState.update {
            it.copy(
                calibrationProfile = profile,
                calibrationRequired = profile == null || !profile.isReadyForRuntime(),
            )
        }
    }

    fun setGestureActionMap(map: com.openclaw.relay.calibration.GestureActionMap) {
        mutableState.update {
            val currentProfile = it.calibrationProfile
            if (currentProfile != null) {
                val updatedProfile = currentProfile.copy(
                    gestureActionMap = map,
                )
                it.copy(
                    calibrationProfile = updatedProfile,
                )
            } else {
                it
            }
        }
    }

    fun setCalibrationSession(session: com.openclaw.relay.calibration.CalibrationSessionState?) {
        mutableState.update {
            it.copy(calibrationSession = session)
        }
    }

    fun setCalibrationRequired(required: Boolean) {
        mutableState.update {
            it.copy(calibrationRequired = required)
        }
    }

    fun clearCalibration() {
        mutableState.update {
            it.copy(
                calibrationProfile = null,
                calibrationSession = null,
                calibrationRequired = true,
                recentUnmatchedSignals = emptyList(),
                runtimeMissCount = 0,
                recentMatchedSignals = emptyList(),
            )
        }
    }

    fun setRuntimeMissCount(count: Int) {
        mutableState.update {
            it.copy(
                runtimeMissCount = count,
                calibrationRequired = if (count >= RECALIBRATION_MISS_THRESHOLD) true else it.calibrationRequired,
            )
        }
    }

    fun recordUnmatchedSignal(
        providerId: String,
        gestureType: String,
        keyCode: String? = null,
        reason: String,
    ) {
        mutableState.update {
            val newRecord = com.openclaw.relay.UnmatchedSignalRecord(
                providerId = providerId,
                gestureType = gestureType,
                keyCode = keyCode,
                reason = reason,
            )
            val updatedSignals = (it.recentUnmatchedSignals + newRecord)
                .takeLast(20)
            val newMissCount = it.runtimeMissCount + 1
            it.copy(
                recentUnmatchedSignals = updatedSignals,
                runtimeMissCount = newMissCount,
                calibrationRequired = if (newMissCount >= RECALIBRATION_MISS_THRESHOLD) true else it.calibrationRequired,
            )
        }
    }

    fun recordMatchedSignal(
        providerId: String,
        gestureType: String,
        action: String,
    ) {
        mutableState.update {
            val newMissCount = maxOf(0, it.runtimeMissCount - 1)
            val newRecord = com.openclaw.relay.MatchedSignalRecord(
                providerId = providerId,
                gestureType = gestureType,
                action = action,
            )
            val updatedSignals = (it.recentMatchedSignals + newRecord)
                .takeLast(20)
            it.copy(
                runtimeMissCount = newMissCount,
                recentMatchedSignals = updatedSignals,
                calibrationRequired = if (newMissCount < RECALIBRATION_MISS_THRESHOLD) false else it.calibrationRequired,
            )
        }
    }

    fun setOutboxEvents(events: List<com.openclaw.relay.BridgeOutboxEvent>, cursor: String) {
        mutableState.update {
            it.copy(
                outboxEvents = events,
                outboxCursor = cursor,
                outboxBadgeCount = events.size,
            )
        }
    }

    fun clearOutboxBadge() {
        mutableState.update {
            it.copy(outboxBadgeCount = 0)
        }
    }

    fun removeOutboxEvent(eventId: String) {
        mutableState.update {
            it.copy(
                outboxEvents = it.outboxEvents.filter { e -> e.id != eventId },
                outboxBadgeCount = maxOf(0, it.outboxBadgeCount - 1),
            )
        }
    }

    fun setNotificationPreference(preference: NotificationPreference) {
        mutableState.update {
            it.copy(notificationPreference = preference)
        }
    }

    fun getNotificationPreference(): NotificationPreference {
        return mutableState.value.notificationPreference
            ?: NotificationPreference(sessionId = mutableState.value.config.sessionId)
    }

    fun setReminders(reminders: List<Reminder>) {
        mutableState.update {
            it.copy(reminders = reminders)
        }
    }

    fun addReminder(reminder: Reminder) {
        mutableState.update {
            it.copy(reminders = it.reminders + reminder)
        }
    }

    fun removeReminder(reminderId: String) {
        mutableState.update {
            it.copy(reminders = it.reminders.filter { r -> r.id != reminderId })
        }
    }

    fun setActiveLearningPrompt(prompt: BridgeOutboxEvent?) {
        mutableState.update {
            it.copy(activeLearningPrompt = prompt)
        }
    }

    fun clearLearningPrompt() {
        mutableState.update {
            it.copy(activeLearningPrompt = null)
        }
    }

    fun setDiscoveredBridges(bridges: List<DiscoveredBridge>) {
        mutableState.update {
            it.copy(discoveredBridges = bridges)
        }
    }

    fun setIsDiscovering(discovering: Boolean) {
        mutableState.update {
            it.copy(isDiscovering = discovering)
        }
    }

    fun setQuickStartEnabled(enabled: Boolean) {
        mutableState.update {
            it.copy(quickStartEnabled = enabled)
        }
    }

    fun setNudgePolicy(policy: NudgePolicy) {
        mutableState.update {
            it.copy(nudgePolicy = policy)
        }
    }

    fun setLearnedPhrases(phrases: List<LearnedPhrase>) {
        mutableState.update {
            it.copy(learnedPhrases = phrases)
        }
    }

    fun removeLearnedPhrase(phrase: String) {
        mutableState.update {
            it.copy(learnedPhrases = it.learnedPhrases.filter { p -> p.phrase != phrase })
        }
    }

    // --- Sherpa benchmark state ---

    fun recordSherpaBenchmarkReport(report: SherpaCommandBenchmarkReport) {
        mutableState.update {
            it.copy(
                voiceDiagnostics = it.voiceDiagnostics.copy(
                    sherpaBenchmarkReport = report,
                    sherpaPromotionState = report.promotionState,
                ),
            )
        }
    }

    fun setSherpaPromotionState(state: SherpaPromotionState) {
        mutableState.update {
            it.copy(
                voiceDiagnostics = it.voiceDiagnostics.copy(
                    sherpaPromotionState = state,
                ),
            )
        }
    }

    fun startBenchmarkSession(totalCommands: Int, firstCommand: String, engine: CommandBenchmarkEngine) {
        mutableState.update {
            it.copy(
                benchmarkSession = SherpaBenchmarkUiState(
                    isRunning = true,
                    currentCommandIndex = 0,
                    totalCommands = totalCommands,
                    currentCommand = firstCommand,
                    currentEngine = engine,
                    statusLabel = "Speak the command",
                ),
            )
        }
    }

    fun advanceBenchmarkCommand(index: Int, command: String, engine: CommandBenchmarkEngine, samplesCollected: Int) {
        mutableState.update {
            it.copy(
                benchmarkSession = it.benchmarkSession?.copy(
                    currentCommandIndex = index,
                    currentCommand = command,
                    currentEngine = engine,
                    samplesCollected = samplesCollected,
                    lastTranscript = "",
                    statusLabel = "Speak the command",
                ),
            )
        }
    }

    fun recordBenchmarkTranscript(transcript: String, latencyMs: Long?) {
        mutableState.update {
            it.copy(
                benchmarkSession = it.benchmarkSession?.copy(
                    lastTranscript = transcript,
                    lastSampleLatencyMs = latencyMs,
                    statusLabel = if (transcript.isNotBlank()) "Sample recorded" else "No speech detected",
                ),
            )
        }
    }

    fun finishBenchmarkSession() {
        mutableState.update {
            it.copy(benchmarkSession = null)
        }
    }

    fun recordBenchmarkSample(sample: CommandBenchmarkSample) {
        mutableState.update {
            it.copy(lastBenchmarkSample = sample)
        }
    }

    fun clearBenchmarkSample() {
        mutableState.update {
            it.copy(lastBenchmarkSample = null)
        }
    }
}

internal fun resolveUserFacingError(message: String?): String? {
    if (message.isNullOrBlank()) return null
    val lower = message.lowercase()
    return when {
        lower.contains("unreachable") || lower.contains("could not reach") ||
            (lower.contains("bridge") && lower.contains("unavailable")) ||
            lower.contains("health check failed") ->
            "The desktop bridge is unreachable. Make sure your computer and phone are on the same network, and the bridge is running. Tap Health to retry."

        lower.contains("pairing") && (lower.contains("expired") || lower.contains("invalid") ||
            lower.contains("verify") || lower.contains("could not import")) ->
            "Pairing expired or is invalid. Re-scan the QR code from the desktop bridge, or paste the pairing page URL again."

        (lower.contains("speech recognition") && lower.contains("unavailable")) ||
            lower.contains("stt") && lower.contains("not available") ->
            "Speech-to-text is not available on this device. Install a speech recognition engine from the Play Store, or enable it in system settings."

        lower.contains("microphone permission") || lower.contains("record_audio") ||
            lower.contains("insufficient_permissions") || lower.contains("microphone error") ->
            "Microphone permission is denied. Go to Settings > Apps > DevPods Relay > Permissions, and allow Microphone."

        lower.contains("enable phone microphone fallback") ->
            "Earbud microphone routing failed. Enable Phone microphone fallback in Device settings, or reconnect your earbuds and try again."

        lower.contains("no earbud") || (lower.contains("headset") && lower.contains("disconnect")) ||
            lower.contains("bluetooth routing failed") || lower.contains("no communication headset") ||
            lower.contains("communication microphone route") ->
            "No headset is connected. Pair your earbuds via Bluetooth, place them in your ears, and try again."

        else -> message
    }
}
