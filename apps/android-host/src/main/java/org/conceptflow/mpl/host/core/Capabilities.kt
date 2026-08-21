// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.core

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Vibrator

data class RuntimeCapabilities(
    val camera: Boolean,
    val rotationVector: Boolean,
    val gyroscope: Boolean,
    val audioOutput: Boolean,
    val haptics: Boolean,
    val validatedNetwork: Boolean,
    val meteredNetwork: Boolean,
)

fun interface CapabilityDetector {
    fun detect(): RuntimeCapabilities
}

class AndroidCapabilityDetector(context: Context) : CapabilityDetector {
    private val appContext = context.applicationContext

    override fun detect(): RuntimeCapabilities {
        val packageManager = appContext.packageManager
        val sensors = appContext.getSystemService(SensorManager::class.java)
        val audio = appContext.getSystemService(AudioManager::class.java)
        val vibrator = appContext.getSystemService(Vibrator::class.java)
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivity.activeNetwork
        val network = activeNetwork?.let(connectivity::getNetworkCapabilities)
        return RuntimeCapabilities(
            camera = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
            rotationVector = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null ||
                sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null,
            gyroscope = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null,
            audioOutput = audio.isMusicActive || packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT),
            haptics = vibrator.hasVibrator(),
            validatedNetwork = network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            meteredNetwork = connectivity.isActiveNetworkMetered,
        )
    }
}
