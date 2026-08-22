// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

data class SegmentedObject(
    val trackId: String,
    val classId: String,
    val confidence: Double,
) {
    init {
        require(trackId.isNotBlank() && trackId.length <= 128)
        require(classId.isNotBlank() && classId.length <= 64)
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

data class SegmentationStageResult(
    val frameId: Long,
    val completedMonotonicTimestampNanos: Long,
    val fixedVocabularySha256: String,
    val objects: List<SegmentedObject>,
) {
    init {
        require(frameId > 0L && completedMonotonicTimestampNanos >= 0L)
        require(SHA256.matches(fixedVocabularySha256))
        require(objects.size <= MAX_OBJECTS)
        require(objects.map(SegmentedObject::trackId).toSet().size == objects.size)
    }

    private companion object {
        val SHA256 = Regex("[a-f0-9]{64}")
        const val MAX_OBJECTS = 256
    }
}

data class DepthStageResult(
    val frameId: Long,
    val completedMonotonicTimestampNanos: Long,
    val depthProfileId: String,
    val relativeDepthSamplesByTrack: Map<String, List<Double>>,
) {
    init {
        require(frameId > 0L && completedMonotonicTimestampNanos >= 0L)
        require(depthProfileId.isNotBlank() && depthProfileId.length <= 128)
        require(relativeDepthSamplesByTrack.size <= 256)
        relativeDepthSamplesByTrack.forEach { (trackId, samples) ->
            require(trackId.isNotBlank() && trackId.length <= 128)
            require(samples.isNotEmpty() && samples.size <= 4_096)
            require(samples.all { it.isFinite() && it > 0.0 })
        }
    }
}

interface StagedMachineVisionInferenceAdapter {
    /** Runs the fixed-vocabulary segmentation stage without requiring a depth profile. */
    fun segment(frame: VisionFrame): SegmentationStageResult

    /** Runs only the timestamp-selected indoor or outdoor metric-depth graph. */
    fun inferDepth(
        frame: VisionFrame,
        depthProfile: MachineVisionModelProfile,
        segmentedObjects: List<SegmentedObject>,
    ): DepthStageResult
}

data class EnvironmentAwareMachineVisionResult(
    val profileDecision: TimestampedDepthProfileDecision?,
    val perception: MachineVisionPipelineResult?,
    val reason: String,
)

/**
 * Enforces segmentation -> environment classification -> depth-profile
 * selection -> metric-depth inference for one correlated frame.
 */
class EnvironmentAwareMachineVisionPipeline(
    private val adapter: StagedMachineVisionInferenceAdapter,
    private val calibration: MetricDepthCalibration,
    private val environmentCoordinator: EnvironmentDepthCoordinator = EnvironmentDepthCoordinator(),
    private val maximumStageAgeNanos: Long = 350_000_000L,
    private val minimumSemanticConfidence: Double = 0.55,
) {
    init {
        require(maximumStageAgeNanos in 1_000_000L..5_000_000_000L)
        require(minimumSemanticConfidence.isFinite() && minimumSemanticConfidence in 0.0..1.0)
    }

    fun setEnvironmentMode(mode: EnvironmentSelectionMode) = environmentCoordinator.setMode(mode)

    fun updateGnss(sample: GnssQualitySample): Boolean = environmentCoordinator.updateGnss(sample)

    fun process(
        frame: VisionFrame,
        nowNanos: Long,
        bothDepthProfilesAvailable: Boolean,
        dedicatedVisualSignal: EnvironmentSignal? = null,
    ): EnvironmentAwareMachineVisionResult {
        require(nowNanos >= frame.captureMonotonicTimestampNanos)
        val segmentation = runCatching { adapter.segment(frame) }.getOrElse {
            return EnvironmentAwareMachineVisionResult(null, null, "segmentation_adapter_failure")
        }
        if (!validStageTimestamp(
                frame,
                segmentation.frameId,
                segmentation.completedMonotonicTimestampNanos,
                nowNanos,
            )
        ) {
            return EnvironmentAwareMachineVisionResult(null, null, "invalid_or_stale_segmentation")
        }
        if (segmentation.fixedVocabularySha256 != MachineVisionModelProfiles.fixedVocabularySha256) {
            return EnvironmentAwareMachineVisionResult(null, null, "fixed_vocabulary_mismatch")
        }
        val eligibleObjects = segmentation.objects.filter {
            it.confidence >= minimumSemanticConfidence && BviClassCatalog.find(it.classId) != null
        }
        val profileDecision = environmentCoordinator.routeFrame(
            frame = frame,
            semanticDetections = eligibleObjects.map { SceneSemanticDetection(it.classId, it.confidence) },
            dedicatedVisualSignal = dedicatedVisualSignal,
            nowNanos = nowNanos,
            bothProfilesAvailable = bothDepthProfilesAvailable,
        )
        val profile = profileDecision.selectedProfile
            ?: return EnvironmentAwareMachineVisionResult(profileDecision, null, "environment_unresolved")
        val environment = profileDecision.selectedEnvironment
            ?: return EnvironmentAwareMachineVisionResult(profileDecision, null, "environment_unresolved")
        val depth = runCatching { adapter.inferDepth(frame, profile, eligibleObjects) }.getOrElse {
            return EnvironmentAwareMachineVisionResult(profileDecision, null, "depth_adapter_failure")
        }
        if (!validStageTimestamp(frame, depth.frameId, depth.completedMonotonicTimestampNanos, nowNanos) ||
            depth.depthProfileId != profile.id
        ) {
            return EnvironmentAwareMachineVisionResult(profileDecision, null, "invalid_or_stale_depth")
        }
        val combined = MachineVisionInference(
            frameId = frame.frameId,
            completedMonotonicTimestampNanos = depth.completedMonotonicTimestampNanos,
            fixedVocabularySha256 = segmentation.fixedVocabularySha256,
            depthProfileId = depth.depthProfileId,
            observations = eligibleObjects.mapNotNull { segmented ->
                depth.relativeDepthSamplesByTrack[segmented.trackId]?.let { samples ->
                    SemanticMaskObservation(
                        trackId = segmented.trackId,
                        classId = segmented.classId,
                        confidence = segmented.confidence,
                        relativeDepthSamples = samples,
                    )
                }
            },
        )
        val perception = MachineVisionPipeline(
            adapter = MachineVisionInferenceAdapter { _, _ -> combined },
            calibration = calibration,
            maximumResultAgeNanos = maximumStageAgeNanos,
            minimumSemanticConfidence = minimumSemanticConfidence,
        ).process(frame, environment, nowNanos)
        return EnvironmentAwareMachineVisionResult(profileDecision, perception, perception.reason)
    }

    private fun validStageTimestamp(
        frame: VisionFrame,
        resultFrameId: Long,
        completedNanos: Long,
        nowNanos: Long,
    ): Boolean = resultFrameId == frame.frameId &&
        completedNanos in frame.captureMonotonicTimestampNanos..nowNanos &&
        nowNanos - frame.captureMonotonicTimestampNanos <= maximumStageAgeNanos
}
