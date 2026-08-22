// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MachineVisionPipelineTest {
    private val calibration = TwoAnchorMetricDepthCalibrator().calibrate(
        listOf(
            GuidedCalibrationSample("door", ReferenceDistance.NEAR_TWO_FEET, 2.0, 0.9),
            GuidedCalibrationSample("door", ReferenceDistance.FAR_EIGHT_FEET, 8.0, 0.9),
        ),
        RelativeDepthRepresentation.DEPTH,
    )!!

    @Test
    fun fusesFixedVocabularyMaskDepthAndRejectsUnknownClass() {
        val pipeline = MachineVisionPipeline(adapter(completedAt = 120L), calibration)
        val result = pipeline.process(frame(), DepthEnvironment.INDOOR, nowNanos = 150L)

        assertFalse(result.rejectedStale)
        assertEquals(1, result.rejectedUnknownClasses)
        assertEquals(1, result.rejectedLowConfidence)
        assertEquals(1, result.tracks.size)
        assertEquals("door", result.tracks.single().classId)
        assertEquals(1.524, result.tracks.single().representativeDistance.distanceMeters, 1e-9)
        assertEquals(DepthEnvironment.INDOOR, result.tracks.single().depthEnvironment)
        assertEquals(TemporalMotionEvidence.UNKNOWN, result.tracks.single().temporalMotionEvidence)
    }

    @Test
    fun rejectsStaleResultAndWrongVocabularyFingerprint() {
        val stale = MachineVisionPipeline(adapter(completedAt = 120L), calibration, maximumResultAgeNanos = 1_000_000L)
            .process(frame(), DepthEnvironment.OUTDOOR, nowNanos = 2_000_100L)
        assertTrue(stale.rejectedStale)

        val mismatch = MachineVisionPipeline(
            adapter(completedAt = 120L, vocabulary = "0".repeat(64)),
            calibration,
        ).process(frame(), DepthEnvironment.INDOOR, nowNanos = 150L)
        assertEquals("fixed_vocabulary_mismatch", mismatch.reason)
        assertTrue(mismatch.tracks.isEmpty())
    }

    @Test
    fun convertsAdapterExceptionToTypedFailure() {
        val pipeline = MachineVisionPipeline(
            MachineVisionInferenceAdapter { _, _ -> error("sensitive vendor failure") },
            calibration,
        )
        val result = pipeline.process(frame(), DepthEnvironment.INDOOR, nowNanos = 150L)
        assertEquals("adapter_failure", result.reason)
        assertTrue(result.tracks.isEmpty())
    }

    @Test
    fun exactRoutedProfileAndCalibrationContextAreEnforced() {
        val intrinsics = CameraIntrinsics(1_920, 1_080, 1_000.0, 1_000.0, 960.0, 540.0)
        val selected = MachineVisionModelProfiles.depthIndoorLowPower
        val boundCalibration = TwoAnchorMetricDepthCalibrator().calibrate(
            listOf(
                GuidedCalibrationSample("door", ReferenceDistance.NEAR_TWO_FEET, 2.0, 0.9),
                GuidedCalibrationSample("door", ReferenceDistance.FAR_EIGHT_FEET, 8.0, 0.9),
            ),
            RelativeDepthRepresentation.DEPTH,
            MetricDepthCalibrationBinding.forProfile(selected, intrinsics),
        )!!
        var receivedProfile: MachineVisionModelProfile? = null
        val exactAdapter = MachineVisionInferenceAdapter { inputFrame, profile ->
            receivedProfile = profile
            MachineVisionInference(
                inputFrame.frameId,
                120L,
                MachineVisionModelProfiles.fixedVocabularySha256,
                profile.id,
                listOf(SemanticMaskObservation("track-1", "door", 0.9, listOf(5.0))),
            )
        }
        val pipeline = MachineVisionPipeline(
            exactAdapter,
            boundCalibration,
            calibrationBindingPolicy = CalibrationBindingPolicy.REQUIRE_BOUND,
        )
        val productionFrame = VisionFrame(1L, 100L, 1_920, 1_080, false, intrinsics)

        assertEquals("processed", pipeline.process(productionFrame, selected, 150L).reason)
        assertEquals(selected, receivedProfile)
        assertEquals(
            "calibration_depth_profile_mismatch",
            pipeline.process(productionFrame, MachineVisionModelProfiles.depthOutdoorLowPower, 150L).reason,
        )
        assertEquals(
            "calibration_depth_profile_mismatch",
            pipeline.process(productionFrame, MachineVisionModelProfiles.depthIndoorBalanced, 150L).reason,
        )
        val changedIntrinsics = CameraIntrinsics(1_920, 1_080, 1_001.0, 1_000.0, 960.0, 540.0)
        assertEquals(
            "calibration_intrinsics_mismatch",
            pipeline.process(productionFrame.copy(cameraIntrinsics = changedIntrinsics), selected, 150L).reason,
        )
    }

    @Test
    fun productionFrameRejectsExplicitlyUnboundLegacyCalibration() {
        val productionFrame = frame().copy(synthetic = false)
        val result = MachineVisionPipeline(adapter(120L), calibration)
            .process(productionFrame, MachineVisionModelProfiles.depthIndoorBalanced, 150L)

        assertEquals("calibration_unbound", result.reason)
    }

    private fun frame() = VisionFrame(1L, 100L, 1_920, 1_080, synthetic = true)

    private fun adapter(
        completedAt: Long,
        vocabulary: String = MachineVisionModelProfiles.fixedVocabularySha256,
    ) = MachineVisionInferenceAdapter { frame, profile ->
        MachineVisionInference(
            frameId = frame.frameId,
            completedMonotonicTimestampNanos = completedAt,
            fixedVocabularySha256 = vocabulary,
            depthProfileId = profile.id,
            observations = listOf(
                SemanticMaskObservation("track-1", "door", 0.9, listOf(4.0, 5.0, 6.0)),
                SemanticMaskObservation("track-2", "unbounded_prompt", 0.9, listOf(4.0)),
                SemanticMaskObservation("track-3", "chair", 0.1, listOf(4.0)),
            ),
        )
    }
}
