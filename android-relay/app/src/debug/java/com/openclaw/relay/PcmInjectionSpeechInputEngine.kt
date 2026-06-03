package com.openclaw.relay

import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Debug-only PCM speech input engine for deterministic emulator proof runs.
 *
 * The engine accepts in-memory 16 kHz mono PCM frames, derives RMS/VAD events,
 * and emits scripted transcripts. It deliberately does not persist raw PCM.
 */
internal class PcmInjectionSpeechInputEngine(
    override val id: String = "debug_pcm_injection",
) : SpeechInputEngine {
    private var activeScript: PcmInjectionScript? = null
    private var isRunning = false

    override fun capabilities(): SpeechEngineCapabilities =
        SpeechEngineCapabilities(
            engineId = id,
            isAvailable = true,
            supportsPartialResults = true,
            supportsEndpointingHints = true,
            supportsOnDeviceRecognition = true,
            storesRawAudio = false,
        )

    override fun prepare(request: SpeechSessionRequest): Boolean = true

    override suspend fun start(request: SpeechSessionRequest, callbacks: SpeechCallbacks) {
        val script = activeScript ?: PcmInjectionScript()
        activeScript = null
        isRunning = true

        callbacks.onRecognizerCreated()
        callbacks.onListeningStarted()
        callbacks.onReadyForSpeech()

        val formatFailure = validate(script)
        if (formatFailure != null) {
            callbacks.onError(formatFailure)
            isRunning = false
            return
        }

        var speechDetected = false
        var partialEmitted = false
        var offset = 0

        while (isRunning && offset < script.frames.size) {
            val count = minOf(script.windowSizeSamples, script.frames.size - offset)
            val rmsDb = calculateRmsDb(script.frames, offset, count)
            callbacks.onRmsChanged(rmsDb)

            if (rmsDb > script.speechThresholdDb) {
                if (!speechDetected) {
                    speechDetected = true
                    callbacks.onBeginningOfSpeech()
                }

                if (!partialEmitted && script.partialText.isNotBlank()) {
                    partialEmitted = true
                    callbacks.onPartialTranscript(script.partialText)
                }
            }

            if (script.frameDelayMs > 0) {
                delay(script.frameDelayMs)
            }
            offset += count
        }

        if (!isRunning) {
            return
        }

        if (speechDetected && script.finalText.isNotBlank()) {
            callbacks.onEndOfSpeech()
            callbacks.onFinalTranscript(script.finalText)
        } else {
            callbacks.onError(noSpeechFailure())
        }

        isRunning = false
    }

    override suspend fun stop(reason: SpeechStopReason) {
        isRunning = false
    }

    override fun destroy() {
        isRunning = false
        activeScript = null
    }

    fun setScript(script: PcmInjectionScript) {
        activeScript = script
    }

    private fun validate(script: PcmInjectionScript): SpeechRecognitionFailure? {
        if (script.sampleRateHz != PcmInjectionScript.REQUIRED_SAMPLE_RATE_HZ) {
            return SpeechRecognitionFailure(
                message = "PCM injection requires 16 kHz mono signed PCM.",
                shouldResetSession = true,
                errorCode = ERROR_UNSUPPORTED_FORMAT,
                endpointReason = SpeechEndpointReason.CLIENT_ERROR,
            )
        }

        if (script.windowSizeSamples <= 0) {
            return SpeechRecognitionFailure(
                message = "PCM injection requires a positive analysis window.",
                shouldResetSession = true,
                errorCode = ERROR_UNSUPPORTED_FORMAT,
                endpointReason = SpeechEndpointReason.CLIENT_ERROR,
            )
        }

        return null
    }

    private fun noSpeechFailure(): SpeechRecognitionFailure =
        SpeechRecognitionFailure(
            message = "No speech detected in injected PCM fixture.",
            shouldResetSession = false,
            errorCode = ERROR_NO_SPEECH,
            endpointReason = SpeechEndpointReason.NO_SPEECH,
        )

    private fun calculateRmsDb(
        frames: ShortArray,
        offset: Int,
        count: Int,
    ): Float {
        if (count <= 0) {
            return SILENCE_DB
        }

        var sumSquares = 0.0
        repeat(count) { index ->
            val normalized = frames[offset + index].toDouble() / Short.MAX_VALUE.toDouble()
            sumSquares += normalized * normalized
        }

        val rms = sqrt(sumSquares / count.toDouble())
        if (rms <= 0.0) {
            return SILENCE_DB
        }

        return (20.0 * log10(rms)).toFloat()
    }

    companion object {
        const val ERROR_NO_SPEECH: Int = 7
        const val ERROR_UNSUPPORTED_FORMAT: Int = 1_001
        private const val SILENCE_DB: Float = -120f
    }
}

internal data class PcmInjectionScript(
    val finalText: String = "run tests",
    val partialText: String = "run",
    val sampleRateHz: Int = REQUIRED_SAMPLE_RATE_HZ,
    val frames: ShortArray = PcmInjectionFixtures.spokenPhrase(),
    val windowSizeSamples: Int = DEFAULT_WINDOW_SIZE_SAMPLES,
    val speechThresholdDb: Float = DEFAULT_SPEECH_THRESHOLD_DB,
    val frameDelayMs: Long = DEFAULT_FRAME_DELAY_MS,
) {
    companion object {
        const val REQUIRED_SAMPLE_RATE_HZ: Int = 16_000
        const val DEFAULT_WINDOW_SIZE_SAMPLES: Int = 512
        const val DEFAULT_SPEECH_THRESHOLD_DB: Float = -45f
        const val DEFAULT_FRAME_DELAY_MS: Long = 4
    }
}

internal object PcmInjectionFixtures {
    fun spokenPhrase(
        durationMs: Int = 900,
        sampleRateHz: Int = PcmInjectionScript.REQUIRED_SAMPLE_RATE_HZ,
        amplitude: Short = 9_000,
        frequencyHz: Double = 440.0,
    ): ShortArray {
        val totalSamples = samplesForDuration(durationMs, sampleRateHz)
        val leadingSilence = samplesForDuration(durationMs / 8, sampleRateHz)
        val trailingSilence = samplesForDuration(durationMs / 8, sampleRateHz)
        val speechEndExclusive = (totalSamples - trailingSilence).coerceAtLeast(leadingSilence)

        return ShortArray(totalSamples) { index ->
            if (index < leadingSilence || index >= speechEndExclusive) {
                0
            } else {
                val phase = 2.0 * PI * frequencyHz * index.toDouble() / sampleRateHz.toDouble()
                (sin(phase) * amplitude).toInt().toShort()
            }
        }
    }

    fun silence(
        durationMs: Int = 900,
        sampleRateHz: Int = PcmInjectionScript.REQUIRED_SAMPLE_RATE_HZ,
    ): ShortArray = ShortArray(samplesForDuration(durationMs, sampleRateHz))

    private fun samplesForDuration(durationMs: Int, sampleRateHz: Int): Int =
        ((durationMs.coerceAtLeast(1).toLong() * sampleRateHz.toLong()) / 1_000L)
            .coerceAtLeast(1L)
            .toInt()
}
