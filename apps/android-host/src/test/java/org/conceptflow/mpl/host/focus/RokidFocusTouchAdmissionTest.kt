// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.focus

import org.conceptflow.mpl.host.realtime.TimedTouchEvent
import org.conceptflow.mpl.v1.RokidTouchAction
import org.conceptflow.mpl.v1.RokidTouchEvent
import org.conceptflow.mpl.v1.RokidTouchKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RokidFocusTouchAdmissionTest {
    @Test
    fun `one through four exact holds map to bounded commands`() {
        var now = 100L
        val admission = admission { now }

        assertNull(admission.commandFor(hold(1L, 100L)))
        assertNull(admission.commandAt(109L))
        assertEquals(SpatialFocusCommand.NEXT, admission.commandAt(110L))

        now = 200L
        assertNull(admission.commandFor(hold(2L, 200L)))
        now = 205L
        assertNull(admission.commandFor(hold(3L, 205L)))
        assertEquals(SpatialFocusCommand.PREVIOUS, admission.commandAt(215L))

        now = 300L
        assertNull(admission.commandFor(hold(4L, 300L)))
        now = 305L
        assertNull(admission.commandFor(hold(5L, 305L)))
        now = 310L
        assertNull(admission.commandFor(hold(6L, 310L)))
        assertEquals(SpatialFocusCommand.ACTIVATE, admission.commandAt(320L))

        now = 400L
        assertNull(admission.commandFor(hold(7L, 400L)))
        now = 405L
        assertNull(admission.commandFor(hold(8L, 405L)))
        now = 410L
        assertNull(admission.commandFor(hold(9L, 410L)))
        now = 415L
        assertEquals(SpatialFocusCommand.BACK, admission.commandFor(hold(10L, 415L)))
        assertNull(admission.pendingDeadlineNanos())
        now = 416L
        assertNull(admission.commandFor(hold(11L, 416L)))
        assertNull(admission.pendingDeadlineNanos())
        now = 425L
        assertNull(admission.commandFor(hold(12L, 425L)))
        assertEquals(SpatialFocusCommand.NEXT, admission.commandAt(435L))
    }

    @Test
    fun `preamble edges do not split the firmware semantic hold burst`() {
        var now = 100L
        val admission = admission { now }
        assertNull(admission.commandFor(hold(1L, 100L)))
        now = 102L
        assertNull(admission.commandFor(preamble(2L, 102L, RokidTouchAction.ROKID_TOUCH_ACTION_DOWN)))
        now = 103L
        assertNull(admission.commandFor(preamble(3L, 103L, RokidTouchAction.ROKID_TOUCH_ACTION_UP)))
        now = 105L
        assertNull(admission.commandFor(hold(4L, 105L)))
        assertEquals(SpatialFocusCommand.PREVIOUS, admission.commandAt(115L))
    }

    @Test
    fun `ordinary one finger event cancels rather than becoming a focus command`() {
        var now = 100L
        val admission = admission { now }
        assertNull(admission.commandFor(hold(1L, 100L)))
        now = 104L
        assertNull(admission.commandFor(singleTap(2L, 104L)))
        assertNull(admission.pendingDeadlineNanos())
        assertNull(admission.commandAt(200L))
    }

    @Test
    fun `stale uncertain malformed and duplicate events fail closed`() {
        var now = 200L
        val admission = TwoFingerHoldBurstFocusAdmission(
            clockNanos = { now },
            completionGapNanos = 10L,
            maximumBurstSpanNanos = 40L,
            maximumEventAgeNanos = 20L,
            maximumClockUncertaintyNanos = 5L,
        )

        assertNull(admission.commandFor(hold(1L, 100L)))
        assertNull(admission.commandFor(hold(2L, 200L, uncertaintyNanos = 6L)))
        assertNull(admission.commandFor(hold(3L, 210L)))
        assertNull(admission.commandFor(hold(3L, 201L)))
        assertNull(admission.commandFor(hold(4L, 200L, scanCode = 148)))
        assertNull(admission.pendingDeadlineNanos())
    }

    @Test
    fun `stale preamble cancels rather than preserving a pending burst`() {
        var now = 100L
        val admission = admission { now }
        assertNull(admission.commandFor(hold(1L, 100L)))

        now = 200L
        assertNull(admission.commandFor(preamble(2L, 100L, RokidTouchAction.ROKID_TOUCH_ACTION_DOWN)))
        assertNull(admission.pendingDeadlineNanos())
        assertNull(admission.commandAt(300L))
    }

    @Test
    fun `expired burst restarts and reset invalidates pending command`() {
        var now = 100L
        val admission = admission { now }
        assertNull(admission.commandFor(hold(1L, 100L)))
        now = 120L
        assertNull(admission.commandFor(hold(2L, 120L)))
        assertEquals(SpatialFocusCommand.NEXT, admission.commandAt(130L))

        now = 200L
        assertNull(admission.commandFor(hold(3L, 200L)))
        admission.reset()
        assertNull(admission.commandAt(300L))
    }

    @Test
    fun `regressing and overflowing timestamps fail closed`() {
        var now = 100L
        val admission = admission { now }
        assertNull(admission.commandFor(hold(1L, 100L)))

        now = 99L
        assertNull(admission.commandFor(hold(2L, 99L)))
        assertNull(admission.pendingDeadlineNanos())

        now = Long.MAX_VALUE
        assertNull(admission.commandFor(hold(3L, Long.MAX_VALUE)))
        assertEquals(Long.MAX_VALUE, admission.pendingDeadlineNanos())
        assertEquals(SpatialFocusCommand.NEXT, admission.commandAt(Long.MAX_VALUE))
    }

    private fun admission(clock: () -> Long) = TwoFingerHoldBurstFocusAdmission(
        clockNanos = clock,
        completionGapNanos = 10L,
        maximumBurstSpanNanos = 40L,
        maximumEventAgeNanos = 20L,
        maximumClockUncertaintyNanos = 5L,
    )

    private fun hold(
        id: Long,
        timestampNanos: Long,
        uncertaintyNanos: Long = 1L,
        scanCode: Int = 149,
    ) = timed(
        id,
        timestampNanos,
        uncertaintyNanos,
        RokidTouchKey.ROKID_TOUCH_KEY_TWO_FINGER_LONG_PRESS,
        RokidTouchAction.ROKID_TOUCH_ACTION_TRIGGERED,
        scanCode,
        longPress = true,
    )

    private fun preamble(id: Long, timestampNanos: Long, action: RokidTouchAction) = timed(
        id,
        timestampNanos,
        1L,
        RokidTouchKey.ROKID_TOUCH_KEY_PREAMBLE,
        action,
        204,
    )

    private fun singleTap(id: Long, timestampNanos: Long) = timed(
        id,
        timestampNanos,
        1L,
        RokidTouchKey.ROKID_TOUCH_KEY_SINGLE_TAP,
        RokidTouchAction.ROKID_TOUCH_ACTION_DOWN,
        148,
    )

    private fun timed(
        id: Long,
        timestampNanos: Long,
        uncertaintyNanos: Long,
        key: RokidTouchKey,
        action: RokidTouchAction,
        scanCode: Int,
        longPress: Boolean = false,
    ) = TimedTouchEvent(
        event = RokidTouchEvent.newBuilder()
            .setEventId(id)
            .setObservedMonotonicTimestampNs(timestampNanos)
            .setSourceUptimeMs(timestampNanos.coerceAtLeast(1L))
            .setKey(key)
            .setAction(action)
            .setRepeatCount(0)
            .setCanceled(false)
            .setLongPress(longPress)
            .setScanCode(scanCode)
            .build(),
        hostObservedTimestampNs = timestampNanos,
        clockUncertaintyNs = uncertaintyNanos,
    )
}
