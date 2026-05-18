package com.openclaw.relay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.LANG_MISSING_DATA
import android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class AndroidTtsSpeaker(
    context: Context,
    private val onError: (String) -> Unit = {},
    private val onReadyChanged: (Boolean) -> Unit = {},
    private val onSpeakingChanged: (Boolean) -> Unit = {},
    private val onPlaybackMetrics: (TtsPlaybackMetrics) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val completionCallbacks = ConcurrentHashMap<String, () -> Unit>()
    private val playbackRecorders = ConcurrentHashMap<String, TtsPlaybackMetricsRecorder>()
    private var textToSpeech: TextToSpeech? = null
    private var ready = false

    init {
        textToSpeech = TextToSpeech(applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            onReadyChanged(ready)
            if (!ready) {
                onError("Text-to-speech initialization failed with status $status.")
                return@TextToSpeech
            }

            val languageResult = textToSpeech?.setLanguage(Locale.US)
            if (languageResult == LANG_MISSING_DATA || languageResult == LANG_NOT_SUPPORTED) {
                ready = false
                onReadyChanged(false)
                onError("US English text-to-speech data is unavailable on this device.")
                return@TextToSpeech
            }

            textToSpeech?.setSpeechRate(1.05f)
            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    utteranceId?.let(playbackRecorders::get)?.let { recorder ->
                        recorder.markStarted(clock())
                        onPlaybackMetrics(recorder.snapshot())
                    }
                    onSpeakingChanged(true)
                }

                override fun onDone(utteranceId: String?) {
                    utteranceId?.let(playbackRecorders::remove)?.let { recorder ->
                        recorder.markDone(clock())
                        onPlaybackMetrics(recorder.snapshot())
                    }
                    onSpeakingChanged(false)
                    completionCallbacks.remove(utteranceId)?.let { callback ->
                        mainHandler.post(callback)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    utteranceId?.let(playbackRecorders::remove)?.let { recorder ->
                        recorder.markError(clock(), errorCode = null)
                        onPlaybackMetrics(recorder.snapshot())
                    }
                    onSpeakingChanged(false)
                    completionCallbacks.remove(utteranceId)
                    this@AndroidTtsSpeaker.onError("Text-to-speech playback failed.")
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    utteranceId?.let(playbackRecorders::remove)?.let { recorder ->
                        recorder.markError(clock(), errorCode = errorCode)
                        onPlaybackMetrics(recorder.snapshot())
                    }
                    onSpeakingChanged(false)
                    completionCallbacks.remove(utteranceId)
                    this@AndroidTtsSpeaker.onError("Text-to-speech playback failed with error code $errorCode.")
                }
            })
        }
    }

    fun speak(text: String, utteranceId: String? = null, onComplete: (() -> Unit)? = null) {
        if (!ready || text.isBlank()) {
            if (text.isNotBlank() && !ready) {
                onError("Text-to-speech is not ready yet.")
            }
            return
        }

        val requestedAtMs = clock()
        val resolvedUtteranceId = utteranceId ?: "relay-$requestedAtMs"
        val recorder = TtsPlaybackMetricsRecorder(
            utteranceId = resolvedUtteranceId,
            textLength = text.length,
            requestedAtMs = requestedAtMs,
        )
        playbackRecorders[resolvedUtteranceId] = recorder
        onPlaybackMetrics(recorder.snapshot())
        if (onComplete != null) {
            completionCallbacks[resolvedUtteranceId] = onComplete
        }
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, resolvedUtteranceId)
    }

    fun stop() {
        val requestedAtMs = clock()
        playbackRecorders.values.forEach { recorder ->
            recorder.markStopped(clock(), requestedAtMs)
            onPlaybackMetrics(recorder.snapshot())
        }
        onSpeakingChanged(false)
        completionCallbacks.clear()
        playbackRecorders.clear()
        textToSpeech?.stop()
    }

    fun close() {
        onSpeakingChanged(false)
        onReadyChanged(false)
        completionCallbacks.clear()
        playbackRecorders.clear()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
    }
}
