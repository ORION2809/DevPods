package com.openclaw.relay

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession

class RelayMediaSessionController(
    context: Context,
    private val onWakeRequested: (String) -> Unit,
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
                        onWakeRequested("headset_button_single")
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
}