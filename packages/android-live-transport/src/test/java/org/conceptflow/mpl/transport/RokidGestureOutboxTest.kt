// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import org.conceptflow.mpl.v1.RokidGestureOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RokidGestureOutboxTest {
    @Test
    fun `gesture survives failed write until explicitly acknowledged`() {
        val outbox = RokidGestureOutbox(maximumAgeNanos = 1_000L)
        val gesture = PendingRokidGesture(
            gestureId = 7L,
            observedMonotonicNs = 100L,
            operation = RokidGestureOperation.ROKID_GESTURE_OPERATION_ENABLE_NODE,
        )
        outbox.replace(gesture)

        assertEquals(gesture, outbox.peekFresh(500L))
        assertEquals(gesture, outbox.peekFresh(700L))
        assertTrue(outbox.acknowledgeWritten(gesture))
        assertNull(outbox.peekFresh(700L))
    }

    @Test
    fun `latest gesture supersedes older intent and stale intent expires`() {
        val outbox = RokidGestureOutbox(maximumAgeNanos = 1_000L)
        val first = PendingRokidGesture(
            1L,
            100L,
            RokidGestureOperation.ROKID_GESTURE_OPERATION_ENABLE_NODE,
        )
        val replacement = PendingRokidGesture(
            2L,
            200L,
            RokidGestureOperation.ROKID_GESTURE_OPERATION_DISABLE_NODE,
        )
        outbox.replace(first)
        outbox.replace(replacement)

        assertFalse(outbox.acknowledgeWritten(first))
        assertEquals(replacement, outbox.peekFresh(1_200L))
        assertNull(outbox.peekFresh(1_201L))
    }
}
