// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import org.conceptflow.mpl.v1.ErrorCode
import org.conceptflow.mpl.v1.LiveLinkControl
import org.conceptflow.mpl.v1.LiveTransportPeerRole
import org.conceptflow.mpl.v1.MicrophoneControlOperation
import org.conceptflow.mpl.v1.RokidGestureOperation
import org.conceptflow.mpl.v1.RokidNodeCommandOperation
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
    fun `capability negotiation describes bounded implemented sensor transport`() {
        val glasses = LiveControlMessages.capabilities(
            LiveTransportPeerRole.LIVE_TRANSPORT_PEER_ROLE_GLASSES,
        )
        val accepted = LiveControlMessages.requireCompatibleCapabilities(
            LiveLinkControl.parseFrom(glasses.toByteArray()),
            LiveTransportPeerRole.LIVE_TRANSPORT_PEER_ROLE_GLASSES,
        )

        assertEquals(LiveControlMessages.PROTOCOL_MAJOR, accepted.protocolVersion.major)
        assertEquals(LiveControlMessages.PROTOCOL_MINOR, accepted.protocolVersion.minor)
        assertEquals(640, accepted.maxCameraWidth)
        assertEquals(640, accepted.maxCameraHeight)
        assertEquals(64, accepted.maxTouchEventsBuffered)
        assertTrue(accepted.supportsClockSync)
        assertTrue(accepted.supportsCameraLatestFrame)
        assertFalse(accepted.supportsDiagnosticSpool)
        assertTrue(
            LiveControlMessages.capabilities(
                LiveTransportPeerRole.LIVE_TRANSPORT_PEER_ROLE_GLASSES,
                supportsDiagnosticSpool = true,
            ).capabilities.supportsDiagnosticSpool,
        )
        assertThrows(IllegalArgumentException::class.java) {
            LiveControlMessages.requireCompatibleCapabilities(
                glasses,
                LiveTransportPeerRole.LIVE_TRANSPORT_PEER_ROLE_HOST,
            )
        }
    }

    @Test
    fun `telemetry contains only bounded aggregate pressure counters`() {
        val queues = LiveOutboundQueueSnapshot(1, 2, 3, 4, 5, 6, 7, 8)
        val transport = SanitizedTransportMetrics().apply {
            recordSent(org.conceptflow.mpl.v1.LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL, 10)
            recordSent(org.conceptflow.mpl.v1.LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA, 20)
        }.snapshot()
        val cameraGate = LiveCameraGateTelemetry(
            framesAnalyzed = 7,
            framesEmitted = 4,
            relaxedTierSamples = 3,
            motionTierSamples = 4,
            framesDroppedDark = 1,
            framesDroppedBlurry = 1,
            framesDroppedCadence = 1,
            currentTargetFramesPerSecond = 5,
        )

        val telemetry = LiveControlMessages.telemetry(9_000L, queues, transport, cameraGate).telemetry

        assertEquals(9_000L, telemetry.sampledMonotonicTimestampNs)
        assertEquals(1, telemetry.pendingCameraFrames)
        assertEquals(4, telemetry.pendingTouchEvents)
        assertEquals(8L, telemetry.touchOverflowEvents)
        assertEquals(1L, telemetry.sentRealtimeMessages)
        assertEquals(1L, telemetry.sentCameraMessages)
        assertEquals(7L, telemetry.cameraFramesAnalyzed)
        assertEquals(4L, telemetry.cameraFramesEmitted)
        assertEquals(3L, telemetry.cameraRelaxedTierSamples)
        assertEquals(4L, telemetry.cameraMotionTierSamples)
        assertEquals(1L, telemetry.cameraFramesDroppedDark)
        assertEquals(1L, telemetry.cameraFramesDroppedBlurry)
        assertEquals(1L, telemetry.cameraFramesDroppedCadence)
        assertEquals(5, telemetry.currentCameraTargetFps)
    }

    @Test
    fun `baseline lease grants camera IMU and touch but never microphone`() {
        val request = LiveControlMessages.leaseRequest(binding).leaseRequest
        val grant = LiveControlMessages.leaseGrant(request).leaseGrant

        assertEquals(
            setOf(
                SensorStreamKind.SENSOR_STREAM_KIND_CAMERA,
                SensorStreamKind.SENSOR_STREAM_KIND_IMU,
                SensorStreamKind.SENSOR_STREAM_KIND_TOUCH,
            ),
            grant.grantedStreamsList.toSet(),
        )
        assertFalse(grant.grantedStreamsList.contains(SensorStreamKind.SENSOR_STREAM_KIND_MICROPHONE))
        assertEquals(MAXIMUM_LIVE_LEASE_MILLIS, request.requestedDurationMs)
        assertEquals(MAXIMUM_LIVE_LEASE_MILLIS, grant.grantedDurationMs)
    }

    @Test
    fun `lease grant cannot exceed the exact ten minute cap`() {
        val oversized = LiveControlMessages.leaseRequest(binding).leaseRequest.toBuilder()
            .setRequestedDurationMs(MAXIMUM_LIVE_LEASE_MILLIS + 1)
            .build()

        assertEquals(
            MAXIMUM_LIVE_LEASE_MILLIS,
            LiveControlMessages.leaseGrant(oversized).leaseGrant.grantedDurationMs,
        )
    }

    @Test
    fun `client accepts only the matching bounded open grant`() {
        val grant = LiveControlMessages.leaseGrant(LiveControlMessages.leaseRequest(binding).leaseRequest)
            .leaseGrant

        assertTrue(grant.isAcceptedOpenGrant(binding))
        assertFalse(grant.toBuilder().setRequestId("different-request").build().isAcceptedOpenGrant(binding))
        assertFalse(grant.toBuilder().setGrantedDurationMs(0).build().isAcceptedOpenGrant(binding))
        assertFalse(
            grant.toBuilder().setGrantedDurationMs(MAXIMUM_LIVE_LEASE_MILLIS + 1).build()
                .isAcceptedOpenGrant(binding),
        )
    }

    @Test
    fun `microphone lease is separate explicit bound and ten seconds maximum`() {
        val control = LiveControlMessages.microphoneLeaseRequest(binding, originatingIntentId = 41L)
        assertTrue(LiveControlMessages.isMicrophoneLeaseRequest(control, binding))
        assertTrue(control.leaseRequest.userRequestedMicrophone)
        assertEquals(
            setOf(SensorStreamKind.SENSOR_STREAM_KIND_MICROPHONE),
            control.leaseRequest.requestedStreamsList.toSet(),
        )
        assertEquals(MAXIMUM_MICROPHONE_LEASE_MILLIS, control.leaseRequest.requestedDurationMs)
        assertEquals(41L, control.leaseRequest.originatingMicrophoneIntentId)

        val accepted = LiveControlMessages.microphoneLeaseGrant(control.leaseRequest, accepted = true)
        val rejected = LiveControlMessages.microphoneLeaseGrant(control.leaseRequest, accepted = false)
        assertTrue(LiveControlMessages.microphoneGrantAccepted(accepted, binding))
        assertTrue(LiveControlMessages.microphoneGrantAccepted(accepted, binding, 41L))
        assertFalse(LiveControlMessages.microphoneGrantAccepted(accepted, binding, 42L))
        assertEquals(41L, accepted.leaseGrant.originatingMicrophoneIntentId)
        assertTrue(LiveControlMessages.isMicrophoneGrantResponse(rejected, binding))
        assertFalse(LiveControlMessages.microphoneGrantAccepted(rejected, binding))
        assertFalse(
            LiveControlMessages.isMicrophoneLeaseRequest(
                control.toBuilder().setLeaseRequest(
                    control.leaseRequest.toBuilder().setUserRequestedMicrophone(false),
                ).build(),
                binding,
            ),
        )
    }

    @Test
    fun `gesture intent and result round trip with exact correlation`() {
        val start = LiveControlMessages.microphoneControlIntent(
            binding,
            intentId = 42L,
            createdMonotonicNs = 7_000_000_000L,
            operation = MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_START,
        )
        val parsed = LiveLinkControl.parseFrom(start.toByteArray())
        assertEquals(binding.sessionId, parsed.microphoneControlIntent.sessionId)
        assertEquals(binding.leaseId, parsed.microphoneControlIntent.leaseId)
        assertEquals(42L, parsed.microphoneControlIntent.intentId)
        assertTrue(parsed.microphoneControlIntent.userRequested)
        assertEquals(MAXIMUM_MICROPHONE_LEASE_MILLIS, parsed.microphoneControlIntent.requestedDurationMs)

        val accepted = LiveControlMessages.microphoneControlResult(
            binding,
            parsed.microphoneControlIntent,
        )
        val rejected = LiveControlMessages.microphoneControlResult(
            binding,
            parsed.microphoneControlIntent,
            MicrophoneIntentRejection.STALE,
        )
        assertTrue(LiveControlMessages.isMicrophoneControlResult(accepted, binding))
        assertFalse(rejected.microphoneControlResult.accepted)
        assertEquals(ErrorCode.ERROR_CODE_STALE, rejected.microphoneControlResult.error.code)
        assertTrue(LiveControlMessages.isMicrophoneControlResult(rejected, binding))
        assertFalse(
            LiveControlMessages.isMicrophoneControlResult(
                accepted.toBuilder().setMicrophoneControlResult(
                    accepted.microphoneControlResult.toBuilder().setLeaseId("other"),
                ).build(),
                binding,
            ),
        )

        val stop = LiveControlMessages.microphoneControlIntent(
            binding,
            intentId = 43L,
            createdMonotonicNs = 7_100_000_000L,
            operation = MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_STOP,
        )
        assertEquals(0, stop.microphoneControlIntent.requestedDurationMs)
    }

    @Test
    fun `Rokid gesture command and acknowledgement retain exact correlation`() {
        val gesture = LiveControlMessages.rokidGestureIntent(
            binding,
            gestureId = 71L,
            observedMonotonicNs = 9_000_000_000L,
            operation = RokidGestureOperation.ROKID_GESTURE_OPERATION_ENABLE_NODE,
        )
        val parsedGesture = LiveLinkControl.parseFrom(gesture.toByteArray()).rokidGestureIntent
        assertEquals(71L, parsedGesture.gestureId)
        assertTrue(parsedGesture.userInitiated)

        val command = LiveControlMessages.rokidNodeCommand(
            binding,
            commandId = 81L,
            originatingGestureId = parsedGesture.gestureId,
            issuedMonotonicNs = 10_000_000_000L,
            operation = RokidNodeCommandOperation.ROKID_NODE_COMMAND_OPERATION_ACTIVATE_NODE,
        )
        val parsedCommand = LiveLinkControl.parseFrom(command.toByteArray()).rokidNodeCommand
        assertEquals(71L, parsedCommand.originatingGestureId)
        assertEquals(MAXIMUM_ROKID_COMMAND_TTL_MILLIS, parsedCommand.validForMs)
        assertTrue(parsedCommand.userAuthorized)

        val result = LiveControlMessages.rokidNodeCommandResult(binding, parsedCommand, accepted = true)
        assertEquals(81L, LiveLinkControl.parseFrom(result.toByteArray()).rokidNodeCommandResult.commandId)
        assertTrue(result.rokidNodeCommandResult.acceptedForExecution)
        assertFalse(result.rokidNodeCommandResult.hasError())
    }

    @Test
    fun `baseline grant still rejects microphone mixed into ordinary capture`() {
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
