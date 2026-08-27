// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.core

import com.google.protobuf.ByteString
import java.security.MessageDigest
import org.conceptflow.mpl.v1.CameraFrameChunk
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.ImageDescriptor
import org.conceptflow.mpl.v1.ImuBatch
import org.conceptflow.mpl.v1.ImuReading
import org.conceptflow.mpl.v1.Pose
import org.conceptflow.mpl.v1.SensorStreamEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AuthenticatedLiveGlassesIngressTest {
    private val ingress = GlassesStreamIngress("session", "lease", false, HostClock { 10_000 })

    @Test
    fun `authenticated lane accepts camera chunk with unused nested sequence zero`() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
        val metadata = FramePayload.newBuilder()
            .setSessionId("session")
            .setFrameId(1)
            .setCaptureMonotonicTimestampNs(1)
            .setImage(
                ImageDescriptor.newBuilder()
                    .setWidth(1)
                    .setHeight(1)
                    .setPayloadBytes(jpeg.size.toLong())
                    .setSha256(ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(jpeg))),
            ).build()
        val nested = base().setCameraChunk(
            CameraFrameChunk.newBuilder()
                .setFrameMetadata(metadata)
                .setFrameId(1)
                .setChunkIndex(0)
                .setChunkCount(1)
                .setTotalPayloadBytes(jpeg.size.toLong())
                .setCaptureMonotonicTimestampNs(metadata.captureMonotonicTimestampNs)
                .setChunkData(ByteString.copyFrom(jpeg)),
        ).build()

        assertEquals(0L, nested.sequenceId)
        assertEquals(StreamIngressDisposition.CAMERA_READY, ingress.acceptAuthenticatedLane(nested))
        assertNotNull(ingress.takeLatestCamera())
    }

    @Test
    fun `authenticated lane accepts IMU batch with unused nested sequence zero`() {
        val nested = base().setImuBatch(
            ImuBatch.newBuilder()
                .setLeaseId("lease")
                .setBatchId(1)
                .setCreatedMonotonicTimestampNs(20)
                .addSamples(
                    ImuReading.newBuilder()
                        .setSequenceId(1)
                        .setPose(Pose.newBuilder().setMonotonicTimestampNs(10)),
                ),
        ).build()

        assertEquals(0L, nested.sequenceId)
        assertEquals(StreamIngressDisposition.IMU_READY, ingress.acceptAuthenticatedLane(nested))
        assertEquals(1, ingress.takeLatestImu()?.samplesCount)
    }

    private fun base(): SensorStreamEnvelope.Builder = SensorStreamEnvelope.newBuilder()
        .setSessionId("session")
        .setLeaseId("lease")
}
