// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.core

import com.google.protobuf.ByteString
import java.security.MessageDigest
import org.conceptflow.mpl.v1.AudioSampleEncoding
import org.conceptflow.mpl.v1.CameraFrameChunk
import org.conceptflow.mpl.v1.CoordinateFrame
import org.conceptflow.mpl.v1.ImuBatch
import org.conceptflow.mpl.v1.ImuReading
import org.conceptflow.mpl.v1.MicrophoneChunk
import org.conceptflow.mpl.v1.Pose
import org.conceptflow.mpl.v1.Quaternion
import org.conceptflow.mpl.v1.RokidTouchAction
import org.conceptflow.mpl.v1.RokidTouchEvent
import org.conceptflow.mpl.v1.RokidTouchKey
import org.conceptflow.mpl.v1.SensorStreamEnvelope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlassesStreamIngressTest {
    @Test
    fun reassemblesCameraAndKeepsOnlyLatestUnreadFrame() {
        val clock = MutableHostClock(1L)
        val ingress = GlassesStreamIngress("session", "lease", false, clock)
        var envelopeSequence = 0L

        for (frameId in 1L..2L) {
            val bytes = ByteArray(2_500) { (it + frameId.toInt()).toByte() }
            val packets = chunks(frameId, bytes, envelopeSequence)
            envelopeSequence += packets.size
            packets.dropLast(1).forEach {
                assertEquals(StreamIngressDisposition.CAMERA_PARTIAL, ingress.accept(it))
            }
            assertEquals(StreamIngressDisposition.CAMERA_READY, ingress.accept(packets.last()))
        }

        val frame = ingress.takeLatestCamera()!!
        assertEquals(2L, frame.frameId)
        assertArrayEquals(ByteArray(2_500) { (it + 2).toByte() }, frame.frameData.toByteArray())
        assertEquals(1L, ingress.statistics().unreadCameraFramesReplaced)
        assertNull(ingress.takeLatestCamera())
    }

    @Test
    fun acceptsFrameAfterCameraSourceRestartWhenSessionSequenceContinuesAboveHighWater() {
        val ingress = GlassesStreamIngress("session", "lease", false, MutableHostClock(1L))
        val beforeRestart = chunks(158L, ByteArray(100) { 1 }, 0L)
        beforeRestart.forEachIndexed { index, packet ->
            assertEquals(
                if (index == beforeRestart.lastIndex) {
                    StreamIngressDisposition.CAMERA_READY
                } else {
                    StreamIngressDisposition.CAMERA_PARTIAL
                },
                ingress.accept(packet),
            )
        }
        val afterRestart = chunks(159L, ByteArray(100) { 2 }, beforeRestart.size.toLong())

        afterRestart.forEachIndexed { index, packet ->
            assertEquals(
                if (index == afterRestart.lastIndex) {
                    StreamIngressDisposition.CAMERA_READY
                } else {
                    StreamIngressDisposition.CAMERA_PARTIAL
                },
                ingress.accept(packet),
            )
        }

        assertEquals(159L, ingress.takeLatestCamera()!!.frameId)
    }

    @Test
    fun newerFrameDropsIncompleteOlderFrameAndRejectsBadDigest() {
        val clock = MutableHostClock(1L)
        val ingress = GlassesStreamIngress("session", "lease", false, clock)
        val old = chunks(1L, ByteArray(2_500) { 1 }, 0L)
        val bad = chunks(2L, ByteArray(100) { 2 }, 1L).single().toBuilder()
            .setCameraChunk(
                chunks(2L, ByteArray(100) { 2 }, 1L).single().cameraChunk.toBuilder()
                    .setChunkData(ByteString.copyFrom(ByteArray(100) { 3 })),
            ).build()

        assertEquals(StreamIngressDisposition.CAMERA_PARTIAL, ingress.accept(old.first()))
        assertEquals(StreamIngressDisposition.REJECTED_MALFORMED, ingress.accept(bad))
        assertEquals(2L, ingress.statistics().incompleteCameraFramesDropped)
    }

    @Test
    fun olderFrameCannotReplaceNewerPartialAssembly() {
        val ingress = GlassesStreamIngress("session", "lease", false, MutableHostClock(1L))
        val newer = chunks(2L, ByteArray(2_500) { 2 }, 0L)
        val older = chunks(1L, ByteArray(2_500) { 1 }, 1L)

        assertEquals(StreamIngressDisposition.CAMERA_PARTIAL, ingress.accept(newer.first()))
        assertEquals(StreamIngressDisposition.REJECTED_ORDER, ingress.accept(older.first()))
        assertEquals(0L, ingress.statistics().incompleteCameraFramesDropped)
    }

    @Test
    fun validatesAbsoluteImuOrderAndMicrophoneAuthorization() {
        val clock = MutableHostClock(1L)
        val ingress = GlassesStreamIngress("session", "lease", false, clock)
        val imu = ImuBatch.newBuilder()
            .setLeaseId("lease")
            .setBatchId(1L)
            .addSamples(imu(1L, 10L))
            .addSamples(imu(2L, 20L))
            .build()

        assertEquals(StreamIngressDisposition.IMU_READY, ingress.accept(envelope(1L).setImuBatch(imu).build()))
        assertEquals(listOf(1L, 2L), ingress.takeLatestImu()!!.samplesList.map { it.sequenceId })
        val microphone = MicrophoneChunk.newBuilder()
            .setLeaseId("lease")
            .setChunkId(1L)
            .setCaptureMonotonicTimestampNs(30L)
            .setSampleRateHz(16_000)
            .setChannelCount(1)
            .setEncoding(AudioSampleEncoding.AUDIO_SAMPLE_ENCODING_PCM_S16LE)
            .setAudioData(ByteString.copyFrom(byteArrayOf(1, 2)))
            .build()
        assertEquals(
            StreamIngressDisposition.REJECTED_UNAUTHORIZED,
            ingress.accept(envelope(2L).setMicrophoneChunk(microphone).build()),
        )
    }

    @Test
    fun authenticatedMicrophoneIngressPreservesContinuityAndIsBounded() {
        val ingress = GlassesStreamIngress("session", "lease", true, MutableHostClock(1L))
        val first = microphone(chunkId = 1L, bytes = byteArrayOf(1, 2))
        val second = microphone(chunkId = 2L, bytes = byteArrayOf(3, 4))

        assertEquals(StreamIngressDisposition.MICROPHONE_READY, ingress.acceptAuthenticatedLane(first))
        assertEquals(StreamIngressDisposition.MICROPHONE_READY, ingress.acceptAuthenticatedLane(second))
        assertArrayEquals(byteArrayOf(1, 2), ingress.takeLatestMicrophone()!!.audioData.toByteArray())
        assertArrayEquals(byteArrayOf(3, 4), ingress.takeLatestMicrophone()!!.audioData.toByteArray())
        assertEquals(0L, ingress.statistics().microphoneOverflowChunks)
    }

    @Test
    fun touchEventsPreserveOrderAndRejectOverflowWithoutEviction() {
        val ingress = GlassesStreamIngress(
            "session",
            "lease",
            true,
            MutableHostClock(1L),
            GlassesIngressLimits(maximumTouchEvents = 2),
        )

        assertEquals(StreamIngressDisposition.TOUCH_READY, ingress.acceptAuthenticatedLane(touch(1L)))
        assertEquals(StreamIngressDisposition.TOUCH_READY, ingress.acceptAuthenticatedLane(touch(2L)))
        assertEquals(StreamIngressDisposition.REJECTED_OVERFLOW, ingress.acceptAuthenticatedLane(touch(3L)))
        assertEquals(listOf(1L, 2L), ingress.takeTouchEvents().map { it.eventId })
        assertEquals(1L, ingress.statistics().touchOverflowEvents)
    }

    @Test
    fun semanticTwoFingerHoldCrossesIngressAsOneTriggeredEvent() {
        val ingress = GlassesStreamIngress("session", "lease", true, MutableHostClock(1L))
        val event = envelope(1L).setTouchEvent(
            RokidTouchEvent.newBuilder()
                .setEventId(1L)
                .setObservedMonotonicTimestampNs(10L)
                .setSourceUptimeMs(9L)
                .setKey(RokidTouchKey.ROKID_TOUCH_KEY_TWO_FINGER_LONG_PRESS)
                .setAction(RokidTouchAction.ROKID_TOUCH_ACTION_TRIGGERED)
                .setLongPress(true)
                .setScanCode(149),
        ).build()

        assertEquals(StreamIngressDisposition.TOUCH_READY, ingress.acceptAuthenticatedLane(event))
        val accepted = ingress.takeTouchEvents().single()
        assertEquals(RokidTouchKey.ROKID_TOUCH_KEY_TWO_FINGER_LONG_PRESS, accepted.key)
        assertEquals(RokidTouchAction.ROKID_TOUCH_ACTION_TRIGGERED, accepted.action)
    }

    @Test
    fun microphoneIngressRejectsPartialPcmFrames() {
        val ingress = GlassesStreamIngress("session", "lease", true, MutableHostClock(1L))

        assertEquals(
            StreamIngressDisposition.REJECTED_MALFORMED,
            ingress.acceptAuthenticatedLane(microphone(chunkId = 1L, bytes = byteArrayOf(1, 2, 3))),
        )
        assertNull(ingress.takeLatestMicrophone())
    }

    @Test
    fun expiresPartialAssemblyUsingReceiverClockNotSenderClock() {
        val clock = MutableHostClock(1L)
        val ingress = GlassesStreamIngress(
            "session",
            "lease",
            false,
            clock,
            GlassesIngressLimits(cameraAssemblyTimeoutNanos = 20_000_000L),
        )
        val packets = chunks(1L, ByteArray(2_500) { 1 }, 0L)
        assertEquals(StreamIngressDisposition.CAMERA_PARTIAL, ingress.accept(packets.first()))

        clock.now += 20_000_000L
        assertEquals(StreamIngressDisposition.REJECTED_ORDER, ingress.accept(packets[1]))
        assertEquals(1L, ingress.statistics().incompleteCameraFramesDropped)
    }

    private fun chunks(frameId: Long, bytes: ByteArray, priorSequence: Long): List<SensorStreamEnvelope> {
        val frame = testFrame(frameId = frameId, bytes = bytes, width = 50, height = 50)
        val metadata = frame.toBuilder().clearFrameData().build()
        val chunkSize = 1_024
        val count = (bytes.size + chunkSize - 1) / chunkSize
        return List(count) { index ->
            val start = index * chunkSize
            val end = minOf(bytes.size, start + chunkSize)
            val chunk = CameraFrameChunk.newBuilder()
                .setFrameId(frameId)
                .setChunkIndex(index)
                .setChunkCount(count)
                .setTotalPayloadBytes(bytes.size.toLong())
                .setCaptureMonotonicTimestampNs(frame.captureMonotonicTimestampNs)
                .setChunkData(ByteString.copyFrom(bytes, start, end - start))
                .apply { if (index == 0) setFrameMetadata(metadata) }
                .build()
            envelope(priorSequence + index + 1L).setCameraChunk(chunk).build()
        }
    }

    private fun envelope(sequence: Long) = SensorStreamEnvelope.newBuilder()
        .setSessionId("session")
        .setLeaseId("lease")
        .setSequenceId(sequence)
        .setSentMonotonicTimestampNs(1L)

    private fun microphone(chunkId: Long, bytes: ByteArray): SensorStreamEnvelope = envelope(chunkId)
        .setMicrophoneChunk(
            MicrophoneChunk.newBuilder()
                .setLeaseId("lease")
                .setChunkId(chunkId)
                .setCaptureMonotonicTimestampNs(chunkId)
                .setSampleRateHz(16_000)
                .setChannelCount(1)
                .setEncoding(AudioSampleEncoding.AUDIO_SAMPLE_ENCODING_PCM_S16LE)
                .setAudioData(ByteString.copyFrom(bytes)),
        ).build()

    private fun touch(eventId: Long): SensorStreamEnvelope = envelope(eventId)
        .setTouchEvent(
            RokidTouchEvent.newBuilder()
                .setEventId(eventId)
                .setObservedMonotonicTimestampNs(eventId)
                .setSourceUptimeMs(eventId)
                .setKey(RokidTouchKey.ROKID_TOUCH_KEY_SINGLE_TAP)
                .setAction(RokidTouchAction.ROKID_TOUCH_ACTION_DOWN)
                .setScanCode(148),
        ).build()

    private fun imu(sequence: Long, timestamp: Long): ImuReading = ImuReading.newBuilder()
        .setSequenceId(sequence)
        .setPose(
            Pose.newBuilder()
                .setReferenceFrame(CoordinateFrame.COORDINATE_FRAME_HEAD)
                .setRotation(Quaternion.newBuilder().setW(1.0))
                .setMonotonicTimestampNs(timestamp),
        )
        .build()
}
