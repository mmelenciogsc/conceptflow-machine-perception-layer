// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvcDecodeFaultGateTest {
    @Test
    fun oneArmProducesExactlyOneFailure() {
        val gate = AvcDecodeFaultGate()
        assertFalse(gate.consume())
        assertTrue(gate.arm())
        assertFalse(gate.arm())
        assertTrue(gate.consume())
        assertFalse(gate.consume())
    }

    @Test
    fun clearDisarmsPendingFailure() {
        val gate = AvcDecodeFaultGate()
        assertTrue(gate.arm())
        gate.clear()
        assertFalse(gate.consume())
    }
}
