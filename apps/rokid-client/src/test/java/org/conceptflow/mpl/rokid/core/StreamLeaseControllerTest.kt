// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.conceptflow.mpl.v1.SensorStreamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamLeaseControllerTest {
    private val peer = AuthenticatedStreamPeer("paired-poco")

    @Test
    fun sensorsStayUnauthorizedUntilLeaseAndStopAtExpiry() {
        val clock = MutableClock(1_000_000_000L)
        val controller = StreamLeaseController(clock, leaseIdFactory = { "lease-1" })

        assertNull(controller.current())
        val lease = (controller.open(
            peer,
            StreamLeaseSpec(
                sessionId = "session-1",
                requestedStreams = setOf(
                    SensorStreamKind.SENSOR_STREAM_KIND_CAMERA,
                    SensorStreamKind.SENSOR_STREAM_KIND_IMU,
                ),
                requestedDurationMillis = 2_000L,
            ),
        ) as StreamLeaseDecision.Granted).lease

        assertFalse(lease.permits(SensorStreamKind.SENSOR_STREAM_KIND_CAMERA, lease.openedAtNanos - 1L))
        assertTrue(lease.permits(SensorStreamKind.SENSOR_STREAM_KIND_CAMERA, clock.nowNanos()))
        assertTrue(lease.permits(SensorStreamKind.SENSOR_STREAM_KIND_IMU, clock.nowNanos()))
        clock.value = lease.expiresAtNanos
        assertNull(controller.current())
    }

    @Test
    fun microphoneRequiresExplicitRequestAndHasShorterHardExpiry() {
        val clock = MutableClock(2_000_000_000L)
        val controller = StreamLeaseController(
            clock,
            policy = StreamLeasePolicy(
                maximumLeaseDurationMillis = 60_000L,
                maximumMicrophoneDurationMillis = 2_000L,
            ),
            leaseIdFactory = { "lease-mic" },
        )
        val denied = (controller.open(
            peer,
            StreamLeaseSpec(
                "session-1",
                setOf(SensorStreamKind.SENSOR_STREAM_KIND_MICROPHONE),
                5_000L,
                userRequestedMicrophone = false,
            ),
        ) as StreamLeaseDecision.Rejected)
        assertEquals("no streams were authorized", denied.reason)

        val lease = (controller.open(
            peer,
            StreamLeaseSpec(
                "session-1",
                setOf(SensorStreamKind.SENSOR_STREAM_KIND_CAMERA, SensorStreamKind.SENSOR_STREAM_KIND_MICROPHONE),
                5_000L,
                userRequestedMicrophone = true,
            ),
        ) as StreamLeaseDecision.Granted).lease
        assertTrue(lease.permits(SensorStreamKind.SENSOR_STREAM_KIND_MICROPHONE, clock.nowNanos()))
        clock.value += 2_000_000_000L
        val active = controller.current()!!
        assertFalse(active.permits(SensorStreamKind.SENSOR_STREAM_KIND_MICROPHONE, clock.nowNanos()))
        assertTrue(active.permits(SensorStreamKind.SENSOR_STREAM_KIND_CAMERA, clock.nowNanos()))
    }

    @Test
    fun anotherPeerCannotRenewOrCloseLease() {
        val clock = MutableClock(1L)
        val controller = StreamLeaseController(clock, leaseIdFactory = { "lease-1" })
        val lease = (controller.open(
            peer,
            StreamLeaseSpec("session", setOf(SensorStreamKind.SENSOR_STREAM_KIND_IMU), 2_000L),
        ) as StreamLeaseDecision.Granted).lease
        val intruder = AuthenticatedStreamPeer("other-peer")

        assertTrue(controller.renew(intruder, lease.leaseId, 2_000L) is StreamLeaseDecision.Rejected)
        assertFalse(controller.close(intruder, lease.leaseId))
        assertTrue(controller.close(peer, lease.leaseId))
    }

    @Test
    fun renewalNeverExtendsExpiredOrExistingMicrophoneConsent() {
        val clock = MutableClock(1_000_000_000L)
        val controller = StreamLeaseController(
            clock,
            policy = StreamLeasePolicy(60_000L, 2_000L),
            leaseIdFactory = { "lease-mic" },
        )
        val lease = (controller.open(
            peer,
            StreamLeaseSpec(
                "session",
                setOf(SensorStreamKind.SENSOR_STREAM_KIND_IMU, SensorStreamKind.SENSOR_STREAM_KIND_MICROPHONE),
                10_000L,
                userRequestedMicrophone = true,
            ),
        ) as StreamLeaseDecision.Granted).lease
        val originalMicrophoneExpiry = lease.microphoneExpiresAtNanos

        clock.value += 1_000_000_000L
        val renewed = (controller.renew(peer, lease.leaseId, 20_000L) as StreamLeaseDecision.Granted).lease
        assertEquals(originalMicrophoneExpiry, renewed.microphoneExpiresAtNanos)
        clock.value = originalMicrophoneExpiry!!
        assertFalse(renewed.permits(SensorStreamKind.SENSOR_STREAM_KIND_MICROPHONE, clock.nowNanos()))
        assertTrue(renewed.permits(SensorStreamKind.SENSOR_STREAM_KIND_IMU, clock.nowNanos()))
    }

    private class MutableClock(var value: Long) : MonotonicClock {
        override fun nowNanos(): Long = value
    }
}
