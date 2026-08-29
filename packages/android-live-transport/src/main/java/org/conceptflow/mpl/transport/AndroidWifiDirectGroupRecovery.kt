// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.Closeable
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

enum class WifiDirectGroupRecoveryOutcome {
    RECREATED_2GHZ,
    RECREATED_5GHZ,
    RECREATED_AUTO,
    CREATED_2GHZ,
    CREATED_5GHZ,
    CREATED_AUTO,
    READY_FOR_NEGOTIATION,
    REMOVED_FOR_NEGOTIATION,
    REFUSED_NOT_OWNER,
    REFUSED_ACTIVE_CLIENTS,
    PERMISSION_MISSING,
    WIFI_UNAVAILABLE,
    CHANNEL_DISCONNECTED,
    REMOVE_FAILED,
    CREATE_FAILED,
    VALIDATION_FAILED,
    TIMEOUT,
    CLOSED,
}

enum class WifiDirectRecoveryBand { TWO_GHZ, FIVE_GHZ, AUTO }

enum class WifiDirectRecoveryMode { RECREATE_AUTONOMOUS, REMOVE_FOR_NEGOTIATION }

data class WifiDirectGroupRecoveryResult(
    val outcome: WifiDirectGroupRecoveryOutcome,
    val removedExistingGroup: Boolean,
) {
    val succeeded: Boolean
        get() = outcome == WifiDirectGroupRecoveryOutcome.RECREATED_2GHZ ||
            outcome == WifiDirectGroupRecoveryOutcome.RECREATED_5GHZ ||
            outcome == WifiDirectGroupRecoveryOutcome.RECREATED_AUTO ||
            outcome == WifiDirectGroupRecoveryOutcome.CREATED_2GHZ ||
            outcome == WifiDirectGroupRecoveryOutcome.CREATED_5GHZ ||
            outcome == WifiDirectGroupRecoveryOutcome.CREATED_AUTO ||
            outcome == WifiDirectGroupRecoveryOutcome.READY_FOR_NEGOTIATION ||
            outcome == WifiDirectGroupRecoveryOutcome.REMOVED_FOR_NEGOTIATION
}

internal enum class WifiDirectGroupRecoveryDecision {
    CREATE,
    REMOVE_AND_RECREATE,
    REFUSE_NOT_OWNER,
    REFUSE_ACTIVE_CLIENTS,
}

internal data class WifiDirectGroupCredentials(
    val networkName: String,
    val passphrase: String,
)

/**
 * Explicitly repairs an empty phone-owned Wi-Fi Direct group.
 *
 * This operation is intentionally separate from routine rendezvous. It never removes a group with
 * clients and never removes a group owned by another peer. Callers must place it behind an explicit
 * user/operator action because group replacement can trigger platform-owned authorization UI.
 */
@SuppressLint("MissingPermission")
class AndroidWifiDirectGroupRecovery(
    context: Context,
    private val preferredBand: WifiDirectRecoveryBand = WifiDirectRecoveryBand.TWO_GHZ,
    private val mode: WifiDirectRecoveryMode = WifiDirectRecoveryMode.RECREATE_AUTONOMOUS,
    private val onComplete: (WifiDirectGroupRecoveryResult) -> Unit,
) : Closeable {
    private val appContext = context.applicationContext
    private val manager = requireNotNull(appContext.getSystemService(WifiP2pManager::class.java))
    private val wifiManager = requireNotNull(appContext.getSystemService(WifiManager::class.java))
    private val handler = Handler(Looper.getMainLooper())
    private val completed = AtomicBoolean(false)
    private var removedExistingGroup = false
    private var preferredBandAttempt = true
    private var validationAttempts = 0
    private val credentials = newCredentials()
    private lateinit var channel: WifiP2pManager.Channel

    private val overallTimeout = Runnable {
        finish(WifiDirectGroupRecoveryOutcome.TIMEOUT)
    }

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Wi-Fi Direct recovery must start on the main looper"
        }
        if (!hasRequiredPermission()) {
            finish(WifiDirectGroupRecoveryOutcome.PERMISSION_MISSING)
            return
        }
        if (!wifiManager.isWifiEnabled ||
            !appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
        ) {
            finish(WifiDirectGroupRecoveryOutcome.WIFI_UNAVAILABLE)
            return
        }
        handler.postDelayed(overallTimeout, OVERALL_TIMEOUT_MILLIS)
        channel = manager.initialize(appContext, Looper.getMainLooper()) {
            finish(WifiDirectGroupRecoveryOutcome.CHANNEL_DISCONNECTED)
        }
        handler.postDelayed(::inspectCurrentGroup, CHANNEL_SETTLE_MILLIS)
    }

    override fun close() {
        if (completed.compareAndSet(false, true)) {
            handler.removeCallbacksAndMessages(null)
            onComplete(
                WifiDirectGroupRecoveryResult(
                    WifiDirectGroupRecoveryOutcome.CLOSED,
                    removedExistingGroup,
                ),
            )
        }
    }

    private fun inspectCurrentGroup() {
        if (completed.get()) return
        manager.requestGroupInfo(channel) { group ->
            if (completed.get()) return@requestGroupInfo
            when (recoveryDecision(group != null, group?.isGroupOwner == true, group?.clientList?.size ?: 0)) {
                WifiDirectGroupRecoveryDecision.CREATE -> {
                    if (mode == WifiDirectRecoveryMode.REMOVE_FOR_NEGOTIATION) {
                        finish(WifiDirectGroupRecoveryOutcome.READY_FOR_NEGOTIATION)
                    } else {
                        createPreferredGroup()
                    }
                }
                WifiDirectGroupRecoveryDecision.REMOVE_AND_RECREATE -> removeEmptyOwnerGroup()
                WifiDirectGroupRecoveryDecision.REFUSE_NOT_OWNER ->
                    finish(WifiDirectGroupRecoveryOutcome.REFUSED_NOT_OWNER)
                WifiDirectGroupRecoveryDecision.REFUSE_ACTIVE_CLIENTS ->
                    finish(WifiDirectGroupRecoveryOutcome.REFUSED_ACTIVE_CLIENTS)
            }
        }
    }

    private fun removeEmptyOwnerGroup() {
        manager.removeGroup(
            channel,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    removedExistingGroup = true
                    if (mode == WifiDirectRecoveryMode.REMOVE_FOR_NEGOTIATION) {
                        handler.postDelayed(
                            { finish(WifiDirectGroupRecoveryOutcome.REMOVED_FOR_NEGOTIATION) },
                            GROUP_REMOVAL_SETTLE_MILLIS,
                        )
                    } else {
                        handler.postDelayed(::createPreferredGroup, GROUP_REMOVAL_SETTLE_MILLIS)
                    }
                }

                override fun onFailure(reason: Int) {
                    Log.w(TAG, "state=wifi_direct_recovery remove=failed reason=${failureReasonName(reason)}")
                    finish(WifiDirectGroupRecoveryOutcome.REMOVE_FAILED)
                }
            },
        )
    }

    private fun createPreferredGroup() {
        preferredBandAttempt = true
        runCatching { groupConfiguration(preferredBand) }
            .onSuccess(::createGroup)
            .onFailure {
                Log.e(TAG, "state=wifi_direct_recovery create_preferred=config_failed")
                finish(WifiDirectGroupRecoveryOutcome.CREATE_FAILED)
            }
    }

    private fun createAutomaticBandFallback() {
        preferredBandAttempt = false
        runCatching { groupConfiguration(WifiDirectRecoveryBand.AUTO) }
            .onSuccess(::createGroup)
            .onFailure {
                Log.e(TAG, "state=wifi_direct_recovery create_auto=config_failed")
                finish(WifiDirectGroupRecoveryOutcome.CREATE_FAILED)
            }
    }

    private fun createGroup(configuration: WifiP2pConfig) {
        if (completed.get()) return
        manager.createGroup(
            channel,
            configuration,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    validationAttempts = 0
                    handler.postDelayed(::validateCreatedGroup, GROUP_VALIDATION_DELAY_MILLIS)
                }

                override fun onFailure(reason: Int) {
                    if (preferredBandAttempt && preferredBand != WifiDirectRecoveryBand.AUTO) {
                        Log.w(
                            TAG,
                            "state=wifi_direct_recovery create_preferred=failed " +
                                "reason=${failureReasonName(reason)} fallback=auto",
                        )
                        handler.postDelayed(::createAutomaticBandFallback, CREATE_FALLBACK_DELAY_MILLIS)
                    } else {
                        Log.w(
                            TAG,
                            "state=wifi_direct_recovery create_auto=failed reason=${failureReasonName(reason)}",
                        )
                        finish(WifiDirectGroupRecoveryOutcome.CREATE_FAILED)
                    }
                }
            },
        )
    }

    private fun validateCreatedGroup() {
        if (completed.get()) return
        validationAttempts += 1
        manager.requestGroupInfo(channel) { group ->
            if (completed.get()) return@requestGroupInfo
            if (group?.isGroupOwner == true && group.clientList.isEmpty()) {
                if (preferredBandAttempt && !bandMatches(preferredBand, group.frequency)) {
                    Log.w(TAG, "state=wifi_direct_recovery validation=unexpected_band")
                    finish(WifiDirectGroupRecoveryOutcome.VALIDATION_FAILED)
                    return@requestGroupInfo
                }
                val activeBand = if (preferredBandAttempt) preferredBand else WifiDirectRecoveryBand.AUTO
                val outcome = successOutcome(removedExistingGroup, activeBand)
                finish(outcome)
            } else if (validationAttempts < MAXIMUM_VALIDATION_ATTEMPTS) {
                handler.postDelayed(::validateCreatedGroup, GROUP_VALIDATION_DELAY_MILLIS)
            } else {
                finish(WifiDirectGroupRecoveryOutcome.VALIDATION_FAILED)
            }
        }
    }

    private fun finish(outcome: WifiDirectGroupRecoveryOutcome) {
        if (!completed.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        Log.i(
            TAG,
            "state=wifi_direct_recovery outcome=${outcome.name.lowercase()} " +
                "removed_existing=$removedExistingGroup",
        )
        onComplete(WifiDirectGroupRecoveryResult(outcome, removedExistingGroup))
    }

    private fun hasRequiredPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun groupConfiguration(band: WifiDirectRecoveryBand): WifiP2pConfig = WifiP2pConfig.Builder()
        .setNetworkName(credentials.networkName)
        .setPassphrase(credentials.passphrase)
        .setGroupOperatingBand(
            when (band) {
                WifiDirectRecoveryBand.TWO_GHZ -> WifiP2pConfig.GROUP_OWNER_BAND_2GHZ
                WifiDirectRecoveryBand.FIVE_GHZ -> WifiP2pConfig.GROUP_OWNER_BAND_5GHZ
                WifiDirectRecoveryBand.AUTO -> WifiP2pConfig.GROUP_OWNER_BAND_AUTO
            },
        )
        .enablePersistentMode(true)
        .build()

    internal companion object {
        const val CHANNEL_SETTLE_MILLIS = 750L
        const val GROUP_REMOVAL_SETTLE_MILLIS = 1_000L
        const val CREATE_FALLBACK_DELAY_MILLIS = 750L
        const val GROUP_VALIDATION_DELAY_MILLIS = 500L
        const val MAXIMUM_VALIDATION_ATTEMPTS = 12
        const val OVERALL_TIMEOUT_MILLIS = 20_000L
        private const val TAG = "ConceptFlowP2pRecovery"
        private const val CREDENTIAL_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private const val NETWORK_RANDOM_LENGTH = 12
        private const val PASSPHRASE_LENGTH = 24

        fun recoveryDecision(
            groupPresent: Boolean,
            isGroupOwner: Boolean,
            clientCount: Int,
        ): WifiDirectGroupRecoveryDecision {
            require(clientCount >= 0)
            return when {
                !groupPresent -> WifiDirectGroupRecoveryDecision.CREATE
                !isGroupOwner -> WifiDirectGroupRecoveryDecision.REFUSE_NOT_OWNER
                clientCount > 0 -> WifiDirectGroupRecoveryDecision.REFUSE_ACTIVE_CLIENTS
                else -> WifiDirectGroupRecoveryDecision.REMOVE_AND_RECREATE
            }
        }

        fun is2Ghz(frequencyMhz: Int): Boolean = frequencyMhz in 2_400..2_500

        fun is5Ghz(frequencyMhz: Int): Boolean = frequencyMhz in 5_000 until 6_000

        fun bandMatches(band: WifiDirectRecoveryBand, frequencyMhz: Int): Boolean = when (band) {
            WifiDirectRecoveryBand.TWO_GHZ -> is2Ghz(frequencyMhz)
            WifiDirectRecoveryBand.FIVE_GHZ -> is5Ghz(frequencyMhz)
            WifiDirectRecoveryBand.AUTO -> frequencyMhz > 0
        }

        fun successOutcome(
            removedExistingGroup: Boolean,
            band: WifiDirectRecoveryBand,
        ): WifiDirectGroupRecoveryOutcome = if (removedExistingGroup) {
            when (band) {
                WifiDirectRecoveryBand.TWO_GHZ -> WifiDirectGroupRecoveryOutcome.RECREATED_2GHZ
                WifiDirectRecoveryBand.FIVE_GHZ -> WifiDirectGroupRecoveryOutcome.RECREATED_5GHZ
                WifiDirectRecoveryBand.AUTO -> WifiDirectGroupRecoveryOutcome.RECREATED_AUTO
            }
        } else {
            when (band) {
                WifiDirectRecoveryBand.TWO_GHZ -> WifiDirectGroupRecoveryOutcome.CREATED_2GHZ
                WifiDirectRecoveryBand.FIVE_GHZ -> WifiDirectGroupRecoveryOutcome.CREATED_5GHZ
                WifiDirectRecoveryBand.AUTO -> WifiDirectGroupRecoveryOutcome.CREATED_AUTO
            }
        }

        internal fun credentialsFrom(randomBytes: ByteArray): WifiDirectGroupCredentials {
            require(randomBytes.size >= 2 + NETWORK_RANDOM_LENGTH + PASSPHRASE_LENGTH)
            var offset = 0
            fun take(length: Int): String = buildString(length) {
                repeat(length) {
                    val index = randomBytes[offset++].toInt().and(0xff) % CREDENTIAL_ALPHABET.length
                    append(CREDENTIAL_ALPHABET[index])
                }
            }
            val directPrefix = take(2)
            val opaqueNetworkSuffix = take(NETWORK_RANDOM_LENGTH)
            return WifiDirectGroupCredentials(
                networkName = "DIRECT-$directPrefix-$opaqueNetworkSuffix",
                passphrase = take(PASSPHRASE_LENGTH),
            )
        }

        private fun newCredentials(): WifiDirectGroupCredentials {
            val bytes = ByteArray(2 + NETWORK_RANDOM_LENGTH + PASSPHRASE_LENGTH)
            SecureRandom().nextBytes(bytes)
            return credentialsFrom(bytes)
        }

        private fun failureReasonName(reason: Int): String = when (reason) {
            WifiP2pManager.ERROR -> "ERROR"
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
            WifiP2pManager.BUSY -> "BUSY"
            else -> "UNKNOWN"
        }
    }
}
