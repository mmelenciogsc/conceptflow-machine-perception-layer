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
import org.conceptflow.mpl.host.LiveMachineVisionPhase
import org.conceptflow.mpl.host.focus.SpatialFocusCommand
import org.conceptflow.mpl.transport.LiveLinkProvisioningStore

/** Shell-only debug control/status surface for OEM builds where ADB lacks DUMP permission. */
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
        listOf(SPEECH_TIMEOUT_PROBE_STATUS_PATH) -> AndroidWhisperTimeoutProbe.status()
        listOf(CODEC_FIDELITY_PATH, CODEC_FIDELITY_START_PATH) ->
            AndroidCodecFidelityProbe.start(requireNotNull(context))
        listOf(CODEC_FIDELITY_PATH, CODEC_FIDELITY_STATUS_PATH) ->
            AndroidCodecFidelityProbe.status()
        listOf(CODEC_FIDELITY_PATH, CODEC_FIDELITY_CLEAR_PATH) ->
            AndroidCodecFidelityProbe.clear(requireNotNull(context))
        listOf(AVC_FAULT_PATH) -> if (AndroidNodeForegroundService.injectNextAvcDecodeFailureNow()) {
            "avc_decode_fault_armed"
        } else {
            "avc_decode_fault_unavailable"
        }
        listOf(MICROPHONE_PATH) -> dispatchMicrophone()
        listOf(FOCUS_STATUS_PATH) -> AndroidNodeRuntimeState.currentFocus()?.let { focus ->
            "mode=${focus.mode.name.lowercase()} items=${focus.itemCount} " +
                "selected=${focus.selectedIndex} dwell=${focus.dwell.name.lowercase()} " +
                "reason=${focus.statusReason} phrase=${focus.talkBackPhrase}"
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

    private fun dispatchMicrophone(): String {
        val admission = microphoneCommandAdmission(AndroidNodeRuntimeState.current()?.phase)
        if (admission != DebugMicrophoneCommandAdmission.QUEUED) {
            return admission.status
        }
        return runCatching {
            // Do not enter the synchronized perception controller from a Binder query. During
            // model transitions that can hold the shell command open and make a healthy sensor
            // link look unresponsive. The already-running foreground service is the nonvisual,
            // lifecycle-owned command queue; its log/status reports final transport admission.
            AndroidNodeForegroundService.requestMicrophone(requireNotNull(context))
            admission.status
        }.getOrElse { "microphone_dispatch_failed" }
    }

    private fun dispatchFocus(command: SpatialFocusCommand): String = runCatching {
        val state = AndroidNodeForegroundService.focusCommandSequenceNow(listOf(command))
            ?: return@runCatching "focus_${command.name.lowercase()}_unavailable"
        "focus_${command.name.lowercase()}_mode_${state.mode.name.lowercase()}_reason_${state.statusReason}"
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
        const val SPEECH_TIMEOUT_PROBE_STATUS_PATH = "speech-timeout-probe-status"
        const val CODEC_FIDELITY_PATH = "codec-fidelity"
        const val CODEC_FIDELITY_START_PATH = "start"
        const val CODEC_FIDELITY_STATUS_PATH = "status"
        const val CODEC_FIDELITY_CLEAR_PATH = "clear"
        const val AVC_FAULT_PATH = "avc-decode-fault"
        const val MICROPHONE_PATH = "microphone"
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

internal enum class DebugMicrophoneCommandAdmission(val status: String) {
    QUEUED("microphone_request_queued"),
    NO_AUTHENTICATED_SESSION("microphone_no_authenticated_session"),
}

internal fun microphoneCommandAdmission(
    phase: LiveMachineVisionPhase?,
): DebugMicrophoneCommandAdmission = if (phase == LiveMachineVisionPhase.STREAMING) {
    DebugMicrophoneCommandAdmission.QUEUED
} else {
    DebugMicrophoneCommandAdmission.NO_AUTHENTICATED_SESSION
}
