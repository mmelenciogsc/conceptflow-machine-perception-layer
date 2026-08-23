// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import com.google.protobuf.ByteString
import java.security.SecureRandom
import java.util.Locale
import org.conceptflow.mpl.v1.ClockSyncRequest
import org.conceptflow.mpl.v1.ClockSyncResponse
import org.conceptflow.mpl.v1.LiveLaneOpenRequest
import org.conceptflow.mpl.v1.LiveLaneOpenResponse
import org.conceptflow.mpl.v1.LiveLaneTicketGrant
import org.conceptflow.mpl.v1.LiveLinkControl
import org.conceptflow.mpl.v1.LiveLinkEnvelope
import org.conceptflow.mpl.v1.LiveLinkHello
import org.conceptflow.mpl.v1.LiveLinkKeepalive
import org.conceptflow.mpl.v1.LiveTransportLane
import org.conceptflow.mpl.v1.LiveTransportPeerRole
import org.conceptflow.mpl.v1.ProtocolVersion
import org.conceptflow.mpl.v1.SensorStreamEnvelope
import org.conceptflow.mpl.v1.SensorStreamKind
import org.conceptflow.mpl.v1.StreamLeaseGrant
import org.conceptflow.mpl.v1.StreamLeaseOperation
import org.conceptflow.mpl.v1.StreamLeaseRequest

fun interface MonotonicTimeSource {
    fun nowNs(): Long
}

object AndroidMonotonicTimeSource : MonotonicTimeSource {
    override fun nowNs(): Long = android.os.SystemClock.elapsedRealtimeNanos()
}

internal class EphemeralBindingFactory(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun create(): LiveSessionBinding {
        val nonce = ByteArray(LiveSessionBinding.CONNECTION_NONCE_BYTES).also(secureRandom::nextBytes)
        return LiveSessionBinding(
            sessionId = randomIdentifier("session"),
            leaseId = randomIdentifier("lease"),
            connectionNonce = nonce,
        )
    }

    private fun randomIdentifier(prefix: String): String {
        val bytes = ByteArray(16).also(secureRandom::nextBytes)
        val suffix = bytes.joinToString(separator = "") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
        return "$prefix-$suffix"
    }
}

internal class LiveEnvelopeFactory(
    private val binding: LiveSessionBinding,
    private val state: LiveConnectionState,
    private val clock: MonotonicTimeSource,
) {
    fun control(lane: LiveTransportLane, control: LiveLinkControl): LiveLinkEnvelope =
        base(lane).setControl(control).build()

    fun sensor(lane: LiveTransportLane, sensor: SensorStreamEnvelope): LiveLinkEnvelope {
        require(sensor.sessionId == binding.sessionId && sensor.leaseId == binding.leaseId) {
            "sensor record does not match the active binding"
        }
        require(!sensor.hasMicrophoneChunk()) { "microphone is disabled for the baseline live link" }
        return base(lane).setSensor(sensor).build()
    }

    private fun base(lane: LiveTransportLane): LiveLinkEnvelope.Builder = LiveLinkEnvelope.newBuilder()
        .setSessionId(binding.sessionId)
        .setLeaseId(binding.leaseId)
        .setLane(lane)
        .setLaneSequenceId(state.takeNextSequence(lane))
        .setSentMonotonicTimestampNs(clock.nowNs())
}

internal object LiveControlMessages {
    const val CLOCK_PROBES = 8

    fun hello(role: LiveTransportPeerRole, nonce: ByteArray): LiveLinkControl = LiveLinkControl.newBuilder()
        .setHello(
            LiveLinkHello.newBuilder()
                .setProtocolVersion(ProtocolVersion.newBuilder().setMajor(1).setMinor(0))
                .setPeerRole(role)
                .setConnectionNonce(ByteString.copyFrom(nonce)),
        ).build()

    fun ticketGrant(ticket: ByteArray, validForMs: Int): LiveLinkControl = LiveLinkControl.newBuilder()
        .setLaneTicketGrant(
            LiveLaneTicketGrant.newBuilder()
                .setLane(LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA)
                .setLaneTicket(ByteString.copyFrom(ticket))
                .setValidForMs(validForMs),
        ).build()

    fun leaseRequest(binding: LiveSessionBinding): LiveLinkControl = LiveLinkControl.newBuilder()
        .setLeaseRequest(
            StreamLeaseRequest.newBuilder()
                .setRequestId("live-link-open")
                .setSessionId(binding.sessionId)
                .setLeaseId(binding.leaseId)
                .setOperation(StreamLeaseOperation.STREAM_LEASE_OPERATION_OPEN)
                .addRequestedStreams(SensorStreamKind.SENSOR_STREAM_KIND_CAMERA)
                .addRequestedStreams(SensorStreamKind.SENSOR_STREAM_KIND_IMU)
                .setUserRequestedMicrophone(false)
                .setRequestedDurationMs(60_000)
                .setCameraRelaxedFps(3)
                .setCameraMotionFps(5)
                .setImuMaxBatchDelayMs(20)
                .setImuMaxSilenceMs(1_000),
        ).build()

    fun leaseClose(binding: LiveSessionBinding): LiveLinkControl = LiveLinkControl.newBuilder()
        .setLeaseRequest(
            StreamLeaseRequest.newBuilder()
                .setRequestId("live-link-close")
                .setSessionId(binding.sessionId)
                .setLeaseId(binding.leaseId)
                .setOperation(StreamLeaseOperation.STREAM_LEASE_OPERATION_CLOSE)
                .setRequestedDurationMs(0)
                .setUserRequestedMicrophone(false),
        ).build()

    fun isLeaseClose(control: LiveLinkControl, binding: LiveSessionBinding): Boolean =
        control.payloadCase == LiveLinkControl.PayloadCase.LEASE_REQUEST &&
            control.leaseRequest.requestId == "live-link-close" &&
            control.leaseRequest.sessionId == binding.sessionId &&
            control.leaseRequest.leaseId == binding.leaseId &&
            control.leaseRequest.operation == StreamLeaseOperation.STREAM_LEASE_OPERATION_CLOSE &&
            control.leaseRequest.requestedStreamsCount == 0 &&
            control.leaseRequest.requestedDurationMs == 0 &&
            !control.leaseRequest.userRequestedMicrophone

    fun leaseCloseAcknowledged(binding: LiveSessionBinding): LiveLinkControl = LiveLinkControl.newBuilder()
        .setLeaseGrant(
            StreamLeaseGrant.newBuilder()
                .setRequestId("live-link-close")
                .setSessionId(binding.sessionId)
                .setLeaseId(binding.leaseId)
                .setGrantedDurationMs(0),
        ).build()

    fun isLeaseCloseAcknowledgement(control: LiveLinkControl, binding: LiveSessionBinding): Boolean =
        control.payloadCase == LiveLinkControl.PayloadCase.LEASE_GRANT &&
            control.leaseGrant.requestId == "live-link-close" &&
            control.leaseGrant.sessionId == binding.sessionId &&
            control.leaseGrant.leaseId == binding.leaseId &&
            control.leaseGrant.grantedStreamsCount == 0 &&
            control.leaseGrant.grantedDurationMs == 0 &&
            !control.leaseGrant.hasError()

    fun leaseGrant(request: StreamLeaseRequest): LiveLinkControl {
        require(request.operation == StreamLeaseOperation.STREAM_LEASE_OPERATION_OPEN &&
            !request.userRequestedMicrophone &&
            request.requestedStreamsList.toSet() == setOf(
                SensorStreamKind.SENSOR_STREAM_KIND_CAMERA,
                SensorStreamKind.SENSOR_STREAM_KIND_IMU,
            )
        ) { "live-link lease requests may contain only camera and IMU" }
        return LiveLinkControl.newBuilder()
            .setLeaseGrant(
                StreamLeaseGrant.newBuilder()
                    .setRequestId(request.requestId)
                    .setSessionId(request.sessionId)
                    .setLeaseId(request.leaseId)
                    .addGrantedStreams(SensorStreamKind.SENSOR_STREAM_KIND_CAMERA)
                    .addGrantedStreams(SensorStreamKind.SENSOR_STREAM_KIND_IMU)
                    .setGrantedDurationMs(request.requestedDurationMs.coerceIn(1_000, 60_000))
                    .setCameraRelaxedFps(3)
                    .setCameraMotionFps(5)
                    .setImuMaxBatchDelayMs(20)
                    .setImuMaxSilenceMs(1_000),
            ).build()
    }

    fun clockRequest(probeId: Long, sentNs: Long): LiveLinkControl = LiveLinkControl.newBuilder()
        .setClockSyncRequest(
            ClockSyncRequest.newBuilder()
                .setProbeId(probeId)
                .setInitiatorSendMonotonicNs(sentNs),
        ).build()

    fun clockResponse(request: ClockSyncRequest, receiveNs: Long, sendNs: Long): LiveLinkControl =
        LiveLinkControl.newBuilder()
            .setClockSyncResponse(
                ClockSyncResponse.newBuilder()
                    .setProbeId(request.probeId)
                    .setInitiatorSendMonotonicNs(request.initiatorSendMonotonicNs)
                    .setResponderReceiveMonotonicNs(receiveNs)
                    .setResponderSendMonotonicNs(sendNs),
            ).build()

    fun laneOpen(binding: LiveSessionBinding, ticket: ByteArray): LiveLinkControl = LiveLinkControl.newBuilder()
        .setLaneOpenRequest(
            LiveLaneOpenRequest.newBuilder()
                .setLane(LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA)
                .setSessionId(binding.sessionId)
                .setLeaseId(binding.leaseId)
                .setConnectionNonce(ByteString.copyFrom(binding.connectionNonce))
                .setLaneTicket(ByteString.copyFrom(ticket)),
        ).build()

    fun laneOpenAccepted(): LiveLinkControl = LiveLinkControl.newBuilder()
        .setLaneOpenResponse(
            LiveLaneOpenResponse.newBuilder()
                .setLane(LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA)
                .setAccepted(true),
        ).build()

    fun keepalive(nonce: Long, sentNs: Long, response: Boolean): LiveLinkControl = LiveLinkControl.newBuilder()
        .setKeepalive(
            LiveLinkKeepalive.newBuilder()
                .setNonce(nonce)
                .setSentMonotonicNs(sentNs)
                .setResponse(response),
        ).build()
}

internal fun StreamLeaseGrant.toNegotiatedLease(deadline: MonotonicLeaseDeadline): NegotiatedLiveLease =
    NegotiatedLiveLease(
        expiresAtMonotonicNs = deadline.expiresAtNs,
        cameraRelaxedFps = cameraRelaxedFps,
        cameraMotionFps = cameraMotionFps,
        imuMaximumBatchDelayMs = imuMaxBatchDelayMs,
        imuMaximumSilenceMs = imuMaxSilenceMs,
    )
