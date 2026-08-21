// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveCaptureTest {
    @Test
    fun captureSelectionPrefersExactGateThenClosestAspectCompatibleSource() {
        val exact = PixelDimensions(1_920, 1_080)
        assertEquals(
            exact,
            selectClosestCaptureSize(
                listOf(PixelDimensions(4_032, 3_024), PixelDimensions(2_560, 1_440), exact),
            ),
        )

        assertEquals(
            PixelDimensions(2_560, 1_440),
            selectClosestCaptureSize(
                listOf(PixelDimensions(4_032, 3_024), PixelDimensions(1_440, 1_080), PixelDimensions(2_560, 1_440)),
            ),
        )
    }

    @Test
    fun aspectFitPreservesFourByThreeAndNeverUpscales() {
        assertEquals(PixelDimensions(1_440, 1_080), aspectFit(PixelDimensions(4_032, 3_024)))
        assertEquals(PixelDimensions(640, 480), aspectFit(PixelDimensions(640, 480)))
    }

    @Test
    fun lumaStatisticsTreatBytesAsUnsigned() {
        val result = FrameAnalyzer.analyze(
            LumaFrame(2, 2, byteArrayOf(0, 16, 128.toByte(), 255.toByte())),
            previous = null,
        )

        assertEquals(99.75, result.meanLuma, 0.0001)
        assertEquals(0.5, result.darkFraction, 0.0001)
    }

    @Test
    fun texturedFrameHasFarMoreFocusEnergyThanUniformFrame() {
        val uniform = FrameAnalyzer.analyze(frame(24, 18) { _, _ -> 100 }, null)
        val checker = FrameAnalyzer.analyze(checkerFrame(), null)

        assertEquals(0.0, uniform.laplacianVariance, 0.0)
        assertTrue(checker.laplacianVariance > 1_000.0)
    }

    @Test
    fun uniformExposureShiftDoesNotLookLikeMovement() {
        val before = frame(48, 36) { x, y -> if ((x + y) % 2 == 0) 60 else 180 }
        val after = frame(48, 36) { x, y -> if ((x + y) % 2 == 0) 72 else 192 }

        val result = FrameAnalyzer.analyze(after, before)

        assertEquals(0.0, result.motionScore, 0.0001)
    }

    @Test
    fun localizedChangeRemainsVisibleInSimilarityGate() {
        val before = checkerFrame()
        val after = frame(before.width, before.height) { x, y ->
            if (x in 12..17 && y in 9..14) 255 else before.unsigned(x, y)
        }

        assertTrue(FrameAnalyzer.analyze(after, before).motionScore >= 0.06)
    }

    @Test
    fun stableQualityFramesUseRelaxedTwoFrameCadence() {
        val gate = AdaptiveFrameGate()
        val image = checkerFrame()

        assertTrue(gate.evaluate(1_000_000_000L, image).emit)
        assertFalse(gate.evaluate(1_200_000_000L, image).emit)
        assertFalse(gate.evaluate(1_400_000_000L, image).emit)
        assertTrue(gate.evaluate(1_600_000_000L, image).emit)
        assertFalse(gate.evaluate(1_800_000_000L, image).emit)
        assertTrue(gate.evaluate(2_000_000_000L, image).emit)
    }

    @Test
    fun materialMovementUsesFiveFramesPerSecondDuringHoldThenRelaxes() {
        val gate = AdaptiveFrameGate()
        val still = checkerFrame()
        val moved = frame(still.width, still.height) { x, y ->
            if (x in 12..17 && y in 9..14) 255 else still.unsigned(x, y)
        }

        assertTrue(gate.evaluate(1_000_000_000L, still).emit)
        val movement = gate.evaluate(1_100_000_000L, moved)
        assertEquals(5.0, movement.targetFramesPerSecond, 0.0)
        assertFalse(movement.emit)
        assertTrue(gate.evaluate(1_200_000_000L, moved).emit)
        assertTrue(gate.evaluate(1_400_000_000L, moved).emit)
        assertEquals(5.0, gate.evaluate(2_600_000_000L, moved).targetFramesPerSecond, 0.0)
        val relaxed = gate.evaluate(2_600_000_001L, moved)
        assertEquals(2.0, relaxed.targetFramesPerSecond, 0.0)
        assertFalse(relaxed.emit)
    }

    @Test
    fun darknessAndBlurAreRejectedBeforeCadence() {
        val gate = AdaptiveFrameGate()
        val dark = frame(24, 18) { _, _ -> 4 }
        val brightUniform = frame(24, 18) { _, _ -> 128 }

        assertEquals(FrameDropReason.DARK, gate.evaluate(1L, dark).reason)
        assertEquals(FrameDropReason.BLURRY, gate.evaluate(2L, brightUniform).reason)
    }

    @Test
    fun timestampsAreStrictUntilResetStartsFreshTimeline() {
        val gate = AdaptiveFrameGate()
        val image = checkerFrame()
        assertTrue(gate.evaluate(10L, image).emit)
        assertThrows(IllegalArgumentException::class.java) { gate.evaluate(10L, image) }
        assertThrows(IllegalArgumentException::class.java) { gate.evaluate(9L, image) }

        gate.reset()
        assertTrue(gate.evaluate(1L, image).emit)
    }

    private fun checkerFrame(width: Int = 48, height: Int = 36): LumaFrame =
        frame(width, height) { x, y -> if ((x + y) % 2 == 0) 48 else 208 }

    private fun frame(width: Int, height: Int, value: (Int, Int) -> Int): LumaFrame =
        LumaFrame(
            width,
            height,
            ByteArray(width * height) { index ->
                value(index % width, index / width).coerceIn(0, 255).toByte()
            },
        )

    private fun LumaFrame.unsigned(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF
}
