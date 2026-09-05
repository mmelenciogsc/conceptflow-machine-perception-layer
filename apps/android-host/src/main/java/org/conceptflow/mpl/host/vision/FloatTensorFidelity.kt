// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import kotlin.math.abs
import kotlin.math.sqrt

data class FloatTensorFidelityReport(
    val elementCount: Int,
    val finitePairCount: Int,
    val nonFiniteMismatchCount: Int,
    val meanAbsoluteError: Double,
    val normalizedRootMeanSquareError: Double,
    val cosineSimilarity: Double,
)

/** Allocation-bounded comparison of two little-endian float32 tensors. */
object FloatTensorFidelity {
    fun compare(reference: ByteArray, candidate: ByteArray): FloatTensorFidelityReport {
        require(reference.isNotEmpty() && reference.size == candidate.size && reference.size % 4 == 0)
        var finitePairs = 0
        var nonFiniteMismatches = 0
        var absoluteError = 0.0
        var squaredError = 0.0
        var referenceEnergy = 0.0
        var candidateEnergy = 0.0
        var dotProduct = 0.0
        var offset = 0
        while (offset < reference.size) {
            val expected = readFloat(reference, offset)
            val actual = readFloat(candidate, offset)
            when {
                expected.isFinite() && actual.isFinite() -> {
                    val expectedDouble = expected.toDouble()
                    val actualDouble = actual.toDouble()
                    val difference = expectedDouble - actualDouble
                    finitePairs += 1
                    absoluteError += abs(difference)
                    squaredError += difference * difference
                    referenceEnergy += expectedDouble * expectedDouble
                    candidateEnergy += actualDouble * actualDouble
                    dotProduct += expectedDouble * actualDouble
                }
                expected.toRawBits() != actual.toRawBits() -> nonFiniteMismatches += 1
            }
            offset += 4
        }
        require(finitePairs > 0) { "tensor comparison requires at least one finite pair" }
        val rootMeanSquareError = sqrt(squaredError / finitePairs)
        val referenceRootMeanSquare = sqrt(referenceEnergy / finitePairs)
        val normalizedError = rootMeanSquareError / referenceRootMeanSquare.coerceAtLeast(MINIMUM_SCALE)
        val cosineDenominator = sqrt(referenceEnergy * candidateEnergy)
        val cosine = if (cosineDenominator <= MINIMUM_SCALE) {
            if (squaredError == 0.0) 1.0 else 0.0
        } else {
            (dotProduct / cosineDenominator).coerceIn(-1.0, 1.0)
        }
        return FloatTensorFidelityReport(
            elementCount = reference.size / 4,
            finitePairCount = finitePairs,
            nonFiniteMismatchCount = nonFiniteMismatches,
            meanAbsoluteError = absoluteError / finitePairs,
            normalizedRootMeanSquareError = normalizedError,
            cosineSimilarity = cosine,
        )
    }

    private fun readFloat(bytes: ByteArray, offset: Int): Float = Float.fromBits(
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24),
    )

    private const val MINIMUM_SCALE = 1e-12
}
