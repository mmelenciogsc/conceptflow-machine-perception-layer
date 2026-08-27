// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RokidSystemTouchEventHubTest {
    @Test
    fun publicationIsNonBufferedAndStopsAfterTheExactSinkIsCleared() {
        val event = RokidSystemTouchEvent(
            input = RokidSystemBroadcastInput.TWO_FINGER_LONG_PRESS,
            sourceUptimeMillis = 10L,
            observedMonotonicTimestampNs = 11L,
        )
        var observed: RokidSystemTouchEvent? = null
        val sink = RokidSystemTouchEventHub.Sink { observed = it }

        assertFalse(RokidSystemTouchEventHub.publish(event))
        RokidSystemTouchEventHub.install(sink)
        try {
            assertTrue(RokidSystemTouchEventHub.publish(event))
            assertEquals(event, observed)
        } finally {
            RokidSystemTouchEventHub.clear(sink)
        }
        assertFalse(RokidSystemTouchEventHub.publish(event))
    }
}
