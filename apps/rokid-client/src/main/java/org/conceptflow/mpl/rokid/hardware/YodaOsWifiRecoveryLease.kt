// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.hardware

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.util.Log
import java.io.Closeable
import org.conceptflow.mpl.rokid.core.YodaOsTemporaryP2pGroupDecision
import org.conceptflow.mpl.rokid.core.YodaOsTemporaryP2pGroupPolicy
import org.conceptflow.mpl.rokid.core.YodaOsWifiControlCompatibility
import org.conceptflow.mpl.rokid.core.YodaOsWifiRecoverySchedule

/**
 * Bounded recovery for the non-display YodaOS-Sprite radio policy.
 *
 * The firmware can explicitly disable Wi-Fi during a wear/CXR transition; a normal Android
 * [WifiManager.WifiLock] cannot reverse that decision. On the verified Rokid glasses build, the
 * system assist service exposes `open_wifi_p2p`, which is the non-OTA path that enables the radio
 * even while CXR is connected. This controller uses that path only as a short recovery pulse. It
 * removes an empty, locally owned temporary group through the public P2P API; the vendor close
 * command is deliberately not used because physical testing proved that it also disables Wi-Fi.
 *
 * No capture callback calls this class, and no sensing gate or payload passes through it.
 */
class YodaOsWifiRecoveryLease(
    context: Context,
    private val handler: Handler = Handler(context.mainLooper),
) : Closeable {
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val wifiP2pManager = appContext.getSystemService(WifiP2pManager::class.java)
    private val supported = YodaOsWifiControlCompatibility.isSupported(
        manufacturer = Build.MANUFACTURER,
        brand = Build.BRAND,
        device = Build.DEVICE,
        product = Build.PRODUCT,
    )
    private val retrySchedule = YodaOsWifiRecoverySchedule()
    private var active = false
    private var receiverRegistered = false
    private var pendingAttempt: Runnable? = null
    private var recoveryPulseOpen = false
    private var releaseRequestInFlight = false
    private var wifiP2pChannel: WifiP2pManager.Channel? = null
    private val pendingReleases = mutableListOf<Runnable>()
    private var generation = 0L

    private val wifiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!active || intent?.action != WifiManager.WIFI_STATE_CHANGED_ACTION) return
            when (intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)) {
                WifiManager.WIFI_STATE_ENABLED -> onRadioEnabled()
                WifiManager.WIFI_STATE_DISABLED -> beginRecovery("radio_disabled")
            }
        }
    }

    /** Starts observing an explicitly armed node. The call is idempotent. */
    fun acquire(): Boolean {
        if (!supported || wifiManager == null) {
            Log.i(TAG, "state=yoda_wifi_recovery supported=false")
            return false
        }
        if (!active) {
            active = true
            registerReceiver()
        }
        ensureAvailable()
        return true
    }

    /** Re-evaluates the radio at the start of a bounded rendezvous epoch. */
    fun ensureAvailable() {
        if (!active || !supported || wifiManager == null) return
        if (wifiManager.isWifiEnabled) {
            onRadioEnabled()
        } else if (pendingAttempt == null) {
            beginRecovery("rendezvous")
        }
    }

    private fun beginRecovery(reason: String) {
        if (!active || pendingAttempt != null) return
        cancelPendingReleases()
        generation = Math.addExact(generation, 1L)
        retrySchedule.restart()
        Log.i(TAG, "state=yoda_wifi_recovery phase=started reason=$reason bounded=true")
        scheduleNextAttempt(generation)
    }

    private fun scheduleNextAttempt(activeGeneration: Long) {
        val delayMillis = retrySchedule.nextDelayMillis()
        if (delayMillis == null) {
            pendingAttempt = null
            Log.w(
                TAG,
                "state=yoda_wifi_recovery phase=exhausted " +
                    "attempts=${retrySchedule.maximumAttempts}",
            )
            return
        }
        val attempt = Runnable {
            pendingAttempt = null
            if (!active || generation != activeGeneration) return@Runnable
            if (wifiManager?.isWifiEnabled == true) {
                onRadioEnabled()
                return@Runnable
            }
            val sent = sendYodaOsCommand(COMMAND_OPEN_WIFI_P2P)
            recoveryPulseOpen = recoveryPulseOpen || sent
            Log.i(
                TAG,
                "state=yoda_wifi_recovery phase=request_open sent=$sent delay_ms=$delayMillis",
            )
            scheduleNextAttempt(activeGeneration)
        }
        pendingAttempt = attempt
        handler.postDelayed(attempt, delayMillis)
    }

    private fun onRadioEnabled() {
        if (!active) return
        cancelAttempts()
        if (!recoveryPulseOpen || pendingReleases.isNotEmpty()) return
        YodaOsWifiRecoverySchedule.DEFAULT_RELEASE_DELAYS_MILLIS.forEachIndexed { index, delayMillis ->
            lateinit var release: Runnable
            release = Runnable {
                pendingReleases.remove(release)
                if (!active || !recoveryPulseOpen) return@Runnable
                val finalAttempt = index == YodaOsWifiRecoverySchedule.DEFAULT_RELEASE_DELAYS_MILLIS.lastIndex
                releaseTemporaryP2pGroup(
                    activeGeneration = generation,
                    finalAttempt = finalAttempt,
                    allowInactive = false,
                    attemptNumber = index + 1,
                )
            }
            pendingReleases += release
            handler.postDelayed(release, delayMillis)
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(wifiStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(wifiStateReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun sendYodaOsCommand(command: String): Boolean = runCatching {
        // This must remain unscoped: the verified firmware receiver is dynamically registered.
        // The message contains no identifier, credential, address, or user content.
        appContext.sendBroadcast(
            Intent(YODAOS_COMMAND_ACTION)
                .putExtra(YODAOS_COMMAND_EXTRA, command),
        )
    }.fold(
        onSuccess = { true },
        onFailure = {
            Log.w(TAG, "state=yoda_wifi_recovery phase=command_failed")
            false
        },
    )

    @SuppressLint("MissingPermission")
    private fun releaseTemporaryP2pGroup(
        activeGeneration: Long,
        finalAttempt: Boolean,
        allowInactive: Boolean,
        attemptNumber: Int,
    ) {
        if ((!active && !allowInactive) || generation != activeGeneration ||
            !recoveryPulseOpen || releaseRequestInFlight
        ) {
            return
        }
        val manager = wifiP2pManager
        val channel = wifiP2pChannel ?: runCatching {
            manager?.initialize(appContext, appContext.mainLooper) {
                wifiP2pChannel = null
                releaseRequestInFlight = false
                Log.w(TAG, "state=yoda_wifi_recovery phase=p2p_channel_disconnected")
            }
        }.getOrNull()?.also { wifiP2pChannel = it }
        if (manager == null || channel == null) {
            Log.w(TAG, "state=yoda_wifi_recovery phase=release_p2p unavailable=true")
            return
        }
        releaseRequestInFlight = true
        runCatching {
            manager.requestGroupInfo(channel) { group ->
                releaseRequestInFlight = false
                if (generation != activeGeneration || (!active && !allowInactive)) {
                    closeP2pChannelIfInactive()
                    return@requestGroupInfo
                }
                when (
                    YodaOsTemporaryP2pGroupPolicy.releaseDecision(
                        groupPresent = group != null,
                        isGroupOwner = group?.isGroupOwner == true,
                        clientCount = group?.clientList?.size ?: 0,
                    )
                ) {
                    YodaOsTemporaryP2pGroupDecision.ALREADY_ABSENT -> {
                        if (finalAttempt) recoveryPulseOpen = false
                        Log.i(
                            TAG,
                            "state=yoda_wifi_recovery phase=release_p2p result=absent " +
                                "attempt=$attemptNumber final=$finalAttempt",
                        )
                        closeP2pChannelIfInactive()
                    }
                    YodaOsTemporaryP2pGroupDecision.RETAIN_NOT_OWNER,
                    YodaOsTemporaryP2pGroupDecision.RETAIN_ACTIVE_CLIENTS,
                    -> {
                        recoveryPulseOpen = false
                        Log.w(
                            TAG,
                            "state=yoda_wifi_recovery phase=release_p2p result=retained_not_owned " +
                                "attempt=$attemptNumber",
                        )
                        closeP2pChannelIfInactive()
                    }
                    YodaOsTemporaryP2pGroupDecision.REMOVE_EMPTY_OWNER -> {
                        releaseRequestInFlight = true
                        manager.removeGroup(
                            channel,
                            object : WifiP2pManager.ActionListener {
                                override fun onSuccess() {
                                    releaseRequestInFlight = false
                                    if (generation == activeGeneration) recoveryPulseOpen = false
                                    Log.i(
                                        TAG,
                                        "state=yoda_wifi_recovery phase=release_p2p " +
                                            "result=removed attempt=$attemptNumber",
                                    )
                                    closeP2pChannelIfInactive()
                                }

                                override fun onFailure(reason: Int) {
                                    releaseRequestInFlight = false
                                    Log.w(
                                        TAG,
                                        "state=yoda_wifi_recovery phase=release_p2p " +
                                            "result=failed attempt=$attemptNumber reason=$reason",
                                    )
                                    closeP2pChannelIfInactive()
                                }
                            },
                        )
                    }
                }
            }
        }.onFailure {
            releaseRequestInFlight = false
            Log.w(TAG, "state=yoda_wifi_recovery phase=release_p2p result=exception")
            closeP2pChannelIfInactive()
        }
    }

    private fun closeP2pChannelIfInactive() {
        if (active || releaseRequestInFlight) return
        val channel = wifiP2pChannel ?: return
        wifiP2pChannel = null
        runCatching { channel.close() }
            .onFailure { Log.w(TAG, "state=yoda_wifi_recovery phase=p2p_channel_close_failed") }
    }

    private fun cancelAttempts() {
        generation = Math.addExact(generation, 1L)
        pendingAttempt?.let(handler::removeCallbacks)
        pendingAttempt = null
    }

    private fun cancelPendingReleases() {
        pendingReleases.forEach(handler::removeCallbacks)
        pendingReleases.clear()
    }

    override fun close() {
        if (!active && !receiverRegistered) return
        active = false
        cancelAttempts()
        cancelPendingReleases()
        if (recoveryPulseOpen) {
            releaseTemporaryP2pGroup(
                activeGeneration = generation,
                finalAttempt = true,
                allowInactive = true,
                attemptNumber = 0,
            )
        }
        closeP2pChannelIfInactive()
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(wifiStateReceiver) }
            receiverRegistered = false
        }
    }

    private companion object {
        const val TAG = "ConceptFlowYodaWifi"
        const val YODAOS_COMMAND_ACTION = "com.rokid.os.master.assist.server.cmd"
        const val YODAOS_COMMAND_EXTRA = "cmd_type"
        const val COMMAND_OPEN_WIFI_P2P = "open_wifi_p2p"
    }
}
