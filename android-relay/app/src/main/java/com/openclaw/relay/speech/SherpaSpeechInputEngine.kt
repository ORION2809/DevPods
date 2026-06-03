package com.openclaw.relay.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.openclaw.relay.SpeechCallbacks
import com.openclaw.relay.SpeechEndpointReason
import com.openclaw.relay.SpeechEngineCapabilities
import com.openclaw.relay.SpeechInputEngine
import com.openclaw.relay.SpeechRecognitionFailure
import com.openclaw.relay.SpeechSessionRequest
import com.openclaw.relay.SpeechStopReason
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
 * Real Sherpa-ONNX streaming STT engine.
 *
 * Captures audio via [AudioRecord], feeds it to Sherpa's [OnlineRecognizer],
 * and emits the full [SpeechCallbacks] lifecycle.
 *
 * Requires:
 * - Native runtime loaded ([SherpaNativeLoader])
 * - STT model installed ([SherpaModelStatus.isReady])
 * - Microphone permission granted
 * - [AudioCaptureOwner] lease acquired
 */
internal class SherpaSpeechInputEngine(
    context: Context,
    private val captureOwner: AudioCaptureOwner,
    private val modelSpecProvider: () -> SherpaModelSpec?,
    private val modelStatusProvider: () -> SherpaModelStatus?,
    private val runtimeAvailabilityProvider: () -> SherpaRuntimeAvailability,
    private val clock: () -> Long = System::currentTimeMillis,
    override val id: String = "sherpa_streaming",
) : SpeechInputEngine {

    private val appContext = context.applicationContext
    @Volatile
    private var stopRequested = false
    @Volatile
    private var activeLease: com.openclaw.relay.audio.CaptureLease? = null

    override fun capabilities(): SpeechEngineCapabilities =
        SpeechEngineCapabilities(
            engineId = id,
            isAvailable = isEngineAvailable(),
            supportsPartialResults = true,
            supportsEndpointingHints = true,
            supportsOnDeviceRecognition = true,
            storesRawAudio = false,
        )

    override fun prepare(request: SpeechSessionRequest): Boolean = isEngineAvailable()

    override suspend fun start(request: SpeechSessionRequest, callbacks: SpeechCallbacks) {
        if (!isEngineAvailable()) {
            val runtime = runtimeAvailabilityProvider()
            val reasons = buildList {
                if (!runtime.isNativeLibraryLoadable) add("Sherpa native runtime unavailable: ${runtime.failureReason}")
                val modelStatus = modelStatusProvider()
                if (modelStatus == null || !modelStatus.isReady) add("STT model not installed")
            }
            callbacks.onError(
                SpeechRecognitionFailure(
                    message = "Sherpa STT unavailable: ${reasons.joinToString(", ")}",
                    shouldResetSession = false,
                    errorCode = null,
                    endpointReason = SpeechEndpointReason.CLIENT_ERROR,
                ),
            )
            return
        }

        val lease = captureOwner.acquire(CaptureOwner.SHERPA_STT, request.sessionId)
        if (lease == null) {
            callbacks.onError(
                SpeechRecognitionFailure(
                    message = "Microphone is busy: ${captureOwner.currentOwnerName()}",
                    shouldResetSession = false,
                    errorCode = null,
                    endpointReason = SpeechEndpointReason.RECOGNIZER_BUSY,
                ),
            )
            return
        }
        activeLease = lease

        try {
            runRecognition(request, callbacks)
        } finally {
            activeLease = null
            lease.release()
        }
    }

    override suspend fun stop(reason: SpeechStopReason) {
        stopRequested = true
    }

    override fun destroy() {
        stopRequested = true
        activeLease?.release()
    }

    private fun isEngineAvailable(): Boolean {
        val runtime = runtimeAvailabilityProvider()
        val modelStatus = modelStatusProvider()
        return runtime.isNativeLibraryLoadable && modelStatus != null && modelStatus.isReady
    }

    private suspend fun runRecognition(request: SpeechSessionRequest, callbacks: SpeechCallbacks) {
        if (!hasRecordAudioPermission()) {
            callbacks.onError(
                SpeechRecognitionFailure(
                    message = "Microphone permission denied.",
                    shouldResetSession = false,
                    errorCode = null,
                    endpointReason = SpeechEndpointReason.PERMISSION_DENIED,
                ),
            )
            return
        }

        val modelSpec = modelSpecProvider() ?: run {
            callbacks.onError(
                SpeechRecognitionFailure(
                    message = "STT model spec not configured.",
                    shouldResetSession = false,
                    errorCode = null,
                    endpointReason = SpeechEndpointReason.CLIENT_ERROR,
                ),
            )
            return
        }

        val sampleRateHz = 16_000
        val bufferSizeMs = 100
        val windowSamples = (sampleRateHz * bufferSizeMs / 1000)
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) {
            callbacks.onError(
                SpeechRecognitionFailure(
                    message = "AudioRecord does not support ${sampleRateHz} Hz mono PCM.",
                    shouldResetSession = false,
                    errorCode = null,
                    endpointReason = SpeechEndpointReason.AUDIO_ERROR,
                ),
            )
            return
        }

        val bufferSizeBytes = maxOf(minBufferSize, windowSamples * Short.SIZE_BYTES)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRateHz,
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
            callbacks.onError(
                SpeechRecognitionFailure(
                    message = "Could not open AudioRecord for STT.",
                    shouldResetSession = false,
                    errorCode = null,
                    endpointReason = SpeechEndpointReason.AUDIO_ERROR,
                ),
            )
            return
        }

        val recognizer = try {
            createRecognizer(modelSpec)
        } catch (e: Exception) {
            callbacks.onError(
                SpeechRecognitionFailure(
                    message = "Failed to create Sherpa recognizer: ${e.message}",
                    shouldResetSession = false,
                    errorCode = null,
                    endpointReason = SpeechEndpointReason.CLIENT_ERROR,
                ),
            )
            recorder.release()
            return
        }

        callbacks.onRecognizerCreated()
        stopRequested = false

        try {
            withContext(Dispatchers.IO) {
                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    callbacks.onError(
                        SpeechRecognitionFailure(
                            message = "AudioRecord failed to start recording.",
                            shouldResetSession = false,
                            errorCode = null,
                            endpointReason = SpeechEndpointReason.AUDIO_ERROR,
                        ),
                    )
                    return@withContext
                }

                callbacks.onListeningStarted()
                callbacks.onReadyForSpeech()

                val stream = recognizer.createStream()
                val buffer = ShortArray(windowSamples)
                var speechStarted = false
                val maxDurationMs = 30_000L
                val startAtMs = clock()

                try {
                    while (!stopRequested && clock() - startAtMs < maxDurationMs && currentCoroutineContext().isActive) {
                        val readSize = recorder.read(buffer, 0, buffer.size)
                        if (readSize <= 0) continue

                        val floatSamples = buffer.shortToFloat(readSize)
                        stream.acceptWaveform(floatSamples, sampleRateHz)

                        if (!speechStarted && recognizer.isReady(stream)) {
                            recognizer.decode(stream)
                            val result = recognizer.getResult(stream)
                            if (result.text.isNotBlank()) {
                                speechStarted = true
                                callbacks.onBeginningOfSpeech()
                                callbacks.onPartialTranscript(result.text)
                            }
                        } else if (speechStarted && recognizer.isReady(stream)) {
                            recognizer.decode(stream)
                            val result = recognizer.getResult(stream)
                            if (result.text.isNotBlank()) {
                                callbacks.onPartialTranscript(result.text)
                            }
                        }

                        if (recognizer.isEndpoint(stream)) {
                            recognizer.decode(stream)
                            val result = recognizer.getResult(stream)
                            callbacks.onEndOfSpeech()
                            callbacks.onFinalTranscript(result.text)
                            return@withContext
                        }
                    }

                    // Flush remaining audio
                    stream.inputFinished()
                    recognizer.decode(stream)
                    val result = recognizer.getResult(stream)
                    if (speechStarted) {
                        callbacks.onEndOfSpeech()
                    }
                    if (result.text.isNotBlank()) {
                        callbacks.onFinalTranscript(result.text)
                    } else if (!speechStarted) {
                        callbacks.onError(
                            SpeechRecognitionFailure(
                                message = "No speech detected.",
                                shouldResetSession = false,
                                errorCode = null,
                                endpointReason = SpeechEndpointReason.NO_SPEECH,
                            ),
                        )
                    }
                } finally {
                    stream.release()
                }
            }
        } catch (_: SecurityException) {
            callbacks.onError(
                SpeechRecognitionFailure(
                    message = "Microphone permission denied during recording.",
                    shouldResetSession = false,
                    errorCode = null,
                    endpointReason = SpeechEndpointReason.PERMISSION_DENIED,
                ),
            )
        } catch (e: IllegalStateException) {
            callbacks.onError(
                SpeechRecognitionFailure(
                    message = "Audio recorder error: ${e.message}",
                    shouldResetSession = false,
                    errorCode = null,
                    endpointReason = SpeechEndpointReason.AUDIO_ERROR,
                ),
            )
        } finally {
            runCatching {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            }
            recorder.release()
            runCatching { recognizer.release() }
        }
    }

    private fun createRecognizer(modelSpec: SherpaModelSpec): com.k2fsa.sherpa.onnx.OnlineRecognizer {
        val modelRoot = modelSpec.modelRootPath
        val featConfig = com.k2fsa.sherpa.onnx.FeatureConfig()
        val modelConfig = com.k2fsa.sherpa.onnx.OnlineModelConfig(
            transducer = com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig(
                encoder = "$modelRoot/encoder.onnx",
                decoder = "$modelRoot/decoder.onnx",
                joiner = "$modelRoot/joiner.onnx",
            ),
            tokens = "$modelRoot/tokens.txt",
            numThreads = 1,
            provider = "cpu",
            debug = false,
        )
        val endpointConfig = com.k2fsa.sherpa.onnx.EndpointConfig()
        val recognizerConfig = com.k2fsa.sherpa.onnx.OnlineRecognizerConfig(
            featConfig = featConfig,
            modelConfig = modelConfig,
            endpointConfig = endpointConfig,
            enableEndpoint = true,
            decodingMethod = "greedy_search",
            maxActivePaths = 4,
        )
        return com.k2fsa.sherpa.onnx.OnlineRecognizer(config = recognizerConfig)
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
