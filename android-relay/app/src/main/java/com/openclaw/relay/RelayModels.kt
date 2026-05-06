package com.openclaw.relay

import kotlinx.serialization.Serializable

data class RelayConfig(
    val bridgeBaseUrl: String = "http://192.168.1.10:4545",
    val relayToken: String = "",
    val workspace: String = "current_repo",
    val sessionId: String = "android-relay",
    val useBluetoothRouting: Boolean = true,
)

data class RelayLatencySnapshot(
    val lastHealthMs: Long? = null,
    val lastBridgeCommandMs: Long? = null,
    val lastSpeechStartedAtMs: Long? = null,
)

data class RelayUiState(
    val config: RelayConfig = RelayConfig(),
    val isServiceRunning: Boolean = false,
    val isListening: Boolean = false,
    val lastHeadsetEvent: String? = null,
    val bridgeStatus: String = "Unknown",
    val lastTranscript: String = "",
    val partialTranscript: String = "",
    val lastResponseSpeak: String = "",
    val lastResponseDisplay: String = "",
    val lastResponseStatus: String? = null,
    val pendingActionId: String? = null,
    val pendingApprovalSummary: String? = null,
    val latency: RelayLatencySnapshot = RelayLatencySnapshot(),
    val errorMessage: String? = null,
)

@Serializable
data class BridgeApprovalRequest(
    val actionType: String,
    val summary: String,
    val riskClass: String,
    val expiresInMs: Int,
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
)

@Serializable
data class BridgeHealthResponse(
    val ok: Boolean,
    val brainMode: String,
    val openclawTransport: String? = null,
    val openclawRewritePolicy: String? = null,
    val openclawReady: Boolean,
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
)

data class TimedBridgeResult<T>(
    val value: T,
    val durationMs: Long,
)