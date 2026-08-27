// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RokidTouchTimestampTest {
    @Test
    fun `uptime event is anchored into elapsed realtime without changing event age`() {
        assertEquals(
            14_750_000_000L,
            uptimeMillisToElapsedRealtimeNanos(
                eventUptimeMillis = 9_750L,
                receiptUptimeMillis = 10_000L,
                receiptElapsedRealtimeNanos = 15_000_000_000L,
            ),
        )
    }

    @Test
    fun `future source timestamp clamps to receipt instead of producing future fusion time`() {
        assertEquals(
            15_000_000_000L,
            uptimeMillisToElapsedRealtimeNanos(10_001L, 10_000L, 15_000_000_000L),
        )
    }
}
