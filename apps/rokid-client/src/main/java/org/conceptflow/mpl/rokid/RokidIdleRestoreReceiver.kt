// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Restores only the explicitly enabled sensor-free foreground idle service. */
class RokidIdleRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val serviceAction = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> RokidRuntimeService.ACTION_RESTORE_AFTER_BOOT
            Intent.ACTION_MY_PACKAGE_REPLACED -> RokidRuntimeService.ACTION_RESTORE_AFTER_PACKAGE_REPLACED
            else -> return
        }
        if (!IdleControlModeStore(context).isEnabled()) {
            Log.i(TAG, "state=idle_restore_skipped reason=not_enabled")
            return
        }
        runCatching {
            val proof = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, RokidIdleRestoreReceiver::class.java)
                    .setAction(RokidRuntimeService.ACTION_RESTORE_PROOF),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            context.startForegroundService(
                Intent(context, RokidRuntimeService::class.java)
                    .setAction(serviceAction)
                    .putExtra(RokidRuntimeService.EXTRA_INTERNAL_RESTORE_PROOF, proof),
            )
        }.onFailure {
            Log.w(TAG, "state=idle_restore_failed reason=start_rejected")
        }
    }

    companion object {
        private const val TAG = "ConceptFlowRokid"
    }
}
