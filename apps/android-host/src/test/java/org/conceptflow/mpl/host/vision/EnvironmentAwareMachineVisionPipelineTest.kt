// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentAwareMachineVisionPipelineTest {
    private val cameraIntrinsics = CameraIntrinsics(1_920, 1_080, 1_000.0, 1_000.0, 960.0, 540.0)

    @Test
    fun segmentationClassifiesSceneBeforeSelectedDepthGraphRuns() {
        val adapter = RecordingAdapter()
        val pipeline = EnvironmentAwareMachineVisionPipeline(
            adapter,
            calibration(MachineVisionModelProfiles.depthOutdoorBalanced),
        )

        val first = pipeline.process(frame(1L, 100L), 120L, bothDepthProfilesAvailable = true)
        val second = pipeline.process(frame(2L, 130L), 150L, bothDepthProfilesAvailable = true)
        val third = pipeline.process(frame(3L, 160L), 180L, bothDepthProfilesAvailable = true)

        assertEquals("environment_unresolved", first.reason)
        assertEquals("environment_unresolved", second.reason)
        assertNull(first.perception)
        assertEquals("processed", third.reason)
        assertEquals(DepthEnvironment.OUTDOOR, third.profileDecision!!.selectedEnvironment)
        assertEquals(listOf("segment", "segment", "segment", "depth:OUTDOOR"), adapter.calls)
        assertEquals(2, third.perception!!.tracks.size)
    }

    @Test
    fun gpsAloneCannotChooseMetricWeights() {
        val adapter = RecordingAdapter(objects = emptyList())
        val pipeline = EnvironmentAwareMachineVisionPipeline(
            adapter,
            calibration(MachineVisionModelProfiles.depthIndoorBalanced),
        )
        pipeline.updateGnss(GnssQualitySample(90L, 16, 9, 35.0, 5.0, 1L))

        val result = pipeline.process(frame(1L, 100L), 120L, bothDepthProfilesAvailable = true)

        assertEquals("environment_unresolved", result.reason)
        assertNull(result.profileDecision!!.selectedProfile)
        assertEquals(listOf("segment"), adapter.calls)
    }

    @Test
    fun manualOverrideSelectsDepthImmediatelyWithoutPretendingAutomaticClassification() {
        val adapter = RecordingAdapter(objects = emptyList())
        val pipeline = EnvironmentAwareMachineVisionPipeline(
            adapter,
            calibration(MachineVisionModelProfiles.depthIndoorBalanced),
        )
        pipeline.setEnvironmentMode(EnvironmentSelectionMode.FORCE_INDOOR)

        val result = pipeline.process(frame(1L, 100L), 120L, bothDepthProfilesAvailable = true)

        assertEquals("processed", result.reason)
        assertEquals("manual_override", result.profileDecision!!.reason)
        assertEquals(listOf("segment", "depth:INDOOR"), adapter.calls)
    }

    @Test
    fun staleSegmentationNeverRunsDepth() {
        val adapter = RecordingAdapter(completedOffsetNanos = 2_000_000L)
        val pipeline = EnvironmentAwareMachineVisionPipeline(
            adapter,
            calibration(MachineVisionModelProfiles.depthIndoorBalanced),
            maximumStageAgeNanos = 1_000_000L,
        )

        val result = pipeline.process(frame(1L, 100L), 2_000_100L, bothDepthProfilesAvailable = true)

        assertEquals("invalid_or_stale_segmentation", result.reason)
        assertEquals(listOf("segment"), adapter.calls)
    }

    @Test
    fun invalidDedicatedVisualFrameCorrelationIsRejected() {
        val pipeline = EnvironmentAwareMachineVisionPipeline(
            RecordingAdapter(),
            calibration(MachineVisionModelProfiles.depthIndoorBalanced),
        )
        val signal = EnvironmentSignal(
            "camera-scene-model",
            EnvironmentSignalFamily.CAMERA,
            100L,
            0.1,
            0.9,
            0.9,
            originatingFrameId = 2L,
        )

        val failure = runCatching {
            pipeline.process(frame(1L, 100L), 120L, true, signal)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun segmentationRejectsDuplicateTrackIdentity() {
        val duplicate = SegmentedObject("duplicate", "door", 0.9)

        val failure = runCatching {
            SegmentationStageResult(
                1L,
                100L,
                MachineVisionModelProfiles.fixedVocabularySha256,
                listOf(duplicate, duplicate.copy(classId = "chair")),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun rejectsDepthSamplesThatAreNotCorrelatedToEligibleMaskIds() {
        val pipeline = EnvironmentAwareMachineVisionPipeline(
            CorrelatedAdapter(includeUnrequestedDepth = true),
            calibration(MachineVisionModelProfiles.depthIndoorBalanced),
        )
        pipeline.setEnvironmentMode(EnvironmentSelectionMode.FORCE_INDOOR)

        val result = pipeline.process(frameWithIntrinsics(), 120L, bothDepthProfilesAvailable = true)

        assertEquals("invalid_depth_correlation", result.reason)
        assertNull(result.perception)
    }

    @Test
    fun carriesCorrelatedMaskGeometryIntoMetricVector() {
        val pipeline = EnvironmentAwareMachineVisionPipeline(
            CorrelatedAdapter(),
            calibration(MachineVisionModelProfiles.depthIndoorBalanced),
        )
        pipeline.setEnvironmentMode(EnvironmentSelectionMode.FORCE_INDOOR)

        val result = pipeline.process(frameWithIntrinsics(), 120L, bothDepthProfilesAvailable = true)
        val track = result.perception!!.tracks.single()

        assertEquals("processed", result.reason)
        assertEquals("door-mask", track.trackId)
        assertEquals(1L, track.frameId)
        assertEquals(100L, track.sourceCaptureMonotonicTimestampNanos)
        assertEquals(110L, track.sourceInferenceMonotonicTimestampNanos)
        assertTrue(track.maskGeometry != null)
        assertTrue(track.cameraVectorMeters != null)
    }

    @Test
    fun rejectsSwappedMaskFingerprintEvenWhenTrackIdMatches() {
        val pipeline = EnvironmentAwareMachineVisionPipeline(
            CorrelatedAdapter(swapMaskFingerprint = true),
            calibration(MachineVisionModelProfiles.depthIndoorBalanced),
        )
        pipeline.setEnvironmentMode(EnvironmentSelectionMode.FORCE_INDOOR)

        val result = pipeline.process(frameWithIntrinsics(), 120L, bothDepthProfilesAvailable = true)

        assertEquals("invalid_mask_fingerprint_correlation", result.reason)
        assertNull(result.perception)
    }

    @Test
    fun explicitRoutingPassesExactTierAndFailsClosedWhenUnavailable() {
        val adapter = RecordingAdapter(objects = emptyList())
        val lowPower = MachineVisionModelProfiles.depthIndoorLowPower
        val pipeline = EnvironmentAwareMachineVisionPipeline(adapter, calibration(lowPower))
        pipeline.setEnvironmentMode(EnvironmentSelectionMode.FORCE_INDOOR)
        val request = DepthModelRoutingRequest(
            DepthEnvironment.INDOOR,
            serviceTier = DepthServiceTier.LOW_POWER,
            maximumEndToEndLatencyMillis = 100.0,
        )

        val accepted = pipeline.process(frame(1L, 100L), 120L, request, setOf(lowPower.id))
        val unavailable = pipeline.process(frame(2L, 130L), 150L, request, emptySet())

        assertEquals("processed", accepted.reason)
        assertEquals(lowPower.id, accepted.profileDecision!!.selectedProfile!!.id)
        assertEquals(listOf(lowPower.id), adapter.depthProfileIds)
        assertEquals("depth_routing_selected_artifact_unavailable", unavailable.reason)
    }

    @Test
    fun onePipelineResolvesExactCalibrationAcrossEnvironmentAndResolutionSwitches() {
        val adapter = RecordingAdapter(objects = emptyList())
        val indoorBalanced = MachineVisionModelProfiles.depthIndoorBalanced
        val outdoorLowPower = MachineVisionModelProfiles.depthOutdoorLowPower
        val missingIndoorLowPower = MachineVisionModelProfiles.depthIndoorLowPower
        val calibrationStore = BoundedMetricDepthCalibrationStore(
            listOf(calibration(indoorBalanced), calibration(outdoorLowPower)),
        )
        val pipeline = EnvironmentAwareMachineVisionPipeline(adapter, calibrationStore)

        pipeline.setEnvironmentMode(EnvironmentSelectionMode.FORCE_INDOOR)
        val indoor = pipeline.process(
            frame(1L, 100L),
            120L,
            DepthModelRoutingRequest(DepthEnvironment.INDOOR, maximumEndToEndLatencyMillis = 140.0),
            setOf(indoorBalanced.id),
        )
        pipeline.setEnvironmentMode(EnvironmentSelectionMode.FORCE_OUTDOOR)
        val outdoor = pipeline.process(
            frame(2L, 130L),
            150L,
            DepthModelRoutingRequest(
                DepthEnvironment.OUTDOOR,
                serviceTier = DepthServiceTier.LOW_POWER,
                maximumEndToEndLatencyMillis = 100.0,
            ),
            setOf(outdoorLowPower.id),
        )
        pipeline.setEnvironmentMode(EnvironmentSelectionMode.FORCE_INDOOR)
        val missing = pipeline.process(
            frame(3L, 160L),
            180L,
            DepthModelRoutingRequest(
                DepthEnvironment.INDOOR,
                serviceTier = DepthServiceTier.LOW_POWER,
                maximumEndToEndLatencyMillis = 100.0,
            ),
            setOf(missingIndoorLowPower.id),
        )

        assertEquals("processed", indoor.reason)
        assertEquals(indoorBalanced.id, indoor.profileDecision!!.selectedProfile!!.id)
        assertEquals("processed", outdoor.reason)
        assertEquals(outdoorLowPower.id, outdoor.profileDecision!!.selectedProfile!!.id)
        assertEquals("calibration_unavailable_for_profile", missing.reason)
        assertEquals(listOf(indoorBalanced.id, outdoorLowPower.id), adapter.depthProfileIds)
    }

    private fun frame(id: Long, timestamp: Long) = VisionFrame(
        id,
        timestamp,
        1_920,
        1_080,
        true,
        cameraIntrinsics,
    )

    private fun frameWithIntrinsics() = VisionFrame(
        1L,
        100L,
        1_920,
        1_080,
        synthetic = false,
        cameraIntrinsics = cameraIntrinsics,
    )

    private fun calibration(profile: MachineVisionModelProfile) = TwoAnchorMetricDepthCalibrator().calibrate(
        listOf(
            GuidedCalibrationSample("door", ReferenceDistance.NEAR_TWO_FEET, 2.0, 0.9),
            GuidedCalibrationSample("door", ReferenceDistance.FAR_EIGHT_FEET, 8.0, 0.9),
        ),
        RelativeDepthRepresentation.DEPTH,
        MetricDepthCalibrationBinding.forProfile(profile, cameraIntrinsics),
    )!!

    private class CorrelatedAdapter(
        private val includeUnrequestedDepth: Boolean = false,
        private val swapMaskFingerprint: Boolean = false,
    ) : StagedMachineVisionInferenceAdapter {
        override fun segment(frame: VisionFrame) = SegmentationStageResult(
            frame.frameId,
            frame.captureMonotonicTimestampNanos + 5L,
            MachineVisionModelProfiles.fixedVocabularySha256,
            listOf(
                SegmentedObject(
                    "door-mask",
                    "door",
                    0.95,
                    InstanceMaskGeometry(
                        1_920,
                        1_080,
                        735,
                        335,
                        1_186,
                        746,
                        960.0,
                        540.0,
                        120_000,
                    ),
                    MASK_FINGERPRINT,
                ),
            ),
        )

        override fun inferDepth(
            frame: VisionFrame,
            depthProfile: MachineVisionModelProfile,
            segmentedObjects: List<SegmentedObject>,
        ) = DepthStageResult(
            frame.frameId,
            frame.captureMonotonicTimestampNanos + 10L,
            depthProfile.id,
            buildMap {
                put("door-mask", listOf(4.5, 5.0, 5.5))
                if (includeUnrequestedDepth) put("not-a-mask", listOf(5.0))
            },
            mapOf(
                "door-mask" to if (swapMaskFingerprint) SWAPPED_MASK_FINGERPRINT else MASK_FINGERPRINT,
            ),
        )

        private companion object {
            val MASK_FINGERPRINT = "a".repeat(64)
            val SWAPPED_MASK_FINGERPRINT = "b".repeat(64)
        }
    }

    private class RecordingAdapter(
        private val objects: List<SegmentedObject> = listOf(
            SegmentedObject("crosswalk-1", "crosswalk", 0.95),
            SegmentedObject("signal-1", "traffic_light", 0.95),
        ),
        private val completedOffsetNanos: Long = 10L,
    ) : StagedMachineVisionInferenceAdapter {
        val calls = mutableListOf<String>()
        val depthProfileIds = mutableListOf<String>()

        override fun segment(frame: VisionFrame): SegmentationStageResult {
            calls += "segment"
            return SegmentationStageResult(
                frame.frameId,
                frame.captureMonotonicTimestampNanos + completedOffsetNanos,
                MachineVisionModelProfiles.fixedVocabularySha256,
                objects,
            )
        }

        override fun inferDepth(
            frame: VisionFrame,
            depthProfile: MachineVisionModelProfile,
            segmentedObjects: List<SegmentedObject>,
        ): DepthStageResult {
            calls += "depth:${depthProfile.depthEnvironment}"
            depthProfileIds += depthProfile.id
            return DepthStageResult(
                frame.frameId,
                frame.captureMonotonicTimestampNanos + completedOffsetNanos,
                depthProfile.id,
                segmentedObjects.associate { it.trackId to listOf(4.5, 5.0, 5.5) },
            )
        }
    }
}
