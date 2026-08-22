// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import java.util.ArrayDeque
import org.conceptflow.mpl.v1.SensorStreamEnvelope

data class SensorStreamOutboxStatistics(
    val cameraFramesReplaced: Long,
    val imuBatchesReplaced: Long,
    val microphoneChunksReplaced: Long,
    val packetsTaken: Long,
)

/**
 * Small latest-wins queues between packetization and a non-blocking transport.
 * Calling [clear] on disconnect prevents replay under a new lease.
 */
class SensorStreamOutbox(private val clock: MonotonicClock) {
    private val camera = ArrayDeque<SensorStreamEnvelope>()
    private var cameraFrameId = 0L
    private var imuBatchId = 0L
    private var microphoneChunkId = 0L
    private var imu: SensorStreamEnvelope? = null
    private var microphone: SensorStreamEnvelope? = null
    private var nextLane = 0
    private var nextEnvelopeSequence = 0L
    private var cameraFramesReplaced = 0L
    private var imuBatchesReplaced = 0L
    private var microphoneChunksReplaced = 0L
    private var packetsTaken = 0L

    @Synchronized
    fun offerCameraFrame(packets: List<SensorStreamEnvelope>): Boolean {
        if (packets.isEmpty() || packets.any { !it.hasCameraChunk() }) return false
        val frameId = packets.first().cameraChunk.frameId
        val expectedChunks = packets.first().cameraChunk.chunkCount
        val sessionId = packets.first().sessionId
        val leaseId = packets.first().leaseId
        if (frameId == 0L || frameId <= cameraFrameId || sessionId.isBlank() || leaseId.isBlank() ||
            expectedChunks != packets.size ||
            packets.withIndex().any { (index, packet) ->
                packet.sessionId != sessionId || packet.leaseId != leaseId ||
                    packet.cameraChunk.frameId != frameId || packet.cameraChunk.chunkCount != expectedChunks ||
                    packet.cameraChunk.chunkIndex != index
            }
        ) {
            return false
        }
        if (camera.isNotEmpty()) cameraFramesReplaced += 1L
        camera.clear()
        camera.addAll(packets)
        cameraFrameId = frameId
        return true
    }

    @Synchronized
    fun offerImu(packet: SensorStreamEnvelope): Boolean {
        if (!packet.hasImuBatch() || packet.sessionId.isBlank() || packet.leaseId.isBlank() ||
            packet.imuBatch.leaseId != packet.leaseId || packet.imuBatch.batchId <= imuBatchId
        ) {
            return false
        }
        if (imu != null) imuBatchesReplaced += 1L
        imu = packet
        imuBatchId = packet.imuBatch.batchId
        return true
    }

    @Synchronized
    fun offerMicrophone(packet: SensorStreamEnvelope): Boolean {
        if (!packet.hasMicrophoneChunk() || packet.sessionId.isBlank() || packet.leaseId.isBlank() ||
            packet.microphoneChunk.leaseId != packet.leaseId ||
            packet.microphoneChunk.chunkId <= microphoneChunkId
        ) {
            return false
        }
        if (microphone != null) microphoneChunksReplaced += 1L
        microphone = packet
        microphoneChunkId = packet.microphoneChunk.chunkId
        return true
    }

    @Synchronized
    fun takeNext(): SensorStreamEnvelope? {
        repeat(LANE_COUNT) {
            val lane = nextLane
            nextLane = (nextLane + 1) % LANE_COUNT
            val packet = when (lane) {
                IMU_LANE -> imu.also { imu = null }
                MICROPHONE_LANE -> microphone.also { microphone = null }
                else -> if (camera.isEmpty()) null else camera.removeFirst()
            }
            if (packet != null) {
                packetsTaken += 1L
                return packet.toBuilder()
                    .setSequenceId(++nextEnvelopeSequence)
                    .setSentMonotonicTimestampNs(clock.nowNanos())
                    .build()
            }
        }
        return null
    }

    @Synchronized
    fun clear() {
        camera.clear()
        cameraFrameId = 0L
        imuBatchId = 0L
        microphoneChunkId = 0L
        imu = null
        microphone = null
        nextLane = 0
        nextEnvelopeSequence = 0L
    }

    @Synchronized
    fun statistics(): SensorStreamOutboxStatistics = SensorStreamOutboxStatistics(
        cameraFramesReplaced = cameraFramesReplaced,
        imuBatchesReplaced = imuBatchesReplaced,
        microphoneChunksReplaced = microphoneChunksReplaced,
        packetsTaken = packetsTaken,
    )

    private companion object {
        const val IMU_LANE = 0
        const val MICROPHONE_LANE = 1
        const val LANE_COUNT = 3
    }
}
