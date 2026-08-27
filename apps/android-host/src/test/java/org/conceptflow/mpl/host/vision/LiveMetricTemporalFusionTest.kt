// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.conceptflow.mpl.v1.CoordinateFrame
import org.conceptflow.mpl.v1.ImuReading
import org.conceptflow.mpl.v1.Pose
import org.conceptflow.mpl.v1.Quaternion
import org.conceptflow.mpl.v1.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMetricTemporalFusionTest {
    @Test
    fun `IMU mapper admits only normalized reliable head orientation and never translation`() {
        val mapped = requireNotNull(LiveImuPoseMapper.map(imu(), 1_000L))
        val buffer = BoundedHeadPoseBuffer()
        assertTrue(buffer.add(mapped))
        val pose = requireNotNull(buffer.cameraPoseAt(1_000L, extrinsic()))

        assertEquals(UnitQuaternion.IDENTITY, pose.worldFromCamera)
        assertNull(pose.positionEvidence)
        assertNull(LiveImuPoseMapper.map(imu(accuracy = 0), 1_001L))
        assertNull(LiveImuPoseMapper.map(imu(frame = CoordinateFrame.COORDINATE_FRAME_BODY), 1_002L))
        assertNull(LiveImuPoseMapper.map(imu(w = 0.5), 1_003L))
    }

    @Test
    fun `pose buffer interpolates head orientation and reset removes correlation`() {
        val buffer = BoundedHeadPoseBuffer(
            maximumCorrelationAgeNanos = 100_000_000L,
            retentionNanos = 1_000_000_000L,
        )
        buffer.add(HeadPoseObservation(100_000_000L, UnitQuaternion.IDENTITY, 3))
        buffer.add(HeadPoseObservation(200_000_000L, UnitQuaternion(0.0, 0.0, 1.0, 0.0), 3))

        val halfway = requireNotNull(buffer.cameraPoseAt(150_000_000L, extrinsic()))
        val rotated = halfway.worldFromCamera.rotate(MetricVector3(0.0, 0.0, 1.0))
        assertEquals(1.0, rotated.x, 1e-9)
        assertEquals(0.0, rotated.z, 1e-9)
        buffer.reset()
        assertNull(buffer.cameraPoseAt(150_000_000L, extrinsic()))
    }

    @Test
    fun `missing metric semantics preserves relative evidence but exposes zero metric tracks`() {
        val plainFrame = frame(intrinsics = null)
        val calibratedFrame = frame(intrinsics = intrinsics())
        val inference = result(calibratedFrame)

        val missingCalibration = LiveMetricTemporalFusion(
            MetricDepthCalibrationProvider.none(),
            null,
            NativeMetricDepthSemanticsProvider.none(),
        )
            .process(calibratedFrame, inference, 200L)
        assertUnavailable(missingCalibration, LiveMetricFusionReason.PROFILE_BOUND_METRIC_SEMANTICS_MISSING)

        val unboundCalibration = LiveMetricTemporalFusion(
            MetricDepthCalibrationProvider.single(calibration(binding = null)),
            extrinsic(),
            NativeMetricDepthSemanticsProvider.none(),
        ).process(calibratedFrame, inference, 200L)
        assertUnavailable(unboundCalibration, LiveMetricFusionReason.PROFILE_BOUND_METRIC_SEMANTICS_MISSING)

        val scalarWithoutIntrinsics = LiveMetricTemporalFusion(MetricDepthCalibrationProvider.none(), null)
            .process(plainFrame, result(plainFrame), 200L)
        assertEquals(
            LiveMetricFusionReason.CAMERA_METRIC_TRACKS_READY_PROPAGATION_INTRINSICS_MISSING,
            scalarWithoutIntrinsics.reason,
        )
        assertEquals(1, scalarWithoutIntrinsics.metricTrackCount)
        assertEquals(2.0, scalarWithoutIntrinsics.metricTracks.single().representativeDistance.distanceMeters, 1e-9)
        assertNull(scalarWithoutIntrinsics.metricTracks.single().cameraVectorMeters)
        assertTrue(scalarWithoutIntrinsics.temporalTracks.isEmpty())
    }

    @Test
    fun `native metric camera tracks do not require extrinsic but propagation does`() {
        val frame = frame(intrinsics = intrinsics())
        val fusion = LiveMetricTemporalFusion(MetricDepthCalibrationProvider.none(), null)
        val output = fusion.process(frame, result(frame), 200L)

        assertEquals(
            LiveMetricFusionReason.CAMERA_METRIC_TRACKS_READY_PROPAGATION_EXTRINSIC_MISSING,
            output.reason,
        )
        assertTrue(output.cameraMetricOutputAvailable)
        assertEquals(1, output.metricTrackCount)
        assertEquals(2.0, output.metricTracks.single().representativeDistance.distanceMeters, 1e-9)
        assertNull(output.metricTracks.single().representativeDistance.uncertaintyMeters)
        assertTrue(output.temporalTracks.isEmpty())
    }

    @Test
    fun `derived intrinsics preserve native metric output without becoming calibrated`() {
        val derivedIntrinsics = intrinsics().copy(source = CameraIntrinsicsSource.DERIVED)
        val frame = frame(intrinsics = derivedIntrinsics)
        val result = result(frame).copy(calibrationState = LiveMetricCalibrationState.DERIVED_INTRINSICS_PRESENT)
        val output = LiveMetricTemporalFusion(MetricDepthCalibrationProvider.none(), null)
            .process(frame, result, 200L)

        assertEquals(CameraIntrinsicsSource.DERIVED, requireNotNull(frame.cameraIntrinsics).source)
        assertEquals(1, output.metricTrackCount)
        assertEquals(2.0, output.metricTracks.single().representativeDistance.distanceMeters, 1e-9)
        assertEquals(
            MetricDepthProvenanceKind.PINNED_OFFICIAL_NATIVE_METRIC,
            requireNotNull(output.metricProvenance).kind,
        )
    }

    @Test
    fun `unquantified derived intrinsics propagate with explicit unquantified status`() {
        val derivedIntrinsics = intrinsics().copy(source = CameraIntrinsicsSource.DERIVED)
        val frame = frame(intrinsics = derivedIntrinsics)
        val guided = MetricDepthCalibrationProvider.single(
            calibration(
                MetricDepthCalibrationBinding.forProfile(
                    MachineVisionModelProfiles.depthIndoorBalanced,
                    derivedIntrinsics,
                ),
            ),
        )
        val fusion = LiveMetricTemporalFusion(guided, extrinsic())
        assertTrue(fusion.acceptPose(HeadPoseObservation(100L, UnitQuaternion.IDENTITY, 3)).accepted)

        val output = fusion.process(
            frame,
            result(frame, TemporalMotionEvidence.CONFIRMED_STATIC_WORLD).copy(
                calibrationState = LiveMetricCalibrationState.DERIVED_INTRINSICS_PRESENT,
            ),
            200L,
        )

        assertEquals(
            LiveMetricFusionReason.METRIC_TRACKS_READY_PROPAGATION_INTRINSICS_UNQUANTIFIED,
            output.reason,
        )
        assertEquals(1, output.metricTrackCount)
        assertTrue(output.metricTracks.single().cameraVectorMeters != null)
        assertEquals(MetricDepthProvenanceKind.GUIDED_TWO_ANCHOR, requireNotNull(output.metricProvenance).kind)
        assertEquals(1, output.temporalTracks.size)
    }

    @Test
    fun `native metric input outside pinned profile range fails closed`() {
        val frame = frame(intrinsics = intrinsics())
        val output = LiveMetricTemporalFusion(MetricDepthCalibrationProvider.none(), null)
            .process(frame, result(frame, depthSamples = listOf(20.1)), 200L)

        assertUnavailable(output, LiveMetricFusionReason.METRIC_DEPTH_INPUT_INVALID)
    }

    @Test
    fun `unknown depth profile is rejected before metric semantics lookup`() {
        val frame = frame(intrinsics = intrinsics())
        val unknown = result(frame).let { valid ->
            valid.copy(
                selectedDepthProfileId = "unknown-depth-profile",
                inference = valid.inference.copy(depthProfileId = "unknown-depth-profile"),
            )
        }

        val output = LiveMetricTemporalFusion(MetricDepthCalibrationProvider.none(), null)
            .process(frame, unknown, 200L)

        assertUnavailable(output, LiveMetricFusionReason.INFERENCE_REJECTED)
        assertEquals("unknown_depth_profile", output.detailCode)
    }

    @Test
    fun `profile bound fusion emits metric tracks but unknown motion never propagates`() {
        val frame = frame(intrinsics = intrinsics())
        val fusion = LiveMetricTemporalFusion(provider(), extrinsic())
        assertTrue(fusion.acceptPose(HeadPoseObservation(100L, UnitQuaternion.IDENTITY, 3)).accepted)

        val output = fusion.process(frame, result(frame), 200L)

        assertEquals(LiveMetricFusionReason.METRIC_TRACKS_READY, output.reason)
        assertEquals(1, output.relativeTrackCount)
        assertEquals(1, output.metricTrackCount)
        assertEquals(0, output.propagatedTrackCount)
        assertTrue(output.temporalTracks.isEmpty())

        assertTrue(fusion.acceptPose(HeadPoseObservation(250L, yawDegrees(20.0), 3)).accepted)
        assertEquals(0, fusion.acceptPose(HeadPoseObservation(300L, yawDegrees(25.0), 3)).temporalTrackCount)
        fusion.reset()
        val afterReset = fusion.process(
            frame.copy(frameId = 2L),
            result(frame.copy(frameId = 2L)),
            300L,
        )
        assertEquals(
            LiveMetricFusionReason.CAMERA_METRIC_TRACKS_READY_PROPAGATION_POSE_MISSING_OR_STALE,
            afterReset.reason,
        )
        assertEquals(1, afterReset.metricTrackCount)
        assertTrue(afterReset.temporalTracks.isEmpty())
    }

    @Test
    fun `only explicit static evidence permits orientation-only temporal propagation`() {
        val frame = frame(intrinsics = intrinsics())
        val fusion = LiveMetricTemporalFusion(provider(), extrinsic())
        fusion.acceptPose(HeadPoseObservation(100L, UnitQuaternion.IDENTITY, 3))

        val ready = fusion.process(
            frame,
            result(frame, TemporalMotionEvidence.CONFIRMED_STATIC_WORLD),
            200L,
        )
        val moved = fusion.acceptPose(HeadPoseObservation(250L, yawDegrees(15.0), 3))

        assertEquals(1, ready.temporalTracks.size)
        assertEquals(1, moved.temporalTrackCount)
    }

    @Test
    fun `frame supplied camera2 extrinsic enables subsequent pose propagation`() {
        val frame = frame(intrinsics = intrinsics())
        val fusion = LiveMetricTemporalFusion(provider(), null)
        val before = fusion.acceptPose(HeadPoseObservation(100L, UnitQuaternion.IDENTITY, 3))

        val ready = fusion.process(
            frame,
            result(frame, TemporalMotionEvidence.CONFIRMED_STATIC_WORLD),
            200L,
            extrinsic().copy(provenance = HeadCameraExtrinsicProvenance.CAMERA2_SENSOR_COORDINATES),
        )
        val moved = fusion.acceptPose(HeadPoseObservation(250L, yawDegrees(15.0), 3))

        assertEquals("head_camera_extrinsic_missing", before.reason)
        assertEquals(LiveMetricFusionReason.METRIC_TRACKS_READY, ready.reason)
        assertEquals(1, ready.temporalTracks.size)
        assertEquals(100L, requireNotNull(ready.capturePose).monotonicTimestampNanos)
        assertEquals(1, moved.temporalTrackCount)
        assertEquals(250L, requireNotNull(moved.cameraPose).monotonicTimestampNanos)
    }

    @Test
    fun `reset removes frame supplied extrinsic as well as prior head state`() {
        val frame = frame(intrinsics = intrinsics())
        val fusion = LiveMetricTemporalFusion(provider(), null)
        fusion.acceptPose(HeadPoseObservation(100L, UnitQuaternion.IDENTITY, 3))
        val first = fusion.process(frame, result(frame), 200L, extrinsic())
        assertEquals(LiveMetricFusionReason.METRIC_TRACKS_READY, first.reason)

        fusion.reset()
        val afterResetPose = fusion.acceptPose(HeadPoseObservation(100L, UnitQuaternion.IDENTITY, 3))
        assertEquals("head_camera_extrinsic_missing", afterResetPose.reason)
        assertNull(afterResetPose.cameraPose)

        val replacement = extrinsic().copy(verificationFingerprint = "b".repeat(64))
        val second = fusion.process(frame, result(frame), 200L, replacement)
        assertEquals(LiveMetricFusionReason.METRIC_TRACKS_READY, second.reason)
    }

    private fun assertUnavailable(result: LiveMetricFusionResult, reason: LiveMetricFusionReason) {
        assertEquals(reason, result.reason)
        assertEquals(1, result.relativeTrackCount)
        assertEquals(0, result.metricTrackCount)
        assertEquals(0, result.propagatedTrackCount)
        assertTrue(result.temporalTracks.isEmpty())
    }

    private fun provider(): MetricDepthCalibrationProvider = MetricDepthCalibrationProvider.single(
        calibration(MetricDepthCalibrationBinding.forProfile(MachineVisionModelProfiles.depthIndoorBalanced, intrinsics())),
    )

    private fun calibration(binding: MetricDepthCalibrationBinding?) = MetricDepthCalibration(
        representation = RelativeDepthRepresentation.DEPTH,
        scale = 1.0,
        offsetMeters = 0.0,
        nearFeature = 1.0,
        farFeature = 10.0,
        residualMeters = 0.1,
        contributingSamples = 2,
        binding = binding,
    )

    private fun frame(intrinsics: CameraIntrinsics?) = VisionFrame(
        frameId = 1L,
        captureMonotonicTimestampNanos = 100L,
        width = 640,
        height = 360,
        synthetic = false,
        cameraIntrinsics = intrinsics,
    )

    private fun result(
        frame: VisionFrame,
        evidence: TemporalMotionEvidence = TemporalMotionEvidence.UNKNOWN,
        depthSamples: List<Double> = listOf(2.0, 2.1, 1.9),
    ): QnnLiveFrameResult {
        val observation = SemanticMaskObservation(
            trackId = "qnn-00000001",
            classId = "door",
            confidence = 0.9,
            relativeDepthSamples = depthSamples,
            maskGeometry = InstanceMaskGeometry(
                frame.width,
                frame.height,
                270,
                130,
                371,
                231,
                320.0,
                180.0,
                8_000,
            ),
            temporalMotionEvidence = evidence,
        )
        return QnnLiveFrameResult(
            frameId = frame.frameId,
            selectedDepthProfileId = MachineVisionModelProfiles.depthIndoorBalanced.id,
            segmentedObjectCount = 1,
            finiteYoloValues = QnnLiveFrameResult.YOLO_FINITE_VALUE_COUNT,
            finitePositiveDepthValues = QnnLiveFrameResult.DEPTH_FINITE_VALUE_COUNT,
            decodeLatencyNanos = 1L,
            yoloPreprocessLatencyNanos = 1L,
            segmentationLatencyNanos = 1L,
            yoloPostprocessLatencyNanos = 1L,
            modelSetupLatencyNanos = 1L,
            depthPreprocessLatencyNanos = 1L,
            depthLatencyNanos = 1L,
            depthPostprocessLatencyNanos = 1L,
            totalLatencyNanos = 8L,
            calibrationState = LiveMetricCalibrationState.CALIBRATED_INTRINSICS_PRESENT,
            inference = MachineVisionInference(
                frameId = frame.frameId,
                completedMonotonicTimestampNanos = frame.captureMonotonicTimestampNanos + 10L,
                fixedVocabularySha256 = MachineVisionModelProfiles.fixedVocabularySha256,
                depthProfileId = MachineVisionModelProfiles.depthIndoorBalanced.id,
                observations = listOf(observation),
            ),
        )
    }

    private fun intrinsics() = CameraIntrinsics(640, 360, 500.0, 500.0, 320.0, 180.0)

    private fun extrinsic() = VerifiedHeadCameraExtrinsic(UnitQuaternion.IDENTITY, "a".repeat(64))

    private fun yawDegrees(degrees: Double): UnitQuaternion {
        val half = degrees * PI / 360.0
        return UnitQuaternion(cos(half), 0.0, sin(half), 0.0)
    }

    private fun imu(
        accuracy: Int = 3,
        frame: CoordinateFrame = CoordinateFrame.COORDINATE_FRAME_HEAD,
        w: Double = 1.0,
    ): ImuReading = ImuReading.newBuilder()
        .setSequenceId(1L)
        .setPose(
            Pose.newBuilder()
                .setReferenceFrame(frame)
                .setMonotonicTimestampNs(1L)
                .setTranslationMeters(Vector3.newBuilder().setX(99.0).setY(88.0).setZ(77.0))
                .setRotation(Quaternion.newBuilder().setW(w)),
        )
        .setLinearAccelerationMetersPerSecondSquared(
            Vector3.newBuilder().setX(100.0).setY(100.0).setZ(100.0),
        )
        .setOrientationAccuracy(accuracy)
        .build()
}
