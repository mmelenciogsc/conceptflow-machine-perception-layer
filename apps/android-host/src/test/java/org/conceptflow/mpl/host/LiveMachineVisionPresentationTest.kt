// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMachineVisionPresentationTest {
    @Test
    fun `successful live execution supersedes the not-exercised readiness state`() {
        assertEquals(
            LiveQnnBackendEvidence.NOT_EXERCISED,
            liveQnnBackendEvidence(null, 0L),
        )
        assertEquals(
            LiveQnnBackendEvidence.HTP_EXECUTED,
            liveQnnBackendEvidence(LiveMachineVisionPhase.COMPLETE, 25L),
        )
    }

    @Test
    fun `initialization and failure remain distinct from successful HTP execution`() {
        assertEquals(
            LiveQnnBackendEvidence.INITIALIZING,
            liveQnnBackendEvidence(LiveMachineVisionPhase.OPENING_QNN_HTP, 0L),
        )
        assertEquals(
            LiveQnnBackendEvidence.QNN_FAILED,
            liveQnnBackendEvidence(LiveMachineVisionPhase.FAILED, 0L, "QNN_RUNTIME_LOAD_FAILED"),
        )
        assertEquals(
            LiveQnnBackendEvidence.LIVE_FAILED_BEFORE_QNN_EXECUTION,
            liveQnnBackendEvidence(LiveMachineVisionPhase.FAILED, 0L, "LINK_PROTOCOL"),
        )
    }

    @Test
    fun `terminal status publication is exactly once per controller run`() {
        val gate = LiveTerminalPublicationGate()
        var publications = 0

        assertTrue(gate.publishOnce { publications++ })
        assertFalse(gate.publishOnce { publications++ })
        assertEquals(1, publications)

        gate.reset()
        assertTrue(gate.publishOnce { publications++ })
        assertEquals(2, publications)
    }
}
