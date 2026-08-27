// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.conceptflow.mpl.v1.FramePayload

/** App-private persistence seam used after camera/IMU/microphone admission gates. */
interface RokidSensorSpool {
    fun beginSession(sessionId: String) = Unit
    fun storeCamera(lease: ActiveStreamLease, frame: FramePayload): Boolean
    fun storeImu(lease: ActiveStreamLease, batch: ImuTransmissionBatch): Boolean
    fun storeMicrophone(lease: ActiveStreamLease, chunk: PcmAudioChunk): Boolean
    fun metricsSnapshot(): LegacySpoolMetricsSnapshot? = null
}

data class LatencyNanoseconds(
    val samples: Int,
    val p50: Long?,
    val p95: Long?,
    val p99: Long?,
)

/** Aggregate-only evidence for the temporary filesystem handoff. */
data class LegacySpoolMetricsSnapshot(
    val cameraRecords: Long,
    val imuRecords: Long,
    val microphoneRecords: Long,
    val artifactFilesWritten: Long,
    val artifactBytesWritten: Long,
    val manifestWrites: Long,
    val manifestBytesWritten: Long,
    val recoveryStateBytesWritten: Long,
    val artifactReads: Long,
    val artifactBytesRead: Long,
    val acknowledgements: Long,
    val cameraTransform: LatencyNanoseconds,
    val cameraStore: LatencyNanoseconds,
    val imuStore: LatencyNanoseconds,
    val microphoneStore: LatencyNanoseconds,
    val manifestPersist: LatencyNanoseconds,
    val artifactRead: LatencyNanoseconds,
)

/** Fixed-memory percentile collector. Values are aggregate timing only. */
class BoundedDurationSamples(private val capacity: Int = 2_048) {
    private val values = LongArray(capacity)
    private var count = 0
    private var cursor = 0

    init {
        require(capacity in 1..16_384)
    }

    @Synchronized
    fun record(durationNanos: Long) {
        require(durationNanos >= 0L)
        values[cursor] = durationNanos
        cursor = (cursor + 1) % capacity
        if (count < capacity) count += 1
    }

    @Synchronized
    fun snapshot(): LatencyNanoseconds {
        if (count == 0) return LatencyNanoseconds(0, null, null, null)
        val ordered = values.copyOf(count).sortedArray()
        return LatencyNanoseconds(
            count,
            percentile(ordered, 0.50),
            percentile(ordered, 0.95),
            percentile(ordered, 0.99),
        )
    }

    @Synchronized
    fun reset() {
        count = 0
        cursor = 0
    }

    private fun percentile(ordered: LongArray, fraction: Double): Long {
        val index = kotlin.math.ceil(fraction * ordered.size).toInt().coerceIn(1, ordered.size) - 1
        return ordered[index]
    }
}

class LegacySpoolMetrics {
    private var cameraRecords = 0L
    private var imuRecords = 0L
    private var microphoneRecords = 0L
    private var artifactFilesWritten = 0L
    private var artifactBytesWritten = 0L
    private var manifestWrites = 0L
    private var manifestBytesWritten = 0L
    private var recoveryStateBytesWritten = 0L
    private var artifactReads = 0L
    private var artifactBytesRead = 0L
    private var acknowledgements = 0L
    private val cameraTransform = BoundedDurationSamples()
    private val cameraStore = BoundedDurationSamples()
    private val imuStore = BoundedDurationSamples()
    private val microphoneStore = BoundedDurationSamples()
    private val manifestPersist = BoundedDurationSamples()
    private val artifactRead = BoundedDurationSamples()

    @Synchronized
    fun reset() {
        cameraRecords = 0L
        imuRecords = 0L
        microphoneRecords = 0L
        artifactFilesWritten = 0L
        artifactBytesWritten = 0L
        manifestWrites = 0L
        manifestBytesWritten = 0L
        recoveryStateBytesWritten = 0L
        artifactReads = 0L
        artifactBytesRead = 0L
        acknowledgements = 0L
        cameraTransform.reset()
        cameraStore.reset()
        imuStore.reset()
        microphoneStore.reset()
        manifestPersist.reset()
        artifactRead.reset()
    }

    @Synchronized
    fun recordCamera(transformNanos: Long, totalNanos: Long, artifactBytes: Int) {
        cameraRecords = Math.addExact(cameraRecords, 1L)
        artifactFilesWritten = Math.addExact(artifactFilesWritten, 1L)
        artifactBytesWritten = Math.addExact(artifactBytesWritten, artifactBytes.toLong())
        cameraTransform.record(transformNanos)
        cameraStore.record(totalNanos)
    }

    @Synchronized
    fun recordImu(totalNanos: Long) {
        imuRecords = Math.addExact(imuRecords, 1L)
        imuStore.record(totalNanos)
    }

    @Synchronized
    fun recordMicrophone(totalNanos: Long, artifactBytes: Int) {
        microphoneRecords = Math.addExact(microphoneRecords, 1L)
        artifactFilesWritten = Math.addExact(artifactFilesWritten, 1L)
        artifactBytesWritten = Math.addExact(artifactBytesWritten, artifactBytes.toLong())
        microphoneStore.record(totalNanos)
    }

    @Synchronized
    fun recordManifestPersist(jsonBytes: Int, stateBytes: Int, durationNanos: Long) {
        manifestWrites = Math.addExact(manifestWrites, 1L)
        manifestBytesWritten = Math.addExact(manifestBytesWritten, jsonBytes.toLong())
        recoveryStateBytesWritten = Math.addExact(recoveryStateBytesWritten, stateBytes.toLong())
        manifestPersist.record(durationNanos)
    }

    @Synchronized
    fun recordArtifactRead(bytes: Int, durationNanos: Long) {
        artifactReads = Math.addExact(artifactReads, 1L)
        artifactBytesRead = Math.addExact(artifactBytesRead, bytes.toLong())
        artifactRead.record(durationNanos)
    }

    @Synchronized
    fun recordAcknowledgement(count: Int) {
        acknowledgements = Math.addExact(acknowledgements, count.toLong())
    }

    @Synchronized
    fun snapshot(): LegacySpoolMetricsSnapshot = LegacySpoolMetricsSnapshot(
        cameraRecords,
        imuRecords,
        microphoneRecords,
        artifactFilesWritten,
        artifactBytesWritten,
        manifestWrites,
        manifestBytesWritten,
        recoveryStateBytesWritten,
        artifactReads,
        artifactBytesRead,
        acknowledgements,
        cameraTransform.snapshot(),
        cameraStore.snapshot(),
        imuStore.snapshot(),
        microphoneStore.snapshot(),
        manifestPersist.snapshot(),
        artifactRead.snapshot(),
    )
}

data class SquareAspectFillTransform(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val scaledWidth: Int,
    val scaledHeight: Int,
    val cropLeft: Int,
    val cropTop: Int,
    val outputSize: Int,
) {
    init {
        require(sourceWidth > 0 && sourceHeight > 0 && outputSize > 0)
        require(scaledWidth >= outputSize && scaledHeight == outputSize)
        require(cropLeft >= 0 && cropLeft + outputSize <= scaledWidth)
        require(cropTop == 0)
    }

    val scaleX: Double get() = scaledWidth.toDouble() / sourceWidth
    val scaleY: Double get() = scaledHeight.toDouble() / sourceHeight

    companion object {
        fun centered(sourceWidth: Int, sourceHeight: Int, outputSize: Int = 640): SquareAspectFillTransform {
            require(sourceWidth >= sourceHeight) { "square aspect-fill input must be landscape or square" }
            require(sourceHeight > 0 && outputSize > 0)
            val scaledWidth = ((sourceWidth.toLong() * outputSize + sourceHeight / 2L) / sourceHeight)
                .toInt()
            require(scaledWidth >= outputSize)
            return SquareAspectFillTransform(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                scaledWidth = scaledWidth,
                scaledHeight = outputSize,
                cropLeft = (scaledWidth - outputSize) / 2,
                cropTop = 0,
                outputSize = outputSize,
            )
        }
    }
}
