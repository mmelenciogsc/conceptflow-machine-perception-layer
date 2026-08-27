// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.realtime

/** Stable, hand-written Binder ABI shared with the standalone Unity Android plug-in. */
internal object PerceptionIpcProtocol {
    const val DESCRIPTOR = "org.conceptflow.mpl.host.realtime.IPerceptionBridge"
    const val PERMISSION = "org.conceptflow.mpl.androidhost.permission.READ_PERCEPTION_BUS"

    // These deliberately match IBinder.FIRST_CALL_TRANSACTION + [0, 3].
    const val TRANSACTION_POLL_WORLD_STATE = 1
    const val TRANSACTION_POLL_FOCUS_STATE = 2
    const val TRANSACTION_POLL_HEAD_POSE = 3
    const val TRANSACTION_DRAIN_TOUCH_EVENTS = 4

    const val STATUS_OK = 0
    const val STATUS_NO_UPDATE = 1
    const val STATUS_INVALID_ARGUMENT = 2
    const val STATUS_OVERSIZE = 3
    const val STATUS_INTERNAL_ERROR = 4
    const val STATUS_MALFORMED_REQUEST = 5

    const val MAXIMUM_TOUCH_EVENTS = 128
    const val MAXIMUM_WORLD_BYTES = 65_536
    const val MAXIMUM_FOCUS_BYTES = 1_024
    const val MAXIMUM_HEAD_POSE_BYTES = 256
    const val MAXIMUM_TOUCH_BYTES = 8_192

    fun maximumPayloadBytes(transactionCode: Int): Int = when (transactionCode) {
        TRANSACTION_POLL_WORLD_STATE -> MAXIMUM_WORLD_BYTES
        TRANSACTION_POLL_FOCUS_STATE -> MAXIMUM_FOCUS_BYTES
        TRANSACTION_POLL_HEAD_POSE -> MAXIMUM_HEAD_POSE_BYTES
        TRANSACTION_DRAIN_TOUCH_EVENTS -> MAXIMUM_TOUCH_BYTES
        else -> 0
    }
}

internal interface PerceptionIpcSource {
    fun pollWorldState(lastRevision: Long): ByteArray?

    fun pollFocusState(lastRevision: Long): ByteArray?

    fun pollHeadPose(lastSequence: Long): ByteArray?

    fun drainTouchEvents(maximumEvents: Int): ByteArray
}

internal data class PerceptionIpcResponse(
    val status: Int,
    val payload: ByteArray? = null,
)

/**
 * Android-free validation and dispatch core. Keeping this separate from Parcel handling makes all
 * legal bounds, failure mapping and reply-size limits deterministic JVM-testable behavior.
 */
internal class PerceptionIpcDispatcher(
    private val source: PerceptionIpcSource,
) {
    fun dispatch(transactionCode: Int, argument: Long): PerceptionIpcResponse {
        if (!isLegalArgument(transactionCode, argument)) {
            return PerceptionIpcResponse(PerceptionIpcProtocol.STATUS_INVALID_ARGUMENT)
        }
        val payload = try {
            when (transactionCode) {
                PerceptionIpcProtocol.TRANSACTION_POLL_WORLD_STATE ->
                    source.pollWorldState(argument)
                PerceptionIpcProtocol.TRANSACTION_POLL_FOCUS_STATE ->
                    source.pollFocusState(argument)
                PerceptionIpcProtocol.TRANSACTION_POLL_HEAD_POSE ->
                    source.pollHeadPose(argument)
                PerceptionIpcProtocol.TRANSACTION_DRAIN_TOUCH_EVENTS ->
                    source.drainTouchEvents(argument.toInt())
                else -> return PerceptionIpcResponse(PerceptionIpcProtocol.STATUS_MALFORMED_REQUEST)
            }
        } catch (_: RuntimeException) {
            return PerceptionIpcResponse(PerceptionIpcProtocol.STATUS_INTERNAL_ERROR)
        }
        if (payload == null) {
            return PerceptionIpcResponse(PerceptionIpcProtocol.STATUS_NO_UPDATE)
        }
        if (payload.size > PerceptionIpcProtocol.maximumPayloadBytes(transactionCode)) {
            return PerceptionIpcResponse(PerceptionIpcProtocol.STATUS_OVERSIZE)
        }
        return PerceptionIpcResponse(PerceptionIpcProtocol.STATUS_OK, payload.copyOf())
    }

    private fun isLegalArgument(transactionCode: Int, argument: Long): Boolean = when (transactionCode) {
        PerceptionIpcProtocol.TRANSACTION_POLL_WORLD_STATE,
        PerceptionIpcProtocol.TRANSACTION_POLL_FOCUS_STATE,
        PerceptionIpcProtocol.TRANSACTION_POLL_HEAD_POSE,
        -> argument >= 0L
        PerceptionIpcProtocol.TRANSACTION_DRAIN_TOUCH_EVENTS ->
            argument in 1L..PerceptionIpcProtocol.MAXIMUM_TOUCH_EVENTS.toLong()
        else -> false
    }
}
