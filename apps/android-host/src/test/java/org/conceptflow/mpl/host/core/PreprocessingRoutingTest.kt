// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.core

import com.google.protobuf.ByteString
import org.conceptflow.mpl.v1.ImageEncoding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreprocessingRoutingTest {
    @Test
    fun validatesCanonicalFrameLimitsAndDigest() {
        val preprocessor = BoundedFramePreprocessor(PreprocessingLimits(maxFrameBytes = 2_048))
        assertTrue(preprocessor.prepare(testFrame()) is PreprocessingResult.Ready)

        val invalidDigest = testFrame().toBuilder()
            .setImage(testFrame().image.toBuilder().setSha256(ByteString.copyFrom(ByteArray(32))))
            .build()
        assertEquals(
            PreprocessingRejection.DIGEST_MISMATCH,
            (preprocessor.prepare(invalidDigest) as PreprocessingResult.Rejected).reason,
        )
        val oversized = testFrame(bytes = ByteArray(5) { it.toByte() })
        assertEquals(
            PreprocessingRejection.OVERSIZE,
            (
                BoundedFramePreprocessor(PreprocessingLimits(maxFrameBytes = 4))
                    .prepare(oversized) as PreprocessingResult.Rejected
                ).reason,
        )
    }

    @Test
    fun acceptsExactCanonicalEncodingAndMediaTypeMatrix() {
        val preprocessor = BoundedFramePreprocessor(PreprocessingLimits(maxFrameBytes = 2_048))
        val frames = listOf(
            testFrame(
                bytes = ByteArray(12),
                width = 2,
                height = 2,
                rowStrideBytes = 6,
                encoding = ImageEncoding.IMAGE_ENCODING_RGB8,
                mediaType = "application/x-conceptflow-rgb8",
            ),
            testFrame(
                bytes = ByteArray(4),
                width = 2,
                height = 2,
                rowStrideBytes = 2,
                encoding = ImageEncoding.IMAGE_ENCODING_GRAY8,
                mediaType = "application/x-conceptflow-gray8",
            ),
            testFrame(
                bytes = ByteArray(6),
                width = 2,
                height = 2,
                rowStrideBytes = 2,
                encoding = ImageEncoding.IMAGE_ENCODING_YUV420_I420,
                mediaType = "application/x-conceptflow-i420",
            ),
            testFrame(bytes = ONE_PIXEL_JPEG),
            testFrame(bytes = ONE_PIXEL_PNG, encoding = ImageEncoding.IMAGE_ENCODING_PNG, mediaType = "image/png"),
        )

        frames.forEach { assertTrue(preprocessor.prepare(it) is PreprocessingResult.Ready) }
    }

    @Test
    fun rejectsMediaTypeAndRawStrideMismatches() {
        val preprocessor = BoundedFramePreprocessor(PreprocessingLimits(maxFrameBytes = 2_048))
        val badMediaTypes = listOf(
            testFrame(bytes = ByteArray(3), rowStrideBytes = 3, encoding = ImageEncoding.IMAGE_ENCODING_RGB8),
            testFrame(
                bytes = ByteArray(1),
                rowStrideBytes = 1,
                encoding = ImageEncoding.IMAGE_ENCODING_GRAY8,
                mediaType = "application/x-conceptflow-rgb8",
            ),
            testFrame(mediaType = "image/png"),
            testFrame(bytes = ONE_PIXEL_PNG, encoding = ImageEncoding.IMAGE_ENCODING_PNG, mediaType = "image/jpeg"),
        )
        badMediaTypes.forEach {
            assertEquals(
                PreprocessingRejection.UNSUPPORTED_ENCODING,
                (preprocessor.prepare(it) as PreprocessingResult.Rejected).reason,
            )
        }

        val shortStride = testFrame(
            bytes = ByteArray(12),
            width = 2,
            height = 2,
            rowStrideBytes = 5,
            encoding = ImageEncoding.IMAGE_ENCODING_RGB8,
            mediaType = "application/x-conceptflow-rgb8",
        )
        assertEquals(
            PreprocessingRejection.INVALID_STRIDE,
            (preprocessor.prepare(shortStride) as PreprocessingResult.Rejected).reason,
        )
        assertEquals(
            PreprocessingRejection.INVALID_STRIDE,
            (
                preprocessor.prepare(testFrame(rowStrideBytes = 1)) as PreprocessingResult.Rejected
                ).reason,
        )
    }

    @Test
    fun rejectsMalformedAndDimensionMismatchedPngAndJpegWithoutDecoding() {
        val preprocessor = BoundedFramePreprocessor(PreprocessingLimits(maxFrameBytes = 100_000))
        val corruptPng = ONE_PIXEL_PNG.copyOf().also { it[29] = (it[29].toInt() xor 0x01).toByte() }
        val excessiveJpegHeader = ByteArray(2 + 4 * 17_000 + 2).also { bytes ->
            bytes[0] = 0xFF.toByte()
            bytes[1] = 0xD8.toByte()
            repeat(17_000) { index ->
                val offset = 2 + index * 4
                bytes[offset] = 0xFF.toByte()
                bytes[offset + 1] = 0xE0.toByte()
                bytes[offset + 2] = 0x00
                bytes[offset + 3] = 0x02
            }
            bytes[bytes.lastIndex - 1] = 0xFF.toByte()
            bytes[bytes.lastIndex] = 0xD9.toByte()
        }
        val malformed = listOf(
            testFrame(bytes = ONE_PIXEL_PNG.copyOf(ONE_PIXEL_PNG.size - 1), encoding = ImageEncoding.IMAGE_ENCODING_PNG, mediaType = "image/png"),
            testFrame(bytes = corruptPng, encoding = ImageEncoding.IMAGE_ENCODING_PNG, mediaType = "image/png"),
            testFrame(bytes = ONE_PIXEL_JPEG.copyOf(ONE_PIXEL_JPEG.size - 1)),
            testFrame(bytes = excessiveJpegHeader),
        )
        malformed.forEach {
            assertEquals(
                PreprocessingRejection.MALFORMED_IMAGE,
                (preprocessor.prepare(it) as PreprocessingResult.Rejected).reason,
            )
        }

        val dimensionMismatches = listOf(
            testFrame(bytes = ONE_PIXEL_PNG, width = 2, encoding = ImageEncoding.IMAGE_ENCODING_PNG, mediaType = "image/png"),
            testFrame(bytes = ONE_PIXEL_JPEG, height = 2),
        )
        dimensionMismatches.forEach {
            assertEquals(
                PreprocessingRejection.MALFORMED_IMAGE,
                (preprocessor.prepare(it) as PreprocessingResult.Rejected).reason,
            )
        }
    }

    @Test
    fun routesWithoutDeviceBrandAssumptionsAndBoundsQueue() {
        val policy = RoutingPolicy(remoteByteLimitOnMeteredNetwork = 3)
        val frame = testFrame()
        assertEquals(
            ProcessingRoute.LOCAL,
            policy.choose(frame, RouteEnvironment(true, false, true)).route,
        )
        assertEquals(
            ProcessingRoute.DROP,
            policy.choose(frame, RouteEnvironment(false, true, true)).route,
        )
        assertEquals(
            ProcessingRoute.GRPC,
            policy.choose(frame, RouteEnvironment(false, true, false)).route,
        )

        val queue = BoundedFrameQueue(2)
        queue.offer(testFrame(1))
        queue.offer(testFrame(2))
        val offer = queue.offer(testFrame(3))
        assertEquals(1L, offer.evicted?.frameId)
        assertEquals(listOf(2L, 3L), listOf(queue.poll()?.frameId, queue.poll()?.frameId))
    }
}
