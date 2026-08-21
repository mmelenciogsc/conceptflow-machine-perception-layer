// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSourceStateControllerTest {
    @Test
    fun matchingAsynchronousFailureStopsAndClearsSource() {
        val controller = FrameSourceStateController()
        val source = FakeFrameSource()
        assertTrue(controller.attach(source))

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

    private class FakeFrameSource : FrameSource {
        var stopped = false
        override val isRunning: Boolean get() = !stopped
        override fun start(listener: FrameSource.Listener) = Unit
        override fun stop() {
            stopped = true
        }
    }
}
