package com.openclaw.relay

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession

class RelayMediaSessionController(
    context: Context,
    private val onWakeRequested: (RelayWakeSignal) -> Unit,
) {
    private val player = ExoPlayer.Builder(context).build()

    private val mediaSession = MediaSession.Builder(context, player)
        .setCallback(object : MediaSession.Callback {
            override fun onMediaButtonEvent(
                session: MediaSession,
                controllerInfo: MediaSession.ControllerInfo,
                intent: Intent,
            ): Boolean {
                @Suppress("DEPRECATION")
                val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    ?: return super.onMediaButtonEvent(session, controllerInfo, intent)

                if (keyEvent.action != KeyEvent.ACTION_UP) {
                    return true
                }

                return when (keyEvent.keyCode) {
                    KeyEvent.KEYCODE_HEADSETHOOK,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        onWakeRequested(
                            RelayWakeSignal(
                                trigger = "headset_button_single",
                                source = "physical_media_button",
                                sourceLabel = "Physical headset media button",
                                keyLabel = describeKeyCode(keyEvent.keyCode),
                                controllerPackage = controllerInfo.packageName.takeIf { value -> value.isNotBlank() },
                            ),
                        )
                        true
                    }

                    else -> super.onMediaButtonEvent(session, controllerInfo, intent)
                }
            }
        })
        .build()

    fun release() {
        mediaSession.release()
        player.release()
    }

    private fun describeKeyCode(keyCode: Int): String {
        return when (keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK -> "Headset hook"
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "Play or pause"
            KeyEvent.KEYCODE_MEDIA_PLAY -> "Play"
            else -> "Key code $keyCode"
        }
    }
}