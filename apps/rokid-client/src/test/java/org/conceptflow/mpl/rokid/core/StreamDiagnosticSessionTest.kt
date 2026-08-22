// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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

    @Test
    fun cameraRatesUseFirstToLastTimingRatherThanColdStartDuration() {
        val session = StreamDiagnosticSession(1_000_000_000L)
        assertTrue(session.recordCameraJpegSessionReady(1_500_000_000L))
        assertTrue(
            session.recordCaptureTiming(
                timingEvent(
                    analyzedNanos = 2_000_000_000L,
                    emittedNanos = 2_050_000_000L,
                    requestToImageNanos = 100_000_000L,
                    processorNanos = 10_000_000L,
                ),
            ),
        )
        assertTrue(
            session.recordCaptureTiming(
                timingEvent(
                    analyzedNanos = 2_400_000_000L,
                    emittedNanos = null,
                    requestToImageNanos = 200_000_000L,
                    processorNanos = 20_000_000L,
                ),
            ),
        )
        assertTrue(
            session.recordCaptureTiming(
                timingEvent(
                    analyzedNanos = 2_800_000_000L,
                    emittedNanos = 2_850_000_000L,
                    requestToImageNanos = 300_000_000L,
                    processorNanos = 30_000_000L,
                ),
            ),
        )

        val snapshot = session.finish(9_000_000_000L)

        assertEquals(8_000L, snapshot.durationMillis)
        assertTrue(snapshot.cameraJpegSessionReady)
        assertEquals(500.0, snapshot.cameraJpegSessionStartupMillis, 0.001)
        assertEquals(3L, snapshot.cameraTimingSamples)
        assertEquals(3, snapshot.cameraTimingRetainedSamples)
        assertEquals(3L, snapshot.cameraRequestTimingSamples)
        assertEquals(3, snapshot.cameraRequestTimingRetainedSamples)
        assertEquals(800.0, snapshot.cameraAnalyzedActiveMillis, 0.001)
        assertEquals(2.5, snapshot.cameraAnalyzedObservedFramesPerSecond, 0.001)
        assertEquals(2L, snapshot.cameraEmittedTimingSamples)
        assertEquals(800.0, snapshot.cameraEmittedActiveMillis, 0.001)
        assertEquals(1.25, snapshot.cameraEmittedObservedFramesPerSecond, 0.001)
        assertEquals(200.0, snapshot.cameraRequestToImageP50Millis, 0.001)
        assertEquals(300.0, snapshot.cameraRequestToImageP95Millis, 0.001)
        assertEquals(300.0, snapshot.cameraRequestToImageMaximumMillis, 0.001)
        assertEquals(5.0, snapshot.cameraImageAcquisitionP50Millis, 0.001)
        assertEquals(5.0, snapshot.cameraImageAcquisitionP95Millis, 0.001)
        assertEquals(5.0, snapshot.cameraImageAcquisitionMaximumMillis, 0.001)
        assertEquals(20.0, snapshot.cameraProcessorP50Millis, 0.001)
        assertEquals(30.0, snapshot.cameraProcessorP95Millis, 0.001)
        assertEquals(30.0, snapshot.cameraProcessorMaximumMillis, 0.001)
        assertEquals(15.0, snapshot.cameraListenerPathP50Millis, 0.001)
        assertEquals(15.0, snapshot.cameraListenerPathP95Millis, 0.001)
        assertEquals(15.0, snapshot.cameraListenerPathMaximumMillis, 0.001)
    }

    @Test
    fun zeroAndOneCameraTimingSamplesHaveZeroActiveRate() {
        val empty = StreamDiagnosticSession(0L).finish(8_000_000_000L)
        assertFalse(empty.cameraJpegSessionReady)
        assertEquals(0L, empty.cameraTimingSamples)
        assertEquals(0.0, empty.cameraAnalyzedActiveMillis, 0.0)
        assertEquals(0.0, empty.cameraAnalyzedObservedFramesPerSecond, 0.0)
        assertEquals(0.0, empty.cameraRequestToImageP95Millis, 0.0)

        val oneSample = StreamDiagnosticSession(0L)
        assertTrue(oneSample.recordCameraJpegSessionReady(100_000_000L))
        assertTrue(
            oneSample.recordCaptureTiming(
                timingEvent(1_000_000_000L, 1_010_000_000L),
            ),
        )
        val one = oneSample.finish(8_000_000_000L)
        assertEquals(1L, one.cameraTimingSamples)
        assertEquals(0.0, one.cameraAnalyzedActiveMillis, 0.0)
        assertEquals(0.0, one.cameraAnalyzedObservedFramesPerSecond, 0.0)
        assertEquals(1L, one.cameraEmittedTimingSamples)
        assertEquals(0.0, one.cameraEmittedObservedFramesPerSecond, 0.0)
    }

    @Test
    fun cameraTimingRejectsNonMonotonicSamplesAndFreezesFinalSnapshot() {
        val session = StreamDiagnosticSession(1_000_000_000L)
        assertFalse(session.recordCameraJpegSessionReady(999_999_999L))
        assertTrue(session.recordCameraJpegSessionReady(1_100_000_000L))
        assertFalse(session.recordCameraJpegSessionReady(1_200_000_000L))
        assertTrue(session.recordCaptureTiming(timingEvent(2_000_000_000L, 2_010_000_000L)))
        assertFalse(session.recordCaptureTiming(timingEvent(2_000_000_000L, 2_020_000_000L)))
        assertFalse(session.recordCaptureTiming(timingEvent(1_900_000_000L, null)))

        val snapshot = session.finish(3_000_000_000L)

        assertEquals(1L, snapshot.cameraTimingSamples)
        assertEquals(4L, snapshot.cameraTimingRejectedEvents)
        assertFalse(session.recordCaptureTiming(timingEvent(3_000_000_000L, 3_010_000_000L)))
        assertFalse(session.recordCameraJpegSessionReady(3_000_000_000L))
        assertEquals(snapshot, session.finish(4_000_000_000L))
    }

    @Test
    fun captureTimingEventValidatesInternalMonotonicValues() {
        assertThrows(IllegalArgumentException::class.java) {
            timingEvent(analyzedNanos = 2_000_000_000L, emittedNanos = 1_999_999_999L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            timingEvent(analyzedNanos = 2_000_000_000L, emittedNanos = null, processorNanos = -1L)
        }
    }

    @Test
    fun unmatchedImageStillContributesToRatesWithoutInventingRequestLatency() {
        val session = StreamDiagnosticSession(0L)
        assertTrue(
            session.recordCaptureTiming(
                timingEvent(
                    analyzedNanos = 1_000_000_000L,
                    emittedNanos = null,
                    requestToImageNanos = null,
                ),
            ),
        )

        val snapshot = session.finish(2_000_000_000L)

        assertEquals(1L, snapshot.cameraTimingSamples)
        assertEquals(0L, snapshot.cameraRequestTimingSamples)
        assertEquals(0, snapshot.cameraRequestTimingRetainedSamples)
        assertEquals(0.0, snapshot.cameraRequestToImageP95Millis, 0.0)
    }

    @Test
    fun capturePipelineCountersAreCumulativeAndFreezeAtFinish() {
        val session = StreamDiagnosticSession(0L)
        assertTrue(session.recordCapturePipelineSnapshot(pipelineSnapshot(submitted = 3L, outstanding = 3, maximum = 3)))
        assertTrue(
            session.recordCapturePipelineSnapshot(
                pipelineSnapshot(
                    submitted = 4L,
                    backpressured = 1L,
                    superseded = 2L,
                    unmatched = 1L,
                    failures = 1L,
                    lateCallbacks = 2L,
                    outstanding = 1,
                    maximum = 3,
                ),
            ),
        )
        assertFalse(session.recordCapturePipelineSnapshot(pipelineSnapshot(submitted = 2L)))
        assertTrue(
            session.recordCapturePipelineSnapshot(
                pipelineSnapshot(
                    submitted = 4L,
                    backpressured = 1L,
                    superseded = 2L,
                    unmatched = 1L,
                    failures = 1L,
                    lateCallbacks = 2L,
                    outstanding = 0,
                    maximum = 3,
                ),
            ),
        )

        val snapshot = session.finish(1_000_000_000L)

        assertEquals(4L, snapshot.cameraRequestsSubmitted)
        assertEquals(1L, snapshot.cameraOpportunitiesBackpressured)
        assertEquals(2L, snapshot.cameraRequestsSuperseded)
        assertEquals(1L, snapshot.cameraImagesWithoutExactRequestMatch)
        assertEquals(1L, snapshot.cameraCaptureFailures)
        assertEquals(2L, snapshot.cameraLateCallbacks)
        assertEquals(0, snapshot.cameraOutstandingRequests)
        assertEquals(3, snapshot.cameraMaximumOutstandingRequests)
        assertEquals(1L, snapshot.cameraTimingRejectedEvents)
        assertFalse(session.recordCapturePipelineSnapshot(pipelineSnapshot(submitted = 5L)))
        assertEquals(snapshot, session.finish(2_000_000_000L))
    }

    @Test
    fun timingWindowRetainsOnlyBoundedRecentSamplesAndResets() {
        val window = BoundedTimingWindow(capacity = 3)
        assertFalse(window.record(-1L))
        assertTrue(window.record(10L))
        assertTrue(window.record(20L))
        assertTrue(window.record(30L))
        assertTrue(window.record(40L))

        val bounded = window.snapshot()
        assertEquals(4L, bounded.totalSampleCount)
        assertEquals(3, bounded.retainedSampleCount)
        assertEquals(30L, bounded.p50Nanos)
        assertEquals(40L, bounded.p95Nanos)
        assertEquals(40L, bounded.maximumNanos)

        window.reset()
        assertEquals(BoundedTimingSnapshot(0L, 0, 0L, 0L, 0L), window.snapshot())
    }

    @Test
    fun monotonicRateTrackerRejectsDuplicateAndOutOfOrderTimestampsAndResets() {
        val tracker = MonotonicRateTracker()
        assertFalse(tracker.record(0L))
        assertTrue(tracker.record(1_000_000_000L))
        assertFalse(tracker.record(1_000_000_000L))
        assertFalse(tracker.record(900_000_000L))
        assertTrue(tracker.record(1_500_000_000L))
        assertEquals(MonotonicRateSnapshot(2L, 500_000_000L, 2.0), tracker.snapshot())

        tracker.reset()
        assertEquals(MonotonicRateSnapshot(0L, 0L, 0.0), tracker.snapshot())
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

    private fun timingEvent(
        analyzedNanos: Long,
        emittedNanos: Long?,
        requestToImageNanos: Long? = 100_000_000L,
        imageAcquisitionNanos: Long = 5_000_000L,
        processorNanos: Long = 10_000_000L,
        listenerPathNanos: Long = 15_000_000L,
    ): CaptureTimingEvent = CaptureTimingEvent(
        analyzedMonotonicTimestampNanos = analyzedNanos,
        emittedMonotonicTimestampNanos = emittedNanos,
        requestToImageLatencyNanos = requestToImageNanos,
        imageAcquisitionDurationNanos = imageAcquisitionNanos,
        processorDurationNanos = processorNanos,
        listenerPathDurationNanos = listenerPathNanos,
    )

    private fun pipelineSnapshot(
        submitted: Long,
        backpressured: Long = 0L,
        superseded: Long = 0L,
        unmatched: Long = 0L,
        failures: Long = 0L,
        lateCallbacks: Long = 0L,
        outstanding: Int = 0,
        maximum: Int = outstanding,
    ): CapturePipelineSnapshot = CapturePipelineSnapshot(
        requestsSubmitted = submitted,
        opportunitiesBackpressured = backpressured,
        requestsSuperseded = superseded,
        imagesWithoutExactRequestMatch = unmatched,
        captureFailures = failures,
        lateCallbacks = lateCallbacks,
        outstandingRequests = outstanding,
        maximumOutstandingRequests = maximum,
    )
}
