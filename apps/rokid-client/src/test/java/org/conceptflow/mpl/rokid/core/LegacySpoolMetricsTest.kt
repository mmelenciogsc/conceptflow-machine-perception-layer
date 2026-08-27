// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacySpoolMetricsTest {
    @Test
    fun boundedSamplesUseNearestRankPercentilesAndReset() {
        val samples = BoundedDurationSamples(capacity = 4)
        listOf(10L, 20L, 30L, 40L).forEach(samples::record)

        assertEquals(LatencyNanoseconds(4, 20L, 40L, 40L), samples.snapshot())

        samples.record(50L)
        assertEquals(LatencyNanoseconds(4, 30L, 50L, 50L), samples.snapshot())
        samples.reset()
        assertEquals(0, samples.snapshot().samples)
        assertNull(samples.snapshot().p95)
    }

    @Test
    fun metricsContainOnlyBoundedAggregateCountsBytesAndTimings() {
        val metrics = LegacySpoolMetrics()
        metrics.recordCamera(transformNanos = 11L, totalNanos = 23L, artifactBytes = 101)
        metrics.recordImu(totalNanos = 7L)
        metrics.recordMicrophone(totalNanos = 13L, artifactBytes = 202)
        metrics.recordManifestPersist(jsonBytes = 31, stateBytes = 47, durationNanos = 5L)
        metrics.recordArtifactRead(bytes = 53, durationNanos = 3L)
        metrics.recordAcknowledgement(2)

        val snapshot = metrics.snapshot()
        assertEquals(1L, snapshot.cameraRecords)
        assertEquals(1L, snapshot.imuRecords)
        assertEquals(1L, snapshot.microphoneRecords)
        assertEquals(2L, snapshot.artifactFilesWritten)
        assertEquals(303L, snapshot.artifactBytesWritten)
        assertEquals(1L, snapshot.manifestWrites)
        assertEquals(31L, snapshot.manifestBytesWritten)
        assertEquals(47L, snapshot.recoveryStateBytesWritten)
        assertEquals(1L, snapshot.artifactReads)
        assertEquals(53L, snapshot.artifactBytesRead)
        assertEquals(2L, snapshot.acknowledgements)
        assertEquals(11L, snapshot.cameraTransform.p95)
        assertEquals(23L, snapshot.cameraStore.p95)
    }
}
