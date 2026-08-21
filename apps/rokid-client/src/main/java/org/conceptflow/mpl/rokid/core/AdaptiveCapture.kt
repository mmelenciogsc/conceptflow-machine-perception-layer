// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class PixelDimensions(val width: Int, val height: Int) {
    init {
        require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) {
            "Pixel dimensions must be between 1 and $MAX_DIMENSION"
        }
    }

    val area: Long get() = width.toLong() * height.toLong()

    companion object {
        private const val MAX_DIMENSION = 8_192
    }
}

fun aspectFit(
    source: PixelDimensions,
    gate: PixelDimensions = PixelDimensions(1_920, 1_080),
): PixelDimensions {
    val scale = min(
        1.0,
        min(gate.width.toDouble() / source.width, gate.height.toDouble() / source.height),
    )
    return PixelDimensions(
        width = floor(source.width * scale).toInt().coerceIn(1, gate.width),
        height = floor(source.height * scale).toInt().coerceIn(1, gate.height),
    )
}

fun selectClosestCaptureSize(
    candidates: Collection<PixelDimensions>,
    gate: PixelDimensions = PixelDimensions(1_920, 1_080),
): PixelDimensions? {
    if (candidates.isEmpty()) return null
    candidates.firstOrNull { it == gate }?.let { return it }
    val covering = candidates.filter { it.width >= gate.width && it.height >= gate.height }
    return if (covering.isNotEmpty()) {
        covering.minWithOrNull(
            compareBy<PixelDimensions> { aspectError(it, gate) }
                .thenBy { it.area - gate.area }
                .thenBy { it.width }
                .thenBy { it.height },
        )
    } else {
        candidates.minWithOrNull(
            compareBy<PixelDimensions> { aspectError(it, gate) }
                .thenByDescending { it.area }
                .thenBy { it.width }
                .thenBy { it.height },
        )
    }
}

private fun aspectError(size: PixelDimensions, target: PixelDimensions): Double =
    abs(size.width.toDouble() / size.height - target.width.toDouble() / target.height)

data class LumaFrame(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(width in 1..MAX_ANALYSIS_DIMENSION && height in 1..MAX_ANALYSIS_DIMENSION) {
            "Analysis dimensions must be bounded"
        }
        require(pixels.size == width * height) { "Luma data does not match its dimensions" }
    }

    companion object {
        private const val MAX_ANALYSIS_DIMENSION = 512
    }
}

data class FrameAnalysis(
    val meanLuma: Double,
    val darkFraction: Double,
    val laplacianVariance: Double,
    val motionScore: Double,
)

object FrameAnalyzer {
    private const val DARK_PIXEL_MAXIMUM = 16
    private const val CHANGED_PIXEL_MINIMUM = 12.0
    private const val GRID_COLUMNS = 8
    private const val GRID_ROWS = 6

    fun analyze(current: LumaFrame, previous: LumaFrame?): FrameAnalysis {
        val count = current.pixels.size
        var lumaSum = 0L
        var darkPixels = 0
        for (pixel in current.pixels) {
            val luma = pixel.toInt() and 0xFF
            lumaSum += luma
            if (luma <= DARK_PIXEL_MAXIMUM) darkPixels += 1
        }
        val meanLuma = lumaSum.toDouble() / count
        return FrameAnalysis(
            meanLuma = meanLuma,
            darkFraction = darkPixels.toDouble() / count,
            laplacianVariance = laplacianVariance(current),
            motionScore = motionScore(current, previous),
        )
    }

    private fun laplacianVariance(frame: LumaFrame): Double {
        if (frame.width < 3 || frame.height < 3) return 0.0
        var sum = 0.0
        var squaredSum = 0.0
        var count = 0
        for (y in 1 until frame.height - 1) {
            val row = y * frame.width
            for (x in 1 until frame.width - 1) {
                val center = frame.pixels[row + x].toInt() and 0xFF
                val laplacian = 4 * center -
                    (frame.pixels[row + x - 1].toInt() and 0xFF) -
                    (frame.pixels[row + x + 1].toInt() and 0xFF) -
                    (frame.pixels[row - frame.width + x].toInt() and 0xFF) -
                    (frame.pixels[row + frame.width + x].toInt() and 0xFF)
                sum += laplacian
                squaredSum += laplacian.toDouble() * laplacian
                count += 1
            }
        }
        val mean = sum / count
        return max(0.0, squaredSum / count - mean * mean)
    }

    private fun motionScore(current: LumaFrame, previous: LumaFrame?): Double {
        if (previous == null || previous.width != current.width || previous.height != current.height) return 0.0
        val count = current.pixels.size
        var signedDeltaSum = 0L
        for (index in 0 until count) {
            signedDeltaSum += (current.pixels[index].toInt() and 0xFF) -
                (previous.pixels[index].toInt() and 0xFF)
        }
        val exposureDelta = signedDeltaSum.toDouble() / count
        val columns = min(GRID_COLUMNS, current.width)
        val rows = min(GRID_ROWS, current.height)
        val blockSums = DoubleArray(columns * rows)
        val blockCounts = IntArray(columns * rows)
        var residualSum = 0.0
        var changed = 0
        for (y in 0 until current.height) {
            for (x in 0 until current.width) {
                val index = y * current.width + x
                val delta = (current.pixels[index].toInt() and 0xFF) -
                    (previous.pixels[index].toInt() and 0xFF)
                val residual = abs(delta - exposureDelta)
                residualSum += residual
                if (residual >= CHANGED_PIXEL_MINIMUM) changed += 1
                val block = (y * rows / current.height) * columns + (x * columns / current.width)
                blockSums[block] += residual
                blockCounts[block] += 1
            }
        }
        val meanResidual = residualSum / count / 255.0
        val changedFraction = changed.toDouble() / count
        val peakBlock = blockSums.indices.maxOf { index -> blockSums[index] / blockCounts[index] } / 255.0
        return max(max(meanResidual * 2.0, changedFraction), peakBlock * 0.5).coerceIn(0.0, 1.0)
    }
}

enum class FrameDropReason { DARK, BLURRY, CADENCE_SIMILAR }

data class AdaptiveFrameConfig(
    val relaxedFramesPerSecond: Double = 2.0,
    val motionFramesPerSecond: Double = 5.0,
    val minimumMeanLuma: Double = 18.0,
    val maximumDarkFraction: Double = 0.92,
    val minimumLaplacianVariance: Double = 60.0,
    val materialMotionThreshold: Double = 0.06,
    val motionHoldNanos: Long = 1_500_000_000L,
) {
    init {
        require(relaxedFramesPerSecond.isFinite() && motionFramesPerSecond.isFinite())
        require(relaxedFramesPerSecond > 0.0 && relaxedFramesPerSecond <= motionFramesPerSecond)
        require(motionFramesPerSecond <= 10.0)
        require(minimumMeanLuma.isFinite() && minimumMeanLuma in 0.0..255.0)
        require(maximumDarkFraction.isFinite() && maximumDarkFraction in 0.0..1.0)
        require(minimumLaplacianVariance.isFinite() && minimumLaplacianVariance >= 0.0)
        require(materialMotionThreshold.isFinite() && materialMotionThreshold in 0.0..1.0)
        require(motionHoldNanos >= 0L)
    }
}

data class AdaptiveFrameDecision(
    val emit: Boolean,
    val reason: FrameDropReason?,
    val targetFramesPerSecond: Double,
    val analysis: FrameAnalysis,
)

class AdaptiveFrameGate(private val config: AdaptiveFrameConfig = AdaptiveFrameConfig()) {
    private var previous: LumaFrame? = null
    private var lastTimestampNanos = 0L
    private var lastEmissionNanos = 0L
    private var nextEmissionNanos = 0L
    private var motionUntilNanos = 0L
    private var motionTierActive = false

    @Synchronized
    fun evaluate(timestampNanos: Long, current: LumaFrame): AdaptiveFrameDecision {
        require(timestampNanos > 0L && timestampNanos > lastTimestampNanos) {
            "Frame timestamps must be positive and strictly increasing"
        }
        val analysis = FrameAnalyzer.analyze(current, previous)
        previous = LumaFrame(current.width, current.height, current.pixels.copyOf())
        lastTimestampNanos = timestampNanos
        if (analysis.motionScore >= config.materialMotionThreshold) {
            motionUntilNanos = max(motionUntilNanos, saturatingAdd(timestampNanos, config.motionHoldNanos))
        }
        val useMotionTier = timestampNanos <= motionUntilNanos
        val targetFps = if (useMotionTier) {
            config.motionFramesPerSecond
        } else {
            config.relaxedFramesPerSecond
        }
        val targetPeriodNanos = floor(1_000_000_000.0 / targetFps).toLong()
        if (lastEmissionNanos != 0L && useMotionTier != motionTierActive) {
            val transitionDue = saturatingAdd(lastEmissionNanos, targetPeriodNanos)
            nextEmissionNanos = if (useMotionTier) {
                min(nextEmissionNanos, transitionDue)
            } else {
                max(nextEmissionNanos, transitionDue)
            }
        }
        motionTierActive = useMotionTier
        val reason = when {
            analysis.meanLuma < config.minimumMeanLuma ||
                analysis.darkFraction > config.maximumDarkFraction -> FrameDropReason.DARK
            analysis.laplacianVariance < config.minimumLaplacianVariance -> FrameDropReason.BLURRY
            nextEmissionNanos != 0L && timestampNanos < nextEmissionNanos -> {
                FrameDropReason.CADENCE_SIMILAR
            }
            else -> null
        }
        if (reason == null) {
            lastEmissionNanos = timestampNanos
            nextEmissionNanos = advanceDue(nextEmissionNanos, timestampNanos, targetPeriodNanos)
        }
        return AdaptiveFrameDecision(reason == null, reason, targetFps, analysis)
    }

    @Synchronized
    fun reset() {
        previous = null
        lastTimestampNanos = 0L
        lastEmissionNanos = 0L
        nextEmissionNanos = 0L
        motionUntilNanos = 0L
        motionTierActive = false
    }
}

private fun saturatingAdd(value: Long, increment: Long): Long =
    if (Long.MAX_VALUE - value < increment) Long.MAX_VALUE else value + increment

private fun advanceDue(currentDue: Long, timestampNanos: Long, periodNanos: Long): Long {
    if (currentDue == 0L) return saturatingAdd(timestampNanos, periodNanos)
    if (currentDue > timestampNanos) return currentDue
    val periods = (timestampNanos - currentDue) / periodNanos + 1L
    return if (periods > (Long.MAX_VALUE - currentDue) / periodNanos) {
        Long.MAX_VALUE
    } else {
        currentDue + periods * periodNanos
    }
}
