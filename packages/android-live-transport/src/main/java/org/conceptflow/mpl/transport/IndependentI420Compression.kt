// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import com.github.luben.zstd.Zstd
import com.google.protobuf.ByteString
import com.google.protobuf.UnsafeByteOperations
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import net.jpountz.lz4.LZ4Factory
import org.conceptflow.mpl.v1.ImageEncoding

data class IndependentI420WirePayload(
    val encoding: ImageEncoding,
    val mediaType: String,
    val bytes: ByteArray,
    val compressed: Boolean,
)

/**
 * Compresses each packed-I420 frame independently. A codec failure or a non-beneficial result
 * returns the original I420 payload, so freshness and decodability never depend on prior frames.
 */
class IndependentI420FrameEncoder(
    private val requestedEncoding: ImageEncoding,
) {
    private val codecEnabled = AtomicBoolean(true)

    init {
        require(requestedEncoding.isIndependentLosslessI420Encoding() ||
            requestedEncoding == ImageEncoding.IMAGE_ENCODING_YUV420_I420)
    }

    fun encode(i420: ByteArray): IndependentI420WirePayload {
        require(i420.isNotEmpty())
        if (requestedEncoding == ImageEncoding.IMAGE_ENCODING_YUV420_I420 || !codecEnabled.get()) {
            return raw(i420)
        }
        val compressed = try {
            IndependentI420Compression.compress(requestedEncoding, i420)
        } catch (_: RuntimeException) {
            codecEnabled.set(false)
            return raw(i420)
        } catch (_: LinkageError) {
            codecEnabled.set(false)
            return raw(i420)
        }
        return if (compressed.size < i420.size) {
            IndependentI420WirePayload(
                requestedEncoding,
                requestedEncoding.losslessI420MediaType(),
                compressed,
                true,
            )
        } else {
            raw(i420)
        }
    }

    val isCodecEnabled: Boolean get() = codecEnabled.get()

    private fun raw(i420: ByteArray) = IndependentI420WirePayload(
        ImageEncoding.IMAGE_ENCODING_YUV420_I420,
        I420_MEDIA_TYPE,
        i420,
        false,
    )
}

/** Strict bounded decoder used before frames enter the Android sensor timeline. */
object IndependentI420Compression {
    const val ZSTD_LEVEL = 1
    const val I420_MEDIA_TYPE = "application/x-conceptflow-i420"
    const val LZ4_MEDIA_TYPE = "application/x-conceptflow-i420-lz4-block"
    const val ZSTD_MEDIA_TYPE = "application/x-conceptflow-i420-zstd"
    const val MAXIMUM_I420_BYTES = 8 * 1_024 * 1_024

    private val lz4Factory by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { LZ4Factory.safeInstance() }

    fun expectedI420Bytes(width: Int, height: Int): Int {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "I420 dimensions must be positive and even"
        }
        val luma = Math.multiplyExact(width, height)
        return Math.addExact(luma, luma / 2).also {
            require(it <= MAXIMUM_I420_BYTES) { "I420 frame exceeds the decoded size bound" }
        }
    }

    fun compress(encoding: ImageEncoding, i420: ByteArray): ByteArray {
        require(i420.size <= MAXIMUM_I420_BYTES) { "I420 frame exceeds the input size bound" }
        return when (encoding) {
            ImageEncoding.IMAGE_ENCODING_YUV420_I420_LZ4_BLOCK -> {
                val compressor = lz4Factory.fastCompressor()
                val destination = ByteArray(compressor.maxCompressedLength(i420.size))
                val size = compressor.compress(i420, 0, i420.size, destination, 0, destination.size)
                destination.copyOf(size)
            }
            ImageEncoding.IMAGE_ENCODING_YUV420_I420_ZSTD -> {
                val destination = ByteArray(Zstd.compressBound(i420.size.toLong()).toInt())
                val size = Zstd.compress(destination, i420, ZSTD_LEVEL)
                require(!Zstd.isError(size)) { "Zstandard compression failed: ${Zstd.getErrorName(size)}" }
                destination.copyOf(size.toInt())
            }
            else -> throw IllegalArgumentException("encoding is not an independent lossless I420 codec")
        }
    }

    fun decompress(
        encoding: ImageEncoding,
        compressed: ByteArray,
        expectedBytes: Int,
    ): ByteArray {
        require(expectedBytes in 1..MAXIMUM_I420_BYTES) { "decoded I420 size is outside its bound" }
        require(compressed.isNotEmpty() && compressed.size <= MAXIMUM_I420_BYTES) {
            "compressed I420 payload is outside its bound"
        }
        val destination = ByteArray(expectedBytes)
        val produced = when (encoding) {
            ImageEncoding.IMAGE_ENCODING_YUV420_I420_LZ4_BLOCK -> {
                val consumed = lz4Factory.fastDecompressor()
                    .decompress(compressed, 0, destination, 0, expectedBytes)
                require(consumed == compressed.size) { "LZ4 block has trailing or missing data" }
                expectedBytes.toLong()
            }
            ImageEncoding.IMAGE_ENCODING_YUV420_I420_ZSTD -> {
                val size = Zstd.decompress(destination, compressed)
                require(!Zstd.isError(size)) { "Zstandard decompression failed: ${Zstd.getErrorName(size)}" }
                size
            }
            else -> throw IllegalArgumentException("encoding is not an independent lossless I420 codec")
        }
        require(produced == expectedBytes.toLong()) { "decoded I420 payload size does not match dimensions" }
        return destination
    }

    fun decodeFrame(frame: org.conceptflow.mpl.v1.FramePayload): org.conceptflow.mpl.v1.FramePayload {
        val encoding = frame.image.encoding
        require(encoding.isIndependentLosslessI420Encoding()) {
            "frame does not use an independent lossless I420 encoding"
        }
        require(frame.image.mediaType == encoding.losslessI420MediaType()) {
            "lossless I420 media type does not match its encoding"
        }
        require(frame.image.rowStrideBytes == frame.image.width) {
            "decoded I420 stride must be tightly packed"
        }
        require(frame.image.payloadBytes == frame.frameData.size().toLong()) {
            "lossless I420 wire size does not match its descriptor"
        }
        val wireBytes = frame.frameData.toByteArray()
        require(frame.image.sha256.size() == 32 && MessageDigest.isEqual(
            MessageDigest.getInstance("SHA-256").digest(wireBytes),
            frame.image.sha256.toByteArray(),
        )) { "lossless I420 wire digest does not match its descriptor" }
        val decoded = decompress(
            encoding,
            wireBytes,
            expectedI420Bytes(frame.image.width, frame.image.height),
        )
        return frame.toBuilder()
            .setImage(
                frame.image.toBuilder()
                    .setEncoding(ImageEncoding.IMAGE_ENCODING_YUV420_I420)
                    .setMediaType(I420_MEDIA_TYPE)
                    .setPayloadBytes(decoded.size.toLong())
                    .setSha256(ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(decoded))),
            )
            .setFrameData(UnsafeByteOperations.unsafeWrap(decoded))
            .build()
    }
}

fun ImageEncoding.isIndependentLosslessI420Encoding(): Boolean =
    this == ImageEncoding.IMAGE_ENCODING_YUV420_I420_LZ4_BLOCK ||
        this == ImageEncoding.IMAGE_ENCODING_YUV420_I420_ZSTD

fun ImageEncoding.losslessI420MediaType(): String = when (this) {
    ImageEncoding.IMAGE_ENCODING_YUV420_I420_LZ4_BLOCK -> IndependentI420Compression.LZ4_MEDIA_TYPE
    ImageEncoding.IMAGE_ENCODING_YUV420_I420_ZSTD -> IndependentI420Compression.ZSTD_MEDIA_TYPE
    else -> throw IllegalArgumentException("encoding is not an independent lossless I420 codec")
}

private const val I420_MEDIA_TYPE = IndependentI420Compression.I420_MEDIA_TYPE
