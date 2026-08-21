// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

data class StreamDiagnosticSnapshot(
    val durationMillis: Long,
    val cameraFrames: Long,
    val cameraBytes: Long,
    val imuSamples: Long,
    val imuSignalSamples: Long,
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
    private var imuSamples = 0L
    private var imuSignalSamples = 0L
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
    fun recordImuSample(hasNonZeroSignal: Boolean): Boolean {
        if (!accepting) return false
        imuSamples += 1L
        if (hasNonZeroSignal) imuSignalSamples += 1L
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
            imuSamples = imuSamples,
            imuSignalSamples = imuSignalSamples,
            microphoneChunks = microphoneChunks,
            microphoneBytes = microphoneBytes,
            microphoneNonZeroSamples = microphoneNonZeroSamples,
            microphonePeakAbsolute = microphonePeakAbsolute,
        ).also { finalSnapshot = it }
    }
}
