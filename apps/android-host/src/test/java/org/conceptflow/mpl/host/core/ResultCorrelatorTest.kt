// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ResultCorrelatorTest {
    @Test
    fun acceptsExactCorrelationAndRejectsMismatchAndStale() {
        val clock = MutableHostClock(100L)
        val correlator = ResultCorrelator(clock, defaultTtlMillis = 10L)
        val frame = testFrame()
        correlator.register(frame)
        val mismatch = testResult(frame).toBuilder().setFrameId(2L).build()
        assertEquals(
            CorrelationRejection.FRAME_MISMATCH,
            (correlator.correlate(mismatch) as CorrelationResult.Rejected).reason,
        )
        assertEquals(
            CorrelationRejection.UNKNOWN_REQUEST,
            (correlator.correlate(testResult(frame)) as CorrelationResult.Rejected).reason,
        )

        val stale = testFrame(2)
        correlator.register(stale)
        clock.now += 10_000_000L
        assertEquals(
            CorrelationRejection.STALE,
            (correlator.correlate(testResult(stale)) as CorrelationResult.Rejected).reason,
        )
    }

    @Test
    fun tupleMismatchConsumesPendingRequestAndCannotBeReplayed() {
        val correlator = ResultCorrelator(MutableHostClock())
        val frame = testFrame(frameId = 7L, streamId = "left", captureMonotonicTimestampNs = 123L)
        correlator.register(frame)

        val wrongStream = testResult(frame).toBuilder().setStreamId("right").build()
        assertEquals(
            CorrelationRejection.STREAM_MISMATCH,
            (correlator.correlate(wrongStream) as CorrelationResult.Rejected).reason,
        )
        assertEquals(
            CorrelationRejection.UNKNOWN_REQUEST,
            (correlator.correlate(testResult(frame)) as CorrelationResult.Rejected).reason,
        )
        assertEquals(0, correlator.pendingCount())
    }

    @Test
    fun duplicateRegistrationIsRejectedWithoutReplacingOriginalTuple() {
        val correlator = ResultCorrelator(MutableHostClock())
        val original = testFrame(frameId = 1L, requestId = "duplicate", streamId = "left")
        val replacement = testFrame(frameId = 2L, requestId = "duplicate", streamId = "right")
        correlator.register(original)

        try {
            correlator.register(replacement)
            fail("A duplicate pending request id must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }

        assertEquals(1, correlator.pendingCount())
        assertTrue(correlator.correlate(testResult(original)) is CorrelationResult.Accepted)
    }

    @Test
    fun rejectsOutOfOrderAndCancelledResults() {
        val clock = MutableHostClock()
        val correlator = ResultCorrelator(clock)
        val old = testFrame(1)
        val current = testFrame(2)
        correlator.register(old)
        correlator.register(current)
        assertTrue(correlator.correlate(testResult(current)) is CorrelationResult.Accepted)
        assertEquals(
            CorrelationRejection.OUT_OF_ORDER,
            (correlator.correlate(testResult(old)) as CorrelationResult.Rejected).reason,
        )
        val cancelled = testFrame(3)
        correlator.register(cancelled)
        assertTrue(correlator.cancel(cancelled.requestId))
        assertEquals(
            CorrelationRejection.CANCELLED,
            (correlator.correlate(testResult(cancelled)) as CorrelationResult.Rejected).reason,
        )
    }

    @Test
    fun orderingIsStrictWithinEachSessionAndStreamOnly() {
        val correlator = ResultCorrelator(MutableHostClock())
        val high = testFrame(frameId = 10L, requestId = "high", sessionId = "session-a", streamId = "left")
        correlator.register(high)
        assertTrue(correlator.correlate(testResult(high)) is CorrelationResult.Accepted)

        val equal = testFrame(frameId = 10L, requestId = "equal", sessionId = "session-a", streamId = "left")
        correlator.register(equal)
        assertEquals(
            CorrelationRejection.OUT_OF_ORDER,
            (correlator.correlate(testResult(equal)) as CorrelationResult.Rejected).reason,
        )

        val lowerOtherStream = testFrame(
            frameId = 1L,
            requestId = "other-stream",
            sessionId = "session-a",
            streamId = "right",
        )
        correlator.register(lowerOtherStream)
        assertTrue(correlator.correlate(testResult(lowerOtherStream)) is CorrelationResult.Accepted)

        val lowerOtherSession = testFrame(
            frameId = 1L,
            requestId = "other-session",
            sessionId = "session-b",
            streamId = "left",
        )
        correlator.register(lowerOtherSession)
        assertTrue(correlator.correlate(testResult(lowerOtherSession)) is CorrelationResult.Accepted)
    }

    @Test
    fun acceptedStreamHistoryIsBoundedAndEvictsLeastRecentUnpinnedStream() {
        val correlator = ResultCorrelator(
            MutableHostClock(),
            maximumPending = 2,
            maximumAcceptedStreamHistory = 2,
        )
        val streamA = testFrame(frameId = 10L, requestId = "a-10", streamId = "a")
        val streamB = testFrame(frameId = 10L, requestId = "b-10", streamId = "b")
        correlator.register(streamA)
        correlator.register(streamB)
        assertTrue(correlator.correlate(testResult(streamA)) is CorrelationResult.Accepted)
        assertTrue(correlator.correlate(testResult(streamB)) is CorrelationResult.Accepted)

        val pendingOlderA = testFrame(frameId = 9L, requestId = "a-9", streamId = "a")
        val streamC = testFrame(frameId = 10L, requestId = "c-10", streamId = "c")
        correlator.register(pendingOlderA)
        correlator.register(streamC)
        assertTrue(correlator.correlate(testResult(streamC)) is CorrelationResult.Accepted)
        assertEquals(2, correlator.acceptedStreamHistoryCount())

        assertEquals(
            CorrelationRejection.OUT_OF_ORDER,
            (correlator.correlate(testResult(pendingOlderA)) as CorrelationResult.Rejected).reason,
        )
        val evictedStream = testFrame(frameId = 9L, requestId = "b-9", streamId = "b")
        correlator.register(evictedStream)
        assertTrue(correlator.correlate(testResult(evictedStream)) is CorrelationResult.Accepted)
        assertEquals(2, correlator.acceptedStreamHistoryCount())
    }

    @Test
    fun pendingCapacityEvictsOldestRequestDeterministically() {
        val correlator = ResultCorrelator(MutableHostClock(), maximumPending = 2)
        val first = testFrame(frameId = 1L, requestId = "first")
        val second = testFrame(frameId = 2L, requestId = "second")
        val third = testFrame(frameId = 3L, requestId = "third")

        assertEquals(null, correlator.register(first))
        assertEquals(null, correlator.register(second))
        assertEquals("first", correlator.register(third))
        assertEquals(2, correlator.pendingCount())
        assertFalse(correlator.correlate(testResult(first)) is CorrelationResult.Accepted)
    }
}
