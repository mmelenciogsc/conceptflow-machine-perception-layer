// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleControlLifecycleTest {
    @Test
    fun idlePromotionAndTerminalDemotionAreOrdered() {
        val lifecycle = IdleControlLifecycle()
        assertTrue(lifecycle.onIdleEnabled().showIdleForeground)
        assertEquals(RokidForegroundMode.IDLE, lifecycle.mode)

        val promotion = lifecycle.beforeLiveCapture()
        assertTrue(promotion.showCameraStartingForeground)
        assertEquals(RokidForegroundMode.CAMERA_STARTING, lifecycle.mode)
        val active = lifecycle.onLiveProducersStarted()
        assertTrue(active.showCameraActiveForeground)
        assertEquals(RokidForegroundMode.CAMERA_ACTIVE, lifecycle.mode)
        assertFalse(lifecycle.beforeLiveCapture().showCameraStartingForeground)
        assertFalse(lifecycle.onIdleEnabled().showIdleForeground)
        assertEquals(RokidForegroundMode.CAMERA_ACTIVE, lifecycle.mode)

        val terminal = lifecycle.onLiveTerminal(persistedEnabled = true)
        assertTrue(terminal.showIdleForeground)
        assertFalse(terminal.stopService)
        assertEquals(RokidForegroundMode.IDLE, lifecycle.mode)
    }

    @Test
    fun reconnectDemotesNotificationUntilTheNextProducerPromotion() {
        val lifecycle = IdleControlLifecycle()
        lifecycle.onIdleEnabled()
        lifecycle.beforeLiveCapture()
        lifecycle.onLiveProducersStarted()

        val reconnecting = lifecycle.onLiveReconnecting()
        assertTrue(reconnecting.showIdleForeground)
        assertEquals(RokidForegroundMode.IDLE, lifecycle.mode)

        val resumed = lifecycle.beforeLiveCapture()
        assertTrue(resumed.showCameraStartingForeground)
        assertEquals(RokidForegroundMode.CAMERA_STARTING, lifecycle.mode)
        assertTrue(lifecycle.onLiveProducersStarted().showCameraActiveForeground)
        assertEquals(RokidForegroundMode.CAMERA_ACTIVE, lifecycle.mode)
    }

    @Test
    fun persistenceFailureStillStopsSourcesAndWaitsForLiveTerminal() {
        val lifecycle = IdleControlLifecycle()
        lifecycle.onIdleEnabled()
        lifecycle.beforeLiveCapture()

        val disable = lifecycle.disable(persistenceSucceeded = false, liveCaptureActive = true)
        assertTrue(disable.stopSources)
        assertTrue(disable.requestLiveTerminal)
        assertFalse(disable.stopService)
        assertFalse(disable.persistenceSucceeded)
        assertEquals(RokidForegroundMode.STOPPING, lifecycle.mode)

        val duplicate = lifecycle.disable(persistenceSucceeded = true, liveCaptureActive = true)
        assertFalse(duplicate.stopSources)
        assertFalse(duplicate.requestLiveTerminal)
        assertFalse(duplicate.stopService)

        val terminal = lifecycle.onLiveTerminal(persistedEnabled = true)
        assertTrue(terminal.stopService)
        assertFalse(terminal.showIdleForeground)
        assertEquals(RokidForegroundMode.STOPPED, lifecycle.mode)
    }

    @Test
    fun disableWithoutLiveCaptureCanStopAfterSourcesClose() {
        val lifecycle = IdleControlLifecycle()
        lifecycle.onIdleEnabled()

        val disable = lifecycle.disable(persistenceSucceeded = true, liveCaptureActive = false)

        assertTrue(disable.stopSources)
        assertFalse(disable.requestLiveTerminal)
        assertTrue(disable.stopService)
    }

    @Test
    fun watchdogStopsOnlyAnOutstandingShutdown() {
        val lifecycle = IdleControlLifecycle()
        assertFalse(lifecycle.onShutdownWatchdog().stopService)
        lifecycle.onIdleEnabled()
        lifecycle.beforeLiveCapture()
        lifecycle.disable(persistenceSucceeded = true, liveCaptureActive = true)

        assertTrue(lifecycle.onShutdownWatchdog().stopService)
        assertFalse(lifecycle.onShutdownWatchdog().stopService)
    }
}
