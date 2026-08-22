// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

data class SegmentedObject(
    val trackId: String,
    val classId: String,
    val confidence: Double,
    val maskGeometry: InstanceMaskGeometry? = null,
    val maskFingerprint: String? = null,
    val temporalMotionEvidence: TemporalMotionEvidence = TemporalMotionEvidence.UNKNOWN,
) {
    init {
        require(trackId.isNotBlank() && trackId.length <= 128)
        require(classId.isNotBlank() && classId.length <= 64)
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(maskGeometry == null || maskFingerprint != null)
        maskFingerprint?.let { require(MASK_FINGERPRINT.matches(it)) }
    }

    private companion object {
        val MASK_FINGERPRINT = Regex("[a-f0-9]{64}")
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
    val maskFingerprintsByTrack: Map<String, String> = emptyMap(),
) {
    init {
        require(frameId > 0L && completedMonotonicTimestampNanos >= 0L)
        require(depthProfileId.isNotBlank() && depthProfileId.length <= 128)
        require(relativeDepthSamplesByTrack.size <= 256)
        require(maskFingerprintsByTrack.keys.all { it in relativeDepthSamplesByTrack })
        require(maskFingerprintsByTrack.values.all { MASK_FINGERPRINT.matches(it) })
        relativeDepthSamplesByTrack.forEach { (trackId, samples) ->
            require(trackId.isNotBlank() && trackId.length <= 128)
            require(samples.isNotEmpty() && samples.size <= 4_096)
            require(samples.all { it.isFinite() && it > 0.0 })
        }
    }

    private companion object {
        val MASK_FINGERPRINT = Regex("[a-f0-9]{64}")
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
    val depthModelRoutingDecision: DepthModelRoutingDecision? = null,
)

/**
 * Enforces segmentation -> environment classification -> depth-profile
 * selection -> metric-depth inference for one correlated frame.
 */
class EnvironmentAwareMachineVisionPipeline(
    private val adapter: StagedMachineVisionInferenceAdapter,
    private val calibrationProvider: MetricDepthCalibrationProvider,
    private val environmentCoordinator: EnvironmentDepthCoordinator = EnvironmentDepthCoordinator(),
    private val maximumStageAgeNanos: Long = 350_000_000L,
    private val minimumSemanticConfidence: Double = 0.55,
) {
    constructor(
        adapter: StagedMachineVisionInferenceAdapter,
        calibration: MetricDepthCalibration,
        environmentCoordinator: EnvironmentDepthCoordinator = EnvironmentDepthCoordinator(),
        maximumStageAgeNanos: Long = 350_000_000L,
        minimumSemanticConfidence: Double = 0.55,
    ) : this(
        adapter,
        MetricDepthCalibrationProvider.single(calibration),
        environmentCoordinator,
        maximumStageAgeNanos,
        minimumSemanticConfidence,
    )

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
        val balancedProfiles = if (bothDepthProfilesAvailable) {
            setOf(
                MachineVisionModelProfiles.depthIndoorBalanced.id,
                MachineVisionModelProfiles.depthOutdoorBalanced.id,
            )
        } else {
            emptySet()
        }
        return processInternal(
            frame,
            nowNanos,
            depthRoutingRequest = null,
            availableDepthProfileIds = balancedProfiles,
            bothDepthProfilesAvailable = bothDepthProfilesAvailable,
            dedicatedVisualSignal = dedicatedVisualSignal,
        )
    }

    fun process(
        frame: VisionFrame,
        nowNanos: Long,
        depthRoutingRequest: DepthModelRoutingRequest,
        availableDepthProfileIds: Set<String>,
        dedicatedVisualSignal: EnvironmentSignal? = null,
    ): EnvironmentAwareMachineVisionResult = processInternal(
        frame,
        nowNanos,
        depthRoutingRequest,
        availableDepthProfileIds,
        bothDepthProfilesAvailable = DepthEnvironment.entries.all { environment ->
            MachineVisionModelProfiles.allProfiles.any { profile ->
                profile.id in availableDepthProfileIds && profile.depthEnvironment == environment
            }
        },
        dedicatedVisualSignal,
    )

    fun process(
        frame: VisionFrame,
        nowNanos: Long,
        depthRoutingRequest: DepthModelRoutingRequest,
        modelBundleStatus: ModelBundleStatus,
        dedicatedVisualSignal: EnvironmentSignal? = null,
    ): EnvironmentAwareMachineVisionResult = process(
        frame,
        nowNanos,
        depthRoutingRequest,
        modelBundleStatus.availableProfileIds,
        dedicatedVisualSignal,
    )

    private fun processInternal(
        frame: VisionFrame,
        nowNanos: Long,
        depthRoutingRequest: DepthModelRoutingRequest?,
        availableDepthProfileIds: Set<String>,
        bothDepthProfilesAvailable: Boolean,
        dedicatedVisualSignal: EnvironmentSignal?,
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
        if (segmentation.objects.any { it.maskGeometry != null && !it.maskGeometry.matches(frame) }) {
            return EnvironmentAwareMachineVisionResult(null, null, "invalid_mask_geometry")
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
        val environment = profileDecision.selectedEnvironment
            ?: return EnvironmentAwareMachineVisionResult(profileDecision, null, "environment_unresolved")
        if (depthRoutingRequest != null && depthRoutingRequest.environment != environment) {
            return EnvironmentAwareMachineVisionResult(
                profileDecision,
                null,
                "depth_routing_environment_mismatch",
            )
        }
        val effectiveRoutingRequest = depthRoutingRequest ?: DepthModelRoutingRequest(
            environment = environment,
            serviceTier = DepthServiceTier.BALANCED,
            maximumEndToEndLatencyMillis = COMPATIBILITY_BALANCED_BUDGET_MILLIS,
        )
        val routingDecision = DepthModelRoutingPolicy.select(
            effectiveRoutingRequest,
            availableDepthProfileIds,
        )
        val profile = routingDecision.profile ?: return EnvironmentAwareMachineVisionResult(
            profileDecision,
            null,
            "depth_routing_${routingDecision.reason}",
            routingDecision,
        )
        val routedProfileDecision = profileDecision.copy(selectedProfile = profile)
        val intrinsics = frame.cameraIntrinsics ?: return EnvironmentAwareMachineVisionResult(
            routedProfileDecision,
            null,
            "calibration_intrinsics_missing",
            routingDecision,
        )
        val calibration = calibrationProvider.resolve(profile, intrinsics)
            ?: return EnvironmentAwareMachineVisionResult(
                routedProfileDecision,
                null,
                "calibration_unavailable_for_profile",
                routingDecision,
            )
        val allowLegacySyntheticCalibration = depthRoutingRequest == null &&
            frame.synthetic && calibration.binding == null
        if (calibration.binding == null && !allowLegacySyntheticCalibration) {
            return EnvironmentAwareMachineVisionResult(
                routedProfileDecision,
                null,
                "calibration_unavailable_for_profile",
                routingDecision,
            )
        }
        val depth = runCatching { adapter.inferDepth(frame, profile, eligibleObjects) }.getOrElse {
            return EnvironmentAwareMachineVisionResult(
                routedProfileDecision,
                null,
                "depth_adapter_failure",
                routingDecision,
            )
        }
        if (!validStageTimestamp(frame, depth.frameId, depth.completedMonotonicTimestampNanos, nowNanos) ||
            depth.depthProfileId != profile.id
        ) {
            return EnvironmentAwareMachineVisionResult(
                routedProfileDecision,
                null,
                "invalid_or_stale_depth",
                routingDecision,
            )
        }
        val eligibleTrackIds = eligibleObjects.map(SegmentedObject::trackId).toSet()
        if (depth.relativeDepthSamplesByTrack.keys.any { it !in eligibleTrackIds }) {
            return EnvironmentAwareMachineVisionResult(
                routedProfileDecision,
                null,
                "invalid_depth_correlation",
                routingDecision,
            )
        }
        if (eligibleObjects.any { segmented ->
                segmented.maskFingerprint != null &&
                    depth.maskFingerprintsByTrack[segmented.trackId] != segmented.maskFingerprint
            }
        ) {
            return EnvironmentAwareMachineVisionResult(
                routedProfileDecision,
                null,
                "invalid_mask_fingerprint_correlation",
                routingDecision,
            )
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
                        maskGeometry = segmented.maskGeometry,
                        temporalMotionEvidence = segmented.temporalMotionEvidence,
                    )
                }
            },
        )
        val rawPerception = MachineVisionPipeline(
            adapter = MachineVisionInferenceAdapter { _, _ -> combined },
            calibration = calibration,
            maximumResultAgeNanos = maximumStageAgeNanos,
            minimumSemanticConfidence = minimumSemanticConfidence,
            calibrationBindingPolicy = if (allowLegacySyntheticCalibration) {
                CalibrationBindingPolicy.ALLOW_SYNTHETIC_UNBOUND
            } else {
                CalibrationBindingPolicy.REQUIRE_BOUND
            },
        ).process(frame, profile, nowNanos)
        val perception = rawPerception.copy(
            tracks = rawPerception.tracks.map { track ->
                track.copy(
                    representativeDistance = track.representativeDistance.copy(
                        uncertaintyMeters = track.representativeDistance.uncertaintyMeters *
                            routedProfileDecision.uncertaintyMultiplier,
                    ),
                )
            },
        )
        return EnvironmentAwareMachineVisionResult(
            routedProfileDecision,
            perception,
            perception.reason,
            routingDecision,
        )
    }

    private fun validStageTimestamp(
        frame: VisionFrame,
        resultFrameId: Long,
        completedNanos: Long,
        nowNanos: Long,
    ): Boolean = resultFrameId == frame.frameId &&
        completedNanos in frame.captureMonotonicTimestampNanos..nowNanos &&
        nowNanos - frame.captureMonotonicTimestampNanos <= maximumStageAgeNanos

    private companion object {
        const val COMPATIBILITY_BALANCED_BUDGET_MILLIS = 300.0
    }
}
