// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.conceptflow.mpl.v1.SensorStreamKind
import java.util.UUID

/** Identity already authenticated by the transport. A caller-supplied string is not authentication. */
data class AuthenticatedStreamPeer(val id: String) {
    init {
        require(id.isNotBlank() && id.length <= 128)
    }
}

data class StreamLeasePolicy(
    val maximumLeaseDurationMillis: Long = 120_000L,
    val maximumMicrophoneDurationMillis: Long = 10_000L,
) {
    init {
        require(maximumLeaseDurationMillis in 1_000L..600_000L)
        require(maximumMicrophoneDurationMillis in 250L..maximumLeaseDurationMillis)
    }
}

data class StreamLeaseSpec(
    val sessionId: String,
    val requestedStreams: Set<SensorStreamKind>,
    val requestedDurationMillis: Long,
    val userRequestedMicrophone: Boolean = false,
) {
    init {
        require(sessionId.isNotBlank() && sessionId.length <= 128)
        require(requestedStreams.isNotEmpty())
        require(requestedDurationMillis > 0L)
        require(requestedStreams.all { it in SUPPORTED_STREAMS })
    }

    companion object {
        val SUPPORTED_STREAMS = setOf(
            SensorStreamKind.SENSOR_STREAM_KIND_CAMERA,
            SensorStreamKind.SENSOR_STREAM_KIND_IMU,
            SensorStreamKind.SENSOR_STREAM_KIND_MICROPHONE,
            SensorStreamKind.SENSOR_STREAM_KIND_TOUCH,
        )
    }
}

data class ActiveStreamLease(
    val leaseId: String,
    val peer: AuthenticatedStreamPeer,
    val sessionId: String,
    val streams: Set<SensorStreamKind>,
    val openedAtNanos: Long,
    val expiresAtNanos: Long,
    val microphoneExpiresAtNanos: Long?,
) {
    fun permits(stream: SensorStreamKind, nowNanos: Long): Boolean {
        if (nowNanos < openedAtNanos || nowNanos >= expiresAtNanos || stream !in streams) return false
        return stream != SensorStreamKind.SENSOR_STREAM_KIND_MICROPHONE ||
            nowNanos < (microphoneExpiresAtNanos ?: return false)
    }
}

sealed interface StreamLeaseDecision {
    data class Granted(val lease: ActiveStreamLease) : StreamLeaseDecision
    data class Rejected(val reason: String) : StreamLeaseDecision
}

class StreamLeaseController(
    private val clock: MonotonicClock,
    private val policy: StreamLeasePolicy = StreamLeasePolicy(),
    private val leaseIdFactory: () -> String = { "lease-${UUID.randomUUID()}" },
) {
    private var active: ActiveStreamLease? = null

    @Synchronized
    fun open(peer: AuthenticatedStreamPeer, spec: StreamLeaseSpec): StreamLeaseDecision {
        expireLocked(clock.nowNanos())
        if (active != null) return StreamLeaseDecision.Rejected("another lease is active")
        val now = clock.nowNanos()
        val durationMillis = spec.requestedDurationMillis.coerceAtMost(policy.maximumLeaseDurationMillis)
        val streams = spec.requestedStreams.toMutableSet()
        if (!spec.userRequestedMicrophone) {
            streams.remove(SensorStreamKind.SENSOR_STREAM_KIND_MICROPHONE)
        }
        if (streams.isEmpty()) return StreamLeaseDecision.Rejected("no streams were authorized")
        val expiresAt = saturatingNanosAfter(now, durationMillis)
        val microphoneExpiresAt = if (SensorStreamKind.SENSOR_STREAM_KIND_MICROPHONE in streams) {
            saturatingNanosAfter(now, minOf(durationMillis, policy.maximumMicrophoneDurationMillis))
        } else {
            null
        }
        val leaseId = leaseIdFactory()
        if (leaseId.isBlank() || leaseId.length > 128) {
            return StreamLeaseDecision.Rejected("lease identifier generation failed")
        }
        return ActiveStreamLease(
            leaseId = leaseId,
            peer = peer,
            sessionId = spec.sessionId,
            streams = streams.toSet(),
            openedAtNanos = now,
            expiresAtNanos = expiresAt,
            microphoneExpiresAtNanos = microphoneExpiresAt,
        ).also { active = it }.let(StreamLeaseDecision::Granted)
    }

    @Synchronized
    fun renew(
        peer: AuthenticatedStreamPeer,
        leaseId: String,
        requestedDurationMillis: Long,
    ): StreamLeaseDecision {
        require(requestedDurationMillis > 0L)
        val now = clock.nowNanos()
        val current = expireLocked(now) ?: return StreamLeaseDecision.Rejected("lease is not active")
        if (current.peer != peer || current.leaseId != leaseId) {
            return StreamLeaseDecision.Rejected("lease ownership does not match")
        }
        val durationMillis = requestedDurationMillis.coerceAtMost(policy.maximumLeaseDurationMillis)
        val renewedExpiry = saturatingNanosAfter(now, durationMillis)
        // Renewal cannot silently extend or reactivate microphone consent.
        val microphoneExpiry = current.microphoneExpiresAtNanos?.let { minOf(it, renewedExpiry) }
        val renewed = current.copy(
            expiresAtNanos = renewedExpiry,
            microphoneExpiresAtNanos = microphoneExpiry,
        )
        active = renewed
        return StreamLeaseDecision.Granted(renewed)
    }

    @Synchronized
    fun close(peer: AuthenticatedStreamPeer, leaseId: String): Boolean {
        val current = active ?: return false
        if (current.peer != peer || current.leaseId != leaseId) return false
        active = null
        return true
    }

    @Synchronized
    fun current(): ActiveStreamLease? = expireLocked(clock.nowNanos())

    @Synchronized
    fun clear(): ActiveStreamLease? = active.also { active = null }

    private fun expireLocked(nowNanos: Long): ActiveStreamLease? {
        val current = active ?: return null
        if (nowNanos >= current.expiresAtNanos) {
            active = null
            return null
        }
        return current
    }
}

private fun saturatingNanosAfter(nowNanos: Long, durationMillis: Long): Long {
    require(nowNanos >= 0L)
    val durationNanos = if (durationMillis > Long.MAX_VALUE / 1_000_000L) {
        Long.MAX_VALUE
    } else {
        durationMillis * 1_000_000L
    }
    return if (Long.MAX_VALUE - nowNanos < durationNanos) Long.MAX_VALUE else nowNanos + durationNanos
}
