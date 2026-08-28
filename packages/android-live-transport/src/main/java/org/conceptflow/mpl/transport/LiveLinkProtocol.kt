// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import com.google.protobuf.ByteString
import java.security.SecureRandom
import java.util.Locale
import org.conceptflow.mpl.v1.ClockSyncRequest
import org.conceptflow.mpl.v1.ClockSyncResponse
import org.conceptflow.mpl.v1.ErrorCode
import org.conceptflow.mpl.v1.ErrorStatus
import org.conceptflow.mpl.v1.LiveLaneOpenRequest
import org.conceptflow.mpl.v1.LiveLaneOpenResponse
import org.conceptflow.mpl.v1.LiveLaneTicketGrant
import org.conceptflow.mpl.v1.LiveLinkControl
import org.conceptflow.mpl.v1.LiveLinkCapabilities
import org.conceptflow.mpl.v1.LiveLinkEnvelope
import org.conceptflow.mpl.v1.LiveLinkHello
import org.conceptflow.mpl.v1.LiveLinkKeepalive
import org.conceptflow.mpl.v1.LiveLinkTelemetry
import org.conceptflow.mpl.v1.LiveTransportLane
import org.conceptflow.mpl.v1.LiveTransportPeerRole
import org.conceptflow.mpl.v1.MicrophoneControlIntent
import org.conceptflow.mpl.v1.MicrophoneControlOperation
import org.conceptflow.mpl.v1.MicrophoneControlResult
import org.conceptflow.mpl.v1.ProtocolVersion
import org.conceptflow.mpl.v1.RokidGestureIntent
import org.conceptflow.mpl.v1.RokidGestureOperation
import org.conceptflow.mpl.v1.RokidNodeCommand
import org.conceptflow.mpl.v1.RokidNodeCommandOperation
import org.conceptflow.mpl.v1.RokidNodeCommandResult
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
        require(
            sensor.hasCameraChunk() && lane == LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA ||
                (sensor.hasImuBatch() || sensor.hasMicrophoneChunk() || sensor.hasTouchEvent()) &&
                lane == LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
        ) { "sensor payload does not match its transport lane" }
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
    const val PROTOCOL_MAJOR = 1
    const val PROTOCOL_MINOR = 2

    fun hello(role: LiveTransportPeerRole, nonce: ByteArray): LiveLinkControl = LiveLinkControl.newBuilder()
        .setHello(
            LiveLinkHello.newBuilder()
                .setProtocolVersion(
                    ProtocolVersion.newBuilder().setMajor(PROTOCOL_MAJOR).setMinor(PROTOCOL_MINOR),
                )
                .setPeerRole(role)
                .setConnectionNonce(ByteString.copyFrom(nonce)),
        ).build()

    fun capabilities(
        role: LiveTransportPeerRole,
        supportsDiagnosticSpool: Boolean = false,
    ): LiveLinkControl {
        require(role == LiveTransportPeerRole.LIVE_TRANSPORT_PEER_ROLE_GLASSES ||
            role == LiveTransportPeerRole.LIVE_TRANSPORT_PEER_ROLE_HOST
        )
        val builder = LiveLinkCapabilities.newBuilder()
            .setProtocolVersion(
                ProtocolVersion.newBuilder().setMajor(PROTOCOL_MAJOR).setMinor(PROTOCOL_MINOR),
            )
            .setPeerRole(role)
            .addSupportedStreams(SensorStreamKind.SENSOR_STREAM_KIND_CAMERA)
            .addSupportedStreams(SensorStreamKind.SENSOR_STREAM_KIND_IMU)
            .addSupportedStreams(SensorStreamKind.SENSOR_STREAM_KIND_MICROPHONE)
            .addSupportedStreams(SensorStreamKind.SENSOR_STREAM_KIND_TOUCH)
            .addCameraEncodings(org.conceptflow.mpl.v1.ImageEncoding.IMAGE_ENCODING_RGB8)
            .setMaxCameraWidth(640)
            .setMaxCameraHeight(640)
            .setMaxCameraFrameBytes(2L * 1_024L * 1_024L)
            .setMaxAudioBlockBytes(64 * 1_024)
            .setMaxImuSamplesPerBatch(64)
            .setMaxTouchEventsBuffered(
                if (role == LiveTransportPeerRole.LIVE_TRANSPORT_PEER_ROLE_GLASSES) 64 else 128,
            )
            .setSupportsClockSync(true)
            .setSupportsCameraLatestFrame(true)
            .setSupportsDiagnosticSpool(supportsDiagnosticSpool)
        return LiveLinkControl.newBuilder().setCapabilities(builder).build()
    }

    fun requireCompatibleCapabilities(
        control: LiveLinkControl,
        expectedRole: LiveTransportPeerRole,
    ): LiveLinkCapabilities {
        require(control.payloadCase == LiveLinkControl.PayloadCase.CAPABILITIES)
        val capabilities = control.capabilities
        require(capabilities.protocolVersion.major == PROTOCOL_MAJOR &&
            capabilities.protocolVersion.minor <= PROTOCOL_MINOR &&
            capabilities.peerRole == expectedRole &&
            capabilities.supportedStreamsList.toSet().containsAll(
                setOf(
                    SensorStreamKind.SENSOR_STREAM_KIND_CAMERA,
                    SensorStreamKind.SENSOR_STREAM_KIND_IMU,
                    SensorStreamKind.SENSOR_STREAM_KIND_MICROPHONE,
                    SensorStreamKind.SENSOR_STREAM_KIND_TOUCH,
                ),
            ) &&
            capabilities.cameraEncodingsList.contains(
                org.conceptflow.mpl.v1.ImageEncoding.IMAGE_ENCODING_RGB8,
            ) &&
            capabilities.maxCameraWidth >= 640 &&
            capabilities.maxCameraHeight >= 640 &&
            capabilities.maxCameraFrameBytes >= 640L * 640L * 3L &&
            capabilities.maxCameraFrameBytes <= 8L * 1_024L * 1_024L &&
            capabilities.maxAudioBlockBytes in 256..256 * 1_024 &&
            capabilities.maxImuSamplesPerBatch in 1..64 &&
            capabilities.maxTouchEventsBuffered in 1..512 &&
            capabilities.supportsClockSync && capabilities.supportsCameraLatestFrame
        ) { "incompatible live-link capabilities" }
        return capabilities
    }

    fun telemetry(
        sampledMonotonicNs: Long,
        queues: LiveOutboundQueueSnapshot,
        transport: TransportMetricsSnapshot,
        cameraGate: LiveCameraGateTelemetry = LiveCameraGateTelemetry(),
    ): LiveLinkControl {
        require(sampledMonotonicNs > 0L)
        return LiveLinkControl.newBuilder().setTelemetry(
            LiveLinkTelemetry.newBuilder()
                .setSampledMonotonicTimestampNs(sampledMonotonicNs)
                .setPendingCameraFrames(queues.pendingCameraFrames)
                .setPendingImuBatches(queues.pendingImuBatches)
                .setPendingAudioBlocks(queues.pendingMicrophoneChunks)
                .setPendingTouchEvents(queues.pendingTouchEvents)
                .setDroppedCameraFrames(queues.droppedCameraFrames)
                .setDroppedImuBatches(queues.droppedImuBatches)
                .setDroppedAudioBlocks(queues.droppedMicrophoneChunks)
                .setTouchOverflowEvents(queues.touchOverflowEvents)
                .setSentRealtimeMessages(transport.realtimeControl.sentMessages)
                .setSentCameraMessages(transport.camera.sentMessages)
                .setCameraFramesAnalyzed(cameraGate.framesAnalyzed)
                .setCameraFramesEmitted(cameraGate.framesEmitted)
                .setCameraRelaxedTierSamples(cameraGate.relaxedTierSamples)
                .setCameraMotionTierSamples(cameraGate.motionTierSamples)
                .setCameraFramesDroppedDark(cameraGate.framesDroppedDark)
                .setCameraFramesDroppedBlurry(cameraGate.framesDroppedBlurry)
                .setCameraFramesDroppedCadence(cameraGate.framesDroppedCadence)
                .setCurrentCameraTargetFps(cameraGate.currentTargetFramesPerSecond),
        ).build()
    }

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
                .setRequestId(LIVE_LINK_OPEN_REQUEST_ID)
                .setSessionId(binding.sessionId)
                .setLeaseId(binding.leaseId)
                .setOperation(StreamLeaseOperation.STREAM_LEASE_OPERATION_OPEN)
                .addRequestedStreams(SensorStreamKind.SENSOR_STREAM_KIND_CAMERA)
                .addRequestedStreams(SensorStreamKind.SENSOR_STREAM_KIND_IMU)
                .addRequestedStreams(SensorStreamKind.SENSOR_STREAM_KIND_TOUCH)
                .setUserRequestedMicrophone(false)
                .setRequestedDurationMs(MAXIMUM_LIVE_LEASE_MILLIS)
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
                SensorStreamKind.SENSOR_STREAM_KIND_TOUCH,
            )
        ) { "live-link lease requests may contain only camera, IMU, and touch" }
        return LiveLinkControl.newBuilder()
            .setLeaseGrant(
                StreamLeaseGrant.newBuilder()
                    .setRequestId(request.requestId)
                    .setSessionId(request.sessionId)
                    .setLeaseId(request.leaseId)
                    .addGrantedStreams(SensorStreamKind.SENSOR_STREAM_KIND_CAMERA)
                    .addGrantedStreams(SensorStreamKind.SENSOR_STREAM_KIND_IMU)
                    .addGrantedStreams(SensorStreamKind.SENSOR_STREAM_KIND_TOUCH)
                    .setGrantedDurationMs(request.requestedDurationMs.coerceIn(1_000, MAXIMUM_LIVE_LEASE_MILLIS))
                    .setCameraRelaxedFps(3)
                    .setCameraMotionFps(5)
                    .setImuMaxBatchDelayMs(20)
                    .setImuMaxSilenceMs(1_000),
            ).build()
    }

    fun microphoneLeaseRequest(
        binding: LiveSessionBinding,
        durationMillis: Int = MAXIMUM_MICROPHONE_LEASE_MILLIS,
        originatingIntentId: Long = 0L,
    ): LiveLinkControl {
        require(durationMillis in 1..MAXIMUM_MICROPHONE_LEASE_MILLIS)
        require(originatingIntentId >= 0L)
        return LiveLinkControl.newBuilder()
            .setLeaseRequest(
                StreamLeaseRequest.newBuilder()
                    .setRequestId(LIVE_LINK_MICROPHONE_REQUEST_ID)
                    .setSessionId(binding.sessionId)
                    .setLeaseId(binding.leaseId)
                    .setOperation(StreamLeaseOperation.STREAM_LEASE_OPERATION_OPEN)
                    .addRequestedStreams(SensorStreamKind.SENSOR_STREAM_KIND_MICROPHONE)
                    .setRequestedDurationMs(durationMillis)
                    .setUserRequestedMicrophone(true)
                    .setOriginatingMicrophoneIntentId(originatingIntentId),
            ).build()
    }

    fun isMicrophoneLeaseRequest(control: LiveLinkControl, binding: LiveSessionBinding): Boolean =
        control.payloadCase == LiveLinkControl.PayloadCase.LEASE_REQUEST &&
            control.leaseRequest.requestId == LIVE_LINK_MICROPHONE_REQUEST_ID &&
            control.leaseRequest.sessionId == binding.sessionId &&
            control.leaseRequest.leaseId == binding.leaseId &&
            control.leaseRequest.operation == StreamLeaseOperation.STREAM_LEASE_OPERATION_OPEN &&
            control.leaseRequest.requestedStreamsList.toSet() ==
            setOf(SensorStreamKind.SENSOR_STREAM_KIND_MICROPHONE) &&
            control.leaseRequest.requestedDurationMs in 1..MAXIMUM_MICROPHONE_LEASE_MILLIS &&
            control.leaseRequest.originatingMicrophoneIntentId >= 0L &&
            control.leaseRequest.userRequestedMicrophone

    fun microphoneLeaseGrant(
        request: StreamLeaseRequest,
        accepted: Boolean,
    ): LiveLinkControl {
        require(request.requestId == LIVE_LINK_MICROPHONE_REQUEST_ID)
        val grant = StreamLeaseGrant.newBuilder()
            .setRequestId(request.requestId)
            .setSessionId(request.sessionId)
            .setLeaseId(request.leaseId)
            .setOriginatingMicrophoneIntentId(request.originatingMicrophoneIntentId)
        if (accepted) {
            grant.addGrantedStreams(SensorStreamKind.SENSOR_STREAM_KIND_MICROPHONE)
                .setGrantedDurationMs(request.requestedDurationMs.coerceAtMost(MAXIMUM_MICROPHONE_LEASE_MILLIS))
        } else {
            grant.setGrantedDurationMs(0).setError(
                ErrorStatus.newBuilder()
                    .setCode(ErrorCode.ERROR_CODE_INVALID_ARGUMENT)
                    .setMessage("microphone request was not authorized")
                    .setRetryable(false),
            )
        }
        return LiveLinkControl.newBuilder().setLeaseGrant(grant).build()
    }

    fun microphoneGrantAccepted(
        control: LiveLinkControl,
        binding: LiveSessionBinding,
        expectedOriginatingIntentId: Long? = null,
    ): Boolean =
        control.payloadCase == LiveLinkControl.PayloadCase.LEASE_GRANT &&
            control.leaseGrant.requestId == LIVE_LINK_MICROPHONE_REQUEST_ID &&
            control.leaseGrant.sessionId == binding.sessionId &&
            control.leaseGrant.leaseId == binding.leaseId &&
            !control.leaseGrant.hasError() &&
            control.leaseGrant.grantedStreamsList.toSet() ==
            setOf(SensorStreamKind.SENSOR_STREAM_KIND_MICROPHONE) &&
            control.leaseGrant.grantedDurationMs in 1..MAXIMUM_MICROPHONE_LEASE_MILLIS &&
            (expectedOriginatingIntentId == null ||
                control.leaseGrant.originatingMicrophoneIntentId == expectedOriginatingIntentId)

    fun isMicrophoneGrantResponse(
        control: LiveLinkControl,
        binding: LiveSessionBinding,
        expectedOriginatingIntentId: Long? = null,
    ): Boolean =
        control.payloadCase == LiveLinkControl.PayloadCase.LEASE_GRANT &&
            control.leaseGrant.requestId == LIVE_LINK_MICROPHONE_REQUEST_ID &&
            control.leaseGrant.sessionId == binding.sessionId &&
            control.leaseGrant.leaseId == binding.leaseId &&
            (microphoneGrantAccepted(control, binding, expectedOriginatingIntentId) ||
                control.leaseGrant.hasError() && control.leaseGrant.grantedStreamsCount == 0 &&
                control.leaseGrant.grantedDurationMs == 0 &&
                (expectedOriginatingIntentId == null ||
                    control.leaseGrant.originatingMicrophoneIntentId == expectedOriginatingIntentId))

    fun microphoneControlIntent(
        binding: LiveSessionBinding,
        intentId: Long,
        createdMonotonicNs: Long,
        operation: MicrophoneControlOperation,
    ): LiveLinkControl {
        require(intentId > 0L && createdMonotonicNs > 0L)
        require(operation == MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_START ||
            operation == MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_STOP
        )
        return LiveLinkControl.newBuilder().setMicrophoneControlIntent(
            MicrophoneControlIntent.newBuilder()
                .setSessionId(binding.sessionId)
                .setLeaseId(binding.leaseId)
                .setIntentId(intentId)
                .setCreatedMonotonicTimestampNs(createdMonotonicNs)
                .setOperation(operation)
                .setUserRequested(true)
                .setRequestedDurationMs(
                    if (operation == MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_START) {
                        MAXIMUM_MICROPHONE_LEASE_MILLIS
                    } else {
                        0
                    },
                ),
        ).build()
    }

    fun microphoneControlResult(
        binding: LiveSessionBinding,
        intent: MicrophoneControlIntent,
        rejection: MicrophoneIntentRejection? = null,
    ): LiveLinkControl {
        val builder = MicrophoneControlResult.newBuilder()
            .setSessionId(binding.sessionId)
            .setLeaseId(binding.leaseId)
            .setIntentId(intent.intentId)
            .setOperation(intent.operation)
            .setAccepted(rejection == null)
        if (rejection != null) {
            builder.error = ErrorStatus.newBuilder()
                .setCode(
                    if (rejection == MicrophoneIntentRejection.STALE ||
                        rejection == MicrophoneIntentRejection.REPLAY
                    ) {
                        ErrorCode.ERROR_CODE_STALE
                    } else {
                        ErrorCode.ERROR_CODE_INVALID_ARGUMENT
                    },
                )
                .setMessage("microphone control intent rejected: ${rejection.wireName}")
                .setRetryable(false)
                .build()
        }
        return LiveLinkControl.newBuilder().setMicrophoneControlResult(builder).build()
    }

    fun isMicrophoneControlResult(
        control: LiveLinkControl,
        binding: LiveSessionBinding,
    ): Boolean {
        if (control.payloadCase != LiveLinkControl.PayloadCase.MICROPHONE_CONTROL_RESULT) return false
        val result = control.microphoneControlResult
        val validOperation = result.operation == MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_START ||
            result.operation == MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_STOP
        val validOutcome = result.accepted && !result.hasError() || !result.accepted && result.hasError()
        return result.sessionId == binding.sessionId && result.leaseId == binding.leaseId &&
            result.intentId > 0L && validOperation && validOutcome
    }

    fun rokidGestureIntent(
        binding: LiveSessionBinding,
        gestureId: Long,
        observedMonotonicNs: Long,
        operation: RokidGestureOperation,
    ): LiveLinkControl {
        require(gestureId > 0L && observedMonotonicNs > 0L)
        require(RokidGestureCommandPolicy.commandFor(operation) != null)
        return LiveLinkControl.newBuilder().setRokidGestureIntent(
            RokidGestureIntent.newBuilder()
                .setSessionId(binding.sessionId)
                .setLeaseId(binding.leaseId)
                .setGestureId(gestureId)
                .setObservedMonotonicTimestampNs(observedMonotonicNs)
                .setOperation(operation)
                .setUserInitiated(true),
        ).build()
    }

    fun rokidNodeCommand(
        binding: LiveSessionBinding,
        commandId: Long,
        originatingGestureId: Long,
        issuedMonotonicNs: Long,
        operation: RokidNodeCommandOperation,
    ): LiveLinkControl {
        require(commandId > 0L && originatingGestureId >= 0L && issuedMonotonicNs > 0L)
        require(operation in ALLOWED_ROKID_NODE_COMMANDS)
        return LiveLinkControl.newBuilder().setRokidNodeCommand(
            RokidNodeCommand.newBuilder()
                .setSessionId(binding.sessionId)
                .setLeaseId(binding.leaseId)
                .setCommandId(commandId)
                .setOriginatingGestureId(originatingGestureId)
                .setIssuedMonotonicTimestampNs(issuedMonotonicNs)
                .setValidForMs(MAXIMUM_ROKID_COMMAND_TTL_MILLIS)
                .setOperation(operation)
                .setUserAuthorized(true),
        ).build()
    }

    fun rokidNodeCommandResult(
        binding: LiveSessionBinding,
        command: RokidNodeCommand,
        accepted: Boolean,
    ): LiveLinkControl {
        val result = RokidNodeCommandResult.newBuilder()
            .setSessionId(binding.sessionId)
            .setLeaseId(binding.leaseId)
            .setCommandId(command.commandId)
            .setOriginatingGestureId(command.originatingGestureId)
            .setOperation(command.operation)
            .setAcceptedForExecution(accepted)
        if (!accepted) {
            result.error = ErrorStatus.newBuilder()
                .setCode(ErrorCode.ERROR_CODE_CANCELLED)
                .setMessage("Rokid Node command was not accepted for execution")
                .setRetryable(false)
                .build()
        }
        return LiveLinkControl.newBuilder().setRokidNodeCommandResult(result).build()
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

internal fun StreamLeaseGrant.isAcceptedOpenGrant(binding: LiveSessionBinding): Boolean =
    requestId == LIVE_LINK_OPEN_REQUEST_ID &&
        sessionId == binding.sessionId &&
        leaseId == binding.leaseId &&
        !hasError() &&
        grantedDurationMs in 1..MAXIMUM_LIVE_LEASE_MILLIS &&
        grantedStreamsList.toSet() == setOf(
            SensorStreamKind.SENSOR_STREAM_KIND_CAMERA,
            SensorStreamKind.SENSOR_STREAM_KIND_IMU,
            SensorStreamKind.SENSOR_STREAM_KIND_TOUCH,
        )

/** Ten-minute sensor workload plus a bounded authenticated shutdown envelope. */
internal const val MAXIMUM_LIVE_LEASE_MILLIS = 610_000
const val MAXIMUM_MICROPHONE_LEASE_MILLIS = 10_000
internal const val LIVE_LINK_OPEN_REQUEST_ID = "live-link-open"
internal const val LIVE_LINK_MICROPHONE_REQUEST_ID = "live-link-microphone-open"
private val ALLOWED_ROKID_NODE_COMMANDS = setOf(
    RokidNodeCommandOperation.ROKID_NODE_COMMAND_OPERATION_ACTIVATE_NODE,
    RokidNodeCommandOperation.ROKID_NODE_COMMAND_OPERATION_SLEEP_NODE,
    RokidNodeCommandOperation.ROKID_NODE_COMMAND_OPERATION_PLAY_BRAND_SEQUENCE,
)
