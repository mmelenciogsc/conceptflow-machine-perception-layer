// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.hardware

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.PowerManager

/**
 * Bounded CPU and Wi-Fi locks for an explicitly armed live-link epoch.
 *
 * The service first bounds this lease to rendezvous authentication, then renews it to the
 * independently bounded stream duration after authentication. This prevents an untethered
 * YodaOS power transition from suspending the data plane while camera/IMU producers are active.
 */
enum class WifiRadioPolicy {
    HIGH_PERFORMANCE,
    PLATFORM_DEFAULT,
}

class BoundedRendezvousWakeLease(context: Context) : AutoCloseable {
    private val expiryHandler = Handler(context.mainLooper)
    private val expiry = Runnable { release() }
    private val wakeLock = context.getSystemService(PowerManager::class.java).newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        "${context.packageName}:live-link",
    ).apply {
        setReferenceCounted(false)
    }
    // LOW_LATENCY disables additional Wi-Fi power saving on this API-32 target. HIGH_PERF is
    // retained only for rendezvous and reconnect; an authenticated stream returns to the
    // platform-default radio policy while the independently bounded CPU lease remains held.
    @Suppress("DEPRECATION")
    private val wifiLock = context.applicationContext.getSystemService(WifiManager::class.java)?.createWifiLock(
        WifiManager.WIFI_MODE_FULL_HIGH_PERF,
        "${context.packageName}:live-link",
    )?.apply {
        setReferenceCounted(false)
    }

    @Synchronized
    fun acquire(
        timeoutMillis: Long,
        radioPolicy: WifiRadioPolicy = WifiRadioPolicy.HIGH_PERFORMANCE,
    ): Boolean {
        require(timeoutMillis > 0L)
        return runCatching {
            release()
            wakeLock.acquire(timeoutMillis)
            applyRadioPolicy(radioPolicy)
            check(expiryHandler.postDelayed(expiry, timeoutMillis))
        }.fold(
            onSuccess = { true },
            onFailure = {
                release()
                false
            },
        )
    }

    /**
     * Keeps reconnect/bootstrap responsive while allowing Android's normal Wi-Fi power policy
     * during an authenticated stream. This never releases the independently bounded CPU lease.
     */
    @Synchronized
    fun applyRadioPolicy(policy: WifiRadioPolicy) {
        when (policy) {
            WifiRadioPolicy.HIGH_PERFORMANCE -> if (wifiLock?.isHeld == false) wifiLock.acquire()
            WifiRadioPolicy.PLATFORM_DEFAULT ->
                wifiLock?.takeIf { it.isHeld }?.let { held -> runCatching { held.release() } }
        }
    }

    @Synchronized
    fun release() {
        expiryHandler.removeCallbacks(expiry)
        wifiLock?.takeIf { it.isHeld }?.let { held -> runCatching { held.release() } }
        if (wakeLock.isHeld) runCatching { wakeLock.release() }
    }

    fun cpuHeld(): Boolean = wakeLock.isHeld

    fun wifiHeld(): Boolean = wifiLock?.isHeld == true

    override fun close() = release()
}
