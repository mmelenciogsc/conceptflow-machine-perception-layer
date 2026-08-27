// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import com.google.protobuf.ByteString
import org.conceptflow.mpl.v1.CoordinateFrame
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.ImageDescriptor
import org.conceptflow.mpl.v1.ImageEncoding
import org.conceptflow.mpl.v1.ImuBatch
import org.conceptflow.mpl.v1.ImuReading
import org.conceptflow.mpl.v1.Pose
import org.conceptflow.mpl.v1.Quaternion
import org.conceptflow.mpl.v1.SpoolArtifactChunk
import org.conceptflow.mpl.v1.SpoolManifestSnapshot
import org.conceptflow.mpl.v1.SpoolRecord
import org.conceptflow.mpl.v1.SpoolRecordKind
import org.conceptflow.mpl.v1.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpoolPullProtocolTest {
    @Test
    fun `poll delivers inline imu then verifies camera bytes before ack`() {
        val artifact = ByteArray(70_000) { index -> (index and 0xff).toByte() }
        val imu = imuRecord(revision = 1)
        val camera = cameraRecord(revision = 2, artifact = artifact)
        val delivered = mutableListOf<PulledSpoolRecord>()
        val coordinator = HostSpoolPullCoordinator(deliver = { delivered += it; true })

        assertTrue(coordinator.nextControl(1L)!!.hasSpoolManifestPoll())
        val json = SpoolManifestJson.encode(2L, listOf(imu, camera))
        assertTrue(
            coordinator.accept(
                org.conceptflow.mpl.v1.LiveLinkControl.newBuilder().setSpoolManifestSnapshot(
                    SpoolManifestSnapshot.newBuilder()
                        .setRevision(2L)
                        .setManifestJsonUtf8(ByteString.copyFrom(json))
                        .setManifestSha256(ByteString.copyFrom(sha256(json)))
                        .addRecords(imu)
                        .addRecords(camera),
                ).build(),
            ),
        )
        assertEquals(listOf("imu-1-100"), delivered.map { it.record.recordId })

        val imuAck = coordinator.nextControl(2L)!!
        assertEquals(listOf("imu-1-100"), imuAck.spoolRecordsAck.recordIdsList)

        var request = coordinator.nextControl(3L)!!.spoolArtifactRequest
        assertEquals(0L, request.offset)
        val first = artifact.copyOfRange(0, request.maxBytes)
        coordinator.accept(artifactControl(camera, artifact, 0L, first, end = false))

        request = coordinator.nextControl(4L)!!.spoolArtifactRequest
        assertEquals(first.size.toLong(), request.offset)
        val second = artifact.copyOfRange(first.size, artifact.size)
        coordinator.accept(artifactControl(camera, artifact, first.size.toLong(), second, end = true))

        assertEquals(2, delivered.size)
        assertTrue(delivered.last().artifact!!.contentEquals(artifact))
        val cameraAck = coordinator.nextControl(5L)!!
        assertEquals(listOf(camera.recordId), cameraAck.spoolRecordsAck.recordIdsList)
    }

    @Test
    fun `canonical json includes file location and selected imu values`() {
        val bytes = SpoolManifestJson.encode(2L, listOf(imuRecord(1), cameraRecord(2, byteArrayOf(1))))
        val json = bytes.toString(Charsets.UTF_8)

        assertTrue(json.contains("\"relative_path\":\"camera/camera-7-200.jpg\""))
        assertTrue(json.contains("\"pose_timestamp_ns\":90"))
        assertTrue(json.contains("\"angular_velocity_rad_s\":[0.1,0.2,0.3]"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `manifest with mismatched hash fails closed`() {
        val record = imuRecord(1)
        val json = SpoolManifestJson.encode(1L, listOf(record))
        val coordinator = HostSpoolPullCoordinator(deliver = { true })
        coordinator.nextControl(1L)
        coordinator.accept(
            org.conceptflow.mpl.v1.LiveLinkControl.newBuilder().setSpoolManifestSnapshot(
                SpoolManifestSnapshot.newBuilder()
                    .setRevision(1L)
                    .setManifestJsonUtf8(ByteString.copyFrom(json))
                    .setManifestSha256(ByteString.copyFrom(ByteArray(32)))
                    .addRecords(record),
            ).build(),
        )
    }

    @Test
    fun `late duplicate manifest is discarded without closing the live transport`() {
        val coordinator = HostSpoolPullCoordinator(deliver = { true })
        val json = SpoolManifestJson.encode(0L, emptyList())
        val response = org.conceptflow.mpl.v1.LiveLinkControl.newBuilder().setSpoolManifestSnapshot(
            SpoolManifestSnapshot.newBuilder()
                .setRevision(0L)
                .setManifestJsonUtf8(ByteString.copyFrom(json))
                .setManifestSha256(ByteString.copyFrom(sha256(json))),
        ).build()

        assertTrue(coordinator.nextControl(1L)!!.hasSpoolManifestPoll())
        assertTrue(coordinator.accept(response))
        assertTrue(coordinator.accept(response))
    }

    private fun artifactControl(
        record: SpoolRecord,
        allBytes: ByteArray,
        offset: Long,
        bytes: ByteArray,
        end: Boolean,
    ) = org.conceptflow.mpl.v1.LiveLinkControl.newBuilder().setSpoolArtifactChunk(
        SpoolArtifactChunk.newBuilder()
            .setRecordId(record.recordId)
            .setOffset(offset)
            .setTotalBytes(allBytes.size.toLong())
            .setData(ByteString.copyFrom(bytes))
            .setArtifactSha256(ByteString.copyFrom(sha256(allBytes)))
            .setEndOfFile(end),
    ).build()

    private fun cameraRecord(revision: Long, artifact: ByteArray): SpoolRecord = SpoolRecord.newBuilder()
        .setRecordId("camera-7-200")
        .setRevision(revision)
        .setCreatedMonotonicTimestampNs(200L)
        .setKind(SpoolRecordKind.SPOOL_RECORD_KIND_CAMERA)
        .setRelativePath("camera/camera-7-200.jpg")
        .setArtifactBytes(artifact.size.toLong())
        .setArtifactSha256(ByteString.copyFrom(sha256(artifact)))
        .setCameraMetadata(
            FramePayload.newBuilder()
                .setRequestId("request")
                .setSessionId("session")
                .setStreamId("camera")
                .setFrameId(7L)
                .setCaptureMonotonicTimestampNs(200L)
                .setImage(
                    ImageDescriptor.newBuilder()
                        .setWidth(640)
                        .setHeight(640)
                        .setEncoding(ImageEncoding.IMAGE_ENCODING_JPEG)
                        .setMediaType("image/jpeg")
                        .setPayloadBytes(artifact.size.toLong())
                        .setSha256(ByteString.copyFrom(sha256(artifact))),
                ),
        )
        .build()

    private fun imuRecord(revision: Long): SpoolRecord {
        val reading = ImuReading.newBuilder()
            .setSequenceId(1L)
            .setPose(
                Pose.newBuilder()
                    .setReferenceFrame(CoordinateFrame.COORDINATE_FRAME_HEAD)
                    .setMonotonicTimestampNs(90L)
                    .setRotation(Quaternion.newBuilder().setW(1.0)),
            )
            .setAngularVelocityRadiansPerSecond(Vector3.newBuilder().setX(0.1).setY(0.2).setZ(0.3))
            .setLinearAccelerationMetersPerSecondSquared(Vector3.newBuilder().setZ(9.8))
            .setAngularVelocityMonotonicTimestampNs(90L)
            .setLinearAccelerationMonotonicTimestampNs(90L)
            .build()
        return SpoolRecord.newBuilder()
            .setRecordId("imu-1-100")
            .setRevision(revision)
            .setCreatedMonotonicTimestampNs(100L)
            .setKind(SpoolRecordKind.SPOOL_RECORD_KIND_IMU)
            .setImuBatch(
                ImuBatch.newBuilder()
                    .setLeaseId("lease")
                    .setBatchId(1L)
                    .setCreatedMonotonicTimestampNs(100L)
                    .addSamples(reading),
            )
            .build()
    }
}
