// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.hardware

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.conceptflow.mpl.rokid.core.PixelDimensions

class Camera2FrameSourceTest {
    @Test
    fun physicalCaptureCadenceStartsRelaxedAndRaisesOnlyAfterMotionSignal() {
        val cadence = AdaptivePhysicalCaptureCadence()

        assertEquals(334L, cadence.intervalMillis())
        assertTrue(cadence.update(5.0))
        assertEquals(200L, cadence.intervalMillis())
        assertFalse(cadence.update(5.0))
        cadence.reset()
        assertEquals(334L, cadence.intervalMillis())
    }

    @Test
    fun physicalCaptureCadenceClampsUnexpectedGateValues() {
        val cadence = AdaptivePhysicalCaptureCadence()

        cadence.update(9.0)
        assertEquals(200L, cadence.intervalMillis())
        cadence.update(0.5)
        assertEquals(334L, cadence.intervalMillis())
    }

    @Test
    fun physicalCaptureCadenceBudgetsFromRequestTimeNotProcessingCompletion() {
        val cadence = AdaptivePhysicalCaptureCadence()
        cadence.recordOpportunity(1_000_000_000L)

        assertEquals(134L, cadence.delayUntilNextOpportunityMillis(1_200_000_000L))
        assertEquals(0L, cadence.delayUntilNextOpportunityMillis(1_600_000_000L))
        cadence.update(5.0)
        assertEquals(0L, cadence.delayUntilNextOpportunityMillis(1_250_000_000L))
    }

    @Test
    fun boundedPipelineCapsOutstandingRequestsWithoutAccumulatingMissedOpportunities() {
        val pipeline = BoundedCaptureRequestPipeline(maximumOutstandingRequests = 3)
        pipeline.beginRun(1L)

        val first = pipeline.tryAcquire(1L, 1_000_000_000L)
        val second = pipeline.tryAcquire(1L, 1_200_000_000L)
        val third = pipeline.tryAcquire(1L, 1_400_000_000L)
        assertTrue(first != null)
        assertTrue(second != null)
        assertTrue(third != null)
        assertNull(pipeline.tryAcquire(1L, 1_600_000_000L))

        val capped = pipeline.snapshot()
        assertEquals(3L, capped.requestsSubmitted)
        assertEquals(1L, capped.opportunitiesBackpressured)
        assertEquals(3, capped.outstandingRequests)
        assertEquals(3, capped.maximumOutstandingRequests)

        assertTrue(pipeline.recordCaptureStarted(first!!, 10_000L))
        assertEquals(
            first.requestedAtMonotonicTimestampNanos,
            pipeline.associateLatestImage(1L, 10_000L).requestedAtMonotonicTimestampNanos,
        )
        assertTrue(pipeline.tryAcquire(1L, 1_800_000_000L) != null)
        val resumed = pipeline.snapshot()
        assertEquals(4L, resumed.requestsSubmitted)
        assertEquals(1L, resumed.opportunitiesBackpressured)
        assertEquals(3, resumed.outstandingRequests)
    }

    @Test
    fun latestImageAssociationRetiresSupersededRequestsAndRejectsInventedLatency() {
        val pipeline = BoundedCaptureRequestPipeline(maximumOutstandingRequests = 3)
        pipeline.beginRun(7L)
        val first = pipeline.tryAcquire(7L, 1_000L)!!
        val second = pipeline.tryAcquire(7L, 2_000L)!!
        val third = pipeline.tryAcquire(7L, 3_000L)!!
        assertTrue(pipeline.recordCaptureStarted(first, 10_000L))
        assertTrue(pipeline.recordCaptureStarted(second, 20_000L))
        assertTrue(pipeline.recordCaptureStarted(third, 30_000L))

        val latest = pipeline.associateLatestImage(7L, 30_000L)

        assertTrue(latest.exactTimestampMatch)
        assertEquals(3_000L, latest.requestedAtMonotonicTimestampNanos)
        assertEquals(2, latest.supersededRequestCount)
        assertEquals(0, pipeline.snapshot().outstandingRequests)
        assertEquals(2L, pipeline.snapshot().requestsSuperseded)
        assertFalse(pipeline.recordCaptureFailed(first))

        val unmatched = pipeline.associateLatestImage(7L, 40_000L)
        assertFalse(unmatched.exactTimestampMatch)
        assertNull(unmatched.requestedAtMonotonicTimestampNanos)
        assertEquals(1L, pipeline.snapshot().imagesWithoutExactRequestMatch)
        assertEquals(1L, pipeline.snapshot().lateCallbacks)
    }

    @Test
    fun captureResultCropIsForwardedOnlyForTheExactlyCorrelatedImage() {
        val pipeline = BoundedCaptureRequestPipeline(maximumOutstandingRequests = 2)
        pipeline.beginRun(8L)
        val ticket = pipeline.tryAcquire(8L, 1_000L)!!
        val metadata = CaptureResultCalibrationMetadata(
            cropRegion = CameraCalibrationCrop(336.0, 500.0, 3_360.0, 1_890.0),
            focalLengthMillimeters = 1.9,
        )
        assertTrue(pipeline.recordCaptureStarted(ticket, 20_000L))
        assertTrue(pipeline.recordCaptureCompleted(ticket, 20_000L, metadata))

        val association = pipeline.associateLatestImage(8L, 20_000L)

        assertTrue(association.exactTimestampMatch)
        assertEquals(metadata, association.calibrationMetadata)
    }

    @Test
    fun mismatchedCaptureResultTimestampCannotInfluenceImageCalibration() {
        val pipeline = BoundedCaptureRequestPipeline(maximumOutstandingRequests = 2)
        pipeline.beginRun(9L)
        val ticket = pipeline.tryAcquire(9L, 1_000L)!!
        assertTrue(pipeline.recordCaptureStarted(ticket, 20_000L))
        assertFalse(
            pipeline.recordCaptureCompleted(
                ticket,
                20_001L,
                CaptureResultCalibrationMetadata(
                    cropRegion = CameraCalibrationCrop(0.0, 0.0, 4_032.0, 3_024.0),
                    focalLengthMillimeters = 1.9,
                ),
            ),
        )

        val association = pipeline.associateLatestImage(9L, 20_000L)

        assertTrue(association.exactTimestampMatch)
        assertNull(association.calibrationMetadata)
        assertEquals(1L, pipeline.snapshot().lateCallbacks)
    }

    @Test
    fun verifiedCamera2ResultSatisfiesEveryRequestedCalibrationGate() {
        val metadata = gatedRokidMetadata()
        val intrinsics = resolveCameraIntrinsicsForCapture(
            metadata,
            CaptureResultCalibrationMetadata(
                cropRegion = metadata.captureContract!!.cropRegion,
                focalLengthMillimeters = 1.9,
                unitZoom = true,
                rotateAndCropNone = true,
                distortionCorrectionOff = true,
                videoStabilizationOff = true,
                opticalStabilizationOff = true,
            ),
            PixelDimensions(1_920, 1_080),
        )!!

        assertEquals(904.7619047619, intrinsics.focalXPixels, 1e-9)
        assertEquals(960.0, intrinsics.principalXPixels, 1e-9)
        assertFalse(intrinsics.hasUncertainty())
    }

    @Test
    fun contradictoryCorrelatedCamera2ResultRejectsFrameIntrinsics() {
        val metadata = gatedRokidMetadata()

        val stabilizationContradiction = resolveCameraIntrinsicsForCapture(
            metadata,
            CaptureResultCalibrationMetadata(
                cropRegion = metadata.captureContract!!.cropRegion,
                focalLengthMillimeters = 1.9,
                unitZoom = true,
                rotateAndCropNone = true,
                distortionCorrectionOff = true,
                videoStabilizationOff = false,
                opticalStabilizationOff = true,
            ),
            PixelDimensions(1_920, 1_080),
        )
        val cropContradiction = resolveCameraIntrinsicsForCapture(
            metadata,
            CaptureResultCalibrationMetadata(
                cropRegion = CameraCalibrationCrop(0.0, 0.0, 4_000.0, 3_000.0),
                focalLengthMillimeters = 1.9,
                unitZoom = true,
                rotateAndCropNone = true,
                distortionCorrectionOff = true,
                videoStabilizationOff = true,
                opticalStabilizationOff = true,
            ),
            PixelDimensions(1_920, 1_080),
        )

        assertNull(stabilizationContradiction)
        assertNull(cropContradiction)
    }

    @Test
    fun absentCaptureResultUsesDocumentedStaticDerivedFallback() {
        val intrinsics = resolveCameraIntrinsicsForCapture(
            gatedRokidMetadata(),
            captureResult = null,
            output = PixelDimensions(1_920, 1_080),
        )!!

        assertEquals(540.0, intrinsics.principalYPixels, 1e-9)
        assertFalse(intrinsics.hasUncertainty())
    }

    @Test
    fun pipelineResetClearsBacklogAndLateCallbacksCannotAffectNewRun() {
        val pipeline = BoundedCaptureRequestPipeline(maximumOutstandingRequests = 3)
        pipeline.beginRun(11L)
        val oldTicket = pipeline.tryAcquire(11L, 1_000L)!!
        assertEquals(1, pipeline.snapshot().outstandingRequests)

        val terminal = pipeline.endRun(11L)!!
        assertEquals(1L, terminal.requestsSubmitted)
        assertEquals(0, terminal.outstandingRequests)
        assertEquals(1, terminal.maximumOutstandingRequests)
        pipeline.beginRun(12L)
        val newTicket = pipeline.tryAcquire(12L, 2_000L)!!

        assertTrue(newTicket.sequence > oldTicket.sequence)
        assertFalse(pipeline.recordCaptureStarted(oldTicket, 10_000L))
        assertFalse(pipeline.recordCaptureFailed(oldTicket))
        val staleImage = pipeline.associateLatestImage(11L, 10_000L)
        assertFalse(staleImage.exactTimestampMatch)
        assertNull(staleImage.requestedAtMonotonicTimestampNanos)
        val restarted = pipeline.snapshot()
        assertEquals(1L, restarted.requestsSubmitted)
        assertEquals(1, restarted.outstandingRequests)
        assertEquals(0L, restarted.lateCallbacks)
        assertEquals(0L, restarted.imagesWithoutExactRequestMatch)
    }

    @Test
    fun captureFailureReleasesExactlyOneSlotWithoutBacklogReplay() {
        val pipeline = BoundedCaptureRequestPipeline(maximumOutstandingRequests = 1)
        pipeline.beginRun(21L)
        val failed = pipeline.tryAcquire(21L, 1_000L)!!
        assertNull(pipeline.tryAcquire(21L, 2_000L))

        assertTrue(pipeline.recordCaptureFailed(failed))
        val replacement = pipeline.tryAcquire(21L, 3_000L)

        assertTrue(replacement != null)
        val snapshot = pipeline.snapshot()
        assertEquals(2L, snapshot.requestsSubmitted)
        assertEquals(1L, snapshot.opportunitiesBackpressured)
        assertEquals(1L, snapshot.captureFailures)
        assertEquals(1, snapshot.outstandingRequests)
    }

    @Test
    fun headlessPreviewPrefersExactBoundedSize() {
        val selected = selectHeadlessPreviewSize(
            listOf(
                PixelDimensions(320, 240),
                PixelDimensions(1_920, 1_080),
                PixelDimensions(640, 480),
            ),
        )

        assertEquals(PixelDimensions(640, 480), selected)
    }

    @Test
    fun headlessPreviewRejectsUnboundedSizesAndSelectsClosestArea() {
        val selected = selectHeadlessPreviewSize(
            listOf(
                PixelDimensions(1_920, 1_080),
                PixelDimensions(320, 240),
                PixelDimensions(640, 360),
            ),
        )

        assertEquals(PixelDimensions(640, 360), selected)
        assertNull(selectHeadlessPreviewSize(listOf(PixelDimensions(1_920, 1_080))))
    }

    @Test
    fun permissionFailureWithoutFrameworkExceptionUsesStableMessage() {
        val failure = cameraPermissionFailure()

        assertEquals(CAMERA_PERMISSION_UNAVAILABLE_MESSAGE, failure.message)
        assertNull(failure.cause)
    }

    @Test
    fun permissionRacePreservesFrameworkSecurityException() {
        val securityException = SecurityException("permission revoked")
        val failure = cameraPermissionFailure(securityException)

        assertEquals(CAMERA_PERMISSION_UNAVAILABLE_MESSAGE, failure.message)
        assertSame(securityException, failure.cause)
    }

    @Test
    fun lifecycleEndsOnceIgnoresStaleFailureAndAllowsRestart() {
        val lifecycle = CameraRunLifecycle()
        val firstRun = lifecycle.begin()
        assertTrue(lifecycle.isRunning)
        assertTrue(lifecycle.isActive(firstRun))
        assertThrows(IllegalStateException::class.java) { lifecycle.begin() }

        assertTrue(lifecycle.finish(firstRun))
        assertFalse(lifecycle.finish(firstRun))
        assertFalse(lifecycle.isRunning)

        val restartedRun = lifecycle.begin()
        assertTrue(restartedRun > firstRun)
        assertFalse(lifecycle.finish(firstRun))
        assertTrue(lifecycle.isActive(restartedRun))
        assertEquals(restartedRun, lifecycle.finishCurrent())
        assertNull(lifecycle.finishCurrent())
        assertFalse(lifecycle.isRunning)
    }

    @Test
    fun immediateStopPreventsPendingOpenAction() {
        val lifecycle = CameraRunLifecycle()
        val runId = lifecycle.begin()
        var openCalls = 0

        assertEquals(runId, lifecycle.finishCurrent())
        assertFalse(lifecycle.runIfActive(runId) { openCalls += 1 })
        assertEquals(0, openCalls)
    }

    @Test
    fun activeDispatchCompletesBeforeLifecycleFinishAndNoCallbackStartsAfterward() {
        val lifecycle = CameraRunLifecycle()
        val runId = lifecycle.begin()
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val finishAttempted = CountDownLatch(1)
        val finishResult = AtomicBoolean(false)
        val order = Collections.synchronizedList(mutableListOf<String>())

        val dispatchThread = thread(name = "camera-dispatch-test") {
            lifecycle.runIfActive(runId) {
                order += "callback_started"
                callbackEntered.countDown()
                check(releaseCallback.await(1L, TimeUnit.SECONDS))
                order += "callback_finished"
            }
        }
        assertTrue(callbackEntered.await(1L, TimeUnit.SECONDS))
        val finishThread = thread(name = "camera-finish-test") {
            finishAttempted.countDown()
            finishResult.set(lifecycle.finish(runId))
            order += "lifecycle_finished"
        }
        assertTrue(finishAttempted.await(1L, TimeUnit.SECONDS))

        releaseCallback.countDown()
        dispatchThread.join(1_000L)
        finishThread.join(1_000L)

        assertFalse(dispatchThread.isAlive)
        assertFalse(finishThread.isAlive)
        assertTrue(finishResult.get())
        assertEquals(listOf("callback_started", "callback_finished", "lifecycle_finished"), order)
        assertFalse(lifecycle.runIfActive(runId) { order += "post_stop_callback" })
        assertFalse(order.contains("post_stop_callback"))
    }

    @Test
    fun lateOpenedResourceIsClosedWithoutCreatingSession() {
        val lifecycle = CameraRunLifecycle()
        val runId = lifecycle.begin()
        lifecycle.finishCurrent()
        val camera = FakeCameraResource()

        val attached = attachOrClose(
            camera,
            attach = { lifecycle.runIfActive(runId) {} },
            close = { it.close() },
        )
        if (attached) camera.createSession()

        assertFalse(attached)
        assertEquals(1, camera.closeCalls)
        assertEquals(0, camera.sessionCalls)
    }

    @Test
    fun resourceClosureIsOrderedExceptionSafeAndIdempotent() {
        val closed = mutableListOf<String>()
        val resources = CameraResourceCloser(
            listOf(
                { closed += "handler" },
                {
                    closed += "session"
                    throw IllegalStateException("already closed")
                },
                { closed += "device" },
                { closed += "reader" },
                { closed += "thread" },
            ),
        )

        resources.close()
        resources.close()

        assertEquals(listOf("handler", "session", "device", "reader", "thread"), closed)
    }

    @Test
    fun cameraCallbacksAreDrainedBeforeSharedProcessingStateIsReset() {
        val order = mutableListOf<String>()
        val resources = CameraResourceCloser(
            listOf(
                { order += "callbacks_cancelled" },
                { order += "camera_thread_joined" },
            ),
        )

        drainCameraCallbacksBeforeReset(resources) { order += "processing_state_reset" }

        assertEquals(
            listOf("callbacks_cancelled", "camera_thread_joined", "processing_state_reset"),
            order,
        )
    }

    @Test
    fun terminalCallbackAlwaysClosesItsDeviceExactlyOnce() {
        val lifecycle = CameraRunLifecycle()
        val runId = lifecycle.begin()
        val camera = FakeCameraResource()
        val closer = CallbackResourceCloser<FakeCameraResource> { it.close() }
        val transitions = mutableListOf<Boolean>()

        completeTerminalCallback(camera, closer) { transitions += lifecycle.finish(runId) }
        completeTerminalCallback(camera, closer) { transitions += lifecycle.finish(runId) }

        assertEquals(listOf(true, false), transitions)
        assertEquals(1, camera.closeCalls)
        assertFalse(lifecycle.isRunning)
    }

    @Test
    fun terminalCallbackClosesItsDeviceWhenFailureTransitionThrows() {
        val camera = FakeCameraResource()
        val closer = CallbackResourceCloser<FakeCameraResource> { it.close() }

        assertThrows(IllegalStateException::class.java) {
            completeTerminalCallback(camera, closer) { error("listener failed") }
        }
        assertEquals(1, camera.closeCalls)
    }

    @Test
    fun handlerThreadJoinGuardRejectsCurrentThread() {
        val current = Thread.currentThread()
        assertFalse(shouldJoinCameraThread(current, current))
        assertTrue(shouldJoinCameraThread(Thread("camera-worker"), current))
        assertFalse(shouldJoinCameraThread(null, current))
    }

    @Test
    fun cameraFailureMessagesAreStableAndDoNotExposeFrameworkDetails() {
        assertEquals("Camera could not be opened; capture remains stopped", CAMERA_OPEN_FAILURE_MESSAGE)
        assertEquals("Camera capture failed; capture remains stopped", CAMERA_CAPTURE_FAILURE_MESSAGE)
        assertEquals("Camera disconnected; capture remains stopped", CAMERA_DISCONNECTED_MESSAGE)
    }

    private class FakeCameraResource {
        var closeCalls = 0
        var sessionCalls = 0

        fun close() {
            closeCalls += 1
        }

        fun createSession() {
            sessionCalls += 1
        }
    }

    private fun gatedRokidMetadata(): Camera2CalibrationMetadata {
        val fullArray = CameraCalibrationCrop(0.0, 0.0, 4_032.0, 3_024.0)
        return Camera2CalibrationMetadata(
            intrinsicCalibration = listOf(1_900.0, 1_900.0, 0.0, 0.0, 0.0),
            distortionCoefficients = List(5) { 0.0 },
            coordinateSpace = CameraCalibrationCoordinateSpace(4_032.0, 3_024.0),
            physicalFallback = CameraPhysicalIntrinsicsMetadata(
                focalLengthMillimeters = 1.9,
                sensorPhysicalWidthMillimeters = 4.032,
                sensorPhysicalHeightMillimeters = 3.024,
                pixelArrayWidth = 4_032.0,
                pixelArrayHeight = 3_024.0,
                evidence = CameraPhysicalIntrinsicsEvidence.ROKID_CAMERA2_METADATA_FINGERPRINT,
            ),
            captureContract = CameraCalibrationCaptureContract(
                cropRegion = fullArray,
                focalLengthMillimeters = 1.9,
                requestCropRegion = true,
                verifyCropRegion = true,
                requestFocalLength = true,
                verifyFocalLength = true,
                requestUnitZoom = true,
                verifyUnitZoom = true,
                requestRotateAndCropNone = true,
                verifyRotateAndCropNone = true,
                requestDistortionCorrectionOff = true,
                verifyDistortionCorrectionOff = true,
                requestVideoStabilizationOff = true,
                verifyVideoStabilizationOff = true,
                requestOpticalStabilizationOff = true,
                verifyOpticalStabilizationOff = true,
            ),
        )
    }
}
