// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.conceptflow.mpl.v1.Pose
import org.conceptflow.mpl.v1.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PoseSourceTest {
    @Test
    fun defaultSamplingProfileRequestsUnbatchedOneHundredHertz() {
        val profile = ImuSamplingProfile()

        assertEquals(10_000, profile.samplingPeriodMicros)
        assertEquals(0, profile.maximumReportLatencyMicros)
        assertEquals(100.0, profile.nominalSamplesPerSecond, 0.0)
    }

    @Test
    fun samplingProfileRejectsUnboundedRatesAndLatency() {
        assertThrows(IllegalArgumentException::class.java) { ImuSamplingProfile(samplingPeriodMicros = 4_999) }
        assertThrows(IllegalArgumentException::class.java) { ImuSamplingProfile(samplingPeriodMicros = 200_001) }
        assertThrows(IllegalArgumentException::class.java) { ImuSamplingProfile(maximumReportLatencyMicros = -1) }
    }

    @Test
    fun sampleCarriesFusedOrientationAndLatestVectorTimestamps() {
        val sample = ImuSample(
            pose = Pose.newBuilder().setMonotonicTimestampNs(300L).build(),
            angularVelocityRadiansPerSecond = Vector3.newBuilder().setX(0.25).build(),
            linearAccelerationMetersPerSecondSquared = Vector3.newBuilder().setY(1.5).build(),
            sequenceId = 4L,
            orientationAccuracy = 3,
            angularVelocityTimestampNanos = 290L,
            linearAccelerationTimestampNanos = 295L,
        )

        assertEquals(4L, sample.sequenceId)
        assertEquals(3, sample.orientationAccuracy)
        assertEquals(290L, sample.angularVelocityTimestampNanos)
        assertEquals(295L, sample.linearAccelerationTimestampNanos)
    }

    @Test
    fun sampleRejectsOutOfRangeAccuracy() {
        assertThrows(IllegalArgumentException::class.java) {
            ImuSample(
                pose = Pose.getDefaultInstance(),
                angularVelocityRadiansPerSecond = Vector3.getDefaultInstance(),
                linearAccelerationMetersPerSecondSquared = Vector3.getDefaultInstance(),
                orientationAccuracy = 4,
            )
        }
    }
}
