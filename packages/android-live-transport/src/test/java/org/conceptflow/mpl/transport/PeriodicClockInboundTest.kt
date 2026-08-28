// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import org.conceptflow.mpl.v1.ImuBatch
import org.conceptflow.mpl.v1.ImuReading
import org.conceptflow.mpl.v1.LiveLinkEnvelope
import org.conceptflow.mpl.v1.LiveTransportLane
import org.conceptflow.mpl.v1.Pose
import org.conceptflow.mpl.v1.SensorStreamEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodicClockInboundTest {
    private val binding = LiveSessionBinding("session", "lease", ByteArray(32) { 3 })

    @Test
    fun `outstanding keepalive response is legal while awaiting periodic clock response`() {
        val keepalive = envelope(LiveControlMessages.keepalive(7L, 900L, response = true))

        assertEquals(
            PeriodicClockInboundKind.KEEPALIVE_RESPONSE,
            classifyPeriodicClockInbound(keepalive, binding, expectedProbeId = 10_000L, expectedInitiatorSendNs = 1_000L),
        )
    }

    @Test
    fun `expected clock response remains correlated after an interleaved keepalive`() {
        val request = LiveControlMessages.clockRequest(10_000L, 1_000L).clockSyncRequest
        val response = envelope(LiveControlMessages.clockResponse(request, 1_010L, 1_020L))

        assertEquals(
            PeriodicClockInboundKind.EXPECTED_CLOCK_RESPONSE,
            classifyPeriodicClockInbound(response, binding, expectedProbeId = 10_000L, expectedInitiatorSendNs = 1_000L),
        )
    }

    @Test
    fun `unsolicited keepalive request and mismatched clock response still fail closed`() {
        val request = envelope(LiveControlMessages.keepalive(7L, 900L, response = false))
        assertThrows(LaneProtocolException::class.java) {
            classifyPeriodicClockInbound(request, binding, 10_000L, 1_000L)
        }

        val wrongProbe = LiveControlMessages.clockRequest(10_001L, 1_000L).clockSyncRequest
        val response = envelope(LiveControlMessages.clockResponse(wrongProbe, 1_010L, 1_020L))
        assertThrows(LaneProtocolException::class.java) {
            classifyPeriodicClockInbound(response, binding, 10_000L, 1_000L)
        }
    }

    @Test
    fun `exact timed out clock response is accepted once without weakening unsolicited rejection`() {
        val window = LatePeriodicClockResponseWindow(maximumEntries = 2)
        window.recordTimedOut(probeId = 10_000L, initiatorSendNs = 1_000L)
        val request = LiveControlMessages.clockRequest(10_000L, 1_000L).clockSyncRequest
        val exact = LiveControlMessages.clockResponse(request, receiveNs = 1_010L, sendNs = 1_020L)

        assertTrue(window.accept(exact))
        assertEquals(false, window.accept(exact))

        window.recordTimedOut(probeId = 10_001L, initiatorSendNs = 2_000L)
        val mismatchedRequest = LiveControlMessages.clockRequest(10_001L, 2_001L).clockSyncRequest
        assertEquals(
            false,
            window.accept(LiveControlMessages.clockResponse(mismatchedRequest, 2_010L, 2_020L)),
        )
    }

    @Test
    fun `late clock response window is bounded and rejects invalid responder ordering`() {
        val window = LatePeriodicClockResponseWindow(maximumEntries = 2)
        window.recordTimedOut(probeId = 10_000L, initiatorSendNs = 1_000L)
        window.recordTimedOut(probeId = 10_001L, initiatorSendNs = 2_000L)
        window.recordTimedOut(probeId = 10_002L, initiatorSendNs = 3_000L)

        fun response(probeId: Long, sendNs: Long, receiveNs: Long, responderSendNs: Long) =
            LiveControlMessages.clockResponse(
                LiveControlMessages.clockRequest(probeId, sendNs).clockSyncRequest,
                receiveNs,
                responderSendNs,
            )

        assertEquals(false, window.accept(response(10_000L, 1_000L, 1_010L, 1_020L)))
        assertEquals(false, window.accept(response(10_001L, 2_000L, 2_020L, 2_010L)))
        assertTrue(window.accept(response(10_002L, 3_000L, 3_010L, 3_020L)))
    }

    @Test
    fun `many clock rounds preserve sequence and correlation through dense legal interleavings`() {
        val state = LiveConnectionState(
            liveness = LivenessMonitor(
                keepaliveIntervalNs = 1L,
                missedIntervalsBeforeTimeout = Int.MAX_VALUE,
            ),
        )
        state.reconnectWithoutTicketAuthority(binding, nowNs = 0L)
        installInitialClockEstimate(state)
        var laneSequence = 1L
        var sampleSequence = 1L
        var batchId = 1L
        var remoteTimestamp = 10_000L
        var expectedProbeId = 10_000L

        repeat(24) { round ->
            state.beginClockRound()
            repeat(LiveControlMessages.CLOCK_PROBES) { probeIndex ->
                repeat(3) {
                    remoteTimestamp += 10L
                    val imu = imuEnvelope(laneSequence++, batchId++, sampleSequence++, remoteTimestamp)
                    acceptAndClassify(state, imu, expectedProbeId, remoteTimestamp + 1_000L)
                    val delivery = LiveSensorTimestampNormalizer.normalize(
                        imu,
                        state,
                        receiveNs = remoteTimestamp + 250L,
                    )
                    assertEquals(1, delivery.normalizedImuSamples.size)
                }
                if (probeIndex % 2 == 0) {
                    val keepaliveDecision = state.pollLivenessAtomic(
                        MonotonicTimeSource { remoteTimestamp + 300L },
                        reserveKeepalive = true,
                    )
                    assertEquals(LivenessStatus.KEEPALIVE_DUE, keepaliveDecision.status)
                    val keepaliveNonce = checkNotNull(keepaliveDecision.keepaliveNonce)
                    val keepalive = envelope(
                        LiveControlMessages.keepalive(
                            keepaliveNonce,
                            remoteTimestamp + 301L,
                            response = true,
                        ),
                        laneSequence++,
                    )
                    state.acceptInboundAtomic(
                        keepalive,
                        MonotonicTimeSource { remoteTimestamp + 310L },
                    )
                    assertEquals(
                        PeriodicClockInboundKind.KEEPALIVE_RESPONSE,
                        classifyPeriodicClockInbound(
                            keepalive,
                            binding,
                            expectedProbeId,
                            remoteTimestamp + 1_000L,
                        ),
                    )
                }
                val hostSend = remoteTimestamp + 1_000L
                val request = LiveControlMessages.clockRequest(expectedProbeId, hostSend).clockSyncRequest
                val clockResponse = envelope(
                    LiveControlMessages.clockResponse(request, hostSend + 100L, hostSend + 120L),
                    laneSequence++,
                )
                val hostReceive = hostSend + 220L
                state.acceptInboundAtomic(clockResponse, MonotonicTimeSource { hostReceive })
                assertEquals(
                    PeriodicClockInboundKind.EXPECTED_CLOCK_RESPONSE,
                    classifyPeriodicClockInbound(clockResponse, binding, expectedProbeId, hostSend),
                )
                state.addClockProbe(
                    FourTimestampClockProbe(
                        expectedProbeId,
                        hostSend,
                        hostSend + 100L,
                        hostSend + 120L,
                        hostReceive,
                    ),
                )
                expectedProbeId += 1L
                remoteTimestamp = hostReceive
            }
            val estimate = state.commitClockRound(remoteTimestamp + 1L)
            assertEquals(0L, estimate.offsetRemoteMinusHostNs)
            assertTrue(state.isConnected())
            // Ensure the next round starts after the prior liveness observation.
            remoteTimestamp += 1_000L + round
        }
    }

    private fun acceptAndClassify(
        state: LiveConnectionState,
        envelope: LiveLinkEnvelope,
        expectedProbeId: Long,
        expectedInitiatorSendNs: Long,
    ) {
        state.acceptInboundAtomic(
            envelope,
            MonotonicTimeSource { envelope.sentMonotonicTimestampNs + 250L },
        )
        assertEquals(
            PeriodicClockInboundKind.IMU_BATCH,
            classifyPeriodicClockInbound(envelope, binding, expectedProbeId, expectedInitiatorSendNs),
        )
    }

    private fun installInitialClockEstimate(state: LiveConnectionState) {
        state.beginClockRound()
        repeat(LiveControlMessages.CLOCK_PROBES) { index ->
            val hostSend = 1_000L + index * 100L
            state.addClockProbe(
                FourTimestampClockProbe(index + 1L, hostSend, hostSend + 100L, hostSend + 120L, hostSend + 220L),
            )
        }
        state.commitClockRound(2_000L)
    }

    private fun imuEnvelope(
        laneSequence: Long,
        batchId: Long,
        sampleSequence: Long,
        timestampNs: Long,
    ): LiveLinkEnvelope {
        val sensor = SensorStreamEnvelope.newBuilder()
            .setSessionId(binding.sessionId)
            .setLeaseId(binding.leaseId)
            .setImuBatch(
                ImuBatch.newBuilder()
                    .setLeaseId(binding.leaseId)
                    .setBatchId(batchId)
                    .setCreatedMonotonicTimestampNs(timestampNs)
                    .addSamples(
                        ImuReading.newBuilder()
                            .setSequenceId(sampleSequence)
                            .setPose(Pose.newBuilder().setMonotonicTimestampNs(timestampNs))
                            .setAngularVelocityMonotonicTimestampNs(timestampNs)
                            .setLinearAccelerationMonotonicTimestampNs(timestampNs),
                    ),
            ).build()
        return LiveLinkEnvelope.newBuilder()
            .setSessionId(binding.sessionId)
            .setLeaseId(binding.leaseId)
            .setLane(LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL)
            .setLaneSequenceId(laneSequence)
            .setSentMonotonicTimestampNs(timestampNs)
            .setSensor(sensor)
            .build()
    }

    private fun envelope(
        control: org.conceptflow.mpl.v1.LiveLinkControl,
        sequence: Long = 1L,
    ): LiveLinkEnvelope =
        LiveLinkEnvelope.newBuilder()
            .setSessionId(binding.sessionId)
            .setLeaseId(binding.leaseId)
            .setLane(LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL)
            .setLaneSequenceId(sequence)
            .setControl(control)
            .build()
}
