// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.hardware

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.PowerManager

/** Bounded CPU and Wi-Fi locks used only while an explicitly armed rendezvous is authenticating. */
class BoundedRendezvousWakeLease(context: Context) : AutoCloseable {
    private val expiryHandler = Handler(context.mainLooper)
    private val expiry = Runnable { release() }
    private val wakeLock = context.getSystemService(PowerManager::class.java).newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        "${context.packageName}:preauth-rendezvous",
    ).apply {
        setReferenceCounted(false)
    }
    @Suppress("DEPRECATION")
    private val wifiLock = context.applicationContext.getSystemService(WifiManager::class.java)?.createWifiLock(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        },
        "${context.packageName}:preauth-rendezvous",
    )?.apply {
        setReferenceCounted(false)
    }

    fun acquire(timeoutMillis: Long): Boolean {
        require(timeoutMillis > 0L)
        return runCatching {
            release()
            wakeLock.acquire(timeoutMillis)
            wifiLock?.acquire()
            check(expiryHandler.postDelayed(expiry, timeoutMillis))
        }.fold(
            onSuccess = { true },
            onFailure = {
                release()
                false
            },
        )
    }

    fun release() {
        expiryHandler.removeCallbacks(expiry)
        wifiLock?.takeIf { it.isHeld }?.let { held -> runCatching { held.release() } }
        if (wakeLock.isHeld) runCatching { wakeLock.release() }
    }

    fun cpuHeld(): Boolean = wakeLock.isHeld

    fun wifiHeld(): Boolean = wifiLock?.isHeld == true

    override fun close() = release()
}
