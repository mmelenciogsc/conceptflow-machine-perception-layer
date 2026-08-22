// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporalMachineVisionTest {
    @Test
    fun keyframeGatePreservesThreeFpsInputAndForcesAtNoMoreThanFiveFps() {
        val gate = VisualKeyframeGate()
        val stable = VisualKeyframeSignal(0.0, 0.0)
        val forced = VisualKeyframeSignal(0.9, 0.0)
        val first = frame(1L, 0L)

        val decisions = listOf(
            gate.evaluate(first, stable),
            gate.evaluate(frame(2L, 166_000_000L), stable),
            gate.evaluate(frame(3L, 333_000_000L), stable),
            gate.evaluate(frame(4L, 400_000_000L), forced),
            gate.evaluate(frame(5L, 533_000_000L), forced),
        )

        assertSame(first, decisions.first().acceptedFrame)
        assertEquals(listOf(true, false, true, false, true), decisions.map { it.accepted })
        assertEquals("three_fps_cadence", decisions[1].reason)
        assertEquals("five_fps_cap", decisions[3].reason)
        assertEquals("forced_keyframe", decisions[4].reason)

        gate.reset()
        val physicalThreeFps = (0L..9L).map { index ->
            gate.evaluate(frame(index + 1L, index * 333_000_000L), stable)
        }
        assertEquals(10, physicalThreeFps.count(VisualKeyframeDecision::accepted))

        gate.reset()
        assertTrue(gate.evaluate(frame(1L, 0L), stable).accepted)
        assertFalse(gate.evaluate(frame(2L, 199_000_000L), forced).accepted)
        assertTrue(gate.evaluate(frame(3L, 200_000_000L), forced).accepted)
    }

    @Test
    fun keyframeGateRejectsNonMonotonicFrameIdentityOrTimestamp() {
        val gate = VisualKeyframeGate()
        val signal = VisualKeyframeSignal(0.0, 0.0)
        assertTrue(gate.evaluate(frame(10L, 1_000L), signal).accepted)
        assertEquals("non_monotonic_frame", gate.evaluate(frame(10L, 2_000L), signal).reason)
        assertEquals("non_monotonic_frame", gate.evaluate(frame(11L, 1_000L), signal).reason)
    }

    @Test
    fun validatedIntrinsicsProjectMaskCentroidWithoutInventingGeometry() {
        val intrinsics = intrinsics()
        val geometry = geometry()
        val vector = intrinsics.vectorAtDepth(geometry.centroidXPixels, geometry.centroidYPixels, 2.0)

        assertEquals(0.0, vector.x, 1e-9)
        assertEquals(0.0, vector.y, 1e-9)
        assertEquals(2.0, vector.z, 1e-9)
        assertTrue(
            runCatching {
                CameraIntrinsics(640, 480, 0.0, 500.0, 320.0, 240.0)
            }.isFailure,
        )
    }

    @Test
    fun robustFusionUsesAgreeingPriorAndRejectsOutlier() {
        val calibrated = MetricDepthEstimate(2.0, 0.20, false)
        val agreeing = RobustMetricDepthFusion.fuse(
            calibrated,
            0.9,
            DimensionDistanceEstimate(2.2, 0.20, "chair"),
            0.7,
        )
        val outlier = RobustMetricDepthFusion.fuse(
            calibrated,
            0.9,
            DimensionDistanceEstimate(8.0, 0.10, "chair"),
            0.9,
        )

        assertTrue(agreeing.usedDimensionPrior)
        assertTrue(agreeing.estimate.distanceMeters in 2.0..2.2)
        assertTrue(agreeing.estimate.uncertaintyMeters >= calibrated.uncertaintyMeters)
        assertFalse(agreeing.rejectedDimensionPriorAsOutlier)
        assertFalse(outlier.usedDimensionPrior)
        assertTrue(outlier.rejectedDimensionPriorAsOutlier)
        assertEquals(calibrated, outlier.estimate)
    }

    @Test
    fun headRotationPropagatesExistingStaticWorldAnchor() {
        val store = store()
        store.updateKeyframe(
            frame(1L, 0L),
            listOf(track(motionEvidence = TemporalMotionEvidence.CONFIRMED_STATIC_WORLD)),
            pose(0L),
        )
        val halfAngle = PI / 4.0
        val yawRight = UnitQuaternion(cos(halfAngle), 0.0, sin(halfAngle), 0.0)

        val update = store.updatePose(TimestampedPose(100_000_000L, yawRight))
        val propagated = update.tracks.single()

        assertTrue(update.accepted)
        assertTrue(propagated.propagated)
        assertFalse(propagated.translationApplied)
        assertEquals(-2.0, propagated.cameraVectorMeters.x, 1e-9)
        assertEquals(0.0, propagated.cameraVectorMeters.z, 1e-9)
    }

    @Test
    fun peopleVehiclesAndUnknownMotionTracksNeverPropagate() {
        val store = store()
        val measured = listOf(
            track(
                trackId = "person-1",
                classId = "person",
                motionEvidence = TemporalMotionEvidence.CONFIRMED_STATIC_WORLD,
            ),
            track(
                trackId = "car-1",
                classId = "car",
                motionEvidence = TemporalMotionEvidence.CONFIRMED_STATIC_WORLD,
            ),
            track(
                trackId = "door-unknown",
                classId = "door",
                motionEvidence = TemporalMotionEvidence.UNKNOWN,
            ),
        )

        assertTrue(store.updateKeyframe(frame(1L, 0L), measured, pose(0L)).tracks.isEmpty())
        assertTrue(store.updatePose(pose(100_000_000L)).tracks.isEmpty())
    }

    @Test
    fun reusedStaticIdIsRemovedWhenLaterEvidenceIsDynamicOrUnknown() {
        listOf(TemporalMotionEvidence.DYNAMIC, TemporalMotionEvidence.UNKNOWN).forEach { laterEvidence ->
            val store = store()
            store.updateKeyframe(
                frame(1L, 0L),
                listOf(track(motionEvidence = TemporalMotionEvidence.CONFIRMED_STATIC_WORLD)),
                pose(0L),
            )
            assertEquals(1, store.updatePose(pose(100_000_000L)).tracks.size)

            val ineligible = track(
                frameId = 2L,
                captureNanos = 400_000_000L,
                motionEvidence = laterEvidence,
            )
            assertTrue(
                store.updateKeyframe(frame(2L, 400_000_000L), listOf(ineligible), pose(400_000_000L))
                    .tracks.isEmpty(),
            )
            assertTrue(store.updatePose(pose(500_000_000L)).tracks.isEmpty())
        }
    }

    @Test
    fun translationRequiresExplicitPositionEvidence() {
        val withVio = store()
        val origin = PositionEvidence(
            MetricVector3(0.0, 0.0, 0.0),
            0.02,
            PositionEvidenceSource.VIO,
            "vio-session-a",
        )
        val moved = PositionEvidence(
            MetricVector3(0.0, 0.0, 1.0),
            0.02,
            PositionEvidenceSource.VIO,
            "vio-session-a",
        )
        withVio.updateKeyframe(frame(1L, 0L), listOf(track()), pose(0L, origin))
        val translated = withVio.updatePose(pose(100_000_000L, moved)).tracks.single()

        val orientationOnly = store()
        orientationOnly.updateKeyframe(frame(1L, 0L), listOf(track()), pose(0L))
        val notTranslated = orientationOnly.updatePose(pose(100_000_000L, moved)).tracks.single()

        assertTrue(translated.translationApplied)
        assertEquals(1.0, translated.cameraVectorMeters.z, 1e-9)
        assertFalse(notTranslated.translationApplied)
        assertEquals(2.0, notTranslated.cameraVectorMeters.z, 1e-9)
    }

    @Test
    fun mismatchedTranslationSourceOrCoordinateFrameFallsBackToOrientationOnly() {
        val origin = PositionEvidence(
            MetricVector3(0.0, 0.0, 0.0),
            0.02,
            PositionEvidenceSource.VIO,
            "vio-session-a",
        )
        val wrongFrame = PositionEvidence(
            MetricVector3(0.0, 0.0, 1.0),
            0.02,
            PositionEvidenceSource.VIO,
            "vio-session-b",
        )
        val wrongSource = PositionEvidence(
            MetricVector3(0.0, 0.0, 1.0),
            0.02,
            PositionEvidenceSource.EXTERNAL_TRACKING,
            "vio-session-a",
        )

        listOf(wrongFrame, wrongSource).forEach { incompatible ->
            val store = store()
            store.updateKeyframe(frame(1L, 0L), listOf(track()), pose(0L, origin))
            val output = store.updatePose(pose(100_000_000L, incompatible)).tracks.single()
            assertFalse(output.translationApplied)
            assertEquals(2.0, output.cameraVectorMeters.z, 1e-9)
            assertTrue(output.uncertaintyMeters > 0.10)
        }
    }

    @Test
    fun orientationOnlyPropagationIncreasesUncertainty() {
        val store = store(
            ttlNanos = 2_000_000_000L,
            orientationUncertaintyPerSecond = 0.50,
        )
        val measured = store.updateKeyframe(frame(1L, 0L), listOf(track()), pose(0L)).tracks.single()
        val propagated = store.updatePose(pose(1_000_000_000L)).tracks.single()

        assertEquals(0.10, measured.uncertaintyMeters, 1e-9)
        assertEquals(0.60, propagated.uncertaintyMeters, 1e-9)
        assertTrue(propagated.uncertaintyMeters > measured.uncertaintyMeters)
    }

    @Test
    fun confidenceDecayTtlPoseFreshnessOcclusionRemovalAndResetAreBounded() {
        val store = store(ttlNanos = 1_000_000_000L, confidenceDecayPerSecond = 1.0)
        store.updateKeyframe(frame(1L, 0L), listOf(track()), pose(0L))

        val decayed = store.updatePose(pose(500_000_000L)).tracks.single()
        assertTrue(decayed.confidence < 0.9)
        assertTrue(store.snapshot(700_000_001L).isEmpty())
        assertTrue(store.markOccluded("stable-1"))
        assertTrue(store.updatePose(pose(600_000_000L)).tracks.isEmpty())

        store.updateKeyframe(
            frame(2L, 700_000_000L),
            listOf(track(frameId = 2L, captureNanos = 700_000_000L)),
            pose(700_000_000L),
        )
        assertTrue(store.remove("stable-1"))
        assertTrue(store.updatePose(pose(800_000_000L)).tracks.isEmpty())

        store.updateKeyframe(
            frame(3L, 900_000_000L),
            listOf(track(frameId = 3L, captureNanos = 900_000_000L)),
            pose(900_000_000L),
        )
        assertTrue(store.updatePose(pose(2_000_000_001L)).tracks.isEmpty())
        store.reset()
        assertTrue(store.updatePose(pose(3_000_000_000L)).tracks.isEmpty())
    }

    @Test
    fun keyframeCanReplaceReusedIdOnlyWithNewMeasuredClassAndVector() {
        val store = store()
        store.updateKeyframe(frame(1L, 0L), listOf(track()), pose(0L))
        val replacement = track(
            classId = "chair",
            frameId = 2L,
            captureNanos = 400_000_000L,
            vector = MetricVector3(1.0, 0.0, 3.0),
        )

        val output = store.updateKeyframe(frame(2L, 400_000_000L), listOf(replacement), pose(400_000_000L))
            .tracks.single()

        assertEquals("stable-1", output.stableTrackId)
        assertEquals("chair", output.classId)
        assertEquals(2L, output.sourceFrameId)
        assertEquals(MetricVector3(1.0, 0.0, 3.0), output.cameraVectorMeters)
    }

    @Test
    fun poseTicksAndGeometryFreeMeasurementsNeverInventTracks() {
        val store = store()
        assertTrue(store.updatePose(pose(1L)).tracks.isEmpty())

        val withoutGeometry = track(frameId = 1L, captureNanos = 10L, vector = null)
        val keyframe = store.updateKeyframe(frame(1L, 10L), listOf(withoutGeometry), pose(10L))
        assertTrue(keyframe.tracks.isEmpty())
        assertTrue(store.updatePose(pose(20L)).tracks.isEmpty())
    }

    @Test
    fun processFramePropagatesPoseBeforeCadenceAndInferenceFailureBranches() {
        val profile = MachineVisionModelProfiles.depthIndoorBalanced
        val calibration = TwoAnchorMetricDepthCalibrator().calibrate(
            listOf(
                GuidedCalibrationSample("door", ReferenceDistance.NEAR_TWO_FEET, 2.0, 0.9),
                GuidedCalibrationSample("door", ReferenceDistance.FAR_EIGHT_FEET, 8.0, 0.9),
            ),
            RelativeDepthRepresentation.DEPTH,
            MetricDepthCalibrationBinding.forProfile(profile, intrinsics()),
        )!!
        val staged = EnvironmentAwareMachineVisionPipeline(
            TemporalAdapter(failingSegmentationFrameId = 3L),
            calibration,
        ).also { it.setEnvironmentMode(EnvironmentSelectionMode.FORCE_INDOOR) }
        val pipeline = TemporalEnvironmentAwareMachineVisionPipeline(staged)
        val stable = VisualKeyframeSignal(0.0, 0.0)
        val first = pipeline.processFrame(frame(1L, 0L), pose(0L), stable, 10L, true)
        assertEquals("keyframe_updated", first.reason)
        val distance = first.temporalTracks.single().distanceMeters

        val halfAngle = PI / 4.0
        val nonKeyframe = pipeline.processFrame(
            frame(2L, 100_000_000L),
            TimestampedPose(100_000_000L, UnitQuaternion(cos(halfAngle), 0.0, sin(halfAngle), 0.0)),
            stable,
            100_000_010L,
            true,
        )
        assertEquals("three_fps_cadence", nonKeyframe.reason)
        assertEquals(-distance, nonKeyframe.temporalTracks.single().cameraVectorMeters.x, 1e-9)

        val failedInference = pipeline.processFrame(
            frame(3L, 400_000_000L),
            TimestampedPose(400_000_000L, UnitQuaternion(0.0, 0.0, 1.0, 0.0)),
            stable,
            400_000_010L,
            true,
        )
        assertEquals("segmentation_adapter_failure", failedInference.reason)
        assertEquals(-distance, failedInference.temporalTracks.single().cameraVectorMeters.z, 1e-9)
    }

    private fun store(
        ttlNanos: Long = 2_000_000_000L,
        confidenceDecayPerSecond: Double = 0.0,
        orientationUncertaintyPerSecond: Double = 0.20,
    ) = TemporalMetricTrackStore(
        capacity = 4,
        trackTtlNanos = ttlNanos,
        maximumPoseAgeNanos = 200_000_000L,
        minimumConfidence = 0.20,
        maximumUncertaintyMeters = 3.0,
        confidenceDecayPerSecond = confidenceDecayPerSecond,
        orientationOnlyUncertaintyMetersPerSecond = orientationUncertaintyPerSecond,
    )

    private fun track(
        trackId: String = "stable-1",
        classId: String = "door",
        frameId: Long = 1L,
        captureNanos: Long = 0L,
        vector: MetricVector3? = MetricVector3(0.0, 0.0, 2.0),
        motionEvidence: TemporalMotionEvidence = TemporalMotionEvidence.CONFIRMED_STATIC_WORLD,
    ) = MetricSemanticTrack(
        frameId = frameId,
        trackId = trackId,
        classId = classId,
        confidence = 0.9,
        representativeDistance = MetricDepthEstimate(2.0, 0.10, false),
        depthEnvironment = DepthEnvironment.INDOOR,
        sourceCaptureMonotonicTimestampNanos = captureNanos,
        sourceInferenceMonotonicTimestampNanos = captureNanos + 1L,
        cameraVectorMeters = vector,
        temporalMotionEvidence = motionEvidence,
    )

    private fun pose(timestamp: Long, position: PositionEvidence? = null) = TimestampedPose(
        timestamp,
        UnitQuaternion.IDENTITY,
        position,
    )

    private fun frame(id: Long, timestamp: Long) = VisionFrame(
        id,
        timestamp,
        640,
        480,
        synthetic = false,
        cameraIntrinsics = intrinsics(),
    )

    private fun intrinsics() = CameraIntrinsics(640, 480, 500.0, 500.0, 320.0, 240.0)

    private fun geometry() = InstanceMaskGeometry(
        imageWidthPixels = 640,
        imageHeightPixels = 480,
        leftPixels = 270,
        topPixels = 190,
        rightExclusivePixels = 371,
        bottomExclusivePixels = 291,
        centroidXPixels = 320.0,
        centroidYPixels = 240.0,
        foregroundPixelCount = 8_000,
    )

    private class TemporalAdapter(
        private val failingSegmentationFrameId: Long,
    ) : StagedMachineVisionInferenceAdapter {
        override fun segment(frame: VisionFrame): SegmentationStageResult {
            if (frame.frameId == failingSegmentationFrameId) error("bounded test failure")
            return SegmentationStageResult(
                frame.frameId,
                frame.captureMonotonicTimestampNanos + 1L,
                MachineVisionModelProfiles.fixedVocabularySha256,
                listOf(
                    SegmentedObject(
                        "stable-1",
                        "door",
                        0.9,
                        geometryForFrame(),
                        MASK_FINGERPRINT,
                        TemporalMotionEvidence.CONFIRMED_STATIC_WORLD,
                    ),
                ),
            )
        }

        override fun inferDepth(
            frame: VisionFrame,
            depthProfile: MachineVisionModelProfile,
            segmentedObjects: List<SegmentedObject>,
        ) = DepthStageResult(
            frame.frameId,
            frame.captureMonotonicTimestampNanos + 2L,
            depthProfile.id,
            mapOf("stable-1" to listOf(5.0)),
            mapOf("stable-1" to MASK_FINGERPRINT),
        )

        private fun geometryForFrame() = InstanceMaskGeometry(
            640,
            480,
            270,
            190,
            371,
            291,
            320.0,
            240.0,
            8_000,
        )

        private companion object {
            val MASK_FINGERPRINT = "c".repeat(64)
        }
    }
}
