// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockResyncScheduleTest {
    @Test
    fun `clock estimate refresh is due every ten monotonic seconds`() {
        val schedule = ClockResyncSchedule()
        schedule.arm(2_000)
        assertFalse(schedule.isDue(10_000_001_999L))
        assertTrue(schedule.isDue(10_000_002_000L))
        schedule.markCompleted(10_000_002_050L)
        assertFalse(schedule.isDue(20_000_002_049L))
        assertTrue(schedule.isDue(20_000_002_050L))
    }
}
