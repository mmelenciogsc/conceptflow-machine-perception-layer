// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamDiagnosticSessionTest {
    @Test
    fun passesOnlyAfterAllThreeStreamsProduceData() {
        val session = StreamDiagnosticSession(1_000_000L)

        assertTrue(session.recordCameraFrame(20))
        assertTrue(session.recordImuSample(hasNonZeroSignal = false))
        assertFalse(session.finish(2_000_000L).passed)
    }

    @Test
    fun countsPayloadsAndStopsAcceptingAfterFinish() {
        val session = StreamDiagnosticSession(1_000_000L)
        assertTrue(session.recordCameraFrame(20))
        assertFalse(session.recordCameraFrame(30))
        session.recordCaptureGate(gateEvent(emitted = true))
        session.recordCaptureGate(gateEvent(FrameDropReason.DARK))
        session.recordCaptureGate(gateEvent(FrameDropReason.BLURRY))
        session.recordCaptureGate(gateEvent(FrameDropReason.CADENCE_SIMILAR, targetFramesPerSecond = 5.0))
        assertTrue(session.recordImuSample(hasNonZeroSignal = false, timestampNanos = 1_000_000_000L))
        assertFalse(session.recordImuSample(hasNonZeroSignal = true, timestampNanos = 1_010_000_000L))
        assertTrue(session.recordMicrophoneChunk(byteArrayOf(0, 0, 1, 0)))
        assertFalse(session.recordMicrophoneChunk(byteArrayOf(0, 0, 0, 0, 0xff.toByte(), 0x7f)))

        val snapshot = session.finish(11_000_000L)

        assertTrue(snapshot.passed)
        assertEquals(10L, snapshot.durationMillis)
        assertEquals(2L, snapshot.cameraFrames)
        assertEquals(50L, snapshot.cameraBytes)
        assertEquals(4L, snapshot.cameraFramesAnalyzed)
        assertEquals(1L, snapshot.cameraFramesDroppedDark)
        assertEquals(1L, snapshot.cameraFramesDroppedBlurry)
        assertEquals(1L, snapshot.cameraFramesDroppedCadence)
        assertEquals(1L, snapshot.cameraMotionTierSamples)
        assertEquals(120.0, snapshot.cameraMaximumMeanLuma, 0.001)
        assertEquals(0.1, snapshot.cameraMinimumDarkFraction, 0.001)
        assertEquals(250.0, snapshot.cameraMaximumLaplacianVariance, 0.001)
        assertEquals(0.25, snapshot.cameraMaximumMotionScore, 0.001)
        assertEquals(2L, snapshot.imuSamples)
        assertEquals(1L, snapshot.imuSignalSamples)
        assertEquals(100.0, snapshot.imuObservedSamplesPerSecond, 0.001)
        assertEquals(10.0, snapshot.imuMaximumGapMillis, 0.001)
        assertEquals(2L, snapshot.microphoneChunks)
        assertEquals(10L, snapshot.microphoneBytes)
        assertEquals(2L, snapshot.microphoneNonZeroSamples)
        assertEquals(32_767, snapshot.microphonePeakAbsolute)
        assertFalse(session.recordMicrophoneChunk(byteArrayOf(1, 0)))
        assertEquals(snapshot, session.finish(21_000_000L))
    }

    @Test
    fun observedImuRateUsesOnlyStrictlyAdvancingSourceTimestamps() {
        val session = StreamDiagnosticSession(0L)
        session.recordImuSample(false, 0L)
        session.recordImuSample(false, 1_000_000_000L)
        session.recordImuSample(false, 900_000_000L)
        session.recordImuSample(false, 2_000_000_000L)

        val snapshot = session.finish(2_000_000_000L)

        assertEquals(4L, snapshot.imuSamples)
        assertEquals(1.0, snapshot.imuObservedSamplesPerSecond, 0.001)
        assertEquals(1_000.0, snapshot.imuMaximumGapMillis, 0.001)
    }

    private fun gateEvent(
        reason: FrameDropReason? = null,
        emitted: Boolean = reason == null,
        targetFramesPerSecond: Double = 2.0,
    ): CaptureGateEvent = CaptureGateEvent(
        inputDimensions = PixelDimensions(1_920, 1_080),
        outputDimensions = PixelDimensions(1_920, 1_080),
        emitted = emitted,
        dropReason = reason,
        targetFramesPerSecond = targetFramesPerSecond,
        meanLuma = 120.0,
        darkFraction = 0.1,
        laplacianVariance = 250.0,
        motionScore = 0.25,
    )
}
