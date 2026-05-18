package com.openclaw.relay.signal

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import com.openclaw.relay.MediaButtonAction
import com.openclaw.relay.MediaButtonDiagnostics
import com.openclaw.relay.MediaButtonEventDebouncer
import com.openclaw.relay.RelayStateStore
import com.openclaw.relay.RelayService
import com.openclaw.relay.buildRelayServiceIntent
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.session.MediaSession
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.TimeUnit

private const val TAG = "AndroidMediaSessionProvider"
private val standbySilenceDurationUs = TimeUnit.MINUTES.toMicros(30)

class AndroidMediaSessionProvider(
    private val context: Context,
) : EarbudSignalProvider {
    override val providerId: String = "android_media_session"
    override val providerLabel: String = "Android media controls"
    override val isPhysicalInput: Boolean = true
    override val defaultConfidence: SignalConfidence = SignalConfidence.UNPROVEN

    private val _capabilityProfile = MutableStateFlow(
        EarbudCapabilityProfile(
            providerId = providerId,
            deviceModel = null,
            capabilities = listOf(
                Capability.WAKE_SINGLE_PRESS,
                Capability.WAKE_LONG_PRESS,
                Capability.INTERRUPT_DOUBLE_PRESS,
                Capability.APPROVE_DOUBLE_PRESS,
            ),
            wakeGestures = mapOf(
                GestureType.SINGLE_PRESS to CapabilityConfidence.SUPPORTED_BUT_NOT_OBSERVED,
                GestureType.LONG_PRESS to CapabilityConfidence.SUPPORTED_BUT_NOT_OBSERVED,
            ),
            interruptGestures = mapOf(GestureType.DOUBLE_PRESS to CapabilityConfidence.SUPPORTED_BUT_NOT_OBSERVED),
            approvalGestures = mapOf(GestureType.TRIPLE_PRESS to CapabilityConfidence.SUPPORTED_BUT_NOT_OBSERVED),
            supportsBatteryStatus = false,
            supportsEarDetection = false,
            supportsAudioRouteControl = false,
        )
    )
    override val capabilityProfile: StateFlow<EarbudCapabilityProfile> = _capabilityProfile.asStateFlow()

    private val _deviceState = MutableStateFlow<EarbudDeviceState?>(null)
    override val deviceState: StateFlow<EarbudDeviceState?> = _deviceState.asStateFlow()

    private val _events = Channel<EarbudSignalEvent>(Channel.BUFFERED)
    override val events: Flow<EarbudSignalEvent> = _events.receiveAsFlow()

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    @Volatile
    private var hasPrimedSession = false
    private val mediaButtonDebouncer = MediaButtonEventDebouncer()

    @Suppress("UnsafeOptInUsageError")
    fun mediaSession(): MediaSession? = mediaSession

    private val tapDetector = MediaButtonTapDetector { gestureType ->
        val event = when (gestureType) {
            GestureType.SINGLE_PRESS -> EarbudSignalEvent.WakeGesture(
                providerId = providerId,
                deviceId = null,
                gestureType = GestureType.SINGLE_PRESS,
                confidence = SignalConfidence.OBSERVED,
            )
            GestureType.DOUBLE_PRESS -> EarbudSignalEvent.InterruptGesture(
                providerId = providerId,
                deviceId = null,
                gestureType = GestureType.DOUBLE_PRESS,
                confidence = SignalConfidence.OBSERVED,
            )
            GestureType.TRIPLE_PRESS -> EarbudSignalEvent.ApprovalGesture(
                providerId = providerId,
                deviceId = null,
                approved = true,
                gestureType = GestureType.TRIPLE_PRESS,
            )
            GestureType.LONG_PRESS -> EarbudSignalEvent.WakeGesture(
                providerId = providerId,
                deviceId = null,
                gestureType = GestureType.LONG_PRESS,
                confidence = SignalConfidence.OBSERVED,
            )
            else -> EarbudSignalEvent.WakeGesture(
                providerId = providerId,
                deviceId = null,
                gestureType = gestureType,
                confidence = SignalConfidence.OBSERVED,
            )
        }
        Log.i(TAG, "Media button $gestureType")
        markGestureObserved(gestureType)
        _events.trySend(event)
    }

    private fun markGestureObserved(gestureType: GestureType) {
        val current = _capabilityProfile.value
        _capabilityProfile.value = current.copy(
            wakeGestures = current.wakeGestures.mapValues {
                if (it.key == gestureType && it.value == CapabilityConfidence.SUPPORTED_BUT_NOT_OBSERVED) {
                    CapabilityConfidence.PROVEN
                } else it.value
            },
            interruptGestures = current.interruptGestures.mapValues {
                if (it.key == gestureType && it.value == CapabilityConfidence.SUPPORTED_BUT_NOT_OBSERVED) {
                    CapabilityConfidence.PROVEN
                } else it.value
            },
            approvalGestures = current.approvalGestures.mapValues {
                if (it.key == gestureType && it.value == CapabilityConfidence.SUPPORTED_BUT_NOT_OBSERVED) {
                    CapabilityConfidence.PROVEN
                } else it.value
            },
        )
    }

    @Suppress("UnsafeOptInUsageError")
    override suspend fun start() {
        if (mediaSession != null) return

        val newPlayer = ExoPlayer.Builder(context).build().apply {
            volume = 0f
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!hasPrimedSession && isPlaying) {
                        hasPrimedSession = true
                        Log.i(TAG, "Media session primed for hardware buttons")
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    hasPrimedSession = true
                    Log.e(TAG, "Media session priming failed: ${error.message}", error)
                }
            })
            setMediaSource(SilenceMediaSource(standbySilenceDurationUs))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
            playWhenReady = true
        }

        val newSession = MediaSession.Builder(context, newPlayer)
            .setCallback(object : MediaSession.Callback {
                override fun onMediaButtonEvent(
                    session: MediaSession,
                    controllerInfo: MediaSession.ControllerInfo,
                    intent: Intent,
                ): Boolean {
                    val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    } ?: return super.onMediaButtonEvent(session, controllerInfo, intent)

                    val action = when (keyEvent.action) {
                        KeyEvent.ACTION_DOWN -> MediaButtonAction.DOWN
                        KeyEvent.ACTION_UP -> MediaButtonAction.UP
                        else -> MediaButtonAction.UNKNOWN
                    }
                    val receivedAtMs = System.currentTimeMillis()
                    val baseTelemetry = MediaButtonDiagnostics.normalize(
                        keyCode = keyEvent.keyCode,
                        action = action,
                        repeatCount = keyEvent.repeatCount,
                        receivedAtMs = receivedAtMs,
                        routeState = RelayStateStore.state.value.audioRoute.proof.routeState,
                        serviceRunning = RelayStateStore.state.value.isServiceRunning,
                    )

                    if (!isWakeMediaButtonKey(keyEvent.keyCode)) {
                        RelayStateStore.recordMediaButtonEvent(baseTelemetry.copy(accepted = false))
                        return super.onMediaButtonEvent(session, controllerInfo, intent)
                    }

                    when (keyEvent.action) {
                        KeyEvent.ACTION_DOWN -> {
                            if (keyEvent.repeatCount != 0) {
                                RelayStateStore.recordMediaButtonEvent(baseTelemetry.copy(accepted = false))
                                return true
                            }
                            if (!mediaButtonDebouncer.accepts(keyEvent.keyCode, action, receivedAtMs)) {
                                RelayStateStore.recordMediaButtonEvent(
                                    baseTelemetry.copy(
                                        accepted = false,
                                        debounced = true,
                                    )
                                )
                                return true
                            }
                            RelayStateStore.recordMediaButtonEvent(baseTelemetry)
                            when (keyEvent.keyCode) {
                                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                                    markGestureObserved(GestureType.DOUBLE_PRESS)
                                    _events.trySend(
                                        EarbudSignalEvent.InterruptGesture(
                                            providerId = providerId,
                                            deviceId = null,
                                            gestureType = GestureType.DOUBLE_PRESS,
                                            confidence = SignalConfidence.OBSERVED,
                                        )
                                    )
                                }
                                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                                    markGestureObserved(GestureType.TRIPLE_PRESS)
                                    _events.trySend(
                                        EarbudSignalEvent.ApprovalGesture(
                                            providerId = providerId,
                                            deviceId = null,
                                            approved = true,
                                            gestureType = GestureType.TRIPLE_PRESS,
                                        )
                                    )
                                }
                                KeyEvent.KEYCODE_MEDIA_STOP -> {
                                    context.startService(buildRelayServiceIntent(context, RelayService.ACTION_STOP_RELAY))
                                }
                                else -> {
                                    tapDetector.onButtonDown()
                                }
                            }
                        }
                        KeyEvent.ACTION_UP -> {
                            RelayStateStore.recordMediaButtonEvent(baseTelemetry)
                            when (keyEvent.keyCode) {
                                KeyEvent.KEYCODE_MEDIA_NEXT,
                                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                                    // Already handled on ACTION_DOWN; ignore UP.
                                }
                                KeyEvent.KEYCODE_MEDIA_STOP -> {
                                    // Already handled on ACTION_DOWN; ignore UP.
                                }
                                else -> {
                                    tapDetector.onButtonUp()
                                }
                            }
                        }
                    }
                    return true
                }
            })
            .build()

        player = newPlayer
        mediaSession = newSession

        _deviceState.value = EarbudDeviceState(
            providerId = providerId,
            deviceId = null,
            displayName = "Android media session",
            connectionState = ConnectionState.CONNECTED,
            battery = null,
            earState = null,
            audioRouteState = null,
            capabilityProfile = _capabilityProfile.value,
            confidence = SignalConfidence.PROVEN,
        )
    }

    override suspend fun stop() {
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        _deviceState.value = null
    }

    override suspend fun probe(): EarbudSignalProvider.ProbeResult {
        return EarbudSignalProvider.ProbeResult(
            success = mediaSession != null,
            detectedDevice = true,
            detectedGestures = listOf(
                EarbudSignalProvider.GestureDetected(
                    gestureType = GestureType.SINGLE_PRESS,
                    budSide = null,
                    confidence = SignalConfidence.OBSERVED,
                ),
                EarbudSignalProvider.GestureDetected(
                    gestureType = GestureType.DOUBLE_PRESS,
                    budSide = null,
                    confidence = SignalConfidence.OBSERVED,
                ),
                EarbudSignalProvider.GestureDetected(
                    gestureType = GestureType.TRIPLE_PRESS,
                    budSide = null,
                    confidence = SignalConfidence.OBSERVED,
                ),
                EarbudSignalProvider.GestureDetected(
                    gestureType = GestureType.LONG_PRESS,
                    budSide = null,
                    confidence = SignalConfidence.OBSERVED,
                ),
            ),
            message = if (mediaSession != null) "Media session is active and listening for headset buttons." else "Media session failed to start.",
        )
    }

    private fun isWakeMediaButtonKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_STOP,
            -> true
            else -> false
        }
    }
}
