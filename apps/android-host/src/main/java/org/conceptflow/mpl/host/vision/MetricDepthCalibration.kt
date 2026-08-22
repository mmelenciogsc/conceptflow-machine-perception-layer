// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import kotlin.math.abs
import kotlin.math.max
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
    val uncertaintyMeters: Double,
    val extrapolated: Boolean,
)

data class MetricDepthCalibration(
    val representation: RelativeDepthRepresentation,
    val scale: Double,
    val offsetMeters: Double,
    val nearFeature: Double,
    val farFeature: Double,
    val residualMeters: Double,
    val contributingSamples: Int,
) {
    init {
        require(scale.isFinite() && scale > 0.0)
        require(offsetMeters.isFinite())
        require(nearFeature.isFinite() && farFeature.isFinite() && farFeature > nearFeature)
        require(residualMeters.isFinite() && residualMeters >= 0.0)
        require(contributingSamples >= 2)
    }

    fun estimate(relativeDepthValue: Double): MetricDepthEstimate? {
        if (!relativeDepthValue.isFinite() || relativeDepthValue <= 0.0) return null
        val feature = representation.feature(relativeDepthValue)
        val rawDistance = scale * feature + offsetMeters
        if (!rawDistance.isFinite() || rawDistance <= 0.0) return null
        val extrapolated = feature !in nearFeature..farFeature
        val boundedDistance = rawDistance.coerceIn(MIN_DISTANCE_METERS, MAX_DISTANCE_METERS)
        val calibrationUncertainty = max(MIN_UNCERTAINTY_METERS, residualMeters)
        val extrapolationPenalty = if (extrapolated) boundedDistance * 0.20 else 0.0
        return MetricDepthEstimate(
            distanceMeters = boundedDistance,
            uncertaintyMeters = (calibrationUncertainty + extrapolationPenalty)
                .coerceAtMost(MAX_UNCERTAINTY_METERS),
            extrapolated = extrapolated,
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
    fun calibrate(
        samples: List<GuidedCalibrationSample>,
        representation: RelativeDepthRepresentation,
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
)

/** Produces optional calibration evidence; it is never a Tier 0 geometry source. */
object PinholeDimensionEstimator {
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
        )
    }
}

private fun RelativeDepthRepresentation.feature(value: Double): Double = when (this) {
    RelativeDepthRepresentation.DEPTH -> value
    RelativeDepthRepresentation.INVERSE_DEPTH -> 1.0 / value
}
