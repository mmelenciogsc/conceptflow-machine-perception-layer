// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import java.util.concurrent.TimeUnit

class LeaseExpiredException : IllegalStateException("stream lease expired")

class MonotonicLeaseDeadline private constructor(val expiresAtNs: Long) {
    fun isExpired(nowNs: Long): Boolean {
        require(nowNs >= 0) { "monotonic time must be non-negative" }
        return nowNs >= expiresAtNs
    }

    fun requireActive(nowNs: Long) {
        if (isExpired(nowNs)) throw LeaseExpiredException()
    }

    companion object {
        fun fromDurationMillis(startNs: Long, durationMs: Int): MonotonicLeaseDeadline {
            require(startNs >= 0) { "monotonic time must be non-negative" }
            require(durationMs in 1..MAXIMUM_LIVE_LEASE_MILLIS) {
                "stream lease duration is outside its bound"
            }
            val durationNs = TimeUnit.MILLISECONDS.toNanos(durationMs.toLong())
            return MonotonicLeaseDeadline(Math.addExact(startNs, durationNs))
        }
    }
}

/** Records cross-lane failure while guaranteeing that an expired lease remains the terminal cause. */
internal class ConnectionTermination {
    private data class ObservedFailure(val error: Throwable, val lane: LiveLinkFailureLane)

    private val observed = java.util.concurrent.atomic.AtomicReference<ObservedFailure?>()

    fun record(error: Throwable, lane: LiveLinkFailureLane = LiveLinkFailureLane.NONE) {
        while (true) {
            val current = observed.get()
            if (current?.error is LeaseExpiredException) return
            if (current != null && error !is LeaseExpiredException) return
            if (observed.compareAndSet(current, ObservedFailure(error, lane))) return
        }
    }

    fun failureLane(): LiveLinkFailureLane = observed.get()?.lane ?: LiveLinkFailureLane.NONE

    fun resolve(
        fallback: Throwable,
        deadline: MonotonicLeaseDeadline?,
        nowNs: Long,
        fallbackLane: LiveLinkFailureLane = LiveLinkFailureLane.NONE,
        authenticatedRemoteClose: Boolean = false,
    ): Throwable {
        if (deadline?.isExpired(nowNs) == true) {
            record(LeaseExpiredException())
        } else {
            record(fallback, fallbackLane)
        }
        val terminal = observed.get() ?: return fallback
        return if (authenticatedRemoteClose &&
            terminal.lane != LiveLinkFailureLane.NONE &&
            isAuthenticatedCloseTransportArtifact(terminal.error)
        ) {
            RemoteSessionCompletedException()
        } else {
            terminal.error
        }
    }
}

internal class CameraLaneAdmissionWindow(private val expiresAtNs: Long) {
    init { require(expiresAtNs > 0) }

    fun requireOpen(nowNs: Long, realtimeUsable: Boolean) {
        if (!realtimeUsable) throw java.io.EOFException("realtime lane closed before camera admission")
        if (nowNs >= expiresAtNs) throw java.net.SocketTimeoutException("camera lane admission expired")
    }
}

/** Endpoint-level accounting survives deliberate per-connection state destruction. */
internal class EndpointMetricAccounting(private val metrics: SanitizedTransportMetrics) {
    private var establishedConnections = 0L

    @Synchronized
    fun established() {
        if (establishedConnections > 0) metrics.recordReconnect()
        establishedConnections = Math.addExact(establishedConnections, 1L)
    }

    fun failure(error: Throwable) {
        when {
            error is FramingException -> metrics.recordFramingFailure()
            error is CameraTicketException -> Unit // LiveConnectionState records ticket failures.
            classifyDisconnect(error) == LiveLinkDisconnectReason.AUTHENTICATION -> {
                metrics.recordAuthenticationFailure()
            }
        }
    }
}
