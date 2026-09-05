// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import kotlin.math.max
import kotlin.math.min
import org.conceptflow.mpl.transport.I420FidelityReport

data class NamedTensorFidelity(
    val name: String,
    val report: FloatTensorFidelityReport,
)

data class CodecFidelityDecision(
    val passed: Boolean,
    val failures: List<String>,
)

data class SemanticInstanceFidelityReport(
    val referenceCount: Int,
    val candidateCount: Int,
    val matchedCount: Int,
    val precision: Double,
    val recall: Double,
    val meanMatchedIntersectionOverUnion: Double,
) {
    init {
        require(referenceCount >= 0 && candidateCount >= 0)
        require(matchedCount in 0..min(referenceCount, candidateCount))
        require(precision.isFinite() && precision in 0.0..1.0)
        require(recall.isFinite() && recall in 0.0..1.0)
        require(
            meanMatchedIntersectionOverUnion.isFinite() &&
                meanMatchedIntersectionOverUnion in 0.0..1.0,
        )
    }
}

/** Greedy class-aware matching for postprocessed instance stability across a codec boundary. */
object SemanticInstanceFidelity {
    fun compare(
        reference: List<YoloMaskDetection>,
        candidate: List<YoloMaskDetection>,
    ): SemanticInstanceFidelityReport {
        val unmatchedCandidateIndices = candidate.indices.toMutableSet()
        var matched = 0
        var intersectionOverUnionSum = 0.0
        reference.sortedByDescending(YoloMaskDetection::confidence).forEach { expected ->
            val best = unmatchedCandidateIndices.asSequence()
                .filter { candidate[it].classId == expected.classId }
                .map { it to intersectionOverUnion(expected.geometry, candidate[it].geometry) }
                .maxByOrNull { it.second }
            if (best != null && best.second >= MINIMUM_MATCH_INTERSECTION_OVER_UNION) {
                unmatchedCandidateIndices.remove(best.first)
                matched += 1
                intersectionOverUnionSum += best.second
            }
        }
        val precision = when {
            candidate.isNotEmpty() -> matched.toDouble() / candidate.size
            reference.isEmpty() -> 1.0
            else -> 0.0
        }
        val recall = if (reference.isEmpty()) 1.0 else matched.toDouble() / reference.size
        return SemanticInstanceFidelityReport(
            referenceCount = reference.size,
            candidateCount = candidate.size,
            matchedCount = matched,
            precision = precision,
            recall = recall,
            meanMatchedIntersectionOverUnion = if (matched == 0) 0.0 else intersectionOverUnionSum / matched,
        )
    }

    private fun intersectionOverUnion(
        first: InstanceMaskGeometry,
        second: InstanceMaskGeometry,
    ): Double {
        if (first.imageWidthPixels != second.imageWidthPixels ||
            first.imageHeightPixels != second.imageHeightPixels
        ) return 0.0
        val overlapWidth = max(0, min(first.rightExclusivePixels, second.rightExclusivePixels) -
            max(first.leftPixels, second.leftPixels))
        val overlapHeight = max(0, min(first.bottomExclusivePixels, second.bottomExclusivePixels) -
            max(first.topPixels, second.topPixels))
        val intersection = overlapWidth.toLong() * overlapHeight
        val firstArea = first.widthPixels.toLong() * first.heightPixels
        val secondArea = second.widthPixels.toLong() * second.heightPixels
        val union = firstArea + secondArea - intersection
        return if (union <= 0L) 0.0 else intersection.toDouble() / union
    }

    private const val MINIMUM_MATCH_INTERSECTION_OVER_UNION = 0.5
}

/** Explicit acceptance gate for the synthetic raw-I420 versus hardware-AVC comparison. */
object CodecFidelityGate {
    const val MINIMUM_LUMA_PSNR_DB = 30.0
    const val MINIMUM_CHROMA_PSNR_DB = 28.0
    const val MINIMUM_MODEL_COSINE_SIMILARITY = 0.98
    const val MAXIMUM_MODEL_NORMALIZED_RMSE = 0.15
    const val MINIMUM_REPEATABILITY_COSINE_SIMILARITY = 0.999
    const val MAXIMUM_REPEATABILITY_NORMALIZED_RMSE = 0.01
    const val MINIMUM_SEMANTIC_PRECISION = 0.95
    const val MINIMUM_SEMANTIC_RECALL = 0.95
    const val MINIMUM_SEMANTIC_MEAN_INTERSECTION_OVER_UNION = 0.90

    fun evaluate(
        pixels: I420FidelityReport,
        modelOutputs: List<NamedTensorFidelity>,
        repeatedReferenceOutputs: List<NamedTensorFidelity> = emptyList(),
        semanticInstances: SemanticInstanceFidelityReport? = null,
    ): CodecFidelityDecision {
        require(modelOutputs.isNotEmpty())
        val failures = ArrayList<String>()
        if (pixels.luma.peakSignalToNoiseRatioDb < MINIMUM_LUMA_PSNR_DB) failures += "luma_psnr"
        if (pixels.chroma.peakSignalToNoiseRatioDb < MINIMUM_CHROMA_PSNR_DB) failures += "chroma_psnr"
        repeatedReferenceOutputs.forEach { output ->
            require(output.name.matches(Regex("[a-z0-9_-]{1,64}")))
            if (output.report.nonFiniteMismatchCount != 0 ||
                output.report.cosineSimilarity < MINIMUM_REPEATABILITY_COSINE_SIMILARITY ||
                output.report.normalizedRootMeanSquareError > MAXIMUM_REPEATABILITY_NORMALIZED_RMSE
            ) {
                failures += "${output.name}_repeatability"
            }
        }
        modelOutputs.forEach { output ->
            require(output.name.matches(Regex("[a-z0-9_-]{1,64}")))
            if (output.report.nonFiniteMismatchCount != 0) failures += "${output.name}_nonfinite"
            if (output.report.cosineSimilarity < MINIMUM_MODEL_COSINE_SIMILARITY) {
                failures += "${output.name}_cosine"
            }
            if (output.report.normalizedRootMeanSquareError > MAXIMUM_MODEL_NORMALIZED_RMSE) {
                failures += "${output.name}_nrmse"
            }
        }
        semanticInstances?.let { semantic ->
            if (semantic.referenceCount == 0) failures += "semantic_fixture_empty"
            if (semantic.precision < MINIMUM_SEMANTIC_PRECISION) failures += "semantic_precision"
            if (semantic.recall < MINIMUM_SEMANTIC_RECALL) failures += "semantic_recall"
            if (semantic.meanMatchedIntersectionOverUnion < MINIMUM_SEMANTIC_MEAN_INTERSECTION_OVER_UNION) {
                failures += "semantic_mean_iou"
            }
        }
        return CodecFidelityDecision(failures.isEmpty(), failures)
    }
}
