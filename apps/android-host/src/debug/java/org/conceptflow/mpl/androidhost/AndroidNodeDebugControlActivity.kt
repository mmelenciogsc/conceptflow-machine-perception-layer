// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.androidhost

import android.app.Activity
import android.os.Bundle
import org.conceptflow.mpl.host.AndroidNodeForegroundService
import org.conceptflow.mpl.host.vision.EnvironmentSelectionMode
import org.conceptflow.mpl.transport.WifiDirectRecoveryBand
import org.conceptflow.mpl.transport.WifiDirectRecoveryMode

/**
 * Debug-build-only ADB control entrypoint used by repeatable physical tests.
 *
 * An Activity is intentional: Android permits the foreground-service transition from this
 * explicit foreground launch, while rejecting the same transition from a background provider.
 */
class AndroidNodeDebugControlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            ACTION_START_AUTOMATIC -> start(EnvironmentSelectionMode.AUTOMATIC)
            ACTION_START_INDOOR -> start(EnvironmentSelectionMode.FORCE_INDOOR)
            ACTION_START_OUTDOOR -> start(EnvironmentSelectionMode.FORCE_OUTDOOR)
            ACTION_MICROPHONE -> AndroidNodeForegroundService.requestMicrophone(this)
            ACTION_AMBIENT_PROFILE -> AndroidNodeForegroundService.requestAmbientProfile(this)
            ACTION_SPEECH_TIMEOUT_PROBE -> AndroidWhisperTimeoutProbe.start(this)
            ACTION_RECOVER_WIFI_DIRECT -> AndroidNodeForegroundService.recoverWifiDirect(this)
            ACTION_RECOVER_WIFI_DIRECT_5GHZ -> AndroidNodeForegroundService.recoverWifiDirect(
                this,
                WifiDirectRecoveryBand.FIVE_GHZ,
            )
            ACTION_PREPARE_WIFI_DIRECT_NEGOTIATION -> AndroidNodeForegroundService.recoverWifiDirect(
                this,
                recoveryMode = WifiDirectRecoveryMode.REMOVE_FOR_NEGOTIATION,
            )
            ACTION_STOP -> AndroidNodeForegroundService.stop(this)
        }
        finishAndRemoveTask()
    }

    private fun start(mode: EnvironmentSelectionMode) = AndroidNodeForegroundService.start(this, mode)

    companion object {
        const val ACTION_START_AUTOMATIC = "org.conceptflow.mpl.host.debug.START_AUTOMATIC"
        const val ACTION_START_INDOOR = "org.conceptflow.mpl.host.debug.START_INDOOR"
        const val ACTION_START_OUTDOOR = "org.conceptflow.mpl.host.debug.START_OUTDOOR"
        const val ACTION_MICROPHONE = "org.conceptflow.mpl.host.debug.REQUEST_MICROPHONE"
        const val ACTION_AMBIENT_PROFILE = "org.conceptflow.mpl.host.debug.REQUEST_AMBIENT_PROFILE"
        const val ACTION_SPEECH_TIMEOUT_PROBE =
            "org.conceptflow.mpl.host.debug.SPEECH_TIMEOUT_PROBE"
        const val ACTION_RECOVER_WIFI_DIRECT = "org.conceptflow.mpl.host.debug.RECOVER_WIFI_DIRECT"
        const val ACTION_RECOVER_WIFI_DIRECT_5GHZ =
            "org.conceptflow.mpl.host.debug.RECOVER_WIFI_DIRECT_5GHZ"
        const val ACTION_PREPARE_WIFI_DIRECT_NEGOTIATION =
            "org.conceptflow.mpl.host.debug.PREPARE_WIFI_DIRECT_NEGOTIATION"
        const val ACTION_STOP = "org.conceptflow.mpl.host.debug.STOP"
    }
}
