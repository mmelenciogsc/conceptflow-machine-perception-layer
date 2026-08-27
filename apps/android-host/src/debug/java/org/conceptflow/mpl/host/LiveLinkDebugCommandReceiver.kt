// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.androidhost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.conceptflow.mpl.transport.LiveLinkProvisioningStore

/** Shell-only debug hook for provisioning; live capture remains a user action in [MainActivity]. */
class LiveLinkDebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_INITIALIZE_LIVE_IDENTITY) {
            Log.w(TAG, "state=debug_command_rejected reason=unknown_action")
            return
        }
        val succeeded = runCatching {
            LiveLinkProvisioningStore(context).ensureIdentity(LIVE_IDENTITY_ALIAS)
        }.isSuccess
        Log.i(TAG, "state=live_identity_${if (succeeded) "ready" else "failed"}")
    }

    private companion object {
        const val TAG = "ConceptFlowHost"
        const val ACTION_INITIALIZE_LIVE_IDENTITY =
            "org.conceptflow.mpl.host.debug.INITIALIZE_LIVE_IDENTITY"
        const val LIVE_IDENTITY_ALIAS = "org.conceptflow.mpl.androidhost.live-link.v1"
    }
}
