package com.openclaw.relay.signal

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.KeyEvent
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

                    if (!isWakeMediaButtonKey(keyEvent.keyCode)) {
                        return super.onMediaButtonEvent(session, controllerInfo, intent)
                    }

                    when (keyEvent.action) {
                        KeyEvent.ACTION_DOWN -> {
                            if (keyEvent.repeatCount != 0) {
                                return true
                            }
                            when (keyEvent.keyCode) {
                                KeyEvent.KEYCODE_MEDIA_NEXT -> {
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
                                    _events.trySend(
                                        EarbudSignalEvent.ApprovalGesture(
                                            providerId = providerId,
                                            deviceId = null,
                                            approved = true,
                                            gestureType = GestureType.TRIPLE_PRESS,
                                        )
                                    )
                                }
                                else -> {
                                    tapDetector.onButtonDown()
                                }
                            }
                        }
                        KeyEvent.ACTION_UP -> {
                            when (keyEvent.keyCode) {
                                KeyEvent.KEYCODE_MEDIA_NEXT,
                                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
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
            -> true
            else -> false
        }
    }
}
