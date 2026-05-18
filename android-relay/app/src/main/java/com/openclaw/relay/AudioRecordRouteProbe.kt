package com.openclaw.relay

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

internal data class AudioProbeRequest(
    val durationMs: Long = 1_200L,
    val sampleRateHz: Int = 16_000,
    val windowSize: Int = 512,
)

internal class AudioRecordRouteProbe(
    context: Context,
    private val routeSnapshotProvider: () -> RelayAudioRouteSnapshot,
    private val clock: () -> Long = System::currentTimeMillis,
    private val audioSourceCandidates: List<Int> = listOf(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.MIC,
    ),
) {
    private val appContext = context.applicationContext
    @Volatile
    private var stopRequested = false

    suspend fun runProbe(request: AudioProbeRequest = AudioProbeRequest()): AudioProbeMetrics =
        withContext(Dispatchers.IO) {
            val startedAtMs = clock()
            val routeSnapshot = routeSnapshotProvider()
            if (!hasRecordAudioPermission()) {
                return@withContext AudioProbeMetrics.notStarted(
                    status = AudioProbeInitStatus.PERMISSION_DENIED,
                    routeSnapshot = routeSnapshot,
                    startedAtMs = startedAtMs,
                    finishedAtMs = clock(),
                    errorMessage = "Microphone permission denied",
                )
            }

            val minBufferSize = AudioRecord.getMinBufferSize(
                request.sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBufferSize <= 0) {
                return@withContext AudioProbeMetrics.notStarted(
                    status = AudioProbeInitStatus.UNSUPPORTED_FORMAT,
                    routeSnapshot = routeSnapshot,
                    startedAtMs = startedAtMs,
                    finishedAtMs = clock(),
                    errorMessage = "AudioRecord does not support 16 kHz mono PCM on this device.",
                )
            }

            stopRequested = false
            val bufferSizeBytes = maxOf(minBufferSize, request.windowSize * Short.SIZE_BYTES)
            var lastFailure: AudioProbeMetrics? = null
            for (source in audioSourceCandidates) {
                val sourceName = audioSourceName(source)
                val recorder = tryCreateAudioRecord(
                    source = source,
                    sampleRateHz = request.sampleRateHz,
                    bufferSizeBytes = bufferSizeBytes,
                )
                if (recorder == null) {
                    lastFailure = AudioProbeMetrics.notStarted(
                        status = AudioProbeInitStatus.MIC_BUSY,
                        routeSnapshot = routeSnapshot,
                        startedAtMs = startedAtMs,
                        finishedAtMs = clock(),
                        errorMessage = "$sourceName microphone route could not be opened.",
                    )
                    continue
                }

                val metrics = captureAmplitudeSummary(
                    audioRecord = recorder,
                    sourceName = sourceName,
                    request = request,
                    bufferSizeBytes = bufferSizeBytes,
                    routeSnapshot = routeSnapshot,
                    startedAtMs = startedAtMs,
                )
                if (metrics.initStatus == AudioProbeInitStatus.STARTED || source == audioSourceCandidates.last()) {
                    return@withContext metrics
                }
                lastFailure = metrics
            }

            lastFailure ?: AudioProbeMetrics.notStarted(
                status = AudioProbeInitStatus.ERROR,
                routeSnapshot = routeSnapshot,
                startedAtMs = startedAtMs,
                finishedAtMs = clock(),
                errorMessage = "AudioRecord probe could not start.",
            )
        }

    fun stop() {
        stopRequested = true
    }

    @SuppressLint("MissingPermission")
    private fun tryCreateAudioRecord(
        source: Int,
        sampleRateHz: Int,
        bufferSizeBytes: Int,
    ): AudioRecord? {
        return try {
            AudioRecord(
                source,
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeBytes,
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }
    }

    private suspend fun captureAmplitudeSummary(
        audioRecord: AudioRecord,
        sourceName: String,
        request: AudioProbeRequest,
        bufferSizeBytes: Int,
        routeSnapshot: RelayAudioRouteSnapshot,
        startedAtMs: Long,
    ): AudioProbeMetrics {
        val recorder = AudioProbeMetricsRecorder(
            startedAtMs = startedAtMs,
            routeSnapshot = routeSnapshot,
        )
        recorder.markStarted(
            source = sourceName,
            sampleRateHz = request.sampleRateHz,
            windowSize = request.windowSize,
            minBufferSize = bufferSizeBytes,
        )

        return try {
            audioRecord.startRecording()
            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                recorder.markFailure(AudioProbeInitStatus.MIC_BUSY, "$sourceName microphone route is busy.")
                return recorder.finish(clock())
            }

            val buffer = ShortArray(request.windowSize)
            val endAtMs = clock() + request.durationMs.coerceAtLeast(0L)
            while (!stopRequested && clock() < endAtMs && currentCoroutineContext().isActive) {
                when (val readSize = audioRecord.read(buffer, 0, buffer.size)) {
                    in 1..buffer.size -> recorder.recordRead(buffer, readSize)
                    else -> recorder.recordReadError()
                }
            }

            recorder.finish(clock())
        } catch (_: SecurityException) {
            recorder.markFailure(AudioProbeInitStatus.PERMISSION_DENIED, "Microphone permission denied")
            recorder.finish(clock())
        } catch (error: IllegalStateException) {
            recorder.markFailure(AudioProbeInitStatus.MIC_BUSY, error.message ?: "$sourceName microphone route is busy.")
            recorder.finish(clock())
        } finally {
            runCatching {
                if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop()
                }
            }
            audioRecord.release()
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun audioSourceName(source: Int): String =
        when (source) {
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.MIC -> "MIC"
            else -> "SOURCE_$source"
        }
}
