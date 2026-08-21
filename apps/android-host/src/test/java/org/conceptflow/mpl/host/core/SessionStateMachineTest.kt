// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateMachineTest {
    @Test
    fun reconnectUsesDeterministicBoundedBackoff() {
        val clock = MutableHostClock(1_000L)
        val machine = SessionStateMachine(clock, ReconnectBackoff(100L, 400L, 3))
        assertEquals(SessionState.Connecting(1), machine.dispatch(SessionEvent.Connect))

        val waiting = machine.dispatch(SessionEvent.TransportFailed("offline", retryable = true))
        assertTrue(waiting is SessionState.WaitingToReconnect)
        waiting as SessionState.WaitingToReconnect
        assertEquals(2, waiting.attempt)
        assertEquals(100_001_000L, waiting.retryAtNanos)
        assertEquals(waiting, machine.dispatch(SessionEvent.RetryTimerElapsed))
        clock.now = waiting.retryAtNanos
        assertEquals(SessionState.Connecting(2), machine.dispatch(SessionEvent.RetryTimerElapsed))

        machine.dispatch(SessionEvent.TransportFailed("offline", retryable = true))
        clock.now += 200_000_000L
        assertEquals(SessionState.Connecting(3), machine.dispatch(SessionEvent.RetryTimerElapsed))
        assertTrue(machine.dispatch(SessionEvent.TransportFailed("offline", retryable = true)) is SessionState.Failed)
    }

    @Test
    fun cancellationClosesActiveSession() {
        val machine = SessionStateMachine(MutableHostClock())
        machine.dispatch(SessionEvent.Connect)
        machine.dispatch(SessionEvent.TransportConnected)
        machine.dispatch(SessionEvent.Negotiated("session"))
        assertTrue(machine.state is SessionState.Active)
        assertEquals(SessionState.Cancelling, machine.dispatch(SessionEvent.Cancel))
        assertEquals(SessionState.Closed, machine.dispatch(SessionEvent.TransportClosed))
    }
}
