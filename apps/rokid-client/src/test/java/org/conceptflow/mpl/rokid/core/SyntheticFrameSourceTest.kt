// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.conceptflow.mpl.v1.CameraIntrinsics
import org.conceptflow.mpl.v1.FramePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticFrameSourceTest {
    @Test
    fun idsAndTimestampsRemainMonotonicWhenClockRepeats() {
        val source = SyntheticFrameSource(MonotonicClock { 50L }, WallClock { 1_000L })
        val observed = mutableListOf<FramePayload>()
        source.start(listener(observed))

        val first = source.capture()
        val second = source.capture()

        assertEquals(listOf(1L, 2L), observed.map { it.frameId })
        assertTrue(second.captureMonotonicTimestampNs > first.captureMonotonicTimestampNs)
        assertEquals(1, first.image.width)
        assertEquals(1, first.image.height)
        assertTrue(first.frameData.size() > 4)
        val validator = FrameValidator(FrameLimits())
        assertNull(validator.validate(first))
        assertNull(validator.validate(second))
        source.close()
    }

    @Test
    fun strictLimitsAndOrderingAreRejected() {
        val limits = FrameLimits(maxWidth = 8, maxHeight = 8, maxJpegBytes = 4)
        val source = SyntheticFrameSource(MonotonicClock { 1L }, WallClock { 1L }, limits)
        source.start(listener(mutableListOf()))
        val frame = source.capture(byteArrayOf(1, 2, 3, 4))
        assertThrows(IllegalArgumentException::class.java) { source.capture(ByteArray(5)) }

        val validator = FrameValidator(limits)
        assertNull(validator.validate(frame))
        assertEquals(FrameRejection.NON_MONOTONIC_ID, validator.validate(frame))
    }

    @Test
    fun freshnessIsPartitionedBySessionAndStreamAndBounded() {
        val validator = FrameValidator(FrameLimits(), maxStreamHistories = 2)
        val firstSession = frame(frameId = 10L, timestamp = 100L, session = "first", stream = "camera")
        assertNull(validator.validate(firstSession))
        assertEquals(
            FrameRejection.NON_MONOTONIC_ID,
            validator.validate(frame(frameId = 10L, timestamp = 101L, session = "first", stream = "camera")),
        )
        assertNull(validator.validate(frame(frameId = 1L, timestamp = 1L, session = "second", stream = "camera")))
        assertNull(validator.validate(frame(frameId = 1L, timestamp = 1L, session = "first", stream = "depth")))
        assertEquals(2, validator.historyCount())

        assertEquals(1, validator.resetSession("second"))
        assertNull(validator.validate(frame(frameId = 1L, timestamp = 1L, session = "first", stream = "camera")))
        assertEquals(2, validator.historyCount())
        validator.reset()
        assertEquals(0, validator.historyCount())
    }

    @Test
    fun jpegFrameCarriesOnlyExplicitlyProvidedCameraIntrinsics() {
        val intrinsics = CameraIntrinsics.newBuilder()
            .setFocalXPixels(960.0)
            .setFocalYPixels(960.0)
            .setPrincipalXPixels(960.0)
            .setPrincipalYPixels(540.0)
            .setCalibratedWidth(1_920)
            .setCalibratedHeight(1_080)
            .build()

        val calibrated = buildJpegFrame(
            requestId = "calibrated",
            sessionId = "session",
            streamId = "camera",
            frameId = 1L,
            timestampNanos = 1L,
            wallTimeMillis = 1L,
            width = 1_920,
            height = 1_080,
            bytes = byteArrayOf(1),
            synthetic = false,
            intrinsics = intrinsics,
        )
        val uncalibrated = buildJpegFrame(
            requestId = "uncalibrated",
            sessionId = "session",
            streamId = "camera",
            frameId = 2L,
            timestampNanos = 2L,
            wallTimeMillis = 2L,
            width = 1_920,
            height = 1_080,
            bytes = byteArrayOf(2),
            synthetic = false,
        )

        assertTrue(calibrated.hasIntrinsics())
        assertEquals(intrinsics, calibrated.intrinsics)
        assertTrue(!uncalibrated.hasIntrinsics())
    }

    private fun frame(
        frameId: Long,
        timestamp: Long,
        session: String,
        stream: String,
    ): FramePayload = buildJpegFrame(
        requestId = "request-$session-$stream-$frameId-$timestamp",
        sessionId = session,
        streamId = stream,
        frameId = frameId,
        timestampNanos = timestamp,
        wallTimeMillis = 1L,
        width = 1,
        height = 1,
        bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()),
        synthetic = true,
    )

    private fun listener(frames: MutableList<FramePayload>) = object : FrameSource.Listener {
        override fun onFrame(frame: FramePayload) {
            frames += frame
        }

        override fun onError(message: String) = Unit
    }
}
