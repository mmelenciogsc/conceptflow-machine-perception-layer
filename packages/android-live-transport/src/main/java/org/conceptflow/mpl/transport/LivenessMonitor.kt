// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

enum class LivenessStatus {
    DISCONNECTED,
    HEALTHY,
    KEEPALIVE_DUE,
    TIMED_OUT,
}

/** Monotonic keepalive state with one outstanding nonce and a bounded timeout. */
class LivenessMonitor(
    private val keepaliveIntervalNs: Long = DEFAULT_KEEPALIVE_INTERVAL_NS,
    private val missedIntervalsBeforeTimeout: Int = DEFAULT_MISSED_INTERVALS,
) {
    private var connected = false
    private var timedOut = false
    private var lastInboundNs = 0L
    private var lastKeepaliveSentNs = 0L
    private var nextNonce = 1L
    private var outstandingNonce: Long? = null

    init {
        require(keepaliveIntervalNs > 0) { "keepaliveIntervalNs must be positive" }
        require(missedIntervalsBeforeTimeout > 0) { "missedIntervalsBeforeTimeout must be positive" }
        Math.multiplyExact(keepaliveIntervalNs, missedIntervalsBeforeTimeout.toLong())
    }

    @Synchronized
    fun connect(nowNs: Long) {
        require(nowNs >= 0) { "nowNs must be non-negative" }
        connected = true
        timedOut = false
        lastInboundNs = nowNs
        lastKeepaliveSentNs = nowNs
        nextNonce = 1L
        outstandingNonce = null
    }

    @Synchronized
    fun poll(nowNs: Long): LivenessStatus {
        if (!connected) return LivenessStatus.DISCONNECTED
        requireMonotonic(nowNs, lastInboundNs)
        val timeoutNs = keepaliveIntervalNs * missedIntervalsBeforeTimeout.toLong()
        if (nowNs - lastInboundNs >= timeoutNs) {
            timedOut = true
            return LivenessStatus.TIMED_OUT
        }
        if (!timedOut && outstandingNonce == null && nowNs - lastKeepaliveSentNs >= keepaliveIntervalNs) {
            return LivenessStatus.KEEPALIVE_DUE
        }
        return LivenessStatus.HEALTHY
    }

    @Synchronized
    fun markKeepaliveSent(nowNs: Long): Long {
        check(connected && !timedOut) { "link is not active" }
        check(outstandingNonce == null) { "a keepalive is already outstanding" }
        requireMonotonic(nowNs, lastKeepaliveSentNs)
        requireMonotonic(nowNs, lastInboundNs)
        if (nextNonce == Long.MAX_VALUE) throw IllegalStateException("keepalive nonce exhausted")
        val nonce = nextNonce++
        outstandingNonce = nonce
        lastKeepaliveSentNs = nowNs
        return nonce
    }

    @Synchronized
    fun onInbound(nowNs: Long) {
        check(connected && !timedOut) { "link is not active" }
        requireMonotonic(nowNs, lastInboundNs)
        lastInboundNs = nowNs
    }

    @Synchronized
    fun onKeepaliveResponse(nonce: Long, nowNs: Long) {
        if (outstandingNonce != nonce) throw SecurityException("unexpected keepalive response")
        onInbound(nowNs)
        outstandingNonce = null
    }

    @Synchronized
    fun reset() {
        connected = false
        timedOut = false
        lastInboundNs = 0L
        lastKeepaliveSentNs = 0L
        nextNonce = 1L
        outstandingNonce = null
    }

    private fun requireMonotonic(nowNs: Long, priorNs: Long) {
        require(nowNs >= priorNs) { "monotonic time moved backwards" }
    }

    companion object {
        const val DEFAULT_KEEPALIVE_INTERVAL_NS = 1_000_000_000L
        // Tolerate brief Android scheduling stalls and Wi-Fi roaming without treating them as a
        // dead peer. Sensor TTLs independently reject stale perception while this link recovers.
        const val DEFAULT_MISSED_INTERVALS = 15
    }
}
