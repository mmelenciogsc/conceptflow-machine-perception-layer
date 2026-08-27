// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host

import org.conceptflow.mpl.host.vision.SemanticDepthCadenceTier
import org.conceptflow.mpl.host.vision.VisionFrame
import org.conceptflow.mpl.host.vision.HtpExecutionLease
import org.conceptflow.mpl.host.vision.HtpLeaseAcquisition
import org.conceptflow.mpl.host.vision.HtpLeaseWorkload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class LiveMachineVisionPresentationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `preparation and dispatch exceptions cannot retain HTP ownership`() {
        val lease = HtpExecutionLease(temporaryFolder.newFile("prepared-dispatch.lock"))

        expectIllegalState {
            prepareThenDispatchHtp(
                lease, 0L, { false },
                prepare = { error("pre-dispatch failure") },
                dispatch = { _: Unit -> Unit },
            )
        }
        acquireAndClose(lease)

        expectIllegalState {
            prepareThenDispatchHtp(
                lease, 0L, { false },
                prepare = { "ready" },
                dispatch = { error("dispatch failure") },
            )
        }
        acquireAndClose(lease)
    }
    @Test
    fun `controller frame gate admits only model cadence and resets for a new session`() {
        val gate = LiveHtpFrameAdmissionGate()
        val decisions = listOf(0L, 334_000_000L, 668_000_000L, 1_002_000_000L)
            .mapIndexed { index, timestamp ->
                gate.evaluate(frame(index + 1L, timestamp), emptyList(), true, true)
            }

        assertTrue(decisions[0] is LiveModelFrameDisposition.AdmitForHtp)
        assertTrue(decisions[1] is LiveModelFrameDisposition.PredictionOnly)
        assertTrue(decisions[2] is LiveModelFrameDisposition.PredictionOnly)
        val stable = decisions[3] as LiveModelFrameDisposition.AdmitForHtp
        assertEquals(SemanticDepthCadenceTier.STABLE, stable.cadenceTier)
        assertTrue(stable.allowOpportunisticVlm)

        gate.reset()
        assertTrue(
            gate.evaluate(frame(1L, 0L), emptyList(), true, true) is
                LiveModelFrameDisposition.AdmitForHtp,
        )
    }

    private fun acquireAndClose(lease: HtpExecutionLease) {
        val acquisition = lease.tryAcquire(HtpLeaseWorkload.QNN, 0L)
        assertTrue(acquisition is HtpLeaseAcquisition.Acquired)
        (acquisition as HtpLeaseAcquisition.Acquired).handle.close()
    }

    private fun expectIllegalState(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalStateException")
        } catch (_: IllegalStateException) {
            Unit
        }
    }

    @Test
    fun `live phases have concise nonvisual waiting capturing and stopped states`() {
        assertEquals("Waiting for glasses", accessibleLiveMachineVisionPhase(LiveMachineVisionPhase.LISTENING))
        assertEquals("Capturing", accessibleLiveMachineVisionPhase(LiveMachineVisionPhase.STREAMING))
        assertEquals("Stopped", accessibleLiveMachineVisionPhase(LiveMachineVisionPhase.STOPPED))
        assertEquals(
            "Stopped after completion",
            accessibleLiveMachineVisionPhase(LiveMachineVisionPhase.COMPLETE),
        )
    }

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
    fun `microphone control requires authenticated streaming and no active window`() {
        LiveMachineVisionPhase.entries
            .filterNot { it == LiveMachineVisionPhase.STREAMING }
            .forEach { phase ->
                assertFalse(liveMicrophoneControlEnabled(phase, LiveMicrophonePhase.IDLE))
            }
        assertTrue(
            liveMicrophoneControlEnabled(LiveMachineVisionPhase.STREAMING, LiveMicrophonePhase.IDLE),
        )
        assertTrue(
            liveMicrophoneControlEnabled(LiveMachineVisionPhase.STREAMING, LiveMicrophonePhase.COMPLETE),
        )
        assertTrue(
            liveMicrophoneControlEnabled(LiveMachineVisionPhase.STREAMING, LiveMicrophonePhase.REJECTED),
        )
        assertFalse(
            liveMicrophoneControlEnabled(LiveMachineVisionPhase.STREAMING, LiveMicrophonePhase.REQUESTING),
        )
        assertFalse(
            liveMicrophoneControlEnabled(LiveMachineVisionPhase.STREAMING, LiveMicrophonePhase.ACTIVE),
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

    private fun frame(id: Long, timestampNanos: Long) = VisionFrame(
        id,
        timestampNanos,
        1_920,
        1_080,
        synthetic = true,
    )
}
