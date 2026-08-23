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
import org.junit.Test

class LiveSensorTimestampNormalizerTest {
    @Test
    fun `host normalization retains raw source time and applies minimum RTT estimate`() {
        val state = LiveConnectionState()
        state.reconnectWithoutTicketAuthority(
            LiveSessionBinding("session", "lease", ByteArray(32) { 1 }),
            nowNs = 0,
        )
        state.beginClockRound()
        repeat(8) { index ->
            state.addClockProbe(
                FourTimestampClockProbe(
                    probeId = index + 1L,
                    initiatorSendNs = 1_000 + index * 100L,
                    responderReceiveNs = 1_090 + index * 100L,
                    responderSendNs = 1_100 + index * 100L,
                    initiatorReceiveNs = 1_030 + index * 100L,
                ),
            )
        }
        state.commitClockRound()
        val sensor = SensorStreamEnvelope.newBuilder()
            .setSessionId("session")
            .setLeaseId("lease")
            .setImuBatch(
                ImuBatch.newBuilder()
                    .setLeaseId("lease")
                    .setBatchId(1)
                    .setCreatedMonotonicTimestampNs(2_050)
                    .addSamples(
                        ImuReading.newBuilder()
                            .setSequenceId(1)
                            .setPose(Pose.newBuilder().setMonotonicTimestampNs(2_100))
                            .setAngularVelocityMonotonicTimestampNs(2_110)
                            .setLinearAccelerationMonotonicTimestampNs(2_120),
                    ),
            ).build()
        val envelope = LiveLinkEnvelope.newBuilder()
            .setSessionId("session")
            .setLeaseId("lease")
            .setLane(LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL)
            .setLaneSequenceId(1)
            .setSentMonotonicTimestampNs(2_000)
            .setSensor(sensor)
            .build()

        val delivery = LiveSensorTimestampNormalizer.normalize(envelope, state, receiveNs = 2_200)

        assertEquals(2_000L, delivery.normalizedRemoteSend.rawRemoteNs)
        assertEquals(1_920L, delivery.normalizedRemoteSend.hostMonotonicNs)
        assertEquals(1_970L, delivery.normalizedImuBatchCreated!!.hostMonotonicNs)
        assertEquals(2_020L, delivery.normalizedImuSamples.single().poseTimestamp.hostMonotonicNs)
        assertEquals(2_030L, delivery.normalizedImuSamples.single().angularVelocityTimestamp.hostMonotonicNs)
        assertEquals(2_040L, delivery.normalizedImuSamples.single().linearAccelerationTimestamp.hostMonotonicNs)
        assertEquals(10L, delivery.normalizedRemoteSend.uncertaintyNs)
        assertEquals(1L, delivery.normalizedRemoteSend.clockEvidence.estimateRevision)
        assertEquals(0L, delivery.normalizedRemoteSend.monotonicAdjustmentNs)
    }

    @Test
    fun `incomplete first IMU sample fails with content-free site classification`() {
        val state = synchronizedState()
        val envelope = imuEnvelope(angularTimestampNs = 0L, linearTimestampNs = 2_120L)

        val error = assertThrows(LiveSensorValidationException::class.java) {
            LiveSensorTimestampNormalizer.normalize(envelope, state, receiveNs = 2_200L)
        }

        assertEquals(
            LiveLinkDiagnosticCode.SENSOR_IMU_SAMPLE_TIMESTAMP_REJECTED,
            error.diagnosticCode,
        )
        assertEquals(error.diagnosticCode.name, error.message)
        assertEquals(error.diagnosticCode, classifyDiagnostic(error))
        assertEquals(LiveLinkDisconnectReason.PROTOCOL, classifyDisconnect(error))
    }

    private fun synchronizedState(): LiveConnectionState = LiveConnectionState().also { state ->
        state.reconnectWithoutTicketAuthority(
            LiveSessionBinding("session", "lease", ByteArray(32) { 1 }),
            nowNs = 0,
        )
        state.beginClockRound()
        repeat(8) { index ->
            state.addClockProbe(
                FourTimestampClockProbe(
                    probeId = index + 1L,
                    initiatorSendNs = 1_000 + index * 100L,
                    responderReceiveNs = 1_090 + index * 100L,
                    responderSendNs = 1_100 + index * 100L,
                    initiatorReceiveNs = 1_030 + index * 100L,
                ),
            )
        }
        state.commitClockRound()
    }

    private fun imuEnvelope(angularTimestampNs: Long, linearTimestampNs: Long): LiveLinkEnvelope {
        val sensor = SensorStreamEnvelope.newBuilder()
            .setSessionId("session")
            .setLeaseId("lease")
            .setImuBatch(
                ImuBatch.newBuilder()
                    .setLeaseId("lease")
                    .setBatchId(1)
                    .setCreatedMonotonicTimestampNs(2_050)
                    .addSamples(
                        ImuReading.newBuilder()
                            .setSequenceId(1)
                            .setPose(Pose.newBuilder().setMonotonicTimestampNs(2_100))
                            .setAngularVelocityMonotonicTimestampNs(angularTimestampNs)
                            .setLinearAccelerationMonotonicTimestampNs(linearTimestampNs),
                    ),
            ).build()
        return LiveLinkEnvelope.newBuilder()
            .setSessionId("session")
            .setLeaseId("lease")
            .setLane(LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL)
            .setLaneSequenceId(1)
            .setSentMonotonicTimestampNs(2_000)
            .setSensor(sensor)
            .build()
    }
}
