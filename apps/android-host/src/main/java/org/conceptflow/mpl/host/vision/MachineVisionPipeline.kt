// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

data class VisionFrame(
    val frameId: Long,
    val captureMonotonicTimestampNanos: Long,
    val width: Int,
    val height: Int,
    val synthetic: Boolean,
) {
    init {
        require(frameId > 0L && captureMonotonicTimestampNanos >= 0L)
        require(width in 1..7_680 && height in 1..4_320)
    }
}

data class SemanticMaskObservation(
    val trackId: String,
    val classId: String,
    val confidence: Double,
    val relativeDepthSamples: List<Double>,
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
)

data class MachineVisionPipelineResult(
    val tracks: List<MetricSemanticTrack>,
    val rejectedUnknownClasses: Int,
    val rejectedLowConfidence: Int,
    val rejectedStale: Boolean,
    val reason: String,
)

/**
 * Model-neutral semantic/depth fusion. Immediate body-clearance geometry stays
 * outside this slower semantic path.
 */
class MachineVisionPipeline(
    private val adapter: MachineVisionInferenceAdapter,
    private val calibration: MetricDepthCalibration,
    private val maximumResultAgeNanos: Long = 350_000_000L,
    private val minimumSemanticConfidence: Double = 0.55,
) {
    init {
        require(maximumResultAgeNanos in 1_000_000L..5_000_000_000L)
        require(minimumSemanticConfidence.isFinite() && minimumSemanticConfidence in 0.0..1.0)
    }

    fun process(
        frame: VisionFrame,
        environment: DepthEnvironment,
        nowNanos: Long,
    ): MachineVisionPipelineResult {
        require(nowNanos >= frame.captureMonotonicTimestampNanos)
        val profile = MachineVisionModelProfiles.depth(environment)
        val inference = runCatching { adapter.infer(frame, profile) }.getOrElse {
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
        if (inference.depthProfileId != profile.id) {
            return MachineVisionPipelineResult(emptyList(), 0, 0, false, "depth_profile_mismatch")
        }
        var unknown = 0
        var lowConfidence = 0
        val tracks = inference.observations.mapNotNull { observation ->
            if (BviClassCatalog.find(observation.classId) == null) {
                unknown += 1
                return@mapNotNull null
            }
            if (observation.confidence < minimumSemanticConfidence) {
                lowConfidence += 1
                return@mapNotNull null
            }
            val relativeMedian = median(observation.relativeDepthSamples)
            val estimate = calibration.estimate(relativeMedian) ?: return@mapNotNull null
            MetricSemanticTrack(
                frameId = frame.frameId,
                trackId = observation.trackId,
                classId = observation.classId,
                confidence = observation.confidence,
                representativeDistance = estimate,
                depthEnvironment = environment,
            )
        }
        return MachineVisionPipelineResult(tracks, unknown, lowConfidence, false, "processed")
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }
}
