// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentAwareMachineVisionPipelineTest {
    private val calibration = TwoAnchorMetricDepthCalibrator().calibrate(
        listOf(
            GuidedCalibrationSample("door", ReferenceDistance.NEAR_TWO_FEET, 2.0, 0.9),
            GuidedCalibrationSample("door", ReferenceDistance.FAR_EIGHT_FEET, 8.0, 0.9),
        ),
        RelativeDepthRepresentation.DEPTH,
    )!!

    @Test
    fun segmentationClassifiesSceneBeforeSelectedDepthGraphRuns() {
        val adapter = RecordingAdapter()
        val pipeline = EnvironmentAwareMachineVisionPipeline(adapter, calibration)

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
        val pipeline = EnvironmentAwareMachineVisionPipeline(adapter, calibration)
        pipeline.updateGnss(GnssQualitySample(90L, 16, 9, 35.0, 5.0, 1L))

        val result = pipeline.process(frame(1L, 100L), 120L, bothDepthProfilesAvailable = true)

        assertEquals("environment_unresolved", result.reason)
        assertNull(result.profileDecision!!.selectedProfile)
        assertEquals(listOf("segment"), adapter.calls)
    }

    @Test
    fun manualOverrideSelectsDepthImmediatelyWithoutPretendingAutomaticClassification() {
        val adapter = RecordingAdapter(objects = emptyList())
        val pipeline = EnvironmentAwareMachineVisionPipeline(adapter, calibration)
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
            calibration,
            maximumStageAgeNanos = 1_000_000L,
        )

        val result = pipeline.process(frame(1L, 100L), 2_000_100L, bothDepthProfilesAvailable = true)

        assertEquals("invalid_or_stale_segmentation", result.reason)
        assertEquals(listOf("segment"), adapter.calls)
    }

    @Test
    fun invalidDedicatedVisualFrameCorrelationIsRejected() {
        val pipeline = EnvironmentAwareMachineVisionPipeline(RecordingAdapter(), calibration)
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

    private fun frame(id: Long, timestamp: Long) = VisionFrame(id, timestamp, 1_920, 1_080, true)

    private class RecordingAdapter(
        private val objects: List<SegmentedObject> = listOf(
            SegmentedObject("crosswalk-1", "crosswalk", 0.95),
            SegmentedObject("signal-1", "traffic_light", 0.95),
        ),
        private val completedOffsetNanos: Long = 10L,
    ) : StagedMachineVisionInferenceAdapter {
        val calls = mutableListOf<String>()

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
            return DepthStageResult(
                frame.frameId,
                frame.captureMonotonicTimestampNanos + completedOffsetNanos,
                depthProfile.id,
                segmentedObjects.associate { it.trackId to listOf(4.5, 5.0, 5.5) },
            )
        }
    }
}
