// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SquareAspectFillTransformTest {
    @Test
    fun `1290 by 1080 scales to about 764 wide then center crops 640 square`() {
        val transform = SquareAspectFillTransform.centered(1_290, 1_080)

        assertEquals(764, transform.scaledWidth)
        assertEquals(640, transform.scaledHeight)
        assertEquals(62, transform.cropLeft)
        assertEquals(0, transform.cropTop)
        assertEquals(640, transform.outputSize)
    }

    @Test
    fun `native 648 square scales directly to the 640 transport size`() {
        val transform = SquareAspectFillTransform.centered(648, 648)

        assertEquals(640, transform.scaledWidth)
        assertEquals(640, transform.scaledHeight)
        assertEquals(0, transform.cropLeft)
        assertEquals(0, transform.cropTop)
        assertEquals(640, transform.outputSize)
        assertEquals(640.0 / 648.0, transform.scaleX, 0.0)
        assertEquals(640.0 / 648.0, transform.scaleY, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `portrait input fails closed rather than padding or stretching`() {
        SquareAspectFillTransform.centered(1_080, 1_290)
    }
}
