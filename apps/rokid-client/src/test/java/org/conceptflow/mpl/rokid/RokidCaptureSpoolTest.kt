// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid

import com.google.protobuf.ByteString
import java.security.MessageDigest
import org.conceptflow.mpl.rokid.core.buildJpegFrame
import org.conceptflow.mpl.rokid.core.buildRgbFrame
import org.conceptflow.mpl.v1.CameraIntrinsics
import org.conceptflow.mpl.v1.ImageEncoding
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RokidCaptureSpoolTest {
    @Test
    fun productionRgb8InputPacksExactlyAndPersistsAsJpegMetadata() {
        val rgb = ByteArray(640 * 640 * 3)
        rgb[0] = 1
        rgb[1] = 2
        rgb[2] = 3
        rgb[rgb.lastIndex - 2] = 0xfe.toByte()
        rgb[rgb.lastIndex - 1] = 0x80.toByte()
        rgb[rgb.lastIndex] = 0x40
        val intrinsics = CameraIntrinsics.newBuilder()
            .setFocalXPixels(300.0)
            .setFocalYPixels(301.0)
            .setPrincipalXPixels(320.0)
            .setPrincipalYPixels(319.0)
            .setCalibratedWidth(640)
            .setCalibratedHeight(640)
            .build()
        val frame = buildRgbFrame(
            requestId = "request",
            sessionId = "camera-session",
            streamId = "camera",
            frameId = 7L,
            timestampNanos = 9L,
            wallTimeMillis = 1_000L,
            width = 640,
            height = 640,
            bytes = rgb,
            synthetic = false,
            intrinsics = intrinsics,
        )

        val input = RokidCaptureSpool.validateCameraInput(frame)
        val pixels = RokidCaptureSpool.rgb8ToArgb8888(input)
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())
        val metadata = RokidCaptureSpool.buildPersistedCameraMetadata(frame, jpeg, intrinsics)

        assertEquals(CameraSpoolInputKind.PACKED_RGB8, input.kind)
        assertEquals(1_920, input.rowStrideBytes)
        assertEquals(0xff010203.toInt(), pixels.first())
        assertEquals(0xfffe8040.toInt(), pixels.last())
        assertEquals(ImageEncoding.IMAGE_ENCODING_JPEG, metadata.image.encoding)
        assertEquals("image/jpeg", metadata.image.mediaType)
        assertEquals(640, metadata.image.width)
        assertEquals(640, metadata.image.height)
        assertEquals(0, metadata.image.rowStrideBytes)
        assertEquals(jpeg.size.toLong(), metadata.image.payloadBytes)
        assertArrayEquals(jpeg, metadata.frameData.toByteArray())
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(jpeg), metadata.image.sha256.toByteArray())
        assertEquals(intrinsics, metadata.intrinsics)
    }

    @Test
    fun legacyJpegInputRemainsExplicitlyRecognizedForDiagnosticCompatibility() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())
        val frame = buildJpegFrame(
            requestId = "request",
            sessionId = "camera-session",
            streamId = "camera",
            frameId = 1L,
            timestampNanos = 2L,
            wallTimeMillis = 1_000L,
            width = 1_920,
            height = 1_080,
            bytes = jpeg,
            synthetic = true,
        )

        assertEquals(CameraSpoolInputKind.LEGACY_JPEG, RokidCaptureSpool.validateCameraInput(frame).kind)
    }

    @Test
    fun mismatchedRgbDescriptorsAndPayloadsFailWithStableReasons() {
        val rgb = ByteArray(640 * 640 * 3)
        val frame = buildRgbFrame(
            "request",
            "camera-session",
            "camera",
            1L,
            2L,
            1_000L,
            640,
            640,
            rgb,
            false,
        )

        assertRejection(
            CameraSpoolInputRejection.ROW_STRIDE_MISMATCH,
            frame.toBuilder().setImage(frame.image.toBuilder().setRowStrideBytes(1_919)).build(),
        )
        assertRejection(
            CameraSpoolInputRejection.INVALID_DIMENSIONS,
            frame.toBuilder().setImage(frame.image.toBuilder().setWidth(648).setHeight(648)).build(),
        )
        assertRejection(
            CameraSpoolInputRejection.MEDIA_TYPE_MISMATCH,
            frame.toBuilder().setImage(frame.image.toBuilder().setMediaType("image/jpeg")).build(),
        )
        assertRejection(
            CameraSpoolInputRejection.PAYLOAD_SIZE_MISMATCH,
            frame.toBuilder().setFrameData(ByteString.copyFrom(rgb, 0, rgb.size - 1)).build(),
        )
        assertRejection(
            CameraSpoolInputRejection.PAYLOAD_DIGEST_MISMATCH,
            frame.toBuilder()
                .setImage(frame.image.toBuilder().setSha256(ByteString.copyFrom(ByteArray(32))))
                .build(),
        )
        assertRejection(
            CameraSpoolInputRejection.UNSUPPORTED_ENCODING,
            frame.toBuilder()
                .setImage(frame.image.toBuilder().setEncoding(ImageEncoding.IMAGE_ENCODING_RGBA8))
                .build(),
        )
    }

    private fun assertRejection(expected: CameraSpoolInputRejection, frame: org.conceptflow.mpl.v1.FramePayload) {
        val error = assertThrows(CameraSpoolInputException::class.java) {
            RokidCaptureSpool.validateCameraInput(frame)
        }
        assertEquals(expected, error.rejection)
    }
}
