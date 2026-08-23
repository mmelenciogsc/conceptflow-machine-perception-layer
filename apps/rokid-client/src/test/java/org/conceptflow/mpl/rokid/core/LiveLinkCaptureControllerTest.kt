// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.conceptflow.mpl.transport.LiveLinkDisconnectReason
import org.conceptflow.mpl.transport.LiveLinkCloseEvidence
import org.conceptflow.mpl.transport.LiveLinkDiagnosticCode
import org.conceptflow.mpl.transport.LiveLinkSession
import org.conceptflow.mpl.transport.LiveSessionBinding
import org.conceptflow.mpl.transport.NegotiatedLiveLease
import org.conceptflow.mpl.transport.RokidLiveLinkObserver
import org.conceptflow.mpl.v1.CoordinateFrame
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.Pose
import org.conceptflow.mpl.v1.Quaternion
import org.conceptflow.mpl.v1.SensorStreamEnvelope
import org.conceptflow.mpl.v1.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLinkCaptureControllerTest {
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
    fun retryAndTimeBoundsTerminateExactlyOnce() {
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
        timeFixture.controller.stop()

        assertEquals(LiveLinkCaptureStopReason.TIME_LIMIT_REACHED, timeFixture.controller.snapshot().stopReason)
        assertEquals(1, timeFixture.transport.closeCount)
        assertEquals(1, timeFixture.terminals.size)
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
        fixture.clock.value += 1_000_000_000L

        fixture.controller.poll()

        assertEquals(LiveLinkCaptureState.STOPPED, fixture.controller.snapshot().state)
        assertEquals(1, fixture.transport.closeCount)
        assertTrue(fixture.terminals.isEmpty())

        fixture.transport.completeClose(callbackCount = 2)

        assertEquals(1, fixture.terminals.size)
        assertTrue(fixture.terminals.single().closeEvidence.clientAcknowledgementReceived)
    }

    private class Fixture(
        runDurationMillis: Long = LiveLinkCaptureController.DEFAULT_RUN_DURATION_MILLIS,
        maximumDisconnects: Int = LiveLinkCaptureController.DEFAULT_MAXIMUM_DISCONNECTS,
    ) {
        val clock = MutableClock(1_000_000_000L)
        val transport = FakeTransport()
        val frameController = FrameSourceStateController()
        val frames = mutableListOf<FakeFrameSource>()
        val poses = mutableListOf<FakePoseSource>()
        val negotiatedLeases = mutableListOf<NegotiatedLiveLease>()
        val terminals = mutableListOf<LiveLinkCaptureSnapshot>()
        val controller = LiveLinkCaptureController(
            clock = clock,
            frameSources = frameController,
            transport = transport,
            frameSourceFactory = { lease ->
                negotiatedLeases += lease
                FakeFrameSource().also(frames::add)
            },
            poseSourceFactory = { FakePoseSource().also(poses::add) },
            runDurationMillis = runDurationMillis,
            maximumDisconnects = maximumDisconnects,
            onTerminal = terminals::add,
        )
    }

    private class FakeTransport : RokidLiveTransport {
        var observer: RokidLiveLinkObserver? = null
        var acceptCamera = true
        var acceptImu = true
        var closeCount = 0
        var evidence = LiveLinkCloseEvidence()
        var completeCloseImmediately = true
        private var closeCompletion: ((LiveLinkCloseEvidence) -> Unit)? = null
        val camera = mutableListOf<List<SensorStreamEnvelope>>()
        val imu = mutableListOf<SensorStreamEnvelope>()

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
    }

    private class FakeFrameSource : FrameSource {
        private var listener: FrameSource.Listener? = null
        private var lastListener: FrameSource.Listener? = null
        override var isRunning = false
            private set

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

        fun emitLate(frame: FramePayload) {
            lastListener!!.onFrame(frame)
        }

        override fun stop() {
            isRunning = false
            listener = null
        }
    }

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
