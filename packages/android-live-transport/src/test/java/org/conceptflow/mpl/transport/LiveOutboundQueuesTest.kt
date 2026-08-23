// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import com.google.protobuf.ByteString
import org.conceptflow.mpl.v1.CameraFrameChunk
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.ImuBatch
import org.conceptflow.mpl.v1.MicrophoneChunk
import org.conceptflow.mpl.v1.SensorStreamEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LiveOutboundQueuesTest {
    @Test
    fun `new camera frame atomically replaces the complete older frame`() {
        val queues = LiveOutboundQueues()
        queues.offerCameraFrame(frame(10, 3))
        queues.offerCameraFrame(frame(11, 2))

        val selected = requireNotNull(queues.pollCameraFrame())
        assertEquals(listOf(0, 1), selected.map { it.cameraChunk.chunkIndex })
        assertTrue(selected.all { it.cameraChunk.frameId == 11L })
        assertEquals(1L, queues.snapshot().droppedCameraFrames)
    }

    @Test
    fun `IMU pressure drops oldest whole batch and microphone is structurally rejected`() {
        val queues = LiveOutboundQueues(maximumImuBatches = 2)
        queues.offerImu(imu(1))
        queues.offerImu(imu(2))
        queues.offerImu(imu(3))

        assertEquals(2L, queues.pollImu()?.imuBatch?.batchId)
        assertEquals(3L, queues.pollImu()?.imuBatch?.batchId)
        assertEquals(1L, queues.snapshot().droppedImuBatches)

        val microphone = base().setMicrophoneChunk(
            MicrophoneChunk.newBuilder()
                .setLeaseId("lease")
                .setChunkId(1)
                .setAudioData(ByteString.copyFrom(byteArrayOf(1))),
        ).build()
        assertThrows(IllegalArgumentException::class.java) { queues.offerImu(microphone) }
        assertFalse(microphone.hasImuBatch())
    }

    @Test
    fun `partial or inconsistently bound camera frame is rejected`() {
        val queues = LiveOutboundQueues()
        val incomplete = frame(4, 2).take(1)
        assertThrows(IllegalArgumentException::class.java) { queues.offerCameraFrame(incomplete) }

        val mixed = frame(5, 2).toMutableList()
        mixed[1] = mixed[1].toBuilder().setLeaseId("other-lease").build()
        assertThrows(IllegalArgumentException::class.java) { queues.offerCameraFrame(mixed) }
    }

    @Test
    fun `waiting realtime consumer wakes for each IMU offer without camera polling delay`() {
        val queues = LiveOutboundQueues(maximumImuBatches = 8)
        val received = mutableListOf<Long>()
        val done = CountDownLatch(1)
        val consumer = Thread {
            repeat(20) {
                queues.awaitImu(1_000)?.let { received += it.imuBatch.batchId }
            }
            done.countDown()
        }
        consumer.start()
        repeat(20) { index ->
            queues.offerImu(imu(index + 1L))
            while (queues.snapshot().pendingImuBatches != 0) Thread.yield()
        }

        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals((1L..20L).toList(), received)
        assertEquals(0L, queues.snapshot().droppedImuBatches)
    }

    private fun frame(frameId: Long, count: Int): List<SensorStreamEnvelope> = (0 until count).map { index ->
        val chunk = CameraFrameChunk.newBuilder()
            .setFrameId(frameId)
            .setChunkIndex(index)
            .setChunkCount(count)
            .setTotalPayloadBytes(count.toLong())
            .setChunkData(ByteString.copyFrom(byteArrayOf(index.toByte())))
        if (index == 0) {
            chunk.frameMetadata = FramePayload.newBuilder()
                .setSessionId("session")
                .setStreamId("stream")
                .setFrameId(frameId)
                .setCaptureMonotonicTimestampNs(10_000 + frameId)
                .build()
        }
        base().setCameraChunk(chunk).build()
    }

    private fun imu(batchId: Long): SensorStreamEnvelope = base().setImuBatch(
        ImuBatch.newBuilder().setLeaseId("lease").setBatchId(batchId).setCreatedMonotonicTimestampNs(batchId),
    ).build()

    private fun base(): SensorStreamEnvelope.Builder = SensorStreamEnvelope.newBuilder()
        .setSessionId("session")
        .setLeaseId("lease")
}
