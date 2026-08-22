// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt
import org.conceptflow.mpl.v1.Quaternion
import org.conceptflow.mpl.v1.Vector3

data class ImuTransmissionConfig(
    val orientationDeltaRadians: Double = Math.toRadians(0.5),
    val angularVelocityDeltaRadiansPerSecond: Double = 0.02,
    val linearAccelerationDeltaMetersPerSecondSquared: Double = 0.05,
    val maxBatchDelayNanos: Long = 20_000_000L,
    val maxSilenceNanos: Long = 1_000_000_000L,
    val maxBatchSamples: Int = 8,
) {
    init {
        require(orientationDeltaRadians.isFinite() && orientationDeltaRadians >= 0.0)
        require(angularVelocityDeltaRadiansPerSecond.isFinite() && angularVelocityDeltaRadiansPerSecond >= 0.0)
        require(
            linearAccelerationDeltaMetersPerSecondSquared.isFinite() &&
                linearAccelerationDeltaMetersPerSecondSquared >= 0.0,
        )
        require(maxBatchDelayNanos in 1L..20_000_000L)
        require(maxSilenceNanos in maxBatchDelayNanos..5_000_000_000L)
        require(maxBatchSamples in 1..64)
    }
}

data class ImuTransmissionBatch(
    val batchId: Long,
    val createdMonotonicTimestampNanos: Long,
    val samples: List<ImuSample>,
) {
    init {
        require(batchId > 0L)
        require(createdMonotonicTimestampNanos > 0L)
        require(samples.isNotEmpty())
    }
}

data class ImuGateStatistics(
    val offered: Long,
    val accepted: Long,
    val duplicatesSuppressed: Long,
    val invalidRejected: Long,
    val outOfOrderRejected: Long,
    val batchesEmitted: Long,
)

class ImuTransmissionGate(private val config: ImuTransmissionConfig = ImuTransmissionConfig()) {
    private val pending = ArrayList<ImuSample>(config.maxBatchSamples)
    private var pendingStartedAtNanos = 0L
    private var lastObservedSequence = 0L
    private var lastObservedTimestamp = 0L
    private var lastSelected: ImuSample? = null
    private var nextBatchId = 0L
    private var offered = 0L
    private var accepted = 0L
    private var duplicatesSuppressed = 0L
    private var invalidRejected = 0L
    private var outOfOrderRejected = 0L
    private var batchesEmitted = 0L

    @Synchronized
    fun offer(sample: ImuSample): ImuTransmissionBatch? {
        offered += 1L
        val timestamp = sample.pose.monotonicTimestampNs
        if (!isValid(sample)) {
            invalidRejected += 1L
            return null
        }
        if (sample.sequenceId <= lastObservedSequence || timestamp <= lastObservedTimestamp) {
            outOfOrderRejected += 1L
            return null
        }
        lastObservedSequence = sample.sequenceId
        lastObservedTimestamp = timestamp

        if (isMeaningful(sample)) {
            if (pending.isEmpty()) pendingStartedAtNanos = timestamp
            pending += sample
            lastSelected = sample
            accepted += 1L
        } else {
            duplicatesSuppressed += 1L
        }
        return when {
            pending.size >= config.maxBatchSamples -> emit(timestamp)
            pending.isNotEmpty() && timestamp - pendingStartedAtNanos >= config.maxBatchDelayNanos -> emit(timestamp)
            else -> null
        }
    }

    @Synchronized
    fun poll(nowNanos: Long): ImuTransmissionBatch? {
        require(nowNanos >= 0L)
        return if (pending.isNotEmpty() && nowNanos >= pendingStartedAtNanos &&
            nowNanos - pendingStartedAtNanos >= config.maxBatchDelayNanos
        ) {
            emit(nowNanos)
        } else {
            null
        }
    }

    @Synchronized
    fun flush(nowNanos: Long = lastObservedTimestamp): ImuTransmissionBatch? {
        require(nowNanos >= 0L)
        return if (pending.isEmpty()) null else emit(maxOf(nowNanos, pendingStartedAtNanos))
    }

    @Synchronized
    fun reset() {
        pending.clear()
        pendingStartedAtNanos = 0L
        lastObservedSequence = 0L
        lastObservedTimestamp = 0L
        lastSelected = null
        nextBatchId = 0L
        offered = 0L
        accepted = 0L
        duplicatesSuppressed = 0L
        invalidRejected = 0L
        outOfOrderRejected = 0L
        batchesEmitted = 0L
    }

    @Synchronized
    fun statistics(): ImuGateStatistics = ImuGateStatistics(
        offered = offered,
        accepted = accepted,
        duplicatesSuppressed = duplicatesSuppressed,
        invalidRejected = invalidRejected,
        outOfOrderRejected = outOfOrderRejected,
        batchesEmitted = batchesEmitted,
    )

    private fun isMeaningful(sample: ImuSample): Boolean {
        val previous = lastSelected ?: return true
        val elapsed = sample.pose.monotonicTimestampNs - previous.pose.monotonicTimestampNs
        return elapsed >= config.maxSilenceNanos ||
            sample.orientationAccuracy != previous.orientationAccuracy ||
            quaternionAngularDistance(sample.pose.rotation, previous.pose.rotation) >= config.orientationDeltaRadians ||
            vectorDistance(
                sample.angularVelocityRadiansPerSecond,
                previous.angularVelocityRadiansPerSecond,
            ) >= config.angularVelocityDeltaRadiansPerSecond ||
            vectorDistance(
                sample.linearAccelerationMetersPerSecondSquared,
                previous.linearAccelerationMetersPerSecondSquared,
            ) >= config.linearAccelerationDeltaMetersPerSecondSquared
    }

    private fun emit(nowNanos: Long): ImuTransmissionBatch {
        val snapshot = pending.toList()
        pending.clear()
        pendingStartedAtNanos = 0L
        batchesEmitted += 1L
        return ImuTransmissionBatch(++nextBatchId, nowNanos, snapshot)
    }

    private fun isValid(sample: ImuSample): Boolean {
        if (sample.sequenceId <= 0L || sample.pose.monotonicTimestampNs <= 0L) return false
        if (!sample.pose.rotation.isFiniteUnitCandidate()) return false
        if (!sample.angularVelocityRadiansPerSecond.isFinite() ||
            !sample.linearAccelerationMetersPerSecondSquared.isFinite()
        ) {
            return false
        }
        val poseTimestamp = sample.pose.monotonicTimestampNs
        return (sample.angularVelocityTimestampNanos == 0L ||
            sample.angularVelocityTimestampNanos <= poseTimestamp) &&
            (sample.linearAccelerationTimestampNanos == 0L ||
                sample.linearAccelerationTimestampNanos <= poseTimestamp)
    }
}

private fun Quaternion.isFiniteUnitCandidate(): Boolean {
    if (!x.isFinite() || !y.isFinite() || !z.isFinite() || !w.isFinite()) return false
    return x * x + y * y + z * z + w * w > 1.0e-12
}

private fun Vector3.isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

private fun quaternionAngularDistance(left: Quaternion, right: Quaternion): Double {
    val leftNorm = sqrt(left.x * left.x + left.y * left.y + left.z * left.z + left.w * left.w)
    val rightNorm = sqrt(right.x * right.x + right.y * right.y + right.z * right.z + right.w * right.w)
    val normalizedDot = abs(
        (left.x * right.x + left.y * right.y + left.z * right.z + left.w * right.w) /
            (leftNorm * rightNorm),
    ).coerceIn(0.0, 1.0)
    return 2.0 * acos(normalizedDot)
}

private fun vectorDistance(left: Vector3, right: Vector3): Double {
    val x = left.x - right.x
    val y = left.y - right.y
    val z = left.z - right.z
    return sqrt(x * x + y * y + z * z)
}
