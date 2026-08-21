// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import com.google.protobuf.ByteString
import org.conceptflow.mpl.v1.CoordinateFrame
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.Pose
import org.conceptflow.mpl.v1.Quaternion
import org.conceptflow.mpl.v1.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalTraceInputGateTest {
    @Test
    fun dispatchRequiresFrameFreshHeadPoseAndMicrophoneActivity() {
        val gate = PhysicalTraceInputGate(maximumPoseSkewNanos = 10L)
        assertTrue(gate.recordFrame(frame(timestamp = 100L)))
        assertNull(gate.takeReadyFrame())
        gate.recordPose(sample(timestamp = 95L))
        assertNull(gate.takeReadyFrame())
        gate.recordMicrophoneActivity(payloadBytes = 4_096, hasNonZeroSignal = true)

        val ready = gate.takeReadyFrame()
        assertEquals(95L, ready?.pose?.monotonicTimestampNs)
        assertEquals(CoordinateFrame.COORDINATE_FRAME_HEAD, ready?.pose?.referenceFrame)
        assertNull(gate.takeReadyFrame())
        assertFalse(gate.recordFrame(frame(timestamp = 101L)))
    }

    @Test
    fun staleOrWrongFramePoseDoesNotLeaveTheDevice() {
        val gate = PhysicalTraceInputGate(maximumPoseSkewNanos = 10L)
        gate.recordFrame(frame(timestamp = 100L))
        gate.recordMicrophoneActivity(payloadBytes = 1, hasNonZeroSignal = true)
        gate.recordPose(sample(timestamp = 89L))
        assertNull(gate.takeReadyFrame())
        gate.recordPose(sample(timestamp = 100L, frame = CoordinateFrame.COORDINATE_FRAME_BODY))
        assertNull(gate.takeReadyFrame())
        gate.recordPose(sample(timestamp = 105L))
        assertEquals(105L, gate.takeReadyFrame()?.pose?.monotonicTimestampNs)
    }

    @Test
    fun boundedHistoryPairsTheNearestPoseInsteadOfTheLatestCallback() {
        val gate = PhysicalTraceInputGate(maximumPoseSkewNanos = 10L, poseHistoryCapacity = 3)
        gate.recordPose(sample(timestamp = 95L))
        gate.recordPose(sample(timestamp = 200L))
        gate.recordFrame(frame(timestamp = 100L))
        gate.recordMicrophoneActivity(payloadBytes = 2, hasNonZeroSignal = true)

        assertEquals(95L, gate.takeReadyFrame()?.pose?.monotonicTimestampNs)
    }

    @Test
    fun poseHistoryCapacityEvictsOldestSamples() {
        val gate = PhysicalTraceInputGate(maximumPoseSkewNanos = 10L, poseHistoryCapacity = 2)
        gate.recordPose(sample(timestamp = 100L))
        gate.recordPose(sample(timestamp = 200L))
        gate.recordPose(sample(timestamp = 300L))
        gate.recordFrame(frame(timestamp = 100L))
        gate.recordMicrophoneActivity(payloadBytes = 2, hasNonZeroSignal = true)

        assertNull(gate.takeReadyFrame())
    }

    @Test
    fun microphonePayloadIsReducedToAnActivityBoolean() {
        val gate = PhysicalTraceInputGate(maximumPoseSkewNanos = 10L)
        gate.recordFrame(frame(timestamp = 100L))
        gate.recordPose(sample(timestamp = 100L))
        gate.recordMicrophoneActivity(payloadBytes = 0, hasNonZeroSignal = true)
        assertNull(gate.takeReadyFrame())
        gate.recordMicrophoneActivity(payloadBytes = 2, hasNonZeroSignal = false)
        assertNull(gate.takeReadyFrame())
        gate.recordMicrophoneActivity(payloadBytes = 2, hasNonZeroSignal = true)
        val ready = gate.takeReadyFrame()
        assertTrue(ready != null)
        assertEquals(ByteString.copyFrom(byteArrayOf(1)), ready!!.frameData)
    }

    @Test
    fun clearDropsRetainedFrameAndPreventsDispatch() {
        val gate = PhysicalTraceInputGate(maximumPoseSkewNanos = 10L)
        gate.recordFrame(frame(timestamp = 100L))
        gate.recordPose(sample(timestamp = 100L))
        gate.recordMicrophoneActivity(payloadBytes = 2, hasNonZeroSignal = true)
        gate.clear()
        assertNull(gate.takeReadyFrame())
    }

    private fun frame(timestamp: Long): FramePayload = FramePayload.newBuilder()
        .setRequestId("camera-1")
        .setSessionId("camera-session")
        .setStreamId("camera2-jpeg")
        .setFrameId(1L)
        .setCaptureMonotonicTimestampNs(timestamp)
        .setFrameData(ByteString.copyFrom(byteArrayOf(1)))
        .build()

    private fun sample(
        timestamp: Long,
        frame: CoordinateFrame = CoordinateFrame.COORDINATE_FRAME_HEAD,
    ): ImuSample = ImuSample(
        pose = Pose.newBuilder()
            .setReferenceFrame(frame)
            .setRotation(Quaternion.newBuilder().setW(1.0))
            .setMonotonicTimestampNs(timestamp)
            .build(),
        angularVelocityRadiansPerSecond = Vector3.getDefaultInstance(),
        linearAccelerationMetersPerSecondSquared = Vector3.getDefaultInstance(),
    )
}
