// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.focus

import org.conceptflow.mpl.host.realtime.TimedTouchEvent
import org.conceptflow.mpl.v1.RokidTouchAction
import org.conceptflow.mpl.v1.RokidTouchKey

/**
 * An explicitly enabled command vocabulary using only the one semantic gesture that has been
 * observed without an OEM action when Hi Rokid Shortcuts is disabled on the validated RV203
 * configuration. Provisioning must leave this admission disabled unless that state is confirmed.
 *
 * A completed burst is resolved after [completionGapNanos]: one hold selects Next, two select
 * Previous, three Activate, and four Back. Ordinary one-finger keys are never mapped. The fourth
 * hold completes immediately because no longer legal burst exists. A non-preamble touch event,
 * stale timing, excessive clock uncertainty, or malformed semantic event cancels the burst.
 */
class TwoFingerHoldBurstFocusAdmission(
    private val clockNanos: () -> Long,
    private val completionGapNanos: Long = 2_500_000_000L,
    private val maximumBurstSpanNanos: Long = 10_000_000_000L,
    private val maximumEventAgeNanos: Long = 1_500_000_000L,
    private val maximumClockUncertaintyNanos: Long = 250_000_000L,
) : SpatialFocusTouchAdmission {
    private var count = 0
    private var firstTimestampNanos = 0L
    private var deadlineNanos = 0L
    private var lastEventId = 0L
    private var suppressedUntilNanos = 0L

    init {
        require(completionGapNanos > 0L)
        require(maximumBurstSpanNanos >= completionGapNanos)
        require(maximumEventAgeNanos > 0L)
        require(maximumClockUncertaintyNanos >= 0L)
    }

    @Synchronized
    override fun commandFor(event: TimedTouchEvent): SpatialFocusCommand? {
        val wire = event.event
        if (wire.eventId <= lastEventId) {
            resetBurst()
            return null
        }
        lastEventId = wire.eventId

        if (!hasAdmissibleTiming(event)) {
            resetBurst()
            return null
        }
        if (isPreamble(event)) return null
        if (!hasEligibleHoldSemantics(event)) {
            resetBurst()
            return null
        }

        val timestamp = event.hostObservedTimestampNs
        if (timestamp < suppressedUntilNanos) return null
        if (count > 0 && timestamp < firstTimestampNanos) {
            resetBurst()
            return null
        }
        if (count == 0 || timestamp > deadlineNanos ||
            timestamp > saturatedAdd(firstTimestampNanos, maximumBurstSpanNanos)
        ) {
            count = 1
            firstTimestampNanos = timestamp
        } else {
            count += 1
        }
        deadlineNanos = saturatedAdd(timestamp, completionGapNanos)

        if (count < MAXIMUM_HOLDS) return null
        suppressedUntilNanos = deadlineNanos
        resetBurst()
        return SpatialFocusCommand.BACK
    }

    @Synchronized
    override fun commandAt(nowNanos: Long): SpatialFocusCommand? {
        require(nowNanos >= 0L)
        if (count == 0 || nowNanos < deadlineNanos) return null
        val command = when (count) {
            1 -> SpatialFocusCommand.NEXT
            2 -> SpatialFocusCommand.PREVIOUS
            3 -> SpatialFocusCommand.ACTIVATE
            else -> null
        }
        resetBurst()
        return command
    }

    @Synchronized
    override fun pendingDeadlineNanos(): Long? = deadlineNanos.takeIf { count > 0 }

    @Synchronized
    override fun reset() {
        lastEventId = 0L
        suppressedUntilNanos = 0L
        resetBurst()
    }

    private fun hasAdmissibleTiming(event: TimedTouchEvent): Boolean {
        val now = clockNanos()
        if (now < 0L || event.hostObservedTimestampNs <= 0L ||
            event.clockUncertaintyNs !in 0L..maximumClockUncertaintyNanos
        ) return false
        if (event.hostObservedTimestampNs > saturatedAdd(now, event.clockUncertaintyNs)) return false
        val admissibleAge = saturatedAdd(maximumEventAgeNanos, event.clockUncertaintyNs)
        if (now > saturatedAdd(event.hostObservedTimestampNs, admissibleAge)) return false
        return event.event.observedMonotonicTimestampNs > 0L && event.event.sourceUptimeMs > 0L
    }

    private fun hasEligibleHoldSemantics(event: TimedTouchEvent): Boolean {
        val wire = event.event
        return wire.key == RokidTouchKey.ROKID_TOUCH_KEY_TWO_FINGER_LONG_PRESS &&
            wire.action == RokidTouchAction.ROKID_TOUCH_ACTION_TRIGGERED &&
            wire.repeatCount == 0 &&
            !wire.canceled &&
            wire.longPress &&
            wire.scanCode == TWO_FINGER_HOLD_SCAN_CODE
    }

    private fun isPreamble(event: TimedTouchEvent): Boolean {
        val wire = event.event
        return wire.key == RokidTouchKey.ROKID_TOUCH_KEY_PREAMBLE &&
            (wire.action == RokidTouchAction.ROKID_TOUCH_ACTION_DOWN ||
                wire.action == RokidTouchAction.ROKID_TOUCH_ACTION_UP) &&
            wire.scanCode == PREAMBLE_SCAN_CODE
    }

    private fun resetBurst() {
        count = 0
        firstTimestampNanos = 0L
        deadlineNanos = 0L
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private companion object {
        const val MAXIMUM_HOLDS = 4
        const val TWO_FINGER_HOLD_SCAN_CODE = 149
        const val PREAMBLE_SCAN_CODE = 204
    }
}
