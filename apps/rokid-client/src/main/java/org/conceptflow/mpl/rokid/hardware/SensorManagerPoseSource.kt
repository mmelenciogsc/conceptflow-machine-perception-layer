// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.hardware

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import org.conceptflow.mpl.rokid.core.ImuSample
import org.conceptflow.mpl.rokid.core.ImuSamplingProfile
import org.conceptflow.mpl.rokid.core.MonotonicFrameSequence
import org.conceptflow.mpl.rokid.core.PoseSource
import org.conceptflow.mpl.v1.CoordinateFrame
import org.conceptflow.mpl.v1.Pose
import org.conceptflow.mpl.v1.Quaternion
import org.conceptflow.mpl.v1.Vector3
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class SensorManagerPoseSource(
    context: Context,
    private val samplingProfile: ImuSamplingProfile = ImuSamplingProfile(),
) : PoseSource, SensorEventListener {
    private val manager = context.applicationContext.getSystemService(SensorManager::class.java)
    private val running = AtomicBoolean(false)
    private val sampleSequence = AtomicLong(0L)
    private val timestamps = MonotonicFrameSequence()
    private val sampleLock = Any()
    private var listener: ((ImuSample) -> Unit)? = null
    private var activeRotationSensorType = Sensor.TYPE_GAME_ROTATION_VECTOR
    private var orientationAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var rotation = Quaternion.newBuilder().setW(1.0).build()
    private var angularVelocity = Vector3.getDefaultInstance()
    private var angularVelocityTimestampNanos = 0L
    private var linearAcceleration = Vector3.getDefaultInstance()
    private var linearAccelerationTimestampNanos = 0L
    private val signalReadiness = ImuSignalReadiness()

    override val isRunning: Boolean get() = running.get()

    override fun start(listener: (ImuSample) -> Unit) {
        check(running.compareAndSet(false, true)) { "Pose source is already running" }
        val rotationSensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: run {
                running.set(false)
                error("No rotation-vector sensor is available")
            }
        val gyroscope = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            ?: run {
                running.set(false)
                error("No gyroscope sensor is available")
            }
        val linearAccelerationSensor = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: run {
                running.set(false)
                error("No linear-acceleration sensor is available")
            }
        this.listener = listener
        activeRotationSensorType = rotationSensor.type
        synchronized(sampleLock) {
            signalReadiness.reset()
            angularVelocity = Vector3.getDefaultInstance()
            angularVelocityTimestampNanos = 0L
            linearAcceleration = Vector3.getDefaultInstance()
            linearAccelerationTimestampNanos = 0L
        }
        // Register the component sensors first. Android does not guarantee callback order across
        // sensor types, so orientation callbacks remain gated until both component clocks exist.
        val gyroscopeRegistered = manager.registerListener(
            this,
            gyroscope,
            samplingProfile.samplingPeriodMicros,
            samplingProfile.maximumReportLatencyMicros,
        )
        val linearAccelerationRegistered = manager.registerListener(
            this,
            linearAccelerationSensor,
            samplingProfile.samplingPeriodMicros,
            samplingProfile.maximumReportLatencyMicros,
        )
        val rotationRegistered = manager.registerListener(
            this,
            rotationSensor,
            samplingProfile.samplingPeriodMicros,
            samplingProfile.maximumReportLatencyMicros,
        )
        if (!gyroscopeRegistered || !linearAccelerationRegistered || !rotationRegistered) {
            this.listener = null
            running.set(false)
            manager.unregisterListener(this)
            synchronized(sampleLock) { signalReadiness.reset() }
            error("Required IMU sensor registration failed")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isRunning) return
        val sample = synchronized(sampleLock) {
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    if (signalReadiness.acceptAngularVelocity(event.timestamp)) {
                        angularVelocity = vector(event.values)
                        angularVelocityTimestampNanos = event.timestamp
                    }
                    null
                }
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    if (signalReadiness.acceptLinearAcceleration(event.timestamp)) {
                        linearAcceleration = vector(event.values)
                        linearAccelerationTimestampNanos = event.timestamp
                    }
                    null
                }
                activeRotationSensorType -> {
                    if (signalReadiness.canAssembleAt(event.timestamp)) buildOrientationSample(event) else null
                }
                else -> null
            }
        }
        if (sample != null && isRunning) listener?.invoke(sample)
    }

    private fun buildOrientationSample(event: SensorEvent): ImuSample {
        val quaternion = FloatArray(4)
        SensorManager.getQuaternionFromVector(quaternion, event.values)
        orientationAccuracy = event.accuracy
        rotation = Quaternion.newBuilder()
            .setW(quaternion[0].toDouble())
            .setX(quaternion[1].toDouble())
            .setY(quaternion[2].toDouble())
            .setZ(quaternion[3].toDouble())
            .build()
        val timestamp = timestamps.normalizeTimestamp(event.timestamp)
        val pose = Pose.newBuilder()
            .setReferenceFrame(CoordinateFrame.COORDINATE_FRAME_HEAD)
            .setRotation(rotation)
            .setTranslationMeters(Vector3.getDefaultInstance())
            .setMonotonicTimestampNs(timestamp)
            .build()
        return ImuSample(
            pose = pose,
            angularVelocityRadiansPerSecond = angularVelocity,
            linearAccelerationMetersPerSecondSquared = linearAcceleration,
            sequenceId = sampleSequence.incrementAndGet(),
            orientationAccuracy = orientationAccuracy,
            angularVelocityTimestampNanos = angularVelocityTimestampNanos,
            linearAccelerationTimestampNanos = linearAccelerationTimestampNanos,
        )
    }

    private fun vector(values: FloatArray): Vector3 = Vector3.newBuilder()
        .setX(values.getOrElse(0) { 0f }.toDouble())
        .setY(values.getOrElse(1) { 0f }.toDouble())
        .setZ(values.getOrElse(2) { 0f }.toDouble())
        .build()

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == activeRotationSensorType) {
            synchronized(sampleLock) { orientationAccuracy = accuracy }
        }
    }

    override fun stop() {
        if (!running.getAndSet(false)) return
        manager.unregisterListener(this)
        listener = null
        synchronized(sampleLock) {
            orientationAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
            angularVelocityTimestampNanos = 0L
            linearAccelerationTimestampNanos = 0L
            signalReadiness.reset()
        }
    }
}

/**
 * Pure callback-order gate for the three physical IMU signals required by the wire contract.
 * A component timestamp is never synthesized from an orientation event. Out-of-order component
 * callbacks are ignored, and a pose is emitted only when both retained samples existed by its
 * own hardware timestamp.
 */
internal class ImuSignalReadiness {
    private var angularVelocityTimestampNanos = 0L
    private var linearAccelerationTimestampNanos = 0L

    fun acceptAngularVelocity(timestampNanos: Long): Boolean {
        if (timestampNanos <= angularVelocityTimestampNanos) return false
        angularVelocityTimestampNanos = timestampNanos
        return true
    }

    fun acceptLinearAcceleration(timestampNanos: Long): Boolean {
        if (timestampNanos <= linearAccelerationTimestampNanos) return false
        linearAccelerationTimestampNanos = timestampNanos
        return true
    }

    fun canAssembleAt(orientationTimestampNanos: Long): Boolean =
        orientationTimestampNanos > 0L &&
            angularVelocityTimestampNanos in 1L..orientationTimestampNanos &&
            linearAccelerationTimestampNanos in 1L..orientationTimestampNanos

    fun reset() {
        angularVelocityTimestampNanos = 0L
        linearAccelerationTimestampNanos = 0L
    }
}
