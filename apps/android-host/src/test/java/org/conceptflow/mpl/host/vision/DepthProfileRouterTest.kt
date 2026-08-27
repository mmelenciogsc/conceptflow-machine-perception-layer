// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthProfileRouterTest {
    @Test
    fun routesOnlyAfterTimestampedEvidenceQuorumAndPreservesFrameIdentity() {
        val router = router()
        val first = router.route(7L, 100L, 100L, evidence(100L, 0.05, 0.95), true)
        val second = router.route(8L, 110L, 110L, evidence(110L, 0.04, 0.96), true)

        assertNull(first.selectedProfile)
        assertEquals(SceneEnvironmentState.TRANSITION, first.sceneState)
        assertEquals(7L, first.frameId)
        assertTrue(first.shadowComparisonRecommended)
        assertEquals(DepthEnvironment.OUTDOOR, second.selectedEnvironment)
        assertEquals(MachineVisionModelProfiles.depthOutdoor.id, second.selectedProfile!!.id)
        assertEquals(110L, second.frameTimestampNanos)
    }

    @Test
    fun defaultRouterAcceptsTwoConsistentHighConfidenceVlmResultsAtMeasuredLatency() {
        val router = DepthProfileRouter()
        val firstCapture = 1_000_000_000L
        val secondCapture = 9_000_000_000L

        val noEvidence = router.route(1L, 7_000_000_000L, 7_000_000_000L, null, true)

        val first = router.route(
            2L,
            8_000_000_000L,
            firstCapture + 7_000_000_000L,
            evidence(firstCapture, 0.94, 0.06).copy(independentSignalCount = 1),
            true,
        )
        val second = router.route(
            3L,
            16_000_000_000L,
            secondCapture + 7_000_000_000L,
            evidence(secondCapture, 0.94, 0.06).copy(independentSignalCount = 1),
            true,
        )

        assertEquals("no_environment_evidence", noEvidence.reason)
        assertNull(first.selectedProfile)
        assertEquals(DepthEnvironment.INDOOR, second.selectedEnvironment)
        val betweenStableRefreshes = router.route(
            4L,
            76_000_000_000L,
            76_000_000_000L,
            null,
            true,
        )
        assertEquals(DepthEnvironment.INDOOR, betweenStableRefreshes.selectedEnvironment)
    }

    @Test
    fun manualOverrideIsImmediateAndReturningToAutomaticRequiresFreshEvidence() {
        val router = router()
        router.setMode(EnvironmentSelectionMode.FORCE_INDOOR)
        val manual = router.route(1L, 10L, 10L, null, false)
        router.setMode(EnvironmentSelectionMode.AUTOMATIC)
        val automatic = router.route(2L, 20L, 20L, null, false)

        assertEquals(DepthEnvironment.INDOOR, manual.selectedEnvironment)
        assertEquals("manual_override", manual.reason)
        assertNull(automatic.selectedEnvironment)
        assertEquals(SceneEnvironmentState.UNKNOWN, automatic.sceneState)
    }

    @Test
    fun shadowComparisonIsBoundedAndRateLimitedDuringTransition() {
        val router = router(shadowCooldown = 50L)
        val first = router.route(1L, 10L, 10L, evidence(10L, 0.51, 0.49), true)
        val second = router.route(2L, 20L, 20L, evidence(20L, 0.51, 0.49), true)
        val third = router.route(3L, 70L, 70L, evidence(70L, 0.51, 0.49), true)
        val fourth = router.route(4L, 130L, 130L, evidence(130L, 0.51, 0.49), true)

        assertTrue(first.shadowComparisonRecommended)
        assertFalse(second.shadowComparisonRecommended)
        assertTrue(third.shadowComparisonRecommended)
        assertFalse(fourth.shadowComparisonRecommended)
    }

    @Test
    fun shadowComparatorUsesContinuityCalibrationCoverageAndUncertainty() {
        val comparator = DepthShadowComparator()
        val indoor = quality(DepthEnvironment.INDOOR, temporal = 0.1, calibration = 0.1, uncertainty = 0.2)
        val outdoor = quality(DepthEnvironment.OUTDOOR, temporal = 0.6, calibration = 0.4, uncertainty = 0.6)

        assertEquals(DepthEnvironment.INDOOR, comparator.choose(indoor, outdoor))
        assertNull(comparator.choose(indoor, indoor.copy(environment = DepthEnvironment.OUTDOOR)))
        assertNull(comparator.choose(indoor, outdoor.copy(frameId = 2L)))
        assertNull(
            DepthShadowComparator(maximumCompletionSkewNanos = 10L).choose(
                indoor,
                outdoor.copy(completedMonotonicTimestampNanos = 111L),
            ),
        )
    }

    @Test
    fun coordinatorCombinesDepthIndependentSemanticsWithGnssForSameFrame() {
        val coordinator = EnvironmentDepthCoordinator(router = router())
        coordinator.updateGnss(
            GnssQualitySample(90L, 16, 9, 35.0, 5.0, 1L),
        )
        val detections = listOf(
            SceneSemanticDetection("crosswalk", 0.95),
            SceneSemanticDetection("traffic_light", 0.95),
        )
        val first = coordinator.routeFrame(VisionFrame(1L, 100L, 1_920, 1_080, true), detections, nowNanos = 100L, bothProfilesAvailable = true)
        val second = coordinator.routeFrame(VisionFrame(2L, 110L, 1_920, 1_080, true), detections, nowNanos = 110L, bothProfilesAvailable = true)

        assertEquals(SceneEnvironmentState.TRANSITION, first.sceneState)
        assertEquals(DepthEnvironment.OUTDOOR, second.selectedEnvironment)
    }

    @Test
    fun modeChangeClearsPreviouslyBufferedEvidence() {
        val buffer = EnvironmentEvidenceBuffer()
        val coordinator = EnvironmentDepthCoordinator(buffer = buffer, router = router())
        assertTrue(coordinator.updateGnss(GnssQualitySample(90L, 16, 9, 35.0, 5.0, 1L)))

        coordinator.setMode(EnvironmentSelectionMode.FORCE_INDOOR)
        coordinator.setMode(EnvironmentSelectionMode.AUTOMATIC)

        assertTrue(buffer.snapshot().isEmpty())
    }

    private fun router(shadowCooldown: Long = 100L) = DepthProfileRouter(
        selector = DepthProfileSelector(
            DepthProfileSelectorConfig(
                consecutiveEvidenceRequired = 2,
                minimumHoldNanos = 0L,
                maximumEvidenceAgeNanos = 100L,
                maximumProfileReuseNanos = 500L,
                singleVisualEnterProbability = 0.85,
            ),
        ),
        config = DepthProfileRouterConfig(
            shadowComparisonCooldownNanos = shadowCooldown,
            maximumShadowComparisonsPerTransition = 2,
        ),
    )

    private fun evidence(timestamp: Long, indoor: Double, outdoor: Double) = EnvironmentEvidence(
        timestamp,
        indoor,
        outdoor,
        independentSignalCount = 2,
        hasPrimaryVisualSignal = true,
    )

    private fun quality(
        environment: DepthEnvironment,
        temporal: Double,
        calibration: Double,
        uncertainty: Double,
    ) = DepthProfileQuality(
        frameId = 1L,
        environment = environment,
        completedMonotonicTimestampNanos = 100L,
        validSampleFraction = 0.9,
        temporalMedianErrorMeters = temporal,
        calibrationMedianErrorMeters = calibration,
        medianUncertaintyMeters = uncertainty,
    )
}
