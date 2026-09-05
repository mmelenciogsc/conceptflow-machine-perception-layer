// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.core

import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.ImageEncoding
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.zip.CRC32

data class PreprocessingLimits(
    val maxWidth: Int = 1920,
    val maxHeight: Int = 1080,
    val maxFrameBytes: Int = 1_048_576,
    val maxQueueDepth: Int = 3,
) {
    init {
        require(maxWidth in 1..4096)
        require(maxHeight in 1..4096)
        require(maxFrameBytes in 1..8 * 1024 * 1024)
        require(maxQueueDepth in 1..16)
    }
}

enum class PreprocessingRejection {
    MISSING_IDENTITY,
    UNSUPPORTED_ENCODING,
    INVALID_DIMENSIONS,
    INVALID_STRIDE,
    SIZE_MISMATCH,
    OVERSIZE,
    DIGEST_MISMATCH,
    MALFORMED_IMAGE,
}

sealed interface PreprocessingResult {
    data class Ready(val frame: FramePayload) : PreprocessingResult
    data class Rejected(val reason: PreprocessingRejection) : PreprocessingResult
}

class BoundedFramePreprocessor(private val limits: PreprocessingLimits) {
    fun prepare(frame: FramePayload): PreprocessingResult {
        val expectedMediaType = CANONICAL_MEDIA_TYPES[frame.image.encoding]
        val payloadSize = frame.frameData.size()
        val reason = when {
            frame.requestId.isBlank() || frame.sessionId.isBlank() || frame.streamId.isBlank() ->
                PreprocessingRejection.MISSING_IDENTITY
            expectedMediaType == null || frame.image.mediaType != expectedMediaType ->
                PreprocessingRejection.UNSUPPORTED_ENCODING
            frame.image.width !in 1..limits.maxWidth || frame.image.height !in 1..limits.maxHeight ->
                PreprocessingRejection.INVALID_DIMENSIONS
            payloadSize.toLong() != frame.image.payloadBytes || payloadSize == 0 ->
                PreprocessingRejection.SIZE_MISMATCH
            payloadSize > limits.maxFrameBytes -> PreprocessingRejection.OVERSIZE
            !hasValidStrideAndByteCount(frame) -> strideRejection(frame)
            !hasValidEncodedStructure(frame) -> PreprocessingRejection.MALFORMED_IMAGE
            frame.image.sha256.size() != 32 || !MessageDigest.isEqual(
                MessageDigest.getInstance("SHA-256").digest(frame.frameData.toByteArray()),
                frame.image.sha256.toByteArray(),
            ) -> PreprocessingRejection.DIGEST_MISMATCH
            else -> null
        }
        return if (reason == null) PreprocessingResult.Ready(frame) else PreprocessingResult.Rejected(reason)
    }

    private fun hasValidStrideAndByteCount(frame: FramePayload): Boolean {
        if (frame.image.encoding == ImageEncoding.IMAGE_ENCODING_YUV420_I420) {
            val width = frame.image.width.toLong()
            val height = frame.image.height.toLong()
            if (width % 2L != 0L || height % 2L != 0L ||
                Integer.toUnsignedLong(frame.image.rowStrideBytes) != width
            ) {
                return false
            }
            val lumaBytes = Math.multiplyExact(width, height)
            return Math.addExact(lumaBytes, lumaBytes / 2L) == frame.frameData.size().toLong()
        }
        val channels = when (frame.image.encoding) {
            ImageEncoding.IMAGE_ENCODING_RGB8 -> 3L
            ImageEncoding.IMAGE_ENCODING_GRAY8 -> 1L
            else -> return frame.image.rowStrideBytes == 0
        }
        val stride = Integer.toUnsignedLong(frame.image.rowStrideBytes)
        val minimumStride = frame.image.width.toLong() * channels
        return stride >= minimumStride && stride * frame.image.height.toLong() == frame.frameData.size().toLong()
    }

    private fun strideRejection(frame: FramePayload): PreprocessingRejection {
        val isRaw = frame.image.encoding == ImageEncoding.IMAGE_ENCODING_RGB8 ||
            frame.image.encoding == ImageEncoding.IMAGE_ENCODING_GRAY8 ||
            frame.image.encoding == ImageEncoding.IMAGE_ENCODING_YUV420_I420
        if (!isRaw || Integer.toUnsignedLong(frame.image.rowStrideBytes) < minimumRawStride(frame)) {
            return PreprocessingRejection.INVALID_STRIDE
        }
        return PreprocessingRejection.SIZE_MISMATCH
    }

    private fun minimumRawStride(frame: FramePayload): Long {
        val channels = if (frame.image.encoding == ImageEncoding.IMAGE_ENCODING_RGB8) 3L else 1L
        return frame.image.width.toLong() * channels
    }

    private fun hasValidEncodedStructure(frame: FramePayload): Boolean {
        val expectedDimensions = ImageDimensions(frame.image.width, frame.image.height)
        val actualDimensions = when (frame.image.encoding) {
            ImageEncoding.IMAGE_ENCODING_JPEG -> EncodedImageHeaders.jpegDimensions(frame.frameData.toByteArray())
            ImageEncoding.IMAGE_ENCODING_PNG -> EncodedImageHeaders.pngDimensions(frame.frameData.toByteArray())
            else -> return true
        }
        return actualDimensions == expectedDimensions
    }
}

private data class ImageDimensions(val width: Int, val height: Int)

private object EncodedImageHeaders {
    private val pngSignature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    private const val PNG_MAX_CHUNKS = 4_096
    private const val JPEG_MAX_HEADER_BYTES = 64 * 1024
    private const val JPEG_MAX_HEADER_MARKERS = 512
    private val jpegSupportedStartOfFrame = setOf(0xC0, 0xC1, 0xC2)
    private val jpegUnsupportedStartOfFrame = setOf(
        0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF,
    )

    fun pngDimensions(data: ByteArray): ImageDimensions? {
        if (data.size < 33 || !data.startsWith(pngSignature)) return null
        if (readUnsignedInt(data, 8) != 13L || !data.matchesAscii(12, "IHDR")) return null
        if (!crcMatches(data, typeOffset = 12, dataOffset = 16, dataLength = 13, crcOffset = 29)) return null

        val width = readUnsignedInt(data, 16)
        val height = readUnsignedInt(data, 20)
        if (width !in 1L..Int.MAX_VALUE.toLong() || height !in 1L..Int.MAX_VALUE.toLong()) return null
        val bitDepth = data.unsigned(24)
        val colorType = data.unsigned(25)
        val validDepth = when (colorType) {
            0 -> bitDepth in setOf(1, 2, 4, 8, 16)
            2 -> bitDepth == 8 || bitDepth == 16
            3 -> bitDepth in setOf(1, 2, 4, 8)
            4, 6 -> bitDepth == 8 || bitDepth == 16
            else -> false
        }
        if (!validDepth || data.unsigned(26) != 0 || data.unsigned(27) != 0 || data.unsigned(28) !in 0..1) {
            return null
        }

        var offset = 33
        var chunkCount = 1
        var sawImageData = false
        while (offset < data.size) {
            chunkCount += 1
            if (chunkCount > PNG_MAX_CHUNKS || data.size - offset < 12) return null
            val chunkLength = readUnsignedInt(data, offset)
            val chunkEnd = offset.toLong() + 12L + chunkLength
            if (chunkEnd > data.size.toLong() || chunkLength > Int.MAX_VALUE.toLong()) return null
            val dataLength = chunkLength.toInt()
            val typeOffset = offset + 4
            val chunkDataOffset = offset + 8
            val crcOffset = chunkDataOffset + dataLength
            if (!crcMatches(data, typeOffset, chunkDataOffset, dataLength, crcOffset)) return null
            if (data.matchesAscii(typeOffset, "IHDR")) return null
            if (data.matchesAscii(typeOffset, "IDAT")) sawImageData = true
            if (data.matchesAscii(typeOffset, "IEND")) {
                return if (dataLength == 0 && sawImageData && chunkEnd == data.size.toLong()) {
                    ImageDimensions(width.toInt(), height.toInt())
                } else {
                    null
                }
            }
            offset = chunkEnd.toInt()
        }
        return null
    }

    fun jpegDimensions(data: ByteArray): ImageDimensions? {
        if (data.size < 8 || data.unsigned(0) != 0xFF || data.unsigned(1) != 0xD8) return null
        if (data.unsigned(data.size - 2) != 0xFF || data.unsigned(data.size - 1) != 0xD9) return null

        var offset = 2
        var markerCount = 0
        var dimensions: ImageDimensions? = null
        var sawScan = false
        var inEntropyData = false
        while (offset < data.size) {
            val marker: Int
            if (inEntropyData) {
                val markerOffset = data.indexOfByte(0xFF, offset)
                if (markerOffset < 0 || markerOffset + 1 >= data.size) return null
                offset = markerOffset + 1
                while (offset < data.size && data.unsigned(offset) == 0xFF) offset += 1
                if (offset >= data.size) return null
                marker = data.unsigned(offset)
                offset += 1
                if (marker == 0x00 || marker in 0xD0..0xD7) continue
                inEntropyData = false
            } else {
                if (dimensions == null && offset > JPEG_MAX_HEADER_BYTES) return null
                if (data.unsigned(offset) != 0xFF) return null
                while (offset < data.size && data.unsigned(offset) == 0xFF) offset += 1
                if (offset >= data.size) return null
                marker = data.unsigned(offset)
                offset += 1
            }

            markerCount += 1
            if (markerCount > JPEG_MAX_HEADER_MARKERS || marker == 0x00 || marker == 0xD8) return null
            if (marker == 0xD9) {
                return dimensions.takeIf { sawScan && offset == data.size }
            }
            if (marker == 0x01 || marker in 0xD0..0xD7) continue
            if (offset + 2 > data.size) return null
            val segmentLength = data.readUnsignedShort(offset)
            if (segmentLength < 2 || offset + segmentLength > data.size) return null
            if (marker in jpegUnsupportedStartOfFrame) return null
            if (marker in jpegSupportedStartOfFrame) {
                if (dimensions != null || segmentLength < 8) return null
                val precision = data.unsigned(offset + 2)
                val height = data.readUnsignedShort(offset + 3)
                val width = data.readUnsignedShort(offset + 5)
                val components = data.unsigned(offset + 7)
                if (width == 0 || height == 0 || precision !in setOf(8, 12) ||
                    components !in setOf(1, 3, 4) || segmentLength != 8 + 3 * components
                ) {
                    return null
                }
                dimensions = ImageDimensions(width, height)
            }
            if (marker == 0xDA) {
                if (dimensions == null || segmentLength < 6) return null
                val scanComponents = data.unsigned(offset + 2)
                if (scanComponents == 0 || segmentLength != 6 + 2 * scanComponents) return null
                sawScan = true
                inEntropyData = true
            }
            offset += segmentLength
        }
        return null
    }

    private fun crcMatches(
        data: ByteArray,
        typeOffset: Int,
        dataOffset: Int,
        dataLength: Int,
        crcOffset: Int,
    ): Boolean {
        val crc = CRC32()
        crc.update(data, typeOffset, 4)
        crc.update(data, dataOffset, dataLength)
        return crc.value == readUnsignedInt(data, crcOffset)
    }

    private fun readUnsignedInt(data: ByteArray, offset: Int): Long =
        (data.unsigned(offset).toLong() shl 24) or
            (data.unsigned(offset + 1).toLong() shl 16) or
            (data.unsigned(offset + 2).toLong() shl 8) or
            data.unsigned(offset + 3).toLong()

    private fun ByteArray.readUnsignedShort(offset: Int): Int =
        (unsigned(offset) shl 8) or unsigned(offset + 1)

    private fun ByteArray.unsigned(offset: Int): Int = this[offset].toInt() and 0xFF

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.matchesAscii(offset: Int, value: String): Boolean =
        offset >= 0 && offset + value.length <= size && value.indices.all { this[offset + it].toInt() == value[it].code }

    private fun ByteArray.indexOfByte(value: Int, startIndex: Int): Int {
        for (index in startIndex until size) {
            if (unsigned(index) == value) return index
        }
        return -1
    }
}

private val CANONICAL_MEDIA_TYPES = mapOf(
    ImageEncoding.IMAGE_ENCODING_RGB8 to "application/x-conceptflow-rgb8",
    ImageEncoding.IMAGE_ENCODING_GRAY8 to "application/x-conceptflow-gray8",
    ImageEncoding.IMAGE_ENCODING_YUV420_I420 to "application/x-conceptflow-i420",
    ImageEncoding.IMAGE_ENCODING_JPEG to "image/jpeg",
    ImageEncoding.IMAGE_ENCODING_PNG to "image/png",
)

enum class ProcessingRoute {
    LOCAL,
    GRPC,
    DROP,
}

data class RouteEnvironment(
    val localProcessorAvailable: Boolean,
    val validatedNetwork: Boolean,
    val meteredNetwork: Boolean,
)

data class RouteDecision(val route: ProcessingRoute, val reason: String)

class RoutingPolicy(private val remoteByteLimitOnMeteredNetwork: Int = 256 * 1024) {
    init {
        require(remoteByteLimitOnMeteredNetwork > 0)
    }

    fun choose(frame: FramePayload, environment: RouteEnvironment): RouteDecision = when {
        environment.localProcessorAvailable -> RouteDecision(ProcessingRoute.LOCAL, "local capability available")
        !environment.validatedNetwork -> RouteDecision(ProcessingRoute.DROP, "no validated processing route")
        environment.meteredNetwork && frame.frameData.size() > remoteByteLimitOnMeteredNetwork ->
            RouteDecision(ProcessingRoute.DROP, "frame exceeds metered-network policy")
        else -> RouteDecision(ProcessingRoute.GRPC, "validated network route")
    }
}

data class QueueOffer(val accepted: Boolean, val evicted: FramePayload? = null)

class BoundedFrameQueue(private val capacity: Int) {
    private val frames = ArrayDeque<FramePayload>()

    init {
        require(capacity in 1..16)
    }

    @Synchronized
    fun offer(frame: FramePayload): QueueOffer {
        val evicted = if (frames.size == capacity) frames.removeFirst() else null
        frames.addLast(frame)
        return QueueOffer(accepted = true, evicted = evicted)
    }

    @Synchronized
    fun poll(): FramePayload? = if (frames.isEmpty()) null else frames.removeFirst()

    @Synchronized
    fun size(): Int = frames.size
}
