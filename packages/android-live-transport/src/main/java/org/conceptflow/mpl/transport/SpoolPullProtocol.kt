// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import com.google.protobuf.ByteString
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import org.conceptflow.mpl.v1.CameraFrameChunk
import org.conceptflow.mpl.v1.LiveLinkControl
import org.conceptflow.mpl.v1.SensorStreamEnvelope
import org.conceptflow.mpl.v1.SpoolArtifactChunk
import org.conceptflow.mpl.v1.SpoolArtifactRequest
import org.conceptflow.mpl.v1.SpoolManifestPoll
import org.conceptflow.mpl.v1.SpoolManifestSnapshot
import org.conceptflow.mpl.v1.SpoolRecord
import org.conceptflow.mpl.v1.SpoolRecordKind
import org.conceptflow.mpl.v1.SpoolRecordsAck

/** Read-only authenticated view of Rokid Node's app-private capture spool. */
interface RokidSpoolProvider {
    fun manifest(maximumRecords: Int): SpoolManifestSnapshot
    fun artifact(recordId: String, offset: Long, maximumBytes: Int): SpoolArtifactChunk
    fun acknowledge(manifestRevision: Long, recordIds: List<String>)
}

internal object EmptyRokidSpoolProvider : RokidSpoolProvider {
    override fun manifest(maximumRecords: Int): SpoolManifestSnapshot {
        val json = SpoolManifestJson.encode(0L, emptyList())
        return SpoolManifestSnapshot.newBuilder()
            .setManifestJsonUtf8(ByteString.copyFrom(json))
            .setManifestSha256(ByteString.copyFrom(sha256(json)))
            .build()
    }

    override fun artifact(recordId: String, offset: Long, maximumBytes: Int): SpoolArtifactChunk =
        throw IllegalArgumentException("spool artifact is unavailable")

    override fun acknowledge(manifestRevision: Long, recordIds: List<String>) = Unit
}

/** Exact deterministic JSON representation written on-glasses and verified by Android Node. */
object SpoolManifestJson {
    fun encode(revision: Long, records: List<SpoolRecord>): ByteArray {
        require(revision >= 0L)
        val output = StringBuilder(256 + records.size * 512)
        output.append("{\"schema_version\":1,\"revision\":").append(revision).append(",\"records\":[")
        records.forEachIndexed { index, record ->
            if (index > 0) output.append(',')
            appendRecord(output, record)
        }
        output.append("]}\n")
        return output.toString().toByteArray(Charsets.UTF_8)
    }

    private fun appendRecord(output: StringBuilder, record: SpoolRecord) {
        output.append("{\"record_id\":")
        appendString(output, record.recordId)
        output.append(",\"revision\":").append(record.revision)
            .append(",\"created_monotonic_timestamp_ns\":").append(record.createdMonotonicTimestampNs)
            .append(",\"kind\":")
        appendString(output, record.kind.name.removePrefix("SPOOL_RECORD_KIND_").lowercase(Locale.ROOT))
        output.append(",\"relative_path\":")
        if (record.relativePath.isEmpty()) output.append("null") else appendString(output, record.relativePath)
        output.append(",\"artifact_bytes\":").append(record.artifactBytes)
            .append(",\"artifact_sha256\":")
        if (record.artifactSha256.isEmpty) output.append("null") else appendString(output, record.artifactSha256.hex())
        output.append(",\"metadata\":")
        when (record.metadataCase) {
            SpoolRecord.MetadataCase.CAMERA_METADATA -> appendCamera(output, record)
            SpoolRecord.MetadataCase.IMU_BATCH -> appendImu(output, record)
            SpoolRecord.MetadataCase.MICROPHONE_METADATA -> appendMicrophone(output, record)
            else -> output.append("null")
        }
        output.append('}')
    }

    private fun appendCamera(output: StringBuilder, record: SpoolRecord) {
        val frame = record.cameraMetadata
        output.append("{\"frame_id\":").append(frame.frameId)
            .append(",\"capture_monotonic_timestamp_ns\":").append(frame.captureMonotonicTimestampNs)
            .append(",\"width\":").append(frame.image.width)
            .append(",\"height\":").append(frame.image.height)
            .append(",\"encoding\":")
        appendString(output, frame.image.encoding.name)
        output.append('}')
    }

    private fun appendMicrophone(output: StringBuilder, record: SpoolRecord) {
        val chunk = record.microphoneMetadata
        output.append("{\"chunk_id\":").append(chunk.chunkId)
            .append(",\"capture_monotonic_timestamp_ns\":").append(chunk.captureMonotonicTimestampNs)
            .append(",\"sample_rate_hz\":").append(chunk.sampleRateHz)
            .append(",\"channel_count\":").append(chunk.channelCount)
            .append(",\"encoding\":")
        appendString(output, chunk.encoding.name)
        output.append('}')
    }

    private fun appendImu(output: StringBuilder, record: SpoolRecord) {
        val batch = record.imuBatch
        output.append("{\"batch_id\":").append(batch.batchId)
            .append(",\"created_monotonic_timestamp_ns\":").append(batch.createdMonotonicTimestampNs)
            .append(",\"samples\":[")
        batch.samplesList.forEachIndexed { index, sample ->
            if (index > 0) output.append(',')
            val pose = sample.pose
            output.append("{\"sequence_id\":").append(sample.sequenceId)
                .append(",\"pose_timestamp_ns\":").append(pose.monotonicTimestampNs)
                .append(",\"reference_frame\":")
            appendString(output, pose.referenceFrame.name)
            output.append(",\"rotation\":[")
                .append(pose.rotation.x).append(',').append(pose.rotation.y).append(',')
                .append(pose.rotation.z).append(',').append(pose.rotation.w).append(']')
                .append(",\"angular_velocity_rad_s\":[")
                .append(sample.angularVelocityRadiansPerSecond.x).append(',')
                .append(sample.angularVelocityRadiansPerSecond.y).append(',')
                .append(sample.angularVelocityRadiansPerSecond.z).append(']')
                .append(",\"angular_velocity_timestamp_ns\":")
                .append(sample.angularVelocityMonotonicTimestampNs)
                .append(",\"linear_acceleration_m_s2\":[")
                .append(sample.linearAccelerationMetersPerSecondSquared.x).append(',')
                .append(sample.linearAccelerationMetersPerSecondSquared.y).append(',')
                .append(sample.linearAccelerationMetersPerSecondSquared.z).append(']')
                .append(",\"linear_acceleration_timestamp_ns\":")
                .append(sample.linearAccelerationMonotonicTimestampNs)
                .append(",\"orientation_accuracy\":").append(sample.orientationAccuracy)
                .append('}')
        }
        output.append("]}")
    }

    private fun appendString(output: StringBuilder, value: String) {
        output.append('"')
        value.forEach { character ->
            when (character) {
                '"' -> output.append("\\\"")
                '\\' -> output.append("\\\\")
                '\b' -> output.append("\\b")
                '\u000C' -> output.append("\\f")
                '\n' -> output.append("\\n")
                '\r' -> output.append("\\r")
                '\t' -> output.append("\\t")
                else -> if (character.code < 0x20) {
                    output.append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    output.append(character)
                }
            }
        }
        output.append('"')
    }

    private fun ByteString.hex(): String =
        toByteArray().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
}

internal data class PulledSpoolRecord(val record: SpoolRecord, val artifact: ByteArray?)

/**
 * One-request-at-a-time pull state machine. It bounds memory, validates manifest identity/hash,
 * verifies every artifact before delivery, and acknowledges only successfully delivered records.
 */
internal class HostSpoolPullCoordinator(
    private val deliver: (PulledSpoolRecord) -> Boolean,
    private val pollIntervalNs: Long = 100_000_000L,
) {
    private var knownRevision = 0L
    private var lastPollNs = Long.MIN_VALUE
    private var awaitingResponse = false
    private var responseDeadlineNs = 0L
    private var pendingArtifact: PendingArtifact? = null
    private val pendingAcks = LinkedHashSet<String>()

    fun nextControl(nowNs: Long): LiveLinkControl? {
        require(nowNs >= 0L)
        if (awaitingResponse && nowNs < responseDeadlineNs) return null
        if (awaitingResponse) {
            awaitingResponse = false
            pendingArtifact = null
        }
        if (pendingAcks.isNotEmpty()) {
            val ids = pendingAcks.toList()
            pendingAcks.clear()
            return LiveLinkControl.newBuilder().setSpoolRecordsAck(
                SpoolRecordsAck.newBuilder().setManifestRevision(knownRevision).addAllRecordIds(ids),
            ).build()
        }
        pendingArtifact?.let { pending ->
            awaitingResponse = true
            responseDeadlineNs = nowNs + RESPONSE_TIMEOUT_NS
            return LiveLinkControl.newBuilder().setSpoolArtifactRequest(
                SpoolArtifactRequest.newBuilder()
                    .setRecordId(pending.record.recordId)
                    .setOffset(pending.bytes.size().toLong())
                    .setMaxBytes(ARTIFACT_CHUNK_BYTES),
            ).build()
        }
        if (lastPollNs == Long.MIN_VALUE || nowNs - lastPollNs >= pollIntervalNs) {
            lastPollNs = nowNs
            awaitingResponse = true
            responseDeadlineNs = nowNs + RESPONSE_TIMEOUT_NS
            return LiveLinkControl.newBuilder().setSpoolManifestPoll(
                SpoolManifestPoll.newBuilder().setKnownRevision(knownRevision).setMaxRecords(MAXIMUM_PAGE_RECORDS),
            ).build()
        }
        return null
    }

    fun accept(control: LiveLinkControl): Boolean = when (control.payloadCase) {
        LiveLinkControl.PayloadCase.SPOOL_MANIFEST_SNAPSHOT -> acceptManifest(control.spoolManifestSnapshot)
        LiveLinkControl.PayloadCase.SPOOL_ARTIFACT_CHUNK -> acceptArtifact(control.spoolArtifactChunk)
        else -> false
    }

    private fun acceptManifest(snapshot: SpoolManifestSnapshot): Boolean {
        // A response can arrive after the bounded request deadline. It is authenticated but stale,
        // so discard it instead of tearing down the independent live sensor transport.
        if (!awaitingResponse) return true
        awaitingResponse = false
        require(snapshot.revision >= knownRevision)
        require(snapshot.recordsCount <= MAXIMUM_PAGE_RECORDS)
        val json = snapshot.manifestJsonUtf8.toByteArray()
        require(json.isNotEmpty() && json.size <= MAXIMUM_MANIFEST_BYTES)
        require(snapshot.manifestSha256.size() == SHA256_BYTES)
        require(MessageDigest.isEqual(sha256(json), snapshot.manifestSha256.toByteArray()))
        require(json.contentEquals(SpoolManifestJson.encode(snapshot.revision, snapshot.recordsList)))
        knownRevision = snapshot.revision
        for (record in snapshot.recordsList) {
            validateRecord(record)
            when (record.kind) {
                SpoolRecordKind.SPOOL_RECORD_KIND_IMU -> {
                    if (deliver(PulledSpoolRecord(record, null))) pendingAcks += record.recordId
                }
                SpoolRecordKind.SPOOL_RECORD_KIND_CAMERA,
                SpoolRecordKind.SPOOL_RECORD_KIND_MICROPHONE,
                -> if (pendingArtifact == null) pendingArtifact = PendingArtifact(record)
                else -> error("unsupported spool record kind")
            }
        }
        return true
    }

    private fun acceptArtifact(chunk: SpoolArtifactChunk): Boolean {
        if (!awaitingResponse || pendingArtifact == null) return true
        awaitingResponse = false
        val pending = requireNotNull(pendingArtifact)
        require(chunk.recordId == pending.record.recordId)
        require(chunk.offset == pending.bytes.size().toLong())
        require(chunk.totalBytes == pending.record.artifactBytes)
        require(chunk.artifactSha256 == pending.record.artifactSha256)
        require(!chunk.data.isEmpty && chunk.data.size() <= ARTIFACT_CHUNK_BYTES)
        require(pending.bytes.size().toLong() + chunk.data.size() <= chunk.totalBytes)
        chunk.data.writeTo(pending.bytes)
        if (chunk.endOfFile) {
            val bytes = pending.bytes.toByteArray()
            require(bytes.size.toLong() == pending.record.artifactBytes)
            require(MessageDigest.isEqual(sha256(bytes), pending.record.artifactSha256.toByteArray()))
            if (deliver(PulledSpoolRecord(pending.record, bytes))) pendingAcks += pending.record.recordId
            pendingArtifact = null
        } else {
            require(pending.bytes.size().toLong() < chunk.totalBytes)
        }
        return true
    }

    private fun validateRecord(record: SpoolRecord) {
        require(record.recordId.matches(RECORD_ID_PATTERN))
        require(record.revision in 1L..knownRevision)
        require(record.createdMonotonicTimestampNs > 0L)
        when (record.kind) {
            SpoolRecordKind.SPOOL_RECORD_KIND_CAMERA -> {
                require(record.hasCameraMetadata() && !record.relativePath.isBlank())
                require(record.artifactBytes in 1..MAXIMUM_CAMERA_BYTES)
                require(record.artifactSha256.size() == SHA256_BYTES)
            }
            SpoolRecordKind.SPOOL_RECORD_KIND_IMU -> {
                require(record.hasImuBatch() && record.relativePath.isEmpty())
                require(record.artifactBytes == 0L && record.artifactSha256.isEmpty)
            }
            SpoolRecordKind.SPOOL_RECORD_KIND_MICROPHONE -> {
                require(record.hasMicrophoneMetadata() && !record.relativePath.isBlank())
                require(record.artifactBytes in 1..MAXIMUM_MICROPHONE_BYTES)
                require(record.artifactSha256.size() == SHA256_BYTES)
            }
            else -> error("unsupported spool record kind")
        }
        require(!record.relativePath.startsWith('/') && ".." !in record.relativePath.split('/'))
    }

    private data class PendingArtifact(
        val record: SpoolRecord,
        val bytes: ByteArrayOutputStream = ByteArrayOutputStream(record.artifactBytes.toInt()),
    )

    companion object {
        const val MAXIMUM_PAGE_RECORDS = 16
        const val ARTIFACT_CHUNK_BYTES = 48 * 1024
        private const val MAXIMUM_MANIFEST_BYTES = 60 * 1024
        private const val MAXIMUM_CAMERA_BYTES = 2L * 1024 * 1024
        private const val MAXIMUM_MICROPHONE_BYTES = 512L * 1024
        private const val SHA256_BYTES = 32
        private const val RESPONSE_TIMEOUT_NS = 1_000_000_000L
        private val RECORD_ID_PATTERN = Regex("[a-z]+-[0-9]{1,20}-[0-9]{1,20}")
    }
}

internal fun pulledRecordToSensors(
    pulled: PulledSpoolRecord,
    binding: LiveSessionBinding,
): List<SensorStreamEnvelope> {
    val record = pulled.record
    return when (record.kind) {
        SpoolRecordKind.SPOOL_RECORD_KIND_IMU -> listOf(
            SensorStreamEnvelope.newBuilder()
                .setSessionId(binding.sessionId)
                .setLeaseId(binding.leaseId)
                .setImuBatch(record.imuBatch.toBuilder().setLeaseId(binding.leaseId))
                .build(),
        )
        SpoolRecordKind.SPOOL_RECORD_KIND_MICROPHONE -> {
            val bytes = decodePcmWave(
                requireNotNull(pulled.artifact),
                record.microphoneMetadata.sampleRateHz,
                record.microphoneMetadata.channelCount,
            )
            listOf(
                SensorStreamEnvelope.newBuilder()
                    .setSessionId(binding.sessionId)
                    .setLeaseId(binding.leaseId)
                    .setMicrophoneChunk(
                        record.microphoneMetadata.toBuilder()
                            .setLeaseId(binding.leaseId)
                            .setAudioData(ByteString.copyFrom(bytes)),
                    )
                    .build(),
            )
        }
        SpoolRecordKind.SPOOL_RECORD_KIND_CAMERA -> {
            val bytes = requireNotNull(pulled.artifact)
            val chunkCount = (bytes.size + CAMERA_DELIVERY_CHUNK_BYTES - 1) / CAMERA_DELIVERY_CHUNK_BYTES
            List(chunkCount) { index ->
                val start = index * CAMERA_DELIVERY_CHUNK_BYTES
                val end = minOf(bytes.size, start + CAMERA_DELIVERY_CHUNK_BYTES)
                val chunk = CameraFrameChunk.newBuilder()
                    .setFrameId(record.cameraMetadata.frameId)
                    .setChunkIndex(index)
                    .setChunkCount(chunkCount)
                    .setTotalPayloadBytes(bytes.size.toLong())
                    .setChunkData(ByteString.copyFrom(bytes, start, end - start))
                if (index == 0) {
                    chunk.frameMetadata = record.cameraMetadata.toBuilder()
                        .setSessionId(binding.sessionId)
                        .clearFrameData()
                        .build()
                }
                SensorStreamEnvelope.newBuilder()
                    .setSessionId(binding.sessionId)
                    .setLeaseId(binding.leaseId)
                    .setCameraChunk(chunk)
                    .build()
            }
        }
        else -> emptyList()
    }
}

internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

private fun decodePcmWave(wave: ByteArray, sampleRateHz: Int, channelCount: Int): ByteArray {
    require(wave.size >= WAVE_HEADER_BYTES)
    require(wave.copyOfRange(0, 4).contentEquals("RIFF".toByteArray(Charsets.US_ASCII)))
    require(wave.copyOfRange(8, 16).contentEquals("WAVEfmt ".toByteArray(Charsets.US_ASCII)))
    require(wave.copyOfRange(36, 40).contentEquals("data".toByteArray(Charsets.US_ASCII)))
    val header = ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN)
    require(header.getInt(4) == wave.size - 8)
    require(header.getInt(16) == 16)
    require(header.getShort(20).toInt() == 1)
    require(header.getShort(22).toInt() == channelCount)
    require(header.getInt(24) == sampleRateHz)
    require(header.getShort(34).toInt() == 16)
    val dataBytes = header.getInt(40)
    require(dataBytes == wave.size - WAVE_HEADER_BYTES)
    return wave.copyOfRange(WAVE_HEADER_BYTES, wave.size)
}

private const val CAMERA_DELIVERY_CHUNK_BYTES = 16 * 1024
private const val WAVE_HEADER_BYTES = 44
