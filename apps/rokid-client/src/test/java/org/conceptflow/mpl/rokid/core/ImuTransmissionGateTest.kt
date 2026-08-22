// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.conceptflow.mpl.v1.CoordinateFrame
import org.conceptflow.mpl.v1.Pose
import org.conceptflow.mpl.v1.Quaternion
import org.conceptflow.mpl.v1.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImuTransmissionGateTest {
    @Test
    fun stableSamplesAreSuppressedButHeartbeatIsSelectedAtOneSecond() {
        val gate = ImuTransmissionGate()

        assertNull(gate.offer(sample(1, 10_000_000L)))
        val initial = gate.offer(sample(2, 30_000_000L))!!
        assertEquals(listOf(1L), initial.samples.map { it.sequenceId })
        assertNull(gate.offer(sample(3, 999_000_000L)))
        assertNull(gate.offer(sample(4, 1_010_000_000L)))
        val heartbeat = gate.poll(1_030_000_000L)!!

        assertEquals(listOf(4L), heartbeat.samples.map { it.sequenceId })
        assertEquals(2L, gate.statistics().accepted)
        assertEquals(2L, gate.statistics().duplicatesSuppressed)
    }

    @Test
    fun meaningfulMotionIsEmittedWithinTwentyMilliseconds() {
        val gate = ImuTransmissionGate()
        gate.offer(sample(1, 100_000_000L))
        gate.flush(101_000_000L)

        assertNull(gate.offer(sample(2, 200_000_000L, gyroX = 0.03)))
        assertNull(gate.poll(219_999_999L))
        val batch = gate.poll(220_000_000L)!!

        assertEquals(listOf(2L), batch.samples.map { it.sequenceId })
        assertEquals(220_000_000L, batch.createdMonotonicTimestampNanos)
    }

    @Test
    fun duplicateArrivalFlushesExistingBatchAtDeadline() {
        val gate = ImuTransmissionGate()

        gate.offer(sample(1, 1_000_000L))
        val batch = gate.offer(sample(2, 21_000_000L))!!

        assertEquals(listOf(1L), batch.samples.map { it.sequenceId })
        assertEquals(1L, gate.statistics().duplicatesSuppressed)
    }

    @Test
    fun quaternionSignFlipIsTheSameOrientation() {
        val gate = ImuTransmissionGate()
        gate.offer(sample(1, 1L, rotation = Quaternion.newBuilder().setW(1.0).build()))
        gate.flush(2L)

        assertNull(
            gate.offer(sample(2, 10_000_000L, rotation = Quaternion.newBuilder().setW(-1.0).build())),
        )
        assertEquals(1L, gate.statistics().duplicatesSuppressed)
    }

    @Test
    fun rejectsNonIncreasingAndNonFiniteSamplesWithoutCorruptingReference() {
        val gate = ImuTransmissionGate()
        gate.offer(sample(1, 100L))
        gate.flush(101L)

        assertNull(gate.offer(sample(1, 101L)))
        assertNull(gate.offer(sample(2, 102L, gyroX = Double.NaN)))
        assertNull(gate.offer(sample(3, 103L, gyroX = 0.03)))
        val batch = gate.poll(20_000_103L)!!

        assertEquals(listOf(3L), batch.samples.map { it.sequenceId })
        assertEquals(1L, gate.statistics().outOfOrderRejected)
        assertEquals(1L, gate.statistics().invalidRejected)
    }

    @Test
    fun batchesAreBoundedAndSnapshotsDoNotChangeAfterFurtherOffers() {
        val gate = ImuTransmissionGate(
            ImuTransmissionConfig(
                orientationDeltaRadians = 0.0,
                angularVelocityDeltaRadiansPerSecond = 0.0,
                linearAccelerationDeltaMetersPerSecondSquared = 0.0,
                maxBatchSamples = 2,
            ),
        )

        gate.offer(sample(1, 1L))
        val first = gate.offer(sample(2, 2L))!!
        gate.offer(sample(3, 3L))

        assertEquals(listOf(1L, 2L), first.samples.map { it.sequenceId })
        assertEquals(2, first.samples.size)
        assertTrue(gate.statistics().accepted >= 3L)
    }

    private fun sample(
        sequence: Long,
        timestamp: Long,
        gyroX: Double = 0.0,
        rotation: Quaternion = Quaternion.newBuilder().setW(1.0).build(),
    ): ImuSample = ImuSample(
        pose = Pose.newBuilder()
            .setReferenceFrame(CoordinateFrame.COORDINATE_FRAME_HEAD)
            .setRotation(rotation)
            .setMonotonicTimestampNs(timestamp)
            .build(),
        angularVelocityRadiansPerSecond = Vector3.newBuilder().setX(gyroX).build(),
        linearAccelerationMetersPerSecondSquared = Vector3.getDefaultInstance(),
        sequenceId = sequence,
        orientationAccuracy = 3,
        angularVelocityTimestampNanos = timestamp,
        linearAccelerationTimestampNanos = timestamp,
    )
}
