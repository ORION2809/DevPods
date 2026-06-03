package com.openclaw.relay.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.openclaw.relay.PlatformVadObservation
import com.openclaw.relay.SpeechEndpointReason
import com.openclaw.relay.VadCallbacks
import com.openclaw.relay.VadProbe
import com.openclaw.relay.VadProbeRequest
import com.openclaw.relay.audio.AudioCaptureOwner
import com.openclaw.relay.audio.CaptureOwner
import com.openclaw.relay.sherpa.SherpaModelSpec
import com.openclaw.relay.sherpa.SherpaModelStatus
import com.openclaw.relay.sherpa.SherpaNativeLoader
import com.openclaw.relay.sherpa.SherpaRuntimeAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Real Silero VAD probe using Sherpa-ONNX.
 *
 * Captures audio via [AudioRecord], feeds it to Sherpa's [Vad],
 * and emits [PlatformVadObservation] without storing raw PCM.
 *
 * Requires:
 * - Native runtime loaded ([SherpaNativeLoader])
 * - VAD model installed ([SherpaModelStatus.isReady])
 * - Microphone permission granted
 * - [AudioCaptureOwner] lease acquired
 */
internal class SherpaVadProbe(
    context: Context,
    private val captureOwner: AudioCaptureOwner,
    private val modelSpecProvider: () -> SherpaModelSpec?,
    private val modelStatusProvider: () -> SherpaModelStatus?,
    private val runtimeAvailabilityProvider: () -> SherpaRuntimeAvailability,
    private val clock: () -> Long = System::currentTimeMillis,
) : VadProbe {
    override val id: String = "sherpa_silero_vad"

    private val appContext = context.applicationContext
    @Volatile
    private var stopRequested = false

    override suspend fun start(request: VadProbeRequest, callbacks: VadCallbacks) {
        val runtime = runtimeAvailabilityProvider()
        if (!runtime.isNativeLibraryLoadable) {
            callbacks.onError("Sherpa native runtime unavailable: ${runtime.failureReason}")
            return
        }

        val modelSpec = modelSpecProvider()
        val modelStatus = modelStatusProvider()
        if (modelSpec == null || modelStatus == null || !modelStatus.isReady) {
            callbacks.onError("Silero VAD model not installed or checksum mismatch.")
            return
        }

        val lease = captureOwner.acquire(CaptureOwner.SHERPA_VAD, request.sessionId)
        if (lease == null) {
            callbacks.onError("Microphone is busy: ${captureOwner.currentOwnerName()}")
            return
        }

        try {
            runProbe(request, callbacks, modelSpec)
        } finally {
            lease.release()
        }
    }

    override suspend fun stop() {
        stopRequested = true
    }

    private suspend fun runProbe(
        request: VadProbeRequest,
        callbacks: VadCallbacks,
        modelSpec: SherpaModelSpec,
    ) = withContext(Dispatchers.IO) {
        if (!hasRecordAudioPermission()) {
            callbacks.onObservation(
                PlatformVadObservation(
                    speechDetected = false,
                    endpointReason = SpeechEndpointReason.PERMISSION_DENIED,
                ),
            )
            return@withContext
        }

        val config = SherpaVadConfig.DEFAULT
        val vad = try {
            createVad(modelSpec, config)
        } catch (e: Exception) {
            callbacks.onError("Failed to create VAD: ${e.message}")
            return@withContext
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            config.sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) {
            callbacks.onError("AudioRecord does not support ${config.sampleRateHz} Hz mono PCM.")
            vad.release()
            return@withContext
        }

        val bufferSizeBytes = maxOf(minBufferSize, config.windowSize * Short.SIZE_BYTES)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                config.sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeBytes,
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

        if (recorder == null) {
            callbacks.onError("Could not open AudioRecord for VAD probe.")
            vad.release()
            return@withContext
        }

        stopRequested = false
        val observation = try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                callbacks.onError("AudioRecord failed to start recording.")
                vad.release()
                return@withContext
            }

            val probeStartMs = clock()
            val buffer = ShortArray(config.windowSize)
            val endAtMs = clock() + request.durationMs.coerceAtLeast(0L)
            var totalWindows = 0
            var speechWindows = 0
            var firstSpeechAtMs: Long? = null
            var lastSpeechAtMs: Long? = null
            var peakAmplitude = 0f
            var readErrors = 0
            var rmsAboveFloorCount = 0
            val noiseFloorThreshold = 0.005f

            while (!stopRequested && clock() < endAtMs && currentCoroutineContext().isActive) {
                val readSize = recorder.read(buffer, 0, buffer.size)
                if (readSize <= 0) {
                    readErrors++
                    continue
                }

                totalWindows++
                val floatSamples = buffer.shortToFloat(readSize)
                val windowPeak = floatSamples.maxOf { kotlin.math.abs(it) }
                if (windowPeak > peakAmplitude) peakAmplitude = windowPeak

                val windowRms = kotlin.math.sqrt(floatSamples.map { it * it }.average())
                if (windowRms > noiseFloorThreshold) {
                    rmsAboveFloorCount++
                }

                try {
                    vad.acceptWaveform(floatSamples)
                    if (vad.isSpeechDetected()) {
                        speechWindows++
                        val now = clock()
                        if (firstSpeechAtMs == null) firstSpeechAtMs = now - probeStartMs
                        lastSpeechAtMs = now - probeStartMs
                    }
                } catch (e: Exception) {
                    readErrors++
                }
            }

            val nonzeroFrameRatio = if (totalWindows > 0) speechWindows.toFloat() / totalWindows else 0f
            val noSignal = totalWindows > 0 && speechWindows == 0 && peakAmplitude < 0.01f
            val wrongMicSuspected = totalWindows > 0 && speechWindows == 0 && peakAmplitude >= 0.01f

            PlatformVadObservation(
                speechDetected = speechWindows > 0,
                rmsPeakDb = if (peakAmplitude > 0f) 20 * kotlin.math.log10(peakAmplitude) else null,
                rmsFrameCount = totalWindows,
                rmsFramesAboveNoiseFloor = rmsAboveFloorCount,
                speechStartDelayMs = firstSpeechAtMs,
                endpointReason = when {
                    readErrors > totalWindows / 2 -> SpeechEndpointReason.AUDIO_ERROR
                    noSignal -> SpeechEndpointReason.NO_SPEECH
                    else -> SpeechEndpointReason.FINAL
                },
                wrongMicSuspected = wrongMicSuspected,
            )
        } catch (_: SecurityException) {
            PlatformVadObservation(
                speechDetected = false,
                endpointReason = SpeechEndpointReason.PERMISSION_DENIED,
            )
        } catch (e: IllegalStateException) {
            PlatformVadObservation(
                speechDetected = false,
                endpointReason = SpeechEndpointReason.ROUTE_FAILED,
                wrongMicSuspected = false,
            )
        } finally {
            runCatching {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            }
            recorder.release()
            runCatching { vad.release() }
        }

        callbacks.onObservation(observation)
    }

    private fun createVad(modelSpec: SherpaModelSpec, config: SherpaVadConfig): com.k2fsa.sherpa.onnx.Vad {
        val sileroConfig = com.k2fsa.sherpa.onnx.SileroVadModelConfig(
            model = "${modelSpec.modelRootPath}/${modelSpec.requiredFiles.first()}",
            threshold = config.threshold,
            minSilenceDuration = config.minSilenceDuration,
            minSpeechDuration = config.minSpeechDuration,
            windowSize = config.windowSize,
            maxSpeechDuration = config.maxSpeechDuration,
        )
        val vadModelConfig = com.k2fsa.sherpa.onnx.VadModelConfig(
            sileroVadModelConfig = sileroConfig,
            sampleRate = config.sampleRateHz,
            numThreads = config.numThreads,
            provider = config.provider,
            debug = false,
        )
        return com.k2fsa.sherpa.onnx.Vad(config = vadModelConfig)
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun ShortArray.shortToFloat(size: Int): FloatArray {
        val result = FloatArray(size)
        for (i in 0 until size) {
            result[i] = this[i] / 32768.0f
        }
        return result
    }
}

internal data class SherpaVadConfig(
    val sampleRateHz: Int = 16_000,
    val windowSize: Int = 512,
    val threshold: Float = 0.5f,
    val minSilenceDuration: Float = 0.25f,
    val minSpeechDuration: Float = 0.25f,
    val maxSpeechDuration: Float = 5.0f,
    val numThreads: Int = 1,
    val provider: String = "cpu",
) {
    companion object {
        val DEFAULT = SherpaVadConfig()
    }
}
