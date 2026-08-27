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
                when (sensor.payloadCase) {
                    SensorStreamEnvelope.PayloadCase.IMU_BATCH ->
                        if (sensor.imuBatch.leaseId != binding.leaseId) bindingMismatch()
                    SensorStreamEnvelope.PayloadCase.MICROPHONE_CHUNK ->
                        if (sensor.microphoneChunk.leaseId != binding.leaseId) bindingMismatch()
                    SensorStreamEnvelope.PayloadCase.TOUCH_EVENT ->
                        if (sensor.touchEvent.eventId == 0L) malformedControl()
                    else -> throw LaneProtocolException(LaneProtocolFailure.PAYLOAD_LANE_MISMATCH)
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
            LiveLinkControl.PayloadCase.CAPABILITIES -> {
                val capabilities = control.capabilities
                if (capabilities.protocolVersion.major != LiveControlMessages.PROTOCOL_MAJOR ||
                    capabilities.protocolVersion.minor > LiveControlMessages.PROTOCOL_MINOR ||
                    capabilities.peerRole.number == 0 ||
                    capabilities.maxCameraWidth == 0 || capabilities.maxCameraHeight == 0 ||
                    capabilities.maxCameraFrameBytes == 0L ||
                    capabilities.maxCameraFrameBytes > 8L * 1_024L * 1_024L ||
                    capabilities.maxAudioBlockBytes !in 256..256 * 1_024 ||
                    capabilities.maxImuSamplesPerBatch !in 1..64 ||
                    capabilities.maxTouchEventsBuffered !in 1..512
                ) malformedControl()
            }
            LiveLinkControl.PayloadCase.TELEMETRY -> {
                val telemetry = control.telemetry
                if (telemetry.sampledMonotonicTimestampNs == 0L ||
                    telemetry.pendingCameraFrames > 1 ||
                    telemetry.pendingImuBatches > 64 ||
                    telemetry.pendingAudioBlocks > 64 ||
                    telemetry.pendingTouchEvents > 256
                ) malformedControl()
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
            // These payloads have operation-specific exact-binding, freshness and replay
            // validation in the microphone control state machines.
            LiveLinkControl.PayloadCase.MICROPHONE_CONTROL_INTENT,
            LiveLinkControl.PayloadCase.MICROPHONE_CONTROL_RESULT,
            LiveLinkControl.PayloadCase.ROKID_GESTURE_INTENT,
            LiveLinkControl.PayloadCase.ROKID_NODE_COMMAND,
            LiveLinkControl.PayloadCase.ROKID_NODE_COMMAND_RESULT,
            LiveLinkControl.PayloadCase.SPOOL_MANIFEST_POLL,
            LiveLinkControl.PayloadCase.SPOOL_MANIFEST_SNAPSHOT,
            LiveLinkControl.PayloadCase.SPOOL_ARTIFACT_REQUEST,
            LiveLinkControl.PayloadCase.SPOOL_ARTIFACT_CHUNK,
            LiveLinkControl.PayloadCase.SPOOL_RECORDS_ACK,
            -> Unit
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

    private fun bindingMismatch(): Nothing =
        throw LaneProtocolException(LaneProtocolFailure.BINDING_MISMATCH)
}
