// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LivenessMonitorTest {
    @Test
    fun `keepalive is ordered and times out after bounded missed intervals`() {
        val monitor = LivenessMonitor(keepaliveIntervalNs = 100, missedIntervalsBeforeTimeout = 3)
        monitor.connect(1_000)

        assertEquals(LivenessStatus.HEALTHY, monitor.poll(1_099))
        assertEquals(LivenessStatus.KEEPALIVE_DUE, monitor.poll(1_100))
        val nonce = monitor.markKeepaliveSent(1_100)
        assertEquals(1, nonce)
        assertThrows(SecurityException::class.java) { monitor.onKeepaliveResponse(99, 1_150) }
        monitor.onKeepaliveResponse(nonce, 1_150)
        assertEquals(LivenessStatus.KEEPALIVE_DUE, monitor.poll(1_449))
        assertEquals(LivenessStatus.TIMED_OUT, monitor.poll(1_450))
    }

    @Test
    fun `reset removes all connection liveness state`() {
        val monitor = LivenessMonitor(100, 3)
        monitor.connect(10)
        monitor.markKeepaliveSent(110)
        monitor.reset()

        assertEquals(LivenessStatus.DISCONNECTED, monitor.poll(10_000))
        assertThrows(IllegalStateException::class.java) { monitor.markKeepaliveSent(10_000) }
    }
}
