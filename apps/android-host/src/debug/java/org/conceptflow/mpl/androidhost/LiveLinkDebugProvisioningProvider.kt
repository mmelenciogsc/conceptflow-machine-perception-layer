// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.androidhost

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import org.conceptflow.mpl.host.AndroidNodeForegroundService
import org.conceptflow.mpl.host.AndroidNodeRuntimeState
import org.conceptflow.mpl.host.AndroidNodeWifiDirectRecoveryState
import org.conceptflow.mpl.host.focus.SpatialFocusCommand
import org.conceptflow.mpl.transport.LiveLinkProvisioningStore

/** Shell-only debug identity/status surface for OEM builds where ADB lacks DUMP permission. */
class LiveLinkDebugProvisioningProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val caller = Binder.getCallingUid()
        val status = when {
            caller != Process.SHELL_UID && caller != Process.ROOT_UID -> "unauthorized"
            else -> handleShellCommand(uri.pathSegments)
        }
        return MatrixCursor(arrayOf(STATUS_COLUMN), 1).apply { addRow(arrayOf(status)) }
    }

    override fun getType(uri: Uri): String = MIME_TYPE

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Debug provisioning is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Debug provisioning is read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Debug provisioning is read-only")

    private fun handleShellCommand(path: List<String>): String = when (path) {
        listOf(IDENTITY_PATH) -> runCatching {
            LiveLinkProvisioningStore(requireNotNull(context)).ensureIdentity(IDENTITY_ALIAS)
        }.fold(onSuccess = { "identity_ready" }, onFailure = { "identity_failed" })
        listOf(STATUS_PATH) -> AndroidNodeRuntimeState.current()?.accessibleSummary() ?: "node_idle"
        listOf(WIFI_DIRECT_RECOVERY_STATUS_PATH) ->
            AndroidNodeWifiDirectRecoveryState.current().accessibleSummary()
        listOf(FOCUS_STATUS_PATH) -> AndroidNodeRuntimeState.currentFocus()?.let { focus ->
            "mode=${focus.mode.name.lowercase()} items=${focus.itemCount} " +
                "selected=${focus.selectedIndex} dwell=${focus.dwell.name.lowercase()} " +
                "phrase=${focus.talkBackPhrase}"
        } ?: "focus_idle"
        listOf(FOCUS_PATH, FOCUS_NEXT_PATH) -> dispatchFocus(SpatialFocusCommand.NEXT)
        listOf(FOCUS_PATH, FOCUS_PREVIOUS_PATH) -> dispatchFocus(SpatialFocusCommand.PREVIOUS)
        listOf(FOCUS_PATH, FOCUS_ACTIVATE_PATH) -> dispatchFocus(SpatialFocusCommand.ACTIVATE)
        listOf(FOCUS_PATH, FOCUS_BACK_PATH) -> dispatchFocus(SpatialFocusCommand.BACK)
        listOf(FOCUS_PATH, FOCUS_BEACON_PATH) -> dispatchFocusSequence(
            "beacon",
            listOf(
                SpatialFocusCommand.NEXT,
                SpatialFocusCommand.ACTIVATE,
                SpatialFocusCommand.NEXT,
                SpatialFocusCommand.ACTIVATE,
            ),
        )
        listOf(FOCUS_PATH, FOCUS_VQA_PATH) -> dispatchFocusSequence(
            "vqa",
            listOf(
                SpatialFocusCommand.NEXT,
                SpatialFocusCommand.ACTIVATE,
                SpatialFocusCommand.ACTIVATE,
            ),
        )
        else -> "unknown_path"
    }

    private fun dispatchFocus(command: SpatialFocusCommand): String = runCatching {
        AndroidNodeForegroundService.focusCommand(requireNotNull(context), command)
        "focus_${command.name.lowercase()}_dispatched"
    }.getOrElse { "focus_command_failed" }

    private fun dispatchFocusSequence(
        name: String,
        commands: List<SpatialFocusCommand>,
    ): String = runCatching {
        val state = AndroidNodeForegroundService.focusCommandSequenceNow(commands)
            ?: return@runCatching "focus_${name}_sequence_unavailable"
        "focus_${name}_sequence_mode_${state.mode.name.lowercase()}_reason_${state.statusReason}"
    }.getOrElse { "focus_${name}_sequence_failed" }

    private companion object {
        const val IDENTITY_PATH = "identity"
        const val STATUS_PATH = "status"
        const val WIFI_DIRECT_RECOVERY_STATUS_PATH = "wifi-direct-recovery-status"
        const val FOCUS_STATUS_PATH = "focus-status"
        const val FOCUS_PATH = "focus"
        const val FOCUS_NEXT_PATH = "next"
        const val FOCUS_PREVIOUS_PATH = "previous"
        const val FOCUS_ACTIVATE_PATH = "activate"
        const val FOCUS_BACK_PATH = "back"
        const val FOCUS_BEACON_PATH = "beacon"
        const val FOCUS_VQA_PATH = "vqa"
        const val STATUS_COLUMN = "status"
        const val MIME_TYPE = "vnd.android.cursor.item/vnd.conceptflow.live-link-identity"
        const val IDENTITY_ALIAS = "org.conceptflow.mpl.androidhost.live-link.v1"
    }
}
