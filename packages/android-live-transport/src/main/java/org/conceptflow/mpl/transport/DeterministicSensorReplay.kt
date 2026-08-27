// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import kotlin.math.ceil

/** A privacy-safe timing envelope around an already constructed ingress delivery. */
data class SensorReplayRecord(
    val offsetNanos: Long,
    val delivery: LiveSensorDelivery,
) {
    init {
        require(offsetNanos >= 0L)
    }
}

/**
 * Deterministic diagnostic replay at the same observer boundary used by the live transport.
 *
 * This class performs no file access, sleeping, logging, or payload copying. A diagnostic tool may
 * construct a bounded sanitized trace explicitly, then drive it from a test clock at original,
 * slower, faster, or stepwise timing. Production transport never instantiates it.
 */
class DeterministicSensorReplay(
    records: List<SensorReplayRecord>,
    maximumRecords: Int = MAXIMUM_RECORDS,
) {
    private val records = records.toList()
    private var nextIndex = 0

    init {
        require(maximumRecords in 1..MAXIMUM_RECORDS)
        require(this.records.size <= maximumRecords)
        require(this.records.zipWithNext().all { (left, right) -> left.offsetNanos <= right.offsetNanos }) {
            "sensor replay offsets must be nondecreasing"
        }
    }

    /** Delivers all records due at [elapsedNanos] under the selected timing scale. */
    fun drainThrough(
        elapsedNanos: Long,
        speed: Double = 1.0,
        observer: PocoLiveLinkObserver,
    ): Int {
        require(elapsedNanos >= 0L)
        validateSpeed(speed)
        var delivered = 0
        while (true) {
            val record = synchronized(this) {
                records.getOrNull(nextIndex)?.takeIf { dueElapsedNanos(it.offsetNanos, speed) <= elapsedNanos }
                    ?.also { nextIndex += 1 }
            } ?: break
            observer.onSensor(record.delivery)
            delivered += 1
        }
        return delivered
    }

    /** Delivers exactly one record regardless of timing, for accessible stepwise debugging. */
    fun step(observer: PocoLiveLinkObserver): Boolean {
        val record = synchronized(this) { records.getOrNull(nextIndex)?.also { nextIndex += 1 } } ?: return false
        observer.onSensor(record.delivery)
        return true
    }

    @Synchronized
    fun reset() {
        nextIndex = 0
    }

    @Synchronized fun remaining(): Int = records.size - nextIndex

    private fun dueElapsedNanos(offsetNanos: Long, speed: Double): Long {
        val scaled = ceil(offsetNanos.toDouble() / speed)
        require(scaled.isFinite() && scaled <= Long.MAX_VALUE.toDouble())
        return scaled.toLong()
    }

    private fun validateSpeed(speed: Double) {
        require(speed.isFinite() && speed in MINIMUM_SPEED..MAXIMUM_SPEED)
    }

    companion object {
        const val MAXIMUM_RECORDS = 100_000
        const val MINIMUM_SPEED = 0.1
        const val MAXIMUM_SPEED = 16.0
    }
}
