// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.core

import com.google.protobuf.ByteString
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.conceptflow.mpl.v1.AudioSampleEncoding
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.ImuBatch
import org.conceptflow.mpl.v1.MicrophoneChunk
import org.conceptflow.mpl.v1.RokidTouchAction
import org.conceptflow.mpl.v1.RokidTouchEvent
import org.conceptflow.mpl.v1.RokidTouchKey
import org.conceptflow.mpl.v1.SensorStreamEnvelope

data class GlassesIngressLimits(
    val maximumCameraBytes: Int = 2 * 1_024 * 1_024,
    val maximumCameraChunkBytes: Int = 64 * 1_024,
    val maximumCameraChunks: Int = 2_048,
    val cameraAssemblyTimeoutNanos: Long = 2_000_000_000L,
    val maximumImuSamplesPerBatch: Int = 64,
    val maximumMicrophoneChunkBytes: Int = 64 * 1_024,
    val maximumMicrophoneChunks: Int = 8,
    val maximumTouchEvents: Int = 128,
) {
    init {
        require(maximumCameraBytes in 1..8 * 1_024 * 1_024)
        require(maximumCameraChunkBytes in 1_024..256 * 1_024)
        require(maximumCameraChunks in 1..4_096)
        require(cameraAssemblyTimeoutNanos in 20_000_000L..10_000_000_000L)
        require(maximumImuSamplesPerBatch in 1..64)
        require(maximumMicrophoneChunkBytes in 256..256 * 1_024)
        require(maximumMicrophoneChunks in 1..64)
        require(maximumTouchEvents in 1..512)
    }
}

enum class StreamIngressDisposition {
    CAMERA_PARTIAL,
    CAMERA_READY,
    IMU_READY,
    MICROPHONE_READY,
    TOUCH_READY,
    REJECTED_IDENTITY,
    REJECTED_ORDER,
    REJECTED_MALFORMED,
    REJECTED_UNAUTHORIZED,
    REJECTED_OVERFLOW,
}

data class GlassesIngressStatistics(
    val acceptedEnvelopes: Long,
    val rejectedEnvelopes: Long,
    val incompleteCameraFramesDropped: Long,
    val unreadCameraFramesReplaced: Long,
    val unreadMicrophoneChunksReplaced: Long,
    val microphoneOverflowChunks: Long,
    val touchOverflowEvents: Long,
)

/**
 * Bounded receiver for an authenticated lease. Sender monotonic time is kept
 * for correlation, but is never compared directly with this device's clock.
 */
class GlassesStreamIngress(
    private val expectedSessionId: String,
    private val expectedLeaseId: String,
    private val microphoneAuthorized: Boolean,
    private val clock: HostClock,
    private val limits: GlassesIngressLimits = GlassesIngressLimits(),
) {
    private var lastEnvelopeSequence = 0L
    private var lastCompletedCameraFrame = 0L
    private var lastImuSequence = 0L
    private var lastImuBatchId = 0L
    private var lastMicrophoneChunkId = 0L
    private var lastTouchEventId = 0L
    private var cameraAssembly: CameraAssembly? = null
    private var latestCamera: FramePayload? = null
    private var latestImu: ImuBatch? = null
    private val microphoneChunks = ArrayDeque<MicrophoneChunk>()
    private val touchEvents = ArrayDeque<RokidTouchEvent>()
    private var accepted = 0L
    private var rejected = 0L
    private var incompleteFramesDropped = 0L
    private var unreadFramesReplaced = 0L
    private var unreadMicrophoneChunksReplaced = 0L
    private var microphoneOverflowChunks = 0L
    private var touchOverflowEvents = 0L

    init {
        require(expectedSessionId.isNotBlank() && expectedSessionId.length <= 128)
        require(expectedLeaseId.isNotBlank() && expectedLeaseId.length <= 128)
    }

    @Synchronized
    fun accept(envelope: SensorStreamEnvelope): StreamIngressDisposition {
        expireCameraAssembly()
        if (envelope.sessionId != expectedSessionId || envelope.leaseId != expectedLeaseId) {
            return reject(StreamIngressDisposition.REJECTED_IDENTITY)
        }
        if (envelope.sequenceId == 0L || envelope.sequenceId <= lastEnvelopeSequence) {
            return reject(StreamIngressDisposition.REJECTED_ORDER)
        }
        lastEnvelopeSequence = envelope.sequenceId
        val result = when (envelope.payloadCase) {
            SensorStreamEnvelope.PayloadCase.CAMERA_CHUNK -> acceptCamera(envelope)
            SensorStreamEnvelope.PayloadCase.IMU_BATCH -> acceptImu(envelope.imuBatch)
            SensorStreamEnvelope.PayloadCase.MICROPHONE_CHUNK -> acceptMicrophone(envelope.microphoneChunk)
            SensorStreamEnvelope.PayloadCase.TOUCH_EVENT -> acceptTouch(envelope.touchEvent)
            else -> StreamIngressDisposition.REJECTED_MALFORMED
        }
        if (result.name.startsWith("REJECTED_")) rejected += 1L else accepted += 1L
        return result
    }

    /**
     * Accepts a nested sensor record after LiveLinkEnvelope mutual-TLS binding and per-lane
     * sequence validation have succeeded. The legacy cross-lane sequence is deliberately not
     * synthesized; camera chunk and IMU batch/sample ordering remain fully enforced below.
     */
    @Synchronized
    fun acceptAuthenticatedLane(envelope: SensorStreamEnvelope): StreamIngressDisposition {
        expireCameraAssembly()
        if (envelope.sessionId != expectedSessionId || envelope.leaseId != expectedLeaseId) {
            return reject(StreamIngressDisposition.REJECTED_IDENTITY)
        }
        val result = when (envelope.payloadCase) {
            SensorStreamEnvelope.PayloadCase.CAMERA_CHUNK -> acceptCamera(envelope)
            SensorStreamEnvelope.PayloadCase.IMU_BATCH -> acceptImu(envelope.imuBatch)
            SensorStreamEnvelope.PayloadCase.MICROPHONE_CHUNK -> acceptMicrophone(envelope.microphoneChunk)
            SensorStreamEnvelope.PayloadCase.TOUCH_EVENT -> acceptTouch(envelope.touchEvent)
            else -> StreamIngressDisposition.REJECTED_MALFORMED
        }
        if (result.name.startsWith("REJECTED_")) rejected += 1L else accepted += 1L
        return result
    }

    @Synchronized
    fun takeLatestCamera(): FramePayload? = latestCamera.also { latestCamera = null }

    @Synchronized
    fun takeLatestImu(): ImuBatch? = latestImu.also { latestImu = null }

    @Synchronized
    fun takeLatestMicrophone(): MicrophoneChunk? =
        if (microphoneChunks.isEmpty()) null else microphoneChunks.removeFirst()

    @Synchronized
    fun takeTouchEvents(maximum: Int = limits.maximumTouchEvents): List<RokidTouchEvent> {
        require(maximum in 1..limits.maximumTouchEvents)
        val result = ArrayList<RokidTouchEvent>(minOf(maximum, touchEvents.size))
        repeat(minOf(maximum, touchEvents.size)) { result += touchEvents.removeFirst() }
        return result
    }

    @Synchronized
    fun statistics(): GlassesIngressStatistics = GlassesIngressStatistics(
        acceptedEnvelopes = accepted,
        rejectedEnvelopes = rejected,
        incompleteCameraFramesDropped = incompleteFramesDropped,
        unreadCameraFramesReplaced = unreadFramesReplaced,
        unreadMicrophoneChunksReplaced = unreadMicrophoneChunksReplaced,
        microphoneOverflowChunks = microphoneOverflowChunks,
        touchOverflowEvents = touchOverflowEvents,
    )

    private fun acceptCamera(envelope: SensorStreamEnvelope): StreamIngressDisposition {
        val chunk = envelope.cameraChunk
        if (chunk.frameId == 0L || chunk.chunkCount == 0 || chunk.chunkCount > limits.maximumCameraChunks ||
            chunk.chunkIndex >= chunk.chunkCount || chunk.totalPayloadBytes == 0L ||
            chunk.captureMonotonicTimestampNs == 0L ||
            chunk.totalPayloadBytes > limits.maximumCameraBytes || chunk.chunkData.isEmpty ||
            chunk.chunkData.size() > limits.maximumCameraChunkBytes || chunk.frameId <= lastCompletedCameraFrame
        ) {
            return StreamIngressDisposition.REJECTED_MALFORMED
        }
        var assembly = cameraAssembly
        if (assembly == null || assembly.frameId != chunk.frameId) {
            if (assembly != null && chunk.frameId < assembly.frameId) {
                return StreamIngressDisposition.REJECTED_ORDER
            }
            if (chunk.chunkIndex != 0 || !chunk.hasFrameMetadata()) {
                if (assembly != null && chunk.frameId > assembly.frameId) {
                    cameraAssembly = null
                    incompleteFramesDropped += 1L
                }
                return StreamIngressDisposition.REJECTED_ORDER
            }
            if (assembly != null) {
                cameraAssembly = null
                incompleteFramesDropped += 1L
            }
            val metadata = chunk.frameMetadata
            if (metadata.frameId != chunk.frameId || !metadata.frameData.isEmpty ||
                metadata.image.payloadBytes != chunk.totalPayloadBytes ||
                metadata.captureMonotonicTimestampNs != chunk.captureMonotonicTimestampNs
            ) {
                return StreamIngressDisposition.REJECTED_MALFORMED
            }
            assembly = CameraAssembly(
                frameId = chunk.frameId,
                chunkCount = chunk.chunkCount,
                totalBytes = chunk.totalPayloadBytes.toInt(),
                metadata = metadata,
                startedAtNanos = clock.nowNanos(),
            )
            cameraAssembly = assembly
        }
        if (chunk.chunkIndex != assembly.nextChunkIndex || chunk.chunkCount != assembly.chunkCount ||
            chunk.totalPayloadBytes.toInt() != assembly.totalBytes ||
            chunk.captureMonotonicTimestampNs != assembly.metadata.captureMonotonicTimestampNs ||
            chunk.chunkIndex > 0 && chunk.hasFrameMetadata() ||
            assembly.bytes.size() + chunk.chunkData.size() > assembly.totalBytes
        ) {
            cameraAssembly = null
            incompleteFramesDropped += 1L
            return StreamIngressDisposition.REJECTED_ORDER
        }
        chunk.chunkData.writeTo(assembly.bytes)
        assembly.nextChunkIndex += 1
        if (assembly.nextChunkIndex < assembly.chunkCount) return StreamIngressDisposition.CAMERA_PARTIAL
        cameraAssembly = null
        val bytes = assembly.bytes.toByteArray()
        if (bytes.size != assembly.totalBytes || !MessageDigest.isEqual(
                MessageDigest.getInstance("SHA-256").digest(bytes),
                assembly.metadata.image.sha256.toByteArray(),
            )
        ) {
            incompleteFramesDropped += 1L
            return StreamIngressDisposition.REJECTED_MALFORMED
        }
        if (latestCamera != null) unreadFramesReplaced += 1L
        latestCamera = assembly.metadata.toBuilder().setFrameData(ByteString.copyFrom(bytes)).build()
        lastCompletedCameraFrame = assembly.frameId
        return StreamIngressDisposition.CAMERA_READY
    }

    private fun acceptImu(batch: ImuBatch): StreamIngressDisposition {
        if (batch.leaseId != expectedLeaseId || batch.batchId == 0L || batch.batchId <= lastImuBatchId ||
            batch.samplesCount !in 1..limits.maximumImuSamplesPerBatch
        ) {
            return StreamIngressDisposition.REJECTED_MALFORMED
        }
        var previousSequence = lastImuSequence
        var previousTimestamp = 0L
        for (sample in batch.samplesList) {
            val timestamp = sample.pose.monotonicTimestampNs
            if (sample.sequenceId <= previousSequence || timestamp == 0L || timestamp <= previousTimestamp) {
                return StreamIngressDisposition.REJECTED_ORDER
            }
            previousSequence = sample.sequenceId
            previousTimestamp = timestamp
        }
        lastImuSequence = previousSequence
        lastImuBatchId = batch.batchId
        latestImu = batch
        return StreamIngressDisposition.IMU_READY
    }

    private fun acceptMicrophone(chunk: MicrophoneChunk): StreamIngressDisposition {
        if (!microphoneAuthorized) return StreamIngressDisposition.REJECTED_UNAUTHORIZED
        if (chunk.leaseId != expectedLeaseId || chunk.chunkId == 0L || chunk.chunkId <= lastMicrophoneChunkId ||
            chunk.captureMonotonicTimestampNs == 0L || chunk.sampleRateHz !in 8_000..48_000 ||
            chunk.channelCount !in 1..2 ||
            chunk.encoding != AudioSampleEncoding.AUDIO_SAMPLE_ENCODING_PCM_S16LE ||
            chunk.audioData.isEmpty || chunk.audioData.size() > limits.maximumMicrophoneChunkBytes ||
            chunk.audioData.size() % (chunk.channelCount * Short.SIZE_BYTES) != 0
        ) {
            return StreamIngressDisposition.REJECTED_MALFORMED
        }
        lastMicrophoneChunkId = chunk.chunkId
        if (microphoneChunks.size == limits.maximumMicrophoneChunks) {
            microphoneOverflowChunks += 1L
            return StreamIngressDisposition.REJECTED_OVERFLOW
        }
        microphoneChunks.addLast(chunk)
        return StreamIngressDisposition.MICROPHONE_READY
    }

    private fun acceptTouch(event: RokidTouchEvent): StreamIngressDisposition {
        if (event.eventId == 0L || event.eventId <= lastTouchEventId ||
            event.observedMonotonicTimestampNs == 0L || event.sourceUptimeMs == 0L ||
            event.key == RokidTouchKey.ROKID_TOUCH_KEY_UNSPECIFIED ||
            event.action == RokidTouchAction.ROKID_TOUCH_ACTION_UNSPECIFIED ||
            event.repeatCount < 0 || event.scanCode <= 0
        ) return StreamIngressDisposition.REJECTED_MALFORMED
        if (touchEvents.size == limits.maximumTouchEvents) {
            touchOverflowEvents += 1L
            return StreamIngressDisposition.REJECTED_OVERFLOW
        }
        lastTouchEventId = event.eventId
        touchEvents.addLast(event)
        return StreamIngressDisposition.TOUCH_READY
    }

    private fun expireCameraAssembly() {
        val assembly = cameraAssembly ?: return
        val now = clock.nowNanos()
        if (now >= assembly.startedAtNanos && now - assembly.startedAtNanos >= limits.cameraAssemblyTimeoutNanos) {
            cameraAssembly = null
            incompleteFramesDropped += 1L
        }
    }

    private fun reject(disposition: StreamIngressDisposition): StreamIngressDisposition {
        rejected += 1L
        return disposition
    }

    private data class CameraAssembly(
        val frameId: Long,
        val chunkCount: Int,
        val totalBytes: Int,
        val metadata: FramePayload,
        val startedAtNanos: Long,
        val bytes: ByteArrayOutputStream = ByteArrayOutputStream(totalBytes),
        var nextChunkIndex: Int = 0,
    )
}
