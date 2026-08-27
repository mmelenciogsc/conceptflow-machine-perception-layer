// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.hardware

import java.nio.ByteBuffer
import org.conceptflow.mpl.rokid.core.FrameDropReason
import org.conceptflow.mpl.rokid.core.PixelDimensions
import org.conceptflow.mpl.rokid.core.SquareAspectFillTransform
import org.conceptflow.mpl.rokid.core.buildRgbFrame
import org.conceptflow.mpl.v1.ImageEncoding
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveYuv420ProcessorTest {
    @Test
    fun borrowedByteBufferPlaneHonorsItsInitialPosition() {
        val buffer = ByteBuffer.wrap(byteArrayOf(99, 99, 10, 11, 12, 13)).apply { position(2) }
        val plane = ByteBufferYuv420Plane(buffer, rowStride = 2, pixelStride = 1)

        assertEquals(4, plane.byteCount)
        assertEquals(10, plane.unsignedAt(0, 0))
        assertEquals(13, plane.unsignedAt(1, 1))
    }

    @Test
    fun paddedRowsAndInterleavedChromaStridesConvertDeterministically() {
        val frame = solidYuvFrame(
            width = 4,
            height = 4,
            yValue = 82,
            uValue = 90,
            vValue = 240,
            yRowStride = 6,
            uvRowStride = 6,
            uvPixelStride = 2,
        )

        val rgb = Yuv420RgbConverter.toRgb8(frame, SquareAspectFillTransform.centered(4, 4, 4))

        assertEquals(4 * 4 * 3, rgb.size)
        rgb.indices.step(3).forEach { offset ->
            assertEquals(255, rgb[offset].toInt() and 0xff)
            assertEquals(1, rgb[offset + 1].toInt() and 0xff)
            assertEquals(0, rgb[offset + 2].toInt() and 0xff)
        }
    }

    @Test
    fun invalidPlaneCoverageFailsClosed() {
        val shortY = ByteArrayYuv420Plane(ByteArray(15), rowStride = 4, pixelStride = 1)
        val chroma = ByteArrayYuv420Plane(ByteArray(4), rowStride = 2, pixelStride = 1)

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            Yuv420Frame(PixelDimensions(4, 4), shortY, chroma, chroma)
        }
    }

    @Test
    fun directLumaAnalysisExpandsBt601LimitedRangeWithoutReadingChroma() {
        val frame = Yuv420Frame(
            dimensions = PixelDimensions(3, 1),
            y = ByteArrayYuv420Plane(byteArrayOf(0, 16, 235.toByte()), 3, 1),
            u = NoReadPlane(byteCount = 2, rowStride = 2, pixelStride = 1),
            v = NoReadPlane(byteCount = 2, rowStride = 2, pixelStride = 1),
        )

        val luma = Yuv420RgbConverter.toLumaFrame(frame, PixelDimensions(3, 1))

        assertArrayEquals(byteArrayOf(0, 0, 255.toByte()), luma.pixels)
    }

    @Test
    fun optimizedRgbMatchesBilinearReferenceForPaddedPatternedPlanes() {
        val frame = patternedStridedYuvFrame(width = 9, height = 7)
        val countedY = CountingPlane(frame.y)
        val countedU = CountingPlane(frame.u)
        val countedV = CountingPlane(frame.v)
        val countedFrame = Yuv420Frame(frame.dimensions, countedY, countedU, countedV)
        val transform = SquareAspectFillTransform.centered(9, 7, 6)

        val optimized = Yuv420RgbConverter.toRgb8(countedFrame, transform)
        val reference = referenceRgb8(frame, transform)

        assertArrayEquals(reference, optimized)
        assertTrue(countedY.reads + countedU.reads + countedV.reads < 6 * 6 * 12)
    }

    @Test
    fun cachedSamplingPlanNeverReusesRowsFromThePreviousFrame() {
        val transform = SquareAspectFillTransform.centered(8, 8, 7)
        val plan = Yuv420RgbSamplingPlan.aspectFill(PixelDimensions(8, 8), transform)
        val first = solidYuvFrame(8, 8, yValue = 32, uValue = 128, vValue = 128)
        val second = solidYuvFrame(8, 8, yValue = 220, uValue = 128, vValue = 128)

        Yuv420RgbConverter.toRgb8(first, plan)
        val reusedPlan = Yuv420RgbConverter.toRgb8(second, plan)
        val freshPlan = Yuv420RgbConverter.toRgb8(second, transform)

        assertArrayEquals(freshPlan, reusedPlan)
        assertTrue((reusedPlan.first().toInt() and 0xff) > 200)
    }

    @Test
    fun productionSizeGoldenHashMatchesTheIndependentKotlinReference() {
        val frame = patternedStridedYuvFrame(width = 648, height = 648)
        val transform = SquareAspectFillTransform.centered(648, 648, 640)

        val reference = referenceRgb8(frame, transform)

        assertEquals(7_979_620_236_997_200_776L, fnv1a64(reference))
    }

    @Test
    fun nativeSquareFrameProducesTheExistingRgbTransportContract() {
        val processor = AdaptiveYuv420Processor()
        val frame = checkerYuvFrame(648, 648)

        val processed = processor.process(frame, 1_000_000_000L)

        assertTrue(processed.decision.emit)
        assertEquals(PixelDimensions(648, 648), processed.inputDimensions)
        assertEquals(PixelDimensions(640, 640), processed.outputDimensions)
        assertEquals(RgbConversionBackend.KOTLIN_REFERENCE, processed.rgbConversionBackend)
        val rgb = checkNotNull(processed.rgb8)
        assertEquals(640 * 640 * 3, rgb.size)
        val payload = buildRgbFrame(
            requestId = "camera-1",
            sessionId = "camera-session",
            streamId = "camera2-yuv-rgb8",
            frameId = 1L,
            timestampNanos = 1_000_000_000L,
            wallTimeMillis = 1L,
            width = processed.outputDimensions.width,
            height = processed.outputDimensions.height,
            bytes = rgb,
            synthetic = false,
        )
        assertEquals(ImageEncoding.IMAGE_ENCODING_RGB8, payload.image.encoding)
        assertEquals(640, payload.image.width)
        assertEquals(640, payload.image.height)
        assertEquals(1_920, payload.image.rowStrideBytes)
        assertEquals("application/x-conceptflow-rgb8", payload.image.mediaType)
    }

    @Test
    fun yuvPathKeepsProtectedDarknessCadenceAndMotionState() {
        val processor = AdaptiveYuv420Processor(
            analysisGate = PixelDimensions(8, 8),
            outputSize = 8,
        )
        val dark = solidYuvFrame(8, 8, yValue = 16, uValue = 128, vValue = 128)
        val still = checkerYuvFrame(8, 8)
        val moved = invertedCheckerYuvFrame(8, 8)

        val rejected = processor.process(dark, 1L)
        assertEquals(FrameDropReason.DARK, rejected.decision.reason)
        assertNull(rejected.rgb8)
        assertNull(rejected.rgbConversionBackend)

        processor.reset()
        val first = processor.process(still, 1_000_000_000L)
        assertTrue(first.decision.emit)
        assertEquals(3.0, first.decision.targetFramesPerSecond, 0.0)

        val cadenceDrop = processor.process(still, 1_200_000_000L)
        assertFalse(cadenceDrop.decision.emit)
        assertEquals(FrameDropReason.CADENCE_SIMILAR, cadenceDrop.decision.reason)

        val motion = processor.process(moved, 1_300_000_000L)
        assertEquals(5.0, motion.decision.targetFramesPerSecond, 0.0)
    }

    private fun checkerYuvFrame(width: Int, height: Int): Yuv420Frame =
        patternedYuvFrame(width, height) { x, y -> if ((x / 4 + y / 4) % 2 == 0) 48 else 208 }

    private fun invertedCheckerYuvFrame(width: Int, height: Int): Yuv420Frame =
        patternedYuvFrame(width, height) { x, y -> if ((x / 4 + y / 4) % 2 == 0) 208 else 48 }

    private fun patternedYuvFrame(
        width: Int,
        height: Int,
        yValue: (Int, Int) -> Int,
    ): Yuv420Frame {
        val y = ByteArray(width * height) { index -> yValue(index % width, index / width).toByte() }
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val neutral = ByteArray(chromaWidth * chromaHeight) { 128.toByte() }
        return Yuv420Frame(
            PixelDimensions(width, height),
            ByteArrayYuv420Plane(y, width, 1),
            ByteArrayYuv420Plane(neutral.copyOf(), chromaWidth, 1),
            ByteArrayYuv420Plane(neutral, chromaWidth, 1),
        )
    }

    private fun solidYuvFrame(
        width: Int,
        height: Int,
        yValue: Int,
        uValue: Int,
        vValue: Int,
        yRowStride: Int = width,
        uvRowStride: Int = (width + 1) / 2,
        uvPixelStride: Int = 1,
    ): Yuv420Frame {
        val y = ByteArray(minimumYuvPlaneBytes(width, height, yRowStride, 1))
        repeat(height) { row ->
            repeat(width) { column -> y[row * yRowStride + column] = yValue.toByte() }
        }
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val u = ByteArray(minimumYuvPlaneBytes(chromaWidth, chromaHeight, uvRowStride, uvPixelStride))
        val v = ByteArray(u.size)
        repeat(chromaHeight) { row ->
            repeat(chromaWidth) { column ->
                val index = row * uvRowStride + column * uvPixelStride
                u[index] = uValue.toByte()
                v[index] = vValue.toByte()
            }
        }
        return Yuv420Frame(
            PixelDimensions(width, height),
            ByteArrayYuv420Plane(y, yRowStride, 1),
            ByteArrayYuv420Plane(u, uvRowStride, uvPixelStride),
            ByteArrayYuv420Plane(v, uvRowStride, uvPixelStride),
        )
    }

    private fun patternedStridedYuvFrame(width: Int, height: Int): Yuv420Frame {
        val yRowStride = width + 5
        val y = ByteArray(minimumYuvPlaneBytes(width, height, yRowStride, 1))
        repeat(height) { row ->
            repeat(width) { column ->
                y[row * yRowStride + column] = (16 + (column * 17 + row * 29) % 220).toByte()
            }
        }
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val chromaRowStride = chromaWidth * 2 + 3
        val u = ByteArray(minimumYuvPlaneBytes(chromaWidth, chromaHeight, chromaRowStride, 2))
        val v = ByteArray(u.size)
        repeat(chromaHeight) { row ->
            repeat(chromaWidth) { column ->
                val offset = row * chromaRowStride + column * 2
                u[offset] = (16 + (column * 31 + row * 23) % 225).toByte()
                v[offset] = (16 + (column * 11 + row * 41) % 225).toByte()
            }
        }
        return Yuv420Frame(
            PixelDimensions(width, height),
            ByteArrayYuv420Plane(y, yRowStride, 1),
            ByteArrayYuv420Plane(u, chromaRowStride, 2),
            ByteArrayYuv420Plane(v, chromaRowStride, 2),
        )
    }

    private fun referenceRgb8(
        frame: Yuv420Frame,
        transform: SquareAspectFillTransform,
    ): ByteArray {
        val output = ByteArray(transform.outputSize * transform.outputSize * 3)
        val chromaWidth = (frame.dimensions.width + 1) / 2
        val chromaHeight = (frame.dimensions.height + 1) / 2
        var offset = 0
        repeat(transform.outputSize) { y ->
            repeat(transform.outputSize) { x ->
                val scaledX = transform.cropLeft + x
                val scaledY = transform.cropTop + y
                val luma = referenceSample(
                    frame.y,
                    frame.dimensions.width,
                    frame.dimensions.height,
                    referenceCoordinate(scaledX, transform.scaledWidth, frame.dimensions.width),
                    referenceCoordinate(scaledY, transform.scaledHeight, frame.dimensions.height),
                )
                val u = referenceSample(
                    frame.u,
                    chromaWidth,
                    chromaHeight,
                    referenceCoordinate(scaledX, transform.scaledWidth, chromaWidth),
                    referenceCoordinate(scaledY, transform.scaledHeight, chromaHeight),
                )
                val v = referenceSample(
                    frame.v,
                    chromaWidth,
                    chromaHeight,
                    referenceCoordinate(scaledX, transform.scaledWidth, chromaWidth),
                    referenceCoordinate(scaledY, transform.scaledHeight, chromaHeight),
                )
                val luminance = (luma - 16).coerceAtLeast(0)
                val blueDifference = u - 128
                val redDifference = v - 128
                output[offset++] = (((298 * luminance + 409 * redDifference + 128) shr 8)
                    .coerceIn(0, 255)).toByte()
                output[offset++] = (
                    (298 * luminance - 100 * blueDifference - 208 * redDifference + 128) shr 8
                    ).coerceIn(0, 255).toByte()
                output[offset++] = (((298 * luminance + 516 * blueDifference + 128) shr 8)
                    .coerceIn(0, 255)).toByte()
            }
        }
        return output
    }

    private fun fnv1a64(bytes: ByteArray): Long {
        var hash = -3_750_763_034_362_895_579L
        bytes.forEach { value ->
            hash = hash xor (value.toLong() and 0xffL)
            hash *= 1_099_511_628_211L
        }
        return hash
    }

    private fun referenceCoordinate(index: Int, outputSize: Int, sourceSize: Int): Long {
        val one = 1L shl 16
        val centered = ((2L * index + 1L) * sourceSize * one) / (2L * outputSize) - one / 2L
        return centered.coerceIn(0L, (sourceSize - 1L) * one)
    }

    private fun referenceSample(
        plane: Yuv420Plane,
        width: Int,
        height: Int,
        xFixed: Long,
        yFixed: Long,
    ): Int {
        val shift = 16
        val one = 1L shl shift
        val x0 = (xFixed shr shift).toInt()
        val y0 = (yFixed shr shift).toInt()
        val x1 = minOf(x0 + 1, width - 1)
        val y1 = minOf(y0 + 1, height - 1)
        val xWeight = xFixed and (one - 1L)
        val yWeight = yFixed and (one - 1L)
        val top = plane.unsignedAt(x0, y0) * (one - xWeight) + plane.unsignedAt(x1, y0) * xWeight
        val bottom = plane.unsignedAt(x0, y1) * (one - xWeight) + plane.unsignedAt(x1, y1) * xWeight
        return ((top * (one - yWeight) + bottom * yWeight + (1L shl 31)) shr 32).toInt()
    }

    private class CountingPlane(private val delegate: Yuv420Plane) : Yuv420Plane {
        override val rowStride: Int get() = delegate.rowStride
        override val pixelStride: Int get() = delegate.pixelStride
        override val byteCount: Int get() = delegate.byteCount
        var reads = 0
            private set

        override fun unsignedAt(x: Int, y: Int): Int {
            reads += 1
            return delegate.unsignedAt(x, y)
        }
    }

    private class NoReadPlane(
        override val byteCount: Int,
        override val rowStride: Int,
        override val pixelStride: Int,
    ) : Yuv420Plane {
        override fun unsignedAt(x: Int, y: Int): Int = error("chroma must not be read for gate luma")
    }
}
