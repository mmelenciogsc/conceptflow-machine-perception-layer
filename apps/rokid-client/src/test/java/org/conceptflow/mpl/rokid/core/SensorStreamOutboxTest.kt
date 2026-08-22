// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.conceptflow.mpl.v1.CameraFrameChunk
import org.conceptflow.mpl.v1.ImuBatch
import org.conceptflow.mpl.v1.MicrophoneChunk
import org.conceptflow.mpl.v1.SensorStreamEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorStreamOutboxTest {
    @Test
    fun newerCameraFrameReplacesAllUnsentOlderChunks() {
        val outbox = SensorStreamOutbox(FixedClock(100L))
        assertTrue(outbox.offerCameraFrame(listOf(camera(1L, 0), camera(1L, 1))))
        assertTrue(outbox.offerCameraFrame(listOf(camera(2L, 0), camera(2L, 1))))

        val first = outbox.takeNext()!!
        val second = outbox.takeNext()!!
        assertEquals(2L, first.cameraChunk.frameId)
        assertEquals(2L, second.cameraChunk.frameId)
        assertEquals(listOf(1L, 2L), listOf(first.sequenceId, second.sequenceId))
        assertEquals(100L, first.sentMonotonicTimestampNs)
        assertNull(outbox.takeNext())
        assertFalse(outbox.offerCameraFrame(listOf(camera(1L, 0))))
        assertEquals(1L, outbox.statistics().cameraFramesReplaced)
    }

    @Test
    fun latestAbsoluteImuAndMicrophoneChunksReplaceBacklog() {
        val outbox = SensorStreamOutbox(FixedClock(100L))
        assertTrue(outbox.offerImu(imu(1L)))
        assertTrue(outbox.offerImu(imu(2L)))
        assertTrue(outbox.offerMicrophone(microphone(1L)))
        assertTrue(outbox.offerMicrophone(microphone(2L)))

        assertEquals(2L, outbox.takeNext()!!.imuBatch.batchId)
        assertEquals(2L, outbox.takeNext()!!.microphoneChunk.chunkId)
        assertNull(outbox.takeNext())
        assertEquals(1L, outbox.statistics().imuBatchesReplaced)
        assertEquals(1L, outbox.statistics().microphoneChunksReplaced)
        assertFalse(outbox.offerImu(imu(1L)))
        assertFalse(outbox.offerMicrophone(microphone(1L)))
    }

    @Test
    fun interleavedLanesReceiveStrictSendOrderAtDequeue() {
        val outbox = SensorStreamOutbox(FixedClock(100L))
        outbox.offerCameraFrame(listOf(camera(1L, 0), camera(1L, 1)))
        outbox.offerImu(imu(1L))
        outbox.offerMicrophone(microphone(1L))

        val sent = generateSequence { outbox.takeNext() }.toList()

        assertEquals(listOf(1L, 2L, 3L, 4L), sent.map { it.sequenceId })
        assertTrue(sent[0].hasImuBatch())
        assertTrue(sent[1].hasMicrophoneChunk())
        assertTrue(sent[2].hasCameraChunk())
        assertTrue(sent[3].hasCameraChunk())
    }

    @Test
    fun clearPreventsReplayAndWrongPayloadsAreRejected() {
        val outbox = SensorStreamOutbox(FixedClock(100L))
        assertFalse(outbox.offerCameraFrame(listOf(imu(1L))))
        assertFalse(outbox.offerImu(camera(1L, 0)))
        assertFalse(outbox.offerMicrophone(imu(1L)))
        outbox.offerCameraFrame(listOf(camera(1L, 0), camera(1L, 1)))

        outbox.clear()

        assertNull(outbox.takeNext())
    }

    private fun camera(frameId: Long, chunkIndex: Int): SensorStreamEnvelope = envelope(frameId * 10 + chunkIndex)
        .setCameraChunk(
            CameraFrameChunk.newBuilder()
                .setFrameId(frameId)
                .setChunkIndex(chunkIndex)
                .setChunkCount(2),
        )
        .build()

    private fun imu(batchId: Long): SensorStreamEnvelope = envelope(batchId)
        .setImuBatch(ImuBatch.newBuilder().setLeaseId("lease").setBatchId(batchId))
        .build()

    private fun microphone(chunkId: Long): SensorStreamEnvelope = envelope(chunkId)
        .setMicrophoneChunk(MicrophoneChunk.newBuilder().setLeaseId("lease").setChunkId(chunkId))
        .build()

    private fun envelope(sequence: Long): SensorStreamEnvelope.Builder = SensorStreamEnvelope.newBuilder()
        .setSessionId("session")
        .setLeaseId("lease")
        .setSequenceId(sequence)
        .setSentMonotonicTimestampNs(sequence)

    private class FixedClock(private val value: Long) : MonotonicClock {
        override fun nowNanos(): Long = value
    }
}
