// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host

import org.conceptflow.mpl.host.vision.EnvironmentSelectionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidNodeRestartPolicyTest {
    @Test
    fun `guardian recovery delay is bounded and does not poll continuously`() {
        assertEquals(1_000L, AndroidNodeGuardianRecoveryPolicy.REBIND_DELAY_MILLIS)
    }

    @Test
    fun `restores an explicitly enabled automatic node`() {
        val restored = AndroidNodeRestartPolicy.restore(
            enabled = true,
            serializedEnvironmentMode = EnvironmentSelectionMode.AUTOMATIC.name,
        )

        assertTrue(restored.enabled)
        assertEquals(EnvironmentSelectionMode.AUTOMATIC, restored.environmentMode)
    }

    @Test
    fun `preserves an explicitly selected depth environment`() {
        assertEquals(
            EnvironmentSelectionMode.FORCE_INDOOR,
            AndroidNodeRestartPolicy.restore(true, "FORCE_INDOOR").environmentMode,
        )
        assertEquals(
            EnvironmentSelectionMode.FORCE_OUTDOOR,
            AndroidNodeRestartPolicy.restore(true, "FORCE_OUTDOOR").environmentMode,
        )
    }

    @Test
    fun `invalid persisted mode fails closed to automatic selection`() {
        val restored = AndroidNodeRestartPolicy.restore(
            enabled = false,
            serializedEnvironmentMode = "unsupported",
        )

        assertFalse(restored.enabled)
        assertEquals(EnvironmentSelectionMode.AUTOMATIC, restored.environmentMode)
    }
}
