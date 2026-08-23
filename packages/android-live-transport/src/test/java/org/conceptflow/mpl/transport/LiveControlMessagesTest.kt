// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import org.conceptflow.mpl.v1.SensorStreamKind
import org.conceptflow.mpl.v1.StreamLeaseOperation
import org.conceptflow.mpl.v1.StreamLeaseRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveControlMessagesTest {
    private val binding = LiveSessionBinding("session", "lease", ByteArray(32) { 4 })

    @Test
    fun `baseline lease grants camera and IMU but never microphone`() {
        val request = LiveControlMessages.leaseRequest(binding).leaseRequest
        val grant = LiveControlMessages.leaseGrant(request).leaseGrant

        assertEquals(
            setOf(SensorStreamKind.SENSOR_STREAM_KIND_CAMERA, SensorStreamKind.SENSOR_STREAM_KIND_IMU),
            grant.grantedStreamsList.toSet(),
        )
        assertFalse(grant.grantedStreamsList.contains(SensorStreamKind.SENSOR_STREAM_KIND_MICROPHONE))
    }

    @Test
    fun `microphone request is rejected instead of silently downgraded`() {
        val request = StreamLeaseRequest.newBuilder()
            .setRequestId("request")
            .setSessionId("session")
            .setLeaseId("lease")
            .setOperation(StreamLeaseOperation.STREAM_LEASE_OPERATION_OPEN)
            .addRequestedStreams(SensorStreamKind.SENSOR_STREAM_KIND_CAMERA)
            .addRequestedStreams(SensorStreamKind.SENSOR_STREAM_KIND_IMU)
            .addRequestedStreams(SensorStreamKind.SENSOR_STREAM_KIND_MICROPHONE)
            .setUserRequestedMicrophone(true)
            .build()

        assertThrows(IllegalArgumentException::class.java) { LiveControlMessages.leaseGrant(request) }
    }

    @Test
    fun `authenticated lease close and acknowledgement require the exact active binding`() {
        val close = LiveControlMessages.leaseClose(binding)
        val acknowledgement = LiveControlMessages.leaseCloseAcknowledged(binding)

        assertTrue(LiveControlMessages.isLeaseClose(close, binding))
        assertTrue(LiveControlMessages.isLeaseCloseAcknowledgement(acknowledgement, binding))
        assertFalse(
            LiveControlMessages.isLeaseClose(
                close,
                LiveSessionBinding("other-session", "lease", ByteArray(32) { 4 }),
            ),
        )
        assertFalse(LiveControlMessages.isLeaseClose(LiveControlMessages.leaseRequest(binding), binding))
        assertFalse(
            LiveControlMessages.isLeaseCloseAcknowledgement(
                acknowledgement.toBuilder().setLeaseGrant(
                    acknowledgement.leaseGrant.toBuilder().setGrantedDurationMs(1),
                ).build(),
                binding,
            ),
        )
    }
}
