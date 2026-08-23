// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

data class VisionFrame(
    val frameId: Long,
    val captureMonotonicTimestampNanos: Long,
    val width: Int,
    val height: Int,
    val synthetic: Boolean,
    val cameraIntrinsics: CameraIntrinsics? = null,
) {
    init {
        require(frameId > 0L && captureMonotonicTimestampNanos >= 0L)
        require(width in 1..7_680 && height in 1..4_320)
        require(cameraIntrinsics == null ||
            (cameraIntrinsics.imageWidthPixels == width && cameraIntrinsics.imageHeightPixels == height))
    }
}

enum class TemporalMotionEvidence {
    CONFIRMED_STATIC_WORLD,
    DYNAMIC,
    UNKNOWN,
}

data class SemanticMaskObservation(
    val trackId: String,
    val classId: String,
    val confidence: Double,
    val relativeDepthSamples: List<Double>,
    val maskGeometry: InstanceMaskGeometry? = null,
    val temporalMotionEvidence: TemporalMotionEvidence = TemporalMotionEvidence.UNKNOWN,
) {
    init {
        require(trackId.isNotBlank() && trackId.length <= 128)
        require(classId.isNotBlank() && classId.length <= 64)
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(relativeDepthSamples.isNotEmpty() && relativeDepthSamples.size <= 4_096)
        require(relativeDepthSamples.all { it.isFinite() && it > 0.0 })
    }
}

data class MachineVisionInference(
    val frameId: Long,
    val completedMonotonicTimestampNanos: Long,
    val fixedVocabularySha256: String,
    val depthProfileId: String,
    val observations: List<SemanticMaskObservation>,
) {
    init {
        require(frameId > 0L && completedMonotonicTimestampNanos >= 0L)
        require(SHA256.matches(fixedVocabularySha256))
        require(depthProfileId.isNotBlank() && depthProfileId.length <= 128)
        require(observations.size <= MAX_OBSERVATIONS)
        require(observations.map(SemanticMaskObservation::trackId).toSet().size == observations.size)
    }

    private companion object {
        val SHA256 = Regex("[a-f0-9]{64}")
        const val MAX_OBSERVATIONS = 256
    }
}

fun interface MachineVisionInferenceAdapter {
    fun infer(frame: VisionFrame, depthProfile: MachineVisionModelProfile): MachineVisionInference
}

data class MetricSemanticTrack(
    val frameId: Long,
    val trackId: String,
    val classId: String,
    val confidence: Double,
    val representativeDistance: MetricDepthEstimate,
    val depthEnvironment: DepthEnvironment,
    val sourceCaptureMonotonicTimestampNanos: Long = 0L,
    val sourceInferenceMonotonicTimestampNanos: Long = sourceCaptureMonotonicTimestampNanos,
    val maskGeometry: InstanceMaskGeometry? = null,
    val cameraVectorMeters: MetricVector3? = null,
    val usedDimensionPrior: Boolean = false,
    val rejectedDimensionPriorAsOutlier: Boolean = false,
    val temporalMotionEvidence: TemporalMotionEvidence = TemporalMotionEvidence.UNKNOWN,
) {
    init {
        require(sourceCaptureMonotonicTimestampNanos >= 0L)
        require(sourceInferenceMonotonicTimestampNanos >= sourceCaptureMonotonicTimestampNanos)
    }
}

data class MachineVisionPipelineResult(
    val tracks: List<MetricSemanticTrack>,
    val rejectedUnknownClasses: Int,
    val rejectedLowConfidence: Int,
    val rejectedStale: Boolean,
    val reason: String,
    val rejectedInvalidGeometry: Int = 0,
)

enum class CalibrationBindingPolicy {
    REQUIRE_BOUND,
    ALLOW_SYNTHETIC_UNBOUND,
}

/**
 * Model-neutral semantic/depth fusion. Immediate body-clearance geometry stays
 * outside this slower semantic path.
 */
class MachineVisionPipeline(
    private val adapter: MachineVisionInferenceAdapter,
    private val calibration: MetricDepthCalibration,
    private val maximumResultAgeNanos: Long = 350_000_000L,
    private val minimumSemanticConfidence: Double = 0.55,
    private val calibrationBindingPolicy: CalibrationBindingPolicy =
        CalibrationBindingPolicy.ALLOW_SYNTHETIC_UNBOUND,
) {
    init {
        require(maximumResultAgeNanos in 1_000_000L..5_000_000_000L)
        require(minimumSemanticConfidence.isFinite() && minimumSemanticConfidence in 0.0..1.0)
    }

    fun process(
        frame: VisionFrame,
        environment: DepthEnvironment,
        nowNanos: Long,
    ): MachineVisionPipelineResult = process(
        frame,
        MachineVisionModelProfiles.depth(environment),
        nowNanos,
    )

    fun process(
        frame: VisionFrame,
        depthRoutingRequest: DepthModelRoutingRequest,
        availableDepthProfileIds: Set<String>,
        nowNanos: Long,
    ): MachineVisionPipelineResult {
        val routingDecision = DepthModelRoutingPolicy.select(depthRoutingRequest, availableDepthProfileIds)
        val selectedProfile = routingDecision.profile ?: return MachineVisionPipelineResult(
            emptyList(),
            0,
            0,
            false,
            "depth_routing_${routingDecision.reason}",
        )
        return process(frame, selectedProfile, nowNanos)
    }

    fun process(
        frame: VisionFrame,
        depthRoutingRequest: DepthModelRoutingRequest,
        modelBundleStatus: ModelBundleStatus,
        nowNanos: Long,
    ): MachineVisionPipelineResult = process(
        frame,
        depthRoutingRequest,
        modelBundleStatus.availableProfileIds,
        nowNanos,
    )

    fun process(
        frame: VisionFrame,
        selectedDepthProfile: MachineVisionModelProfile,
        nowNanos: Long,
    ): MachineVisionPipelineResult {
        require(nowNanos >= frame.captureMonotonicTimestampNanos)
        if (selectedDepthProfile.kind != MachineVisionModelKind.METRIC_DEPTH ||
            selectedDepthProfile.depthEnvironment == null
        ) {
            return MachineVisionPipelineResult(emptyList(), 0, 0, false, "invalid_depth_profile")
        }
        calibrationBindingFailure(frame, selectedDepthProfile)?.let { reason ->
            return MachineVisionPipelineResult(emptyList(), 0, 0, false, reason)
        }
        val environment = requireNotNull(selectedDepthProfile.depthEnvironment)
        val inference = runCatching { adapter.infer(frame, selectedDepthProfile) }.getOrElse {
            return MachineVisionPipelineResult(emptyList(), 0, 0, false, "adapter_failure")
        }
        if (inference.frameId != frame.frameId || inference.completedMonotonicTimestampNanos < frame.captureMonotonicTimestampNanos) {
            return MachineVisionPipelineResult(emptyList(), 0, 0, false, "invalid_frame_correlation")
        }
        if (inference.completedMonotonicTimestampNanos > nowNanos ||
            nowNanos - frame.captureMonotonicTimestampNanos > maximumResultAgeNanos
        ) {
            return MachineVisionPipelineResult(emptyList(), 0, 0, true, "stale_result")
        }
        if (inference.fixedVocabularySha256 != MachineVisionModelProfiles.fixedVocabularySha256) {
            return MachineVisionPipelineResult(emptyList(), 0, 0, false, "fixed_vocabulary_mismatch")
        }
        if (inference.depthProfileId != selectedDepthProfile.id) {
            return MachineVisionPipelineResult(emptyList(), 0, 0, false, "depth_profile_mismatch")
        }
        var unknown = 0
        var lowConfidence = 0
        var invalidGeometry = 0
        val tracks = inference.observations.mapNotNull { observation ->
            if (BviClassCatalog.find(observation.classId) == null) {
                unknown += 1
                return@mapNotNull null
            }
            if (observation.confidence < minimumSemanticConfidence) {
                lowConfidence += 1
                return@mapNotNull null
            }
            val geometry = observation.maskGeometry
            if (geometry != null && !geometry.matches(frame)) {
                invalidGeometry += 1
                return@mapNotNull null
            }
            val relativeMedian = median(observation.relativeDepthSamples)
            val calibratedEstimate = calibration.estimate(relativeMedian) ?: return@mapNotNull null
            val intrinsics = frame.cameraIntrinsics
            val dimensionPrior = if (geometry != null && intrinsics != null) {
                PinholeDimensionEstimator.estimate(
                    MaskExtentObservation(
                        classId = observation.classId,
                        maskWidthPixels = geometry.widthPixels,
                        maskHeightPixels = geometry.heightPixels,
                        focalLengthXPixels = intrinsics.focalLengthXPixels,
                        focalLengthYPixels = intrinsics.focalLengthYPixels,
                        confidence = observation.confidence,
                    ),
                )
            } else {
                null
            }
            val fused = RobustMetricDepthFusion.fuse(
                calibratedDepth = calibratedEstimate,
                calibratedConfidence = observation.confidence,
                dimensionPrior = dimensionPrior,
                dimensionConfidence = observation.confidence,
            )
            val cameraVector = if (geometry != null && intrinsics != null) {
                intrinsics.vectorAtDepth(
                    geometry.centroidXPixels,
                    geometry.centroidYPixels,
                    fused.estimate.distanceMeters,
                )
            } else {
                null
            }
            MetricSemanticTrack(
                frameId = frame.frameId,
                trackId = observation.trackId,
                classId = observation.classId,
                confidence = observation.confidence,
                representativeDistance = fused.estimate,
                depthEnvironment = environment,
                sourceCaptureMonotonicTimestampNanos = frame.captureMonotonicTimestampNanos,
                sourceInferenceMonotonicTimestampNanos = inference.completedMonotonicTimestampNanos,
                maskGeometry = geometry,
                cameraVectorMeters = cameraVector,
                usedDimensionPrior = fused.usedDimensionPrior,
                rejectedDimensionPriorAsOutlier = fused.rejectedDimensionPriorAsOutlier,
                temporalMotionEvidence = observation.temporalMotionEvidence,
            )
        }
        return MachineVisionPipelineResult(
            tracks,
            unknown,
            lowConfidence,
            false,
            "processed",
            invalidGeometry,
        )
    }

    private fun calibrationBindingFailure(
        frame: VisionFrame,
        selectedDepthProfile: MachineVisionModelProfile,
    ): String? {
        if (calibration.provenance.kind == MetricDepthProvenanceKind.PINNED_OFFICIAL_NATIVE_METRIC) {
            val canonical = OfficialDepthAnythingV2MetricSemanticsProvider.resolve(selectedDepthProfile)
                ?: return "native_metric_profile_unverified"
            return if (calibration == canonical &&
                calibration.provenance.depthProfileId == selectedDepthProfile.id
            ) {
                null
            } else {
                "native_metric_profile_mismatch"
            }
        }
        val binding = calibration.binding
        if (binding == null) {
            return if (calibrationBindingPolicy == CalibrationBindingPolicy.ALLOW_SYNTHETIC_UNBOUND &&
                frame.synthetic
            ) {
                null
            } else {
                "calibration_unbound"
            }
        }
        if (binding.depthProfileId != selectedDepthProfile.id) return "calibration_depth_profile_mismatch"
        val intrinsics = frame.cameraIntrinsics ?: return "calibration_intrinsics_missing"
        if (binding.cameraIntrinsicsFingerprint != intrinsics.calibrationFingerprint) {
            return "calibration_intrinsics_mismatch"
        }
        return null
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }
}
