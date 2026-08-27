// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import com.google.protobuf.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.LinkedHashMap
import org.conceptflow.mpl.rokid.core.ActiveStreamLease
import org.conceptflow.mpl.rokid.core.ElapsedRealtimeClock
import org.conceptflow.mpl.rokid.core.ImuTransmissionBatch
import org.conceptflow.mpl.rokid.core.LegacySpoolMetrics
import org.conceptflow.mpl.rokid.core.LegacySpoolMetricsSnapshot
import org.conceptflow.mpl.rokid.core.PcmAudioChunk
import org.conceptflow.mpl.rokid.core.RokidSensorSpool
import org.conceptflow.mpl.rokid.core.SensorStreamPacketizer
import org.conceptflow.mpl.rokid.core.SquareAspectFillTransform
import org.conceptflow.mpl.transport.RokidSpoolProvider
import org.conceptflow.mpl.transport.SpoolManifestJson
import org.conceptflow.mpl.v1.AudioSampleEncoding
import org.conceptflow.mpl.v1.CameraIntrinsics
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.ImageEncoding
import org.conceptflow.mpl.v1.SpoolArtifactChunk
import org.conceptflow.mpl.v1.SpoolManifestSnapshot
import org.conceptflow.mpl.v1.SpoolRecord
import org.conceptflow.mpl.v1.SpoolRecordKind

internal enum class CameraSpoolInputKind {
    PACKED_RGB8,
    LEGACY_JPEG,
}

internal enum class CameraSpoolInputRejection(val diagnosticLabel: String) {
    UNSUPPORTED_ENCODING("unsupported_encoding"),
    INVALID_DIMENSIONS("invalid_dimensions"),
    MEDIA_TYPE_MISMATCH("media_type_mismatch"),
    ROW_STRIDE_MISMATCH("row_stride_mismatch"),
    PAYLOAD_SIZE_MISMATCH("payload_size_mismatch"),
    PAYLOAD_DIGEST_MISMATCH("payload_digest_mismatch"),
}

internal class CameraSpoolInputException(val rejection: CameraSpoolInputRejection) :
    IllegalArgumentException(rejection.diagnosticLabel)

internal data class ValidatedCameraSpoolInput(
    val kind: CameraSpoolInputKind,
    val width: Int,
    val height: Int,
    val rowStrideBytes: Int,
    val bytes: ByteArray,
)

/**
 * App-private, bounded, acknowledged capture spool. Only gate-admitted data reaches this class.
 * Files are never placed in shared storage and are removed only after an authenticated host ACK
 * or bounded oldest-first quota eviction.
 */
class RokidCaptureSpool(context: Context) : RokidSensorSpool, RokidSpoolProvider {
    private val root = File(context.noBackupFilesDir, ROOT_DIRECTORY)
    private val cameraDirectory = File(root, CAMERA_DIRECTORY)
    private val microphoneDirectory = File(root, MICROPHONE_DIRECTORY)
    private val manifestFile = AtomicFile(File(root, MANIFEST_FILENAME))
    private val stateFile = AtomicFile(File(root, STATE_FILENAME))
    private val records = LinkedHashMap<String, SpoolRecord>()
    private val packetizer = SensorStreamPacketizer(ElapsedRealtimeClock)
    private val metrics = LegacySpoolMetrics()
    private var revision = 0L
    private var artifactBytes = 0L
    private var dirty = false
    private var lastPersistedMonotonicNs = 0L
    private var activeSessionId: String? = null

    init {
        require(root.mkdirs() || root.isDirectory)
        require(cameraDirectory.mkdirs() || cameraDirectory.isDirectory)
        require(microphoneDirectory.mkdirs() || microphoneDirectory.isDirectory)
        restrict(root)
        restrict(cameraDirectory)
        restrict(microphoneDirectory)
        loadState()
        dirty = true
        persist(force = true)
    }

    @Synchronized
    override fun beginSession(sessionId: String) {
        require(sessionId.isNotBlank())
        if (activeSessionId == sessionId) return
        records.values.forEach(::deleteArtifact)
        records.clear()
        artifactBytes = 0L
        activeSessionId = sessionId
        metrics.reset()
        revision = Math.addExact(revision, 1L)
        dirty = true
        persist(force = true)
    }

    @Synchronized
    override fun storeCamera(lease: ActiveStreamLease, frame: FramePayload): Boolean {
        return runCatching {
            val startedNs = SystemClock.elapsedRealtimeNanos()
            require(frame.frameId > 0L && frame.captureMonotonicTimestampNs > 0L)
            val transformStartedNs = SystemClock.elapsedRealtimeNanos()
            val converted = convertCamera(frame)
            val transformNanos = elapsedSince(transformStartedNs)
            val recordId = "camera-${frame.frameId}-${frame.captureMonotonicTimestampNs}"
            val filename = "$recordId.jpg"
            val relativePath = "$CAMERA_DIRECTORY/$filename"
            val target = resolveArtifact(relativePath)
            writeAtomic(target, converted.jpeg)
            val record = SpoolRecord.newBuilder()
                .setRecordId(recordId)
                .setCreatedMonotonicTimestampNs(frame.captureMonotonicTimestampNs)
                .setKind(SpoolRecordKind.SPOOL_RECORD_KIND_CAMERA)
                .setRelativePath(relativePath)
                .setArtifactBytes(converted.jpeg.size.toLong())
                .setArtifactSha256(ByteString.copyFrom(sha256(converted.jpeg)))
                .setCameraMetadata(converted.metadata.toBuilder().setSessionId(lease.sessionId).clearFrameData())
                .build()
            append(record, forcePersistence = true)
            metrics.recordCamera(transformNanos, elapsedSince(startedNs), converted.jpeg.size)
            true
        }.getOrElse { error ->
            val reason = (error as? CameraSpoolInputException)?.rejection?.diagnosticLabel
                ?: "conversion_or_persistence_failure"
            Log.w(LOG_TAG, "diagnostic_spool_camera_store_failed reason=$reason")
            false
        }
    }

    @Synchronized
    override fun storeImu(lease: ActiveStreamLease, batch: ImuTransmissionBatch): Boolean = runCatching {
        val startedNs = SystemClock.elapsedRealtimeNanos()
        val envelope = requireNotNull(packetizer.imu(lease, batch))
        val recordId = "imu-${batch.batchId}-${batch.createdMonotonicTimestampNanos}"
        append(
            SpoolRecord.newBuilder()
                .setRecordId(recordId)
                .setCreatedMonotonicTimestampNs(batch.createdMonotonicTimestampNanos)
                .setKind(SpoolRecordKind.SPOOL_RECORD_KIND_IMU)
                .setImuBatch(envelope.imuBatch)
                .build(),
            forcePersistence = false,
        )
        metrics.recordImu(elapsedSince(startedNs))
        true
    }.getOrElse { false }

    @Synchronized
    override fun storeMicrophone(lease: ActiveStreamLease, chunk: PcmAudioChunk): Boolean = runCatching {
        val startedNs = SystemClock.elapsedRealtimeNanos()
        val envelope = requireNotNull(packetizer.microphone(lease, chunk))
        val wave = encodeWave(chunk)
        val recordId = "microphone-${chunk.chunkId}-${chunk.captureMonotonicTimestampNs}"
        val filename = "$recordId.wav"
        val relativePath = "$MICROPHONE_DIRECTORY/$filename"
        val target = resolveArtifact(relativePath)
        writeAtomic(target, wave)
        val metadata = envelope.microphoneChunk.toBuilder().clearAudioData().build()
        append(
            SpoolRecord.newBuilder()
                .setRecordId(recordId)
                .setCreatedMonotonicTimestampNs(chunk.captureMonotonicTimestampNs)
                .setKind(SpoolRecordKind.SPOOL_RECORD_KIND_MICROPHONE)
                .setRelativePath(relativePath)
                .setArtifactBytes(wave.size.toLong())
                .setArtifactSha256(ByteString.copyFrom(sha256(wave)))
                .setMicrophoneMetadata(metadata)
                .build(),
            forcePersistence = true,
        )
        metrics.recordMicrophone(elapsedSince(startedNs), wave.size)
        true
    }.getOrElse { false }

    @Synchronized
    override fun manifest(maximumRecords: Int): SpoolManifestSnapshot {
        require(maximumRecords in 1..MAXIMUM_PAGE_RECORDS)
        // The transport may receive its first host poll before the controller's asynchronous
        // onSessionReady callback binds this provider. Never expose recovery records from a
        // previous session during that window; beginSession will clear and version them before
        // current-session publication starts.
        if (activeSessionId == null) return manifestSnapshot(emptyList())
        persist(force = true)
        val page = records.values.take(maximumRecords)
        return manifestSnapshot(page)
    }

    @Synchronized
    override fun artifact(recordId: String, offset: Long, maximumBytes: Int): SpoolArtifactChunk {
        require(activeSessionId != null) { "spool is not bound to an active session" }
        val startedNs = SystemClock.elapsedRealtimeNanos()
        require(recordId.matches(RECORD_ID_PATTERN))
        require(offset >= 0L && maximumBytes in 1..MAXIMUM_ARTIFACT_CHUNK_BYTES)
        val record = requireNotNull(records[recordId]) { "unknown spool record" }
        require(record.relativePath.isNotEmpty() && record.artifactBytes > 0L)
        require(offset < record.artifactBytes)
        val file = resolveArtifact(record.relativePath)
        require(file.isFile && file.length() == record.artifactBytes)
        val count = minOf(maximumBytes.toLong(), record.artifactBytes - offset).toInt()
        val bytes = ByteArray(count)
        file.inputStream().use { input ->
            var skipped = 0L
            while (skipped < offset) {
                val amount = input.skip(offset - skipped)
                require(amount > 0L)
                skipped += amount
            }
            var read = 0
            while (read < count) {
                val amount = input.read(bytes, read, count - read)
                require(amount > 0)
                read += amount
            }
        }
        return SpoolArtifactChunk.newBuilder()
            .setRecordId(record.recordId)
            .setOffset(offset)
            .setTotalBytes(record.artifactBytes)
            .setData(ByteString.copyFrom(bytes))
            .setArtifactSha256(record.artifactSha256)
            .setEndOfFile(offset + count == record.artifactBytes)
            .build().also { metrics.recordArtifactRead(bytes.size, elapsedSince(startedNs)) }
    }

    @Synchronized
    override fun acknowledge(manifestRevision: Long, recordIds: List<String>) {
        require(activeSessionId != null) { "spool is not bound to an active session" }
        require(manifestRevision <= revision)
        require(recordIds.size <= MAXIMUM_PAGE_RECORDS)
        var removedCount = 0
        recordIds.distinct().forEach { recordId ->
            if (!recordId.matches(RECORD_ID_PATTERN)) return@forEach
            val record = records.remove(recordId) ?: return@forEach
            deleteArtifact(record)
            artifactBytes -= record.artifactBytes
            removedCount += 1
        }
        if (removedCount > 0) {
            metrics.recordAcknowledgement(removedCount)
            revision = Math.addExact(revision, 1L)
            persist(force = true)
        }
    }

    @Synchronized
    override fun metricsSnapshot(): LegacySpoolMetricsSnapshot = metrics.snapshot()

    private fun manifestSnapshot(page: List<SpoolRecord>): SpoolManifestSnapshot {
        val json = SpoolManifestJson.encode(revision, page)
        require(json.size <= MAXIMUM_PAGE_JSON_BYTES)
        return SpoolManifestSnapshot.newBuilder()
            .setRevision(revision)
            .setManifestJsonUtf8(ByteString.copyFrom(json))
            .setManifestSha256(ByteString.copyFrom(sha256(json)))
            .addAllRecords(page)
            .build()
    }

    private fun append(unversioned: SpoolRecord, forcePersistence: Boolean) {
        require(unversioned.recordId !in records)
        revision = Math.addExact(revision, 1L)
        val record = unversioned.toBuilder().setRevision(revision).build()
        records[record.recordId] = record
        artifactBytes = Math.addExact(artifactBytes, record.artifactBytes)
        enforceBounds()
        dirty = true
        persist(force = forcePersistence)
    }

    private fun enforceBounds() {
        while (records.size > MAXIMUM_RECORDS || artifactBytes > MAXIMUM_ARTIFACT_BYTES) {
            val oldest = records.entries.firstOrNull() ?: break
            records.remove(oldest.key)
            deleteArtifact(oldest.value)
            artifactBytes -= oldest.value.artifactBytes
            revision = Math.addExact(revision, 1L)
        }
    }

    private fun persist(force: Boolean) {
        if (!dirty && manifestFile.baseFile.isFile && stateFile.baseFile.isFile) return
        val nowNs = SystemClock.elapsedRealtimeNanos()
        if (!force && lastPersistedMonotonicNs > 0L &&
            nowNs - lastPersistedMonotonicNs < MINIMUM_MANIFEST_FLUSH_INTERVAL_NS
        ) {
            return
        }
        val startedNs = SystemClock.elapsedRealtimeNanos()
        val currentRecords = records.values.toList()
        val json = SpoolManifestJson.encode(revision, currentRecords)
        val state = SpoolManifestSnapshot.newBuilder()
            .setRevision(revision)
            .setManifestJsonUtf8(ByteString.copyFrom(json))
            .setManifestSha256(ByteString.copyFrom(sha256(json)))
            .addAllRecords(currentRecords)
            .build()
            .toByteArray()
        writeAtomic(manifestFile, json)
        writeAtomic(stateFile, state)
        metrics.recordManifestPersist(json.size, state.size, elapsedSince(startedNs))
        dirty = false
        lastPersistedMonotonicNs = nowNs
    }

    private fun loadState() {
        val bytes = runCatching { stateFile.openRead().use { it.readBytes() } }.getOrNull() ?: return
        val snapshot = runCatching { SpoolManifestSnapshot.parseFrom(bytes) }.getOrNull() ?: return
        val canonical = SpoolManifestJson.encode(snapshot.revision, snapshot.recordsList)
        if (!canonical.contentEquals(snapshot.manifestJsonUtf8.toByteArray()) ||
            !MessageDigest.isEqual(sha256(canonical), snapshot.manifestSha256.toByteArray())
        ) {
            return
        }
        revision = snapshot.revision
        snapshot.recordsList.forEach { record ->
            if (record.recordId.matches(RECORD_ID_PATTERN) && record.revision <= revision && artifactExists(record)) {
                records[record.recordId] = record
                artifactBytes += record.artifactBytes
            }
        }
        enforceBounds()
    }

    private fun artifactExists(record: SpoolRecord): Boolean = when (record.kind) {
        SpoolRecordKind.SPOOL_RECORD_KIND_IMU -> record.relativePath.isEmpty() && record.artifactBytes == 0L
        SpoolRecordKind.SPOOL_RECORD_KIND_CAMERA,
        SpoolRecordKind.SPOOL_RECORD_KIND_MICROPHONE,
        -> runCatching {
            val file = resolveArtifact(record.relativePath)
            file.isFile && file.length() == record.artifactBytes
        }.getOrDefault(false)
        else -> false
    }

    private fun deleteArtifact(record: SpoolRecord) {
        if (record.relativePath.isNotEmpty()) runCatching { resolveArtifact(record.relativePath).delete() }
    }

    private fun convertCamera(frame: FramePayload): ConvertedCamera {
        val input = validateCameraInput(frame)
        return when (input.kind) {
            CameraSpoolInputKind.PACKED_RGB8 -> convertRgbCamera(frame, input)
            CameraSpoolInputKind.LEGACY_JPEG -> convertLegacyJpegCamera(frame, input)
        }
    }

    private fun convertRgbCamera(
        frame: FramePayload,
        input: ValidatedCameraSpoolInput,
    ): ConvertedCamera {
        val bitmap = Bitmap.createBitmap(
            rgb8ToArgb8888(input),
            input.width,
            input.height,
            Bitmap.Config.ARGB_8888,
        )
        return try {
            val jpeg = compressJpeg(bitmap)
            ConvertedCamera(
                buildPersistedCameraMetadata(
                    frame,
                    jpeg,
                    frame.intrinsics.takeIf { frame.hasIntrinsics() },
                ),
                jpeg,
            )
        } finally {
            bitmap.recycle()
        }
    }

    /** Retained only for the explicit legacy diagnostic source, never the production hot path. */
    private fun convertLegacyJpegCamera(
        frame: FramePayload,
        input: ValidatedCameraSpoolInput,
    ): ConvertedCamera {
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(input.bytes, 0, input.bytes.size))
        var scaled: Bitmap? = null
        var cropped: Bitmap? = null
        try {
            require(decoded.width == input.width && decoded.height == input.height)
            val transform = SquareAspectFillTransform.centered(decoded.width, decoded.height, OUTPUT_SIZE)
            scaled = if (decoded.width == transform.scaledWidth && decoded.height == transform.scaledHeight) {
                decoded
            } else {
                Bitmap.createScaledBitmap(decoded, transform.scaledWidth, transform.scaledHeight, true)
            }
            cropped = if (scaled.width == OUTPUT_SIZE && scaled.height == OUTPUT_SIZE) {
                scaled
            } else {
                Bitmap.createBitmap(scaled, transform.cropLeft, transform.cropTop, OUTPUT_SIZE, OUTPUT_SIZE)
            }
            val jpeg = compressJpeg(cropped)
            return ConvertedCamera(
                buildPersistedCameraMetadata(
                    frame,
                    jpeg,
                    frame.intrinsics.takeIf { frame.hasIntrinsics() }?.let {
                        transformIntrinsics(it, transform)
                    },
                ),
                jpeg,
            )
        } finally {
            if (cropped !== scaled && cropped !== decoded) cropped?.recycle()
            if (scaled !== decoded) scaled?.recycle()
            decoded.recycle()
        }
    }

    private fun compressJpeg(bitmap: Bitmap): ByteArray {
        val buffer = ByteArrayOutputStream(INITIAL_JPEG_CAPACITY)
        require(bitmap.compress(Bitmap.CompressFormat.JPEG, OUTPUT_JPEG_QUALITY, buffer))
        return buffer.toByteArray().also(::validatePersistedJpeg)
    }

    private fun transformIntrinsics(
        source: CameraIntrinsics,
        transform: SquareAspectFillTransform,
    ): CameraIntrinsics = source.toBuilder()
        .setFocalXPixels(source.focalXPixels * transform.scaleX)
        .setFocalYPixels(source.focalYPixels * transform.scaleY)
        .setPrincipalXPixels(source.principalXPixels * transform.scaleX - transform.cropLeft)
        .setPrincipalYPixels(source.principalYPixels * transform.scaleY - transform.cropTop)
        .setCalibratedWidth(OUTPUT_SIZE)
        .setCalibratedHeight(OUTPUT_SIZE)
        .build()

    private fun encodeWave(chunk: PcmAudioChunk): ByteArray {
        require(chunk.sampleRateHz in 8_000..48_000)
        require(chunk.channelCount in 1..2)
        require(chunk.pcm16LittleEndian.isNotEmpty())
        require(chunk.pcm16LittleEndian.size % (chunk.channelCount * Short.SIZE_BYTES) == 0)
        val dataSize = chunk.pcm16LittleEndian.size
        val output = ByteBuffer.allocate(WAVE_HEADER_BYTES + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        output.put("RIFF".toByteArray(Charsets.US_ASCII))
        output.putInt(36 + dataSize)
        output.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        output.putInt(16)
        output.putShort(1)
        output.putShort(chunk.channelCount.toShort())
        output.putInt(chunk.sampleRateHz)
        output.putInt(chunk.sampleRateHz * chunk.channelCount * Short.SIZE_BYTES)
        output.putShort((chunk.channelCount * Short.SIZE_BYTES).toShort())
        output.putShort(16)
        output.put("data".toByteArray(Charsets.US_ASCII))
        output.putInt(dataSize)
        output.put(chunk.pcm16LittleEndian)
        return output.array()
    }

    private fun resolveArtifact(relativePath: String): File {
        require(relativePath.isNotBlank() && !relativePath.startsWith('/') && ".." !in relativePath.split('/'))
        val file = File(root, relativePath).canonicalFile
        require(file.path.startsWith(root.canonicalPath + File.separator))
        return file
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        require(target.parentFile?.isDirectory == true)
        writeAtomic(AtomicFile(target), bytes)
        restrict(target)
    }

    private fun writeAtomic(target: AtomicFile, bytes: ByteArray) {
        val output = target.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            target.finishWrite(output)
        } catch (error: Throwable) {
            target.failWrite(output)
            throw error
        }
    }

    private fun restrict(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
        if (file.isDirectory) file.setExecutable(true, true)
    }

    private fun elapsedSince(startedNs: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startedNs).coerceAtLeast(0L)

    private data class ConvertedCamera(val metadata: FramePayload, val jpeg: ByteArray)

    companion object {
        const val ROOT_DIRECTORY = "machine-perception-spool"
        const val CAMERA_DIRECTORY = "camera"
        const val MICROPHONE_DIRECTORY = "microphone"
        const val MANIFEST_FILENAME = "manifest.json"
        private const val STATE_FILENAME = ".manifest-state.pb"
        private const val OUTPUT_SIZE = 640
        private const val OUTPUT_JPEG_QUALITY = 88
        private const val INITIAL_JPEG_CAPACITY = 256 * 1024
        private const val MAXIMUM_SOURCE_JPEG_BYTES = 20 * 1024 * 1024
        private const val MAXIMUM_OUTPUT_JPEG_BYTES = 2 * 1024 * 1024
        private const val MAXIMUM_RECORDS = 512
        private const val MAXIMUM_PAGE_RECORDS = 16
        private const val MAXIMUM_PAGE_JSON_BYTES = 60 * 1024
        private const val MAXIMUM_ARTIFACT_CHUNK_BYTES = 48 * 1024
        private const val MAXIMUM_ARTIFACT_BYTES = 64L * 1024 * 1024
        private const val WAVE_HEADER_BYTES = 44
        private const val MINIMUM_MANIFEST_FLUSH_INTERVAL_NS = 100_000_000L
        private val RECORD_ID_PATTERN = Regex("[a-z]+-[0-9]{1,20}-[0-9]{1,20}")
        private const val LOG_TAG = "ConceptFlowSpool"

        internal fun validateCameraInput(frame: FramePayload): ValidatedCameraSpoolInput {
            val descriptor = frame.image
            val bytes = frame.frameData.toByteArray()
            cameraInputRequire(
                descriptor.width > 0 && descriptor.height > 0,
                CameraSpoolInputRejection.INVALID_DIMENSIONS,
            )
            cameraInputRequire(
                bytes.isNotEmpty() && bytes.size <= MAXIMUM_SOURCE_JPEG_BYTES &&
                    descriptor.payloadBytes == bytes.size.toLong(),
                CameraSpoolInputRejection.PAYLOAD_SIZE_MISMATCH,
            )
            cameraInputRequire(
                descriptor.sha256.size() == SHA256_BYTES &&
                    MessageDigest.isEqual(sha256(bytes), descriptor.sha256.toByteArray()),
                CameraSpoolInputRejection.PAYLOAD_DIGEST_MISMATCH,
            )
            val kind = when (descriptor.encoding) {
                ImageEncoding.IMAGE_ENCODING_RGB8 -> {
                    cameraInputRequire(
                        descriptor.width == OUTPUT_SIZE && descriptor.height == OUTPUT_SIZE,
                        CameraSpoolInputRejection.INVALID_DIMENSIONS,
                    )
                    cameraInputRequire(
                        descriptor.mediaType == RGB8_MEDIA_TYPE,
                        CameraSpoolInputRejection.MEDIA_TYPE_MISMATCH,
                    )
                    val expectedStride = Math.multiplyExact(descriptor.width, RGB_CHANNELS)
                    cameraInputRequire(
                        descriptor.rowStrideBytes == expectedStride,
                        CameraSpoolInputRejection.ROW_STRIDE_MISMATCH,
                    )
                    cameraInputRequire(
                        bytes.size == Math.multiplyExact(expectedStride, descriptor.height),
                        CameraSpoolInputRejection.PAYLOAD_SIZE_MISMATCH,
                    )
                    CameraSpoolInputKind.PACKED_RGB8
                }
                ImageEncoding.IMAGE_ENCODING_JPEG -> {
                    cameraInputRequire(
                        descriptor.width <= MAXIMUM_SOURCE_DIMENSION &&
                            descriptor.height <= MAXIMUM_SOURCE_DIMENSION &&
                            descriptor.width.toLong() * descriptor.height <= MAXIMUM_SOURCE_PIXELS,
                        CameraSpoolInputRejection.INVALID_DIMENSIONS,
                    )
                    cameraInputRequire(
                        descriptor.mediaType == JPEG_MEDIA_TYPE,
                        CameraSpoolInputRejection.MEDIA_TYPE_MISMATCH,
                    )
                    cameraInputRequire(
                        descriptor.rowStrideBytes == 0,
                        CameraSpoolInputRejection.ROW_STRIDE_MISMATCH,
                    )
                    CameraSpoolInputKind.LEGACY_JPEG
                }
                else -> throw CameraSpoolInputException(CameraSpoolInputRejection.UNSUPPORTED_ENCODING)
            }
            return ValidatedCameraSpoolInput(
                kind,
                descriptor.width,
                descriptor.height,
                descriptor.rowStrideBytes,
                bytes,
            )
        }

        internal fun rgb8ToArgb8888(input: ValidatedCameraSpoolInput): IntArray {
            check(input.kind == CameraSpoolInputKind.PACKED_RGB8)
            val pixels = IntArray(Math.multiplyExact(input.width, input.height))
            var source = 0
            pixels.indices.forEach { index ->
                val red = input.bytes[source].toInt() and 0xff
                val green = input.bytes[source + 1].toInt() and 0xff
                val blue = input.bytes[source + 2].toInt() and 0xff
                pixels[index] = -0x1000000 or (red shl 16) or (green shl 8) or blue
                source += RGB_CHANNELS
            }
            return pixels
        }

        internal fun buildPersistedCameraMetadata(
            frame: FramePayload,
            jpeg: ByteArray,
            intrinsics: CameraIntrinsics?,
        ): FramePayload {
            validatePersistedJpeg(jpeg)
            val descriptor = frame.image.toBuilder()
                .setWidth(OUTPUT_SIZE)
                .setHeight(OUTPUT_SIZE)
                .setRowStrideBytes(0)
                .setEncoding(ImageEncoding.IMAGE_ENCODING_JPEG)
                .setMediaType(JPEG_MEDIA_TYPE)
                .setPayloadBytes(jpeg.size.toLong())
                .setSha256(ByteString.copyFrom(sha256(jpeg)))
                .build()
            return frame.toBuilder()
                .setImage(descriptor)
                .setFrameData(ByteString.copyFrom(jpeg))
                .apply {
                    if (intrinsics == null) clearIntrinsics() else this.intrinsics = intrinsics
                }
                .build()
        }

        private fun validatePersistedJpeg(jpeg: ByteArray) {
            require(jpeg.size in 4..MAXIMUM_OUTPUT_JPEG_BYTES)
            require(jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte())
            require(jpeg[jpeg.lastIndex - 1] == 0xff.toByte() && jpeg.last() == 0xd9.toByte())
        }

        private fun cameraInputRequire(condition: Boolean, rejection: CameraSpoolInputRejection) {
            if (!condition) throw CameraSpoolInputException(rejection)
        }

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        private const val RGB_CHANNELS = 3
        private const val RGB8_MEDIA_TYPE = "application/x-conceptflow-rgb8"
        private const val JPEG_MEDIA_TYPE = "image/jpeg"
        private const val MAXIMUM_SOURCE_DIMENSION = 4_096
        private const val MAXIMUM_SOURCE_PIXELS = 4_032L * 3_024L
        private const val SHA256_BYTES = 32
    }
}
