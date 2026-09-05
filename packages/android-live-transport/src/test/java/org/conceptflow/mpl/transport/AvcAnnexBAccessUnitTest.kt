// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AvcAnnexBAccessUnitTest {
    @Test
    fun `accepts mixed start codes with SPS PPS and IDR`() {
        val bytes = byteArrayOf(
            0, 0, 0, 1, 0x67, 1, 2,
            0, 0, 1, 0x68, 3,
            0, 0, 0, 1, 0x65, 4, 5,
        )
        val result = AvcAnnexBAccessUnit.requireIndependent(bytes)
        assertEquals(listOf(7, 8, 5), result.nalUnitTypes)
        assertTrue(result.sequenceParameterSet!!.contentEquals(byteArrayOf(0, 0, 0, 1, 0x67, 1, 2)))
    }

    @Test
    fun `rejects dependent access unit`() {
        assertThrows(IllegalArgumentException::class.java) {
            AvcAnnexBAccessUnit.requireIndependent(byteArrayOf(0, 0, 0, 1, 0x61, 1, 2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AvcAnnexBAccessUnit.requireIndependent(
                byteArrayOf(9, 0, 0, 0, 1, 0x67, 0, 0, 1, 0x68, 0, 0, 1, 0x65),
            )
        }
    }
}
