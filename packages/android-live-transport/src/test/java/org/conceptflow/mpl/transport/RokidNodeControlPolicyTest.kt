// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import org.conceptflow.mpl.v1.ErrorCode
import org.conceptflow.mpl.v1.ErrorStatus
import org.conceptflow.mpl.v1.RokidGestureIntent
import org.conceptflow.mpl.v1.RokidGestureOperation
import org.conceptflow.mpl.v1.RokidNodeCommand
import org.conceptflow.mpl.v1.RokidNodeCommandOperation
import org.conceptflow.mpl.v1.RokidNodeCommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RokidNodeControlPolicyTest {
    private val binding = LiveSessionBinding("session", "lease", ByteArray(32) { 7 })

    @Test
    fun `gesture guard accepts fresh exact-bound intents once`() {
        val intent = gesture(7L, 2_000_000_000L)
        val guard = HostRokidGestureGuard(binding)

        assertNull(guard.validateStructure(intent, 2_100_000_000L))
        assertNull(guard.acceptFresh(intent, 4_000_000_000L, 4_100_000_000L, 10_000_000L))
        assertEquals(
            RokidNodeControlRejection.REPLAY,
            guard.acceptFresh(intent, 4_000_000_000L, 4_100_000_000L, 10_000_000L),
        )
    }

    @Test
    fun `gesture guard rejects stale malformed and wrong-bound intents`() {
        val guard = HostRokidGestureGuard(binding, maximumAgeNanos = 1_000_000_000L)
        assertEquals(
            RokidNodeControlRejection.STALE,
            guard.acceptFresh(gesture(1L, 1L), 1L, 2_000_000_000L, 0L),
        )
        assertEquals(
            RokidNodeControlRejection.WRONG_BINDING,
            guard.validateStructure(gesture(2L, 2L).toBuilder().setSessionId("other").build(), 3L),
        )
        assertEquals(
            RokidNodeControlRejection.MALFORMED,
            guard.validateStructure(gesture(3L, 2L).toBuilder().setUserInitiated(false).build(), 3L),
        )
    }

    @Test
    fun `command guard enforces binding authorization ttl and replay`() {
        val guard = GlassesRokidNodeCommandGuard(binding)
        val command = command(9L)

        assertNull(guard.accept(command, 8_100_000_000L))
        assertEquals(RokidNodeControlRejection.REPLAY, guard.accept(command, 8_100_000_000L))
        assertEquals(
            RokidNodeControlRejection.WRONG_BINDING,
            GlassesRokidNodeCommandGuard(binding).accept(
                command.toBuilder().setLeaseId("other").build(),
                8_100_000_000L,
            ),
        )
        assertEquals(
            RokidNodeControlRejection.MALFORMED,
            GlassesRokidNodeCommandGuard(binding).accept(
                command.toBuilder().setValidForMs(MAXIMUM_ROKID_COMMAND_TTL_MILLIS + 1).build(),
                8_100_000_000L,
            ),
        )
        assertEquals(
            RokidNodeControlRejection.MALFORMED,
            GlassesRokidNodeCommandGuard(binding).accept(command, 10_000_000_001L),
        )
        assertEquals(
            RokidNodeControlRejection.MALFORMED,
            GlassesRokidNodeCommandGuard(binding).accept(command, 7_999_999_999L),
        )
    }

    @Test
    fun `tracker accepts only exact successful or failed result`() {
        val tracker = HostRokidNodeCommandTracker()
        val command = command(11L)
        tracker.record(command)

        assertNull(
            tracker.accept(
                result(command, accepted = true).toBuilder().setOriginatingGestureId(99L).build(),
            ),
        )
        val accepted = tracker.accept(result(command, accepted = true))
        assertEquals(11L, accepted?.commandId)
        assertEquals(true, accepted?.acceptedForExecution)
        assertNull(tracker.accept(result(command, accepted = true)))

        val rejectedCommand = command(12L)
        tracker.record(rejectedCommand)
        assertEquals(false, tracker.accept(result(rejectedCommand, accepted = false))?.acceptedForExecution)
    }

    private fun gesture(id: Long, observedNs: Long): RokidGestureIntent = RokidGestureIntent.newBuilder()
        .setSessionId(binding.sessionId)
        .setLeaseId(binding.leaseId)
        .setGestureId(id)
        .setObservedMonotonicTimestampNs(observedNs)
        .setOperation(RokidGestureOperation.ROKID_GESTURE_OPERATION_ENABLE_NODE)
        .setUserInitiated(true)
        .build()

    private fun command(id: Long): RokidNodeCommand = RokidNodeCommand.newBuilder()
        .setSessionId(binding.sessionId)
        .setLeaseId(binding.leaseId)
        .setCommandId(id)
        .setOriginatingGestureId(4L)
        .setIssuedMonotonicTimestampNs(8_000_000_000L)
        .setValidForMs(MAXIMUM_ROKID_COMMAND_TTL_MILLIS)
        .setOperation(RokidNodeCommandOperation.ROKID_NODE_COMMAND_OPERATION_ACTIVATE_NODE)
        .setUserAuthorized(true)
        .build()

    private fun result(command: RokidNodeCommand, accepted: Boolean): RokidNodeCommandResult {
        val builder = RokidNodeCommandResult.newBuilder()
            .setSessionId(command.sessionId)
            .setLeaseId(command.leaseId)
            .setCommandId(command.commandId)
            .setOriginatingGestureId(command.originatingGestureId)
            .setOperation(command.operation)
            .setAcceptedForExecution(accepted)
        if (!accepted) {
            builder.error = ErrorStatus.newBuilder()
                .setCode(ErrorCode.ERROR_CODE_CANCELLED)
                .setMessage("rejected")
                .build()
        }
        return builder.build()
    }
}
