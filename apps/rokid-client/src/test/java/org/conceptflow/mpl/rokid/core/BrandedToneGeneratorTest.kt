// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import kotlin.math.ceil
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandedToneGeneratorTest {
    @Test
    fun tonesAreShortStereoProceduralAndLowGain() {
        val ready = BrandedToneGenerator.generate(BrandedToneKind.READY)
        val connection = BrandedToneGenerator.generate(BrandedToneKind.AUTHENTICATED_CONNECTION)
        val peakLimit = ceil(Short.MAX_VALUE * BrandedToneGenerator.MAX_LINEAR_GAIN).toInt()

        assertEquals(6_240, ready.frameCount)
        assertEquals(ready.frameCount * 2, ready.stereoPcm16.size)
        assertTrue(ready.stereoPcm16.any { it.toInt() != 0 })
        assertTrue(ready.stereoPcm16.maxOf { max(it.toInt(), -it.toInt()) } <= peakLimit)
        assertEquals(0, ready.stereoPcm16[0].toInt())
        assertEquals(0, ready.stereoPcm16.last().toInt())
        assertFalse(ready.stereoPcm16.contentEquals(connection.stereoPcm16))
    }

    @Test
    fun ambientBedIsBoundedStereoAndExactlyLoopable() {
        val bed = BrandedAmbientBedGenerator.generate()
        val peakLimit = ceil(Short.MAX_VALUE * BrandedAmbientBedGenerator.MAX_LINEAR_GAIN).toInt()

        assertEquals(96_000, bed.frameCount)
        assertEquals(bed.frameCount * 2, bed.stereoPcm16.size)
        assertTrue(bed.stereoPcm16.any { it.toInt() != 0 })
        assertTrue(bed.stereoPcm16.maxOf { max(it.toInt(), -it.toInt()) } <= peakLimit)
    }
}
