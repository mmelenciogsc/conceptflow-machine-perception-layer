// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.conceptflow.mpl.v1.Pose
import org.conceptflow.mpl.v1.Vector3

data class ImuSample(
    val pose: Pose,
    val angularVelocityRadiansPerSecond: Vector3,
    val linearAccelerationMetersPerSecondSquared: Vector3,
)

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
