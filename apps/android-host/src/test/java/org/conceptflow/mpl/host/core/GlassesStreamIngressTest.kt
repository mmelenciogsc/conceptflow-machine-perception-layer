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
