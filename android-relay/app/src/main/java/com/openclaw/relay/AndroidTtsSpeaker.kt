package com.openclaw.relay

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
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
    private val maxTextLength: Int = 280,
) {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val completionCallbacks = ConcurrentHashMap<String, () -> Unit>()
    private val playbackRecorders = ConcurrentHashMap<String, TtsPlaybackMetricsRecorder>()
    private val warmupUtteranceIds = mutableSetOf<String>()
    private var textToSpeech: TextToSpeech? = null
    private var ready = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private var activeUtteranceId: String? = null

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        mainHandler.post {
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    playbackRecorders[activeUtteranceId]?.let { recorder ->
                        recorder.markFocusLost(clock(), focusChange)
                        onPlaybackMetrics(recorder.snapshot())
                    }
                    textToSpeech?.stop()
                    onSpeakingChanged(false)
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    playbackRecorders[activeUtteranceId]?.let { recorder ->
                        recorder.markFocusLost(clock(), focusChange)
                        onPlaybackMetrics(recorder.snapshot())
                    }
                    stop()
                    abandonAudioFocus()
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    playbackRecorders[activeUtteranceId]?.let { recorder ->
                        recorder.markFocusGained(clock())
                        onPlaybackMetrics(recorder.snapshot())
                    }
                }
            }
        }
    }

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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                textToSpeech?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            }

            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId != null && warmupUtteranceIds.contains(utteranceId)) {
                        val recorder = playbackRecorders[utteranceId]
                        recorder?.markWarmupStarted(clock())
                        recorder?.let { onPlaybackMetrics(it.snapshot()) }
                        return
                    }
                    utteranceId?.let(playbackRecorders::get)?.let { recorder ->
                        recorder.markStarted(clock())
                        onPlaybackMetrics(recorder.snapshot())
                    }
                    onSpeakingChanged(true)
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId != null && warmupUtteranceIds.contains(utteranceId)) {
                        warmupUtteranceIds.remove(utteranceId)
                        val recorder = playbackRecorders.remove(utteranceId)
                        recorder?.markWarmupDone(clock())
                        recorder?.let { onPlaybackMetrics(it.snapshot()) }
                        return
                    }
                    utteranceId?.let(playbackRecorders::remove)?.let { recorder ->
                        recorder.markDone(clock())
                        onPlaybackMetrics(recorder.snapshot())
                    }
                    onSpeakingChanged(false)
                    completionCallbacks.remove(utteranceId)?.let { callback ->
                        mainHandler.post(callback)
                    }
                    if (activeUtteranceId == utteranceId) {
                        abandonAudioFocus()
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId != null && warmupUtteranceIds.contains(utteranceId)) {
                        warmupUtteranceIds.remove(utteranceId)
                        val recorder = playbackRecorders.remove(utteranceId)
                        recorder?.markWarmupError(clock(), errorCode = null)
                        recorder?.let { onPlaybackMetrics(it.snapshot()) }
                        return
                    }
                    utteranceId?.let(playbackRecorders::remove)?.let { recorder ->
                        recorder.markError(clock(), errorCode = null)
                        onPlaybackMetrics(recorder.snapshot())
                    }
                    onSpeakingChanged(false)
                    completionCallbacks.remove(utteranceId)
                    this@AndroidTtsSpeaker.onError("Text-to-speech playback failed.")
                    if (activeUtteranceId == utteranceId) {
                        abandonAudioFocus()
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId != null && warmupUtteranceIds.contains(utteranceId)) {
                        warmupUtteranceIds.remove(utteranceId)
                        val recorder = playbackRecorders.remove(utteranceId)
                        recorder?.markWarmupError(clock(), errorCode = errorCode)
                        recorder?.let { onPlaybackMetrics(it.snapshot()) }
                        return
                    }
                    utteranceId?.let(playbackRecorders::remove)?.let { recorder ->
                        recorder.markError(clock(), errorCode = errorCode)
                        onPlaybackMetrics(recorder.snapshot())
                    }
                    onSpeakingChanged(false)
                    completionCallbacks.remove(utteranceId)
                    this@AndroidTtsSpeaker.onError("Text-to-speech playback failed with error code $errorCode.")
                    if (activeUtteranceId == utteranceId) {
                        abandonAudioFocus()
                    }
                }
            })
        }
    }

    fun speak(text: String, utteranceId: String? = null, onComplete: (() -> Unit)? = null, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        if (!ready || text.isBlank()) {
            if (text.isNotBlank() && !ready) {
                onError("Text-to-speech is not ready yet.")
            }
            return
        }

        val boundedText = if (text.length > maxTextLength) {
            onError("Response was too long to speak. Displaying text instead.")
            text.take(maxTextLength) + "..."
        } else {
            text
        }

        val requestedAtMs = clock()
        val resolvedUtteranceId = utteranceId ?: "relay-$requestedAtMs"
        val recorder = TtsPlaybackMetricsRecorder(
            utteranceId = resolvedUtteranceId,
            textLength = boundedText.length,
            requestedAtMs = requestedAtMs,
        )
        playbackRecorders[resolvedUtteranceId] = recorder
        onPlaybackMetrics(recorder.snapshot())
        if (onComplete != null) {
            completionCallbacks[resolvedUtteranceId] = onComplete
        }

        val focusResult = requestAudioFocus(resolvedUtteranceId)
        recorder.markFocusRequested(clock(), focusResult)
        onPlaybackMetrics(recorder.snapshot())
        activeUtteranceId = resolvedUtteranceId

        textToSpeech?.speak(boundedText, queueMode, null, resolvedUtteranceId)
    }

    fun warm(): Boolean {
        if (!ready) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val utteranceId = "warmup-${clock()}"
            val requestedAtMs = clock()
            val recorder = TtsPlaybackMetricsRecorder(
                utteranceId = utteranceId,
                textLength = 0,
                requestedAtMs = requestedAtMs,
            )
            warmupUtteranceIds.add(utteranceId)
            playbackRecorders[utteranceId] = recorder
            onPlaybackMetrics(recorder.snapshot())
            try {
                textToSpeech?.playSilentUtterance(100L, TextToSpeech.QUEUE_ADD, utteranceId)
                return true
            } catch (e: Exception) {
                warmupUtteranceIds.remove(utteranceId)
                playbackRecorders.remove(utteranceId)
                return false
            }
        }
        return false
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
        abandonAudioFocus()
    }

    fun close() {
        onSpeakingChanged(false)
        onReadyChanged(false)
        completionCallbacks.clear()
        playbackRecorders.clear()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        abandonAudioFocus()
    }

    private fun requestAudioFocus(utteranceId: String): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
        }
    }

    private fun abandonAudioFocus() {
        activeUtteranceId = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }
}
