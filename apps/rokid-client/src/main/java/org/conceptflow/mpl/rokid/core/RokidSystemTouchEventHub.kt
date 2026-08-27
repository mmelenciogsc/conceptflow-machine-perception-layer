// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import java.util.concurrent.atomic.AtomicReference

/**
 * A semantic touch gesture emitted by YodaOS after its private PSOC recognizer completes.
 *
 * This is intentionally distinct from [RokidInputEvent]: Android does not expose the underlying
 * KEY_PROG2 event to the validated RV203 AccessibilityService, so manufacturing DOWN/UP events
 * would misrepresent the hardware evidence.
 */
data class RokidSystemTouchEvent(
    val input: RokidSystemBroadcastInput,
    val sourceUptimeMillis: Long,
    val observedMonotonicTimestampNs: Long,
) {
    init {
        require(input == RokidSystemBroadcastInput.TWO_FINGER_LONG_PRESS)
        require(sourceUptimeMillis > 0L)
        require(observedMonotonicTimestampNs > 0L)
    }
}

/** Non-blocking, process-local handoff from the system receiver to the active sensor publisher. */
object RokidSystemTouchEventHub {
    fun interface Sink {
        fun onTouch(event: RokidSystemTouchEvent)
    }

    private val sink = AtomicReference<Sink?>()

    fun install(value: Sink) {
        sink.set(value)
    }

    fun clear(value: Sink) {
        sink.compareAndSet(value, null)
    }

    fun publish(event: RokidSystemTouchEvent): Boolean {
        val current = sink.get() ?: return false
        current.onTouch(event)
        return true
    }
}
