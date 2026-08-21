// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

data class StreamDiagnosticSnapshot(
    val durationMillis: Long,
    val cameraFrames: Long,
    val cameraBytes: Long,
    val cameraFramesAnalyzed: Long,
    val cameraFramesDroppedDark: Long,
    val cameraFramesDroppedBlurry: Long,
    val cameraFramesDroppedCadence: Long,
    val cameraMotionTierSamples: Long,
    val imuSamples: Long,
    val imuSignalSamples: Long,
    val imuObservedSamplesPerSecond: Double,
    val imuMaximumGapMillis: Double,
    val microphoneChunks: Long,
    val microphoneBytes: Long,
    val microphoneNonZeroSamples: Long,
    val microphonePeakAbsolute: Int,
) {
    val passed: Boolean
        get() = cameraFrames > 0L && imuSamples > 0L && microphoneChunks > 0L
}

class StreamDiagnosticSession(private val startedMonotonicNs: Long) {
    private var accepting = true
    private var cameraFrames = 0L
    private var cameraBytes = 0L
    private var cameraFramesAnalyzed = 0L
    private var cameraFramesDroppedDark = 0L
    private var cameraFramesDroppedBlurry = 0L
    private var cameraFramesDroppedCadence = 0L
    private var cameraMotionTierSamples = 0L
    private var imuSamples = 0L
    private var imuSignalSamples = 0L
    private var firstImuTimestampNanos = 0L
    private var lastImuTimestampNanos = 0L
    private var maximumImuGapNanos = 0L
    private var timedImuSamples = 0L
    private var microphoneChunks = 0L
    private var microphoneBytes = 0L
    private var microphoneNonZeroSamples = 0L
    private var microphonePeakAbsolute = 0
    private var finalSnapshot: StreamDiagnosticSnapshot? = null

    init {
        require(startedMonotonicNs >= 0L)
    }

    @Synchronized
    fun recordCameraFrame(payloadBytes: Int): Boolean {
        require(payloadBytes > 0)
        if (!accepting) return false
        cameraFrames += 1L
        cameraBytes += payloadBytes
        return cameraFrames == 1L
    }

    @Synchronized
    fun recordCaptureGate(event: CaptureGateEvent) {
        if (!accepting) return
        cameraFramesAnalyzed += 1L
        if (event.targetFramesPerSecond >= 5.0) cameraMotionTierSamples += 1L
        when (event.dropReason) {
            FrameDropReason.DARK -> cameraFramesDroppedDark += 1L
            FrameDropReason.BLURRY -> cameraFramesDroppedBlurry += 1L
            FrameDropReason.CADENCE_SIMILAR -> cameraFramesDroppedCadence += 1L
            null -> Unit
        }
    }

    @Synchronized
    fun recordImuSample(hasNonZeroSignal: Boolean, timestampNanos: Long = 0L): Boolean {
        require(timestampNanos >= 0L)
        if (!accepting) return false
        imuSamples += 1L
        if (hasNonZeroSignal) imuSignalSamples += 1L
        if (timestampNanos > 0L) {
            if (firstImuTimestampNanos == 0L) {
                firstImuTimestampNanos = timestampNanos
                lastImuTimestampNanos = timestampNanos
                timedImuSamples = 1L
            } else if (timestampNanos > lastImuTimestampNanos) {
                timedImuSamples += 1L
                maximumImuGapNanos = maxOf(maximumImuGapNanos, timestampNanos - lastImuTimestampNanos)
                lastImuTimestampNanos = timestampNanos
            }
        }
        return imuSamples == 1L
    }

    @Synchronized
    fun recordMicrophoneChunk(pcm16LittleEndian: ByteArray): Boolean {
        require(pcm16LittleEndian.isNotEmpty())
        if (!accepting) return false
        microphoneChunks += 1L
        microphoneBytes += pcm16LittleEndian.size
        for (offset in 0 until pcm16LittleEndian.size - 1 step 2) {
            val low = pcm16LittleEndian[offset].toInt() and 0xff
            val high = pcm16LittleEndian[offset + 1].toInt()
            val sample = ((high shl 8) or low).toShort().toInt()
            if (sample != 0) microphoneNonZeroSamples += 1L
            microphonePeakAbsolute = maxOf(microphonePeakAbsolute, kotlin.math.abs(sample))
        }
        return microphoneChunks == 1L
    }

    @Synchronized
    fun finish(finishedMonotonicNs: Long): StreamDiagnosticSnapshot {
        finalSnapshot?.let { return it }
        accepting = false
        return StreamDiagnosticSnapshot(
            durationMillis = ((finishedMonotonicNs - startedMonotonicNs).coerceAtLeast(0L)) / 1_000_000L,
            cameraFrames = cameraFrames,
            cameraBytes = cameraBytes,
            cameraFramesAnalyzed = cameraFramesAnalyzed,
            cameraFramesDroppedDark = cameraFramesDroppedDark,
            cameraFramesDroppedBlurry = cameraFramesDroppedBlurry,
            cameraFramesDroppedCadence = cameraFramesDroppedCadence,
            cameraMotionTierSamples = cameraMotionTierSamples,
            imuSamples = imuSamples,
            imuSignalSamples = imuSignalSamples,
            imuObservedSamplesPerSecond = observedImuSamplesPerSecond(),
            imuMaximumGapMillis = maximumImuGapNanos / 1_000_000.0,
            microphoneChunks = microphoneChunks,
            microphoneBytes = microphoneBytes,
            microphoneNonZeroSamples = microphoneNonZeroSamples,
            microphonePeakAbsolute = microphonePeakAbsolute,
        ).also { finalSnapshot = it }
    }

    private fun observedImuSamplesPerSecond(): Double {
        if (timedImuSamples < 2L || lastImuTimestampNanos <= firstImuTimestampNanos) return 0.0
        return (timedImuSamples - 1L) * 1_000_000_000.0 / (lastImuTimestampNanos - firstImuTimestampNanos)
    }
}
