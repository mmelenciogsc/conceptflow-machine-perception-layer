// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveVlmHtpAdmissionTest {
    @Test
    fun bootstrapGetsBoundedCompletionOpportunity() {
        val gate = LiveVlmHtpAdmissionGate(maximumVlmWindowNanos = 8_500_000_000L)
        gate.observe(LocalVlmHtpWorkState(LocalVlmHtpWorkKind.ENVIRONMENT_CLASSIFICATION, 1_000_000_000L))

        assertEquals(
            LiveVlmQnnAdmissionDecision.DEFER_QNN_FOR_VLM,
            gate.decide(5_000_000_000L, SemanticDepthRefreshReason.INITIAL),
        )
        gate.observe(null)
        assertEquals(
            LiveVlmQnnAdmissionDecision.RUN_QNN,
            gate.decide(5_100_000_000L, SemanticDepthRefreshReason.STABLE_CADENCE),
        )
    }

    @Test
    fun routineAgingConfidenceAndUncertaintyCannotStarveClassification() {
        listOf(
            SemanticDepthRefreshReason.STABLE_CADENCE,
            SemanticDepthRefreshReason.TRACK_STALE,
            SemanticDepthRefreshReason.DEPTH_STALE,
            SemanticDepthRefreshReason.LOW_CONFIDENCE,
            SemanticDepthRefreshReason.UNCERTAINTY,
        ).forEach { reason ->
            val gate = LiveVlmHtpAdmissionGate(maximumVlmWindowNanos = 8_500_000_000L)
            gate.observe(LocalVlmHtpWorkState(LocalVlmHtpWorkKind.PREWARM, 1_000_000_000L))

            assertEquals(
                "reason=$reason",
                LiveVlmQnnAdmissionDecision.DEFER_QNN_FOR_VLM,
                gate.decide(1_100_000_000L, reason),
            )
            assertTrue(gate.hasActiveWork())
        }
    }

    @Test
    fun directMotionOcclusionAndApproachEvidenceInterruptVlm() {
        listOf(
            SemanticDepthRefreshReason.MOTION,
            SemanticDepthRefreshReason.OCCLUSION,
            SemanticDepthRefreshReason.RAPID_APPROACH,
        ).forEach { reason ->
            val gate = LiveVlmHtpAdmissionGate(maximumVlmWindowNanos = 8_500_000_000L)
            gate.observe(LocalVlmHtpWorkState(LocalVlmHtpWorkKind.ENVIRONMENT_CLASSIFICATION, 1_000_000_000L))

            assertEquals(
                "reason=$reason",
                LiveVlmQnnAdmissionDecision.CANCEL_VLM_FOR_URGENT_QNN,
                gate.decide(1_100_000_000L, reason),
            )
            assertFalse(gate.hasActiveWork())
        }
    }

    @Test
    fun focusedVqaDefersRoutineMotionAndOcclusionButYieldsToRapidApproach() {
        val gate = LiveVlmHtpAdmissionGate(maximumVlmWindowNanos = 8_500_000_000L)
        gate.observe(LocalVlmHtpWorkState(LocalVlmHtpWorkKind.FOCUSED_OBJECT_VQA, 1_000_000_000L))
        assertEquals(
            LiveVlmQnnAdmissionDecision.DEFER_QNN_FOR_VLM,
            gate.decide(1_100_000_000L, SemanticDepthRefreshReason.STABLE_CADENCE),
        )
        assertEquals(
            LiveVlmQnnAdmissionDecision.DEFER_QNN_FOR_VLM,
            gate.decide(1_200_000_000L, SemanticDepthRefreshReason.MOTION),
        )
        assertEquals(
            LiveVlmQnnAdmissionDecision.DEFER_QNN_FOR_VLM,
            gate.decide(1_300_000_000L, SemanticDepthRefreshReason.OCCLUSION),
        )
        assertTrue(gate.hasActiveWork())
        assertEquals(
            LiveVlmQnnAdmissionDecision.CANCEL_VLM_FOR_URGENT_QNN,
            gate.decide(1_400_000_000L, SemanticDepthRefreshReason.RAPID_APPROACH),
        )
        assertFalse(gate.hasActiveWork())
    }

    @Test
    fun persistentSceneChangeCanReachTwoConfirmationsDuringRoutineDepthAging() {
        val cadence = LocalVlmCadenceGate(
            bootstrapIntervalNanos = 10L,
            minimumChangeIntervalNanos = 100L,
            initialFailureBackoffNanos = 20L,
            maximumFailureBackoffNanos = 80L,
        )
        assertTrue(cadence.tryStart(0L, false))
        cadence.complete(LocalVlmEnvironmentLabel.INDOOR, 1L)
        assertTrue(cadence.tryStart(11L, false))
        cadence.complete(LocalVlmEnvironmentLabel.INDOOR, 12L)
        cadence.invalidateForSceneChange(112L)

        assertTrue(cadence.tryStart(112L, true))
        val first = LiveVlmHtpAdmissionGate()
        first.observe(LocalVlmHtpWorkState(LocalVlmHtpWorkKind.ENVIRONMENT_CLASSIFICATION, 112L))
        assertEquals(
            LiveVlmQnnAdmissionDecision.DEFER_QNN_FOR_VLM,
            first.decide(113L, SemanticDepthRefreshReason.DEPTH_STALE),
        )
        cadence.complete(LocalVlmEnvironmentLabel.OUTDOOR, 113L)
        assertEquals(LocalVlmEnvironmentLabel.INDOOR, cadence.confirmedLabel())

        assertTrue(cadence.tryStart(123L, true))
        val second = LiveVlmHtpAdmissionGate()
        second.observe(LocalVlmHtpWorkState(LocalVlmHtpWorkKind.ENVIRONMENT_CLASSIFICATION, 123L))
        assertEquals(
            LiveVlmQnnAdmissionDecision.DEFER_QNN_FOR_VLM,
            second.decide(124L, SemanticDepthRefreshReason.TRACK_STALE),
        )
        cadence.complete(LocalVlmEnvironmentLabel.OUTDOOR, 124L)
        assertEquals(LocalVlmEnvironmentLabel.OUTDOOR, cadence.confirmedLabel())
    }

    @Test
    fun timedOutWorkRecoversAndSessionResetClearsAdmissionState() {
        val gate = LiveVlmHtpAdmissionGate(maximumVlmWindowNanos = 8_500_000_000L)
        gate.observe(LocalVlmHtpWorkState(LocalVlmHtpWorkKind.ENVIRONMENT_CLASSIFICATION, 1_000_000_000L))

        assertEquals(
            LiveVlmQnnAdmissionDecision.CANCEL_VLM_AFTER_TIMEOUT,
            gate.decide(9_500_000_000L, SemanticDepthRefreshReason.DEPTH_STALE),
        )
        assertEquals(
            LiveVlmQnnAdmissionDecision.RUN_QNN,
            gate.decide(9_501_000_000L, SemanticDepthRefreshReason.DEPTH_STALE),
        )

        gate.observe(LocalVlmHtpWorkState(LocalVlmHtpWorkKind.PREWARM, 10_000_000_000L))
        assertTrue(gate.hasActiveWork())
        gate.reset()
        assertFalse(gate.hasActiveWork())
    }

    @Test
    fun cancelledLazyOwnerCannotClearReplacementLane() {
        val gate = GenerationScopedVlmWorkGate()
        val cancelledOwner = requireNotNull(gate.begin(LocalVlmWorkLane.PREWARM))
        assertTrue(gate.isActive(LocalVlmWorkLane.PREWARM))

        gate.cancelAll()
        assertFalse(gate.isActive(LocalVlmWorkLane.PREWARM))
        val replacement = requireNotNull(gate.begin(LocalVlmWorkLane.PREWARM))
        assertFalse(gate.finish(LocalVlmWorkLane.PREWARM, cancelledOwner))
        assertTrue(gate.isActive(LocalVlmWorkLane.PREWARM))
        assertTrue(gate.finish(LocalVlmWorkLane.PREWARM, replacement))
        assertFalse(gate.isActive(LocalVlmWorkLane.PREWARM))
    }

    @Test
    fun workGatePermitsOnlyOneTotalPrewarmOrInferenceLane() {
        val gate = GenerationScopedVlmWorkGate()
        val prewarm = requireNotNull(gate.begin(LocalVlmWorkLane.PREWARM))
        assertNull(gate.begin(LocalVlmWorkLane.DRAIN))
        assertTrue(gate.finish(LocalVlmWorkLane.PREWARM, prewarm))

        val inference = requireNotNull(gate.begin(LocalVlmWorkLane.DRAIN))
        assertNull(gate.begin(LocalVlmWorkLane.PREWARM))
        assertTrue(gate.finish(LocalVlmWorkLane.DRAIN, inference))
    }
}
