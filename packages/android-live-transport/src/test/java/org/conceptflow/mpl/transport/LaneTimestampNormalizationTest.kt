// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaneTimestampNormalizationTest {
    @Test
    fun `cross-lane reordering does not clamp one lane against the other`() {
        val normalizer = RemoteMonotonicClockNormalizer()
        normalizer.install(ClockOffsetEstimate(80, 20, 10, 8))

        val laterCamera = normalizer.normalize(RemoteClockStream.CAMERA_SEND, 2_000)
        val earlierRealtime = normalizer.normalize(RemoteClockStream.REALTIME_CONTROL_SEND, 1_000)

        assertEquals(1_920L, laterCamera.hostMonotonicNs)
        assertEquals(920L, earlierRealtime.hostMonotonicNs)
    }

    @Test
    fun `backward source time is retained and monotonic adjustment is explicit`() {
        val normalizer = RemoteMonotonicClockNormalizer()
        normalizer.install(ClockOffsetEstimate(80, 20, 10, 8), installedHostMonotonicNs = 500)

        normalizer.normalize(RemoteClockStream.IMU_POSE, 2_000)
        val reordered = normalizer.normalize(RemoteClockStream.IMU_POSE, 1_900)

        assertEquals(1_900L, reordered.rawRemoteNs)
        assertEquals(1_820L, reordered.unadjustedHostMonotonicNs)
        assertEquals(1_921L, reordered.hostMonotonicNs)
        assertEquals(101L, reordered.monotonicAdjustmentNs)
        assertTrue(reordered.monotonicAdjustmentNs > 0)
    }

    @Test
    fun `periodic estimate installation exposes offset drift evidence`() {
        val normalizer = RemoteMonotonicClockNormalizer()
        normalizer.install(ClockOffsetEstimate(80, 20, 10, 8), installedHostMonotonicNs = 500)
        normalizer.install(ClockOffsetEstimate(90, 30, 15, 8), installedHostMonotonicNs = 10_000)

        val normalized = normalizer.normalize(RemoteClockStream.CAMERA_CAPTURE, 20_000)

        assertEquals(2L, normalized.clockEvidence.estimateRevision)
        assertEquals(10L, normalized.clockEvidence.offsetChangeFromPreviousNs)
        assertEquals(10_000L, normalized.clockEvidence.installedHostMonotonicNs)
        assertEquals(15L, normalized.clockEvidence.uncertaintyNs)
    }
}
