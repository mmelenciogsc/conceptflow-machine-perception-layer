// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.hardware

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import org.conceptflow.mpl.rokid.core.ImuSample
import org.conceptflow.mpl.rokid.core.MonotonicFrameSequence
import org.conceptflow.mpl.rokid.core.PoseSource
import org.conceptflow.mpl.v1.CoordinateFrame
import org.conceptflow.mpl.v1.Pose
import org.conceptflow.mpl.v1.Quaternion
import org.conceptflow.mpl.v1.Vector3
import java.util.concurrent.atomic.AtomicBoolean

class SensorManagerPoseSource(context: Context) : PoseSource, SensorEventListener {
    private val manager = context.applicationContext.getSystemService(SensorManager::class.java)
    private val running = AtomicBoolean(false)
    private val timestamps = MonotonicFrameSequence()
    private var listener: ((ImuSample) -> Unit)? = null
    private var rotation = Quaternion.newBuilder().setW(1.0).build()
    private var angularVelocity = Vector3.getDefaultInstance()
    private var linearAcceleration = Vector3.getDefaultInstance()

    override val isRunning: Boolean get() = running.get()

    override fun start(listener: (ImuSample) -> Unit) {
        check(running.compareAndSet(false, true)) { "Pose source is already running" }
        val rotationSensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: run {
                running.set(false)
                error("No rotation-vector sensor is available")
            }
        this.listener = listener
        manager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isRunning) return
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                val quaternion = FloatArray(4)
                SensorManager.getQuaternionFromVector(quaternion, event.values)
                rotation = Quaternion.newBuilder()
                    .setW(quaternion[0].toDouble())
                    .setX(quaternion[1].toDouble())
                    .setY(quaternion[2].toDouble())
                    .setZ(quaternion[3].toDouble())
                    .build()
            }
            Sensor.TYPE_GYROSCOPE -> angularVelocity = vector(event.values)
            Sensor.TYPE_LINEAR_ACCELERATION -> linearAcceleration = vector(event.values)
        }
        val timestamp = timestamps.normalizeTimestamp(event.timestamp)
        val pose = Pose.newBuilder()
            .setReferenceFrame(CoordinateFrame.COORDINATE_FRAME_HEAD)
            .setRotation(rotation)
            .setTranslationMeters(Vector3.getDefaultInstance())
            .setMonotonicTimestampNs(timestamp)
            .build()
        listener?.invoke(ImuSample(pose, angularVelocity, linearAcceleration))
    }

    private fun vector(values: FloatArray): Vector3 = Vector3.newBuilder()
        .setX(values.getOrElse(0) { 0f }.toDouble())
        .setY(values.getOrElse(1) { 0f }.toDouble())
        .setZ(values.getOrElse(2) { 0f }.toDouble())
        .build()

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun stop() {
        if (!running.getAndSet(false)) return
        manager.unregisterListener(this)
        listener = null
    }
}
