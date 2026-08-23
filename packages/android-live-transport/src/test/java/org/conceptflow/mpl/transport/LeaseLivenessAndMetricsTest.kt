// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LeaseLivenessAndMetricsTest {
    @Test
    fun `continuous inbound IMU does not suppress scheduled outbound keepalive`() {
        val monitor = LivenessMonitor(keepaliveIntervalNs = 1_000, missedIntervalsBeforeTimeout = 3)
        monitor.connect(0)
        for (now in 100L..900L step 100L) {
            monitor.onInbound(now)
            assertEquals(LivenessStatus.HEALTHY, monitor.poll(now))
        }
        monitor.onInbound(1_000)
        assertEquals(LivenessStatus.KEEPALIVE_DUE, monitor.poll(1_000))
    }

    @Test
    fun `lease deadline prevents work at and after exact expiry`() {
        val deadline = MonotonicLeaseDeadline.fromDurationMillis(startNs = 100, durationMs = 1)
        deadline.requireActive(1_000_099)
        assertThrows(LeaseExpiredException::class.java) { deadline.requireActive(1_000_100) }
        assertThrows(LeaseExpiredException::class.java) { deadline.requireActive(2_000_000) }
    }

    @Test
    fun `camera admission is bounded and requires a live realtime lane`() {
        val window = CameraLaneAdmissionWindow(expiresAtNs = 5_000)
        window.requireOpen(4_999, realtimeUsable = true)
        assertThrows(java.io.EOFException::class.java) {
            window.requireOpen(4_000, realtimeUsable = false)
        }
        assertThrows(java.net.SocketTimeoutException::class.java) {
            window.requireOpen(5_000, realtimeUsable = true)
        }
    }

    @Test
    fun `realtime reader EOF immediately closes camera admission`() {
        val gate = RealtimeAdmissionGate(CameraLaneAdmissionWindow(expiresAtNs = 50_000))
        gate.requireCameraAdmission(nowNs = 1_000)
        gate.markRealtimeClosed()
        assertThrows(java.io.EOFException::class.java) {
            gate.requireCameraAdmission(nowNs = 1_001)
        }
    }

    @Test
    fun `lease expiry dominates network fallout from the other lane`() {
        val termination = ConnectionTermination()
        val deadline = MonotonicLeaseDeadline.fromDurationMillis(startNs = 0, durationMs = 1)
        termination.record(java.io.EOFException("synthetic camera close"))

        val resolved = termination.resolve(
            java.io.EOFException("synthetic realtime close"),
            deadline,
            nowNs = 1_000_000,
        )

        assertEquals(LiveLinkDisconnectReason.LEASE_EXPIRED, classifyDisconnect(resolved))
    }

    @Test
    fun `authenticated close supersedes only close-like lane artifacts`() {
        val cameraTermination = ConnectionTermination()
        cameraTermination.record(CameraLaneClosedException(), LiveLinkFailureLane.CAMERA)
        val cameraResolved = cameraTermination.resolve(
            RemoteSessionCompletedException(),
            deadline = null,
            nowNs = 1L,
            authenticatedRemoteClose = true,
        )
        assertEquals(LiveLinkDisconnectReason.REMOTE_COMPLETED, classifyDisconnect(cameraResolved))
        assertEquals(LiveLinkFailureLane.CAMERA, cameraTermination.failureLane())

        val realtimeTermination = ConnectionTermination()
        realtimeTermination.record(SocketException("peer closed"), LiveLinkFailureLane.REALTIME_CONTROL)
        val realtimeResolved = realtimeTermination.resolve(
            SocketException("ack write closed"),
            deadline = null,
            nowNs = 1L,
            fallbackLane = LiveLinkFailureLane.REALTIME_CONTROL,
            authenticatedRemoteClose = true,
        )
        assertEquals(LiveLinkDisconnectReason.REMOTE_COMPLETED, classifyDisconnect(realtimeResolved))
        assertEquals(LiveLinkFailureLane.REALTIME_CONTROL, realtimeTermination.failureLane())

        listOf(
            FramingException(FramingFailure.TRUNCATED_RECORD) to LiveLinkDisconnectReason.PROTOCOL,
            SocketTimeoutException("read timeout") to LiveLinkDisconnectReason.TIMEOUT,
            IOException("unrelated I/O") to LiveLinkDisconnectReason.NETWORK,
        ).forEach { (failure, expectedReason) ->
            val termination = ConnectionTermination()
            termination.record(failure, LiveLinkFailureLane.CAMERA)
            val resolved = termination.resolve(
                RemoteSessionCompletedException(),
                deadline = null,
                nowNs = 1L,
                authenticatedRemoteClose = true,
            )
            assertEquals(expectedReason, classifyDisconnect(resolved))
        }

        val framingTermination = ConnectionTermination()
        val framing = FramingException(FramingFailure.INVALID_LENGTH)
        framingTermination.record(framing, LiveLinkFailureLane.CAMERA)
        val framingResolved = framingTermination.resolve(
            RemoteSessionCompletedException(),
            deadline = null,
            nowNs = 1L,
            authenticatedRemoteClose = true,
        )
        assertEquals(framing, framingResolved)
        assertEquals(LiveLinkFailureLane.CAMERA, framingTermination.failureLane())
        assertEquals(LiveLinkDiagnosticCode.FRAMING_INVALID_LENGTH, classifyDiagnostic(framingResolved))
    }

    @Test
    fun `negotiated baseline is exactly three to five FPS without microphone`() {
        val binding = LiveSessionBinding("session", "lease", ByteArray(32) { 1 })
        val request = LiveControlMessages.leaseRequest(binding).leaseRequest
        val grant = LiveControlMessages.leaseGrant(request).leaseGrant
        val deadline = MonotonicLeaseDeadline.fromDurationMillis(10, grant.grantedDurationMs)
        val negotiated = grant.toNegotiatedLease(deadline)

        assertEquals(3, negotiated.cameraRelaxedFps)
        assertEquals(5, negotiated.cameraMotionFps)
        assertEquals(
            setOf(
                org.conceptflow.mpl.v1.SensorStreamKind.SENSOR_STREAM_KIND_CAMERA,
                org.conceptflow.mpl.v1.SensorStreamKind.SENSOR_STREAM_KIND_IMU,
            ),
            grant.grantedStreamsList.toSet(),
        )
    }

    @Test
    fun `endpoint counters retain reconnect authentication and framing evidence`() {
        val metrics = SanitizedTransportMetrics()
        val accounting = EndpointMetricAccounting(metrics)
        accounting.established()
        accounting.established()
        accounting.failure(SSLHandshakeException("synthetic"))
        accounting.failure(FramingException(FramingFailure.INVALID_LENGTH))

        val snapshot = metrics.snapshot()
        assertEquals(1L, snapshot.reconnects)
        assertEquals(1L, snapshot.authenticationFailures)
        assertEquals(1L, snapshot.framingFailures)
    }

    @Test
    fun `close evidence contains fixed phases and lane only`() {
        val clientMetrics = SanitizedTransportMetrics()
        clientMetrics.recordClientClose(
            InitiatedSessionCloseOutcome(
                closeRequestWritten = true,
                writersDrained = false,
                acknowledgementReceived = true,
            ),
        )
        val client = clientMetrics.snapshot().closeEvidence
        assertEquals(true, client.clientCloseAttempted)
        assertEquals(true, client.clientCloseRequestWritten)
        assertEquals(false, client.clientWritersDrained)
        assertEquals(true, client.clientAcknowledgementReceived)
        assertEquals(LiveLinkFailureLane.NONE, client.hostFailureLane)

        val hostMetrics = SanitizedTransportMetrics()
        hostMetrics.recordHostClose(
            authenticatedCloseSeen = true,
            failureLane = LiveLinkFailureLane.CAMERA,
        )
        val host = hostMetrics.snapshot().closeEvidence
        assertEquals(true, host.hostAuthenticatedCloseSeen)
        assertEquals(LiveLinkFailureLane.CAMERA, host.hostFailureLane)
        assertEquals(false, host.clientCloseAttempted)
    }
}
