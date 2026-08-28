// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.conceptflow.mpl.transport.LiveLinkDisconnectReason
import org.conceptflow.mpl.transport.LiveLinkCloseEvidence
import org.conceptflow.mpl.transport.LiveLinkDiagnosticCode
import org.conceptflow.mpl.transport.LiveLinkSession
import org.conceptflow.mpl.transport.LiveCameraGateTelemetry
import org.conceptflow.mpl.transport.LiveSessionBinding
import org.conceptflow.mpl.transport.MicrophoneGestureDispatch
import org.conceptflow.mpl.transport.MicrophoneGestureResult
import org.conceptflow.mpl.transport.MicrophoneLeaseAuthorization
import org.conceptflow.mpl.transport.NegotiatedLiveLease
import org.conceptflow.mpl.transport.RokidGestureDispatch
import org.conceptflow.mpl.transport.RokidLiveLinkObserver
import org.conceptflow.mpl.v1.CoordinateFrame
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.MicrophoneControlOperation
import org.conceptflow.mpl.v1.Pose
import org.conceptflow.mpl.v1.RokidGestureOperation
import org.conceptflow.mpl.v1.Quaternion
import org.conceptflow.mpl.v1.SensorStreamEnvelope
import org.conceptflow.mpl.v1.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLinkCaptureControllerTest {
    @Test
    fun `camera gate telemetry observes existing decisions without changing frame publication`() {
        val fixture = Fixture()
        fixture.controller.start()
        fixture.transport.ready()
        val source = fixture.frames.single()

        source.emitGate(gateEvent(emitted = true, targetFramesPerSecond = 3.0))
        source.emit(frame())
        source.emitGate(
            gateEvent(
                emitted = false,
                targetFramesPerSecond = 5.0,
                dropReason = FrameDropReason.CADENCE_SIMILAR,
            ),
        )

        val gate = fixture.transport.cameraGateTelemetry.last()
        assertEquals(2L, gate.framesAnalyzed)
        assertEquals(1L, gate.framesEmitted)
        assertEquals(1L, gate.relaxedTierSamples)
        assertEquals(1L, gate.motionTierSamples)
        assertEquals(1L, gate.framesDroppedCadence)
        assertEquals(5, gate.currentTargetFramesPerSecond)
        assertEquals(1, fixture.transport.camera.size)
        assertEquals(gate, fixture.controller.snapshot().cameraGate)
    }

    @Test
    fun `admitted camera imu and microphone persist to spool instead of push queues`() {
        val spool = FakeSpool()
        val fixture = Fixture(microphonePermissionAvailable = true, sensorSpool = spool)
        fixture.controller.start()
        fixture.transport.ready()

        fixture.frames.single().emit(frame())
        fixture.poses.single().emit(sample(sequence = 1L, timestamp = fixture.clock.value))
        fixture.clock.value += 20_000_000L
        fixture.controller.poll()
        assertTrue(fixture.transport.requestMicrophone())
        fixture.audioSources.single().emit(
            PcmAudioChunk(1L, fixture.clock.value, 16_000, 1, byteArrayOf(1, 2)),
        )

        assertEquals(1, spool.camera.size)
        assertEquals(1, spool.imu.size)
        assertEquals(1, spool.microphone.size)
        assertTrue(fixture.transport.camera.isEmpty())
        assertTrue(fixture.transport.imu.isEmpty())
        assertTrue(fixture.transport.microphone.isEmpty())
    }

    @Test
    fun `node gesture keeps its observed time and enters the transport control lane`() {
        val fixture = Fixture()
        fixture.controller.start()

        assertEquals(
            RokidGestureDispatch.QUEUED,
            fixture.controller.requestRokidGesture(
                RokidGestureOperation.ROKID_GESTURE_OPERATION_ENABLE_NODE,
                900_000_000L,
            ),
        )
        assertEquals(
            RokidGestureOperation.ROKID_GESTURE_OPERATION_ENABLE_NODE to 900_000_000L,
            fixture.transport.nodeGestures.single(),
        )
    }

    @Test
    fun `wearer start waits for sublease and stop is immediate and idempotent`() {
        val fixture = Fixture(microphonePermissionAvailable = true)

        assertEquals(
            MicrophoneGestureDispatch.NO_AUTHENTICATED_SESSION,
            fixture.controller.requestMicrophoneFromUserGesture(),
        )
        fixture.controller.start()
        fixture.transport.ready()

        assertEquals(MicrophoneGestureDispatch.QUEUED, fixture.controller.requestMicrophoneFromUserGesture())
        assertEquals(1, fixture.transport.gestureStarts)
        assertTrue(fixture.audioSources.isEmpty())

        assertTrue(fixture.transport.requestMicrophone())
        val microphone = fixture.audioSources.single()
        assertTrue(microphone.isRunning)

        assertEquals(MicrophoneGestureDispatch.QUEUED, fixture.controller.stopMicrophoneFromUserGesture())
        assertFalse(microphone.isRunning)
        assertEquals(1, fixture.transport.gestureStops)
        assertTrue(fixture.frames.single().isRunning)
        assertTrue(fixture.poses.single().isRunning)

        assertEquals(MicrophoneGestureDispatch.QUEUED, fixture.controller.stopMicrophoneFromUserGesture())
        assertEquals(2, fixture.transport.gestureStops)
        assertTrue(fixture.frames.single().isRunning)
        assertTrue(fixture.poses.single().isRunning)
    }

    @Test
    fun `gesture rejection leaves camera and IMU streaming`() {
        val fixture = Fixture(microphonePermissionAvailable = true)
        fixture.controller.start()
        fixture.transport.ready()
        assertEquals(MicrophoneGestureDispatch.QUEUED, fixture.controller.requestMicrophoneFromUserGesture())

        fixture.transport.gestureResult(
            MicrophoneGestureResult(
                1L,
                MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_START,
                accepted = false,
            ),
        )

        assertEquals(LiveMicrophoneCaptureState.REJECTED_STATE, fixture.microphoneStates.last())
        assertTrue(fixture.frames.single().isRunning)
        assertTrue(fixture.poses.single().isRunning)
    }

    @Test
    fun microphoneRequiresPermissionAndAnAuthenticatedBoundLease() {
        val denied = Fixture(microphonePermissionAvailable = false)
        denied.controller.start()
        denied.transport.ready()
        assertFalse(denied.transport.requestMicrophone())
        assertTrue(denied.audioSources.isEmpty())
        assertEquals(LiveMicrophoneCaptureState.REJECTED_PERMISSION, denied.microphoneStates.last())
        assertTrue(denied.frames.single().isRunning)
        assertTrue(denied.poses.single().isRunning)

        val mismatched = Fixture(microphonePermissionAvailable = true)
        mismatched.controller.start()
        mismatched.transport.ready()
        assertFalse(mismatched.transport.requestMicrophone(sessionId = "other-session"))
        assertTrue(mismatched.audioSources.isEmpty())
        assertEquals(LiveMicrophoneCaptureState.REJECTED_STATE, mismatched.microphoneStates.last())
        assertTrue(mismatched.frames.single().isRunning)
        assertTrue(mismatched.poses.single().isRunning)

        val allowed = Fixture(microphonePermissionAvailable = true)
        allowed.controller.start()
        allowed.transport.ready()
        assertTrue(allowed.transport.requestMicrophone())
        val microphone = allowed.audioSources.single()
        assertTrue(microphone.isRunning)
        microphone.emit(PcmAudioChunk(1L, allowed.clock.value, 16_000, 1, byteArrayOf(1, 2)))
        assertEquals(1L, allowed.controller.snapshot().microphoneChunksQueued)
        assertTrue(allowed.transport.microphone.single().hasMicrophoneChunk())

        allowed.clock.value += 10_000_000_000L
        allowed.controller.poll()
        assertFalse(microphone.isRunning)
        assertTrue(allowed.frames.single().isRunning)
        assertTrue(allowed.poses.single().isRunning)
        assertEquals(LiveMicrophoneCaptureState.STOPPED, allowed.microphoneStates.last())
    }

    @Test
    fun microphoneForegroundPromotionMustSucceedBeforeRecorderCreation() {
        val events = mutableListOf<String>()
        val fixture = Fixture(
            microphonePermissionAvailable = true,
            beforeMicrophoneStart = {
                events += "foreground"
                false
            },
            onAudioFactory = { events += "microphone" },
        )
        fixture.controller.start()
        fixture.transport.ready()

        assertTrue(fixture.transport.requestMicrophone())
        assertEquals(listOf("foreground"), events)
        assertTrue(fixture.audioSources.isEmpty())
        assertTrue(fixture.frames.single().isRunning)
        assertTrue(fixture.poses.single().isRunning)
        assertEquals(LiveMicrophoneCaptureState.SOURCE_FAILURE, fixture.microphoneStates.last())
    }

    @Test
    fun microphoneCannotStartAfterTheBaseLeaseExpires() {
        val events = mutableListOf<String>()
        val fixture = Fixture(
            microphonePermissionAvailable = true,
            beforeMicrophoneStart = {
                events += "foreground"
                true
            },
            onAudioFactory = { events += "microphone" },
        )
        fixture.controller.start()
        fixture.transport.ready(expiresAtNanos = fixture.clock.value + 1_000_000L)
        fixture.clock.value += 2_000_000L

        assertFalse(fixture.transport.requestMicrophone())
        assertTrue(events.isEmpty())
        assertTrue(fixture.audioSources.isEmpty())
        assertEquals(LiveMicrophoneCaptureState.REJECTED_STATE, fixture.microphoneStates.last())
    }

    @Test
    fun producersStartOnlyAfterReadyAndUseNegotiatedBinding() {
        val fixture = Fixture()

        assertTrue(fixture.controller.start())
        assertTrue(fixture.frames.isEmpty())
        assertTrue(fixture.poses.isEmpty())

        fixture.transport.ready("negotiated-session", "negotiated-lease")
        val frameSource = fixture.frames.single()
        val poseSource = fixture.poses.single()
        assertTrue(frameSource.isRunning)
        assertTrue(poseSource.isRunning)

        frameSource.emit(frame(sessionId = "camera-local-session"))
        poseSource.emit(sample(sequence = 1L, timestamp = fixture.clock.value))
        fixture.clock.value += 20_000_000L
        fixture.controller.poll()

        val chunks = fixture.transport.camera.single()
        assertTrue(chunks.all { it.sessionId == "negotiated-session" })
        assertTrue(chunks.all { it.leaseId == "negotiated-lease" })
        assertEquals("negotiated-session", chunks.first().cameraChunk.frameMetadata.sessionId)
        val imu = fixture.transport.imu.single()
        assertEquals("negotiated-session", imu.sessionId)
        assertEquals("negotiated-lease", imu.leaseId)
        assertFalse(imu.hasMicrophoneChunk())
    }

    @Test
    fun unavailableOutboundLaneImmediatelyStopsProducers() {
        val fixture = Fixture()
        fixture.controller.start()
        fixture.transport.ready()
        fixture.transport.acceptCamera = false

        fixture.frames.single().emit(frame())

        assertEquals(LiveLinkCaptureState.CONNECTING, fixture.controller.snapshot().state)
        assertFalse(fixture.frames.single().isRunning)
        assertFalse(fixture.poses.single().isRunning)
        assertEquals(1L, fixture.controller.snapshot().cameraFramesDropped)
        assertFalse(fixture.frameController.hasActiveSource)
    }

    @Test
    fun disconnectAndReconnectReplaceRatherThanDuplicateProducers() {
        val fixture = Fixture()
        fixture.controller.start()
        fixture.transport.ready("session-one", "lease-one")
        val firstFrame = fixture.frames.single()
        val firstPose = fixture.poses.single()

        fixture.transport.disconnect(LiveLinkDisconnectReason.NETWORK)
        assertFalse(firstFrame.isRunning)
        assertFalse(firstPose.isRunning)
        assertFalse(fixture.frameController.hasActiveSource)

        fixture.transport.ready("session-two", "lease-two")
        assertEquals(2, fixture.frames.size)
        assertEquals(2, fixture.poses.size)
        assertEquals(1, fixture.frames.count(FakeFrameSource::isRunning))
        assertEquals(1, fixture.poses.count(FakePoseSource::isRunning))

        firstFrame.emitLate(frame(frameId = 2L))
        assertTrue(fixture.transport.camera.isEmpty())
        fixture.frames.last().emit(frame(frameId = 3L))
        assertEquals("session-two", fixture.transport.camera.single().first().sessionId)
        assertEquals(2L, fixture.controller.snapshot().producerStarts)
    }

    @Test
    fun recoverableCameraFailureReplacesOnlyCameraAndKeepsAuthenticatedStreamAlive() {
        val fixture = Fixture()
        fixture.controller.start()
        fixture.transport.ready()
        val firstCamera = fixture.frames.single()
        val pose = fixture.poses.single()
        repeat(158) { expected -> assertEquals(expected + 1L, firstCamera.nextFrameId()) }
        firstCamera.emitPipeline(CapturePipelineSnapshot(2, 0, 0, 0, 0, 0, 0, 1))

        val diagnostic = CameraSourceDiagnostic(
            CameraSourceDiagnosticDomain.CAMERA_ACCESS_EXCEPTION,
            3,
            "CAMERA_ERROR",
            recoverable = true,
        )
        firstCamera.emitRecoverableError("camera restart requested", diagnostic)

        val snapshot = fixture.controller.snapshot()
        assertEquals(LiveLinkCaptureState.STREAMING, snapshot.state)
        assertNull(snapshot.stopReason)
        assertEquals(0, fixture.transport.closeCount)
        assertFalse(firstCamera.isRunning)
        assertEquals(2, fixture.frames.size)
        assertTrue(fixture.frames.last().isRunning)
        assertSame(pose, fixture.poses.single())
        assertTrue(pose.isRunning)
        assertEquals(1L, snapshot.producerStarts)
        assertEquals(1L, snapshot.cameraSourceRestarts)
        assertEquals(diagnostic, snapshot.lastCameraSourceDiagnostic)

        firstCamera.emitLateError("stale terminal callback")
        fixture.frames.last().emitPipeline(CapturePipelineSnapshot(3, 0, 0, 0, 0, 0, 0, 1))
        val postRestartFrameId = fixture.frames.last().nextFrameId()
        assertEquals(159L, postRestartFrameId)
        fixture.frames.last().emit(frame(frameId = postRestartFrameId))
        assertEquals(1L, fixture.controller.snapshot().cameraFramesQueued)
        assertEquals(5L, fixture.controller.snapshot().cameraSourceTiming.pipeline.requestsSubmitted)
        assertEquals(LiveLinkCaptureState.STREAMING, fixture.controller.snapshot().state)
    }

    @Test
    fun cameraRecoveryAttemptsAreBoundedBeforeFailingTheAuthenticatedStream() {
        val fixture = Fixture(maximumCameraRestartAttempts = 1)
        fixture.controller.start()
        fixture.transport.ready()

        fixture.frames.single().emitRecoverableError("first camera failure")
        assertEquals(LiveLinkCaptureState.STREAMING, fixture.controller.snapshot().state)
        fixture.frames.last().emitRecoverableError("replacement camera failure")

        assertEquals(LiveLinkCaptureState.STOPPED, fixture.controller.snapshot().state)
        assertEquals(LiveLinkCaptureStopReason.SOURCE_FAILURE, fixture.controller.snapshot().stopReason)
        assertEquals(1, fixture.transport.closeCount)
        assertFalse(fixture.poses.single().isRunning)
    }

    @Test
    fun cameraRestartRechecksLeaseExpiryAfterFailedSourceStops() {
        val fixture = Fixture()
        fixture.controller.start()
        fixture.transport.ready(expiresAtNanos = 2_000_000_000L)
        fixture.frames.single().onStop = { fixture.clock.value = 2_000_000_000L }

        fixture.frames.single().emitRecoverableError("camera restart requested")

        assertEquals(1, fixture.frames.size)
        assertEquals(LiveLinkCaptureState.STOPPED, fixture.controller.snapshot().state)
        assertEquals(LiveLinkCaptureStopReason.LEASE_EXPIRED, fixture.controller.snapshot().stopReason)
    }

    @Test
    fun cameraRestartRechecksTotalDeadlineAfterFailedSourceStops() {
        val fixture = Fixture(runDurationMillis = 1_000L)
        fixture.controller.start()
        fixture.transport.ready(expiresAtNanos = 10_000_000_000L)
        fixture.frames.single().onStop = { fixture.clock.value = 2_000_000_000L }

        fixture.frames.single().emitRecoverableError("camera restart requested")

        assertEquals(1, fixture.frames.size)
        assertEquals(LiveLinkCaptureState.STOPPED, fixture.controller.snapshot().state)
        assertEquals(LiveLinkCaptureStopReason.TIME_LIMIT_REACHED, fixture.controller.snapshot().stopReason)
    }

    @Test
    fun retryAndActiveTimeBoundsTerminateExactlyOnce() {
        val retryFixture = Fixture(maximumDisconnects = 2)
        retryFixture.controller.start()
        retryFixture.transport.disconnect(LiveLinkDisconnectReason.AUTHENTICATION)
        retryFixture.transport.disconnect(LiveLinkDisconnectReason.PROTOCOL)

        assertEquals(LiveLinkCaptureState.STOPPED, retryFixture.controller.snapshot().state)
        assertEquals(LiveLinkCaptureStopReason.RETRY_LIMIT_REACHED, retryFixture.controller.snapshot().stopReason)
        assertEquals(1, retryFixture.transport.closeCount)
        assertEquals(1, retryFixture.terminals.size)

        val timeFixture = Fixture(runDurationMillis = 1_000L)
        timeFixture.controller.start()
        timeFixture.clock.value += 1_000_000_000L
        timeFixture.controller.poll()
        assertEquals(LiveLinkCaptureState.CONNECTING, timeFixture.controller.snapshot().state)
        assertTrue(timeFixture.frames.isEmpty())
        timeFixture.transport.ready(expiresAtNanos = timeFixture.clock.value + 2_000_000_000L)
        timeFixture.clock.value += 1_000_000_000L
        timeFixture.controller.poll()
        timeFixture.controller.stop()

        assertEquals(LiveLinkCaptureStopReason.TIME_LIMIT_REACHED, timeFixture.controller.snapshot().stopReason)
        assertEquals(1, timeFixture.transport.closeCount)
        assertEquals(1, timeFixture.terminals.size)
    }

    @Test
    fun firstTransientDisconnectBeforeAuthenticationEndsTheRendezvousEpoch() {
        val fixture = Fixture(maximumDisconnects = 6)
        fixture.controller.start()

        fixture.transport.disconnect(LiveLinkDisconnectReason.NETWORK)

        assertEquals(LiveLinkCaptureState.STOPPED, fixture.controller.snapshot().state)
        assertEquals(LiveLinkCaptureStopReason.RETRY_LIMIT_REACHED, fixture.controller.snapshot().stopReason)
        assertEquals(1L, fixture.controller.snapshot().disconnects)
        assertEquals(1, fixture.transport.closeCount)
        assertTrue(fixture.frames.isEmpty())
        assertTrue(fixture.poses.isEmpty())
    }

    @Test
    fun totalPreAuthenticationDeadlineUsesDistinctTerminalReason() {
        val fixture = Fixture()
        fixture.controller.start()

        fixture.controller.stop(LiveLinkCaptureStopReason.RENDEZVOUS_TIMEOUT)

        assertEquals(LiveLinkCaptureState.STOPPED, fixture.controller.snapshot().state)
        assertEquals(LiveLinkCaptureStopReason.RENDEZVOUS_TIMEOUT, fixture.controller.snapshot().stopReason)
        assertEquals(1, fixture.transport.closeCount)
        assertTrue(fixture.frames.isEmpty())
        assertTrue(fixture.poses.isEmpty())
    }

    @Test
    fun authenticatedRunRetainsSixDisconnectBoundWithoutExtendingDeadline() {
        val fixture = Fixture(maximumDisconnects = 6)
        fixture.controller.start()
        fixture.transport.ready()
        val deadline = fixture.controller.snapshot().activeDeadlineNanos

        repeat(5) { index ->
            fixture.transport.disconnect(LiveLinkDisconnectReason.NETWORK)
            assertEquals(LiveLinkCaptureState.CONNECTING, fixture.controller.snapshot().state)
            fixture.transport.ready(
                sessionId = "session-${index + 2}",
                leaseId = "lease-${index + 2}",
            )
            assertEquals(deadline, fixture.controller.snapshot().activeDeadlineNanos)
        }
        fixture.transport.disconnect(LiveLinkDisconnectReason.NETWORK)

        assertEquals(LiveLinkCaptureState.STOPPED, fixture.controller.snapshot().state)
        assertEquals(LiveLinkCaptureStopReason.RETRY_LIMIT_REACHED, fixture.controller.snapshot().stopReason)
        assertEquals(6L, fixture.controller.snapshot().disconnects)
        assertEquals(deadline, fixture.controller.snapshot().activeDeadlineNanos)
    }

    @Test
    fun firstAuthenticatedSessionStartsOneDeadlineThatReconnectCannotExtend() {
        val fixture = Fixture(runDurationMillis = 1_000L)
        fixture.controller.start()
        assertEquals(null, fixture.controller.snapshot().activeDeadlineNanos)
        fixture.transport.ready(expiresAtNanos = fixture.clock.value + 5_000_000_000L)
        val deadline = fixture.controller.snapshot().activeDeadlineNanos

        fixture.clock.value += 400_000_000L
        fixture.transport.disconnect(LiveLinkDisconnectReason.NETWORK)
        fixture.transport.ready(
            sessionId = "replacement-session",
            leaseId = "replacement-lease",
            expiresAtNanos = fixture.clock.value + 5_000_000_000L,
        )

        assertEquals(deadline, fixture.controller.snapshot().activeDeadlineNanos)
        fixture.clock.value = requireNotNull(deadline)
        fixture.controller.poll()
        assertEquals(LiveLinkCaptureStopReason.TIME_LIMIT_REACHED, fixture.controller.snapshot().stopReason)
    }

    @Test
    fun foregroundGateRunsBeforeFactoriesAndFailureKeepsSensorsOff() {
        val events = mutableListOf<String>()
        val fixture = Fixture(
            beforeProducerStart = {
                events += "foreground"
                false
            },
            onFrameFactory = { events += "camera" },
            onPoseFactory = { events += "imu" },
        )
        fixture.controller.start()

        fixture.transport.ready()

        assertEquals(listOf("foreground"), events)
        assertTrue(fixture.frames.isEmpty())
        assertTrue(fixture.poses.isEmpty())
        assertFalse(fixture.frameController.hasActiveSource)
        assertEquals(LiveLinkCaptureStopReason.SOURCE_FAILURE, fixture.controller.snapshot().stopReason)
    }

    @Test
    fun foregroundGateCompletesBeforeEitherProducerFactoryRuns() {
        val events = mutableListOf<String>()
        val fixture = Fixture(
            beforeProducerStart = {
                events += "foreground"
                true
            },
            onFrameFactory = { events += "camera" },
            onPoseFactory = { events += "imu" },
        )
        fixture.controller.start()

        fixture.transport.ready()

        assertEquals(listOf("foreground", "camera", "imu"), events)
        assertTrue(fixture.frames.single().isRunning)
        assertTrue(fixture.poses.single().isRunning)
    }

    @Test
    fun authenticationAndProtocolFailuresFailClosedWithoutUsingRetryBudget() {
        listOf(
            LiveLinkDisconnectReason.AUTHENTICATION,
            LiveLinkDisconnectReason.CONFIGURATION,
            LiveLinkDisconnectReason.PROTOCOL,
        ).forEach { reason ->
            val fixture = Fixture(maximumDisconnects = 6)
            fixture.controller.start()
            fixture.transport.disconnect(reason)
            assertEquals(LiveLinkCaptureState.STOPPED, fixture.controller.snapshot().state)
            assertEquals(0L, fixture.controller.snapshot().disconnects)
            assertTrue(fixture.frames.isEmpty())
            assertTrue(fixture.poses.isEmpty())
        }
    }

    @Test
    fun hostCompletionTerminatesWithoutEnteringTheReconnectLoop() {
        val fixture = Fixture(maximumDisconnects = 6)
        fixture.controller.start()
        fixture.transport.ready()

        fixture.transport.disconnect(LiveLinkDisconnectReason.REMOTE_COMPLETED)

        val snapshot = fixture.controller.snapshot()
        assertEquals(LiveLinkCaptureState.STOPPED, snapshot.state)
        assertEquals(LiveLinkCaptureStopReason.REMOTE_COMPLETED, snapshot.stopReason)
        assertEquals(0L, snapshot.disconnects)
        assertEquals(1, fixture.transport.closeCount)
        assertEquals(1, fixture.terminals.size)
        assertFalse(fixture.frames.single().isRunning)
        assertFalse(fixture.poses.single().isRunning)
    }

    @Test
    fun negotiatedLeaseDeadlineAndCadenceAreAuthoritative() {
        val fixture = Fixture()
        fixture.controller.start()
        val leaseDeadline = fixture.clock.value + 500_000_000L
        fixture.transport.ready(
            expiresAtNanos = leaseDeadline,
            imuMaximumBatchDelayMs = 5,
            imuMaximumSilenceMs = 500,
        )

        assertEquals(3, fixture.negotiatedLeases.single().cameraRelaxedFps)
        assertEquals(5, fixture.negotiatedLeases.single().cameraMotionFps)
        fixture.poses.single().emit(sample(sequence = 1L, timestamp = fixture.clock.value))
        fixture.clock.value += 5_000_000L
        fixture.controller.poll()
        assertEquals(1, fixture.transport.imu.size)
        fixture.clock.value = leaseDeadline
        fixture.controller.poll()

        assertEquals(LiveLinkCaptureState.STOPPED, fixture.controller.snapshot().state)
        assertEquals(LiveLinkCaptureStopReason.LEASE_EXPIRED, fixture.controller.snapshot().stopReason)
        assertFalse(fixture.frames.single().isRunning)
        assertFalse(fixture.poses.single().isRunning)
    }

    @Test
    fun sanitizedTransportDiagnosticReachesAggregateSnapshot() {
        val fixture = Fixture()
        fixture.controller.start()

        fixture.transport.diagnostic(LiveLinkDiagnosticCode.TLS_KEY_TYPE_UNSUPPORTED)

        assertEquals(
            LiveLinkDiagnosticCode.TLS_KEY_TYPE_UNSUPPORTED,
            fixture.controller.snapshot().lastDiagnosticCode,
        )
    }

    @Test
    fun sanitizedCloseEvidenceReachesTerminalSnapshot() {
        val fixture = Fixture(runDurationMillis = 1_000L)
        fixture.transport.evidence = LiveLinkCloseEvidence(
            clientCloseAttempted = true,
            clientCloseRequestWritten = true,
            clientWritersDrained = true,
            clientAcknowledgementReceived = false,
        )
        fixture.controller.start()
        fixture.transport.ready(expiresAtNanos = fixture.clock.value + 2_000_000_000L)
        fixture.clock.value += 1_000_000_000L

        fixture.controller.poll()

        val evidence = fixture.terminals.single().closeEvidence
        assertTrue(evidence.clientCloseAttempted)
        assertTrue(evidence.clientCloseRequestWritten)
        assertTrue(evidence.clientWritersDrained)
        assertFalse(evidence.clientAcknowledgementReceived)
    }

    @Test
    fun terminalPublicationWaitsForAsynchronousTransportShutdownCompletion() {
        val fixture = Fixture(runDurationMillis = 1_000L)
        fixture.transport.completeCloseImmediately = false
        fixture.transport.evidence = LiveLinkCloseEvidence(
            clientCloseAttempted = true,
            clientCloseRequestWritten = true,
            clientWritersDrained = true,
            clientAcknowledgementReceived = true,
        )
        fixture.controller.start()
        fixture.transport.ready(expiresAtNanos = fixture.clock.value + 2_000_000_000L)
        fixture.clock.value += 1_000_000_000L

        fixture.controller.poll()

        assertEquals(LiveLinkCaptureState.STOPPED, fixture.controller.snapshot().state)
        assertEquals(LiveLinkCaptureState.STOPPED, fixture.statuses.last().state)
        assertEquals(1, fixture.transport.closeCount)
        assertTrue(fixture.terminals.isEmpty())

        fixture.transport.completeClose(callbackCount = 2)

        assertEquals(1, fixture.terminals.size)
        assertTrue(fixture.terminals.single().closeEvidence.clientAcknowledgementReceived)
    }

    @Test
    fun cameraSourceTimingsAndReconnectPipelineCountersRemainAggregateOnly() {
        val fixture = Fixture()
        fixture.controller.start()
        fixture.transport.ready("session-one", "lease-one")
        fixture.frames.single().emitTiming(
            CaptureTimingEvent(
                analyzedMonotonicTimestampNanos = 2_000_000_000L,
                emittedMonotonicTimestampNanos = 2_000_000_400L,
                requestToImageLatencyNanos = 100L,
                imageAcquisitionDurationNanos = 200L,
                processorDurationNanos = 300L,
                listenerPathDurationNanos = 400L,
            ),
        )
        fixture.frames.single().emitPipeline(
            CapturePipelineSnapshot(3, 1, 1, 0, 0, 0, 1, 1),
        )
        fixture.transport.disconnect(LiveLinkDisconnectReason.NETWORK)
        fixture.transport.ready("session-two", "lease-two")
        fixture.frames.last().emitTiming(
            CaptureTimingEvent(
                analyzedMonotonicTimestampNanos = 3_000_000_000L,
                emittedMonotonicTimestampNanos = null,
                requestToImageLatencyNanos = 500L,
                imageAcquisitionDurationNanos = 600L,
                processorDurationNanos = 700L,
                listenerPathDurationNanos = 800L,
            ),
        )
        fixture.frames.last().emitPipeline(
            CapturePipelineSnapshot(4, 2, 0, 1, 1, 1, 0, 1),
        )

        val metrics = fixture.controller.snapshot().cameraSourceTiming
        assertEquals(2L, metrics.requestToImage.samples)
        assertEquals(100L, metrics.requestToImage.p50Nanos)
        assertEquals(500L, metrics.requestToImage.p95Nanos)
        assertEquals(2L, metrics.gateAndResizeProcessor.samples)
        assertEquals(7L, metrics.pipeline.requestsSubmitted)
        assertEquals(3L, metrics.pipeline.opportunitiesBackpressured)
        assertEquals(1L, metrics.pipeline.requestsSuperseded)
        assertEquals(1L, metrics.pipeline.imagesWithoutExactRequestMatch)
        assertEquals(1L, metrics.pipeline.captureFailures)
        assertEquals(1L, metrics.pipeline.lateCallbacks)
        assertEquals(0, metrics.pipeline.outstandingRequests)
        assertEquals(1, metrics.pipeline.maximumOutstandingRequests)
    }

    private class Fixture(
        runDurationMillis: Long = LiveLinkCaptureController.DEFAULT_RUN_DURATION_MILLIS,
        maximumDisconnects: Int = LiveLinkCaptureController.DEFAULT_MAXIMUM_DISCONNECTS,
        beforeProducerStart: (LiveLinkSession) -> Boolean = { true },
        onFrameFactory: () -> Unit = {},
        onPoseFactory: () -> Unit = {},
        microphonePermissionAvailable: Boolean = false,
        beforeMicrophoneStart: () -> Boolean = { true },
        onAudioFactory: () -> Unit = {},
        sensorSpool: RokidSensorSpool? = null,
        maximumCameraRestartAttempts: Int = LiveLinkCaptureController.DEFAULT_MAXIMUM_CAMERA_RESTART_ATTEMPTS,
    ) {
        val clock = MutableClock(1_000_000_000L)
        val transport = FakeTransport()
        val frameController = FrameSourceStateController()
        val frames = mutableListOf<FakeFrameSource>()
        val poses = mutableListOf<FakePoseSource>()
        val audioSources = mutableListOf<FakeAudioSource>()
        val microphoneStates = mutableListOf<LiveMicrophoneCaptureState>()
        val negotiatedLeases = mutableListOf<NegotiatedLiveLease>()
        val statuses = mutableListOf<LiveLinkCaptureSnapshot>()
        val terminals = mutableListOf<LiveLinkCaptureSnapshot>()
        val controller = LiveLinkCaptureController(
            clock = clock,
            frameSources = frameController,
            transport = transport,
            sensorSpool = sensorSpool,
            frameSourceFactory = { lease, sequence ->
                onFrameFactory()
                negotiatedLeases += lease
                FakeFrameSource(sequence).also(frames::add)
            },
            poseSourceFactory = {
                onPoseFactory()
                FakePoseSource().also(poses::add)
            },
            audioSourceFactory = {
                onAudioFactory()
                FakeAudioSource().also(audioSources::add)
            },
            microphonePermissionAvailable = { microphonePermissionAvailable },
            beforeProducerStart = beforeProducerStart,
            beforeMicrophoneStart = beforeMicrophoneStart,
            runDurationMillis = runDurationMillis,
            maximumDisconnects = maximumDisconnects,
            maximumCameraRestartAttempts = maximumCameraRestartAttempts,
            cameraRestartDelayMillis = 0L,
            onStatus = statuses::add,
            onMicrophoneState = microphoneStates::add,
            onTerminal = terminals::add,
        )
    }

    private class FakeSpool : RokidSensorSpool {
        val camera = mutableListOf<FramePayload>()
        val imu = mutableListOf<ImuTransmissionBatch>()
        val microphone = mutableListOf<PcmAudioChunk>()

        override fun storeCamera(lease: ActiveStreamLease, frame: FramePayload): Boolean =
            true.also { camera += frame }

        override fun storeImu(lease: ActiveStreamLease, batch: ImuTransmissionBatch): Boolean =
            true.also { imu += batch }

        override fun storeMicrophone(lease: ActiveStreamLease, chunk: PcmAudioChunk): Boolean =
            true.also { microphone += chunk }
    }

    private class FakeTransport : RokidLiveTransport {
        var observer: RokidLiveLinkObserver? = null
        var acceptCamera = true
        var acceptImu = true
        var closeCount = 0
        var evidence = LiveLinkCloseEvidence()
        var completeCloseImmediately = true
        var gestureStarts = 0
        var gestureStops = 0
        val nodeGestures = mutableListOf<Pair<RokidGestureOperation, Long>>()
        private var closeCompletion: ((LiveLinkCloseEvidence) -> Unit)? = null
        val camera = mutableListOf<List<SensorStreamEnvelope>>()
        val imu = mutableListOf<SensorStreamEnvelope>()
        val microphone = mutableListOf<SensorStreamEnvelope>()
        val cameraGateTelemetry = mutableListOf<LiveCameraGateTelemetry>()

        override fun start(observer: RokidLiveLinkObserver) {
            check(this.observer == null)
            this.observer = observer
        }

        override fun offerCameraFrame(chunks: List<SensorStreamEnvelope>): Boolean {
            if (acceptCamera) camera += chunks
            return acceptCamera
        }

        override fun offerImu(batch: SensorStreamEnvelope): Boolean {
            if (acceptImu) imu += batch
            return acceptImu
        }

        override fun offerMicrophone(chunk: SensorStreamEnvelope): Boolean {
            microphone += chunk
            return true
        }

        override fun updateCameraGateTelemetry(snapshot: LiveCameraGateTelemetry) {
            cameraGateTelemetry += snapshot
        }

        override fun requestMicrophoneFromUserGesture(): MicrophoneGestureDispatch {
            gestureStarts += 1
            return MicrophoneGestureDispatch.QUEUED
        }

        override fun stopMicrophoneFromUserGesture(): MicrophoneGestureDispatch {
            gestureStops += 1
            return MicrophoneGestureDispatch.QUEUED
        }

        override fun requestRokidGesture(
            operation: RokidGestureOperation,
            observedMonotonicNs: Long,
        ): RokidGestureDispatch {
            nodeGestures += operation to observedMonotonicNs
            return RokidGestureDispatch.QUEUED
        }

        override fun closeAsync(onClosed: (LiveLinkCloseEvidence) -> Unit) {
            closeCount += 1
            if (completeCloseImmediately) {
                onClosed(evidence)
            } else {
                check(closeCompletion == null)
                closeCompletion = onClosed
            }
        }

        override fun closeEvidence(): LiveLinkCloseEvidence = evidence

        override fun close() = closeAsync {}

        fun completeClose(callbackCount: Int = 1) {
            require(callbackCount > 0)
            val callback = requireNotNull(closeCompletion)
            repeat(callbackCount) { callback.invoke(evidence) }
            closeCompletion = null
        }

        fun ready(
            sessionId: String = "session",
            leaseId: String = "lease",
            expiresAtNanos: Long = 61_000_000_000L,
            imuMaximumBatchDelayMs: Int = 20,
            imuMaximumSilenceMs: Int = 1_000,
        ) {
            observer!!.onSessionReady(
                LiveLinkSession(
                    LiveSessionBinding(sessionId, leaseId, ByteArray(LiveSessionBinding.CONNECTION_NONCE_BYTES) { 7 }),
                    clockEstimate = null,
                    lease = NegotiatedLiveLease(
                        expiresAtMonotonicNs = expiresAtNanos,
                        cameraRelaxedFps = 3,
                        cameraMotionFps = 5,
                        imuMaximumBatchDelayMs = imuMaximumBatchDelayMs,
                        imuMaximumSilenceMs = imuMaximumSilenceMs,
                    ),
                ),
            )
        }

        fun disconnect(reason: LiveLinkDisconnectReason) {
            observer!!.onDisconnected(reason)
        }

        fun diagnostic(code: LiveLinkDiagnosticCode) {
            observer!!.onDiagnostic(code)
        }

        fun gestureResult(result: MicrophoneGestureResult) {
            observer!!.onMicrophoneGestureResult(result)
        }

        fun requestMicrophone(
            sessionId: String = "session",
            leaseId: String = "lease",
            durationMillis: Int = 10_000,
        ): Boolean {
            val authorization = MicrophoneLeaseAuthorization(
                sessionId,
                leaseId,
                durationMillis,
                11_000_000_000L,
            )
            val accepted = observer!!.mayGrantMicrophoneLease(authorization)
            if (accepted) observer!!.onMicrophoneLeaseGranted(authorization)
            return accepted
        }
    }

    private class FakeFrameSource(
        private val sequence: MonotonicFrameSequence,
    ) : FrameSource {
        private var listener: FrameSource.Listener? = null
        private var lastListener: FrameSource.Listener? = null
        override var isRunning = false
            private set
        var onStop: () -> Unit = {}

        override fun start(listener: FrameSource.Listener) {
            check(!isRunning)
            isRunning = true
            this.listener = listener
            lastListener = listener
        }

        fun emit(frame: FramePayload) {
            check(isRunning)
            listener!!.onFrame(frame)
        }

        fun emitGate(event: CaptureGateEvent) {
            check(isRunning)
            listener!!.onCaptureGate(event)
        }

        fun nextFrameId(): Long = sequence.nextId()

        fun emitLate(frame: FramePayload) {
            lastListener!!.onFrame(frame)
        }

        fun emitTiming(event: CaptureTimingEvent) {
            check(isRunning)
            listener!!.onCaptureTiming(event)
        }

        fun emitPipeline(snapshot: CapturePipelineSnapshot) {
            check(isRunning)
            listener!!.onCapturePipelineSnapshot(snapshot)
        }

        fun emitRecoverableError(message: String) {
            check(isRunning)
            listener!!.onRecoverableError(message)
        }

        fun emitRecoverableError(message: String, diagnostic: CameraSourceDiagnostic) {
            check(isRunning)
            listener!!.onRecoverableError(message, diagnostic)
        }

        fun emitLateError(message: String) {
            lastListener!!.onError(message)
        }

        fun emitError(message: String) {
            check(isRunning)
            listener!!.onError(message)
        }

        override fun stop() {
            isRunning = false
            listener = null
            onStop()
        }
    }

    private fun gateEvent(
        emitted: Boolean,
        targetFramesPerSecond: Double,
        dropReason: FrameDropReason? = null,
    ) = CaptureGateEvent(
        inputDimensions = PixelDimensions(648, 648),
        outputDimensions = PixelDimensions(640, 640),
        emitted = emitted,
        dropReason = dropReason,
        targetFramesPerSecond = targetFramesPerSecond,
        meanLuma = 96.0,
        darkFraction = 0.1,
        laplacianVariance = 80.0,
        motionScore = if (targetFramesPerSecond >= 5.0) 0.2 else 0.01,
    )

    private class FakePoseSource : PoseSource {
        private var listener: ((ImuSample) -> Unit)? = null
        override var isRunning = false
            private set

        override fun start(listener: (ImuSample) -> Unit) {
            check(!isRunning)
            isRunning = true
            this.listener = listener
        }

        fun emit(sample: ImuSample) {
            check(isRunning)
            listener!!.invoke(sample)
        }

        override fun stop() {
            isRunning = false
            listener = null
        }
    }

    private class FakeAudioSource : AudioInputSource {
        private var listener: AudioInputSource.Listener? = null
        override var isRunning = false
            private set

        override fun start(listener: AudioInputSource.Listener) {
            this.listener = listener
            isRunning = true
        }

        fun emit(chunk: PcmAudioChunk) {
            check(isRunning)
            listener!!.onAudioChunk(chunk)
        }

        override fun stop() {
            isRunning = false
            listener = null
        }
    }

    private class MutableClock(var value: Long) : MonotonicClock {
        override fun nowNanos(): Long = value
    }

    companion object {
        private fun frame(sessionId: String = "camera-session", frameId: Long = 1L): FramePayload = buildJpegFrame(
            requestId = "request-$frameId",
            sessionId = sessionId,
            streamId = "camera",
            frameId = frameId,
            timestampNanos = 1_000_000_000L + frameId,
            wallTimeMillis = 1_000L,
            width = 16,
            height = 16,
            bytes = ByteArray(1_200) { (it % 251).toByte() },
            synthetic = true,
        )

        private fun sample(sequence: Long, timestamp: Long): ImuSample = ImuSample(
            pose = Pose.newBuilder()
                .setReferenceFrame(CoordinateFrame.COORDINATE_FRAME_HEAD)
                .setRotation(Quaternion.newBuilder().setW(1.0))
                .setMonotonicTimestampNs(timestamp)
                .build(),
            angularVelocityRadiansPerSecond = Vector3.getDefaultInstance(),
            linearAccelerationMetersPerSecondSquared = Vector3.getDefaultInstance(),
            sequenceId = sequence,
            orientationAccuracy = 3,
            angularVelocityTimestampNanos = timestamp,
            linearAccelerationTimestampNanos = timestamp,
        )
    }
}
