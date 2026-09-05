// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import kotlin.math.log10
import kotlin.math.sqrt

/** Content-free, deterministic camera fixture used for hardware codec fidelity validation. */
object DeterministicI420Fixture {
    const val WIDTH = 640
    const val HEIGHT = 640

    fun create(): ByteArray {
        val lumaBytes = WIDTH * HEIGHT
        val chromaWidth = WIDTH / 2
        val chromaHeight = HEIGHT / 2
        val chromaBytes = chromaWidth * chromaHeight
        return ByteArray(lumaBytes + 2 * chromaBytes).also { output ->
            for (y in 0 until HEIGHT) {
                for (x in 0 until WIDTH) {
                    val horizontal = x * 112 / (WIDTH - 1)
                    val vertical = y * 64 / (HEIGHT - 1)
                    val engineeringGrid = if (x % 80 < 3 || y % 80 < 3) 22 else 0
                    val centerPanel = if (x in 176 until 464 && y in 160 until 480) 26 else 0
                    val diagonal = if (((x + 2 * y) / 48) % 2 == 0) 8 else -8
                    output[y * WIDTH + x] = (32 + horizontal + vertical + engineeringGrid + centerPanel + diagonal)
                        .coerceIn(16, 235)
                        .toByte()
                }
            }
            for (y in 0 until chromaHeight) {
                for (x in 0 until chromaWidth) {
                    val index = y * chromaWidth + x
                    val leftRight = if (x < chromaWidth / 2) -18 else 18
                    val topBottom = if (y < chromaHeight / 2) 14 else -14
                    output[lumaBytes + index] = (128 + leftRight + topBottom).coerceIn(16, 240).toByte()
                    output[lumaBytes + chromaBytes + index] =
                        (128 - leftRight + topBottom).coerceIn(16, 240).toByte()
                }
            }
        }
    }
}

data class PlaneFidelity(
    val sampleCount: Int,
    val meanAbsoluteError: Double,
    val peakSignalToNoiseRatioDb: Double,
)

data class I420FidelityReport(
    val luma: PlaneFidelity,
    val chroma: PlaneFidelity,
    val overall: PlaneFidelity,
)

/** Exact packed-I420 comparison with plane-specific error reporting. */
object I420Fidelity {
    fun compare(reference: ByteArray, candidate: ByteArray, width: Int, height: Int): I420FidelityReport {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
        val lumaBytes = Math.multiplyExact(width, height)
        val expectedBytes = Math.addExact(lumaBytes, lumaBytes / 2)
        require(reference.size == expectedBytes && candidate.size == expectedBytes)
        return I420FidelityReport(
            luma = compareRange(reference, candidate, 0, lumaBytes),
            chroma = compareRange(reference, candidate, lumaBytes, expectedBytes),
            overall = compareRange(reference, candidate, 0, expectedBytes),
        )
    }

    private fun compareRange(
        reference: ByteArray,
        candidate: ByteArray,
        start: Int,
        end: Int,
    ): PlaneFidelity {
        var absoluteError = 0L
        var squaredError = 0.0
        for (index in start until end) {
            val difference = (reference[index].toInt() and 0xff) - (candidate[index].toInt() and 0xff)
            absoluteError += kotlin.math.abs(difference).toLong()
            squaredError += difference.toDouble() * difference
        }
        val count = end - start
        val meanAbsoluteError = absoluteError.toDouble() / count
        val meanSquaredError = squaredError / count
        val psnr = if (meanSquaredError == 0.0) {
            Double.POSITIVE_INFINITY
        } else {
            20.0 * log10(255.0 / sqrt(meanSquaredError))
        }
        return PlaneFidelity(count, meanAbsoluteError, psnr)
    }
}
