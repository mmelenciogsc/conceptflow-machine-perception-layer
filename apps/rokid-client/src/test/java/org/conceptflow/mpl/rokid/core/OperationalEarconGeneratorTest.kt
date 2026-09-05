// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import kotlin.math.ceil
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationalEarconGeneratorTest {
    @Test
    fun microphoneConsentCuesAreDistinctBoundedAndBinaurallyAudible() {
        val start = requireNotNull(
            OperationalEarconGenerator.generateOrNull(
                OperationalEarconGenerator.MICROPHONE_START_ID,
                OperationalEarconGenerator.MAX_LINEAR_GAIN.toFloat(),
            ),
        )
        val stop = requireNotNull(
            OperationalEarconGenerator.generateOrNull(
                OperationalEarconGenerator.MICROPHONE_STOP_ID,
                OperationalEarconGenerator.MAX_LINEAR_GAIN.toFloat(),
            ),
        )
        val peakLimit = ceil(Short.MAX_VALUE * OperationalEarconGenerator.MAX_LINEAR_GAIN).toInt()

        assertEquals(start.frameCount * 2, start.stereoPcm16.size)
        assertEquals(stop.frameCount * 2, stop.stereoPcm16.size)
        assertTrue(stop.frameCount > start.frameCount)
        assertTrue(start.stereoPcm16.any { it.toInt() != 0 })
        assertTrue(stop.stereoPcm16.any { it.toInt() != 0 })
        assertTrue(start.stereoPcm16.maxOf { max(it.toInt(), -it.toInt()) } <= peakLimit)
        assertTrue(stop.stereoPcm16.maxOf { max(it.toInt(), -it.toInt()) } <= peakLimit)
        assertEquals(0, start.stereoPcm16.first().toInt())
        assertEquals(0, start.stereoPcm16.last().toInt())
        assertFalse(start.stereoPcm16.contentEquals(stop.stereoPcm16))
        assertTrue(
            start.stereoPcm16.indices.step(2).all {
                start.stereoPcm16[it] == start.stereoPcm16[it + 1]
            },
        )
        assertTrue(
            stop.stereoPcm16.indices.step(2).all {
                stop.stereoPcm16[it] == stop.stereoPcm16[it + 1]
            },
        )
    }

    @Test
    fun unknownEarconIsNotReinterpretedAsConsentState() {
        assertEquals(null, OperationalEarconGenerator.generateOrNull("other", 0.5f))
    }

    @Test
    fun earlyStopIsSeparatedFromStartWhileNormalStopIsImmediate() {
        val startNs = 10_000_000_000L

        assertEquals(
            500L,
            OperationalEarconGenerator.stopCueDelayMillis(startNs, startNs + 850_000_000L),
        )
        assertEquals(
            0L,
            OperationalEarconGenerator.stopCueDelayMillis(startNs, startNs + 1_500_000_000L),
        )
        assertEquals(0L, OperationalEarconGenerator.stopCueDelayMillis(null, startNs))
        assertEquals(0L, OperationalEarconGenerator.stopCueDelayMillis(startNs, startNs - 1L))
    }
}
