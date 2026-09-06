// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import com.google.protobuf.ByteString
import java.security.MessageDigest
import java.util.Random
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.ImageDescriptor
import org.conceptflow.mpl.v1.ImageEncoding
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IndependentI420CompressionTest {
    @Test
    fun lz4RoundTripIsExactAndIndependent() {
        assertExactIndependentRoundTrip(ImageEncoding.IMAGE_ENCODING_YUV420_I420_LZ4_BLOCK)
    }

    @Test
    fun zstdLevelOneRoundTripIsExactAndIndependent() {
        assertExactIndependentRoundTrip(ImageEncoding.IMAGE_ENCODING_YUV420_I420_ZSTD)
    }

    @Test
    fun incompressibleFrameFallsBackToRawI420() {
        val random = ByteArray(RAW_BYTES).also { Random(29L).nextBytes(it) }
        listOf(
            ImageEncoding.IMAGE_ENCODING_YUV420_I420_LZ4_BLOCK,
            ImageEncoding.IMAGE_ENCODING_YUV420_I420_ZSTD,
        ).forEach { encoding ->
            val wire = IndependentI420FrameEncoder(encoding).encode(random)

            assertFalse(wire.compressed)
            assertEquals(ImageEncoding.IMAGE_ENCODING_YUV420_I420, wire.encoding)
            assertTrue(wire.bytes === random)
        }
    }

    @Test
    fun frameDecoderRestoresCanonicalI420DescriptorAndPayload() {
        val source = compressibleFrame(17)
        val wire = IndependentI420FrameEncoder(
            ImageEncoding.IMAGE_ENCODING_YUV420_I420_ZSTD,
        ).encode(source)
        assertTrue(wire.compressed)
        val encoded = frame(wire)

        val decoded = IndependentI420Compression.decodeFrame(encoded)

        assertArrayEquals(source, decoded.frameData.toByteArray())
        assertEquals(ImageEncoding.IMAGE_ENCODING_YUV420_I420, decoded.image.encoding)
        assertEquals(IndependentI420Compression.I420_MEDIA_TYPE, decoded.image.mediaType)
        assertEquals(RAW_BYTES.toLong(), decoded.image.payloadBytes)
        assertEquals(WIDTH, decoded.image.rowStrideBytes)
        assertArrayEquals(
            MessageDigest.getInstance("SHA-256").digest(source),
            decoded.image.sha256.toByteArray(),
        )
        assertEquals(encoded.frameId, decoded.frameId)
        assertEquals(encoded.captureMonotonicTimestampNs, decoded.captureMonotonicTimestampNs)
    }

    @Test
    fun frameDecoderRejectsWireDigestMismatch() {
        val source = compressibleFrame(3)
        val wire = IndependentI420FrameEncoder(
            ImageEncoding.IMAGE_ENCODING_YUV420_I420_LZ4_BLOCK,
        ).encode(source)
        val encoded = frame(wire).toBuilder()
            .setFrameData(ByteString.copyFrom(wire.bytes.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }))
            .build()

        assertThrows(IllegalArgumentException::class.java) {
            IndependentI420Compression.decodeFrame(encoded)
        }
    }

    private fun assertExactIndependentRoundTrip(encoding: ImageEncoding) {
        val first = compressibleFrame(11)
        val second = compressibleFrame(83)
        val firstWire = IndependentI420FrameEncoder(encoding).encode(first)
        val secondWire = IndependentI420FrameEncoder(encoding).encode(second)
        assertTrue(firstWire.compressed)
        assertTrue(secondWire.compressed)
        assertArrayEquals(
            second,
            IndependentI420Compression.decompress(encoding, secondWire.bytes, RAW_BYTES),
        )
        assertArrayEquals(
            first,
            IndependentI420Compression.decompress(encoding, firstWire.bytes, RAW_BYTES),
        )
    }

    private fun compressibleFrame(seed: Int): ByteArray = ByteArray(RAW_BYTES) { index ->
        ((index / 640 + index % 640 / 16 + seed) and 0xff).toByte()
    }

    private fun frame(wire: IndependentI420WirePayload): FramePayload = FramePayload.newBuilder()
        .setRequestId("frame-7")
        .setSessionId("session")
        .setStreamId("camera")
        .setFrameId(7)
        .setCaptureMonotonicTimestampNs(9_000)
        .setImage(
            ImageDescriptor.newBuilder()
                .setWidth(WIDTH)
                .setHeight(HEIGHT)
                .setRowStrideBytes(WIDTH)
                .setEncoding(wire.encoding)
                .setMediaType(wire.mediaType)
                .setPayloadBytes(wire.bytes.size.toLong())
                .setSha256(ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(wire.bytes))),
        )
        .setFrameData(ByteString.copyFrom(wire.bytes))
        .build()

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 640
        const val RAW_BYTES = WIDTH * HEIGHT * 3 / 2
    }
}
