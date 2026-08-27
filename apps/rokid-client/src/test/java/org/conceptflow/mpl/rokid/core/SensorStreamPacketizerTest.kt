// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.conceptflow.mpl.v1.CoordinateFrame
import org.conceptflow.mpl.v1.Pose
import org.conceptflow.mpl.v1.Quaternion
import org.conceptflow.mpl.v1.SensorStreamEnvelope
import org.conceptflow.mpl.v1.SensorStreamKind
import org.conceptflow.mpl.v1.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorStreamPacketizerTest {
    @Test
    fun cameraPayloadIsBoundedlyChunkedAndRoundTripsThroughProtobuf() {
        val clock = FixedClock(100L)
        val packetizer = SensorStreamPacketizer(clock, StreamPacketLimits(cameraChunkBytes = 1_024))
        val jpeg = ByteArray(2_500) { (it % 251).toByte() }
        val frame = buildJpegFrame(
            "request", "camera-session", "camera", 7L, 90L, 1_000L, 50, 50, jpeg, true,
        )

        val packets = packetizer.camera(lease(), frame)

        assertEquals(3, packets.size)
        assertTrue(packets.first().cameraChunk.hasFrameMetadata())
        assertTrue(packets.first().cameraChunk.frameMetadata.frameData.isEmpty)
        assertFalse(packets[1].cameraChunk.hasFrameMetadata())
        assertTrue(packets.all { it.serializedSize <= 1_500 })
        val reparsed = packets.map { SensorStreamEnvelope.parseFrom(it.toByteArray()) }
        assertEquals(listOf(0L, 0L, 0L), reparsed.map { it.sequenceId })
        assertTrue(reparsed.all { it.leaseId == "lease" })
        assertTrue(reparsed.all { it.sessionId == "session" })
        assertEquals("session", reparsed.first().cameraChunk.frameMetadata.sessionId)
    }

    @Test
    fun imuBatchContainsAbsoluteStatesAndIsDeniedAfterLeaseExpiry() {
        val clock = MutableClock(100L)
        val packetizer = SensorStreamPacketizer(clock)
        val batch = ImuTransmissionBatch(1L, 90L, listOf(sample(1L, 80L)))

        val packet = packetizer.imu(lease(expiresAt = 200L), batch)!!
        assertEquals(1, packet.imuBatch.samplesCount)
        assertEquals(1.0, packet.imuBatch.samplesList.single().pose.rotation.w, 0.0)

        clock.value = 200L
        assertNull(packetizer.imu(lease(expiresAt = 200L), batch))
    }

    @Test
    fun microphonePacketRequiresActiveExplicitMicrophoneWindow() {
        val clock = MutableClock(100L)
        val packetizer = SensorStreamPacketizer(clock)
        val chunk = PcmAudioChunk(1L, 90L, 16_000, 1, byteArrayOf(1, 2, 3, 4))

        assertNull(packetizer.microphone(lease(microphoneExpiry = null), chunk))
        assertTrue(packetizer.microphone(lease(microphoneExpiry = 150L), chunk)!!.hasMicrophoneChunk())
        clock.value = 150L
        assertNull(packetizer.microphone(lease(microphoneExpiry = 150L), chunk))
    }

    @Test
    fun touchPacketPreservesRawOrderingSemanticsAndBothClockDomains() {
        val packet = SensorStreamPacketizer(FixedClock(100L)).touch(
            lease(),
            eventId = 9L,
            observedMonotonicTimestampNs = 80_000_000L,
            event = RokidInputEvent(
                key = RokidInputKey.SWIPE_FORWARD,
                action = RokidInputAction.UP,
                eventTimeMillis = 75L,
                repeatCount = 0,
                canceled = false,
                longPress = false,
                scanCode = 115,
                device = RokidInputDeviceIdentity(4, 257, 257, "ROKID,PSOC-TP-R", false, 1, 2),
            ),
        )!!

        assertEquals(9L, packet.touchEvent.eventId)
        assertEquals(80_000_000L, packet.touchEvent.observedMonotonicTimestampNs)
        assertEquals(75L, packet.touchEvent.sourceUptimeMs)
        assertEquals(115, packet.touchEvent.scanCode)
    }

    @Test
    fun systemTwoFingerHoldIsOneTriggeredSemanticEventRatherThanSyntheticKeyEdges() {
        val packet = SensorStreamPacketizer(FixedClock(100L)).systemTouch(
            lease(),
            eventId = 10L,
            event = RokidSystemTouchEvent(
                input = RokidSystemBroadcastInput.TWO_FINGER_LONG_PRESS,
                sourceUptimeMillis = 76L,
                observedMonotonicTimestampNs = 81_000_000L,
            ),
        )!!

        assertEquals(10L, packet.touchEvent.eventId)
        assertEquals(81_000_000L, packet.touchEvent.observedMonotonicTimestampNs)
        assertEquals(76L, packet.touchEvent.sourceUptimeMs)
        assertEquals(
            org.conceptflow.mpl.v1.RokidTouchKey.ROKID_TOUCH_KEY_TWO_FINGER_LONG_PRESS,
            packet.touchEvent.key,
        )
        assertEquals(
            org.conceptflow.mpl.v1.RokidTouchAction.ROKID_TOUCH_ACTION_TRIGGERED,
            packet.touchEvent.action,
        )
        assertTrue(packet.touchEvent.longPress)
        assertEquals(149, packet.touchEvent.scanCode)
    }

    private fun lease(expiresAt: Long = 1_000L, microphoneExpiry: Long? = null) = ActiveStreamLease(
        leaseId = "lease",
        peer = AuthenticatedStreamPeer("peer"),
        sessionId = "session",
        streams = buildSet {
            add(SensorStreamKind.SENSOR_STREAM_KIND_CAMERA)
            add(SensorStreamKind.SENSOR_STREAM_KIND_IMU)
            add(SensorStreamKind.SENSOR_STREAM_KIND_TOUCH)
            if (microphoneExpiry != null) add(SensorStreamKind.SENSOR_STREAM_KIND_MICROPHONE)
        },
        openedAtNanos = 1L,
        expiresAtNanos = expiresAt,
        microphoneExpiresAtNanos = microphoneExpiry,
    )

    private fun sample(sequence: Long, timestamp: Long) = ImuSample(
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

    private class MutableClock(var value: Long) : MonotonicClock {
        override fun nowNanos(): Long = value
    }

    private class FixedClock(private val value: Long) : MonotonicClock {
        override fun nowNanos(): Long = value
    }
}
