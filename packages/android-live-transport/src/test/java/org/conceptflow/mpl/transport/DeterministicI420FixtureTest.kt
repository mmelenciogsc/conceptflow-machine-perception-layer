// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicI420FixtureTest {
    @Test
    fun fixtureIsStablePackedI420() {
        val first = DeterministicI420Fixture.create()
        val second = DeterministicI420Fixture.create()

        assertEquals(640 * 640 * 3 / 2, first.size)
        assertTrue(first.contentEquals(second))
        assertTrue(first.toSet().size > 32)
    }

    @Test
    fun fidelityReportsExactAndChangedPlanes() {
        val reference = DeterministicI420Fixture.create()
        val exact = I420Fidelity.compare(reference, reference.copyOf(), 640, 640)
        assertEquals(0.0, exact.overall.meanAbsoluteError, 0.0)
        assertEquals(Double.POSITIVE_INFINITY, exact.overall.peakSignalToNoiseRatioDb, 0.0)

        val changed = reference.copyOf().also {
            it[0] = (it[0].toInt() + 4).toByte()
            it[640 * 640] = (it[640 * 640].toInt() - 3).toByte()
        }
        val report = I420Fidelity.compare(reference, changed, 640, 640)
        assertTrue(report.luma.meanAbsoluteError > 0.0)
        assertTrue(report.chroma.meanAbsoluteError > 0.0)
        assertTrue(report.overall.peakSignalToNoiseRatioDb.isFinite())
    }

    @Test(expected = IllegalArgumentException::class)
    fun fidelityRejectsWrongBufferSize() {
        I420Fidelity.compare(ByteArray(3), ByteArray(3), 640, 640)
    }
}
