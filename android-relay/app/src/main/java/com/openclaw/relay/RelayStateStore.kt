package com.openclaw.relay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object RelayStateStore {
    private val mutableState = MutableStateFlow(RelayUiState())

    val state: StateFlow<RelayUiState> = mutableState.asStateFlow()

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

    fun setTtsReady(isReady: Boolean) {
        mutableState.update { it.copy(ttsReady = isReady) }
    }

    fun setAudioRoute(snapshot: RelayAudioRouteSnapshot) {
        mutableState.update { it.copy(audioRoute = snapshot) }
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
                showSetupWizard = phase != SetupPhase.NOT_STARTED && phase != SetupPhase.COMPLETE,
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

        lower.contains("no earbud") || (lower.contains("headset") && lower.contains("disconnect")) ||
            lower.contains("bluetooth routing failed") || lower.contains("no communication headset") ||
            lower.contains("communication microphone route") ->
            "No headset is connected. Pair your earbuds via Bluetooth, place them in your ears, and try again."

        else -> message
    }
}
