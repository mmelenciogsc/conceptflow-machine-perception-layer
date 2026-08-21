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

    override val isRunning: Boolean get() = running.get()

    override fun start(listener: (ImuSample) -> Unit) {
        check(running.compareAndSet(false, true)) { "Pose source is already running" }
        val rotationSensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: run {
                running.set(false)
                error("No rotation-vector sensor is available")
            }
        this.listener = listener
        activeRotationSensorType = rotationSensor.type
        val rotationRegistered = manager.registerListener(
            this,
            rotationSensor,
            samplingProfile.samplingPeriodMicros,
            samplingProfile.maximumReportLatencyMicros,
        )
        if (!rotationRegistered) {
            this.listener = null
            running.set(false)
            manager.unregisterListener(this)
            error("Rotation-vector sensor registration failed")
        }
        manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            manager.registerListener(
                this,
                it,
                samplingProfile.samplingPeriodMicros,
                samplingProfile.maximumReportLatencyMicros,
            )
        }
        manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            manager.registerListener(
                this,
                it,
                samplingProfile.samplingPeriodMicros,
                samplingProfile.maximumReportLatencyMicros,
            )
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isRunning) return
        val sample = synchronized(sampleLock) {
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    angularVelocity = vector(event.values)
                    angularVelocityTimestampNanos = event.timestamp.coerceAtLeast(0L)
                    null
                }
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    linearAcceleration = vector(event.values)
                    linearAccelerationTimestampNanos = event.timestamp.coerceAtLeast(0L)
                    null
                }
                activeRotationSensorType -> buildOrientationSample(event)
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
        }
    }
}
