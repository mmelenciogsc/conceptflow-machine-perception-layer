// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.conceptflow.mpl.v1.Pose
import org.conceptflow.mpl.v1.Vector3

data class ImuSample(
    val pose: Pose,
    val angularVelocityRadiansPerSecond: Vector3,
    val linearAccelerationMetersPerSecondSquared: Vector3,
    val sequenceId: Long = 0L,
    val orientationAccuracy: Int = 0,
    val angularVelocityTimestampNanos: Long = 0L,
    val linearAccelerationTimestampNanos: Long = 0L,
) {
    init {
        require(sequenceId >= 0L)
        require(orientationAccuracy in 0..3)
        require(angularVelocityTimestampNanos >= 0L)
        require(linearAccelerationTimestampNanos >= 0L)
    }
}

data class ImuSamplingProfile(
    val samplingPeriodMicros: Int = 10_000,
    val maximumReportLatencyMicros: Int = 0,
) {
    init {
        require(samplingPeriodMicros in 5_000..200_000)
        require(maximumReportLatencyMicros in 0..1_000_000)
    }

    val nominalSamplesPerSecond: Double get() = 1_000_000.0 / samplingPeriodMicros
}

interface PoseSource : AutoCloseable {
    val isRunning: Boolean
    fun start(listener: (ImuSample) -> Unit)
    fun stop()
    override fun close() = stop()
}

class SyntheticPoseSource(private val samples: List<ImuSample>) : PoseSource {
    private var listener: ((ImuSample) -> Unit)? = null
    override var isRunning: Boolean = false
        private set

    override fun start(listener: (ImuSample) -> Unit) {
        check(!isRunning) { "Pose source is already running" }
        isRunning = true
        this.listener = listener
    }

    fun emit(index: Int): ImuSample {
        check(isRunning) { "Pose source is not running" }
        val sample = samples[index]
        listener?.invoke(sample)
        return sample
    }

    override fun stop() {
        isRunning = false
        listener = null
    }
}
