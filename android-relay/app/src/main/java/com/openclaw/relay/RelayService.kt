package com.openclaw.relay

import android.Manifest
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import androidx.core.app.ServiceCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat as MediaAppNotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import com.openclaw.relay.history.ActivityHistoryEntry
import com.openclaw.relay.history.ActivityHistoryStore
import com.openclaw.relay.history.ActivityEventType
import com.openclaw.relay.signal.toHardwareContext

class RelayService : MediaSessionService() {
    companion object {
        private const val TAG = "OpenClawRelay"

        private const val NOTIFICATION_CHANNEL_ID = "openclaw-relay"
        private const val NOTIFICATION_ID = 41
        private const val LISTENING_SESSION_TIMEOUT_MS = 12_000L
        private const val LEARNING_PROMPT_TIMEOUT_MS = 60_000L
        private const val TONE_DURATION_MS = 300

        const val ACTION_START_RELAY = "com.openclaw.relay.action.START_RELAY"
        const val ACTION_STOP_RELAY = "com.openclaw.relay.action.STOP_RELAY"
        const val ACTION_CHECK_HEALTH = "com.openclaw.relay.action.CHECK_HEALTH"
        const val ACTION_WAKE_AND_LISTEN = "com.openclaw.relay.action.WAKE_AND_LISTEN"
        const val ACTION_QUICK_STATUS = "com.openclaw.relay.action.QUICK_STATUS"
        const val ACTION_ASSIST_LONG_PRESS = "com.openclaw.relay.action.ASSIST_LONG_PRESS"
        const val ACTION_TEST_SPEAKER = "com.openclaw.relay.action.TEST_SPEAKER"
        const val ACTION_TAP_TEST = "com.openclaw.relay.action.TAP_TEST"
        const val ACTION_APPROVE = "com.openclaw.relay.action.APPROVE"
        const val ACTION_REJECT = "com.openclaw.relay.action.REJECT"
        const val ACTION_CANCEL = "com.openclaw.relay.action.CANCEL"
        const val ACTION_RETRY_QUEUE = "com.openclaw.relay.action.RETRY_QUEUE"
        const val ACTION_DISCARD_QUEUE = "com.openclaw.relay.action.DISCARD_QUEUE"
        const val ACTION_AUDIO_ROUTE_PROBE = "com.openclaw.relay.action.AUDIO_ROUTE_PROBE"
        const val ACTION_DEBUG_EVENT = "com.openclaw.relay.action.DEBUG_EVENT"
        const val ACTION_RUN_PROOF = "com.openclaw.relay.action.RUN_PROOF"
        const val ACTION_EXPORT_PROOF = "com.openclaw.relay.action.EXPORT_PROOF"
        const val ACTION_COLLECT_BENCHMARK_SAMPLE = "com.openclaw.relay.action.COLLECT_BENCHMARK_SAMPLE"

        const val EXTRA_TRIGGER = "trigger"
        const val EXTRA_SERVICE_ACTION = "relayAction"
        const val EXTRA_BRIDGE_BASE_URL = "bridgeBaseUrl"
        const val EXTRA_RELAY_TOKEN = "relayToken"
        const val EXTRA_WORKSPACE = "workspace"
        const val EXTRA_EVENT_NAME = "eventName"
        const val EXTRA_UTTERANCE = "utterance"
        const val EXTRA_PENDING_ACTION_ID = "pendingActionId"
        const val EXTRA_BENCHMARK_COMMAND = "benchmarkCommand"
        const val EXTRA_BENCHMARK_ENGINE = "benchmarkEngine"

        fun intent(context: Context, action: String): Intent {
            return buildRelayServiceIntent(context, action)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val autonomyHandler = Handler(Looper.getMainLooper())
    private val learningPromptHandler = Handler(Looper.getMainLooper())
    private val bridgeClient = BridgeClient()
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
    private var pendingAutonomyContinuation: Runnable? = null
    private val listeningSessionMutex = Mutex()
    private val pendingEventQueue = mutableListOf<PendingBridgeEvent>()
    private var bridgeRetryAttempt = 0
    private var bridgeRetryJob: kotlinx.coroutines.Job? = null
    private var interruptedWakeSignal: RelayWakeSignal? = null
    private var activeSpeechRecorder: SpeechSessionMetricsRecorder? = null
    private var activeTtsInterruptionRecorder: TtsInterruptionMetricsRecorder? = null
    private var ttsWarmupJob: kotlinx.coroutines.Job? = null

    // R7: Track candidate route preparation to dedupe against final wake
    private var candidateRoutePreparedAtMs: Long = 0L
    private var pendingSpeechInputMode: SpeechInputMode? = null
    private var lastSpeechInputMode: SpeechInputMode = SpeechInputMode.PLATFORM
    private val CANDIDATE_ROUTE_FRESHNESS_MS = 1000L
    private val candidateDebounceTimes = mutableMapOf<String, Long>()
    private val CANDIDATE_DEBOUNCE_MS = 500L

    private data class PendingBridgeEvent(
        val event: RelayBridgeEvent,
        val onSpeechComplete: (() -> Unit)?,
    )

    internal lateinit var speechInputEngine: SpeechInputEngine
    internal lateinit var speechOutputEngine: SpeechOutputEngine
    private lateinit var audioRouter: BluetoothAudioRouter
    private lateinit var signalProviderRegistry: com.openclaw.relay.signal.SignalProviderRegistry
    private val micCaptureCoordinator = com.openclaw.relay.audio.AudioCaptureOwner()
    private lateinit var gestureRouter: com.openclaw.relay.calibration.CalibratedGestureRouter
    private var bridgeDiscoveryManager: BridgeDiscoveryManager? = null
    private var wearDataSync: com.openclaw.relay.wear.WearDataSync? = null

    override fun onCreate() {
        super.onCreate()
        RelayStateStore.applyServiceRecoveryPlan(RelayServiceRecoveryPolicy.plan(RelayStateStore.state.value))
        speechInputEngine = SpeechInputEngineFactory.create(
            this,
            RelayStateStore.state.value.config,
            micCaptureCoordinator,
        )
        RelayStateStore.setSpeechRecognitionAvailable(speechInputEngine.capabilities().isAvailable)
        RelayStateStore.setCurrentSpeechEngineId(speechInputEngine.id)
        RelayStateStore.setTtsReady(false)
        val ttsSpeaker = AndroidTtsSpeaker(
            this,
            onError = RelayStateStore::recordTtsError,
            onReadyChanged = RelayStateStore::setTtsReady,
            onSpeakingChanged = RelayStateStore::markSpeaking,
            onPlaybackMetrics = { metrics ->
                RelayStateStore.recordTtsPlaybackMetrics(metrics)
                if (metrics.event == TtsPlaybackEvent.STOPPED) {
                    activeTtsInterruptionRecorder?.let { recorder ->
                        recorder.markTtsStopped(metrics.stoppedAtMs ?: System.currentTimeMillis())
                        RelayStateStore.recordTtsInterruptionMetrics(recorder.snapshot())
                    }
                }
                activeSpeechRecorder?.let { recorder ->
                    when (metrics.event) {
                        TtsPlaybackEvent.STARTED -> recorder.markTtsStarted(metrics.startedAtMs ?: System.currentTimeMillis())
                        TtsPlaybackEvent.DONE -> recorder.markTtsDone(metrics.completedAtMs ?: System.currentTimeMillis())
                        TtsPlaybackEvent.STOPPED -> recorder.markInterrupted(
                            metrics.stoppedAtMs ?: System.currentTimeMillis(),
                            SpeechEndpointReason.STOP_REQUESTED,
                        )
                        TtsPlaybackEvent.ERROR -> recorder.markInterrupted(
                            metrics.errorAtMs ?: System.currentTimeMillis(),
                            SpeechEndpointReason.UNKNOWN_ERROR,
                        )
                        TtsPlaybackEvent.REQUESTED -> { }
                        TtsPlaybackEvent.WARMUP_REQUESTED -> { }
                        TtsPlaybackEvent.WARMUP_STARTED -> { }
                        TtsPlaybackEvent.WARMUP_DONE -> { }
                        TtsPlaybackEvent.WARMUP_ERROR -> { }
                    }
                    RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())
                }
            },
        )
        speechOutputEngine = AndroidTtsOutputEngine(ttsSpeaker)
        audioRouter = BluetoothAudioRouter(this)
        RelayStateStore.setAudioRoute(audioRouter.snapshot())
        signalProviderRegistry = com.openclaw.relay.signal.SignalProviderRegistry(this)
        signalProviderRegistry.start()

        gestureRouter = com.openclaw.relay.calibration.CalibratedGestureRouter {
            RelayStateStore.state.value.calibrationProfile
        }

        // Load existing calibration profile if available
        val savedProfile = com.openclaw.relay.device.DeviceProfileStorage.loadDeviceCalibrationProfile(this)
        if (savedProfile != null) {
            RelayStateStore.setCalibrationProfile(savedProfile)
        }

        serviceScope.launch {
            signalProviderRegistry.allEvents.collect { event ->
                handleSignalEvent(event)
            }
        }

        serviceScope.launch {
            signalProviderRegistry.preferredWakeProvider.collect { provider ->
                Log.i(TAG, "preferred wake provider changed to: ${provider?.providerId ?: "none"}")
            }
        }

        serviceScope.launch {
            signalProviderRegistry.providerHealth.collect { healthMap ->
                val uiHealth = healthMap.values.map { h ->
                    val provider = signalProviderRegistry.getProvider(h.providerId)
                    com.openclaw.relay.signal.ProviderHealthUi(
                        providerId = h.providerId,
                        providerLabel = provider?.providerLabel ?: h.providerId,
                        status = h.status.name.lowercase(),
                        deviceName = provider?.deviceState?.value?.displayName,
                        isConnected = provider?.deviceState?.value?.connectionState == com.openclaw.relay.signal.ConnectionState.CONNECTED,
                        lastError = h.lastError,
                    )
                }
                val preferredId = signalProviderRegistry.preferredWakeProvider.value?.providerId
                RelayStateStore.setProviderHealth(uiHealth, preferredId)
            }
        }

        val mediaSession = signalProviderRegistry.getMediaSessionProvider().mediaSession()
        mediaSession?.setSessionActivity(buildMainActivityPendingIntent())

        startOutboxPolling()

        if (!RelayStateStore.state.value.config.isPaired()) {
            bridgeDiscoveryManager = BridgeDiscoveryManager(this)
            bridgeDiscoveryManager?.startDiscovery()
        }

        startRelayForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        RelayStateStore.markServiceRunning(true)

        wearDataSync = com.openclaw.relay.wear.WearDataSync(this).also { it.start() }

        checkBridgeHealth()

        // Start latency optimization loops
        if (RelayStateStore.state.value.config.ttsWarmKeepaliveEnabled) {
            startTtsWarmupLoop()
        }
        if (RelayStateStore.state.value.config.speechRecognizerPrewarmEnabled) {
            prewarmSpeechRecognizer()
        }

        // R6: Observe config changes and start/stop warmup loop dynamically
        lastSpeechInputMode = RelayStateStore.state.value.config.speechInputMode
        serviceScope.launch {
            var lastWarmupEnabled = RelayStateStore.state.value.config.ttsWarmKeepaliveEnabled
            RelayStateStore.state.collect { state ->
                val currentWarmup = state.config.ttsWarmKeepaliveEnabled
                if (currentWarmup != lastWarmupEnabled) {
                    lastWarmupEnabled = currentWarmup
                    if (currentWarmup) startTtsWarmupLoop() else cancelTtsWarmupLoop()
                }
                // P1-3: Recreate speech input engine when mode changes
                val currentMode = state.config.speechInputMode
                if (currentMode != lastSpeechInputMode) {
                    pendingSpeechInputMode = currentMode
                    recreateSpeechInputEngine(state.config)
                }
            }
        }
    }

    private fun tryRetryPendingEngineRecreation() {
        val pending = pendingSpeechInputMode
        if (pending != null && !RelayStateStore.state.value.isListening) {
            Log.i(TAG, "Retrying deferred engine recreation for mode=$pending")
            serviceScope.launch {
                recreateSpeechInputEngine(RelayStateStore.state.value.config)
            }
        }
    }

    private suspend fun recreateSpeechInputEngine(config: RelayConfig) {
        if (RelayStateStore.state.value.isListening) {
            Log.w(TAG, "Deferring engine recreation: listening session is active")
            return
        }
        val oldEngine = speechInputEngine
        try {
            speechInputEngine = SpeechInputEngineFactory.create(
                this,
                config,
                micCaptureCoordinator,
            )
            RelayStateStore.setSpeechRecognitionAvailable(speechInputEngine.capabilities().isAvailable)
            RelayStateStore.setCurrentSpeechEngineId(speechInputEngine.id)
            lastSpeechInputMode = pendingSpeechInputMode ?: config.speechInputMode
            pendingSpeechInputMode = null
            Log.i(TAG, "Recreated speech input engine for mode=${config.speechInputMode} id=${speechInputEngine.id}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to recreate speech input engine for mode=${config.speechInputMode}", e)
            RelayStateStore.setError("Failed to switch speech recognizer: ${e.message}")
            RelayStateStore.setCurrentSpeechEngineId(null)
            return
        }
        // P2: Lifecycle-safe cleanup of old engine
        try {
            oldEngine.stop(SpeechStopReason.STOP_REQUESTED)
        } catch (e: Exception) {
            Log.w(TAG, "Old engine stop failed during recreation: ${e.message}")
        }
        try {
            oldEngine.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Old engine destroy failed during recreation: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!hasValidRelayCommandToken(this, intent)) {
            RelayStateStore.setError("Rejected an unauthorized relay command.")
            Log.w(TAG, "unauthorized relay command action=${intent?.action ?: "none"}")
            return START_NOT_STICKY
        }

        applyIntentConfig(intent)
        Log.i(
            TAG,
            "service onStartCommand action=${intent?.action ?: ACTION_START_RELAY} event=${intent?.getStringExtra(EXTRA_EVENT_NAME) ?: "none"}",
        )
        val serviceAction = intent?.action ?: ACTION_START_RELAY
        recordForegroundSnapshot(
            foregroundServiceType = RelayStateStore.state.value.voiceDiagnostics.foregroundService.foregroundServiceTypeMask,
            isActive = RelayStateStore.state.value.voiceDiagnostics.foregroundService.isForegroundActive,
            action = serviceAction,
        )
        if (shouldRefreshAudioRoute(serviceAction)) {
            RelayStateStore.setAudioRoute(audioRouter.snapshot())
        }

        when (serviceAction) {
            ACTION_START_RELAY -> {
                cancelPendingAutonomyContinuation()
                RelayStateStore.setAudioRoute(audioRouter.snapshot())
                checkBridgeHealth()
            }
            ACTION_STOP_RELAY -> {
                cancelPendingAutonomyContinuation()
                stopSelf()
            }
            ACTION_CHECK_HEALTH -> {
                cancelPendingAutonomyContinuation()
                checkBridgeHealth()
            }
            ACTION_WAKE_AND_LISTEN -> handleGestureSignal(
                RelayWakeSignal(
                    trigger = intent?.getStringExtra(EXTRA_TRIGGER)?.takeIf { value -> value.isNotBlank() } ?: "android_push_to_talk",
                    source = "manual_push_to_talk",
                    sourceLabel = "Push-to-talk button",
                    provider = ManualPushToTalkSignalProvider.observe(),
                ),
            )

            ACTION_QUICK_STATUS -> {
                val nowMs = System.currentTimeMillis()
                val sessionId = RelayStateStore.state.value.config.sessionId
                sendBridgeEvent(
                    event = RelayBridgeEvent(
                        sessionId = sessionId,
                        workspace = RelayStateStore.state.value.config.workspace,
                        event = "android_status_shortcut",
                        timestamp = nowMs,
                        idempotencyKey = "$sessionId-android_status_shortcut-status-$nowMs",
                    ),
                )
            }

            ACTION_ASSIST_LONG_PRESS -> handleAssistantLongPress(
                sourceAction = intent?.getStringExtra(EXTRA_TRIGGER),
            )

            ACTION_TEST_SPEAKER -> {
                if (!RelayStateStore.state.value.ttsReady) {
                    RelayStateStore.recordTtsError("Text-to-speech is still initializing. Please wait before running the speaker test.")
                    return START_STICKY
                }

                RelayStateStore.markSpeechStarted(System.currentTimeMillis())
                speakText("DevPods Relay is ready.")
            }

            ACTION_TAP_TEST -> runTapTest()

            ACTION_APPROVE -> {
                val learningPrompt = RelayStateStore.state.value.activeLearningPrompt
                if (learningPrompt != null && !isLearningPromptExpired(learningPrompt)) {
                    sendLearningPromptEvent("android_learning_confirm", learningPrompt)
                } else {
                    sendApprovalEvent("android_approve", intent?.getStringExtra(EXTRA_PENDING_ACTION_ID))
                }
            }
            ACTION_REJECT -> {
                val learningPrompt = RelayStateStore.state.value.activeLearningPrompt
                if (learningPrompt != null && !isLearningPromptExpired(learningPrompt)) {
                    sendLearningPromptEvent("android_learning_reject", learningPrompt)
                } else {
                    sendApprovalEvent("android_reject", intent?.getStringExtra(EXTRA_PENDING_ACTION_ID))
                }
            }
            ACTION_CANCEL -> {
                val learningPrompt = RelayStateStore.state.value.activeLearningPrompt
                if (learningPrompt != null) {
                    RelayStateStore.clearLearningPrompt()
                    learningPromptHandler.removeCallbacksAndMessages(learningPrompt.id)
                    Log.d(TAG, "Learning prompt cancelled by user")
                } else {
                    sendApprovalEvent("android_cancel", intent?.getStringExtra(EXTRA_PENDING_ACTION_ID))
                }
            }
            ACTION_RETRY_QUEUE -> retryPendingBridgeQueue()
            ACTION_DISCARD_QUEUE -> discardPendingBridgeQueue()
            ACTION_AUDIO_ROUTE_PROBE -> runAudioRouteProbe()
            ACTION_DEBUG_EVENT -> handleDebugEvent(intent)
            ACTION_RUN_PROOF -> handleRunProof(intent)
            ACTION_EXPORT_PROOF -> handleExportProof()
            ACTION_COLLECT_BENCHMARK_SAMPLE -> {
                val command = intent?.getStringExtra(EXTRA_BENCHMARK_COMMAND) ?: return START_STICKY
                val engineName = intent?.getStringExtra(EXTRA_BENCHMARK_ENGINE) ?: return START_STICKY
                val engine = try {
                    CommandBenchmarkEngine.valueOf(engineName)
                } catch (_: IllegalArgumentException) {
                    Log.w(TAG, "Unknown benchmark engine: $engineName")
                    return START_STICKY
                }
                collectBenchmarkSample(
                    expectedCommand = command,
                    engine = engine,
                    onCollected = { sample ->
                        RelayStateStore.recordBenchmarkSample(sample)
                    },
                )
            }
        }

        return START_STICKY
    }

    @Suppress("UnsafeOptInUsageError")
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession {
        return signalProviderRegistry.getMediaSessionProvider().mediaSession()
            ?: throw IllegalStateException("MediaSession not initialized")
    }

    override fun onDestroy() {
        cancelPendingAutonomyContinuation()
        cancelTtsWarmupLoop()
        recordForegroundSnapshot(
            foregroundServiceType = 0,
            isActive = false,
            action = ACTION_STOP_RELAY,
        )
        RelayStateStore.markServiceRunning(false)
        RelayStateStore.markListening(false)
        RelayStateStore.markAwaitingBridgeResponse(false)
        RelayStateStore.markSpeaking(false)
        RelayStateStore.clearPendingAction()
        RelayStateStore.clearAutonomy()
        RelayStateStore.clearLearningPrompt()
        learningPromptHandler.removeCallbacksAndMessages(null)
        toneGenerator.release()
        speechInputEngine.destroy()
        speechOutputEngine.close()
        audioRouter.clear()
        if (::signalProviderRegistry.isInitialized) {
            signalProviderRegistry.stop()
        }
        bridgeDiscoveryManager?.stopDiscovery()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun checkBridgeHealth() {
        val config = RelayStateStore.state.value.config
        serviceScope.launch {
            bridgeClient.health(config)
                .onSuccess { result ->
                    RelayStateStore.recordHealth(result.value, result.durationMs)
                    bridgeRetryAttempt = 0
                    drainPendingEventQueue()
                    fetchNotificationPreferences()
                    Log.i(
                        TAG,
                        "health ok=${result.value.ok} brain=${result.value.brainMode} transport=${result.value.openclawTransport ?: "none"} durationMs=${result.durationMs}",
                    )
                }
                .onFailure { error ->
                    RelayStateStore.setError(error.message ?: "Health check failed")
                    Log.e(TAG, "health failure: ${error.message}", error)
                }
        }
    }

    private fun fetchNotificationPreferences() {
        val config = RelayStateStore.state.value.config
        if (!config.isPaired()) return
        serviceScope.launch {
            try {
                val result = bridgeClient.getNotificationPreferences(config)
                if (result.isSuccess) {
                    RelayStateStore.setNotificationPreference(result.getOrThrow().value)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Preference fetch failed: ${e.message}")
            }
        }
    }

    internal fun handleGestureSignal(wakeSignal: RelayWakeSignal) {
        val config = RelayStateStore.state.value.config
        RelayStateStore.setWakeSignal(wakeSignal)
        RelayStateStore.markAwaitingBridgeResponse(false)
        RelayStateStore.setPartialTranscript("")
        RelayStateStore.setError("")

        if (shouldInterruptImplementation(RelayStateStore.state.value, wakeSignal)) {
            interruptImplementationAndListen(wakeSignal)
            return
        }

        if (!shouldOpenListeningWindow(wakeSignal)) {
            sendBridgeEvent(buildGestureBridgeEvent(wakeSignal))
            return
        }

        // R7: Reuse candidate route preparation if still fresh
        val nowMs = System.currentTimeMillis()
        val routePrepared = if (nowMs - candidateRoutePreparedAtMs < CANDIDATE_ROUTE_FRESHNESS_MS) {
            Log.d(TAG, "Reusing candidate-prepared route (freshness=${nowMs - candidateRoutePreparedAtMs}ms)")
            true
        } else {
            prepareListeningRoute()
        }
        candidateRoutePreparedAtMs = 0L // Clear after use
        if (!routePrepared) {
            return
        }

        // Pre-warm STT engine if enabled to remove recognizer creation from the critical path
        if (config.speechRecognizerPrewarmEnabled) {
            prewarmSpeechRecognizer()
        }

        // Persist preferred provider when a calibrated wake gesture successfully prepares the route
        if (config.preferredProviderOrderingEnabled &&
            wakeSignal.calibratedGestureType != null &&
            wakeSignal.matchedCalibratedAction == com.openclaw.relay.calibration.GestureAction.WAKE_AND_LISTEN.name
        ) {
            signalProviderRegistry.setPersistedPreferredProvider(
                wakeSignal.source,
                com.openclaw.relay.device.PreferredProviderSource.CALIBRATED,
            )
        }

        updateListenReadiness()

        val readiness = RelayStateStore.state.value.listenReadiness
        if (readiness == com.openclaw.relay.signal.ListenReadiness.BLOCKED) {
            val message = RelayStateStore.state.value.listenReadinessMessage
            RelayStateStore.setError(message)
            speakText(message)
            return
        }

        if (config.fastWakeEnabled) {
            // Fast wake path: start STT immediately without waiting for bridge TTS acknowledgement
            startListeningSession(wakeSignal, routeAlreadyPrepared = true)
            // Safe prefetch: non-executing workspace cache warming (does NOT send a bridge command)
            if (config.bridgePrefetchOnWakeEnabled) {
                prefetchWorkspaceData(config)
            }
        } else {
            // Legacy slow path: wait for bridge acknowledgement TTS before opening STT
            sendBridgeEvent(
                event = buildGestureBridgeEvent(wakeSignal),
                onSpeechComplete = { startListeningSession(wakeSignal, routeAlreadyPrepared = true) },
            )
        }
    }

    private fun handleCalibratedAction(
        action: com.openclaw.relay.calibration.GestureAction,
        event: com.openclaw.relay.signal.EarbudSignalEvent,
    ) {
        Log.i(TAG, "Calibrated action routed: $action from ${event.providerId}")
        when (action) {
            com.openclaw.relay.calibration.GestureAction.WAKE_AND_LISTEN -> {
                val provider = signalProviderRegistry.getProvider(event.providerId)
                val gestureType = when (event) {
                    is com.openclaw.relay.signal.EarbudSignalEvent.WakeGesture -> event.gestureType
                    is com.openclaw.relay.signal.EarbudSignalEvent.InterruptGesture -> event.gestureType
                    else -> com.openclaw.relay.signal.GestureType.UNKNOWN
                }
                val wakeSignal = RelayWakeSignal(
                    trigger = normalizeProviderEventToBridgeTrigger(gestureType),
                    source = event.providerId,
                    sourceLabel = provider?.providerLabel ?: event.providerId,
                    provider = com.openclaw.relay.RelayObservedSignalProvider(
                        providerId = event.providerId,
                        providerLabel = provider?.providerLabel ?: event.providerId,
                        confidence = com.openclaw.relay.RelaySignalConfidence.PROVEN,
                        isPhysicalInput = provider?.isPhysicalInput ?: true,
                    ),
                    calibratedGestureType = gestureType,
                    matchedCalibratedAction = action.name,
                )
                handleGestureSignal(wakeSignal)
            }
            com.openclaw.relay.calibration.GestureAction.INTERRUPT -> {
                val provider = signalProviderRegistry.getProvider(event.providerId)
                val gestureType = when (event) {
                    is com.openclaw.relay.signal.EarbudSignalEvent.WakeGesture -> event.gestureType
                    is com.openclaw.relay.signal.EarbudSignalEvent.InterruptGesture -> event.gestureType
                    else -> com.openclaw.relay.signal.GestureType.UNKNOWN
                }
                val wakeSignal = RelayWakeSignal(
                    trigger = normalizeProviderEventToBridgeTrigger(gestureType, isInterrupt = true),
                    source = event.providerId,
                    sourceLabel = provider?.providerLabel ?: event.providerId,
                    provider = com.openclaw.relay.RelayObservedSignalProvider(
                        providerId = event.providerId,
                        providerLabel = provider?.providerLabel ?: event.providerId,
                        confidence = com.openclaw.relay.RelaySignalConfidence.PROVEN,
                        isPhysicalInput = provider?.isPhysicalInput ?: true,
                    ),
                    calibratedGestureType = gestureType,
                    matchedCalibratedAction = action.name,
                )
                handleGestureSignal(wakeSignal)
            }
            com.openclaw.relay.calibration.GestureAction.APPROVE -> {
                val learningPrompt = RelayStateStore.state.value.activeLearningPrompt
                if (learningPrompt != null && !isLearningPromptExpired(learningPrompt)) {
                    sendLearningPromptEvent("android_learning_confirm", learningPrompt)
                } else {
                    sendApprovalEvent("android_approve")
                }
            }
            com.openclaw.relay.calibration.GestureAction.REJECT -> {
                val learningPrompt = RelayStateStore.state.value.activeLearningPrompt
                if (learningPrompt != null && !isLearningPromptExpired(learningPrompt)) {
                    sendLearningPromptEvent("android_learning_reject", learningPrompt)
                } else {
                    sendApprovalEvent("android_reject")
                }
            }
            com.openclaw.relay.calibration.GestureAction.NONE -> {
                Log.d(TAG, "Calibrated action is NONE — ignoring gesture")
            }
        }
    }

    private fun persistRuntimeMissCount() {
        com.openclaw.relay.device.DeviceProfileStorage.saveRuntimeMissCount(
            this,
            RelayStateStore.state.value.runtimeMissCount,
        )
    }

    private fun startOutboxPolling() {
        serviceScope.launch {
            var cursor = ""
            while (true) {
                delay(10_000)
                val config = RelayStateStore.state.value.config
                if (!config.isPaired()) continue
                try {
                    val result = bridgeClient.pollOutbox(config, cursor.takeIf { it.isNotBlank() })
                    if (result.isSuccess) {
                        val poll = result.getOrThrow()
                        val events = poll.value.events
                        if (events.isNotEmpty()) {
                            cursor = poll.value.cursor ?: cursor
                            RelayStateStore.setOutboxEvents(events, cursor)
                            processOutboxEvents(events)
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Outbox poll failed: ${e.message}")
                }
            }
        }
    }

    private fun processOutboxEvents(events: List<BridgeOutboxEvent>) {
        val prefs = RelayStateStore.getNotificationPreference()
        val mutedKinds = prefs.mutedKinds.toSet()
        val isSilent = prefs.style == "silent_with_badge"
        val canSpeak = canSpeakOutboxEvent()

        for (event in events) {
            var presented = false

            if (event.kind in mutedKinds) {
                Log.d(TAG, "Outbox event muted: ${event.kind}")
                presented = true
            } else when (event.kind) {
                "completion_soft_ping" -> {
                    maybePlayTone(event.kind)
                    if (isSilent) {
                        Log.d(TAG, "Soft ping suppressed by silent style")
                        presented = true // badge-only delivery counts as presented
                    } else if (!prefs.softPingTtsEnabled) {
                        Log.d(TAG, "Soft ping TTS disabled")
                        presented = true
                    } else if (canSpeak) {
                        speakText(event.summary)
                        presented = true
                    }
                }
                "completion_full_report" -> {
                    Log.i(TAG, "Outbox full report: ${event.summary}")
                    presented = true
                }
                "reminder_due" -> {
                    maybePlayTone(event.kind)
                    if (isSilent) {
                        presented = true
                    } else if (!prefs.reminderTtsEnabled) {
                        presented = true
                    } else if (canSpeak) {
                        speakText("Reminder: ${event.summary}")
                        presented = true
                    }
                }
                "approval_pending" -> {
                    maybePlayTone(event.kind)
                    Log.i(TAG, "Outbox approval pending: ${event.summary}")
                    postApprovalNotification(event)
                    presented = true
                }
                "workspace_nudge" -> {
                    maybePlayTone(event.kind)
                    if (isSilent) {
                        presented = true
                    } else if (!prefs.nudgeTtsEnabled) {
                        presented = true
                    } else if (canSpeak) {
                        speakText(event.summary)
                        presented = true
                    }
                }
                "learning_prompt" -> {
                    if (isSilent) {
                        presented = true
                    } else if (canSpeak) {
                        RelayStateStore.setActiveLearningPrompt(event)
                        scheduleLearningPromptExpiry(event)
                        speakText(event.summary)
                        presented = true
                    }
                }
                else -> {
                    Log.d(TAG, "Outbox event unhandled: ${event.kind}")
                    presented = true
                }
            }

            if (presented) {
                serviceScope.launch { ackOutboxEvent(event.id) }
            } else {
                Log.d(TAG, "Outbox event deferred (not presented): ${event.id} ${event.kind}")
            }
        }
    }

    private fun canSpeakOutboxEvent(): Boolean {
        val state = RelayStateStore.state.value
        return !state.isListening && !state.isSpeaking && state.pendingApprovalRequest == null
    }

    private fun maybePlayTone(eventKind: String) {
        val prefs = RelayStateStore.getNotificationPreference()
        val shouldPlay = when (prefs.style) {
            "aggressive" -> eventKind != "badge_update"
            "soft" -> eventKind == "approval_pending" || eventKind == "reminder_due"
            else -> false
        }
        if (shouldPlay) {
            try {
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_DURATION_MS)
            } catch (e: Exception) {
                Log.w(TAG, "Tone playback failed: ${e.message}")
            }
        }
    }

    private fun scheduleLearningPromptExpiry(event: BridgeOutboxEvent) {
        learningPromptHandler.removeCallbacksAndMessages(event.id)
        learningPromptHandler.postDelayed({
            val current = RelayStateStore.state.value.activeLearningPrompt
            if (current?.id == event.id) {
                RelayStateStore.clearLearningPrompt()
                Log.d(TAG, "Learning prompt expired: ${event.id}")
            }
        }, event.id, LEARNING_PROMPT_TIMEOUT_MS)
    }

    private fun isLearningPromptExpired(prompt: BridgeOutboxEvent): Boolean {
        return System.currentTimeMillis() > prompt.expiresAtMs
    }

    private suspend fun ackOutboxEvent(eventId: String) {
        val config = RelayStateStore.state.value.config
        if (!config.isPaired()) return
        try {
            bridgeClient.ackOutboxEvent(config, eventId)
            RelayStateStore.removeOutboxEvent(eventId)
        } catch (e: Exception) {
            Log.d(TAG, "Outbox ack failed: ${e.message}")
        }
    }

    private fun handleSignalEvent(event: com.openclaw.relay.signal.EarbudSignalEvent) {
        when (event) {
            is com.openclaw.relay.signal.EarbudSignalEvent.WakeGesture -> {
                val routedAction = gestureRouter.route(event)
                if (routedAction != null) {
                    handleCalibratedAction(routedAction, event)
                    persistRuntimeMissCount()
                    return@handleSignalEvent
                }
                RelayStateStore.recordUnmatchedSignal(
                    providerId = event.providerId,
                    gestureType = event.gestureType.name,
                    keyCode = event.keyCode,
                    reason = "no_ready_profile_or_uncalibrated",
                )
                persistRuntimeMissCount()
                Log.d(TAG, "WakeGesture ignored: no ready calibration profile or uncalibrated signal")
            }
            is com.openclaw.relay.signal.EarbudSignalEvent.InterruptGesture -> {
                val routedAction = gestureRouter.route(event)
                if (routedAction != null) {
                    handleCalibratedAction(routedAction, event)
                    persistRuntimeMissCount()
                    return@handleSignalEvent
                }
                RelayStateStore.recordUnmatchedSignal(
                    providerId = event.providerId,
                    gestureType = event.gestureType.name,
                    keyCode = event.keyCode,
                    reason = "no_ready_profile_or_uncalibrated",
                )
                persistRuntimeMissCount()
                Log.d(TAG, "InterruptGesture ignored: no ready calibration profile or uncalibrated signal")
            }
            is com.openclaw.relay.signal.EarbudSignalEvent.ApprovalGesture -> {
                val currentState = RelayStateStore.state.value
                val learningPrompt = currentState.activeLearningPrompt
                if (learningPrompt != null && !isLearningPromptExpired(learningPrompt)) {
                    val approvalAction = gestureRouter.routeApprovalAction(event)
                    val eventName = when (approvalAction) {
                        com.openclaw.relay.calibration.GestureAction.APPROVE -> "android_learning_confirm"
                        com.openclaw.relay.calibration.GestureAction.REJECT -> "android_learning_reject"
                        else -> {
                            Log.w(TAG, "Learning gesture ignored: mapped to $approvalAction")
                            return@handleSignalEvent
                        }
                    }
                    sendLearningPromptEvent(eventName, learningPrompt)
                    persistRuntimeMissCount()
                    return@handleSignalEvent
                }
                if (RelayStateStore.isPendingApprovalExpired()) {
                    RelayStateStore.clearPendingAction()
                    Log.d(TAG, "Approval gesture ignored: approval expired")
                    return@handleSignalEvent
                }
                if (currentState.pendingApprovalRequest != null) {
                    val approvalAction = gestureRouter.routeApprovalAction(event)
                    if (approvalAction == null) {
                        RelayStateStore.recordUnmatchedSignal(
                            providerId = event.providerId,
                            gestureType = event.gestureType.name,
                            keyCode = event.keyCode,
                            reason = "no_ready_profile_or_ambiguous_approval",
                        )
                        persistRuntimeMissCount()
                        Log.w(TAG, "Approval gesture rejected: no ready calibration profile or ambiguous signal")
                        return@handleSignalEvent
                    }
                    val eventName = when (approvalAction) {
                        com.openclaw.relay.calibration.GestureAction.APPROVE -> "android_approve"
                        com.openclaw.relay.calibration.GestureAction.REJECT -> "android_reject"
                        else -> {
                            Log.w(TAG, "Approval gesture ignored: mapped to $approvalAction")
                            return@handleSignalEvent
                        }
                    }
                    sendApprovalEvent(eventName)
                    persistRuntimeMissCount()
                } else {
                    Log.d(TAG, "Approval gesture ignored: no pending approval")
                }
            }
            is com.openclaw.relay.signal.EarbudSignalEvent.EarStateChanged -> {
                val current = signalProviderRegistry.getProvider(event.providerId)?.deviceState?.value
                RelayStateStore.setCurrentDeviceState(current)
                updateListenReadiness()
            }
            is com.openclaw.relay.signal.EarbudSignalEvent.BatteryChanged -> {
                val current = signalProviderRegistry.getProvider(event.providerId)?.deviceState?.value
                RelayStateStore.setCurrentDeviceState(current)
                updateListenReadiness()
            }
            is com.openclaw.relay.signal.EarbudSignalEvent.ConnectionChanged -> {
                val current = signalProviderRegistry.getProvider(event.providerId)?.deviceState?.value
                RelayStateStore.setCurrentDeviceState(current)
                if (!event.connected && RelayStateStore.state.value.isListening) {
                    serviceScope.launch {
                        speechInputEngine.stop(SpeechStopReason.CANCELLED)
                    }
                    RelayStateStore.markListening(false)
                    interruptedWakeSignal = RelayStateStore.state.value.lastWakeSignal
                    RelayStateStore.setError("Headset disconnected. Listening paused.")
                } else if (event.connected && interruptedWakeSignal != null) {
                    interruptedWakeSignal = null
                    RelayStateStore.clearError()
                    RelayStateStore.setLastHeadsetEvent("Headset reconnected. Ready to resume.")
                }
                // Check calibration requirement on connect
                if (event.connected) {
                    val profile = RelayStateStore.state.value.calibrationProfile
                    val hasProfile = profile != null && profile.isReadyForRuntime()
                    if (!hasProfile) {
                        RelayStateStore.setCalibrationRequired(true)
                        Log.i(TAG, "Earbuds connected without calibration profile — calibration required")
                    }
                }
                updateListenReadiness()
            }
            is com.openclaw.relay.signal.EarbudSignalEvent.InputCandidateStarted -> {
                if (!RelayStateStore.state.value.config.speculativeRoutePrepareEnabled) {
                    return@handleSignalEvent
                }
                // Speculative route preparation: warm Bluetooth route on first tap/down,
                // but do NOT start audio capture, send bridge commands, or trigger approvals.
                if (!RelayStateStore.state.value.isListening &&
                    !RelayStateStore.state.value.isSpeaking &&
                    RelayStateStore.state.value.pendingApprovalRequest == null
                ) {
                    Log.d(TAG, "Speculative route preparation for candidate from ${event.providerId}")
                    val routeOk = prepareListeningRoute()
                    if (routeOk) {
                        candidateRoutePreparedAtMs = System.currentTimeMillis()
                    }
                    // Telemetry: route preparation is not audio capture
                    RelayStateStore.recordMediaButtonEvent(
                        com.openclaw.relay.MediaButtonEventTelemetry(
                            keyCode = 0,
                            keyLabel = "candidate",
                            action = com.openclaw.relay.MediaButtonAction.CANDIDATE,
                            repeatCount = 0,
                            mapping = "speculative_route_prepare",
                            accepted = true,
                            debounced = false,
                            receivedAtMs = System.currentTimeMillis(),
                            routeState = RelayStateStore.state.value.audioRoute.proof.routeState,
                            serviceRunning = true,
                        )
                    )
                    // Safe prefetch on candidate: warm workspace cache when idle and paired
                    val config = RelayStateStore.state.value.config
                    if (config.bridgePrefetchOnWakeEnabled && config.isPaired()) {
                        prefetchWorkspaceData(config)
                    }
                }
            }
            else -> { }
        }
    }

    private fun updateListenReadiness() {
        val state = RelayStateStore.state.value
        val deviceState = state.currentDeviceState
        val audioRoute = state.audioRoute
        val readiness = com.openclaw.relay.signal.computeListenReadiness(
            deviceState = deviceState,
            audioRoute = audioRoute,
            useBluetoothRouting = state.config.useBluetoothRouting,
            speechRecognitionAvailable = state.speechRecognitionAvailable,
        )
        RelayStateStore.setListenReadiness(readiness.readiness, readiness.userFacingMessage)
    }

    internal fun startListeningSession(wakeSignal: RelayWakeSignal, routeAlreadyPrepared: Boolean = false) {
        beginListeningSession(
            gestureReceivedAtMs = wakeSignal.receivedAtMs,
            routeAlreadyPrepared = routeAlreadyPrepared,
        ) { transcript ->
            val nowMs = System.currentTimeMillis()
            val sessionId = RelayStateStore.state.value.config.sessionId
            sendBridgeEvent(
                RelayBridgeEvent(
                    sessionId = sessionId,
                    workspace = RelayStateStore.state.value.config.workspace,
                    event = wakeSignal.trigger,
                    timestamp = nowMs,
                    utterance = transcript,
                    hardwareContext = wakeSignal.hardwareContext,
                    idempotencyKey = "$sessionId-${wakeSignal.trigger}-voice-$nowMs",
                ),
            )
        }
    }

    private fun startAutonomyInterruptListeningSession(wakeSignal: RelayWakeSignal, routeAlreadyPrepared: Boolean = false) {
        beginListeningSession(
            gestureReceivedAtMs = wakeSignal.receivedAtMs,
            routeAlreadyPrepared = routeAlreadyPrepared,
        ) { transcript ->
            val nowMs = System.currentTimeMillis()
            val sessionId = RelayStateStore.state.value.config.sessionId
            sendBridgeEvent(
                RelayBridgeEvent(
                    sessionId = sessionId,
                    workspace = RelayStateStore.state.value.config.workspace,
                    event = "android_autonomy_interrupt",
                    timestamp = nowMs,
                    utterance = transcript,
                    hardwareContext = wakeSignal.hardwareContext
                        ?: RelayStateStore.state.value.lastWakeSignal?.hardwareContext,
                    idempotencyKey = "$sessionId-android_autonomy_interrupt-interrupt-$nowMs",
                ),
            )
        }
    }

    private fun beginListeningSession(
        gestureReceivedAtMs: Long? = null,
        routeAlreadyPrepared: Boolean = false,
        onFinalTranscript: (String) -> Unit,
    ) {
        serviceScope.launch {
            if (!listeningSessionMutex.tryLock()) {
                RelayStateStore.setError("A listening session is already active.")
                Log.w(TAG, "listen request ignored because mutex is already locked")
                return@launch
            }

            try {
                val profile = RelayStateStore.state.value.calibrationProfile
                val wakeSignal = RelayStateStore.state.value.lastWakeSignal
                val recorder = SpeechSessionMetricsRecorder(
                    sessionId = "speech-${System.currentTimeMillis()}",
                    engineId = speechInputEngine.id,
                    wakeSignal = wakeSignal?.trigger,
                    startedAtMs = System.currentTimeMillis(),
                    calibrationProfileId = if (wakeSignal?.calibratedGestureType != null) profile?.profileId else null,
                    calibratedGestureUsed = wakeSignal?.calibratedGestureType?.name,
                    matchedCalibratedAction = wakeSignal?.matchedCalibratedAction,
                )
                gestureReceivedAtMs?.let { recorder.markGestureReceived(it) }
                activeSpeechRecorder = recorder
                RelayStateStore.setSpeechSessionState(SpeechSessionState.ROUTING)
                RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())

                if (RelayStateStore.state.value.isListening) {
                    RelayStateStore.setError("A listening session is already active.")
                    Log.w(TAG, "recovering stale listening state before sessionId=${recorder.snapshot().sessionId}")
                    speechInputEngine.stop(SpeechStopReason.ENGINE_RESET)
                    recorder.markState(SpeechSessionState.FAILED)
                    recorder.markError(
                        nowMs = System.currentTimeMillis(),
                        errorCode = null,
                        reason = SpeechEndpointReason.RECOGNIZER_BUSY,
                    )
                    RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())
                    RelayStateStore.markListening(false)
                    RelayStateStore.setSpeechSessionState(SpeechSessionState.FAILED)
                    return@launch
                }

                RelayStateStore.clearListeningStartupErrors()

                recorder.markRouteRequested(System.currentTimeMillis())
                if (!routeAlreadyPrepared && !prepareListeningRoute()) {
                    recorder.markState(SpeechSessionState.FAILED)
                    recorder.markError(
                        nowMs = System.currentTimeMillis(),
                        errorCode = null,
                        reason = SpeechEndpointReason.ROUTE_FAILED,
                    )
                    RelayStateStore.setSpeechSessionState(SpeechSessionState.FAILED)
                    RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())
                    return@launch
                }
                recorder.markRouteReady(System.currentTimeMillis(), RelayStateStore.state.value.audioRoute)
                RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())

                if (!waitForListeningRouteReady()) {
                    recorder.markRouteReady(System.currentTimeMillis(), RelayStateStore.state.value.audioRoute)
                    recorder.markState(SpeechSessionState.FAILED)
                    recorder.markError(
                        nowMs = System.currentTimeMillis(),
                        errorCode = null,
                        reason = SpeechEndpointReason.ROUTE_FAILED,
                    )
                    RelayStateStore.setSpeechSessionState(SpeechSessionState.FAILED)
                    RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())
                    return@launch
                }
                recorder.markRouteReady(System.currentTimeMillis(), RelayStateStore.state.value.audioRoute)
                RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())

                val routeSettleMs = recorder.snapshot().routeSettleMs
                if (routeSettleMs != null) {
                    ActivityHistoryStore.add(
                        this@RelayService,
                        ActivityHistoryEntry(
                            type = ActivityEventType.ROUTE_SETTLED,
                            summary = "Microphone route ready",
                            detail = "Route settled in ${routeSettleMs}ms",
                        ),
                    )
                }

                RelayStateStore.markAwaitingBridgeResponse(false)
                RelayStateStore.setPartialTranscript("")
                RelayStateStore.markListening(true)
                RelayStateStore.setSpeechSessionState(SpeechSessionState.LISTENING)
                var timeoutJob: kotlinx.coroutines.Job? = null
                fun isAwaitingSpeechResult(): Boolean =
                    recorder.snapshot().endpointReason == SpeechEndpointReason.LISTENING

                timeoutJob = serviceScope.launch {
                    delay(LISTENING_SESSION_TIMEOUT_MS)
                    if (!isAwaitingSpeechResult()) {
                        return@launch
                    }

                    Log.w(TAG, "speech recognizer timed out sessionId=${recorder.snapshot().sessionId}")
                    speechInputEngine.stop(SpeechStopReason.STOP_REQUESTED)
                    recorder.markError(
                        nowMs = System.currentTimeMillis(),
                        errorCode = null,
                        reason = SpeechEndpointReason.TIMEOUT,
                    )
                    recorder.markState(SpeechSessionState.FAILED)
                    RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())
                    RelayStateStore.markListening(false)
                    RelayStateStore.setSpeechSessionState(SpeechSessionState.FAILED)
                    tryRetryPendingEngineRecreation()
                    checkAndRecordWrongMic(recorder.snapshot())
                    postSttErrorNotification("Speech recognition timed out. Try again.")
                }
                try {
                    speechInputEngine.start(
                        request = SpeechSessionRequest(
                            sessionId = recorder.snapshot().sessionId,
                            wakeSignal = RelayStateStore.state.value.lastWakeSignal?.trigger,
                        ),
                        callbacks = SpeechCallbacks(
                        onPartialTranscript = { partial ->
                            recorder.markPartial(System.currentTimeMillis(), partial)
                            RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())
                            RelayStateStore.setPartialTranscript(partial)
                        },
                        onFinalTranscript = { transcript ->
                            if (isAwaitingSpeechResult()) {
                                timeoutJob?.cancel()
                                recorder.markFinal(System.currentTimeMillis(), transcript)
                                recorder.markState(SpeechSessionState.FINALIZING)
                                RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())
                                Log.i(
                                    TAG,
                                    "speech recognizer final transcript sessionId=${recorder.snapshot().sessionId} length=${transcript.length}",
                                )
                                RelayStateStore.markListening(false)
                                RelayStateStore.setSpeechSessionState(SpeechSessionState.FINALIZING)
                                checkAndRecordWrongMic(recorder.snapshot())
                                RelayStateStore.setTranscript(transcript)
                                onFinalTranscript(transcript)
                                RelayStateStore.setSpeechSessionState(SpeechSessionState.IDLE)
                                tryRetryPendingEngineRecreation()
                                // Prewarm STT for next session after successful recognition
                                if (RelayStateStore.state.value.config.speechRecognizerPrewarmEnabled) {
                                    prewarmSpeechRecognizer()
                                }
                            } else {
                                Log.w(TAG, "ignored late final transcript sessionId=${recorder.snapshot().sessionId}")
                            }
                        },
                        onError = { failure ->
                            if (isAwaitingSpeechResult()) {
                                timeoutJob?.cancel()
                                recorder.markError(
                                    nowMs = System.currentTimeMillis(),
                                    errorCode = failure.errorCode,
                                    reason = failure.endpointReason,
                                )
                                recorder.markState(SpeechSessionState.FAILED)
                                RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())
                                RelayStateStore.setAudioRoute(audioRouter.snapshot())
                                RelayStateStore.recordSpeechError(failure.message)
                                RelayStateStore.markListening(false)
                                RelayStateStore.setSpeechSessionState(SpeechSessionState.FAILED)
                                tryRetryPendingEngineRecreation()
                                checkAndRecordWrongMic(recorder.snapshot())
                                Log.w(
                                    TAG,
                                    "speech recognizer failed sessionId=${recorder.snapshot().sessionId} reason=${failure.endpointReason} code=${failure.errorCode}",
                                )
                                postSttErrorNotification(failure.message)
                                if (failure.endpointReason == SpeechEndpointReason.RECOGNIZER_BUSY) {
                                    attemptRecognizerBusyRecovery(recorder.snapshot())
                                }
                                // Prewarm STT after error recovery if reset occurred
                                if (RelayStateStore.state.value.config.speechRecognizerPrewarmEnabled) {
                                    prewarmSpeechRecognizer()
                                }
                            } else {
                                Log.w(
                                    TAG,
                                    "ignored late speech recognizer failure sessionId=${recorder.snapshot().sessionId} reason=${failure.endpointReason} code=${failure.errorCode}",
                                )
                            }
                        },
                        onRecognizerCreated = {
                            recorder.markRecognizerCreated(System.currentTimeMillis())
                            RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())
                        },
                        onListeningStarted = {
                            val nowMs = System.currentTimeMillis()
                            recorder.markListeningStarted(nowMs)
                            Log.i(TAG, "speech recognizer listening started sessionId=${recorder.snapshot().sessionId}")
                            activeTtsInterruptionRecorder?.let { interruptionRecorder ->
                                interruptionRecorder.markListeningStarted(nowMs)
                                RelayStateStore.recordTtsInterruptionMetrics(interruptionRecorder.snapshot())
                                activeTtsInterruptionRecorder = null
                            }
                            RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())
                        },
                        onReadyForSpeech = {
                            val nowMs = System.currentTimeMillis()
                            recorder.markReadyForSpeech(nowMs)
                            RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())
                            Log.i(TAG, "speech recognizer ready for speech sessionId=${recorder.snapshot().sessionId}")
                        },
                        onBeginningOfSpeech = {
                            recorder.markBeginningOfSpeech(System.currentTimeMillis())
                            RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())
                            Log.i(TAG, "speech recognizer beginning of speech sessionId=${recorder.snapshot().sessionId}")
                        },
                        onRmsChanged = { rmsDb ->
                            recorder.markRmsChanged(System.currentTimeMillis(), rmsDb)
                            RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())
                        },
                        onEndOfSpeech = {
                            recorder.markEndOfSpeech(System.currentTimeMillis())
                            RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())
                            Log.i(TAG, "speech recognizer end of speech sessionId=${recorder.snapshot().sessionId}")
                        },
                        ),
                    )
                } catch (error: Exception) {
                    timeoutJob?.cancel()
                    recorder.markError(
                        nowMs = System.currentTimeMillis(),
                        errorCode = null,
                        reason = SpeechEndpointReason.CLIENT_ERROR,
                    )
                    recorder.markState(SpeechSessionState.FAILED)
                    RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())
                    RelayStateStore.markListening(false)
                    RelayStateStore.setSpeechSessionState(SpeechSessionState.FAILED)
                    speechInputEngine.stop(SpeechStopReason.ENGINE_RESET)
                    Log.e(TAG, "speech recognizer start failed sessionId=${recorder.snapshot().sessionId}", error)
                    postSttErrorNotification("Speech recognition could not start. Try again.")
                }
            } finally {
                listeningSessionMutex.unlock()
            }
        }
    }

    private fun checkAndRecordWrongMic(metrics: SpeechSessionMetrics) {
        val observation = metrics.toPlatformVadObservation()
        if (observation.wrongMicSuspected) {
            ActivityHistoryStore.add(
                this,
                ActivityHistoryEntry(
                    type = ActivityEventType.WRONG_MIC_SUSPECTED,
                    summary = "Wrong microphone suspected",
                    detail = "Bluetooth route appeared active but no microphone signal was detected.",
                ),
            )
        }
    }

    private fun attemptRecognizerBusyRecovery(failedMetrics: SpeechSessionMetrics) {
        val wakeSignal = RelayStateStore.state.value.lastWakeSignal
        if (wakeSignal != null) {
            serviceScope.launch {
                delay(500)
                Log.i(TAG, "Recovering from recognizer busy for session ${failedMetrics.sessionId}")
                beginListeningSession { transcript ->
                    val nowMs = System.currentTimeMillis()
                    val sessionId = RelayStateStore.state.value.config.sessionId
                    sendBridgeEvent(
                        RelayBridgeEvent(
                            sessionId = sessionId,
                            workspace = RelayStateStore.state.value.config.workspace,
                            event = wakeSignal.trigger,
                            timestamp = nowMs,
                            utterance = transcript,
                            hardwareContext = wakeSignal.hardwareContext,
                            idempotencyKey = "$sessionId-${wakeSignal.trigger}-recovery-$nowMs",
                        ),
                    )
                }
            }
        }
    }

    private suspend fun waitForListeningRouteReady(): Boolean {
        val useBluetoothRouting = RelayStateStore.state.value.config.useBluetoothRouting
        if (!useBluetoothRouting) {
            return true
        }
        if (RelayStateStore.state.value.audioRoute.isPhoneMicFallback) {
            return true
        }

        val settleDelays = listOf(0L) + listeningRouteSettleDelays(useBluetoothRouting)
        for ((attemptIndex, delayMs) in settleDelays.withIndex()) {
            delay(delayMs)
            val routeSnapshot = audioRouter.snapshot()
            RelayStateStore.setAudioRoute(routeSnapshot)
            val routeCheck = assessListeningRouteCheck(
                useBluetoothRouting = useBluetoothRouting,
                routeSnapshot = routeSnapshot,
                attemptIndex = attemptIndex,
                totalAttempts = settleDelays.size,
            )
            if (routeCheck.shouldStartListening) {
                return true
            }

            if (!routeCheck.shouldRetry) {
                val fallbackCheck = AudioRouteFallbackPolicy.resolve(
                    requestedRoute = routeSnapshot,
                    allowPhoneMicFallback = RelayStateStore.state.value.config.phoneMicFallback,
                )
                if (fallbackCheck.shouldClearRequestedRoute) {
                    audioRouter.clear()
                }
                RelayStateStore.setAudioRoute(fallbackCheck.routeSnapshot)
                if (fallbackCheck.decision == AudioRouteFallbackDecision.USE_PHONE_MIC_FALLBACK) {
                    return true
                }
                RelayStateStore.recordSpeechError(fallbackCheck.blockingMessage ?: routeCheck.errorMessage)
                return false
            }
        }

        return false
    }

    internal fun interruptImplementationAndListen(wakeSignal: RelayWakeSignal) {
        cancelPendingAutonomyContinuation()
        activeSpeechRecorder?.let { recorder ->
            recorder.markInterrupted(System.currentTimeMillis(), SpeechEndpointReason.CANCELLED)
            RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())
        }
        serviceScope.launch {
            speechInputEngine.stop(SpeechStopReason.CANCELLED)
        }
        val interruptionRequestedAtMs = System.currentTimeMillis()
        val interruptionRecorder = TtsInterruptionMetricsRecorder(
            interruptionId = "tts-interrupt-$interruptionRequestedAtMs",
            reason = TtsInterruptionReason.BARGE_IN,
            requestedAtMs = interruptionRequestedAtMs,
        )
        activeTtsInterruptionRecorder = interruptionRecorder
        RelayStateStore.recordTtsInterruptionMetrics(interruptionRecorder.snapshot())
        stopSpeechOutput(TtsStopReason.BARGE_IN)

        val routePrepared = prepareListeningRoute()
        if (!routePrepared) {
            return
        }

        val currentState = RelayStateStore.state.value
        val isRunningOrQueued = currentState.pendingActionId != null && currentState.pendingApprovalRequest == null
        if (isRunningOrQueued) {
            val nowMs = System.currentTimeMillis()
            val sessionId = currentState.config.sessionId
            sendBridgeEvent(
                RelayBridgeEvent(
                    sessionId = sessionId,
                    workspace = currentState.config.workspace,
                    event = wakeSignal.trigger,
                    timestamp = nowMs,
                    pendingActionId = currentState.pendingActionId,
                    hardwareContext = wakeSignal.hardwareContext
                        ?: currentState.lastWakeSignal?.hardwareContext,
                    idempotencyKey = "$sessionId-${wakeSignal.trigger}-${currentState.pendingActionId ?: "none"}-$nowMs",
                ),
                onSpeechComplete = { startAutonomyInterruptListeningSession(wakeSignal, routeAlreadyPrepared = true) },
            )
        } else {
            sendBridgeEvent(
                RelayBridgeEvent(
                    sessionId = currentState.config.sessionId,
                    workspace = currentState.config.workspace,
                    event = wakeSignal.trigger,
                    timestamp = System.currentTimeMillis(),
                    hardwareContext = wakeSignal.hardwareContext,
                    idempotencyKey = "${currentState.config.sessionId}-${wakeSignal.trigger}-interrupt-${System.currentTimeMillis()}",
                ),
                onSpeechComplete = { startAutonomyInterruptListeningSession(wakeSignal, routeAlreadyPrepared = true) },
            )
        }
    }

    private fun speakText(text: String, onComplete: (() -> Unit)? = null, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        activeSpeechRecorder?.markTtsRequested(System.currentTimeMillis())
        serviceScope.launch {
            speechOutputEngine.speak(
                request = TtsRequest(
                    utteranceId = "relay-tts-${System.currentTimeMillis()}",
                    text = text,
                    queueMode = queueMode,
                ),
                callbacks = TtsCallbacks(
                    onComplete = { onComplete?.invoke() },
                    onError = RelayStateStore::recordTtsError,
                ),
            )
        }
    }

    private fun stopSpeechOutput(reason: TtsStopReason) {
        serviceScope.launch {
            speechOutputEngine.stop(reason)
        }
    }

    private fun prepareListeningRoute(): Boolean {
        maybePromoteForegroundForListening()

        if (!RelayStateStore.state.value.config.useBluetoothRouting) {
            return true
        }

        val routeSnapshot = audioRouter.routeCommunicationAudio()
        val routeCheck = AudioRouteFallbackPolicy.resolve(
            requestedRoute = routeSnapshot,
            allowPhoneMicFallback = RelayStateStore.state.value.config.phoneMicFallback,
            allowRouteSettle = true,
        )
        if (routeCheck.shouldClearRequestedRoute) {
            audioRouter.clear()
        }
        RelayStateStore.setAudioRoute(routeCheck.routeSnapshot)
        if (routeCheck.decision == AudioRouteFallbackDecision.BLOCK_LISTENING) {
            RelayStateStore.recordSpeechError(routeCheck.blockingMessage ?: "Bluetooth routing failed. Check the connected audio device.")
            return false
        }

        return true
    }

    private fun buildGestureBridgeEvent(wakeSignal: RelayWakeSignal): RelayBridgeEvent {
        val currentConfig = RelayStateStore.state.value.config
        val deviceState = RelayStateStore.state.value.currentDeviceState
        val nowMs = System.currentTimeMillis()
        val pendingActionId = RelayStateStore.state.value.pendingActionId
        return RelayBridgeEvent(
            sessionId = currentConfig.sessionId,
            workspace = currentConfig.workspace,
            event = wakeSignal.trigger,
            timestamp = nowMs,
            pendingActionId = pendingActionId,
            hardwareContext = wakeSignal.hardwareContext
                ?: deviceState?.toHardwareContext(),
            protocolVersion = RELAY_PROTOCOL_VERSION,
            idempotencyKey = "${currentConfig.sessionId}-${wakeSignal.trigger}-${pendingActionId ?: "none"}-$nowMs",
        )
    }

    private fun maybePromoteForegroundForListening() {
        if (!speechInputEngine.capabilities().isAvailable) {
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        startRelayForeground(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
    }

    private fun startRelayForeground(foregroundServiceType: Int) {
        recordForegroundSnapshot(
            foregroundServiceType = foregroundServiceType,
            isActive = true,
            action = RelayStateStore.state.value.voiceDiagnostics.foregroundService.lastStartAction,
        )
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            foregroundServiceType,
        )
    }

    private fun recordForegroundSnapshot(
        foregroundServiceType: Int,
        isActive: Boolean,
        action: String?,
    ) {
        RelayStateStore.recordForegroundServiceSnapshot(
            RelayForegroundServiceSnapshot(
                isForegroundActive = isActive,
                foregroundServiceTypeMask = foregroundServiceType,
                mediaSessionReady = ::signalProviderRegistry.isInitialized &&
                    signalProviderRegistry.getMediaSessionProvider().mediaSession() != null,
                notificationControls = ForegroundControlSnapshot.requiredRelayControls(),
                lastStartAction = action,
                updatedAtMs = System.currentTimeMillis(),
                restoredAfterRestart = RelayStateStore.state.value.voiceDiagnostics.foregroundService.restoredAfterRestart,
                recoveryReason = RelayStateStore.state.value.voiceDiagnostics.foregroundService.recoveryReason,
            )
        )
    }

    private fun runTapTest() {
        cancelPendingAutonomyContinuation()
        val currentConfig = RelayStateStore.state.value.config
        val nowMs = System.currentTimeMillis()
        RelayStateStore.setWakeSignal(RelayTapTestFactory.createWakeSignal())
        sendBridgeEvent(
            RelayBridgeEvent(
                sessionId = currentConfig.sessionId,
                workspace = currentConfig.workspace,
                event = RelayTapTestFactory.EVENT_NAME,
                timestamp = nowMs,
                idempotencyKey = "${currentConfig.sessionId}-${RelayTapTestFactory.EVENT_NAME}-tap_test-$nowMs",
            ),
        )
    }

    private fun runAudioRouteProbe() {
        serviceScope.launch {
            if (RelayStateStore.state.value.isListening) {
                RelayStateStore.setError("Stop the current listening session before running the microphone route probe.")
                return@launch
            }

            maybePromoteForegroundForListening()
            if (RelayStateStore.state.value.config.useBluetoothRouting) {
                prepareListeningRoute()
            }

            val probe = AudioRecordRouteProbe(
                context = this@RelayService,
                routeSnapshotProvider = { audioRouter.snapshot() },
            )
            val metrics = probe.runProbe()
            RelayStateStore.recordAudioProbeMetrics(metrics)
            if (metrics.initStatus == AudioProbeInitStatus.STARTED) {
                RelayStateStore.clearError()
            } else {
                RelayStateStore.setError(metrics.errorMessage ?: "Microphone route probe failed.")
            }
            RelayStateStore.setAudioRoute(audioRouter.snapshot())
        }
    }

    private fun shouldRefreshAudioRoute(action: String): Boolean {
        return action in setOf(
            ACTION_START_RELAY,
            ACTION_CHECK_HEALTH,
            ACTION_QUICK_STATUS,
            ACTION_ASSIST_LONG_PRESS,
            ACTION_WAKE_AND_LISTEN,
            ACTION_TEST_SPEAKER,
            ACTION_TAP_TEST,
        )
    }

    private fun handleAssistantLongPress(sourceAction: String?) {
        val currentState = RelayStateStore.state.value
        val hasBackgroundImplementation = currentState.pendingActionId != null && currentState.pendingApprovalRequest == null
        if (hasBackgroundImplementation || currentState.activeAutonomy != null) {
            val wakeSignal = RelayWakeSignal(
                trigger = "android_autonomy_interrupt",
                source = sourceAction ?: ACTION_VOICE_ASSIST,
                sourceLabel = "Assistant long press",
                provider = AssistantEntrySignalProvider.observe(),
            )
            RelayStateStore.setWakeSignal(wakeSignal)
            interruptImplementationAndListen(wakeSignal)
            return
        }

        cancelPendingAutonomyContinuation()
        val currentConfig = currentState.config
        val nowMs = System.currentTimeMillis()
        RelayStateStore.setWakeSignal(
            RelayWakeSignal(
                trigger = "left_long_press",
                source = sourceAction ?: ACTION_VOICE_ASSIST,
                sourceLabel = "Assistant long press",
                provider = AssistantEntrySignalProvider.observe(),
            ),
        )
        RelayStateStore.markAwaitingBridgeResponse(false)
        RelayStateStore.setError("")
        sendBridgeEvent(
            RelayBridgeEvent(
                sessionId = currentConfig.sessionId,
                workspace = currentConfig.workspace,
                event = "left_long_press",
                timestamp = nowMs,
                idempotencyKey = "${currentConfig.sessionId}-left_long_press-assist-$nowMs",
            ),
        )
    }

    private fun sendLearningPromptEvent(eventName: String, prompt: BridgeOutboxEvent) {
        RelayStateStore.clearLearningPrompt()
        learningPromptHandler.removeCallbacksAndMessages(prompt.id)
        cancelPendingAutonomyContinuation()
        val detail = prompt.detail
        if (detail.isNullOrBlank()) {
            Log.w(TAG, "Learning prompt detail missing for event=$eventName")
            return
        }
        val json = JSONObject(detail)
        val phrase = json.optString("phrase", "")
        val intent = json.optString("intent", "")
        if (phrase.isBlank() || intent.isBlank()) {
            Log.w(TAG, "Learning prompt phrase or intent missing for event=$eventName")
            return
        }
        val currentState = RelayStateStore.state.value
        val nowMs = System.currentTimeMillis()
        val sessionId = currentState.config.sessionId
        sendBridgeEvent(
            RelayBridgeEvent(
                sessionId = sessionId,
                workspace = currentState.config.workspace,
                event = eventName,
                timestamp = nowMs,
                utterance = phrase,
                pendingActionId = "intent:$intent",
                idempotencyKey = "$sessionId-$eventName-$intent-$nowMs",
            ),
        )
    }

    private fun sendApprovalEvent(eventName: String, forcedActionId: String? = null) {
        dismissApprovalNotification()
        if (RelayStateStore.isPendingApprovalExpired()) {
            RelayStateStore.clearPendingAction()
            RelayStateStore.setError("Approval request has expired. Issue the command again to receive a new approval prompt.")
            Log.w(TAG, "approval skipped: expired for event=$eventName")
            return
        }
        cancelPendingAutonomyContinuation()
        val currentState = RelayStateStore.state.value
        val pendingActionId = forcedActionId ?: currentState.pendingActionId
        if (pendingActionId.isNullOrBlank()) {
            RelayStateStore.setError("No pending approval is available.")
            Log.w(TAG, "approval skipped: no pending action for event=$eventName")
            return
        }

        val nowMs = System.currentTimeMillis()
        val sessionId = currentState.config.sessionId
        sendBridgeEvent(
            RelayBridgeEvent(
                sessionId = sessionId,
                workspace = currentState.config.workspace,
                event = eventName,
                timestamp = nowMs,
                pendingActionId = pendingActionId,
                idempotencyKey = "$sessionId-$eventName-$pendingActionId-$nowMs",
            ),
        )
    }

    private fun sendBridgeEvent(event: RelayBridgeEvent) {
        sendBridgeEvent(event, onSpeechComplete = null)
    }

    private fun sendBridgeEvent(
        event: RelayBridgeEvent,
        onSpeechComplete: (() -> Unit)? = null,
    ) {
        if (event.event != "android_autonomy_continue") {
            cancelPendingAutonomyContinuation()
        }

        val config = RelayStateStore.state.value.config
        val health = RelayStateStore.state.value.lastBridgeHealth
        val streamingEligible = config.eventStreamingEnabled &&
            health != null &&
            health.features.contains("event_streaming") &&
            isEventSafeForStreaming(event)

        if (streamingEligible) {
            sendBridgeEventStreaming(event, onSpeechComplete)
        } else {
            sendBridgeEventLegacy(event, onSpeechComplete)
        }
    }

    private fun isEventSafeForStreaming(event: RelayBridgeEvent): Boolean {
        // Approval, interrupt, and destructive intent events must use the reliable /events path.
        return when {
            event.event.contains("approval") -> false
            event.event.contains("approve") -> false
            event.event.contains("reject") -> false
            event.event.contains("cancel") -> false
            event.event.contains("interrupt") -> false
            event.event.contains("learning") -> false
            else -> true
        }
    }

    private fun sendBridgeEventLegacy(
        event: RelayBridgeEvent,
        onSpeechComplete: (() -> Unit)? = null,
    ) {
        val config = RelayStateStore.state.value.config
        RelayStateStore.markAwaitingBridgeResponse(true)
        serviceScope.launch {
            val requestSentAtMs = System.currentTimeMillis()
            activeSpeechRecorder?.markBridgeRequestSent(requestSentAtMs)
            bridgeClient.sendEvent(config, event)
                .onSuccess { result ->
                    handleBridgeResponse(result.value, result.durationMs, onSpeechComplete)
                }
                .onFailure { error ->
                    handleBridgeFailure(event, onSpeechComplete, error)
                }
        }
    }

    private fun sendBridgeEventStreaming(
        event: RelayBridgeEvent,
        onSpeechComplete: (() -> Unit)? = null,
    ) {
        val config = RelayStateStore.state.value.config
        RelayStateStore.markAwaitingBridgeResponse(true)
        serviceScope.launch {
            val requestSentAtMs = System.currentTimeMillis()
            activeSpeechRecorder?.markBridgeRequestSent(requestSentAtMs)

            var hasSpokenDelta = false
            var accumulatedDeltaText = StringBuilder()
            var finalResponse: BridgeJarvisResponse? = null
            var streamFailed = false

            bridgeClient.sendEventStreaming(config, event) { frame ->
                when (frame) {
                    is StreamSpeakDeltaFrame -> {
                        val queueMode = if (hasSpokenDelta) {
                            android.speech.tts.TextToSpeech.QUEUE_ADD
                        } else {
                            android.speech.tts.TextToSpeech.QUEUE_FLUSH
                        }
                        hasSpokenDelta = true
                        accumulatedDeltaText.append(frame.delta)
                        speakText(frame.delta, queueMode = queueMode)
                    }
                    is StreamFinalResponseFrame -> {
                        finalResponse = frame.response
                    }
                    is StreamErrorFrame -> {
                        Log.w(TAG, "Stream error: ${frame.error} category=${frame.category}")
                        streamFailed = true
                    }
                    else -> { }
                }
            }.onSuccess { result ->
                val response = finalResponse
                if (streamFailed || response == null) {
                    // Fallback to legacy /events on stream error or missing final response
                    Log.w(TAG, "Streaming failed or missing final response; falling back to /events for event=${event.event}")
                    sendBridgeEventLegacy(event, onSpeechComplete)
                } else {
                    val responseReceivedAtMs = System.currentTimeMillis()
                    activeSpeechRecorder?.markBridgeResponseReceived(responseReceivedAtMs)
                    // R4: Reconcile final response speech with already-streamed deltas
                    val alreadySpoken = accumulatedDeltaText.toString().trim()
                    handleBridgeResponse(response, result.durationMs, onSpeechComplete, alreadySpoken = alreadySpoken)
                }
            }.onFailure { error ->
                Log.w(TAG, "Streaming request failed: ${error.message}; falling back to /events for event=${event.event}")
                sendBridgeEventLegacy(event, onSpeechComplete)
            }
        }
    }

    private fun handleBridgeResponse(
        response: BridgeJarvisResponse,
        durationMs: Long,
        onSpeechComplete: (() -> Unit)? = null,
        alreadySpoken: String = "",
    ) {
        RelayStateStore.recordResponse(response, durationMs)
        if (response.actionId == null && !response.requiresApproval) {
            RelayStateStore.clearPendingAction()
        }
        if (!shouldScheduleAutonomyContinue(response.autonomy)) {
            RelayStateStore.clearAutonomy()
        }
        // R4: Skip speaking if streaming deltas already delivered this exact text
        val speakText = response.speak.trim()
        val skipSpeak = alreadySpoken.isNotBlank() && speakText == alreadySpoken
        if (speakText.isNotBlank() && !skipSpeak) {
            RelayStateStore.markSpeechStarted(System.currentTimeMillis())
            speakText(response.speak, onComplete = {
                onSpeechComplete?.invoke()
                if (onSpeechComplete == null) {
                    scheduleAutonomyContinuation(response)
                }
            })
        } else {
            if (skipSpeak) {
                Log.d(TAG, "Skipping final speak because streaming deltas already delivered: $speakText")
            }
            onSpeechComplete?.invoke()
            if (onSpeechComplete == null) {
                scheduleAutonomyContinuation(response)
            }
        }
        Log.i(
            TAG,
            "event handled status=${response.status} actionId=${response.actionId ?: "none"} approval=${response.requiresApproval} durationMs=${durationMs} speak=${response.speak}",
        )
    }

    private fun handleBridgeFailure(
        event: RelayBridgeEvent,
        onSpeechComplete: (() -> Unit)?,
        error: Throwable,
    ) {
        RelayStateStore.markAwaitingBridgeResponse(false)
        RelayStateStore.setError(error.message ?: "Bridge request failed")
        queueBridgeEvent(PendingBridgeEvent(event, onSpeechComplete))
        scheduleBridgeRetry()
        Log.e(TAG, "event=${event.event} failure: ${error.message}", error)
    }

    private fun sendBridgeEventFireAndForget(event: RelayBridgeEvent) {
        if (event.event != "android_autonomy_continue") {
            cancelPendingAutonomyContinuation()
        }
        val config = RelayStateStore.state.value.config
        serviceScope.launch {
            bridgeClient.sendEvent(config, event)
                .onSuccess { result ->
                    Log.i(TAG, "fire-and-forget event=${event.event} status=${result.value.status} durationMs=${result.durationMs}")
                }
                .onFailure { error ->
                    Log.w(TAG, "fire-and-forget event=${event.event} failure: ${error.message}")
                }
        }
    }

    private fun prefetchWorkspaceData(config: RelayConfig) {
        val workspace = config.workspace
        if (workspace.isBlank()) return
        serviceScope.launch {
            bridgeClient.prefetchWorkspace(
                config = config,
                workspaceId = workspace,
                kinds = listOf("workspace_status"),
                idempotencyKey = "prefetch-${System.currentTimeMillis()}",
            )
                .onSuccess { result ->
                    if (result.value) {
                        Log.d(TAG, "Prefetch workspace=$workspace accepted durationMs=${result.durationMs}")
                    } else {
                        Log.w(TAG, "Prefetch workspace=$workspace rejected (accepted=false) durationMs=${result.durationMs}")
                    }
                }
                .onFailure { error ->
                    Log.w(TAG, "Prefetch workspace=$workspace failure: ${error.message}")
                }
        }
    }

    private fun scheduleAutonomyContinuation(response: BridgeJarvisResponse) {
        val autonomy = response.autonomy
        if (!shouldScheduleAutonomyContinue(autonomy)) {
            return
        }

        cancelPendingAutonomyContinuation()
        val continueAfterMs = autonomy?.continueAfterMs ?: return
        val currentConfig = RelayStateStore.state.value.config
        RelayStateStore.setAutonomyUiState(
            AutonomyUiState(
                phase = autonomy.phase,
                nextStep = autonomy.nextStep,
                countdownMs = continueAfterMs.toLong(),
                canStop = true,
            )
        )
        val runnable = Runnable {
            pendingAutonomyContinuation = null
            RelayStateStore.setAutonomyUiState(AutonomyUiState())
            val nowMs = System.currentTimeMillis()
            sendBridgeEvent(
                RelayBridgeEvent(
                    sessionId = currentConfig.sessionId,
                    workspace = currentConfig.workspace,
                    event = "android_autonomy_continue",
                    timestamp = nowMs,
                    idempotencyKey = "${currentConfig.sessionId}-android_autonomy_continue-autonomy-$nowMs",
                ),
            )
        }
        pendingAutonomyContinuation = runnable
        autonomyHandler.postDelayed(runnable, continueAfterMs.toLong())
    }

    private fun cancelPendingAutonomyContinuation() {
        pendingAutonomyContinuation?.let { autonomyHandler.removeCallbacks(it) }
        pendingAutonomyContinuation = null
        RelayStateStore.setAutonomyUiState(AutonomyUiState())
    }

    private fun applyIntentConfig(intent: Intent?) {
        if (intent == null || (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            return
        }

        RelayStateStore.updateConfig { current ->
            current.copy(
                bridgeBaseUrl = intent.getStringExtra(EXTRA_BRIDGE_BASE_URL)?.takeIf { value -> value.isNotBlank() } ?: current.bridgeBaseUrl,
                relayToken = intent.getStringExtra(EXTRA_RELAY_TOKEN) ?: current.relayToken,
                workspace = intent.getStringExtra(EXTRA_WORKSPACE)?.takeIf { value -> value.isNotBlank() } ?: current.workspace,
            )
        }

        val config = RelayStateStore.state.value.config
        Log.i(
            TAG,
            "config bridgeBaseUrl=${config.bridgeBaseUrl} workspace=${config.workspace} tokenPresent=${config.relayToken.isNotBlank()}",
        )
    }

    private fun handleDebugEvent(intent: Intent?) {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            Log.w(TAG, "debug event ignored outside debug builds")
            return
        }

        val eventName = intent?.getStringExtra(EXTRA_EVENT_NAME)?.takeIf { value -> value.isNotBlank() }
        if (eventName == null) {
            RelayStateStore.setError("Debug event name is required.")
            Log.w(TAG, "debug event missing event name")
            return
        }

        RelayStateStore.setWakeSignal(
            RelayWakeSignal(
                trigger = eventName,
                source = "debug_injection",
                sourceLabel = "Debug automation",
                provider = DebugAutomationSignalProvider.observe(),
            ),
        )
        val nowMs = System.currentTimeMillis()
        val sessionId = RelayStateStore.state.value.config.sessionId
        val pendingActionId = intent.getStringExtra(EXTRA_PENDING_ACTION_ID)
            ?: RelayStateStore.state.value.pendingActionId
        sendBridgeEvent(
            RelayBridgeEvent(
                sessionId = sessionId,
                workspace = RelayStateStore.state.value.config.workspace,
                event = eventName,
                timestamp = nowMs,
                utterance = intent.getStringExtra(EXTRA_UTTERANCE),
                pendingActionId = pendingActionId,
                idempotencyKey = "$sessionId-$eventName-${pendingActionId ?: "debug"}-$nowMs",
            ),
        )
    }

    private fun handleRunProof(intent: Intent?) {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            Log.w(TAG, "run proof ignored outside debug builds")
            return
        }
        serviceScope.launch {
            try {
                val extClass = Class.forName("com.openclaw.relay.RelayServiceDebugExt")
                val runMethod = extClass.getMethod("runProof", kotlinx.coroutines.CoroutineScope::class.java, RelayService::class.java, Intent::class.java)
                runMethod.invoke(null, serviceScope, this@RelayService, intent)
            } catch (e: ReflectiveOperationException) {
                Log.e(TAG, "RelayServiceDebugExt not available in release builds", e)
            } catch (e: Exception) {
                Log.e(TAG, "debug proof run failed", e)
                RelayStateStore.setError("Proof run failed: ${e.message}")
            }
        }
    }

    private fun handleExportProof() {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            Log.w(TAG, "export proof ignored outside debug builds")
            return
        }
        try {
            val extClass = Class.forName("com.openclaw.relay.RelayServiceDebugExt")
            val exportMethod = extClass.getMethod("exportProof", Context::class.java)
            exportMethod.invoke(null, this)
        } catch (e: ReflectiveOperationException) {
            Log.e(TAG, "RelayServiceDebugExt not available in release builds", e)
        }
    }

    private fun queueBridgeEvent(pending: PendingBridgeEvent) {
        pendingEventQueue.add(pending)
        if (pendingEventQueue.size > 20) {
            pendingEventQueue.removeAt(0)
        }
        RelayStateStore.setBridgeQueueState(
            BridgeQueueState(
                queuedCount = pendingEventQueue.size,
                retryAttempt = bridgeRetryAttempt,
            )
        )
    }

    private fun scheduleBridgeRetry() {
        bridgeRetryJob?.cancel()
        if (pendingEventQueue.isEmpty()) return
        bridgeRetryAttempt++
        val delayMs = (1000L * kotlin.math.min(bridgeRetryAttempt, 5)).coerceAtMost(30000L)
        RelayStateStore.setBridgeQueueState(
            BridgeQueueState(
                queuedCount = pendingEventQueue.size,
                retryAttempt = bridgeRetryAttempt,
                nextRetryMs = delayMs,
            )
        )
        bridgeRetryJob = serviceScope.launch {
            delay(delayMs)
            drainPendingEventQueue()
        }
    }

    private fun drainPendingEventQueue() {
        if (pendingEventQueue.isEmpty()) {
            RelayStateStore.setBridgeQueueState(BridgeQueueState())
            return
        }
        val pending = pendingEventQueue.removeAt(0)
        RelayStateStore.setBridgeQueueState(
            BridgeQueueState(
                queuedCount = pendingEventQueue.size,
                retryAttempt = bridgeRetryAttempt,
            )
        )
        sendBridgeEvent(pending.event, pending.onSpeechComplete)
    }

    private fun retryPendingBridgeQueue() {
        bridgeRetryJob?.cancel()
        bridgeRetryAttempt = 0
        drainPendingEventQueue()
    }

    private fun discardPendingBridgeQueue() {
        bridgeRetryJob?.cancel()
        bridgeRetryAttempt = 0
        pendingEventQueue.clear()
        RelayStateStore.markAwaitingBridgeResponse(false)
        RelayStateStore.setBridgeQueueState(BridgeQueueState())
        RelayStateStore.clearError()
    }

    private fun postSttErrorNotification(message: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Speech recognition failed")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_stat_devpods)
            .setLargeIcon(buildBrandLargeIcon())
            .setAutoCancel(true)
            .setContentIntent(buildMainActivityPendingIntent())
            .build()
        manager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun postApprovalNotification(event: BridgeOutboxEvent) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "openclaw-relay-approval"
        runCatching {
            val channel = NotificationChannel(
                channelId,
                "DevPods Approvals",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                enableVibration(true)
                vibrationPattern = if (event.priority == "critical") {
                    longArrayOf(0, 300, 200, 300, 200, 500)
                } else {
                    longArrayOf(0, 200, 100, 200)
                }
            }
            manager.createNotificationChannel(channel)
        }

        val detail = event.detail?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
        val riskClass = detail?.optString("riskClass") ?: "approval_required"
        val isHardApproval = riskClass == "hard_approval"
        val showSensitive = RelayStateStore.getNotificationPreference().showSensitiveInNotifications

        val approveIntent = buildServicePendingIntent(ACTION_APPROVE, 10) {
            putExtra(EXTRA_PENDING_ACTION_ID, event.actionId)
        }
        val rejectIntent = buildServicePendingIntent(ACTION_REJECT, 11) {
            putExtra(EXTRA_PENDING_ACTION_ID, event.actionId)
        }
        val cancelIntent = buildServicePendingIntent(ACTION_CANCEL, 12) {
            putExtra(EXTRA_PENDING_ACTION_ID, event.actionId)
        }

        val title = if (isHardApproval) "Hard approval required" else "Approval required"
        val summary = event.summary
        val publicSummary = if (showSensitive) summary else "DevPods action pending your approval"

        val publicVersion = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(publicSummary)
            .setSmallIcon(R.drawable.ic_stat_devpods)
            .setLargeIcon(buildBrandLargeIcon())
            .build()

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(summary)
            .setSmallIcon(R.drawable.ic_stat_devpods)
            .setLargeIcon(buildBrandLargeIcon())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(android.R.drawable.ic_menu_save, "Approve", approveIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Reject", rejectIntent)
            .addAction(android.R.drawable.ic_menu_revert, "Cancel", cancelIntent)
            .setContentIntent(buildMainActivityPendingIntent())
            .build()

        manager.notify(NOTIFICATION_ID + 2, notification)

        // Auto-dismiss when approval expires
        val expiresAtMs = event.expiresAtMs
        if (expiresAtMs > System.currentTimeMillis()) {
            val delayMs = expiresAtMs - System.currentTimeMillis()
            autonomyHandler.postDelayed({
                if (RelayStateStore.isPendingApprovalExpired()) {
                    dismissApprovalNotification()
                }
            }, delayMs)
        }
    }

    private fun dismissApprovalNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID + 2)
    }

    @Suppress("UnsafeOptInUsageError", "DEPRECATION")
    private fun buildNotification() = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setContentTitle(getString(R.string.relay_notification_title))
        .setContentText(getString(R.string.relay_notification_body))
        .setContentIntent(buildMainActivityPendingIntent())
        .setSmallIcon(R.drawable.ic_stat_devpods)
        .setLargeIcon(buildBrandLargeIcon())
        .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
        .setOngoing(true)
        .addAction(
            android.R.drawable.ic_btn_speak_now,
            "Talk",
            buildServicePendingIntent(ACTION_WAKE_AND_LISTEN, 1),
        )
        .addAction(
            android.R.drawable.ic_menu_revert,
            "Retry",
            buildServicePendingIntent(ACTION_RETRY_QUEUE, 2),
        )
        .addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Cancel",
            buildServicePendingIntent(ACTION_CANCEL, 3),
        )
        .addAction(
            android.R.drawable.ic_media_pause,
            "Stop",
            buildServicePendingIntent(ACTION_STOP_RELAY, 4),
        )
        .setStyle(
            MediaAppNotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1, 3)
                .setMediaSession(signalProviderRegistry.getMediaSessionProvider().mediaSession()?.sessionCompatToken),
        )
        .also {
            run {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(channel)
            }
        }
        .build()

    private fun buildBrandLargeIcon() = BitmapFactory.decodeResource(
        resources,
        R.drawable.devpods_notification_large,
    )

    private fun buildMainActivityPendingIntent(): PendingIntent {
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildServicePendingIntent(action: String, requestCode: Int, extras: (Intent.() -> Unit)? = null): PendingIntent {
        val intent = buildRelayServiceIntent(this, action)
        extras?.invoke(intent)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // --- Latency optimization: TTS warm keepalive (Workstream 2) ---

    private fun startTtsWarmupLoop() {
        if (ttsWarmupJob != null) return
        ttsWarmupJob = serviceScope.launch {
            while (true) {
                delay(180_000L) // 3 minutes
                val state = RelayStateStore.state.value
                if (!state.isListening && !state.isSpeaking && state.pendingApprovalRequest == null && state.ttsReady) {
                    val engine = speechOutputEngine
                    if (engine is WarmableSpeechOutputEngine) {
                        val warmed = engine.warm()
                        Log.d(TAG, "TTS warmup result: $warmed")
                    }
                }
            }
        }
    }

    private fun cancelTtsWarmupLoop() {
        ttsWarmupJob?.cancel()
        ttsWarmupJob = null
    }

    // --- Latency optimization: STT prewarm (Workstream 4) ---

    private fun prewarmSpeechRecognizer() {
        val config = RelayStateStore.state.value.config
        val request = SpeechSessionRequest(
            sessionId = "prewarm-${System.currentTimeMillis()}",
            onDeviceOnly = config.speechInputMode == SpeechInputMode.PLATFORM_ON_DEVICE,
        )
        val prepared = speechInputEngine.prepare(request)
        Log.d(TAG, "STT prewarm result: $prepared")
    }

    // --- Sherpa command-mode benchmark collection (P1-7) ---

    internal fun collectBenchmarkSample(
        expectedCommand: String,
        engine: CommandBenchmarkEngine,
        onCollected: (CommandBenchmarkSample) -> Unit,
    ) {
        val startedAtMs = System.currentTimeMillis()
        val sessionId = "benchmark-${startedAtMs}"

        serviceScope.launch {
            if (!listeningSessionMutex.tryLock()) {
                onCollected(
                    CommandBenchmarkSample(
                        command = expectedCommand,
                        engine = engine,
                        noSpeechDetected = true,
                    ),
                )
                return@launch
            }

            try {
                val recorder = SpeechSessionMetricsRecorder(
                    sessionId = sessionId,
                    engineId = speechInputEngine.id,
                    startedAtMs = startedAtMs,
                )
                activeSpeechRecorder = recorder
                RelayStateStore.setSpeechSessionState(SpeechSessionState.ROUTING)

                recorder.markRouteRequested(System.currentTimeMillis())
                if (!prepareListeningRoute()) {
                    onCollected(
                        CommandBenchmarkSample(
                            command = expectedCommand,
                            engine = engine,
                            noSpeechDetected = true,
                        ),
                    )
                    return@launch
                }
                recorder.markRouteReady(System.currentTimeMillis(), RelayStateStore.state.value.audioRoute)

                RelayStateStore.markListening(true)
                RelayStateStore.setSpeechSessionState(SpeechSessionState.LISTENING)

                val result = kotlinx.coroutines.CompletableDeferred<String?>()
                speechInputEngine.start(
                    request = SpeechSessionRequest(sessionId = sessionId),
                    callbacks = SpeechCallbacks(
                        onPartialTranscript = { partial ->
                            recorder.markPartial(System.currentTimeMillis(), partial)
                            RelayStateStore.setPartialTranscript(partial)
                        },
                        onFinalTranscript = { transcript ->
                            recorder.markFinal(System.currentTimeMillis(), transcript)
                            result.complete(transcript)
                        },
                        onError = { failure ->
                            recorder.markError(System.currentTimeMillis(), failure.errorCode, failure.endpointReason)
                            result.complete(null)
                        },
                        onRecognizerCreated = {
                            recorder.markRecognizerCreated(System.currentTimeMillis())
                        },
                        onListeningStarted = {
                            recorder.markListeningStarted(System.currentTimeMillis())
                        },
                        onReadyForSpeech = {
                            recorder.markReadyForSpeech(System.currentTimeMillis())
                        },
                        onBeginningOfSpeech = {
                            recorder.markBeginningOfSpeech(System.currentTimeMillis())
                        },
                        onRmsChanged = { rms ->
                            recorder.markRmsChanged(System.currentTimeMillis(), rms)
                        },
                        onEndOfSpeech = {
                            recorder.markEndOfSpeech(System.currentTimeMillis())
                        },
                    ),
                )

                val transcript = withTimeoutOrNull(LISTENING_SESSION_TIMEOUT_MS) {
                    result.await()
                }

                if (transcript == null) {
                    speechInputEngine.stop(SpeechStopReason.STOP_REQUESTED)
                }

                val snapshot = recorder.snapshot()
                val wakeToReadyMs = snapshot.routeReadyAtMs?.let { it - startedAtMs }
                val firstPartialMs = snapshot.firstPartialAtMs?.let { it - startedAtMs }
                val finalTranscriptMs = snapshot.finalAtMs?.let { it - startedAtMs }
                val endpointDelayMs = if (snapshot.finalAtMs != null && snapshot.endSpeechAtMs != null) {
                    snapshot.finalAtMs - snapshot.endSpeechAtMs
                } else null

                val transcriptAccuracy = if (!transcript.isNullOrBlank()) {
                    computeTranscriptAccuracy(transcript.lowercase(), expectedCommand.lowercase())
                } else null

                RelayStateStore.markListening(false)
                RelayStateStore.setSpeechSessionState(SpeechSessionState.IDLE)

                onCollected(
                    CommandBenchmarkSample(
                        command = expectedCommand,
                        engine = engine,
                        wakeToReadyMs = wakeToReadyMs,
                        firstPartialMs = firstPartialMs,
                        finalTranscriptMs = finalTranscriptMs,
                        transcriptAccuracy = transcriptAccuracy,
                        endpointDelayMs = endpointDelayMs,
                        noSpeechDetected = transcript.isNullOrBlank(),
                        transcriptText = transcript,
                        actualEngineId = speechInputEngine.id,
                    ),
                )
            } finally {
                listeningSessionMutex.unlock()
                activeSpeechRecorder = null
            }
        }
    }

    private fun computeTranscriptAccuracy(actual: String, expected: String): Float {
        if (expected.isBlank()) return 0f
        val actualWords = actual.split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        val expectedWords = expected.split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        if (expectedWords.isEmpty()) return 0f
        val matchCount = expectedWords.count { it in actualWords }
        return matchCount.toFloat() / expectedWords.size.toFloat()
    }

}
