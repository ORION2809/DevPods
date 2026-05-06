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
        mutableState.update { it.copy(isServiceRunning = isRunning) }
    }

    fun markListening(isListening: Boolean) {
        mutableState.update { it.copy(isListening = isListening) }
    }

    fun setLastHeadsetEvent(value: String) {
        mutableState.update { it.copy(lastHeadsetEvent = value, errorMessage = null) }
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

    fun setError(message: String) {
        mutableState.update { it.copy(errorMessage = message) }
    }
}