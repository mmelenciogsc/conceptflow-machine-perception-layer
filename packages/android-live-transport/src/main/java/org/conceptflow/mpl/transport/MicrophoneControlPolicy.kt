// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import org.conceptflow.mpl.v1.MicrophoneControlIntent
import org.conceptflow.mpl.v1.MicrophoneControlOperation
import org.conceptflow.mpl.v1.MicrophoneControlResult

enum class MicrophoneGestureDispatch {
    QUEUED,
    NO_AUTHENTICATED_SESSION,
}

data class MicrophoneGestureResult(
    val intentId: Long,
    val operation: MicrophoneControlOperation,
    val accepted: Boolean,
) {
    init {
        require(intentId > 0L)
        require(operation == MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_START ||
            operation == MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_STOP
        )
    }
}

internal enum class MicrophoneIntentRejection(val wireName: String) {
    WRONG_BINDING("wrong_binding"),
    MALFORMED("malformed"),
    REPLAY("replay"),
    STALE("stale"),
    UNAVAILABLE("unavailable"),
}

/** Stateful host-side admission for wearer gesture intents on one authenticated connection. */
internal class MicrophoneIntentGuard(
    private val binding: LiveSessionBinding,
    private val maximumAgeNanos: Long = MAXIMUM_MICROPHONE_INTENT_AGE_NANOS,
) {
    private var lastAcceptedIntentId = 0L

    init {
        require(maximumAgeNanos in 1L..5_000_000_000L)
    }

    @Synchronized
    fun validateStructure(
        intent: MicrophoneControlIntent,
        envelopeSentMonotonicNs: Long,
    ): MicrophoneIntentRejection? {
        if (intent.sessionId != binding.sessionId || intent.leaseId != binding.leaseId) {
            return MicrophoneIntentRejection.WRONG_BINDING
        }
        val operationValid = intent.operation == MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_START ||
            intent.operation == MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_STOP
        val durationValid = when (intent.operation) {
            MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_START ->
                intent.requestedDurationMs in 1..MAXIMUM_MICROPHONE_LEASE_MILLIS
            MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_STOP -> intent.requestedDurationMs == 0
            else -> false
        }
        if (intent.intentId <= 0L || intent.createdMonotonicTimestampNs <= 0L || !intent.userRequested ||
            !operationValid || !durationValid || envelopeSentMonotonicNs < intent.createdMonotonicTimestampNs ||
            envelopeSentMonotonicNs - intent.createdMonotonicTimestampNs > maximumAgeNanos
        ) {
            return MicrophoneIntentRejection.MALFORMED
        }
        return null
    }

    @Synchronized
    fun acceptFresh(
        intent: MicrophoneControlIntent,
        normalizedCreatedHostNs: Long,
        receiveHostNs: Long,
        clockUncertaintyNs: Long,
    ): MicrophoneIntentRejection? {
        if (normalizedCreatedHostNs < 0L || receiveHostNs < 0L || clockUncertaintyNs < 0L) {
            return MicrophoneIntentRejection.MALFORMED
        }
        if (intent.intentId <= lastAcceptedIntentId) return MicrophoneIntentRejection.REPLAY
        val earliest = saturatingSubtract(receiveHostNs, saturatingAdd(maximumAgeNanos, clockUncertaintyNs))
        val latest = saturatingAdd(receiveHostNs, clockUncertaintyNs)
        if (normalizedCreatedHostNs !in earliest..latest) return MicrophoneIntentRejection.STALE
        lastAcceptedIntentId = intent.intentId
        return null
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private fun saturatingSubtract(left: Long, right: Long): Long =
        if (left < right) 0L else left - right
}

/** Tracks the latest local wearer intent so a delayed START lease cannot overtake STOP. */
internal class GlassesMicrophoneIntentTracker {
    private var latestIntentId = 0L
    private var latestOperation = MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_UNSPECIFIED
    private var awaitingResult = false

    @Synchronized
    fun record(intentId: Long, operation: MicrophoneControlOperation) {
        require(intentId > latestIntentId)
        require(operation == MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_START ||
            operation == MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_STOP
        )
        latestIntentId = intentId
        latestOperation = operation
        awaitingResult = true
    }

    @Synchronized
    fun permitsLease(originatingIntentId: Long): Boolean =
        latestOperation != MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_STOP &&
            (originatingIntentId == 0L ||
                originatingIntentId in 1L..latestIntentId &&
                latestOperation == MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_START)

    @Synchronized
    fun acceptResult(result: MicrophoneControlResult): MicrophoneGestureResult? {
        if (!awaitingResult || result.intentId != latestIntentId || result.operation != latestOperation) return null
        awaitingResult = false
        return MicrophoneGestureResult(result.intentId, result.operation, result.accepted).also {
            // The STOP result is ordered after earlier host writes on the realtime TLS lane.
            // Release the temporary STOP barrier so a later phone-button lease remains usable.
            if (result.operation == MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_STOP) {
                latestOperation = MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_UNSPECIFIED
            }
        }
    }

    @Synchronized
    fun resetConnection() {
        awaitingResult = false
    }
}

internal data class PendingMicrophoneRequest(
    val durationMillis: Int,
    val originatingIntentId: Long,
    var written: Boolean = false,
)

internal enum class MicrophoneResponseDisposition {
    PENDING,
    CANCELLED,
    UNRELATED,
}

/** Correlates host-issued subleases and makes STOP win over requests already on the wire. */
internal class HostMicrophoneLeaseGate(private val leaseDeadline: MonotonicLeaseDeadline) {
    private var pending: PendingMicrophoneRequest? = null
    private val cancelledResponseOrigins = mutableSetOf<Long>()
    private var activeUntilNs = 0L

    @Synchronized
    fun hasPendingRequest(): Boolean = pending != null

    @Synchronized
    fun hasCancelledResponse(originatingIntentId: Long): Boolean =
        originatingIntentId in cancelledResponseOrigins

    @Synchronized
    fun reserve(durationMillis: Int, originatingIntentId: Long) {
        require(durationMillis in 1..MAXIMUM_MICROPHONE_LEASE_MILLIS)
        require(originatingIntentId >= 0L)
        check(pending == null)
        pending = PendingMicrophoneRequest(durationMillis, originatingIntentId)
    }

    @Synchronized
    fun isReserved(durationMillis: Int, originatingIntentId: Long): Boolean =
        pending?.let { it.durationMillis == durationMillis && it.originatingIntentId == originatingIntentId } == true

    @Synchronized
    fun markRequestWritten(originatingIntentId: Long) {
        val request = checkNotNull(pending).also {
            check(it.originatingIntentId == originatingIntentId)
        }
        request.written = true
    }

    @Synchronized
    fun cancelUnwrittenRequest(originatingIntentId: Long) {
        val request = pending
        if (request != null && request.originatingIntentId == originatingIntentId && !request.written) {
            pending = null
        }
    }

    @Synchronized
    fun revoke() {
        pending?.let { request ->
            if (request.written) cancelledResponseOrigins += request.originatingIntentId
        }
        pending = null
        activeUntilNs = 0L
    }

    @Synchronized
    fun acceptResponse(originatingIntentId: Long): MicrophoneResponseDisposition = when {
        pending?.originatingIntentId == originatingIntentId -> {
            pending = null
            MicrophoneResponseDisposition.PENDING
        }
        cancelledResponseOrigins.remove(originatingIntentId) -> MicrophoneResponseDisposition.CANCELLED
        else -> MicrophoneResponseDisposition.UNRELATED
    }

    @Synchronized
    fun setAuthorizedUntil(deadlineNs: Long) {
        require(deadlineNs >= 0L)
        activeUntilNs = deadlineNs
    }

    @Synchronized
    fun isAuthorized(nowNs: Long): Boolean = nowNs < activeUntilNs && !leaseDeadline.isExpired(nowNs)
}

internal const val MAXIMUM_MICROPHONE_INTENT_AGE_NANOS = 1_000_000_000L
