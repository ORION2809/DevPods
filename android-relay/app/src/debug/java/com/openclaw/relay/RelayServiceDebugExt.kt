package com.openclaw.relay

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Debug-only extension for RelayService proof-runner automation.
 * Lives in src/debug/java so it is excluded from release builds.
 */
internal object RelayServiceDebugExt {
    private fun resolveCalibratedWakePath(): String {
        val profile = RelayStateStore.state.value.calibrationProfile
        val hasCalibratedWake = profile?.gestureActionMap?.mappings?.any { it.value == com.openclaw.relay.calibration.GestureAction.WAKE_AND_LISTEN } == true
        return if (hasCalibratedWake) {
            // Calibrated media-session gestures use the media session wake path
            "android_media_session"
        } else {
            "push_to_talk"
        }
    }

    private fun resolveCalibrationFields(proofRun: com.openclaw.relay.VoiceProofRun): Map<String, kotlin.Any?> {
        val profile = RelayStateStore.state.value.calibrationProfile
        val sessionsWithCal = proofRun.sessions.filter {
            it.calibrationProfileId != null && it.calibratedGestureUsed != null
        }

        if (sessionsWithCal.isEmpty()) {
            return mapOf(
                "calibrationProfileId" to null,
                "calibratedGestureUsed" to null,
                "matchedCalibratedAction" to null,
                "sameProfileProof" to false,
                "isModelScoped" to false,
            )
        }

        val firstSession = sessionsWithCal.first()
        val allSameProfile = sessionsWithCal.all { it.calibrationProfileId == firstSession.calibrationProfileId }
        val profileMatches = profile?.profileId == firstSession.calibrationProfileId
        val sameProfileProof = allSameProfile && profileMatches && profile?.isReadyForRuntime() == true

        return mapOf<String, kotlin.Any?>(
            "calibrationProfileId" to firstSession.calibrationProfileId,
            "calibratedGestureUsed" to firstSession.calibratedGestureUsed,
            "matchedCalibratedAction" to firstSession.matchedCalibratedAction,
            "sameProfileProof" to sameProfileProof,
            "isModelScoped" to (profile?.isModelScoped ?: false),
        )
    }
    private const val TAG = "RelayServiceDebugExt"
    private const val BARGE_IN_TRIAL_COUNT = 5
    private const val BARGE_IN_PROMPT =
        "This is a long running proof response for interruption testing. It should be stopped by the next tap before the sentence finishes."

    private val transcripts = listOf(
        "run tests" to "run",
        "what branch am I on" to "what branch",
        "open file main dot kt" to "open file",
        "check status" to "check",
        "deploy staging" to "deploy",
    )

    @JvmStatic
    fun runProof(
        scope: CoroutineScope,
        service: RelayService,
        intent: Intent?,
    ) {
        scope.launch {
            val proofTier = intent?.getStringExtra("proofTier") ?: "T1_EMULATOR_SYNTHETIC"
            val sessionCount = intent?.getIntExtra("sessionCount", 20) ?: 20
            val artifactId = intent?.getStringExtra("artifactId")
                ?: "proof-${System.currentTimeMillis()}"
            val earbudModel = intent?.getStringExtra("earbudModel")
            val providerId = intent?.getStringExtra("providerId")
            val isPcmInjectionTier = proofTier.contains("PCM", ignoreCase = true)
            val isT4Tier = proofTier.startsWith("T4")

            Log.i(TAG, "debug proof run started tier=$proofTier sessions=$sessionCount artifactId=$artifactId")

            when {
                isPcmInjectionTier -> {
                    // PCM: Inject deterministic audio frames into the speech callback contract.
                    service.speechInputEngine = PcmInjectionSpeechInputEngine()
                    service.speechOutputEngine = SyntheticSpeechOutputEngine()
                    RelayStateStore.setSpeechRecognitionAvailable(true)
                    RelayStateStore.setTtsReady(true)
                    RelayStateStore.updateConfig { it.copy(useBluetoothRouting = false) }
                    RelayStateStore.setAudioRoute(
                        RelayAudioRouteSnapshot(
                            isActive = true,
                            isReadyForSpeechCapture = true,
                            status = "Synthetic PCM route ready",
                            selectedDeviceName = "synthetic_pcm",
                            selectedDeviceType = "synthetic_pcm",
                            proof = AudioRouteProof(
                                routeState = AudioRouteProofState.ROUTE_PHONE_MIC,
                                selectedDeviceType = "synthetic_pcm",
                            ),
                        )
                    )
                }
                proofTier.startsWith("T1") -> {
                    // T1: Swap to synthetic engines — no real microphone needed
                    service.speechInputEngine = SyntheticSpeechInputEngine()
                    service.speechOutputEngine = SyntheticSpeechOutputEngine()
                    RelayStateStore.setSpeechRecognitionAvailable(true)
                    RelayStateStore.setTtsReady(true)
                    RelayStateStore.updateConfig { it.copy(useBluetoothRouting = false) }
                    RelayStateStore.setAudioRoute(
                        RelayAudioRouteSnapshot(
                            isActive = true,
                            isReadyForSpeechCapture = true,
                            status = "Synthetic route ready",
                            selectedDeviceName = "synthetic",
                            selectedDeviceType = "synthetic",
                            proof = AudioRouteProof(
                                routeState = AudioRouteProofState.ROUTE_PHONE_MIC,
                                selectedDeviceType = "synthetic",
                            ),
                        )
                    )
                }
                proofTier.startsWith("T2") -> {
                    // T2: Use real platform STT/TTS with emulator host-audio input
                    // Recreate engines via factory to ensure real platform engines (not synthetic)
                    service.speechInputEngine = SpeechInputEngineFactory.create(service, RelayStateStore.state.value.config)
                    RelayStateStore.setSpeechRecognitionAvailable(service.speechInputEngine.capabilities().isAvailable)
                    // TTS engine is recreated by speakText via AndroidTtsOutputEngine which wraps AndroidTtsSpeaker
                    RelayStateStore.updateConfig { it.copy(useBluetoothRouting = false) }
                    RelayStateStore.setAudioRoute(
                        RelayAudioRouteSnapshot(
                            isActive = true,
                            isReadyForSpeechCapture = true,
                            status = "Emulator host audio route",
                            selectedDeviceName = "emulator_host_mic",
                            selectedDeviceType = "emulator_host_mic",
                            proof = AudioRouteProof(
                                routeState = AudioRouteProofState.ROUTE_PHONE_MIC,
                                selectedDeviceType = "emulator_host_mic",
                            ),
                        )
                    )
                }
                isT4Tier -> {
                    // T4: Real physical bluetooth proof on a physical Android device with earbuds.
                    // Use real platform engines; do NOT substitute synthetic engines.
                    service.speechInputEngine = SpeechInputEngineFactory.create(service, RelayStateStore.state.value.config)
                    RelayStateStore.setSpeechRecognitionAvailable(service.speechInputEngine.capabilities().isAvailable)
                    // Enable bluetooth routing so input/output travel through the headset
                    RelayStateStore.updateConfig { it.copy(useBluetoothRouting = true) }

                    // Wait for bluetooth route to become active (max 30s)
                    val btReady = withTimeoutOrNull(30_000L) {
                        while (true) {
                            val route = RelayStateStore.state.value.audioRoute
                            if (route.proof.routeState == AudioRouteProofState.ROUTE_BLUETOOTH_ACTIVE && route.isReadyForSpeechCapture) {
                                break
                            }
                            delay(200)
                        }
                        true
                    }
                    if (btReady == true) {
                        Log.i(TAG, "T4: Bluetooth route is active and ready")
                    } else {
                        Log.w(TAG, "T4: Bluetooth route did not become active within 30s. Proof may fail.")
                    }
                }
            }

            RelayStateStore.startVoiceProofRun(targetSessionCount = sessionCount)

            if (!isT4Tier) {
                // Auto-send synthetic/proof wake signals for non-physical tiers
                repeat(sessionCount) { i ->
                    val (finalText, partialText) = transcripts[i % transcripts.size]
                    val script = SyntheticSpeechScript(
                        mode = SyntheticMode.FINAL_TRANSCRIPT,
                        finalText = finalText,
                        partialText = partialText,
                    )
                    (service.speechInputEngine as? SyntheticSpeechInputEngine)?.setScript(script)
                    (service.speechInputEngine as? PcmInjectionSpeechInputEngine)?.setScript(
                        PcmInjectionScript(
                            finalText = finalText,
                            partialText = partialText,
                            frames = PcmInjectionFixtures.spokenPhrase(),
                        )
                    )

                    val wakeSignal = RelayWakeSignal(
                        trigger = "android_push_to_talk",
                        source = if (isPcmInjectionTier) "pcm_injection_proof" else "synthetic_proof",
                        sourceLabel = if (isPcmInjectionTier) "PCM Injection Proof Runner" else "Synthetic Proof Runner",
                        provider = DebugAutomationSignalProvider.observe(),
                    )

                    val sessionsBefore = RelayStateStore.state.value.voiceDiagnostics.voiceProofRun.sessions.size
                    if (proofTier.startsWith("T2") || isPcmInjectionTier) {
                        RelayStateStore.setWakeSignal(wakeSignal)
                        service.startListeningSession(wakeSignal)
                    } else {
                        service.handleGestureSignal(wakeSignal)
                    }

                    // Wait for this session to complete (timeout 30s)
                    val completed = withTimeoutOrNull(30_000L) {
                        while (RelayStateStore.state.value.voiceDiagnostics.voiceProofRun.sessions.size <= sessionsBefore) {
                            delay(100)
                        }
                        // Wait until the latest session is completed
                        while (true) {
                            val proofRun = RelayStateStore.state.value.voiceDiagnostics.voiceProofRun
                            val latestSession = proofRun.sessions.lastOrNull()
                            if (latestSession == null || latestSession.isCompleted) break
                            delay(100)
                        }
                        true
                    }
                    if (completed != true) {
                        Log.w(TAG, "session $i did not complete within timeout")
                    } else {
                        Log.i(TAG, "session $i completed")
                    }
                }
            }

            // Barge-in trials:
            // - Skipped for synthetic tiers (T1/PCM)
            // - Auto-triggered for T2
            // - Manual for T4 (operator taps during TTS; app records via interruptImplementationAndListen)
            if (!proofTier.startsWith("T1") && !isPcmInjectionTier && !isT4Tier) {
                repeat(BARGE_IN_TRIAL_COUNT) { i ->
                    service.speechOutputEngine.speak(
                        request = TtsRequest(
                            utteranceId = "proof-barge-in-$artifactId-$i",
                            text = BARGE_IN_PROMPT,
                        ),
                        callbacks = TtsCallbacks(
                            onError = { error ->
                                Log.w(TAG, "barge-in trial $i TTS error: $error")
                            },
                        ),
                    )

                    val ttsStarted = withTimeoutOrNull(5_000L) {
                        while (!RelayStateStore.state.value.isSpeaking) {
                            delay(25)
                        }
                        true
                    }
                    if (ttsStarted != true) {
                        Log.w(TAG, "barge-in trial $i TTS did not start within timeout")
                    }

                    val wakeSignal = RelayWakeSignal(
                        trigger = "android_push_to_talk",
                        source = "barge_in_proof",
                        sourceLabel = "Barge-in Proof Runner",
                        provider = DebugAutomationSignalProvider.observe(),
                    )

                    delay(100)
                    val previousInterruptionId = RelayStateStore.state.value.voiceDiagnostics.lastTtsInterruption?.interruptionId
                    val previousSpeechSessionId = RelayStateStore.state.value.voiceDiagnostics.lastSpeechSession?.sessionId
                    service.interruptImplementationAndListen(wakeSignal)

                    val recorded = withTimeoutOrNull(15_000L) {
                        while (true) {
                            val interruption = RelayStateStore.state.value.voiceDiagnostics.lastTtsInterruption
                            if (
                                interruption != null &&
                                interruption.interruptionId != previousInterruptionId &&
                                interruption.ttsStoppedAtMs != null &&
                                interruption.listeningStartedAtMs != null
                            ) {
                                break
                            }
                            delay(50)
                        }
                        true
                    }
                    if (recorded != true) {
                        Log.w(TAG, "barge-in trial $i was not recorded within timeout")
                    } else {
                        val interruption = RelayStateStore.state.value.voiceDiagnostics.lastTtsInterruption
                        Log.i(
                            TAG,
                            "barge-in trial $i recorded targetMet=${interruption?.targetMet} stopLatencyMs=${interruption?.ttsStopLatencyMs} listenLatencyMs=${interruption?.bargeInLatencyMs}",
                        )
                    }

                    val speechCompleted = withTimeoutOrNull(30_000L) {
                        while (true) {
                            val latest = RelayStateStore.state.value.voiceDiagnostics.lastSpeechSession
                            if (
                                latest != null &&
                                latest.sessionId != previousSpeechSessionId &&
                                latest.endpointReason != SpeechEndpointReason.LISTENING
                            ) {
                                break
                            }
                            delay(100)
                        }
                        true
                    }
                    if (speechCompleted != true) {
                        Log.w(TAG, "barge-in trial $i speech session did not complete within timeout")
                    }
                }
            }

            if (isT4Tier) {
                // T4: wait for operator to complete all manual sessions and barge-in trials.
                // Poll until the proof run reaches a terminal state or timeout.
                Log.i(TAG, "T4: waiting for manual proof completion (timeout 600s)")
                val completed = withTimeoutOrNull(600_000L) {
                    while (RelayStateStore.state.value.voiceDiagnostics.voiceProofRun.status == VoiceProofRunStatus.RUNNING) {
                        delay(1000)
                    }
                    true
                }
                if (completed != true) {
                    Log.w(TAG, "T4: proof run timed out waiting for manual sessions")
                } else {
                    Log.i(TAG, "T4: manual proof run completed")
                }
            }

            val proofRun = RelayStateStore.state.value.voiceDiagnostics.voiceProofRun
            val summary = proofRun.summary
            val status = if (summary.hasBlockingFailures) "FAILED" else "PASSED"

            Log.i(TAG, "debug proof run completed artifactId=$artifactId status=$status sessions=${summary.successfulSessionCount}/${summary.targetSessionCount}")

            storeProofArtifact(
                context = service,
                artifactId = artifactId,
                proofTier = proofTier,
                proofRun = proofRun,
                earbudModel = earbudModel,
                providerId = providerId,
            )
        }
    }

    @JvmStatic
    fun exportProof(context: Context) {
        val proofDir = context.getDir("proof_artifacts", Context.MODE_PRIVATE)
        val files = proofDir.listFiles() ?: emptyArray()
        Log.i(TAG, "export proof found ${files.size} artifact files")
        files.forEach { file ->
            Log.i(TAG, "proof artifact: ${file.name} (${file.length()} bytes)")
        }
    }

    private fun storeProofArtifact(
        context: Context,
        artifactId: String,
        proofTier: String,
        proofRun: VoiceProofRun,
        earbudModel: String? = null,
        providerId: String? = null,
    ) {
        try {
            val proofDir = context.getDir("proof_artifacts", Context.MODE_PRIVATE)
            val artifactFile = java.io.File(proofDir, "$artifactId.json")
            val summary = proofRun.summary

            val isPcmTier = proofTier.contains("PCM", ignoreCase = true)
            val isT2Tier = proofTier.startsWith("T2")
            val isT1Tier = proofTier.startsWith("T1") && !isPcmTier
            val isT4Tier = proofTier.startsWith("T4")

            val routeProofSource: String
            val inputPath: String
            val outputPath: String
            val physicalBluetoothProven: Boolean
            when {
                isPcmTier -> {
                    routeProofSource = "synthetic"
                    inputPath = "synthetic_pcm"
                    outputPath = "fake_tts"
                    physicalBluetoothProven = false
                }
                isT1Tier -> {
                    routeProofSource = "synthetic"
                    inputPath = "synthetic_text"
                    outputPath = "fake_tts"
                    physicalBluetoothProven = false
                }
                isT2Tier -> {
                    routeProofSource = "host_audio"
                    inputPath = "emulator_host_mic"
                    outputPath = "host_audio"
                    physicalBluetoothProven = false
                }
                isT4Tier -> {
                    routeProofSource = "physical_bluetooth"
                    inputPath = "android_bluetooth_headset"
                    outputPath = "android_tts_bluetooth"
                    // Only claim physicalBluetoothProven if the run actually passed
                    physicalBluetoothProven = !summary.hasBlockingFailures
                }
                else -> {
                    routeProofSource = "physical_bluetooth"
                    inputPath = "android_bluetooth_headset"
                    outputPath = "android_tts_bluetooth"
                    physicalBluetoothProven = false
                }
            }

            // Determine earbud model and provider id
            val finalEarbudModel = when {
                isT4Tier && !earbudModel.isNullOrBlank() -> earbudModel
                isT2Tier -> "emulator_host_audio"
                isPcmTier -> "emulator_pcm"
                isT1Tier -> "emulator_synthetic"
                else -> "unknown"
            }
            val finalProviderId = when {
                isT4Tier && !providerId.isNullOrBlank() -> providerId
                else -> "emulator"
            }

            val artifact = kotlinx.serialization.json.buildJsonObject {
                put("proofRunId", kotlinx.serialization.json.JsonPrimitive(artifactId))
                put("startedAt", kotlinx.serialization.json.JsonPrimitive(java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())))
                put("proofTier", kotlinx.serialization.json.JsonPrimitive(proofTier))
                put("phoneModel", kotlinx.serialization.json.JsonPrimitive(android.os.Build.MODEL))
                put("androidVersion", kotlinx.serialization.json.JsonPrimitive(android.os.Build.VERSION.RELEASE))
                put("earbudModel", kotlinx.serialization.json.JsonPrimitive(finalEarbudModel))
                put("providerId", kotlinx.serialization.json.JsonPrimitive(finalProviderId))
                put("wakePath", kotlinx.serialization.json.JsonPrimitive(if (isT4Tier) resolveCalibratedWakePath() else "android_push_to_talk"))
                put("inputPath", kotlinx.serialization.json.JsonPrimitive(inputPath))
                put("outputPath", kotlinx.serialization.json.JsonPrimitive(outputPath))
                put("engineId", kotlinx.serialization.json.JsonPrimitive(if (isPcmTier || isT1Tier) "synthetic" else "platform_speech_recognizer"))
                put("bridgeMode", kotlinx.serialization.json.JsonPrimitive("local"))
                put("routeProofSource", kotlinx.serialization.json.JsonPrimitive(routeProofSource))
                put("physicalBluetoothProven", kotlinx.serialization.json.JsonPrimitive(physicalBluetoothProven))
                put("status", kotlinx.serialization.json.JsonPrimitive(if (summary.hasBlockingFailures) "FAILED" else "PASSED"))
                put("blockingFailures", kotlinx.serialization.json.JsonArray(
                    summary.failureReasons.map { kotlinx.serialization.json.JsonPrimitive(it) }
                ))
                put("sessions", kotlinx.serialization.json.JsonArray(
                    proofRun.sessions.mapIndexed { index, session ->
                        kotlinx.serialization.json.buildJsonObject {
                            put("sessionNumber", kotlinx.serialization.json.JsonPrimitive(index + 1))
                            put("sessionId", kotlinx.serialization.json.JsonPrimitive(session.sessionId))
                            put("engineId", kotlinx.serialization.json.JsonPrimitive(session.engineId))
                            put("isCompleted", kotlinx.serialization.json.JsonPrimitive(session.isCompleted))
                            put("wakeDetected", kotlinx.serialization.json.JsonPrimitive(session.sttSucceeded || session.speechDetected))
                            put("endpointReason", kotlinx.serialization.json.JsonPrimitive(session.endpointReason.name))
                            put("routeState", kotlinx.serialization.json.JsonPrimitive(session.routeState.name))
                            session.routeSelectedDeviceType?.let {
                                put("routeSelectedDeviceType", kotlinx.serialization.json.JsonPrimitive(it))
                            }
                            session.routeSettleMs?.let {
                                put("routeSettleMs", kotlinx.serialization.json.JsonPrimitive(it))
                            }
                            session.durationMs?.let {
                                put("durationMs", kotlinx.serialization.json.JsonPrimitive(it))
                            }
                            put("routeSucceeded", kotlinx.serialization.json.JsonPrimitive(session.routeSucceeded))
                            put("sttProducedTranscript", kotlinx.serialization.json.JsonPrimitive(session.sttSucceeded))
                            put("bridgeReached", kotlinx.serialization.json.JsonPrimitive(session.sttSucceeded))
                            put("ttsPlayed", kotlinx.serialization.json.JsonPrimitive(session.isCompleted))
                            put("speechDetected", kotlinx.serialization.json.JsonPrimitive(session.speechDetected))
                            put("wrongMicSuspected", kotlinx.serialization.json.JsonPrimitive(session.wrongMicSuspected))
                            put("finalTranscriptLength", kotlinx.serialization.json.JsonPrimitive(session.finalTranscriptLength))
                            put("rmsFrameCount", kotlinx.serialization.json.JsonPrimitive(session.rmsFrameCount))
                            session.rmsPeakDb?.let {
                                put("rmsPeakDb", kotlinx.serialization.json.JsonPrimitive(it))
                            }
                            put("rmsFramesAboveNoiseFloor", kotlinx.serialization.json.JsonPrimitive(session.rmsFramesAboveNoiseFloor))
                        }
                    }
                ))
                put("interruptionTests", kotlinx.serialization.json.JsonArray(
                    proofRun.ttsInterruptions.mapIndexed { index, interruption ->
                        kotlinx.serialization.json.buildJsonObject {
                            put("testNumber", kotlinx.serialization.json.JsonPrimitive(index + 1))
                            put("targetMet", kotlinx.serialization.json.JsonPrimitive(interruption.targetMet))
                            interruption.ttsStopLatencyMs?.let {
                                put("ttsStopLatencyMs", kotlinx.serialization.json.JsonPrimitive(it))
                            }
                            interruption.bargeInLatencyMs?.let {
                                put("listeningStartLatencyMs", kotlinx.serialization.json.JsonPrimitive(it))
                            }
                        }
                    }
                ))
                put("appVersion", kotlinx.serialization.json.JsonPrimitive(BuildConfig.VERSION_NAME))
                put("redactionMetadata", kotlinx.serialization.json.buildJsonObject {
                    put("rawAudioStored", kotlinx.serialization.json.JsonPrimitive(false))
                    put("transcriptsRedacted", kotlinx.serialization.json.JsonPrimitive(true))
                    put("personalDataRemoved", kotlinx.serialization.json.JsonPrimitive(true))
                })
                val calFields = resolveCalibrationFields(proofRun)
                put("calibrationProfileId", kotlinx.serialization.json.JsonPrimitive(calFields["calibrationProfileId"] as? String))
                put("calibratedGestureUsed", kotlinx.serialization.json.JsonPrimitive(calFields["calibratedGestureUsed"] as? String))
                put("matchedCalibratedAction", kotlinx.serialization.json.JsonPrimitive(calFields["matchedCalibratedAction"] as? String))
                put("sameProfileProof", kotlinx.serialization.json.JsonPrimitive(calFields["sameProfileProof"] as? Boolean ?: false))
                put("isModelScoped", kotlinx.serialization.json.JsonPrimitive(calFields["isModelScoped"] as? Boolean ?: false))
            }

            artifactFile.writeText(
                kotlinx.serialization.json.Json {
                    prettyPrint = true
                }.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), artifact)
            )

            Log.i(TAG, "proof artifact stored: ${artifactFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "failed to store proof artifact", e)
        }
    }
}
