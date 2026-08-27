// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

enum class RelativeDepthRepresentation {
    DEPTH,
    INVERSE_DEPTH,
}

data class GuidedCalibrationSample(
    val classId: String,
    val referenceDistance: ReferenceDistance,
    val relativeDepthValue: Double,
    val confidence: Double,
) {
    init {
        require(classId.isNotBlank())
        require(relativeDepthValue.isFinite() && relativeDepthValue > 0.0)
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

data class MetricDepthEstimate(
    val distanceMeters: Double,
    /** Null means the model has no validated per-observation error bound. */
    val uncertaintyMeters: Double?,
    val extrapolated: Boolean,
    val provenance: MetricDepthProvenance = MetricDepthProvenance.guidedTwoAnchor,
    val uncertaintyBasis: MetricDepthUncertaintyBasis = MetricDepthUncertaintyBasis.GUIDED_RESIDUAL,
) {
    init {
        require(distanceMeters.isFinite() && distanceMeters > 0.0)
        require(uncertaintyMeters == null || (uncertaintyMeters.isFinite() && uncertaintyMeters >= 0.0))
        require(
            (uncertaintyBasis == MetricDepthUncertaintyBasis.UNQUANTIFIED_MODEL_ERROR) ==
                (uncertaintyMeters == null),
        )
    }
}

enum class MetricDepthProvenanceKind {
    GUIDED_TWO_ANCHOR,
    PINNED_OFFICIAL_NATIVE_METRIC,
}

enum class MetricDepthUncertaintyBasis {
    GUIDED_RESIDUAL,
    UNQUANTIFIED_MODEL_ERROR,
    DIMENSION_PRIOR_BOUND,
}

data class MetricDepthProvenance(
    val kind: MetricDepthProvenanceKind,
    val depthProfileId: String? = null,
    val sourceModelId: String? = null,
    val sourceRevision: String? = null,
    val sourceCheckpointSha256: String? = null,
    val declaredMaximumDepthMeters: Double? = null,
) {
    init {
        if (kind == MetricDepthProvenanceKind.PINNED_OFFICIAL_NATIVE_METRIC) {
            require(!depthProfileId.isNullOrBlank() && depthProfileId.length <= 128)
            require(!sourceModelId.isNullOrBlank())
            require(GIT_REVISION.matches(sourceRevision.orEmpty()))
            require(SHA256.matches(sourceCheckpointSha256.orEmpty()))
            require(declaredMaximumDepthMeters?.let { it.isFinite() && it > 0.0 } == true)
        } else {
            require(depthProfileId == null && sourceModelId == null && sourceRevision == null &&
                sourceCheckpointSha256 == null)
            require(declaredMaximumDepthMeters == null)
        }
    }

    companion object {
        val guidedTwoAnchor = MetricDepthProvenance(MetricDepthProvenanceKind.GUIDED_TWO_ANCHOR)
        private val GIT_REVISION = Regex("[a-f0-9]{40}")
        private val SHA256 = Regex("[a-f0-9]{64}")
    }
}

data class MetricDepthCalibrationBinding(
    val depthProfileId: String,
    val cameraIntrinsicsFingerprint: String,
) {
    init {
        require(depthProfileId.isNotBlank() && depthProfileId.length <= 128)
        require(SHA256.matches(cameraIntrinsicsFingerprint))
    }

    companion object {
        fun forProfile(
            depthProfile: MachineVisionModelProfile,
            cameraIntrinsics: CameraIntrinsics,
        ) = MetricDepthCalibrationBinding(depthProfile.id, cameraIntrinsics.calibrationFingerprint)

        private val SHA256 = Regex("[a-f0-9]{64}")
    }
}

fun interface MetricDepthCalibrationProvider {
    fun resolve(
        depthProfile: MachineVisionModelProfile,
        cameraIntrinsics: CameraIntrinsics,
    ): MetricDepthCalibration?

    companion object {
        fun none(): MetricDepthCalibrationProvider = MetricDepthCalibrationProvider { _, _ -> null }

        fun single(calibration: MetricDepthCalibration): MetricDepthCalibrationProvider =
            MetricDepthCalibrationProvider { profile, intrinsics ->
                val binding = calibration.binding
                if (binding == null ||
                    (binding.depthProfileId == profile.id &&
                        binding.cameraIntrinsicsFingerprint == intrinsics.calibrationFingerprint)
                ) {
                    calibration
                } else {
                    null
                }
            }
    }
}

/**
 * Pinned upstream output contract. The official metric head returns metres after multiplying its
 * sigmoid output by 20 m (Hypersim) or 80 m (VKITTI). This is an identity semantics descriptor,
 * not a claim that the monocular estimate has been measured or calibrated on the glasses camera.
 */
fun interface NativeMetricDepthSemanticsProvider {
    fun resolve(depthProfile: MachineVisionModelProfile): MetricDepthCalibration?

    companion object {
        fun none(): NativeMetricDepthSemanticsProvider = NativeMetricDepthSemanticsProvider { null }
    }
}

object OfficialDepthAnythingV2MetricSemanticsProvider : NativeMetricDepthSemanticsProvider {
    private data class Contract(
        val upstreamModelId: String,
        val modelRevision: String,
        val checkpointSha256: String,
        val maximumDepthMeters: Double,
    )

    private val contracts = mapOf(
        "depth-anything/Depth-Anything-V2-Metric-Hypersim-Small" to Contract(
            "depth-anything/Depth-Anything-V2-Metric-Hypersim-Small",
            "3bc65d4e14a6786a61acec16453c50e12bf5f338",
            "b782898d8a3e8be1f639de33837ed85e9b4b73e40f8f5e5cd99067588d722545",
            20.0,
        ),
        "depth-anything/Depth-Anything-V2-Metric-VKITTI-Small" to Contract(
            "depth-anything/Depth-Anything-V2-Metric-VKITTI-Small",
            "c725b8589bdf6ab04072cab74c0467830db80d6d",
            "9203e538d35255c90dda4b7fedb47ff33fe725497bcca3b1e53b3a65ee63f0cb",
            80.0,
        ),
    )

    override fun resolve(depthProfile: MachineVisionModelProfile): MetricDepthCalibration? {
        val canonical = MachineVisionModelProfiles.allProfiles.singleOrNull { it.id == depthProfile.id }
        if (canonical != depthProfile || depthProfile.kind != MachineVisionModelKind.METRIC_DEPTH) return null
        val contract = contracts[depthProfile.upstreamModelId] ?: return null
        return MetricDepthCalibration(
            representation = RelativeDepthRepresentation.DEPTH,
            scale = 1.0,
            offsetMeters = 0.0,
            nearFeature = 0.0,
            farFeature = contract.maximumDepthMeters,
            residualMeters = null,
            contributingSamples = 0,
            binding = null,
            provenance = MetricDepthProvenance(
                MetricDepthProvenanceKind.PINNED_OFFICIAL_NATIVE_METRIC,
                depthProfile.id,
                contract.upstreamModelId,
                contract.modelRevision,
                contract.checkpointSha256,
                contract.maximumDepthMeters,
            ),
        )
    }
}

/** Immutable, bounded lookup keyed by the complete non-sensitive calibration context. */
class BoundedMetricDepthCalibrationStore(
    calibrations: Collection<MetricDepthCalibration>,
    maximumEntries: Int = 16,
) : MetricDepthCalibrationProvider {
    private val byBinding: Map<MetricDepthCalibrationBinding, MetricDepthCalibration>

    init {
        require(maximumEntries in 1..64)
        require(calibrations.size <= maximumEntries)
        require(calibrations.all { it.binding != null })
        byBinding = calibrations.associateBy { requireNotNull(it.binding) }
        require(byBinding.size == calibrations.size)
    }

    override fun resolve(
        depthProfile: MachineVisionModelProfile,
        cameraIntrinsics: CameraIntrinsics,
    ): MetricDepthCalibration? = byBinding[
        MetricDepthCalibrationBinding.forProfile(depthProfile, cameraIntrinsics),
    ]
}

data class MetricDepthCalibration(
    val representation: RelativeDepthRepresentation,
    val scale: Double,
    val offsetMeters: Double,
    val nearFeature: Double,
    val farFeature: Double,
    val residualMeters: Double?,
    val contributingSamples: Int,
    val binding: MetricDepthCalibrationBinding? = null,
    val provenance: MetricDepthProvenance = MetricDepthProvenance.guidedTwoAnchor,
) {
    init {
        require(scale.isFinite() && scale > 0.0)
        require(offsetMeters.isFinite())
        require(nearFeature.isFinite() && farFeature.isFinite() && farFeature > nearFeature)
        when (provenance.kind) {
            MetricDepthProvenanceKind.GUIDED_TWO_ANCHOR -> {
                require(residualMeters?.let { it.isFinite() && it >= 0.0 } == true)
                require(contributingSamples >= 2)
            }
            MetricDepthProvenanceKind.PINNED_OFFICIAL_NATIVE_METRIC -> {
                require(representation == RelativeDepthRepresentation.DEPTH)
                require(scale == 1.0 && offsetMeters == 0.0 && nearFeature == 0.0)
                require(farFeature == provenance.declaredMaximumDepthMeters)
                require(residualMeters == null && contributingSamples == 0 && binding == null)
            }
        }
    }

    fun acceptsRawDepthValue(value: Double): Boolean =
        value.isFinite() && value > 0.0 &&
            (provenance.kind != MetricDepthProvenanceKind.PINNED_OFFICIAL_NATIVE_METRIC || value <= farFeature)

    fun estimate(relativeDepthValue: Double): MetricDepthEstimate? {
        if (!acceptsRawDepthValue(relativeDepthValue)) return null
        val feature = representation.feature(relativeDepthValue)
        val rawDistance = scale * feature + offsetMeters
        if (!rawDistance.isFinite() || rawDistance <= 0.0) return null
        val extrapolated = feature !in nearFeature..farFeature
        if (provenance.kind == MetricDepthProvenanceKind.PINNED_OFFICIAL_NATIVE_METRIC) {
            return MetricDepthEstimate(
                distanceMeters = rawDistance,
                uncertaintyMeters = null,
                extrapolated = false,
                provenance = provenance,
                uncertaintyBasis = MetricDepthUncertaintyBasis.UNQUANTIFIED_MODEL_ERROR,
            )
        }
        val boundedDistance = rawDistance.coerceIn(MIN_DISTANCE_METERS, MAX_DISTANCE_METERS)
        val calibrationUncertainty = max(MIN_UNCERTAINTY_METERS, requireNotNull(residualMeters))
        val extrapolationPenalty = if (extrapolated) boundedDistance * 0.20 else 0.0
        return MetricDepthEstimate(
            distanceMeters = boundedDistance,
            uncertaintyMeters = (calibrationUncertainty + extrapolationPenalty)
                .coerceAtMost(MAX_UNCERTAINTY_METERS),
            extrapolated = extrapolated,
            provenance = provenance,
            uncertaintyBasis = MetricDepthUncertaintyBasis.GUIDED_RESIDUAL,
        )
    }

    private companion object {
        const val MIN_DISTANCE_METERS = 0.15
        const val MAX_DISTANCE_METERS = 30.0
        const val MIN_UNCERTAINTY_METERS = 0.05
        const val MAX_UNCERTAINTY_METERS = 10.0
    }
}

/**
 * Fits a two-anchor affine calibration in O(n), followed by O(1) inference.
 * Only classes explicitly marked dimension-stable contribute. Calibration is
 * a bounded monocular estimate and never upgrades relative depth to sensor
 * truth.
 */
class TwoAnchorMetricDepthCalibrator(
    private val table: KnownDimensionVectorTable = KnownDimensionVectorTable(),
) {
    /**
     * Selects the monotonic representation from the ordered calibration
     * anchors. This avoids hard-coding vendor-specific relative-depth
     * direction while retaining the same bounded two-anchor fit.
     */
    fun calibrateAuto(
        samples: List<GuidedCalibrationSample>,
        binding: MetricDepthCalibrationBinding? = null,
    ): MetricDepthCalibration? =
        calibrate(samples, RelativeDepthRepresentation.DEPTH, binding)
            ?: calibrate(samples, RelativeDepthRepresentation.INVERSE_DEPTH, binding)

    fun calibrate(
        samples: List<GuidedCalibrationSample>,
        representation: RelativeDepthRepresentation,
        binding: MetricDepthCalibrationBinding? = null,
    ): MetricDepthCalibration? {
        val accepted = samples.mapNotNull { sample ->
            val definition = BviClassCatalog.find(sample.classId) ?: return@mapNotNull null
            val record = table.get(sample.classId, sample.referenceDistance) ?: return@mapNotNull null
            val weight = sample.confidence * definition.calibrationWeight *
                (1.0 - definition.dimensions.relativeUncertainty)
            if (weight <= 0.0) return@mapNotNull null
            WeightedAnchorValue(
                reference = sample.referenceDistance,
                feature = representation.feature(sample.relativeDepthValue),
                weight = weight,
                metricDistance = record.referenceDistance.meters,
            )
        }
        val near = weightedMedian(accepted.filter { it.reference == ReferenceDistance.NEAR_TWO_FEET })
            ?: return null
        val far = weightedMedian(accepted.filter { it.reference == ReferenceDistance.FAR_EIGHT_FEET })
            ?: return null
        if (far.feature - near.feature <= MIN_FEATURE_SPAN) return null
        val scale = (far.metricDistance - near.metricDistance) / (far.feature - near.feature)
        if (!scale.isFinite() || scale <= 0.0) return null
        val offset = near.metricDistance - scale * near.feature
        val residual = weightedMeanAbsoluteResidual(accepted, scale, offset)
        return MetricDepthCalibration(
            representation = representation,
            scale = scale,
            offsetMeters = offset,
            nearFeature = near.feature,
            farFeature = far.feature,
            residualMeters = residual,
            contributingSamples = accepted.size,
            binding = binding,
        )
    }

    private fun weightedMedian(values: List<WeightedAnchorValue>): WeightedAnchorValue? {
        if (values.isEmpty()) return null
        val sorted = values.sortedBy(WeightedAnchorValue::feature)
        val halfWeight = sorted.sumOf(WeightedAnchorValue::weight) / 2.0
        var cumulative = 0.0
        for (value in sorted) {
            cumulative += value.weight
            if (cumulative >= halfWeight) return value
        }
        return sorted.last()
    }

    private fun weightedMeanAbsoluteResidual(
        values: List<WeightedAnchorValue>,
        scale: Double,
        offset: Double,
    ): Double {
        val totalWeight = values.sumOf(WeightedAnchorValue::weight)
        if (totalWeight <= 0.0) return 0.0
        return values.sumOf { value ->
            abs(scale * value.feature + offset - value.metricDistance) * value.weight
        } / totalWeight
    }

    private data class WeightedAnchorValue(
        val reference: ReferenceDistance,
        val feature: Double,
        val weight: Double,
        val metricDistance: Double,
    )

    private companion object {
        const val MIN_FEATURE_SPAN = 1e-9
    }
}

data class MaskExtentObservation(
    val classId: String,
    val maskWidthPixels: Int,
    val maskHeightPixels: Int,
    val focalLengthXPixels: Double,
    val focalLengthYPixels: Double,
    val confidence: Double,
) {
    init {
        require(maskWidthPixels > 0 && maskHeightPixels > 0)
        require(focalLengthXPixels.isFinite() && focalLengthXPixels > 0.0)
        require(focalLengthYPixels.isFinite() && focalLengthYPixels > 0.0)
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

data class DimensionDistanceEstimate(
    val distanceMeters: Double,
    val uncertaintyMeters: Double,
    val classId: String,
    val calibrationWeight: Double = 1.0,
) {
    init {
        require(distanceMeters.isFinite() && distanceMeters > 0.0)
        require(uncertaintyMeters.isFinite() && uncertaintyMeters > 0.0)
        require(classId.isNotBlank())
        require(calibrationWeight.isFinite() && calibrationWeight in 0.0..1.0)
    }
}

data class FusedMetricDepthEstimate(
    val estimate: MetricDepthEstimate,
    val usedDimensionPrior: Boolean,
    val rejectedDimensionPriorAsOutlier: Boolean,
)

/**
 * Combines correlated same-image metric evidence by confidence-scaled
 * weighting. A disagreeing dimension prior is discarded, and the fused
 * uncertainty never claims improvement over the calibrated-depth uncertainty.
 */
object RobustMetricDepthFusion {
    fun fuse(
        calibratedDepth: MetricDepthEstimate,
        calibratedConfidence: Double,
        dimensionPrior: DimensionDistanceEstimate?,
        dimensionConfidence: Double,
        disagreementSigma: Double = 3.0,
        minimumDisagreementMeters: Double = 0.35,
    ): FusedMetricDepthEstimate {
        require(calibratedConfidence.isFinite() && calibratedConfidence in 0.0..1.0)
        require(dimensionConfidence.isFinite() && dimensionConfidence in 0.0..1.0)
        require(disagreementSigma.isFinite() && disagreementSigma >= 1.0)
        require(minimumDisagreementMeters.isFinite() && minimumDisagreementMeters > 0.0)
        if (dimensionPrior == null || dimensionConfidence == 0.0) {
            return FusedMetricDepthEstimate(calibratedDepth, false, false)
        }

        val quantifiedDepthUncertainty = calibratedDepth.uncertaintyMeters
        if (quantifiedDepthUncertainty == null) {
            return fuseUnquantifiedNativeMetric(
                calibratedDepth,
                dimensionPrior,
                dimensionConfidence,
            )
        }
        val depthSigma = max(MIN_SIGMA_METERS, quantifiedDepthUncertainty)
        val priorSigma = max(MIN_SIGMA_METERS, dimensionPrior.uncertaintyMeters)
        val difference = abs(calibratedDepth.distanceMeters - dimensionPrior.distanceMeters)
        val gate = max(
            minimumDisagreementMeters,
            disagreementSigma * sqrt(depthSigma * depthSigma + priorSigma * priorSigma),
        )
        if (difference > gate) {
            return FusedMetricDepthEstimate(calibratedDepth, false, true)
        }

        val depthWeight = calibratedConfidence.coerceAtLeast(MIN_CONFIDENCE) / (depthSigma * depthSigma)
        val priorWeight = dimensionConfidence.coerceAtLeast(MIN_CONFIDENCE) / (priorSigma * priorSigma)
        val totalWeight = depthWeight + priorWeight
        val distance = (
            calibratedDepth.distanceMeters * depthWeight + dimensionPrior.distanceMeters * priorWeight
            ) / totalWeight
        val normalizedDepthWeight = depthWeight / totalWeight
        val normalizedPriorWeight = priorWeight / totalWeight
        val residualVariance = normalizedDepthWeight *
            (calibratedDepth.distanceMeters - distance) * (calibratedDepth.distanceMeters - distance) +
            normalizedPriorWeight *
            (dimensionPrior.distanceMeters - distance) * (dimensionPrior.distanceMeters - distance)
        val uncertainty = max(
            quantifiedDepthUncertainty,
            sqrt(1.0 / totalWeight + residualVariance),
        )
        return FusedMetricDepthEstimate(
            estimate = MetricDepthEstimate(
                distanceMeters = distance,
                uncertaintyMeters = uncertainty,
                extrapolated = calibratedDepth.extrapolated,
                provenance = calibratedDepth.provenance,
                uncertaintyBasis = calibratedDepth.uncertaintyBasis,
            ),
            usedDimensionPrior = true,
            rejectedDimensionPriorAsOutlier = false,
        )
    }

    /**
     * Official metric checkpoints return metres but do not publish a calibrated error bound for
     * this camera. A size prior may therefore make only a conservative, bounded correction. It
     * never replaces the model value outright or upgrades monocular depth to sensor-grade truth.
     */
    private fun fuseUnquantifiedNativeMetric(
        native: MetricDepthEstimate,
        prior: DimensionDistanceEstimate,
        dimensionConfidence: Double,
    ): FusedMetricDepthEstimate {
        val difference = prior.distanceMeters - native.distanceMeters
        val absoluteDifference = abs(difference)
        val ratio = prior.distanceMeters / native.distanceMeters
        val disagreementGate = max(
            MIN_NATIVE_DISAGREEMENT_METERS,
            max(prior.uncertaintyMeters * 3.0, native.distanceMeters * MAX_NATIVE_RELATIVE_CORRECTION),
        )
        if (ratio !in MIN_NATIVE_PRIOR_RATIO..MAX_NATIVE_PRIOR_RATIO ||
            absoluteDifference > disagreementGate
        ) {
            return FusedMetricDepthEstimate(native, false, true)
        }
        val trust = (dimensionConfidence * prior.calibrationWeight).coerceIn(0.0, 1.0)
        if (trust <= 0.0) return FusedMetricDepthEstimate(native, false, false)
        val correctionFraction = (trust * MAX_DIMENSION_CORRECTION_FRACTION)
            .coerceAtMost(MAX_DIMENSION_CORRECTION_FRACTION)
        val distance = native.distanceMeters + difference * correctionFraction
        val uncertainty = maxOf(
            prior.uncertaintyMeters,
            absoluteDifference,
            distance * MIN_NATIVE_OUTPUT_RELATIVE_UNCERTAINTY,
        )
        return FusedMetricDepthEstimate(
            native.copy(
                distanceMeters = distance,
                uncertaintyMeters = uncertainty,
                uncertaintyBasis = MetricDepthUncertaintyBasis.DIMENSION_PRIOR_BOUND,
            ),
            usedDimensionPrior = true,
            rejectedDimensionPriorAsOutlier = false,
        )
    }

    private const val MIN_SIGMA_METERS = 0.01
    private const val MIN_CONFIDENCE = 1e-6
    private const val MIN_NATIVE_DISAGREEMENT_METERS = 0.50
    private const val MAX_NATIVE_RELATIVE_CORRECTION = 0.50
    private const val MIN_NATIVE_PRIOR_RATIO = 0.50
    private const val MAX_NATIVE_PRIOR_RATIO = 2.00
    private const val MAX_DIMENSION_CORRECTION_FRACTION = 0.35
    private const val MIN_NATIVE_OUTPUT_RELATIVE_UNCERTAINTY = 0.20
}

/** Produces optional calibration evidence; it is never a Tier 0 geometry source. */
object PinholeDimensionEstimator {
    private val knownDimensions = KnownDimensionVectorTable()

    fun estimate(observation: MaskExtentObservation): DimensionDistanceEstimate? {
        val definition = BviClassCatalog.find(observation.classId) ?: return null
        if (definition.calibrationAxis == CalibrationAxis.NONE || definition.calibrationWeight <= 0.0) return null
        val horizontalAngle = 2.0 * kotlin.math.atan2(
            observation.maskWidthPixels.toDouble(),
            2.0 * observation.focalLengthXPixels,
        )
        val verticalAngle = 2.0 * kotlin.math.atan2(
            observation.maskHeightPixels.toDouble(),
            2.0 * observation.focalLengthYPixels,
        )
        if (!withinTwoToEightFootAnchorEnvelope(definition, horizontalAngle, verticalAngle)) return null
        val candidates = buildList {
            if (definition.calibrationAxis == CalibrationAxis.WIDTH || definition.calibrationAxis == CalibrationAxis.BOTH) {
                add(definition.dimensions.width / (2.0 * tan(horizontalAngle / 2.0)))
            }
            if (definition.calibrationAxis == CalibrationAxis.HEIGHT || definition.calibrationAxis == CalibrationAxis.BOTH) {
                add(definition.dimensions.height / (2.0 * tan(verticalAngle / 2.0)))
            }
        }.filter { it.isFinite() && it > 0.0 }
        if (candidates.isEmpty()) return null
        val distance = candidates.average()
        val confidencePenalty = 1.0 + (1.0 - observation.confidence)
        return DimensionDistanceEstimate(
            distanceMeters = distance,
            uncertaintyMeters = distance * definition.dimensions.relativeUncertainty * confidencePenalty,
            classId = definition.id,
            calibrationWeight = definition.calibrationWeight,
        )
    }

    private fun withinTwoToEightFootAnchorEnvelope(
        definition: BviClassDefinition,
        horizontalAngle: Double,
        verticalAngle: Double,
    ): Boolean {
        val near = requireNotNull(knownDimensions.get(definition.id, ReferenceDistance.NEAR_TWO_FEET))
        val far = requireNotNull(knownDimensions.get(definition.id, ReferenceDistance.FAR_EIGHT_FEET))
        fun accepted(observed: Double, farExtent: Double, nearExtent: Double): Boolean {
            val relativePadding = definition.dimensions.relativeUncertainty.coerceAtMost(0.50)
            return observed in (farExtent * (1.0 - relativePadding))..(nearExtent * (1.0 + relativePadding))
        }
        val horizontalAccepted = accepted(
            horizontalAngle,
            far.expectedHorizontalAngularExtentRadians,
            near.expectedHorizontalAngularExtentRadians,
        )
        val verticalAccepted = accepted(
            verticalAngle,
            far.expectedVerticalAngularExtentRadians,
            near.expectedVerticalAngularExtentRadians,
        )
        return when (definition.calibrationAxis) {
            CalibrationAxis.WIDTH -> horizontalAccepted
            CalibrationAxis.HEIGHT -> verticalAccepted
            CalibrationAxis.BOTH -> horizontalAccepted && verticalAccepted
            CalibrationAxis.NONE -> false
        }
    }
}

private fun RelativeDepthRepresentation.feature(value: Double): Double = when (this) {
    RelativeDepthRepresentation.DEPTH -> value
    RelativeDepthRepresentation.INVERSE_DEPTH -> 1.0 / value
}
