// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatTensorFidelityTest {
    @Test
    fun exactTensorPassesWithUnitCosineAndZeroError() {
        val tensor = floats(0f, 1f, -2f, 4f)
        val report = FloatTensorFidelity.compare(tensor, tensor.copyOf())
        assertEquals(4, report.finitePairCount)
        assertEquals(0, report.nonFiniteMismatchCount)
        assertEquals(0.0, report.meanAbsoluteError, 0.0)
        assertEquals(0.0, report.normalizedRootMeanSquareError, 0.0)
        assertEquals(1.0, report.cosineSimilarity, 0.0)
    }

    @Test
    fun changedTensorReportsFiniteErrorAndNonFiniteMismatch() {
        val report = FloatTensorFidelity.compare(
            floats(1f, 2f, Float.NaN, 4f),
            floats(1f, 3f, Float.POSITIVE_INFINITY, 4f),
        )
        assertEquals(3, report.finitePairCount)
        assertEquals(1, report.nonFiniteMismatchCount)
        assertTrue(report.meanAbsoluteError > 0.0)
        assertTrue(report.normalizedRootMeanSquareError > 0.0)
        assertTrue(report.cosineSimilarity < 1.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDifferentTensorLengths() {
        FloatTensorFidelity.compare(floats(1f), floats(1f, 2f))
    }

    private fun floats(vararg values: Float): ByteArray = ByteArray(values.size * 4).also { bytes ->
        values.forEachIndexed { index, value ->
            val bits = value.toRawBits()
            val offset = index * 4
            bytes[offset] = bits.toByte()
            bytes[offset + 1] = (bits ushr 8).toByte()
            bytes[offset + 2] = (bits ushr 16).toByte()
            bytes[offset + 3] = (bits ushr 24).toByte()
        }
    }
}
