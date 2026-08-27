// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import java.math.BigInteger

data class FourTimestampClockProbe(
    val probeId: Long,
    val initiatorSendNs: Long,
    val responderReceiveNs: Long,
    val responderSendNs: Long,
    val initiatorReceiveNs: Long,
)

data class ClockOffsetSample(
    val probeId: Long,
    val offsetRemoteMinusHostNs: Long,
    val roundTripNs: Long,
    val uncertaintyNs: Long,
)

data class ClockOffsetEstimate(
    val offsetRemoteMinusHostNs: Long,
    val roundTripNs: Long,
    val uncertaintyNs: Long,
    val validSampleCount: Int,
)

enum class ClockSyncFailure {
    INVALID_TIMESTAMPS,
    ROUND_TRIP_OUT_OF_BOUNDS,
    INSUFFICIENT_SAMPLES,
    OFFSET_OUT_OF_RANGE,
    OFFSET_JUMP,
    ROUND_FULL,
}

/** Quality failures that may retain an already authenticated session's previous good estimate. */
internal fun ClockSyncFailure.isRecoverablePeriodicResyncFailure(): Boolean =
    this == ClockSyncFailure.ROUND_TRIP_OUT_OF_BOUNDS ||
        this == ClockSyncFailure.INSUFFICIENT_SAMPLES ||
        this == ClockSyncFailure.OFFSET_JUMP

class ClockSyncException(val failure: ClockSyncFailure) :
    IllegalArgumentException("clock synchronization rejected: $failure")

/** NTP-style four-timestamp estimator that chooses the valid minimum-RTT sample. */
class MinRttClockSynchronizer(
    private val requiredSamples: Int = DEFAULT_REQUIRED_SAMPLES,
    private val maxSamples: Int = DEFAULT_REQUIRED_SAMPLES,
    private val maxRoundTripNs: Long = DEFAULT_MAX_RTT_NS,
    private val maxOffsetJumpNs: Long = DEFAULT_MAX_OFFSET_JUMP_NS,
) {
    private val candidates = mutableListOf<ClockOffsetSample>()
    private var committed: ClockOffsetEstimate? = null

    init {
        require(requiredSamples > 0) { "requiredSamples must be positive" }
        require(maxSamples >= requiredSamples) { "maxSamples must cover requiredSamples" }
        require(maxRoundTripNs > 0) { "maxRoundTripNs must be positive" }
        require(maxOffsetJumpNs >= 0) { "maxOffsetJumpNs must be non-negative" }
    }

    @Synchronized
    fun beginRound() {
        candidates.clear()
    }

    @Synchronized
    fun add(probe: FourTimestampClockProbe): ClockOffsetSample {
        if (candidates.size >= maxSamples) throw ClockSyncException(ClockSyncFailure.ROUND_FULL)
        val sample = calculateSample(probe)
        if (sample.roundTripNs > maxRoundTripNs) {
            throw ClockSyncException(ClockSyncFailure.ROUND_TRIP_OUT_OF_BOUNDS)
        }
        candidates += sample
        return sample
    }

    @Synchronized
    fun commitBest(): ClockOffsetEstimate {
        if (candidates.size < requiredSamples) {
            throw ClockSyncException(ClockSyncFailure.INSUFFICIENT_SAMPLES)
        }
        val best = candidates.minWith(compareBy<ClockOffsetSample> { it.roundTripNs }.thenBy { it.probeId })
        val previous = committed
        if (previous != null) {
            val jump = big(best.offsetRemoteMinusHostNs)
                .subtract(big(previous.offsetRemoteMinusHostNs))
                .abs()
            if (jump > big(maxOffsetJumpNs)) throw ClockSyncException(ClockSyncFailure.OFFSET_JUMP)
        }
        return ClockOffsetEstimate(
            offsetRemoteMinusHostNs = best.offsetRemoteMinusHostNs,
            roundTripNs = best.roundTripNs,
            uncertaintyNs = best.uncertaintyNs,
            validSampleCount = candidates.size,
        ).also {
            committed = it
            candidates.clear()
        }
    }

    @Synchronized
    fun currentEstimate(): ClockOffsetEstimate? = committed

    @Synchronized
    fun reset() {
        candidates.clear()
        committed = null
    }

    companion object {
        const val DEFAULT_REQUIRED_SAMPLES = 8
        const val DEFAULT_MAX_RTT_NS = 1_000_000_000L
        const val DEFAULT_MAX_OFFSET_JUMP_NS = 50_000_000L

        fun calculateSample(probe: FourTimestampClockProbe): ClockOffsetSample {
            if (probe.probeId <= 0 ||
                probe.initiatorSendNs < 0 ||
                probe.responderReceiveNs < 0 ||
                probe.responderSendNs < probe.responderReceiveNs ||
                probe.initiatorReceiveNs < probe.initiatorSendNs
            ) {
                throw ClockSyncException(ClockSyncFailure.INVALID_TIMESTAMPS)
            }
            val t0 = big(probe.initiatorSendNs)
            val t1 = big(probe.responderReceiveNs)
            val t2 = big(probe.responderSendNs)
            val t3 = big(probe.initiatorReceiveNs)
            val roundTrip = t3.subtract(t0).subtract(t2.subtract(t1))
            if (roundTrip.signum() < 0) throw ClockSyncException(ClockSyncFailure.INVALID_TIMESTAMPS)
            val two = BigInteger.valueOf(2L)
            val offset = t1.subtract(t0).add(t2.subtract(t3)).divide(two)
            val uncertainty = roundTrip.add(BigInteger.ONE).divide(two)
            return try {
                ClockOffsetSample(
                    probeId = probe.probeId,
                    offsetRemoteMinusHostNs = exactLong(offset),
                    roundTripNs = exactLong(roundTrip),
                    uncertaintyNs = exactLong(uncertainty),
                )
            } catch (_: ArithmeticException) {
                throw ClockSyncException(ClockSyncFailure.OFFSET_OUT_OF_RANGE)
            }
        }

        private fun big(value: Long): BigInteger = BigInteger.valueOf(value)

        private fun exactLong(value: BigInteger): Long {
            if (value < LONG_MIN || value > LONG_MAX) throw ArithmeticException("value is outside signed long")
            return value.toLong()
        }

        private val LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE)
        private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
    }
}

enum class RemoteClockStream {
    CAMERA_CAPTURE,
    IMU_BATCH_CREATED,
    IMU_POSE,
    IMU_ANGULAR_VELOCITY,
    IMU_LINEAR_ACCELERATION,
    MICROPHONE_CAPTURE,
    TOUCH_OBSERVED,
    CAMERA_SEND,
    REALTIME_CONTROL_SEND,
    MICROPHONE_INTENT_CREATED,
    ROKID_GESTURE_OBSERVED,
    /** Compatibility key for non-lane transports. */
    REMOTE_SEND,
}

data class ClockNormalizationEvidence(
    val estimateRevision: Long,
    val offsetRemoteMinusHostNs: Long,
    val roundTripNs: Long,
    val uncertaintyNs: Long,
    val offsetChangeFromPreviousNs: Long?,
    val installedHostMonotonicNs: Long,
)

data class NormalizedMonotonicTimestamp(
    val rawRemoteNs: Long,
    val hostMonotonicNs: Long,
    val uncertaintyNs: Long,
    val unadjustedHostMonotonicNs: Long,
    val monotonicAdjustmentNs: Long,
    val clockEvidence: ClockNormalizationEvidence,
)

/** Applies an estimate without destroying source time and explicitly marks monotonic correction. */
class RemoteMonotonicClockNormalizer {
    private var estimate: ClockOffsetEstimate? = null
    private var evidence: ClockNormalizationEvidence? = null
    private var revision = 0L
    private val lastByStream = mutableMapOf<RemoteClockStream, Long>()

    @Synchronized
    fun install(estimate: ClockOffsetEstimate, installedHostMonotonicNs: Long = 0L) {
        require(estimate.roundTripNs >= 0 && estimate.uncertaintyNs >= 0) { "invalid clock estimate" }
        require(installedHostMonotonicNs >= 0) { "clock install timestamp must be non-negative" }
        val previousOffset = this.estimate?.offsetRemoteMinusHostNs
        val offsetChange = previousOffset?.let {
            try {
                BigInteger.valueOf(estimate.offsetRemoteMinusHostNs)
                    .subtract(BigInteger.valueOf(it))
                    .let(::exactLong)
            } catch (_: ArithmeticException) {
                throw ClockSyncException(ClockSyncFailure.OFFSET_OUT_OF_RANGE)
            }
        }
        revision = Math.addExact(revision, 1L)
        this.estimate = estimate
        evidence = ClockNormalizationEvidence(
            revision,
            estimate.offsetRemoteMinusHostNs,
            estimate.roundTripNs,
            estimate.uncertaintyNs,
            offsetChange,
            installedHostMonotonicNs,
        )
    }

    @Synchronized
    fun normalize(stream: RemoteClockStream, remoteNs: Long): NormalizedMonotonicTimestamp {
        if (remoteNs < 0) throw ClockSyncException(ClockSyncFailure.INVALID_TIMESTAMPS)
        val active = estimate ?: throw IllegalStateException("clock synchronization is not established")
        val computed = try {
            BigInteger.valueOf(remoteNs)
                .subtract(BigInteger.valueOf(active.offsetRemoteMinusHostNs))
                .let(::exactLong)
        } catch (_: ArithmeticException) {
            throw ClockSyncException(ClockSyncFailure.OFFSET_OUT_OF_RANGE)
        }
        if (computed < 0) throw ClockSyncException(ClockSyncFailure.OFFSET_OUT_OF_RANGE)
        val previous = lastByStream[stream]
        val clamped = if (previous != null && computed <= previous) {
            if (previous == Long.MAX_VALUE) throw ClockSyncException(ClockSyncFailure.OFFSET_OUT_OF_RANGE)
            previous + 1L
        } else {
            computed
        }
        lastByStream[stream] = clamped
        return NormalizedMonotonicTimestamp(
            remoteNs,
            clamped,
            active.uncertaintyNs,
            computed,
            clamped - computed,
            requireNotNull(evidence),
        )
    }

    @Synchronized
    fun reset() {
        estimate = null
        evidence = null
        revision = 0L
        lastByStream.clear()
    }

    private fun exactLong(value: BigInteger): Long {
        if (value < LONG_MIN || value > LONG_MAX) throw ArithmeticException("value is outside signed long")
        return value.toLong()
    }

    companion object {
        private val LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE)
        private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
    }
}

/** Monotonic, bounded cadence for refreshing an established cross-device clock estimate. */
class ClockResyncSchedule(private val intervalNs: Long = DEFAULT_INTERVAL_NS) {
    private var nextDueNs: Long? = null

    init {
        require(intervalNs in MINIMUM_INTERVAL_NS..MAXIMUM_INTERVAL_NS) {
            "clock resynchronization interval is outside its bound"
        }
    }

    @Synchronized
    fun arm(nowNs: Long) {
        require(nowNs >= 0)
        nextDueNs = Math.addExact(nowNs, intervalNs)
    }

    @Synchronized
    fun isDue(nowNs: Long): Boolean {
        require(nowNs >= 0)
        return nextDueNs?.let { nowNs >= it } ?: false
    }

    @Synchronized
    fun markCompleted(nowNs: Long) = arm(nowNs)

    companion object {
        const val DEFAULT_INTERVAL_NS = 10_000_000_000L
        const val MINIMUM_INTERVAL_NS = 5_000_000_000L
        const val MAXIMUM_INTERVAL_NS = 30_000_000_000L
    }
}
