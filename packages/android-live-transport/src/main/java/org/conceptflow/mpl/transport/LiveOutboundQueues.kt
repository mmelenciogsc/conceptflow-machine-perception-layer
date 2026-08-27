// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import java.io.Closeable
import java.util.ArrayDeque
import org.conceptflow.mpl.v1.SensorStreamEnvelope

data class LiveOutboundQueueSnapshot(
    val pendingCameraFrames: Int,
    val pendingImuBatches: Int,
    val pendingMicrophoneChunks: Int,
    val pendingTouchEvents: Int,
    val droppedCameraFrames: Long,
    val droppedImuBatches: Long,
    val droppedMicrophoneChunks: Long,
    val touchOverflowEvents: Long,
)

/**
 * Bounded application-side queues. Camera replacement is atomic at a complete-frame boundary;
 * IMU pressure evicts the oldest whole batch. Microphone preserves a short continuity window and
 * rejects new chunks on overflow. Touch never evicts accepted input. Realtime dequeue prioritizes
 * touch, then alternates microphone and IMU so continuous audio cannot starve pose batches.
 */
class LiveOutboundQueues(
    private val maximumImuBatches: Int = 8,
    private val maximumMicrophoneChunks: Int = 8,
    private val maximumTouchEvents: Int = 64,
) : Closeable {
    private var pendingCamera: List<SensorStreamEnvelope>? = null
    private val pendingImu = ArrayDeque<SensorStreamEnvelope>()
    private val pendingMicrophone = ArrayDeque<SensorStreamEnvelope>()
    private val pendingTouch = ArrayDeque<SensorStreamEnvelope>()
    private var droppedCameraFrames = 0L
    private var droppedImuBatches = 0L
    private var droppedMicrophoneChunks = 0L
    private var touchOverflowEvents = 0L
    private var microphoneTurn = true
    private var closed = false

    init {
        require(maximumImuBatches in 1..64) { "maximumImuBatches is outside its bound" }
        require(maximumMicrophoneChunks in 1..64) { "maximumMicrophoneChunks is outside its bound" }
        require(maximumTouchEvents in 1..256) { "maximumTouchEvents is outside its bound" }
    }

    @Synchronized
    fun offerCameraFrame(chunks: List<SensorStreamEnvelope>): Boolean {
        check(!closed) { "outbound queues are closed" }
        val immutable = validateCameraFrame(chunks)
        if (pendingCamera != null) droppedCameraFrames = Math.addExact(droppedCameraFrames, 1L)
        pendingCamera = immutable
        (this as java.lang.Object).notifyAll()
        return true
    }

    @Synchronized
    fun offerImu(batch: SensorStreamEnvelope): Boolean {
        check(!closed) { "outbound queues are closed" }
        require(batch.hasImuBatch() && !batch.hasMicrophoneChunk()) { "only IMU batches are accepted" }
        require(batch.sessionId.isNotBlank() && batch.leaseId.isNotBlank()) { "IMU binding is missing" }
        if (pendingImu.size == maximumImuBatches) {
            pendingImu.removeFirst()
            droppedImuBatches = Math.addExact(droppedImuBatches, 1L)
        }
        pendingImu.addLast(batch)
        (this as java.lang.Object).notifyAll()
        return true
    }

    @Synchronized
    fun offerMicrophone(chunk: SensorStreamEnvelope): Boolean {
        check(!closed) { "outbound queues are closed" }
        require(chunk.hasMicrophoneChunk() && !chunk.hasImuBatch()) { "only microphone chunks are accepted" }
        require(chunk.sessionId.isNotBlank() && chunk.leaseId.isNotBlank()) { "microphone binding is missing" }
        require(chunk.microphoneChunk.audioData.size() in 1..MAXIMUM_MICROPHONE_CHUNK_BYTES) {
            "microphone chunk size is outside its bound"
        }
        if (pendingMicrophone.size == maximumMicrophoneChunks) {
            droppedMicrophoneChunks = Math.addExact(droppedMicrophoneChunks, 1L)
            return false
        }
        pendingMicrophone.addLast(chunk)
        (this as java.lang.Object).notifyAll()
        return true
    }

    /** Touch is never evicted or overwritten. A full queue is a surfaced transport fault. */
    @Synchronized
    fun offerTouch(event: SensorStreamEnvelope): Boolean {
        check(!closed) { "outbound queues are closed" }
        require(event.hasTouchEvent()) { "only touch events are accepted" }
        require(event.sessionId.isNotBlank() && event.leaseId.isNotBlank()) { "touch binding is missing" }
        if (pendingTouch.size == maximumTouchEvents) {
            touchOverflowEvents = Math.addExact(touchOverflowEvents, 1L)
            return false
        }
        pendingTouch.addLast(event)
        (this as java.lang.Object).notifyAll()
        return true
    }

    @Synchronized
    fun pollCameraFrame(): List<SensorStreamEnvelope>? = pendingCamera.also { pendingCamera = null }

    @Synchronized
    fun pollImu(): SensorStreamEnvelope? = if (pendingImu.isEmpty()) null else pendingImu.removeFirst()

    @Synchronized
    fun pollRealtime(): SensorStreamEnvelope? {
        if (pendingTouch.isNotEmpty()) return pendingTouch.removeFirst()
        val hasMicrophone = pendingMicrophone.isNotEmpty()
        val hasImu = pendingImu.isNotEmpty()
        if (hasMicrophone && (!hasImu || microphoneTurn)) {
            microphoneTurn = false
            return pendingMicrophone.removeFirst()
        }
        if (hasImu) {
            microphoneTurn = true
            return pendingImu.removeFirst()
        }
        return null
    }

    @Synchronized
    fun awaitCameraFrame(timeoutMs: Long): List<SensorStreamEnvelope>? {
        require(timeoutMs in 1..30_000) { "timeoutMs is outside its bound" }
        if (pendingCamera == null && !closed) (this as java.lang.Object).wait(timeoutMs)
        return pollCameraFrame()
    }

    @Synchronized
    fun awaitImu(timeoutMs: Long): SensorStreamEnvelope? {
        require(timeoutMs in 1..30_000) { "timeoutMs is outside its bound" }
        if (pendingImu.isEmpty() && !closed) (this as java.lang.Object).wait(timeoutMs)
        return pollImu()
    }

    @Synchronized
    fun awaitRealtime(timeoutMs: Long): SensorStreamEnvelope? {
        require(timeoutMs in 1..30_000) { "timeoutMs is outside its bound" }
        if (pendingMicrophone.isEmpty() && pendingImu.isEmpty() && pendingTouch.isEmpty() && !closed) {
            (this as java.lang.Object).wait(timeoutMs)
        }
        return pollRealtime()
    }

    @Synchronized
    fun snapshot(): LiveOutboundQueueSnapshot = LiveOutboundQueueSnapshot(
        pendingCameraFrames = if (pendingCamera == null) 0 else 1,
        pendingImuBatches = pendingImu.size,
        pendingMicrophoneChunks = pendingMicrophone.size,
        pendingTouchEvents = pendingTouch.size,
        droppedCameraFrames = droppedCameraFrames,
        droppedImuBatches = droppedImuBatches,
        droppedMicrophoneChunks = droppedMicrophoneChunks,
        touchOverflowEvents = touchOverflowEvents,
    )

    @Synchronized
    fun reset() {
        pendingCamera = null
        pendingImu.clear()
        pendingMicrophone.clear()
        pendingTouch.clear()
        microphoneTurn = true
    }

    @Synchronized
    override fun close() {
        closed = true
        reset()
        (this as java.lang.Object).notifyAll()
    }

    private fun validateCameraFrame(chunks: List<SensorStreamEnvelope>): List<SensorStreamEnvelope> {
        require(chunks.isNotEmpty() && chunks.size <= MAXIMUM_CAMERA_CHUNKS) {
            "camera frame chunk count is outside its bound"
        }
        val first = chunks.first()
        require(first.hasCameraChunk() && !first.hasMicrophoneChunk()) { "only camera chunks are accepted" }
        val frameId = first.cameraChunk.frameId
        val expectedCount = first.cameraChunk.chunkCount
        require(frameId > 0 && expectedCount == chunks.size) { "camera frame chunk metadata is inconsistent" }
        require(first.cameraChunk.chunkIndex == 0 && first.cameraChunk.hasFrameMetadata()) {
            "camera frame must begin with metadata-bearing chunk zero"
        }
        require(first.sessionId.isNotBlank() && first.leaseId.isNotBlank()) { "camera binding is missing" }
        chunks.forEachIndexed { index, envelope ->
            val chunk = envelope.cameraChunk
            require(envelope.hasCameraChunk() && !envelope.hasMicrophoneChunk()) {
                "camera frame contains a non-camera record"
            }
            require(envelope.sessionId == first.sessionId && envelope.leaseId == first.leaseId) {
                "camera frame changes session binding"
            }
            require(chunk.frameId == frameId && chunk.chunkCount == expectedCount && chunk.chunkIndex == index) {
                "camera frame chunk ordering is inconsistent"
            }
            if (index > 0) require(!chunk.hasFrameMetadata()) {
                "only camera chunk zero may carry frame metadata"
            }
        }
        return chunks.toList()
    }

    companion object {
        private const val MAXIMUM_CAMERA_CHUNKS = 256
        private const val MAXIMUM_MICROPHONE_CHUNK_BYTES = 64 * 1_024
    }
}
