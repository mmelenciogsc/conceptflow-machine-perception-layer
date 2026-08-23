// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import org.conceptflow.mpl.v1.CameraFrameChunk
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.ImuBatch
import org.conceptflow.mpl.v1.LiveLinkEnvelope
import org.conceptflow.mpl.v1.LiveTransportLane
import org.conceptflow.mpl.v1.SensorStreamEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PerLaneSequenceGuardTest {
    private val binding = LiveSessionBinding("session", "lease", ByteArray(32) { 3 })

    @Test
    fun `camera delay does not block contiguous realtime sequence`() {
        val guard = PerLaneSequenceGuard(binding)

        guard.accept(realtime(sequence = 1, batchId = 1))
        guard.accept(realtime(sequence = 2, batchId = 2))
        guard.accept(camera(sequence = 1, frameId = 1))
        guard.accept(realtime(sequence = 3, batchId = 3))

        assertEquals(4, guard.nextExpected(LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL))
        assertEquals(2, guard.nextExpected(LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA))
    }

    @Test
    fun `sender assigns independent contiguous sequences`() {
        val allocator = PerLaneSequenceAllocator()

        assertEquals(1, allocator.take(LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL))
        assertEquals(2, allocator.take(LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL))
        assertEquals(1, allocator.take(LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA))
        allocator.reset()
        assertEquals(1, allocator.take(LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA))
    }

    @Test
    fun `duplicate gap and wrong binding are rejected without advancing`() {
        val guard = PerLaneSequenceGuard(binding)
        guard.accept(realtime(sequence = 1, batchId = 1))

        val gap = assertThrows(LaneProtocolException::class.java) {
            guard.accept(realtime(sequence = 3, batchId = 3))
        }
        assertEquals(LaneProtocolFailure.SEQUENCE_REPLAY_OR_GAP, gap.failure)
        guard.accept(realtime(sequence = 2, batchId = 2))

        val duplicate = assertThrows(LaneProtocolException::class.java) {
            guard.accept(realtime(sequence = 2, batchId = 2))
        }
        assertEquals(LaneProtocolFailure.SEQUENCE_REPLAY_OR_GAP, duplicate.failure)

        val wrong = realtime(sequence = 3, batchId = 3).toBuilder().setSessionId("other").build()
        val mismatch = assertThrows(LaneProtocolException::class.java) { guard.accept(wrong) }
        assertEquals(LaneProtocolFailure.BINDING_MISMATCH, mismatch.failure)
        guard.accept(realtime(sequence = 3, batchId = 3))
    }

    @Test
    fun `payload cannot cross lanes`() {
        val guard = PerLaneSequenceGuard(binding)
        val crossed = realtime(sequence = 1, batchId = 1).toBuilder()
            .setLane(LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA)
            .build()

        val error = assertThrows(LaneProtocolException::class.java) { guard.accept(crossed) }

        assertEquals(LaneProtocolFailure.PAYLOAD_LANE_MISMATCH, error.failure)
    }

    private fun realtime(sequence: Long, batchId: Long): LiveLinkEnvelope {
        val sensor = SensorStreamEnvelope.newBuilder()
            .setSessionId(binding.sessionId)
            .setLeaseId(binding.leaseId)
            .setImuBatch(ImuBatch.newBuilder().setLeaseId(binding.leaseId).setBatchId(batchId))
        return base(sequence, LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL)
            .setSensor(sensor)
            .build()
    }

    private fun camera(sequence: Long, frameId: Long): LiveLinkEnvelope {
        val sensor = SensorStreamEnvelope.newBuilder()
            .setSessionId(binding.sessionId)
            .setLeaseId(binding.leaseId)
            .setCameraChunk(
                CameraFrameChunk.newBuilder()
                    .setFrameId(frameId)
                    .setChunkIndex(0)
                    .setChunkCount(1)
                    .setTotalPayloadBytes(1)
                    .setFrameMetadata(FramePayload.newBuilder().setSessionId(binding.sessionId).setFrameId(frameId)),
            )
        return base(sequence, LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA)
            .setSensor(sensor)
            .build()
    }

    private fun base(sequence: Long, lane: LiveTransportLane): LiveLinkEnvelope.Builder =
        LiveLinkEnvelope.newBuilder()
            .setSessionId(binding.sessionId)
            .setLeaseId(binding.leaseId)
            .setLane(lane)
            .setLaneSequenceId(sequence)
}
