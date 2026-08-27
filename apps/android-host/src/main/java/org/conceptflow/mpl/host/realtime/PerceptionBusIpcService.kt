// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.realtime

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel

/**
 * Signature-protected, compact-only cross-application boundary for the standalone Unity lab.
 * Camera, microphone, IMU and other sensor buffers are intentionally absent from this surface.
 */
class PerceptionBusIpcService : Service() {
    private val dispatcher = PerceptionIpcDispatcher(
        object : PerceptionIpcSource {
            override fun pollWorldState(lastRevision: Long): ByteArray? =
                AndroidPerceptionBridge.pollWorldState(lastRevision)

            override fun pollFocusState(lastRevision: Long): ByteArray? =
                AndroidPerceptionBridge.pollFocusState(lastRevision)

            override fun pollHeadPose(lastSequence: Long): ByteArray? =
                AndroidPerceptionBridge.pollHeadPose(lastSequence)

            override fun drainTouchEvents(maximumEvents: Int): ByteArray =
                AndroidPerceptionBridge.drainTouchEvents(maximumEvents)
        },
    )

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(PerceptionIpcProtocol.DESCRIPTOR)
                return true
            }
            if (PerceptionIpcProtocol.maximumPayloadBytes(code) == 0) {
                return super.onTransact(code, data, reply, flags)
            }
            // Never drain ordered input for a one-way call that cannot return an acknowledgement.
            if (reply == null || flags and FLAG_ONEWAY != 0) return false

            return try {
                data.enforceInterface(PerceptionIpcProtocol.DESCRIPTOR)
                enforceCallingOrSelfPermission(
                    PerceptionIpcProtocol.PERMISSION,
                    "Signature permission required for perception IPC",
                )
                val argument = when (code) {
                    PerceptionIpcProtocol.TRANSACTION_DRAIN_TOUCH_EVENTS -> data.readInt().toLong()
                    else -> data.readLong()
                }
                val response = if (data.dataAvail() == 0) {
                    dispatcher.dispatch(code, argument)
                } else {
                    PerceptionIpcResponse(PerceptionIpcProtocol.STATUS_MALFORMED_REQUEST)
                }
                writeResponse(reply, response)
                true
            } catch (error: SecurityException) {
                throw error
            } catch (_: RuntimeException) {
                reply.setDataSize(0)
                writeResponse(
                    reply,
                    PerceptionIpcResponse(PerceptionIpcProtocol.STATUS_MALFORMED_REQUEST),
                )
                true
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun writeResponse(reply: Parcel, response: PerceptionIpcResponse) {
        reply.writeNoException()
        reply.writeInt(response.status)
        if (response.status == PerceptionIpcProtocol.STATUS_OK) {
            reply.writeByteArray(requireNotNull(response.payload))
        }
    }
}
