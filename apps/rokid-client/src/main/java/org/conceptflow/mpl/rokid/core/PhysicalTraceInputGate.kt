// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.conceptflow.mpl.v1.CoordinateFrame
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.Pose

/**
 * Retains at most one frame and a bounded pose history until all local input
 * lanes are active. Microphone bytes are never retained or added to the wire
 * payload.
 */
class PhysicalTraceInputGate(
    private val maximumPoseSkewNanos: Long = 250_000_000L,
    private val poseHistoryCapacity: Int = 1_024,
) {
    private var capturedFrame: FramePayload? = null
    private val headPoseHistory = ArrayDeque<Pose>()
    private var microphoneObserved = false
    private var dispatched = false

    init {
        require(maximumPoseSkewNanos in 1L..2_000_000_000L)
        require(poseHistoryCapacity in 1..4_096)
    }

    @Synchronized
    fun recordFrame(frame: FramePayload): Boolean {
        if (capturedFrame != null || dispatched) return false
        capturedFrame = frame
        return true
    }

    @Synchronized
    fun recordPose(sample: ImuSample) {
        val pose = sample.pose
        if (pose.referenceFrame == CoordinateFrame.COORDINATE_FRAME_HEAD &&
            pose.monotonicTimestampNs > 0L
        ) {
            headPoseHistory.addLast(pose)
            while (headPoseHistory.size > poseHistoryCapacity) headPoseHistory.removeFirst()
        }
    }

    @Synchronized
    fun recordMicrophoneActivity(payloadBytes: Int, hasNonZeroSignal: Boolean) {
        if (payloadBytes > 0 && hasNonZeroSignal) microphoneObserved = true
    }

    @Synchronized
    fun takeReadyFrame(): FramePayload? {
        if (dispatched || !microphoneObserved) return null
        val frame = capturedFrame ?: return null
        val pose = headPoseHistory.minByOrNull {
            absoluteDifference(frame.captureMonotonicTimestampNs, it.monotonicTimestampNs)
        } ?: return null
        val skew = absoluteDifference(frame.captureMonotonicTimestampNs, pose.monotonicTimestampNs)
        if (skew > maximumPoseSkewNanos) return null
        dispatched = true
        capturedFrame = null
        headPoseHistory.clear()
        return frame.toBuilder().setPose(pose).build()
    }

    @Synchronized
    fun clear() {
        capturedFrame = null
        headPoseHistory.clear()
        microphoneObserved = false
        dispatched = true
    }
}

private fun absoluteDifference(left: Long, right: Long): Long =
    if (left >= right) left - right else right - left
