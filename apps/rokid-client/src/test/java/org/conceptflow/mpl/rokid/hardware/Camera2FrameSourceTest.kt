// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.hardware

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

        assertEquals(500L, cadence.intervalMillis())
        cadence.update(5.0)
        assertEquals(200L, cadence.intervalMillis())
        cadence.reset()
        assertEquals(500L, cadence.intervalMillis())
    }

    @Test
    fun physicalCaptureCadenceClampsUnexpectedGateValues() {
        val cadence = AdaptivePhysicalCaptureCadence()

        cadence.update(9.0)
        assertEquals(200L, cadence.intervalMillis())
        cadence.update(0.5)
        assertEquals(500L, cadence.intervalMillis())
    }

    @Test
    fun physicalCaptureCadenceBudgetsFromRequestTimeNotProcessingCompletion() {
        val cadence = AdaptivePhysicalCaptureCadence()
        cadence.recordCaptureRequest(1_000_000_000L)

        assertEquals(300L, cadence.delayAfterCompletionMillis(1_200_000_000L))
        assertEquals(0L, cadence.delayAfterCompletionMillis(1_600_000_000L))
        cadence.update(5.0)
        assertEquals(0L, cadence.delayAfterCompletionMillis(1_250_000_000L))
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
}
