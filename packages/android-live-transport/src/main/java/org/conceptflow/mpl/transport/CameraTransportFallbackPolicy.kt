// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import java.util.concurrent.atomic.AtomicBoolean

enum class CameraTransportFallbackDispatch {
    DEMOTED_RECONNECT_REQUIRED,
    ALREADY_DEMOTED,
    CONFIGURED_I420,
}

data class CameraTransportFallbackSnapshot(
    val configuredTransport: LiveCameraTransport,
    val activeTransport: LiveCameraTransport,
    val fallbackCount: Long,
)

/**
 * One-way process-lifetime AVC circuit breaker. A service restart may probe AVC again, but a
 * failing live process cannot oscillate between codecs and repeatedly lose frames.
 */
class CameraTransportFallbackPolicy(
    private val configuredTransport: LiveCameraTransport,
) {
    private val demoted = AtomicBoolean(false)

    fun allowsAvcIntra(): Boolean =
        configuredTransport == LiveCameraTransport.AVC_INTRA && !demoted.get()

    fun requestI420Demotion(): CameraTransportFallbackDispatch = when {
        configuredTransport != LiveCameraTransport.AVC_INTRA ->
            CameraTransportFallbackDispatch.CONFIGURED_I420
        demoted.compareAndSet(false, true) ->
            CameraTransportFallbackDispatch.DEMOTED_RECONNECT_REQUIRED
        else -> CameraTransportFallbackDispatch.ALREADY_DEMOTED
    }

    fun snapshot(): CameraTransportFallbackSnapshot {
        val isDemoted = demoted.get()
        return CameraTransportFallbackSnapshot(
            configuredTransport = configuredTransport,
            activeTransport = if (configuredTransport == LiveCameraTransport.AVC_INTRA && !isDemoted) {
                LiveCameraTransport.AVC_INTRA
            } else {
                LiveCameraTransport.I420
            },
            fallbackCount = if (isDemoted) 1L else 0L,
        )
    }
}
