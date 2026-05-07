package com.openclaw.relay

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.session.MediaSession
import java.util.concurrent.TimeUnit

private const val TAG = "OpenClawRelay"
private val standbySilenceDurationUs = TimeUnit.MINUTES.toMicros(30)

internal fun isWakeMediaButtonKey(keyCode: Int): Boolean {
    return when (keyCode) {
        KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        -> true
        else -> false
    }
}

internal fun createWakeSignalForMediaButton(
    action: Int,
    keyCode: Int,
    controllerPackage: String?,
): RelayWakeSignal? {
    if (action != KeyEvent.ACTION_DOWN) {
        return null
    }

    val keyLabel = when (keyCode) {
        KeyEvent.KEYCODE_HEADSETHOOK -> "Headset hook"
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "Play or pause"
        KeyEvent.KEYCODE_MEDIA_PLAY -> "Play"
        KeyEvent.KEYCODE_MEDIA_NEXT -> "Next"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "Previous"
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "Pause"
        else -> return null
    }

    val trigger = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_NEXT -> "triple_tap_right"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        -> "left_long_press"
        else -> "headset_button_single"
    }

    return RelayWakeSignal(
        trigger = trigger,
        source = "physical_media_button",
        sourceLabel = "Physical headset media button",
        keyLabel = keyLabel,
        controllerPackage = controllerPackage?.takeIf { value -> value.isNotBlank() },
    )
}

class RelayMediaSessionController(
    context: Context,
    private val onWakeRequested: (RelayWakeSignal) -> Unit,
) {
    @Volatile
    private var hasPrimedMediaButtonSession = false

    private val player = ExoPlayer.Builder(context).build().apply {
        volume = 0f
        addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!hasPrimedMediaButtonSession && isPlaying) {
                        hasPrimedMediaButtonSession = true
                        Log.i(TAG, "activated silent playback for hardware media buttons")
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    hasPrimedMediaButtonSession = true
                    Log.e(TAG, "media session priming failed: ${error.message}", error)
                }
            },
        )
        setMediaSource(SilenceMediaSource(standbySilenceDurationUs))
        repeatMode = Player.REPEAT_MODE_ONE
        prepare()
        playWhenReady = true
    }

    private val mediaSession = MediaSession.Builder(context, player)
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
                }
                    ?: return super.onMediaButtonEvent(session, controllerInfo, intent)

                if (!isWakeMediaButtonKey(keyEvent.keyCode)) {
                    return super.onMediaButtonEvent(session, controllerInfo, intent)
                }

                val wakeSignal = createWakeSignalForMediaButton(
                    action = keyEvent.action,
                    keyCode = keyEvent.keyCode,
                    controllerPackage = controllerInfo.packageName,
                )

                if (wakeSignal == null) {
                    return true
                }

                Log.i(
                    TAG,
                    "media button keyCode=${keyEvent.keyCode} label=${wakeSignal.keyLabel} package=${wakeSignal.controllerPackage ?: "unknown"}",
                )
                onWakeRequested(wakeSignal)
                return true
            }
        })
        .build()

    fun session(): MediaSession = mediaSession

    fun release() {
        mediaSession.release()
        player.release()
    }

    fun playbackState(): Int = player.playbackState
}