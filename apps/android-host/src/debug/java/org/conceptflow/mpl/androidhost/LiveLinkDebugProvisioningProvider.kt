// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.androidhost

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import org.conceptflow.mpl.host.AndroidNodeRuntimeState
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
        else -> "unknown_path"
    }

    private companion object {
        const val IDENTITY_PATH = "identity"
        const val STATUS_PATH = "status"
        const val STATUS_COLUMN = "status"
        const val MIME_TYPE = "vnd.android.cursor.item/vnd.conceptflow.live-link-identity"
        const val IDENTITY_ALIAS = "org.conceptflow.mpl.androidhost.live-link.v1"
    }
}
