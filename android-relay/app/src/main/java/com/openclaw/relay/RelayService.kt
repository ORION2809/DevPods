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
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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

    private lateinit var speechRecognizer: AndroidSpeechRecognizer
    private lateinit var ttsSpeaker: AndroidTtsSpeaker
    private lateinit var audioRouter: BluetoothAudioRouter
    private lateinit var mediaSessionController: RelayMediaSessionController

    override fun onCreate() {
        super.onCreate()
        speechRecognizer = AndroidSpeechRecognizer(this)
        RelayStateStore.setSpeechRecognitionAvailable(speechRecognizer.isRecognitionAvailable())
        RelayStateStore.setTtsReady(false)
        ttsSpeaker = AndroidTtsSpeaker(
            this,
            onError = RelayStateStore::recordTtsError,
            onReadyChanged = RelayStateStore::setTtsReady,
            onSpeakingChanged = RelayStateStore::markSpeaking,
        )
        audioRouter = BluetoothAudioRouter(this)
        RelayStateStore.setAudioRoute(audioRouter.snapshot())
        mediaSessionController = RelayMediaSessionController(this) {
            handleGestureSignal(it)
        }
        mediaSessionController.session().setSessionActivity(buildMainActivityPendingIntent())

        startRelayForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        RelayStateStore.markServiceRunning(true)
        checkBridgeHealth()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
                ttsSpeaker.speak("DevPods Relay is ready.")
            }

            ACTION_TAP_TEST -> runTapTest()

            ACTION_APPROVE -> sendApprovalEvent("android_approve")
            ACTION_REJECT -> sendApprovalEvent("android_reject")
            ACTION_CANCEL -> sendApprovalEvent("android_cancel")
            ACTION_DEBUG_EVENT -> handleDebugEvent(intent)
        }

        return START_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession {
        return mediaSessionController.session()
    }

    override fun onDestroy() {
        cancelPendingAutonomyContinuation()
        RelayStateStore.markServiceRunning(false)
        RelayStateStore.markListening(false)
        RelayStateStore.markAwaitingBridgeResponse(false)
        RelayStateStore.markSpeaking(false)
        RelayStateStore.clearPendingAction()
        RelayStateStore.clearAutonomy()
        speechRecognizer.destroy()
        ttsSpeaker.close()
        audioRouter.clear()
        mediaSessionController.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun checkBridgeHealth() {
        val config = RelayStateStore.state.value.config
        serviceScope.launch {
            bridgeClient.health(config)
                .onSuccess { result ->
                    RelayStateStore.recordHealth(result.value, result.durationMs)
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

        if (shouldInterruptImplementation(RelayStateStore.state.value, wakeSignal.trigger)) {
            interruptImplementationAndListen()
            return
        }

        if (!shouldOpenListeningWindow(wakeSignal.trigger)) {
            sendBridgeEvent(buildGestureBridgeEvent(wakeSignal))
            return
        }

        if (!prepareListeningRoute()) {
            return
        }

        sendBridgeEvent(
            event = buildGestureBridgeEvent(wakeSignal),
            onSpeechComplete = { startListeningSession(wakeSignal) },
        )
    }

    private fun startListeningSession(wakeSignal: RelayWakeSignal) {
        RelayStateStore.markListening(true)
        RelayStateStore.markAwaitingBridgeResponse(false)
        RelayStateStore.setPartialTranscript("")

        speechRecognizer.startListening(
            onPartialTranscript = { partial -> RelayStateStore.setPartialTranscript(partial) },
            onFinalTranscript = { transcript ->
                RelayStateStore.markListening(false)
                RelayStateStore.setTranscript(transcript)
                sendBridgeEvent(
                    RelayBridgeEvent(
                        sessionId = RelayStateStore.state.value.config.sessionId,
                        workspace = RelayStateStore.state.value.config.workspace,
                        event = wakeSignal.trigger,
                        timestamp = System.currentTimeMillis(),
                        utterance = transcript,
                    ),
                )
            },
            onError = { error ->
                RelayStateStore.recordSpeechError(error)
            },
        )
    }

    private fun startAutonomyInterruptListeningSession() {
        RelayStateStore.markListening(true)
        RelayStateStore.markAwaitingBridgeResponse(false)
        RelayStateStore.setPartialTranscript("")

        speechRecognizer.startListening(
            onPartialTranscript = { partial -> RelayStateStore.setPartialTranscript(partial) },
            onFinalTranscript = { transcript ->
                RelayStateStore.markListening(false)
                RelayStateStore.setTranscript(transcript)
                sendBridgeEvent(
                    RelayBridgeEvent(
                        sessionId = RelayStateStore.state.value.config.sessionId,
                        workspace = RelayStateStore.state.value.config.workspace,
                        event = "android_autonomy_interrupt",
                        timestamp = System.currentTimeMillis(),
                        utterance = transcript,
                    ),
                )
            },
            onError = { error ->
                RelayStateStore.recordSpeechError(error)
            },
        )
    }

    private fun interruptImplementationAndListen() {
        cancelPendingAutonomyContinuation()
        speechRecognizer.stopListening()
        ttsSpeaker.stop()

        if (!prepareListeningRoute()) {
            return
        }

        val currentState = RelayStateStore.state.value
        val isRunningOrQueued = currentState.pendingActionId != null && currentState.pendingApprovalSummary == null
        if (isRunningOrQueued) {
            sendBridgeEvent(
                RelayBridgeEvent(
                    sessionId = currentState.config.sessionId,
                    workspace = currentState.config.workspace,
                    event = "android_cancel",
                    timestamp = System.currentTimeMillis(),
                    pendingActionId = currentState.pendingActionId,
                ),
                onSpeechComplete = { startAutonomyInterruptListeningSession() },
            )
            return
        }

        RelayStateStore.clearAutonomy()
        RelayStateStore.markSpeechStarted(System.currentTimeMillis())
        ttsSpeaker.speak("Implementation paused. Tell me what to change.") {
            startAutonomyInterruptListeningSession()
        }
    }

    private fun prepareListeningRoute(): Boolean {
        maybePromoteForegroundForListening()

        if (!RelayStateStore.state.value.config.useBluetoothRouting) {
            return true
        }

        val routeSnapshot = audioRouter.routeCommunicationAudio()
        RelayStateStore.setAudioRoute(routeSnapshot)
        if (!routeSnapshot.isActive) {
            RelayStateStore.recordSpeechError("Bluetooth routing failed. Check the connected audio device.")
            return false
        }

        return true
    }

    private fun buildGestureBridgeEvent(wakeSignal: RelayWakeSignal): RelayBridgeEvent {
        val currentConfig = RelayStateStore.state.value.config
        return RelayBridgeEvent(
            sessionId = currentConfig.sessionId,
            workspace = currentConfig.workspace,
            event = wakeSignal.trigger,
            timestamp = System.currentTimeMillis(),
            pendingActionId = RelayStateStore.state.value.pendingActionId,
        )
    }

    private fun maybePromoteForegroundForListening() {
        if (!speechRecognizer.isRecognitionAvailable()) {
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
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            foregroundServiceType,
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
        val hasBackgroundImplementation = currentState.pendingActionId != null && currentState.pendingApprovalSummary == null
        if (hasBackgroundImplementation || currentState.activeAutonomy != null) {
            RelayStateStore.setWakeSignal(
                RelayWakeSignal(
                    trigger = "android_autonomy_interrupt",
                    source = sourceAction ?: ACTION_VOICE_ASSIST,
                    sourceLabel = "Assistant long press",
                ),
            )
            interruptImplementationAndListen()
            return
        }

        cancelPendingAutonomyContinuation()
        val currentConfig = currentState.config
        RelayStateStore.setWakeSignal(
            RelayWakeSignal(
                trigger = "left_long_press",
                source = sourceAction ?: ACTION_VOICE_ASSIST,
                sourceLabel = "Assistant long press",
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
                        ttsSpeaker.speak(result.value.speak) {
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
        val runnable = Runnable {
            pendingAutonomyContinuation = null
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

    private fun buildNotification() = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setContentTitle(getString(R.string.relay_notification_title))
        .setContentText(getString(R.string.relay_notification_body))
        .setContentIntent(buildMainActivityPendingIntent())
        .setSmallIcon(android.R.drawable.stat_sys_headset)
        .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
        .setOngoing(true)
        .setStyle(
            MediaAppNotificationCompat.MediaStyle()
                .setMediaSession(mediaSessionController.session().sessionCompatToken),
        )
        .also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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

}