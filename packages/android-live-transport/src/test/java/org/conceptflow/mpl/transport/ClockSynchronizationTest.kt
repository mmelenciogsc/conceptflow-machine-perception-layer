// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ClockSynchronizationTest {
    @Test
    fun `chooses minimum RTT and reports half RTT uncertainty`() {
        val synchronizer = MinRttClockSynchronizer(requiredSamples = 3, maxSamples = 3)
        synchronizer.beginRound()
        synchronizer.add(symmetricProbe(1, delayNs = 50, offsetNs = 500))
        synchronizer.add(symmetricProbe(2, delayNs = 10, offsetNs = 500))
        synchronizer.add(symmetricProbe(3, delayNs = 30, offsetNs = 500))

        val estimate = synchronizer.commitBest()

        assertEquals(500, estimate.offsetRemoteMinusHostNs)
        assertEquals(20, estimate.roundTripNs)
        assertEquals(10, estimate.uncertaintyNs)
        assertEquals(3, estimate.validSampleCount)
    }

    @Test
    fun `rejects impossible timing and bounded refresh jump`() {
        assertThrows(ClockSyncException::class.java) {
            MinRttClockSynchronizer.calculateSample(
                FourTimestampClockProbe(1, 100, 600, 700, 150),
            )
        }

        val synchronizer = MinRttClockSynchronizer(
            requiredSamples = 1,
            maxSamples = 1,
            maxOffsetJumpNs = 50,
        )
        synchronizer.beginRound()
        synchronizer.add(symmetricProbe(1, 10, 500))
        synchronizer.commitBest()
        synchronizer.beginRound()
        synchronizer.add(symmetricProbe(2, 10, 551))

        val jump = assertThrows(ClockSyncException::class.java) { synchronizer.commitBest() }
        assertEquals(ClockSyncFailure.OFFSET_JUMP, jump.failure)
    }

    @Test
    fun `normalization preserves raw source and clamps each stream independently`() {
        val normalizer = RemoteMonotonicClockNormalizer()
        normalizer.install(ClockOffsetEstimate(500, 20, 10, 8))

        val first = normalizer.normalize(RemoteClockStream.IMU_POSE, 1_500)
        val clamped = normalizer.normalize(RemoteClockStream.IMU_POSE, 1_499)
        val camera = normalizer.normalize(RemoteClockStream.CAMERA_CAPTURE, 1_499)

        assertEquals(1_500, first.rawRemoteNs)
        assertEquals(1_000, first.hostMonotonicNs)
        assertEquals(1_001, clamped.hostMonotonicNs)
        assertEquals(999, camera.hostMonotonicNs)
        assertEquals(10, camera.uncertaintyNs)
    }

    @Test
    fun `normalization rejects arithmetic overflow`() {
        val normalizer = RemoteMonotonicClockNormalizer()
        normalizer.install(ClockOffsetEstimate(Long.MIN_VALUE, 0, 0, 1))

        val error = assertThrows(ClockSyncException::class.java) {
            normalizer.normalize(RemoteClockStream.REMOTE_SEND, Long.MAX_VALUE)
        }

        assertEquals(ClockSyncFailure.OFFSET_OUT_OF_RANGE, error.failure)
    }

    private fun symmetricProbe(id: Long, delayNs: Long, offsetNs: Long): FourTimestampClockProbe {
        val t0 = 1_000L
        val processing = 5L
        val t1 = t0 + delayNs + offsetNs
        val t2 = t1 + processing
        val t3 = t0 + delayNs + processing + delayNs
        return FourTimestampClockProbe(id, t0, t1, t2, t3)
    }
}
