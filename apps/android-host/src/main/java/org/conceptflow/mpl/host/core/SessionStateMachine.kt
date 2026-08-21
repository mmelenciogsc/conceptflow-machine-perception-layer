// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.core

data class ReconnectBackoff(
    val initialMillis: Long = 250L,
    val maximumMillis: Long = 8_000L,
    val maximumAttempts: Int = 6,
) {
    init {
        require(initialMillis > 0L)
        require(maximumMillis >= initialMillis)
        require(maximumAttempts in 1..20)
    }

    fun delayMillis(attempt: Int): Long {
        require(attempt >= 1)
        var value = initialMillis
        repeat((attempt - 1).coerceAtMost(62)) {
            value = if (value >= maximumMillis / 2L) maximumMillis else value * 2L
        }
        return value.coerceAtMost(maximumMillis)
    }
}

sealed interface SessionState {
    data object Idle : SessionState
    data class Connecting(val attempt: Int) : SessionState
    data object Negotiating : SessionState
    data class Active(val sessionId: String) : SessionState
    data class WaitingToReconnect(val attempt: Int, val retryAtNanos: Long) : SessionState
    data object Cancelling : SessionState
    data object Closed : SessionState
    data class Failed(val reason: String) : SessionState
}

sealed interface SessionEvent {
    data object Connect : SessionEvent
    data object TransportConnected : SessionEvent
    data class Negotiated(val sessionId: String) : SessionEvent
    data class TransportFailed(val reason: String, val retryable: Boolean) : SessionEvent
    data object RetryTimerElapsed : SessionEvent
    data object Cancel : SessionEvent
    data object TransportClosed : SessionEvent
    data object Disconnect : SessionEvent
}

class SessionStateMachine(
    private val clock: HostClock,
    private val backoff: ReconnectBackoff = ReconnectBackoff(),
) {
    var state: SessionState = SessionState.Idle
        private set

    @Synchronized
    fun dispatch(event: SessionEvent): SessionState {
        state = reduce(state, event)
        return state
    }

    private fun reduce(current: SessionState, event: SessionEvent): SessionState = when (event) {
        SessionEvent.Connect -> if (current is SessionState.Idle || current is SessionState.Closed) {
            SessionState.Connecting(attempt = 1)
        } else current
        SessionEvent.TransportConnected -> if (current is SessionState.Connecting) SessionState.Negotiating else current
        is SessionEvent.Negotiated -> if (current is SessionState.Negotiating && event.sessionId.isNotBlank()) {
            SessionState.Active(event.sessionId)
        } else current
        is SessionEvent.TransportFailed -> onFailure(current, event)
        SessionEvent.RetryTimerElapsed -> if (
            current is SessionState.WaitingToReconnect && clock.nowNanos() >= current.retryAtNanos
        ) {
            SessionState.Connecting(current.attempt)
        } else current
        SessionEvent.Cancel -> when (current) {
            SessionState.Idle, SessionState.Closed -> SessionState.Closed
            else -> SessionState.Cancelling
        }
        SessionEvent.TransportClosed -> if (current is SessionState.Cancelling) SessionState.Closed else current
        SessionEvent.Disconnect -> SessionState.Closed
    }

    private fun onFailure(current: SessionState, failure: SessionEvent.TransportFailed): SessionState {
        if (!failure.retryable) return SessionState.Failed(failure.reason)
        val failedAttempt = when (current) {
            is SessionState.Connecting -> current.attempt
            is SessionState.WaitingToReconnect -> current.attempt
            else -> 1
        }
        if (failedAttempt >= backoff.maximumAttempts) return SessionState.Failed(failure.reason)
        val nextAttempt = failedAttempt + 1
        val retryAt = clock.nowNanos() + backoff.delayMillis(failedAttempt) * 1_000_000L
        return SessionState.WaitingToReconnect(nextAttempt, retryAt)
    }
}
