package com.openclaw.relay

import kotlin.math.abs

enum class AudioProbeInitStatus {
    NOT_STARTED,
    STARTED,
    PERMISSION_DENIED,
    MIC_BUSY,
    UNSUPPORTED_FORMAT,
    READ_FAILED,
    ERROR,
}

data class AudioProbeMetrics(
    val initStatus: AudioProbeInitStatus,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val audioSource: String? = null,
    val sampleRateHz: Int = 16_000,
    val windowSize: Int = 512,
    val minBufferSize: Int = 0,
    val framesRead: Int = 0,
    val nonZeroFrames: Int = 0,
    val readErrorCount: Int = 0,
    val peakAmplitude: Float = 0f,
    val noiseFloorEstimate: Float = 0f,
    val bluetoothRouteAtCapture: AudioRouteProof = AudioRouteProof(),
    val rawAudioPersisted: Boolean = false,
    val rawAudioPath: String? = null,
    val errorMessage: String? = null,
) {
    val durationMs: Long
        get() = (finishedAtMs - startedAtMs).coerceAtLeast(0L)

    val nonZeroFrameRatio: Float
        get() = if (framesRead == 0) 0f else nonZeroFrames.toFloat() / framesRead.toFloat()

    companion object {
        fun notStarted(
            status: AudioProbeInitStatus,
            routeSnapshot: RelayAudioRouteSnapshot,
            startedAtMs: Long,
            finishedAtMs: Long,
            errorMessage: String? = null,
        ): AudioProbeMetrics =
            AudioProbeMetrics(
                initStatus = status,
                startedAtMs = startedAtMs,
                finishedAtMs = finishedAtMs,
                bluetoothRouteAtCapture = routeSnapshot.proof,
                errorMessage = errorMessage,
            )
    }
}

internal class AudioProbeMetricsRecorder(
    startedAtMs: Long,
    routeSnapshot: RelayAudioRouteSnapshot,
) {
    private var metrics = AudioProbeMetrics(
        initStatus = AudioProbeInitStatus.NOT_STARTED,
        startedAtMs = startedAtMs,
        finishedAtMs = startedAtMs,
        bluetoothRouteAtCapture = routeSnapshot.proof,
    )
    private var absoluteAmplitudeTotal = 0f

    fun markStarted(
        source: String,
        sampleRateHz: Int,
        windowSize: Int,
        minBufferSize: Int,
    ) {
        metrics = metrics.copy(
            initStatus = AudioProbeInitStatus.STARTED,
            audioSource = source,
            sampleRateHz = sampleRateHz,
            windowSize = windowSize,
            minBufferSize = minBufferSize,
        )
    }

    fun recordRead(buffer: ShortArray, readSize: Int) {
        if (readSize <= 0) {
            recordReadError()
            return
        }

        var nonZeroFrames = 0
        var peak = metrics.peakAmplitude
        var absoluteTotal = 0f
        val safeReadSize = minOf(readSize, buffer.size)
        for (index in 0 until safeReadSize) {
            val normalized = abs(buffer[index].toFloat()) / Short.MAX_VALUE.toFloat()
            if (normalized > 0f) {
                nonZeroFrames += 1
            }
            absoluteTotal += normalized
            peak = maxOf(peak, normalized)
        }

        absoluteAmplitudeTotal += absoluteTotal
        val framesRead = metrics.framesRead + safeReadSize
        metrics = metrics.copy(
            framesRead = framesRead,
            nonZeroFrames = metrics.nonZeroFrames + nonZeroFrames,
            peakAmplitude = peak,
            noiseFloorEstimate = if (framesRead == 0) 0f else absoluteAmplitudeTotal / framesRead.toFloat(),
        )
    }

    fun recordReadError() {
        metrics = metrics.copy(readErrorCount = metrics.readErrorCount + 1)
    }

    fun markFailure(status: AudioProbeInitStatus, message: String? = null) {
        metrics = metrics.copy(initStatus = status, errorMessage = message)
    }

    fun finish(finishedAtMs: Long): AudioProbeMetrics =
        metrics.copy(
            finishedAtMs = finishedAtMs,
            // Inline decision: the blueprint forbids raw audio by default, so the probe only exposes summaries.
            rawAudioPersisted = false,
            rawAudioPath = null,
        )
}
