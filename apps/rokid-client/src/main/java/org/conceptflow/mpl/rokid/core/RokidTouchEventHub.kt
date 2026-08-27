// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import java.util.concurrent.atomic.AtomicReference

/** Process-local, non-blocking handoff from AccessibilityService to the active sensor publisher. */
object RokidTouchEventHub {
    fun interface Sink {
        fun onTouch(event: RokidInputEvent, observedMonotonicTimestampNs: Long)
    }

    private val sink = AtomicReference<Sink?>()

    fun install(value: Sink) {
        sink.set(value)
    }

    fun clear(value: Sink) {
        sink.compareAndSet(value, null)
    }

    fun publish(event: RokidInputEvent, observedMonotonicTimestampNs: Long) {
        if (observedMonotonicTimestampNs > 0L) sink.get()?.onTouch(event, observedMonotonicTimestampNs)
    }
}

/** Converts an uptime-domain event timestamp using a same-receipt dual-clock anchor. */
internal fun uptimeMillisToElapsedRealtimeNanos(
    eventUptimeMillis: Long,
    receiptUptimeMillis: Long,
    receiptElapsedRealtimeNanos: Long,
): Long {
    require(eventUptimeMillis >= 0L && receiptUptimeMillis >= 0L && receiptElapsedRealtimeNanos > 0L)
    val ageMillis = (receiptUptimeMillis - eventUptimeMillis).coerceAtLeast(0L)
    val ageNanos = runCatching { Math.multiplyExact(ageMillis, 1_000_000L) }.getOrDefault(Long.MAX_VALUE)
    return (receiptElapsedRealtimeNanos - ageNanos).coerceAtLeast(1L)
}
