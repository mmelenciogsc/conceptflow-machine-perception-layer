// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSourceStateControllerTest {
    @Test
    fun matchingAsynchronousFailureStopsAndClearsSource() {
        val controller = FrameSourceStateController()
        val source = FakeFrameSource()
        val unrelated = FakeFrameSource()
        assertTrue(controller.attach(source))
        assertFalse(controller.stopIfCurrent(unrelated))
        assertTrue(controller.isCurrent(source))
        assertFalse(unrelated.stopped)

        assertTrue(controller.stopIfCurrent(source))

        assertFalse(controller.hasActiveSource)
        assertTrue(source.stopped)
    }

    @Test
    fun staleFailureCannotStopNewlyRestartedSource() {
        val controller = FrameSourceStateController()
        val oldSource = FakeFrameSource()
        val restartedSource = FakeFrameSource()
        assertTrue(controller.attach(oldSource))
        assertTrue(controller.stopCurrent())
        assertTrue(controller.attach(restartedSource))

        assertFalse(controller.stopIfCurrent(oldSource))

        assertTrue(controller.isCurrent(restartedSource))
        assertFalse(restartedSource.stopped)
    }

    @Test
    fun stopCurrentReleasesControllerMonitorBeforeSourceCloseCompletes() {
        val controller = FrameSourceStateController()
        val source = BlockingFrameSource()
        val replacement = FakeFrameSource()
        assertTrue(controller.attach(source))
        val stopResult = AtomicBoolean(false)
        val stopThread = thread(name = "frame-source-stop") {
            stopResult.set(controller.stopCurrent())
        }
        assertTrue(source.closeEntered.await(1L, TimeUnit.SECONDS))

        val queryResult = AtomicBoolean(true)
        val queryCompleted = CountDownLatch(1)
        val queryThread = thread(name = "frame-source-query") {
            queryResult.set(controller.isCurrent(source))
            queryCompleted.countDown()
        }
        try {
            assertTrue(queryCompleted.await(2L, TimeUnit.SECONDS))
            assertFalse(queryResult.get())
            assertTrue(controller.hasActiveSource)
            assertFalse(controller.attach(replacement))
        } finally {
            source.allowClose.countDown()
        }
        stopThread.join(1_000L)
        queryThread.join(1_000L)

        assertFalse(stopThread.isAlive)
        assertFalse(queryThread.isAlive)
        assertTrue(stopResult.get())
        assertTrue(source.stopped)
        assertFalse(controller.hasActiveSource)
        assertTrue(controller.attach(replacement))
        assertTrue(controller.isCurrent(replacement))
    }

    @Test
    fun closeFailureClearsOwnershipTransitionForNextAttach() {
        val controller = FrameSourceStateController()
        assertTrue(controller.attach(ThrowingFrameSource()))

        assertThrows(IllegalStateException::class.java) { controller.stopCurrent() }

        assertFalse(controller.hasActiveSource)
        assertTrue(controller.attach(FakeFrameSource()))
    }

    private class FakeFrameSource : FrameSource {
        var stopped = false
        override val isRunning: Boolean get() = !stopped
        override fun start(listener: FrameSource.Listener) = Unit
        override fun stop() {
            stopped = true
        }
    }

    private class BlockingFrameSource : FrameSource {
        val closeEntered = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        @Volatile var stopped = false
            private set

        override val isRunning: Boolean get() = !stopped
        override fun start(listener: FrameSource.Listener) = Unit

        override fun stop() {
            closeEntered.countDown()
            check(allowClose.await(5L, TimeUnit.SECONDS))
            stopped = true
        }
    }

    private class ThrowingFrameSource : FrameSource {
        override val isRunning: Boolean = true
        override fun start(listener: FrameSource.Listener) = Unit
        override fun stop() = throw IllegalStateException("close failed")
    }
}
