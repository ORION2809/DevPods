package com.openclaw.relay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RelayService : Service() {
    companion object {
        private const val TAG = "OpenClawRelay"

        private const val NOTIFICATION_CHANNEL_ID = "openclaw-relay"
        private const val NOTIFICATION_ID = 41

        const val ACTION_START_RELAY = "com.openclaw.relay.action.START_RELAY"
        const val ACTION_STOP_RELAY = "com.openclaw.relay.action.STOP_RELAY"
        const val ACTION_CHECK_HEALTH = "com.openclaw.relay.action.CHECK_HEALTH"
        const val ACTION_WAKE_AND_LISTEN = "com.openclaw.relay.action.WAKE_AND_LISTEN"
        const val ACTION_QUICK_STATUS = "com.openclaw.relay.action.QUICK_STATUS"
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
            return Intent(context, RelayService::class.java).setAction(action)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bridgeClient = BridgeClient()

    private lateinit var speechRecognizer: AndroidSpeechRecognizer
    private lateinit var ttsSpeaker: AndroidTtsSpeaker
    private lateinit var audioRouter: BluetoothAudioRouter
    private lateinit var mediaSessionController: RelayMediaSessionController

    override fun onCreate() {
        super.onCreate()
        speechRecognizer = AndroidSpeechRecognizer(this)
        ttsSpeaker = AndroidTtsSpeaker(this, RelayStateStore::setError)
        audioRouter = BluetoothAudioRouter(this)
        mediaSessionController = RelayMediaSessionController(this) {
            RelayStateStore.setLastHeadsetEvent(it)
            beginListening(it)
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        RelayStateStore.markServiceRunning(true)
        checkBridgeHealth()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        applyIntentConfig(intent)
        Log.i(
            TAG,
            "service onStartCommand action=${intent?.action ?: ACTION_START_RELAY} event=${intent?.getStringExtra(EXTRA_EVENT_NAME) ?: "none"}",
        )

        when (intent?.action ?: ACTION_START_RELAY) {
            ACTION_START_RELAY -> checkBridgeHealth()
            ACTION_STOP_RELAY -> stopSelf()
            ACTION_CHECK_HEALTH -> checkBridgeHealth()
            ACTION_WAKE_AND_LISTEN -> beginListening(intent?.getStringExtra(EXTRA_TRIGGER) ?: "android_push_to_talk")
            ACTION_QUICK_STATUS -> sendBridgeEvent(
                event = RelayBridgeEvent(
                    sessionId = RelayStateStore.state.value.config.sessionId,
                    workspace = RelayStateStore.state.value.config.workspace,
                    event = "android_status_shortcut",
                    timestamp = System.currentTimeMillis(),
                ),
            )

            ACTION_APPROVE -> sendApprovalEvent("android_approve", intent?.getStringExtra(EXTRA_PENDING_ACTION_ID))
            ACTION_REJECT -> sendApprovalEvent("android_reject", intent?.getStringExtra(EXTRA_PENDING_ACTION_ID))
            ACTION_CANCEL -> sendApprovalEvent("android_cancel", intent?.getStringExtra(EXTRA_PENDING_ACTION_ID))
            ACTION_DEBUG_EVENT -> handleDebugEvent(intent)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        RelayStateStore.markServiceRunning(false)
        RelayStateStore.markListening(false)
        RelayStateStore.clearPendingAction()
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

    private fun beginListening(trigger: String) {
        RelayStateStore.setLastHeadsetEvent(trigger)
        RelayStateStore.markListening(true)
        RelayStateStore.setPartialTranscript("")
        RelayStateStore.setError("")

        if (RelayStateStore.state.value.config.useBluetoothRouting) {
            if (!audioRouter.routeCommunicationAudio()) {
                RelayStateStore.setError("Bluetooth routing failed. Check the connected audio device.")
            }
        }

        speechRecognizer.startListening(
            onPartialTranscript = { partial -> RelayStateStore.setPartialTranscript(partial) },
            onFinalTranscript = { transcript ->
                RelayStateStore.markListening(false)
                RelayStateStore.setTranscript(transcript)
                sendBridgeEvent(
                    RelayBridgeEvent(
                        sessionId = RelayStateStore.state.value.config.sessionId,
                        workspace = RelayStateStore.state.value.config.workspace,
                        event = trigger,
                        timestamp = System.currentTimeMillis(),
                        utterance = transcript,
                    ),
                )
            },
            onError = { error ->
                RelayStateStore.markListening(false)
                RelayStateStore.setError(error)
            },
        )
    }

    private fun sendApprovalEvent(eventName: String, explicitPendingActionId: String?) {
        val currentState = RelayStateStore.state.value
        val pendingActionId = explicitPendingActionId ?: currentState.pendingActionId
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
        val config = RelayStateStore.state.value.config
        serviceScope.launch {
            bridgeClient.sendEvent(config, event)
                .onSuccess { result ->
                    RelayStateStore.recordResponse(result.value, result.durationMs)
                    if (result.value.actionId == null && !result.value.requiresApproval) {
                        RelayStateStore.clearPendingAction()
                    }
                    if (result.value.speak.isNotBlank()) {
                        RelayStateStore.markSpeechStarted(System.currentTimeMillis())
                        ttsSpeaker.speak(result.value.speak)
                    }
                    Log.i(
                        TAG,
                        "event=${event.event} status=${result.value.status} actionId=${result.value.actionId ?: "none"} approval=${result.value.requiresApproval} durationMs=${result.durationMs} speak=${result.value.speak}",
                    )
                }
                .onFailure { error ->
                    RelayStateStore.setError(error.message ?: "Bridge request failed")
                    Log.e(TAG, "event=${event.event} failure: ${error.message}", error)
                }
        }
    }

    private fun applyIntentConfig(intent: Intent?) {
        if (intent == null) {
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

        RelayStateStore.setLastHeadsetEvent(eventName)
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
        .setSmallIcon(android.R.drawable.stat_sys_headset)
        .setOngoing(true)
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

}