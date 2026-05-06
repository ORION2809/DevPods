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

    fun markServiceRunning(isRunning: Boolean) {
        mutableState.update {
            if (isRunning) {
                it.copy(
                    isServiceRunning = true,
                    errorMessage = null,
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
                    pendingApprovalSummary = null,
                    errorMessage = null,
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
        mutableState.update { it.copy(lastHeadsetEvent = value, errorMessage = null) }
    }

    fun setWakeSignal(value: RelayWakeSignal) {
        mutableState.update {
            it.copy(
                lastHeadsetEvent = value.trigger,
                lastWakeSignal = value,
                errorMessage = null,
            )
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
        mutableState.update { it.copy(partialTranscript = value, errorMessage = null) }
    }

    fun setTranscript(value: String) {
        mutableState.update {
            it.copy(lastTranscript = value, partialTranscript = value, errorMessage = null)
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
                errorMessage = null,
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
                pendingApprovalSummary = response.approvalRequest?.summary?.takeIf {
                    response.requiresApproval || response.nextState == "approval_pending"
                },
                latency = it.latency.copy(lastBridgeCommandMs = durationMs),
                errorMessage = null,
            )
        }
    }

    fun markSpeechStarted(nowMs: Long) {
        mutableState.update { it.copy(latency = it.latency.copy(lastSpeechStartedAtMs = nowMs)) }
    }

    fun clearPendingAction() {
        mutableState.update { it.copy(pendingActionId = null, pendingApprovalSummary = null) }
    }

    fun recordSpeechError(message: String) {
        mutableState.update {
            it.copy(
                lastSpeechError = message,
                isListening = false,
                isAwaitingBridgeResponse = false,
                errorMessage = message,
            )
        }
    }

    fun recordTtsError(message: String) {
        mutableState.update {
            it.copy(
                lastTtsError = message,
                isSpeaking = false,
                errorMessage = message,
            )
        }
    }

    fun setError(message: String) {
        mutableState.update {
            it.copy(
                isAwaitingBridgeResponse = false,
                errorMessage = message,
            )
        }
    }
}