// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import com.google.protobuf.ByteString
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.conceptflow.mpl.v1.CameraFrameChunk
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.ImuBatch
import org.conceptflow.mpl.v1.LiveLaneOpenRequest
import org.conceptflow.mpl.v1.LiveLaneOpenResponse
import org.conceptflow.mpl.v1.LiveLinkControl
import org.conceptflow.mpl.v1.LiveLinkEnvelope
import org.conceptflow.mpl.v1.LiveTransportLane
import org.conceptflow.mpl.v1.SensorStreamEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveConnectionStateTest {
    @Test
    fun `camera data remains blocked until a one-use ticket admits the lane`() {
        val state = LiveConnectionState()
        val binding = LiveSessionBinding("session", "lease", ByteArray(32) { 4 })
        state.reconnect(binding, key(4), nowNs = 1_000)

        val unauthenticated = assertThrows(LaneProtocolException::class.java) {
            state.accept(cameraEnvelope(binding, sequence = 1, frameId = 1))
        }
        assertEquals(LaneProtocolFailure.CAMERA_LANE_UNAUTHENTICATED, unauthenticated.failure)

        val ticket = state.issueCameraTicket(1_000, 100)
        val open = LiveLinkEnvelope.newBuilder()
            .setSessionId(binding.sessionId)
            .setLeaseId(binding.leaseId)
            .setLane(LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA)
            .setLaneSequenceId(1)
            .setControl(
                LiveLinkControl.newBuilder().setLaneOpenRequest(
                    LiveLaneOpenRequest.newBuilder()
                        .setLane(LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA)
                        .setSessionId(binding.sessionId)
                        .setLeaseId(binding.leaseId)
                        .setConnectionNonce(ByteString.copyFrom(binding.connectionNonce))
                        .setLaneTicket(ByteString.copyFrom(ticket)),
                ),
            )
            .build()
        state.acceptCameraLaneOpen(open, nowNs = 1_010)
        state.accept(cameraEnvelope(binding, sequence = 2, frameId = 1))

        assertThrows(CameraTicketException::class.java) {
            state.acceptCameraLaneOpen(open, nowNs = 1_011)
        }
    }

    @Test
    fun `reconnect resets replay ticket clock and liveness state`() {
        val state = LiveConnectionState(
            clockSynchronizer = MinRttClockSynchronizer(requiredSamples = 1, maxSamples = 1),
        )
        val oldBinding = LiveSessionBinding("old-session", "old-lease", ByteArray(32) { 1 })
        val newBinding = LiveSessionBinding("new-session", "new-lease", ByteArray(32) { 2 })
        state.reconnect(oldBinding, key(1), nowNs = 1_000)
        state.accept(imuEnvelope(oldBinding, 1))
        val oldTicket = state.issueCameraTicket(1_000, 100)
        state.consumeCameraTicket(oldTicket, 1_010)
        state.beginClockRound()
        state.addClockProbe(FourTimestampClockProbe(1, 1_000, 1_510, 1_515, 1_025))
        state.commitClockRound()
        assertTrue(state.currentClockEstimate() != null)

        state.reconnect(newBinding, key(2), nowNs = 2_000)

        assertEquals(2, state.generation())
        assertNull(state.currentClockEstimate())
        assertEquals(LivenessStatus.HEALTHY, state.pollLiveness(2_000))
        state.accept(imuEnvelope(newBinding, 1))
        assertThrows(LaneProtocolException::class.java) { state.accept(imuEnvelope(oldBinding, 2)) }
        assertThrows(CameraTicketException::class.java) { state.consumeCameraTicket(oldTicket, 2_001) }
        assertEquals(1, state.metrics.snapshot().reconnects)

        state.disconnect()
        assertFalse(state.isConnected())
    }

    @Test
    fun `metrics expose aggregate values only`() {
        val metrics = SanitizedTransportMetrics()
        metrics.recordSent(LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA, 128)
        metrics.recordDropped(LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA, 2)
        metrics.recordQueueDepth(LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA, 4)
        metrics.recordQueueDepth(LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA, 2)

        val snapshot = metrics.snapshot()

        assertEquals(1, snapshot.camera.sentMessages)
        assertEquals(128, snapshot.camera.sentBytes)
        assertEquals(2, snapshot.camera.droppedMessages)
        assertEquals(4, snapshot.camera.queueHighWater)
        assertFalse(snapshot.toString().contains("session"))
    }

    @Test
    fun `cross-lane readers cannot commit timestamps out of state-lock order`() {
        val state = LiveConnectionState()
        val binding = LiveSessionBinding("session", "lease", ByteArray(32) { 5 })
        state.reconnectWithoutTicketAuthority(binding, nowNs = 0L)
        state.acceptInboundAtomic(cameraOpenResponse(binding, sequence = 1L), MonotonicTimeSource { 1L })
        val firstClockEntered = CountDownLatch(1)
        val releaseFirstClock = CountDownLatch(1)
        val clockCalls = AtomicInteger()
        val clock = MonotonicTimeSource {
            when (clockCalls.incrementAndGet()) {
                1 -> {
                    firstClockEntered.countDown()
                    check(releaseFirstClock.await(2, TimeUnit.SECONDS))
                    100L
                }
                2 -> 200L
                else -> error("unexpected clock sample")
            }
        }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val camera = workers.submit<Long> {
                state.acceptInboundAtomic(cameraEnvelope(binding, sequence = 2L, frameId = 1L), clock)
            }
            assertTrue(firstClockEntered.await(2, TimeUnit.SECONDS))
            val realtime = workers.submit<Long> {
                state.acceptInboundAtomic(imuEnvelope(binding, sequence = 1L), clock)
            }
            releaseFirstClock.countDown()

            assertEquals(100L, camera.get(2, TimeUnit.SECONDS))
            assertEquals(200L, realtime.get(2, TimeUnit.SECONDS))
            assertEquals(LivenessStatus.HEALTHY, state.pollLiveness(200L))
        } finally {
            releaseFirstClock.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun `keepalive polling reserves nonce before camera lane can advance liveness`() {
        val state = LiveConnectionState(
            liveness = LivenessMonitor(keepaliveIntervalNs = 100L, missedIntervalsBeforeTimeout = 5),
        )
        val binding = LiveSessionBinding("session", "lease", ByteArray(32) { 6 })
        state.reconnectWithoutTicketAuthority(binding, nowNs = 0L)
        state.acceptInboundAtomic(cameraOpenResponse(binding, sequence = 1L), MonotonicTimeSource { 1L })
        val pollClockEntered = CountDownLatch(1)
        val releasePollClock = CountDownLatch(1)
        val cameraStarted = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val poll = workers.submit<LiveLivenessDecision> {
                state.pollLivenessAtomic(
                    MonotonicTimeSource {
                        pollClockEntered.countDown()
                        check(releasePollClock.await(2, TimeUnit.SECONDS))
                        100L
                    },
                    reserveKeepalive = true,
                )
            }
            assertTrue(pollClockEntered.await(2, TimeUnit.SECONDS))
            val camera = workers.submit<Long> {
                cameraStarted.countDown()
                state.acceptInboundAtomic(
                    cameraEnvelope(binding, sequence = 2L, frameId = 1L),
                    MonotonicTimeSource { 200L },
                )
            }
            assertTrue(cameraStarted.await(2, TimeUnit.SECONDS))
            assertFalse(camera.isDone)
            releasePollClock.countDown()

            val decision = poll.get(2, TimeUnit.SECONDS)
            assertEquals(LivenessStatus.KEEPALIVE_DUE, decision.status)
            assertEquals(100L, decision.sampledAtNs)
            assertEquals(1L, decision.keepaliveNonce)
            assertEquals(200L, camera.get(2, TimeUnit.SECONDS))
            assertEquals(LivenessStatus.HEALTHY, state.pollLiveness(200L))
        } finally {
            releasePollClock.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun `keepalive response clears nonce before camera lane can advance liveness`() {
        val state = LiveConnectionState(
            liveness = LivenessMonitor(keepaliveIntervalNs = 100L, missedIntervalsBeforeTimeout = 5),
        )
        val binding = LiveSessionBinding("session", "lease", ByteArray(32) { 7 })
        state.reconnectWithoutTicketAuthority(binding, nowNs = 0L)
        state.acceptInboundAtomic(cameraOpenResponse(binding, sequence = 1L), MonotonicTimeSource { 1L })
        val firstDecision = state.pollLivenessAtomic(
            MonotonicTimeSource { 100L },
            reserveKeepalive = true,
        )
        val firstNonce = checkNotNull(firstDecision.keepaliveNonce)
        val responseClockEntered = CountDownLatch(1)
        val releaseResponseClock = CountDownLatch(1)
        val cameraStarted = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val response = workers.submit<Long> {
                state.acceptInboundAtomic(
                    keepaliveResponseEnvelope(binding, sequence = 1L, nonce = firstNonce),
                    MonotonicTimeSource {
                        responseClockEntered.countDown()
                        check(releaseResponseClock.await(2, TimeUnit.SECONDS))
                        200L
                    },
                )
            }
            assertTrue(responseClockEntered.await(2, TimeUnit.SECONDS))
            val camera = workers.submit<Long> {
                cameraStarted.countDown()
                state.acceptInboundAtomic(
                    cameraEnvelope(binding, sequence = 2L, frameId = 1L),
                    MonotonicTimeSource { 300L },
                )
            }
            assertTrue(cameraStarted.await(2, TimeUnit.SECONDS))
            assertFalse(camera.isDone)
            releaseResponseClock.countDown()

            assertEquals(200L, response.get(2, TimeUnit.SECONDS))
            assertEquals(300L, camera.get(2, TimeUnit.SECONDS))
            val nextDecision = state.pollLivenessAtomic(
                MonotonicTimeSource { 300L },
                reserveKeepalive = true,
            )
            assertEquals(LivenessStatus.KEEPALIVE_DUE, nextDecision.status)
            assertEquals(2L, nextDecision.keepaliveNonce)
        } finally {
            releaseResponseClock.countDown()
            workers.shutdownNow()
        }
    }

    private fun imuEnvelope(binding: LiveSessionBinding, sequence: Long): LiveLinkEnvelope {
        val sensor = SensorStreamEnvelope.newBuilder()
            .setSessionId(binding.sessionId)
            .setLeaseId(binding.leaseId)
            .setImuBatch(ImuBatch.newBuilder().setLeaseId(binding.leaseId).setBatchId(sequence))
        return LiveLinkEnvelope.newBuilder()
            .setSessionId(binding.sessionId)
            .setLeaseId(binding.leaseId)
            .setLane(LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL)
            .setLaneSequenceId(sequence)
            .setSensor(sensor)
            .build()
    }

    private fun cameraEnvelope(
        binding: LiveSessionBinding,
        sequence: Long,
        frameId: Long,
    ): LiveLinkEnvelope {
        val sensor = SensorStreamEnvelope.newBuilder()
            .setSessionId(binding.sessionId)
            .setLeaseId(binding.leaseId)
            .setCameraChunk(
                CameraFrameChunk.newBuilder()
                    .setFrameId(frameId)
                    .setChunkIndex(0)
                    .setChunkCount(1)
                    .setTotalPayloadBytes(1)
                    .setFrameMetadata(FramePayload.newBuilder().setSessionId(binding.sessionId).setFrameId(frameId)),
            )
        return LiveLinkEnvelope.newBuilder()
            .setSessionId(binding.sessionId)
            .setLeaseId(binding.leaseId)
            .setLane(LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA)
            .setLaneSequenceId(sequence)
            .setSensor(sensor)
            .build()
    }

    private fun cameraOpenResponse(binding: LiveSessionBinding, sequence: Long): LiveLinkEnvelope =
        LiveLinkEnvelope.newBuilder()
            .setSessionId(binding.sessionId)
            .setLeaseId(binding.leaseId)
            .setLane(LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA)
            .setLaneSequenceId(sequence)
            .setControl(
                LiveLinkControl.newBuilder().setLaneOpenResponse(
                    LiveLaneOpenResponse.newBuilder()
                        .setLane(LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA)
                        .setAccepted(true),
                ),
            )
            .build()

    private fun keepaliveResponseEnvelope(
        binding: LiveSessionBinding,
        sequence: Long,
        nonce: Long,
    ): LiveLinkEnvelope = LiveLinkEnvelope.newBuilder()
        .setSessionId(binding.sessionId)
        .setLeaseId(binding.leaseId)
        .setLane(LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL)
        .setLaneSequenceId(sequence)
        .setControl(LiveControlMessages.keepalive(nonce, sentNs = 150L, response = true))
        .build()

    private fun key(value: Int) = SecretKeySpec(ByteArray(32) { value.toByte() }, "HmacSHA256")
}
