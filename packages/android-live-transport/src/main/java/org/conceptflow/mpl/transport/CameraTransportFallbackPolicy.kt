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
 * One-way process-lifetime camera-codec circuit breaker. A service restart may probe the selected
 * codec again, but a failing live process cannot oscillate between codecs and repeatedly lose frames.
 */
class CameraTransportFallbackPolicy(
    private val configuredTransport: LiveCameraTransport,
) {
    private val demoted = AtomicBoolean(false)

    fun allowsAvcIntra(): Boolean =
        configuredTransport == LiveCameraTransport.AVC_INTRA && !demoted.get()

    fun allowsI420Lz4(): Boolean =
        configuredTransport == LiveCameraTransport.I420_LZ4 && !demoted.get()

    fun allowsI420Zstd(): Boolean =
        configuredTransport == LiveCameraTransport.I420_ZSTD && !demoted.get()

    fun requestI420Demotion(): CameraTransportFallbackDispatch = when {
        configuredTransport == LiveCameraTransport.I420 ->
            CameraTransportFallbackDispatch.CONFIGURED_I420
        demoted.compareAndSet(false, true) ->
            CameraTransportFallbackDispatch.DEMOTED_RECONNECT_REQUIRED
        else -> CameraTransportFallbackDispatch.ALREADY_DEMOTED
    }

    fun snapshot(): CameraTransportFallbackSnapshot {
        val isDemoted = demoted.get()
        return CameraTransportFallbackSnapshot(
            configuredTransport = configuredTransport,
            activeTransport = if (configuredTransport != LiveCameraTransport.I420 && !isDemoted) {
                configuredTransport
            } else {
                LiveCameraTransport.I420
            },
            fallbackCount = if (isDemoted) 1L else 0L,
        )
    }
}
