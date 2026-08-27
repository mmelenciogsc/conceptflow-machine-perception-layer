// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import java.util.concurrent.atomic.AtomicReference
import org.conceptflow.mpl.v1.RokidGestureIntent
import org.conceptflow.mpl.v1.RokidGestureOperation
import org.conceptflow.mpl.v1.RokidNodeCommand
import org.conceptflow.mpl.v1.RokidNodeCommandOperation
import org.conceptflow.mpl.v1.RokidNodeCommandResult

enum class RokidGestureDispatch {
    QUEUED,
    TRANSPORT_STOPPED,
}

enum class RokidNodeCommandDispatch {
    REQUESTED,
    NO_AUTHENTICATED_SESSION,
}

data class RokidNodeCommandDelivery(
    val commandId: Long,
    val originatingGestureId: Long,
    val operation: RokidNodeCommandOperation,
    val acceptedForExecution: Boolean,
)

internal data class PendingRokidGesture(
    val gestureId: Long,
    val observedMonotonicNs: Long,
    val operation: RokidGestureOperation,
)

/** Latest-wins, retry-safe outbox. A gesture is removed only after a successful socket write. */
internal class RokidGestureOutbox(
    private val maximumAgeNanos: Long = MAXIMUM_ROKID_GESTURE_AGE_NANOS,
) {
    private val pending = AtomicReference<PendingRokidGesture?>()

    init {
        require(maximumAgeNanos > 0L)
    }

    fun replace(gesture: PendingRokidGesture) {
        require(gesture.gestureId > 0L && gesture.observedMonotonicNs > 0L)
        pending.set(gesture)
    }

    fun peekFresh(nowNanos: Long): PendingRokidGesture? {
        require(nowNanos >= 0L)
        while (true) {
            val candidate = pending.get() ?: return null
            val age = nowNanos - candidate.observedMonotonicNs
            if (age in 0L..maximumAgeNanos) return candidate
            if (pending.compareAndSet(candidate, null)) return null
        }
    }

    fun acknowledgeWritten(gesture: PendingRokidGesture): Boolean =
        pending.compareAndSet(gesture, null)

    fun clear() {
        pending.set(null)
    }
}

internal enum class RokidNodeControlRejection(val wireName: String) {
    WRONG_BINDING("wrong_binding"),
    MALFORMED("malformed"),
    REPLAY("replay"),
    STALE("stale"),
    UNAVAILABLE("unavailable"),
}

object RokidGestureCommandPolicy {
    fun commandFor(operation: RokidGestureOperation): RokidNodeCommandOperation? = when (operation) {
        RokidGestureOperation.ROKID_GESTURE_OPERATION_ENABLE_NODE ->
            RokidNodeCommandOperation.ROKID_NODE_COMMAND_OPERATION_ACTIVATE_NODE
        RokidGestureOperation.ROKID_GESTURE_OPERATION_DISABLE_NODE ->
            RokidNodeCommandOperation.ROKID_NODE_COMMAND_OPERATION_SLEEP_NODE
        else -> null
    }
}

internal class HostRokidGestureGuard(
    private val binding: LiveSessionBinding,
    private val maximumAgeNanos: Long = MAXIMUM_ROKID_GESTURE_AGE_NANOS,
) {
    private var lastAcceptedGestureId = 0L

    init {
        require(maximumAgeNanos in 1L..30_000_000_000L)
    }

    @Synchronized
    fun validateStructure(
        intent: RokidGestureIntent,
        envelopeSentMonotonicNs: Long,
    ): RokidNodeControlRejection? {
        if (intent.sessionId != binding.sessionId || intent.leaseId != binding.leaseId) {
            return RokidNodeControlRejection.WRONG_BINDING
        }
        if (intent.gestureId <= 0L || intent.observedMonotonicTimestampNs <= 0L ||
            !intent.userInitiated || RokidGestureCommandPolicy.commandFor(intent.operation) == null ||
            envelopeSentMonotonicNs < intent.observedMonotonicTimestampNs ||
            envelopeSentMonotonicNs - intent.observedMonotonicTimestampNs > maximumAgeNanos
        ) {
            return RokidNodeControlRejection.MALFORMED
        }
        return null
    }

    @Synchronized
    fun acceptFresh(
        intent: RokidGestureIntent,
        normalizedObservedHostNs: Long,
        receiveHostNs: Long,
        clockUncertaintyNs: Long,
    ): RokidNodeControlRejection? {
        if (normalizedObservedHostNs < 0L || receiveHostNs < 0L || clockUncertaintyNs < 0L) {
            return RokidNodeControlRejection.MALFORMED
        }
        if (intent.gestureId <= lastAcceptedGestureId) return RokidNodeControlRejection.REPLAY
        val tolerance = saturatingAdd(maximumAgeNanos, clockUncertaintyNs)
        val earliest = saturatingSubtract(receiveHostNs, tolerance)
        val latest = saturatingAdd(receiveHostNs, clockUncertaintyNs)
        if (normalizedObservedHostNs !in earliest..latest) return RokidNodeControlRejection.STALE
        lastAcceptedGestureId = intent.gestureId
        return null
    }
}

internal class GlassesRokidNodeCommandGuard(private val binding: LiveSessionBinding) {
    private var lastAcceptedCommandId = 0L

    @Synchronized
    fun accept(command: RokidNodeCommand, envelopeSentMonotonicNs: Long): RokidNodeControlRejection? {
        if (command.sessionId != binding.sessionId || command.leaseId != binding.leaseId) {
            return RokidNodeControlRejection.WRONG_BINDING
        }
        val ttlNanos = command.validForMs.toLong() * 1_000_000L
        if (command.commandId <= 0L || command.issuedMonotonicTimestampNs <= 0L ||
            command.validForMs !in 1..MAXIMUM_ROKID_COMMAND_TTL_MILLIS ||
            envelopeSentMonotonicNs < command.issuedMonotonicTimestampNs ||
            envelopeSentMonotonicNs - command.issuedMonotonicTimestampNs > ttlNanos ||
            !command.userAuthorized || command.operation !in ALLOWED_NODE_COMMANDS
        ) {
            return RokidNodeControlRejection.MALFORMED
        }
        if (command.commandId <= lastAcceptedCommandId) return RokidNodeControlRejection.REPLAY
        lastAcceptedCommandId = command.commandId
        return null
    }
}

internal class HostRokidNodeCommandTracker {
    private val pending = linkedMapOf<Long, RokidNodeCommand>()

    @Synchronized
    fun record(command: RokidNodeCommand) {
        require(command.commandId > 0L)
        check(command.commandId !in pending)
        while (pending.size >= MAXIMUM_PENDING_NODE_COMMANDS) {
            pending.remove(pending.keys.first())
        }
        pending[command.commandId] = command
    }

    @Synchronized
    fun discard(commandId: Long) {
        pending.remove(commandId)
    }

    @Synchronized
    fun accept(result: RokidNodeCommandResult): RokidNodeCommandDelivery? {
        val command = pending[result.commandId] ?: return null
        if (result.sessionId != command.sessionId || result.leaseId != command.leaseId ||
            result.originatingGestureId != command.originatingGestureId ||
            result.operation != command.operation ||
            result.acceptedForExecution == result.hasError()
        ) {
            return null
        }
        pending.remove(result.commandId)
        return RokidNodeCommandDelivery(
            result.commandId,
            result.originatingGestureId,
            result.operation,
            result.acceptedForExecution,
        )
    }
}

private fun saturatingAdd(left: Long, right: Long): Long =
    if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

private fun saturatingSubtract(left: Long, right: Long): Long =
    if (left < right) 0L else left - right

internal const val MAXIMUM_ROKID_GESTURE_AGE_NANOS = 15_000_000_000L
internal const val MAXIMUM_ROKID_COMMAND_TTL_MILLIS = 2_000
private const val MAXIMUM_PENDING_NODE_COMMANDS = 8
private val ALLOWED_NODE_COMMANDS = setOf(
    RokidNodeCommandOperation.ROKID_NODE_COMMAND_OPERATION_ACTIVATE_NODE,
    RokidNodeCommandOperation.ROKID_NODE_COMMAND_OPERATION_SLEEP_NODE,
    RokidNodeCommandOperation.ROKID_NODE_COMMAND_OPERATION_PLAY_BRAND_SEQUENCE,
)
