package com.openclaw.relay.diagnostic

import android.content.Context
import android.content.Intent
import com.openclaw.relay.AudioRouteProofState
import com.openclaw.relay.RelayUiState
import com.openclaw.relay.SpeechEndpointReason
import com.openclaw.relay.TtsPlaybackEvent
import com.openclaw.relay.VoiceProofRunStatus
import com.openclaw.relay.device.DeviceCapabilityMatrix
import com.openclaw.relay.device.DeviceProfileStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Redacted diagnostic export for troubleshooting and compatibility reporting.
 *
 * This deliberately strips:
 * - MAC addresses and unique device identifiers
 * - Relay tokens and bridge URLs
 * - Workspace names
 * - Raw packet payloads
 *
 * It preserves:
 * - Device model family (e.g., "AirPods Pro 2")
 * - Capability status (proven/observed/unsupported)
 * - Phone model and Android version
 * - Provider states and readiness reasons
 * - Recent error types (not full messages)
 */
object DiagnosticExport {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    @Serializable
    data class RedactedDiagnostic(
        val exportVersion: Int = 1,
        val phoneModel: String,
        val androidVersion: String,
        val appVersion: String?,
        val device: RedactedDeviceInfo?,
        val readiness: RedactedReadiness,
        val providers: List<RedactedProviderInfo>,
        val capabilityMatrix: DeviceCapabilityMatrix,
        val recentErrors: List<String>,
        val setupPhase: String,
        val voice: RedactedVoiceDiagnostics,
    )

    @Serializable
    data class RedactedDeviceInfo(
        val modelFamily: String,
        val connectionState: String,
        val earState: String,
        val batterySummary: String,
    )

    @Serializable
    data class RedactedReadiness(
        val status: String,
        val reason: String,
        val speechRecognitionAvailable: Boolean,
        val ttsReady: Boolean,
    )

    @Serializable
    data class RedactedProviderInfo(
        val providerId: String,
        val providerLabel: String,
        val isPhysicalInput: Boolean,
        val confidence: String,
        val deviceModel: String?,
        val capabilities: List<String>,
    )

    @Serializable
    data class RedactedVoiceDiagnostics(
        val speechSessionId: String? = null,
        val speechEngine: String? = null,
        val endpointReason: String = SpeechEndpointReason.LISTENING.name.lowercase(),
        val routeState: String = AudioRouteProofState.ROUTE_UNKNOWN.name.lowercase(),
        val routeSettleMs: Long? = null,
        val phoneMicFallbackActive: Boolean = false,
        val rmsFrameCount: Int = 0,
        val rmsPeakDb: Float? = null,
        val speechDetected: Boolean = false,
        val wrongMicSuspected: Boolean = false,
        val finalTranscriptLength: Int = 0,
        val ttsEvent: String? = null,
        val ttsStartDelayMs: Long? = null,
        val ttsStopLatencyMs: Long? = null,
        val ttsBargeInLatencyMs: Long? = null,
        val ttsInterruptionTargetMet: Boolean? = null,
        val selectedDeviceType: String? = null,
        val audioProbeStatus: String? = null,
        val audioProbeSource: String? = null,
        val audioProbeDurationMs: Long? = null,
        val audioProbeNonZeroFrameRatio: Float? = null,
        val audioProbePeakAmplitude: Float? = null,
        val audioProbeReadErrorCount: Int? = null,
        val offlineRequestedMode: String,
        val offlineActiveEngine: String,
        val offlineCanRun: Boolean,
        val offlineModelVersion: String? = null,
        val offlineModelFootprintBytes: Long = 0L,
        val offlineFailureReasons: List<String> = emptyList(),
        val lastMediaButton: RedactedMediaButtonEvent? = null,
        val foregroundControls: RedactedForegroundControls = RedactedForegroundControls(),
        val foregroundService: RedactedForegroundService = RedactedForegroundService(),
        val proofRun: RedactedVoiceProofRun? = null,
    )

    @Serializable
    data class RedactedMediaButtonEvent(
        val keyLabel: String,
        val action: String,
        val mapping: String,
        val accepted: Boolean,
        val debounced: Boolean,
        val routeState: String,
        val serviceRunning: Boolean,
    )

    @Serializable
    data class RedactedForegroundControls(
        val pushToTalk: Boolean = false,
        val retryQueue: Boolean = false,
        val cancelCurrentAction: Boolean = false,
        val stopRelay: Boolean = false,
        val actionCount: Int = 0,
        val missingControls: List<String> = emptyList(),
    )

    @Serializable
    data class RedactedForegroundService(
        val active: Boolean = false,
        val mediaSessionReady: Boolean = false,
        val foregroundServiceTypeMask: Int = 0,
        val complete: Boolean = false,
        val restoredAfterRestart: Boolean = false,
        val recoveryReason: String? = null,
        val lastStartAction: String? = null,
    )

    @Serializable
    data class RedactedVoiceProofRun(
        val status: String,
        val targetSessionCount: Int,
        val completedSessionCount: Int,
        val successfulSessionCount: Int,
        val routeSuccessCount: Int,
        val routeFailureCount: Int,
        val sttSuccessCount: Int,
        val wrongMicSuspectedCount: Int,
        val reliabilityPercent: Int,
        val audioProbeSuccessCount: Int,
        val failureReasons: List<String>,
    )

    fun build(
        context: Context,
        state: RelayUiState,
        options: DiagnosticExportOptions = DiagnosticExportOptions(),
    ): RedactedDiagnostic {
        val deviceState = state.currentDeviceState
        val deviceInfo = deviceState?.let {
            RedactedDeviceInfo(
                modelFamily = redactModelName(it.displayName) ?: "Unknown",
                connectionState = it.connectionState.name.lowercase(),
                earState = describeEarState(it.earState),
                batterySummary = describeBattery(it.battery),
            )
        }

        val providers = listOfNotNull(
            state.currentDeviceState?.capabilityProfile,
        ).map {
            RedactedProviderInfo(
                providerId = it.providerId,
                providerLabel = redactProviderLabel(it.providerId),
                isPhysicalInput = it.providerId != "assistant_entry",
                confidence = state.currentDeviceState?.confidence?.name?.lowercase() ?: "unknown",
                deviceModel = redactModelName(it.deviceModel),
                capabilities = it.capabilities.map { c -> c.name },
            )
        }

        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) {
            null
        }

        return RedactedDiagnostic(
            phoneModel = if (options.includePhoneModel) android.os.Build.MODEL ?: "Unknown" else "[redacted]",
            androidVersion = if (options.includePhoneModel) android.os.Build.VERSION.RELEASE ?: "Unknown" else "[redacted]",
            appVersion = appVersion,
            device = deviceInfo,
            readiness = RedactedReadiness(
                status = state.listenReadiness.name.lowercase(),
                reason = state.listenReadinessMessage,
                speechRecognitionAvailable = state.speechRecognitionAvailable,
                ttsReady = state.ttsReady,
            ),
            providers = providers,
            capabilityMatrix = if (options.includeCapabilityMatrix) state.capabilityMatrix else DeviceCapabilityMatrix(),
            recentErrors = if (options.includeErrorCategories) listOfNotNull(
                state.lastSpeechError?.let { "speech: ${redactErrorMessage(it)}" },
                state.lastTtsError?.let { "tts: ${redactErrorMessage(it)}" },
                state.errorMessage?.let { "general: ${redactErrorMessage(it)}" },
            ) else emptyList(),
            setupPhase = state.setupPhase.name.lowercase(),
            voice = buildVoiceDiagnostics(state, options),
        )
    }

    fun toJson(
        context: Context,
        state: RelayUiState,
        options: DiagnosticExportOptions = DiagnosticExportOptions(),
    ): String {
        return json.encodeToString(build(context, state, options))
    }

    fun share(
        context: Context,
        state: RelayUiState,
        options: DiagnosticExportOptions = DiagnosticExportOptions(),
    ) {
        val payload = toJson(context, state, options)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "DevPods Relay Diagnostic Export")
            putExtra(Intent.EXTRA_TEXT, payload)
        }
        val chooser = Intent.createChooser(intent, "Share Diagnostic Export")
        context.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun redactModelName(name: String?): String? {
        if (name == null) return null
        return name
            .replace(Regex("[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}"), "**:**:**:**:**:**")
            .replace(Regex("[0-9A-Fa-f]{12}"), "************")
            .take(80)
    }

    private fun redactErrorMessage(message: String?): String? {
        if (message == null) return null
        var redacted = message
            // Redact http/https URLs including IPs and hostnames
            .replace(Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE), "[REDACTED_URL]")
            // Redact IP addresses
            .replace(Regex("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b"), "[REDACTED_IP]")
            // Redact bearer tokens and similar long alphanumeric secrets
            .replace(Regex("\\b[a-zA-Z0-9_-]{20,}\\b"), "[REDACTED_TOKEN]")
            // Redact workspace names that look like paths or identifiers
            .replace(Regex("workspace[\\s=:]+[^\\s,;]+", RegexOption.IGNORE_CASE), "workspace=[REDACTED]")
            .take(120)
        return redacted
    }

    private fun redactProviderLabel(providerId: String): String {
        return when (providerId) {
            "librepods_airpods" -> "LibrePods native"
            "android_media_session" -> "Android media controls"
            "assistant_entry" -> "Device assistant"
            else -> "Unknown provider"
        }
    }

    private fun buildVoiceDiagnostics(
        state: RelayUiState,
        options: DiagnosticExportOptions,
    ): RedactedVoiceDiagnostics {
        val speech = state.voiceDiagnostics.lastSpeechSession
        val vad = state.voiceDiagnostics.lastVadObservation
        val tts = state.voiceDiagnostics.lastTtsPlayback
        val interruption = state.voiceDiagnostics.lastTtsInterruption
        val probe = state.voiceDiagnostics.lastAudioProbe
        val proofRun = state.voiceDiagnostics.voiceProofRun
        val proofSummary = proofRun.summary
        val offline = state.voiceDiagnostics.offlineSpeechReadiness
        val mediaButton = state.voiceDiagnostics.lastMediaButtonEvent
        val foregroundControls = state.voiceDiagnostics.foregroundControls
        val foregroundService = state.voiceDiagnostics.foregroundService
        return RedactedVoiceDiagnostics(
            speechSessionId = speech?.sessionId,
            speechEngine = speech?.engineId,
            endpointReason = speech?.endpointReason?.name?.lowercase()
                ?: SpeechEndpointReason.LISTENING.name.lowercase(),
            routeState = speech?.routeProof?.routeState?.name?.lowercase()
                ?: state.audioRoute.proof.routeState.name.lowercase(),
            routeSettleMs = speech?.routeSettleMs ?: state.audioRoute.proof.routeSettleMs,
            phoneMicFallbackActive = state.audioRoute.isPhoneMicFallback,
            rmsFrameCount = speech?.rmsFrameCount ?: 0,
            rmsPeakDb = speech?.rmsPeakDb,
            speechDetected = vad?.speechDetected ?: false,
            wrongMicSuspected = vad?.wrongMicSuspected ?: false,
            finalTranscriptLength = speech?.finalTranscriptLength ?: 0,
            ttsEvent = tts?.event?.name?.lowercase() ?: TtsPlaybackEvent.REQUESTED.name.lowercase(),
            ttsStartDelayMs = tts?.startDelayMs,
            ttsStopLatencyMs = tts?.stopLatencyMs,
            ttsBargeInLatencyMs = interruption?.bargeInLatencyMs,
            ttsInterruptionTargetMet = interruption?.targetMet,
            selectedDeviceType = if (options.includeRawRoute) {
                speech?.routeProof?.selectedDeviceType ?: state.audioRoute.proof.selectedDeviceType
            } else {
                null
            },
            audioProbeStatus = probe?.initStatus?.name?.lowercase(),
            audioProbeSource = probe?.audioSource,
            audioProbeDurationMs = probe?.durationMs,
            audioProbeNonZeroFrameRatio = probe?.nonZeroFrameRatio,
            audioProbePeakAmplitude = probe?.peakAmplitude,
            audioProbeReadErrorCount = probe?.readErrorCount,
            offlineRequestedMode = offline.requestedMode.name.lowercase(),
            offlineActiveEngine = offline.activeEngineId,
            offlineCanRun = offline.canRunOffline,
            offlineModelVersion = offline.modelVersion,
            offlineModelFootprintBytes = offline.modelFootprintBytes,
            offlineFailureReasons = offline.failureReasons,
            lastMediaButton = mediaButton?.let {
                RedactedMediaButtonEvent(
                    keyLabel = it.keyLabel,
                    action = it.action.name.lowercase(),
                    mapping = it.mapping,
                    accepted = it.accepted,
                    debounced = it.debounced,
                    routeState = it.routeState.name.lowercase(),
                    serviceRunning = it.serviceRunning,
                )
            },
            foregroundControls = RedactedForegroundControls(
                pushToTalk = foregroundControls.hasPushToTalk,
                retryQueue = foregroundControls.hasRetryQueue,
                cancelCurrentAction = foregroundControls.hasCancelCurrentAction,
                stopRelay = foregroundControls.hasStopRelay,
                actionCount = foregroundControls.actionCount,
                missingControls = foregroundControls.missingRequiredActions,
            ),
            foregroundService = RedactedForegroundService(
                active = foregroundService.isForegroundActive,
                mediaSessionReady = foregroundService.mediaSessionReady,
                foregroundServiceTypeMask = foregroundService.foregroundServiceTypeMask,
                complete = foregroundService.isComplete,
                restoredAfterRestart = foregroundService.restoredAfterRestart,
                recoveryReason = foregroundService.recoveryReason,
                lastStartAction = foregroundService.lastStartAction,
            ),
            proofRun = if (proofRun.status == VoiceProofRunStatus.NOT_STARTED) {
                null
            } else {
                RedactedVoiceProofRun(
                    status = proofRun.status.name.lowercase(),
                    targetSessionCount = proofSummary.targetSessionCount,
                    completedSessionCount = proofSummary.completedSessionCount,
                    successfulSessionCount = proofSummary.successfulSessionCount,
                    routeSuccessCount = proofSummary.routeSuccessCount,
                    routeFailureCount = proofSummary.routeFailureCount,
                    sttSuccessCount = proofSummary.sttSuccessCount,
                    wrongMicSuspectedCount = proofSummary.wrongMicSuspectedCount,
                    reliabilityPercent = proofSummary.reliabilityPercent,
                    audioProbeSuccessCount = proofSummary.audioProbeSuccessCount,
                    failureReasons = proofSummary.failureReasons,
                )
            },
        )
    }

    private fun describeEarState(earState: com.openclaw.relay.signal.EarState?): String {
        if (earState == null) return "unknown"
        return when {
            earState.leftInEar == true && earState.rightInEar == true -> "both_in_ear"
            earState.leftInEar == true -> "left_in_ear"
            earState.rightInEar == true -> "right_in_ear"
            else -> "out_of_ear"
        }
    }

    private fun describeBattery(battery: com.openclaw.relay.signal.EarbudBatteryState?): String {
        if (battery == null) return "unknown"
        val left = battery.leftPercent?.toString() ?: "?"
        val right = battery.rightPercent?.toString() ?: "?"
        val case = battery.casePercent?.toString() ?: "?"
        return "L:$left R:$right C:$case${if (battery.isLow) " LOW" else ""}"
    }
}
