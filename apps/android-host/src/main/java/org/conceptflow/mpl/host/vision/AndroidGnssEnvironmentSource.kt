// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.conceptflow.mpl.host.core.HostClock

enum class GnssAcquisitionState {
    STARTED,
    ALREADY_ACTIVE,
    THROTTLED,
    PERMISSION_REQUIRED,
    LOCATION_DISABLED,
    PROVIDER_UNAVAILABLE,
    FAILED,
    STOPPED,
}

/**
 * Foreground-only, bounded GNSS-quality sampler. Latitude, longitude, altitude,
 * bearing, and speed are never copied from Location or retained.
 */
class AndroidGnssEnvironmentSource(
    context: Context,
    private val clock: HostClock,
    private val onSample: (GnssQualitySample) -> Unit,
    private val burstDurationMillis: Long = 20_000L,
    private val minimumBurstIntervalNanos: Long = 60_000_000_000L,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var active = false
    private var lastBurstStartNanos = Long.MIN_VALUE
    private var lastFixTimestampNanos: Long? = null
    private var lastHorizontalAccuracyMeters: Double? = null

    init {
        require(burstDurationMillis in 1_000L..60_000L)
        require(minimumBurstIntervalNanos >= burstDurationMillis * 1_000_000L)
    }

    private val stopBurst = Runnable { stop() }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // Deliberately retain only monotonic freshness and horizontal
            // accuracy. Never read coordinate, altitude, speed, or bearing.
            lastFixTimestampNanos = location.elapsedRealtimeNanos
            lastHorizontalAccuracyMeters = location.accuracy
                .toDouble()
                .takeIf { location.hasAccuracy() && it.isFinite() && it >= 0.0 }
        }

        override fun onProviderDisabled(provider: String) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val now = clock.nowNanos()
            var usedInFix = 0
            var visible = 0
            var carrierToNoiseTotal = 0.0
            for (index in 0 until status.satelliteCount) {
                val carrierToNoise = status.getCn0DbHz(index).toDouble()
                if (!carrierToNoise.isFinite() || carrierToNoise < 0.0) continue
                visible += 1
                carrierToNoiseTotal += carrierToNoise
                if (status.usedInFix(index)) usedInFix += 1
            }
            val fixAge = lastFixTimestampNanos?.let { (now - it).coerceAtLeast(0L) }
            onSample(
                GnssQualitySample(
                    timestampNanos = now,
                    visibleSatelliteCount = visible,
                    usedInFixCount = usedInFix,
                    meanCarrierToNoiseDbHz = if (visible == 0) null else carrierToNoiseTotal / visible,
                    horizontalAccuracyMeters = lastHorizontalAccuracyMeters,
                    locationFixAgeNanos = fixAge,
                ),
            )
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun startBurst(): GnssAcquisitionState {
        if (active) return GnssAcquisitionState.ALREADY_ACTIVE
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return GnssAcquisitionState.PERMISSION_REQUIRED
        }
        val manager = locationManager ?: return GnssAcquisitionState.PROVIDER_UNAVAILABLE
        if (!manager.isLocationEnabled) return GnssAcquisitionState.LOCATION_DISABLED
        if (!manager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
            return GnssAcquisitionState.PROVIDER_UNAVAILABLE
        }
        val now = clock.nowNanos()
        if (lastBurstStartNanos != Long.MIN_VALUE && now - lastBurstStartNanos < minimumBurstIntervalNanos) {
            return GnssAcquisitionState.THROTTLED
        }
        return try {
            if (!manager.registerGnssStatusCallback(gnssCallback, mainHandler)) {
                return GnssAcquisitionState.FAILED
            }
            @Suppress("DEPRECATION")
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                LOCATION_INTERVAL_MILLIS,
                LOCATION_MINIMUM_DISTANCE_METERS,
                locationListener,
                Looper.getMainLooper(),
            )
            active = true
            lastBurstStartNanos = now
            mainHandler.removeCallbacks(stopBurst)
            mainHandler.postDelayed(stopBurst, burstDurationMillis)
            GnssAcquisitionState.STARTED
        } catch (_: SecurityException) {
            safeUnregister(manager)
            GnssAcquisitionState.PERMISSION_REQUIRED
        } catch (_: IllegalArgumentException) {
            safeUnregister(manager)
            GnssAcquisitionState.PROVIDER_UNAVAILABLE
        } catch (_: RuntimeException) {
            safeUnregister(manager)
            GnssAcquisitionState.FAILED
        }
    }

    @Synchronized
    fun stop(): GnssAcquisitionState {
        mainHandler.removeCallbacks(stopBurst)
        if (!active) return GnssAcquisitionState.STOPPED
        locationManager?.let(::safeUnregister)
        active = false
        lastFixTimestampNanos = null
        lastHorizontalAccuracyMeters = null
        return GnssAcquisitionState.STOPPED
    }

    override fun close() {
        stop()
    }

    private fun safeUnregister(manager: LocationManager) {
        runCatching { manager.unregisterGnssStatusCallback(gnssCallback) }
        runCatching { manager.removeUpdates(locationListener) }
    }

    private companion object {
        const val LOCATION_INTERVAL_MILLIS = 10_000L
        const val LOCATION_MINIMUM_DISTANCE_METERS = 10.0f
    }
}
