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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import com.openclaw.relay.signal.toHardwareContext

class RelayService : MediaSessionService() {
    companion object {
        private const val TAG = "OpenClawRelay"

        private const val NOTIFICATION_CHANNEL_ID = "openclaw-relay"
        private const val NOTIFICATION_ID = 41

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

        const val EXTRA_TRIGGER = "trigger"
        const val EXTRA_SERVICE_ACTION = "relayAction"
        const val EXTRA_BRIDGE_BASE_URL = "bridgeBaseUrl"
        const val EXTRA_RELAY_TOKEN = "relayToken"
        const val EXTRA_WORKSPACE = "workspace"
        const val EXTRA_EVENT_NAME = "eventName"
        const val EXTRA_UTTERANCE = "utterance"
        const val EXTRA_PENDING_ACTION_ID = "pendingActionId"

        fun intent(context: Context, action: String): Intent {
            return buildRelayServiceIntent(context, action)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val autonomyHandler = Handler(Looper.getMainLooper())
    private val bridgeClient = BridgeClient()
    private var pendingAutonomyContinuation: Runnable? = null
    private val listeningSessionMutex = Mutex()
    private val pendingEventQueue = mutableListOf<PendingBridgeEvent>()
    private var bridgeRetryAttempt = 0
    private var bridgeRetryJob: kotlinx.coroutines.Job? = null
    private var interruptedWakeSignal: RelayWakeSignal? = null
    private var activeSpeechRecorder: SpeechSessionMetricsRecorder? = null
    private var activeTtsInterruptionRecorder: TtsInterruptionMetricsRecorder? = null

    private data class PendingBridgeEvent(
        val event: RelayBridgeEvent,
        val onSpeechComplete: (() -> Unit)?,
    )

    private lateinit var speechInputEngine: SpeechInputEngine
    private lateinit var speechOutputEngine: SpeechOutputEngine
    private lateinit var audioRouter: BluetoothAudioRouter
    private lateinit var signalProviderRegistry: com.openclaw.relay.signal.SignalProviderRegistry

    override fun onCreate() {
        super.onCreate()
        RelayStateStore.applyServiceRecoveryPlan(RelayServiceRecoveryPolicy.plan(RelayStateStore.state.value))
        speechInputEngine = SpeechInputEngineFactory.create(this, RelayStateStore.state.value.config)
        RelayStateStore.setSpeechRecognitionAvailable(speechInputEngine.capabilities().isAvailable)
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

        startRelayForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        RelayStateStore.markServiceRunning(true)
        checkBridgeHealth()
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

            ACTION_QUICK_STATUS -> sendBridgeEvent(
                event = RelayBridgeEvent(
                    sessionId = RelayStateStore.state.value.config.sessionId,
                    workspace = RelayStateStore.state.value.config.workspace,
                    event = "android_status_shortcut",
                    timestamp = System.currentTimeMillis(),
                ),
            )

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

            ACTION_APPROVE -> sendApprovalEvent("android_approve")
            ACTION_REJECT -> sendApprovalEvent("android_reject")
            ACTION_CANCEL -> sendApprovalEvent("android_cancel")
            ACTION_RETRY_QUEUE -> retryPendingBridgeQueue()
            ACTION_DISCARD_QUEUE -> discardPendingBridgeQueue()
            ACTION_AUDIO_ROUTE_PROBE -> runAudioRouteProbe()
            ACTION_DEBUG_EVENT -> handleDebugEvent(intent)
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
        speechInputEngine.destroy()
        speechOutputEngine.close()
        audioRouter.clear()
        if (::signalProviderRegistry.isInitialized) {
            signalProviderRegistry.stop()
        }
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

    private fun handleGestureSignal(wakeSignal: RelayWakeSignal) {
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

        if (!prepareListeningRoute()) {
            return
        }

        updateListenReadiness()

        val readiness = RelayStateStore.state.value.listenReadiness
        if (readiness == com.openclaw.relay.signal.ListenReadiness.BLOCKED) {
            val message = RelayStateStore.state.value.listenReadinessMessage
            RelayStateStore.setError(message)
            speakText(message)
            return
        }

        sendBridgeEvent(
            event = buildGestureBridgeEvent(wakeSignal),
            onSpeechComplete = { startListeningSession(wakeSignal) },
        )
    }

    private fun handleSignalEvent(event: com.openclaw.relay.signal.EarbudSignalEvent) {
        when (event) {
            is com.openclaw.relay.signal.EarbudSignalEvent.WakeGesture -> {
                val provider = signalProviderRegistry.getProvider(event.providerId)
                val trigger = normalizeProviderEventToBridgeTrigger(event.gestureType)
                val wakeSignal = RelayWakeSignal(
                    trigger = trigger,
                    source = event.providerId,
                    sourceLabel = provider?.providerLabel ?: event.providerId,
                    provider = com.openclaw.relay.RelayObservedSignalProvider(
                        providerId = event.providerId,
                        providerLabel = provider?.providerLabel ?: event.providerId,
                        confidence = com.openclaw.relay.RelaySignalConfidence.valueOf(event.confidence.name),
                        deviceLabel = event.deviceId,
                        isPhysicalInput = provider?.isPhysicalInput ?: true,
                    ),
                    hardwareContext = event.deviceId?.let {
                        com.openclaw.relay.signal.HardwareContext(
                            providerId = event.providerId,
                            wakeSource = "${event.budSide?.name?.lowercase() ?: "unknown"}_${event.gestureType.name.lowercase()}",
                            deviceConfidence = event.confidence.name.lowercase(),
                        )
                    },
                )
                handleGestureSignal(wakeSignal)
            }
            is com.openclaw.relay.signal.EarbudSignalEvent.InterruptGesture -> {
                val provider = signalProviderRegistry.getProvider(event.providerId)
                val trigger = normalizeProviderEventToBridgeTrigger(event.gestureType, isInterrupt = true)
                val wakeSignal = RelayWakeSignal(
                    trigger = trigger,
                    source = event.providerId,
                    sourceLabel = provider?.providerLabel ?: event.providerId,
                    provider = com.openclaw.relay.RelayObservedSignalProvider(
                        providerId = event.providerId,
                        providerLabel = provider?.providerLabel ?: event.providerId,
                        confidence = com.openclaw.relay.RelaySignalConfidence.valueOf(event.confidence.name),
                        deviceLabel = event.deviceId,
                        isPhysicalInput = provider?.isPhysicalInput ?: true,
                    ),
                    hardwareContext = event.deviceId?.let {
                        com.openclaw.relay.signal.HardwareContext(
                            providerId = event.providerId,
                            wakeSource = "${event.budSide?.name?.lowercase() ?: "unknown"}_${event.gestureType.name.lowercase()}",
                            deviceConfidence = event.confidence.name.lowercase(),
                        )
                    },
                )
                handleGestureSignal(wakeSignal)
            }
            is com.openclaw.relay.signal.EarbudSignalEvent.ApprovalGesture -> {
                if (RelayStateStore.isPendingApprovalExpired()) {
                    RelayStateStore.clearPendingAction()
                    Log.d(TAG, "Approval gesture ignored: approval expired")
                    return@handleSignalEvent
                }
                val currentState = RelayStateStore.state.value
                if (currentState.pendingApprovalRequest != null) {
                    val eventName = if (event.approved) "android_approve" else "android_reject"
                    sendApprovalEvent(eventName)
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
                updateListenReadiness()
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

    private fun startListeningSession(wakeSignal: RelayWakeSignal) {
        beginListeningSession { transcript ->
            sendBridgeEvent(
                RelayBridgeEvent(
                    sessionId = RelayStateStore.state.value.config.sessionId,
                    workspace = RelayStateStore.state.value.config.workspace,
                    event = wakeSignal.trigger,
                    timestamp = System.currentTimeMillis(),
                    utterance = transcript,
                    hardwareContext = wakeSignal.hardwareContext,
                ),
            )
        }
    }

    private fun startAutonomyInterruptListeningSession(wakeSignal: RelayWakeSignal) {
        beginListeningSession { transcript ->
            sendBridgeEvent(
                RelayBridgeEvent(
                    sessionId = RelayStateStore.state.value.config.sessionId,
                    workspace = RelayStateStore.state.value.config.workspace,
                    event = "android_autonomy_interrupt",
                    timestamp = System.currentTimeMillis(),
                    utterance = transcript,
                    hardwareContext = wakeSignal.hardwareContext
                        ?: RelayStateStore.state.value.lastWakeSignal?.hardwareContext,
                ),
            )
        }
    }

    private fun beginListeningSession(onFinalTranscript: (String) -> Unit) {
        serviceScope.launch {
            if (!listeningSessionMutex.tryLock()) {
                RelayStateStore.setError("A listening session is already active.")
                return@launch
            }

            try {
                val recorder = SpeechSessionMetricsRecorder(
                    sessionId = "speech-${System.currentTimeMillis()}",
                    engineId = speechInputEngine.id,
                    wakeSignal = RelayStateStore.state.value.lastWakeSignal?.trigger,
                    startedAtMs = System.currentTimeMillis(),
                )
                activeSpeechRecorder = recorder
                RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())

                if (RelayStateStore.state.value.isListening) {
                    RelayStateStore.setError("A listening session is already active.")
                    return@launch
                }

                RelayStateStore.clearListeningStartupErrors()

                recorder.markRouteRequested(System.currentTimeMillis())
                if (!prepareListeningRoute()) {
                    recorder.markError(
                        nowMs = System.currentTimeMillis(),
                        errorCode = null,
                        reason = SpeechEndpointReason.ROUTE_FAILED,
                    )
                    RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())
                    return@launch
                }
                recorder.markRouteReady(System.currentTimeMillis(), RelayStateStore.state.value.audioRoute)
                RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())

                if (!waitForListeningRouteReady()) {
                    recorder.markRouteReady(System.currentTimeMillis(), RelayStateStore.state.value.audioRoute)
                    recorder.markError(
                        nowMs = System.currentTimeMillis(),
                        errorCode = null,
                        reason = SpeechEndpointReason.ROUTE_FAILED,
                    )
                    RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())
                    return@launch
                }
                recorder.markRouteReady(System.currentTimeMillis(), RelayStateStore.state.value.audioRoute)
                RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())

                RelayStateStore.markAwaitingBridgeResponse(false)
                RelayStateStore.setPartialTranscript("")
                RelayStateStore.markListening(true)
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
                            recorder.markFinal(System.currentTimeMillis(), transcript)
                            RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())
                            RelayStateStore.markListening(false)
                            RelayStateStore.setTranscript(transcript)
                            onFinalTranscript(transcript)
                        },
                        onError = { failure ->
                            recorder.markError(
                                nowMs = System.currentTimeMillis(),
                                errorCode = failure.errorCode,
                                reason = failure.endpointReason,
                            )
                            RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())
                            RelayStateStore.setAudioRoute(audioRouter.snapshot())
                            RelayStateStore.recordSpeechError(failure.message)
                            postSttErrorNotification(failure.message)
                        },
                        onRecognizerCreated = {
                            recorder.markRecognizerCreated(System.currentTimeMillis())
                            RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())
                        },
                        onListeningStarted = {
                            val nowMs = System.currentTimeMillis()
                            recorder.markListeningStarted(nowMs)
                            activeTtsInterruptionRecorder?.let { interruptionRecorder ->
                                interruptionRecorder.markListeningStarted(nowMs)
                                RelayStateStore.recordTtsInterruptionMetrics(interruptionRecorder.snapshot())
                                activeTtsInterruptionRecorder = null
                            }
                            RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())
                        },
                        onReadyForSpeech = {
                            recorder.markReadyForSpeech(System.currentTimeMillis())
                            RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())
                        },
                        onBeginningOfSpeech = {
                            recorder.markBeginningOfSpeech(System.currentTimeMillis())
                            RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())
                        },
                        onRmsChanged = { rmsDb ->
                            recorder.markRmsChanged(System.currentTimeMillis(), rmsDb)
                            RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())
                        },
                        onEndOfSpeech = {
                            recorder.markEndOfSpeech(System.currentTimeMillis())
                            RelayStateStore.recordSpeechSessionMetrics(recorder.snapshot())
                        },
                    ),
                )
            } finally {
                listeningSessionMutex.unlock()
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

    private fun interruptImplementationAndListen(wakeSignal: RelayWakeSignal) {
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

        if (!prepareListeningRoute()) {
            return
        }

        val currentState = RelayStateStore.state.value
        val isRunningOrQueued = currentState.pendingActionId != null && currentState.pendingApprovalRequest == null
        if (isRunningOrQueued) {
            sendBridgeEvent(
                RelayBridgeEvent(
                    sessionId = currentState.config.sessionId,
                    workspace = currentState.config.workspace,
                    event = wakeSignal.trigger,
                    timestamp = System.currentTimeMillis(),
                    pendingActionId = currentState.pendingActionId,
                    hardwareContext = wakeSignal.hardwareContext
                        ?: currentState.lastWakeSignal?.hardwareContext,
                ),
                onSpeechComplete = { startAutonomyInterruptListeningSession(wakeSignal) },
            )
            return
        }

        RelayStateStore.clearAutonomy()
        RelayStateStore.markSpeechStarted(System.currentTimeMillis())
        speakText("Implementation paused. Tell me what to change.") {
            startAutonomyInterruptListeningSession(wakeSignal)
        }
    }

    private fun speakText(text: String, onComplete: (() -> Unit)? = null) {
        serviceScope.launch {
            speechOutputEngine.speak(
                request = TtsRequest(
                    utteranceId = "relay-tts-${System.currentTimeMillis()}",
                    text = text,
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
        return RelayBridgeEvent(
            sessionId = currentConfig.sessionId,
            workspace = currentConfig.workspace,
            event = wakeSignal.trigger,
            timestamp = System.currentTimeMillis(),
            pendingActionId = RelayStateStore.state.value.pendingActionId,
            hardwareContext = wakeSignal.hardwareContext
                ?: deviceState?.toHardwareContext(),
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
        RelayStateStore.setWakeSignal(RelayTapTestFactory.createWakeSignal())
        sendBridgeEvent(
            RelayBridgeEvent(
                sessionId = currentConfig.sessionId,
                workspace = currentConfig.workspace,
                event = RelayTapTestFactory.EVENT_NAME,
                timestamp = System.currentTimeMillis(),
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
                timestamp = System.currentTimeMillis(),
            ),
        )
    }

    private fun sendApprovalEvent(eventName: String) {
        if (RelayStateStore.isPendingApprovalExpired()) {
            RelayStateStore.clearPendingAction()
            RelayStateStore.setError("Approval request has expired. Issue the command again to receive a new approval prompt.")
            Log.w(TAG, "approval skipped: expired for event=$eventName")
            return
        }
        cancelPendingAutonomyContinuation()
        val currentState = RelayStateStore.state.value
        val pendingActionId = currentState.pendingActionId
        if (pendingActionId.isNullOrBlank()) {
            RelayStateStore.setError("No pending approval is available.")
            Log.w(TAG, "approval skipped: no pending action for event=$eventName")
            return
        }

        sendBridgeEvent(
            RelayBridgeEvent(
                sessionId = currentState.config.sessionId,
                workspace = currentState.config.workspace,
                event = eventName,
                timestamp = System.currentTimeMillis(),
                pendingActionId = pendingActionId,
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
        RelayStateStore.markAwaitingBridgeResponse(true)
        serviceScope.launch {
            bridgeClient.sendEvent(config, event)
                .onSuccess { result ->
                    RelayStateStore.recordResponse(result.value, result.durationMs)
                    if (result.value.actionId == null && !result.value.requiresApproval) {
                        RelayStateStore.clearPendingAction()
                    }
                    if (!shouldScheduleAutonomyContinue(result.value.autonomy)) {
                        RelayStateStore.clearAutonomy()
                    }
                    if (result.value.speak.isNotBlank()) {
                        RelayStateStore.markSpeechStarted(System.currentTimeMillis())
                        speakText(result.value.speak) {
                            onSpeechComplete?.invoke()
                            if (onSpeechComplete == null) {
                                scheduleAutonomyContinuation(result.value)
                            }
                        }
                    } else {
                        onSpeechComplete?.invoke()
                        if (onSpeechComplete == null) {
                            scheduleAutonomyContinuation(result.value)
                        }
                    }
                    Log.i(
                        TAG,
                        "event=${event.event} status=${result.value.status} actionId=${result.value.actionId ?: "none"} approval=${result.value.requiresApproval} durationMs=${result.durationMs} speak=${result.value.speak}",
                    )
                }
                .onFailure { error ->
                    RelayStateStore.markAwaitingBridgeResponse(false)
                    RelayStateStore.setError(error.message ?: "Bridge request failed")
                    queueBridgeEvent(PendingBridgeEvent(event, onSpeechComplete))
                    scheduleBridgeRetry()
                    Log.e(TAG, "event=${event.event} failure: ${error.message}", error)
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
            sendBridgeEvent(
                RelayBridgeEvent(
                    sessionId = currentConfig.sessionId,
                    workspace = currentConfig.workspace,
                    event = "android_autonomy_continue",
                    timestamp = System.currentTimeMillis(),
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
        sendBridgeEvent(
            RelayBridgeEvent(
                sessionId = RelayStateStore.state.value.config.sessionId,
                workspace = RelayStateStore.state.value.config.workspace,
                event = eventName,
                timestamp = System.currentTimeMillis(),
                utterance = intent.getStringExtra(EXTRA_UTTERANCE),
                pendingActionId = intent.getStringExtra(EXTRA_PENDING_ACTION_ID)
                    ?: RelayStateStore.state.value.pendingActionId,
            ),
        )
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
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setContentIntent(buildMainActivityPendingIntent())
            .build()
        manager.notify(NOTIFICATION_ID + 1, notification)
    }

    @Suppress("UnsafeOptInUsageError", "DEPRECATION")
    private fun buildNotification() = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setContentTitle(getString(R.string.relay_notification_title))
        .setContentText(getString(R.string.relay_notification_body))
        .setContentIntent(buildMainActivityPendingIntent())
        .setSmallIcon(android.R.drawable.stat_sys_headset)
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

    private fun buildServicePendingIntent(action: String, requestCode: Int): PendingIntent {
        return PendingIntent.getService(
            this,
            requestCode,
            buildRelayServiceIntent(this, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

}
