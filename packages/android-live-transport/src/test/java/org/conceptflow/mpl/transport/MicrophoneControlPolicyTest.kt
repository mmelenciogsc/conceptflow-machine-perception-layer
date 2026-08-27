// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import org.conceptflow.mpl.v1.MicrophoneControlOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneControlPolicyTest {
    private val binding = LiveSessionBinding("session", "lease", ByteArray(32) { 5 })

    @Test
    fun `guard accepts exact fresh monotonic starts and stops`() {
        val guard = MicrophoneIntentGuard(binding)
        val start = intent(1L, 1_000_000_000L, MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_START)
        val stop = intent(2L, 1_100_000_000L, MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_STOP)

        assertNull(guard.validateStructure(start, 1_010_000_000L))
        assertNull(guard.acceptFresh(start, 2_000_000_000L, 2_010_000_000L, 1_000_000L))
        assertNull(guard.validateStructure(stop, 1_110_000_000L))
        assertNull(guard.acceptFresh(stop, 2_100_000_000L, 2_110_000_000L, 1_000_000L))
    }

    @Test
    fun `guard rejects wrong binding malformed replay and stale intents`() {
        val guard = MicrophoneIntentGuard(binding)
        val valid = intent(3L, 2_000_000_000L, MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_START)
        val wrongBinding = valid.toBuilder().setLeaseId("other").build()
        val missingUserAction = valid.toBuilder().setUserRequested(false).build()
        val invalidStop = intent(
            4L,
            2_100_000_000L,
            MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_STOP,
        ).toBuilder().setRequestedDurationMs(1).build()

        assertEquals(MicrophoneIntentRejection.WRONG_BINDING, guard.validateStructure(wrongBinding, 2_010_000_000L))
        assertEquals(MicrophoneIntentRejection.MALFORMED, guard.validateStructure(missingUserAction, 2_010_000_000L))
        assertEquals(MicrophoneIntentRejection.MALFORMED, guard.validateStructure(invalidStop, 2_110_000_000L))
        assertNull(guard.acceptFresh(valid, 3_000_000_000L, 3_010_000_000L, 0L))
        assertEquals(MicrophoneIntentRejection.REPLAY, guard.acceptFresh(valid, 3_000_000_000L, 3_020_000_000L, 0L))
        val stale = intent(4L, 2_100_000_000L, MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_START)
        assertEquals(
            MicrophoneIntentRejection.STALE,
            guard.acceptFresh(stale, 3_100_000_000L, 4_200_000_001L, 100_000_000L),
        )
    }

    @Test
    fun `latest stop blocks delayed grants until its ordered result`() {
        val tracker = GlassesMicrophoneIntentTracker()
        tracker.record(1L, MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_START)
        assertTrue(tracker.permitsLease(1L))
        assertTrue(tracker.permitsLease(0L))
        tracker.record(2L, MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_START)
        assertTrue(tracker.permitsLease(1L))

        tracker.record(3L, MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_STOP)
        assertFalse(tracker.permitsLease(1L))
        assertFalse(tracker.permitsLease(0L))
        assertNull(
            tracker.acceptResult(
                result(1L, MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_START, accepted = true),
            ),
        )

        val stopResult = tracker.acceptResult(
            result(3L, MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_STOP, accepted = true),
        )
        assertEquals(3L, stopResult?.intentId)
        assertTrue(tracker.permitsLease(0L))
    }

    @Test
    fun `host stop revokes authorization and absorbs a correlated late grant`() {
        val gate = HostMicrophoneLeaseGate(MonotonicLeaseDeadline.fromDurationMillis(1_000L, 10_000))
        gate.reserve(durationMillis = 10_000, originatingIntentId = 9L)
        gate.markRequestWritten(originatingIntentId = 9L)
        gate.setAuthorizedUntil(5_000L)

        gate.revoke()

        assertFalse(gate.isAuthorized(2_000L))
        assertFalse(gate.hasPendingRequest())
        assertTrue(gate.hasCancelledResponse(9L))
        assertEquals(MicrophoneResponseDisposition.CANCELLED, gate.acceptResponse(9L))
        assertFalse(gate.hasCancelledResponse(9L))
        assertEquals(MicrophoneResponseDisposition.UNRELATED, gate.acceptResponse(9L))
    }

    @Test
    fun `host cancellation before a write cannot authorize a delayed response`() {
        val gate = HostMicrophoneLeaseGate(MonotonicLeaseDeadline.fromDurationMillis(1_000L, 10_000))
        gate.reserve(durationMillis = 10_000, originatingIntentId = 0L)
        gate.cancelUnwrittenRequest(originatingIntentId = 0L)

        assertFalse(gate.hasPendingRequest())
        assertFalse(gate.hasCancelledResponse(0L))
        assertEquals(MicrophoneResponseDisposition.UNRELATED, gate.acceptResponse(0L))
    }

    private fun intent(
        id: Long,
        createdNs: Long,
        operation: MicrophoneControlOperation,
    ) = LiveControlMessages.microphoneControlIntent(binding, id, createdNs, operation).microphoneControlIntent

    private fun result(
        id: Long,
        operation: MicrophoneControlOperation,
        accepted: Boolean,
    ) = LiveControlMessages.microphoneControlResult(
        binding,
        intent(id, 1L, operation),
        rejection = if (accepted) null else MicrophoneIntentRejection.UNAVAILABLE,
    ).microphoneControlResult
}
