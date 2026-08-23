// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import org.conceptflow.mpl.v1.LiveLinkControl
import org.conceptflow.mpl.v1.LiveLinkEnvelope
import org.conceptflow.mpl.v1.LiveTransportLane
import org.conceptflow.mpl.v1.SensorStreamEnvelope

enum class LaneProtocolFailure {
    BINDING_MISMATCH,
    UNSUPPORTED_LANE,
    PAYLOAD_LANE_MISMATCH,
    MALFORMED_CONTROL,
    CAMERA_LANE_UNAUTHENTICATED,
    SEQUENCE_REPLAY_OR_GAP,
    SEQUENCE_EXHAUSTED,
}

class LaneProtocolException(val failure: LaneProtocolFailure) :
    SecurityException("live-link record rejected: $failure")

/** Sender-side sequence assignment. Call only when a record is actually dequeued for a lane. */
class PerLaneSequenceAllocator {
    private val next = mutableMapOf(
        LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL to 1L,
        LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA to 1L,
    )

    @Synchronized
    fun take(lane: LiveTransportLane): Long {
        val value = next[lane] ?: throw IllegalArgumentException("unsupported transport lane")
        if (value == Long.MAX_VALUE) throw LaneProtocolException(LaneProtocolFailure.SEQUENCE_EXHAUSTED)
        next[lane] = value + 1L
        return value
    }

    @Synchronized
    fun reset() {
        next.keys.forEach { next[it] = 1L }
    }
}

/** Validates complete records before atomically advancing an independent lane sequence. */
class PerLaneSequenceGuard(binding: LiveSessionBinding) {
    private var binding = binding
    private val nextExpected = mutableMapOf(
        LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL to 1L,
        LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA to 1L,
    )

    @Synchronized
    fun accept(envelope: LiveLinkEnvelope) {
        val lane = envelope.lane
        if (lane !in nextExpected) throw LaneProtocolException(LaneProtocolFailure.UNSUPPORTED_LANE)
        if (envelope.sessionId != binding.sessionId || envelope.leaseId != binding.leaseId) {
            throw LaneProtocolException(LaneProtocolFailure.BINDING_MISMATCH)
        }
        validatePayload(lane, envelope)
        val expected = nextExpected.getValue(lane)
        if (envelope.laneSequenceId != expected) {
            throw LaneProtocolException(LaneProtocolFailure.SEQUENCE_REPLAY_OR_GAP)
        }
        if (expected == Long.MAX_VALUE) {
            throw LaneProtocolException(LaneProtocolFailure.SEQUENCE_EXHAUSTED)
        }
        nextExpected[lane] = expected + 1L
    }

    @Synchronized
    fun reset(binding: LiveSessionBinding) {
        this.binding = binding
        nextExpected.keys.forEach { nextExpected[it] = 1L }
    }

    @Synchronized
    fun clear() {
        nextExpected.keys.forEach { nextExpected[it] = 1L }
    }

    @Synchronized
    fun nextExpected(lane: LiveTransportLane): Long =
        nextExpected[lane] ?: throw IllegalArgumentException("unsupported transport lane")

    private fun validatePayload(lane: LiveTransportLane, envelope: LiveLinkEnvelope) {
        when (envelope.payloadCase) {
            LiveLinkEnvelope.PayloadCase.CONTROL -> validateControl(lane, envelope.control)
            LiveLinkEnvelope.PayloadCase.SENSOR -> validateSensor(lane, envelope.sensor)
            LiveLinkEnvelope.PayloadCase.PAYLOAD_NOT_SET,
            null,
            -> throw LaneProtocolException(LaneProtocolFailure.PAYLOAD_LANE_MISMATCH)
        }
    }

    private fun validateSensor(lane: LiveTransportLane, sensor: SensorStreamEnvelope) {
        if (sensor.sessionId != binding.sessionId || sensor.leaseId != binding.leaseId) {
            throw LaneProtocolException(LaneProtocolFailure.BINDING_MISMATCH)
        }
        when (lane) {
            LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA -> {
                if (sensor.payloadCase != SensorStreamEnvelope.PayloadCase.CAMERA_CHUNK) {
                    throw LaneProtocolException(LaneProtocolFailure.PAYLOAD_LANE_MISMATCH)
                }
                val chunk = sensor.cameraChunk
                if (chunk.chunkIndex == 0 &&
                    (!chunk.hasFrameMetadata() || chunk.frameMetadata.sessionId != binding.sessionId)
                ) {
                    throw LaneProtocolException(LaneProtocolFailure.BINDING_MISMATCH)
                }
            }
            LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL -> {
                if (sensor.payloadCase != SensorStreamEnvelope.PayloadCase.IMU_BATCH ||
                    sensor.imuBatch.leaseId != binding.leaseId
                ) {
                    throw LaneProtocolException(LaneProtocolFailure.PAYLOAD_LANE_MISMATCH)
                }
            }
            else -> throw LaneProtocolException(LaneProtocolFailure.UNSUPPORTED_LANE)
        }
    }

    private fun validateControl(lane: LiveTransportLane, control: LiveLinkControl) {
        if (lane == LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA) {
            when (control.payloadCase) {
                LiveLinkControl.PayloadCase.LANE_OPEN_REQUEST -> {
                    val request = control.laneOpenRequest
                    if (request.lane != lane ||
                        !binding.matches(request.sessionId, request.leaseId, request.connectionNonce.toByteArray()) ||
                        request.laneTicket.isEmpty
                    ) {
                        throw LaneProtocolException(LaneProtocolFailure.BINDING_MISMATCH)
                    }
                }
                LiveLinkControl.PayloadCase.LANE_OPEN_RESPONSE -> {
                    if (control.laneOpenResponse.lane != lane) {
                        throw LaneProtocolException(LaneProtocolFailure.PAYLOAD_LANE_MISMATCH)
                    }
                }
                else -> throw LaneProtocolException(LaneProtocolFailure.PAYLOAD_LANE_MISMATCH)
            }
            return
        }

        if (lane != LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL) {
            throw LaneProtocolException(LaneProtocolFailure.UNSUPPORTED_LANE)
        }
        when (control.payloadCase) {
            LiveLinkControl.PayloadCase.HELLO -> {
                val hello = control.hello
                if (hello.protocolVersion.major == 0 ||
                    hello.peerRole.number == 0 ||
                    !binding.matches(binding.sessionId, binding.leaseId, hello.connectionNonce.toByteArray())
                ) {
                    throw LaneProtocolException(LaneProtocolFailure.MALFORMED_CONTROL)
                }
            }
            LiveLinkControl.PayloadCase.CLOCK_SYNC_REQUEST -> {
                if (control.clockSyncRequest.probeId == 0L) malformedControl()
            }
            LiveLinkControl.PayloadCase.CLOCK_SYNC_RESPONSE -> {
                val response = control.clockSyncResponse
                if (response.probeId == 0L ||
                    response.responderSendMonotonicNs < response.responderReceiveMonotonicNs
                ) malformedControl()
            }
            LiveLinkControl.PayloadCase.KEEPALIVE -> {
                if (control.keepalive.nonce == 0L) malformedControl()
            }
            LiveLinkControl.PayloadCase.LEASE_REQUEST -> {
                val request = control.leaseRequest
                if (request.sessionId != binding.sessionId || request.leaseId != binding.leaseId) {
                    throw LaneProtocolException(LaneProtocolFailure.BINDING_MISMATCH)
                }
            }
            LiveLinkControl.PayloadCase.LEASE_GRANT -> {
                val grant = control.leaseGrant
                if (grant.sessionId != binding.sessionId || grant.leaseId != binding.leaseId) {
                    throw LaneProtocolException(LaneProtocolFailure.BINDING_MISMATCH)
                }
            }
            LiveLinkControl.PayloadCase.LANE_TICKET_GRANT -> {
                val grant = control.laneTicketGrant
                if (grant.lane != LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA ||
                    grant.laneTicket.isEmpty ||
                    grant.validForMs == 0
                ) malformedControl()
            }
            LiveLinkControl.PayloadCase.ERROR -> Unit
            LiveLinkControl.PayloadCase.LANE_OPEN_REQUEST,
            LiveLinkControl.PayloadCase.LANE_OPEN_RESPONSE,
            LiveLinkControl.PayloadCase.PAYLOAD_NOT_SET,
            null,
            -> malformedControl()
        }
    }

    private fun malformedControl(): Nothing =
        throw LaneProtocolException(LaneProtocolFailure.MALFORMED_CONTROL)
}
