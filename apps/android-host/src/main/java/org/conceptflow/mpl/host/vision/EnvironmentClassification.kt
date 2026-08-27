// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import kotlin.math.abs
import kotlin.math.exp

enum class SceneEnvironmentState {
    INDOOR,
    OUTDOOR,
    TRANSITION,
    UNKNOWN,
}

enum class EnvironmentSelectionMode {
    AUTOMATIC,
    FORCE_INDOOR,
    FORCE_OUTDOOR,
}

enum class EnvironmentSignalFamily {
    CAMERA,
    VLM_CAMERA,
    GNSS,
}

/** Privacy-safe evidence. It contains no image, coordinate, SSID, or BSSID. */
data class EnvironmentSignal(
    val sourceId: String,
    val family: EnvironmentSignalFamily,
    val timestampNanos: Long,
    val indoorProbability: Double,
    val outdoorProbability: Double,
    val reliability: Double,
    val originatingFrameId: Long? = null,
) {
    init {
        require(SOURCE_ID.matches(sourceId))
        require(timestampNanos >= 0L)
        require(indoorProbability.isFinite() && indoorProbability in 0.0..1.0)
        require(outdoorProbability.isFinite() && outdoorProbability in 0.0..1.0)
        require(abs(indoorProbability + outdoorProbability - 1.0) <= 1e-6)
        require(reliability.isFinite() && reliability in 0.0..1.0)
        require(originatingFrameId == null || originatingFrameId > 0L)
        require((family != EnvironmentSignalFamily.GNSS) == (originatingFrameId != null))
    }

    private companion object {
        val SOURCE_ID = Regex("[a-z0-9][a-z0-9._-]{1,63}")
    }
}

data class SceneSemanticDetection(
    val classId: String,
    val confidence: Double,
) {
    init {
        require(classId.isNotBlank() && classId.length <= 64)
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

fun interface CameraSceneClassifier {
    fun classify(
        frameId: Long,
        captureMonotonicTimestampNanos: Long,
        detections: List<SceneSemanticDetection>,
    ): EnvironmentSignal?
}

/**
 * Converts the fixed YOLOE vocabulary into conservative scene evidence before
 * metric depth runs. Classes that occur naturally in both settings contribute
 * little or no evidence. Repeated instances of one class cannot dominate.
 */
class BviSemanticSceneClassifier : CameraSceneClassifier {
    init {
        require((INDOOR_WEIGHTS.keys + OUTDOOR_WEIGHTS.keys).all { BviClassCatalog.find(it) != null })
    }

    override fun classify(
        frameId: Long,
        captureMonotonicTimestampNanos: Long,
        detections: List<SceneSemanticDetection>,
    ): EnvironmentSignal? {
        require(frameId > 0L && captureMonotonicTimestampNanos >= 0L)
        val strongestByClass = detections
            .filter { BviClassCatalog.find(it.classId) != null }
            .groupBy(SceneSemanticDetection::classId)
            .mapValues { (_, values) -> values.maxOf(SceneSemanticDetection::confidence) }
        val indoorScore = score(strongestByClass, BviEnvironmentClass.INDOOR, INDOOR_WEIGHTS)
        val outdoorScore = score(strongestByClass, BviEnvironmentClass.OUTDOOR, OUTDOOR_WEIGHTS)
        val total = indoorScore + outdoorScore
        if (total <= 0.0) return null

        val certainty = 1.0 - exp(-total.coerceAtMost(MAX_EVIDENCE_SCORE))
        val direction = (indoorScore - outdoorScore) / total
        val indoorProbability = (0.5 + direction * certainty * 0.5).coerceIn(0.0, 1.0)
        return EnvironmentSignal(
            sourceId = SOURCE_ID,
            family = EnvironmentSignalFamily.CAMERA,
            timestampNanos = captureMonotonicTimestampNanos,
            indoorProbability = indoorProbability,
            outdoorProbability = 1.0 - indoorProbability,
            reliability = (total / RELIABLE_SCORE).coerceIn(0.05, 1.0),
            originatingFrameId = frameId,
        )
    }

    private fun score(
        confidences: Map<String, Double>,
        environmentClass: BviEnvironmentClass,
        overrides: Map<String, Double>,
    ): Double = confidences.entries.sumOf { (classId, confidence) ->
        val definition = requireNotNull(BviClassCatalog.find(classId))
        val defaultWeight = if (definition.environmentClass == environmentClass) {
            GENERIC_CONTEXT_WEIGHT
        } else {
            0.0
        }
        confidence * (overrides[classId] ?: defaultWeight)
    }.coerceAtMost(MAX_EVIDENCE_SCORE)

    companion object {
        private const val SOURCE_ID = "bvi-semantic-scene"
        private const val MAX_EVIDENCE_SCORE = 3.0
        private const val RELIABLE_SCORE = 1.25
        private const val GENERIC_CONTEXT_WEIGHT = 0.12

        private val INDOOR_WEIGHTS = mapOf(
            "room_number" to 1.00,
            "elevator" to 0.90,
            "escalator" to 0.65,
            "door_handle" to 0.45,
            "information_board" to 0.35,
            "doorway" to 0.20,
            "door" to 0.15,
        )
        private val OUTDOOR_WEIGHTS = mapOf(
            "crosswalk" to 1.00,
            "traffic_light" to 0.95,
            "stop_sign" to 0.95,
            "curb_cut" to 0.80,
            "curb" to 0.75,
            "tree" to 0.65,
            "bus" to 0.55,
            "bollard" to 0.50,
            "car" to 0.35,
        )
    }
}

data class GnssQualitySample(
    val timestampNanos: Long,
    val visibleSatelliteCount: Int,
    val usedInFixCount: Int,
    val meanCarrierToNoiseDbHz: Double?,
    val horizontalAccuracyMeters: Double?,
    val locationFixAgeNanos: Long?,
) {
    init {
        require(timestampNanos >= 0L)
        require(visibleSatelliteCount in 0..256)
        require(usedInFixCount in 0..visibleSatelliteCount)
        require(meanCarrierToNoiseDbHz == null ||
            meanCarrierToNoiseDbHz.isFinite() && meanCarrierToNoiseDbHz in 0.0..100.0)
        require(horizontalAccuracyMeters == null ||
            horizontalAccuracyMeters.isFinite() && horizontalAccuracyMeters >= 0.0)
        require(locationFixAgeNanos == null || locationFixAgeNanos >= 0L)
    }
}

/** GNSS can reinforce outdoor evidence, but loss of reception never proves indoors. */
object GnssOutdoorEvidenceInterpreter {
    fun interpret(sample: GnssQualitySample): EnvironmentSignal? {
        val carrierToNoise = sample.meanCarrierToNoiseDbHz ?: return null
        if (sample.usedInFixCount < 4 || carrierToNoise < 16.0) return null
        val satelliteStrength = (sample.usedInFixCount / 8.0).coerceIn(0.0, 1.0)
        val signalStrength = ((carrierToNoise - 16.0) / 19.0).coerceIn(0.0, 1.0)
        val accuracyStrength = sample.horizontalAccuracyMeters
            ?.let { ((50.0 - it) / 40.0).coerceIn(0.0, 1.0) }
            ?: 0.25
        val freshnessStrength = sample.locationFixAgeNanos
            ?.let { (1.0 - it.toDouble() / MAX_FIX_AGE_NANOS).coerceIn(0.0, 1.0) }
            ?: 0.25
        val strength = (
            satelliteStrength * 0.35 +
                signalStrength * 0.35 +
                accuracyStrength * 0.15 +
                freshnessStrength * 0.15
            ).coerceIn(0.0, 1.0)
        val outdoorProbability = 0.5 + 0.48 * strength
        return EnvironmentSignal(
            sourceId = "android-gnss-quality",
            family = EnvironmentSignalFamily.GNSS,
            timestampNanos = sample.timestampNanos,
            indoorProbability = 1.0 - outdoorProbability,
            outdoorProbability = outdoorProbability,
            reliability = (0.10 + 0.25 * strength).coerceAtMost(MAX_GNSS_RELIABILITY),
        )
    }

    private const val MAX_FIX_AGE_NANOS = 30_000_000_000.0
    private const val MAX_GNSS_RELIABILITY = 0.35
}

data class EnvironmentEvidenceFusionConfig(
    val cameraMaximumAgeNanos: Long = 2_000_000_000L,
    val vlmCameraMaximumAgeNanos: Long = 20_000_000_000L,
    val gnssMaximumAgeNanos: Long = 15_000_000_000L,
    val maximumFutureSkewNanos: Long = 100_000_000L,
) {
    init {
        require(cameraMaximumAgeNanos > 0L && vlmCameraMaximumAgeNanos >= cameraMaximumAgeNanos)
        require(gnssMaximumAgeNanos > 0L)
        require(maximumFutureSkewNanos >= 0L)
    }
}

class EnvironmentEvidenceBuffer(private val maximumSources: Int = 8) {
    private val latestBySource = mutableMapOf<String, EnvironmentSignal>()

    init {
        require(maximumSources in 2..32)
    }

    @Synchronized
    fun update(signal: EnvironmentSignal): Boolean {
        val previous = latestBySource[signal.sourceId]
        if (previous != null &&
            (signal.family != previous.family || signal.timestampNanos <= previous.timestampNanos)
        ) {
            return false
        }
        latestBySource[signal.sourceId] = signal
        if (latestBySource.size > maximumSources) {
            val oldest = latestBySource.minBy { it.value.timestampNanos }.key
            latestBySource.remove(oldest)
        }
        return true
    }

    @Synchronized
    fun snapshot(): List<EnvironmentSignal> = latestBySource.values.sortedBy(EnvironmentSignal::sourceId)

    @Synchronized
    fun clear() = latestBySource.clear()
}

class EnvironmentEvidenceFusion(
    private val config: EnvironmentEvidenceFusionConfig = EnvironmentEvidenceFusionConfig(),
) {
    fun fuse(frameTimestampNanos: Long, signals: List<EnvironmentSignal>): EnvironmentEvidence? {
        require(frameTimestampNanos >= 0L)
        val accepted = signals.mapNotNull { signal ->
            val age = frameTimestampNanos - signal.timestampNanos
            if (age < -config.maximumFutureSkewNanos) return@mapNotNull null
            val maximumAge = when (signal.family) {
                EnvironmentSignalFamily.CAMERA -> config.cameraMaximumAgeNanos
                EnvironmentSignalFamily.VLM_CAMERA -> config.vlmCameraMaximumAgeNanos
                EnvironmentSignalFamily.GNSS -> config.gnssMaximumAgeNanos
            }
            val boundedAge = age.coerceAtLeast(0L)
            if (boundedAge > maximumAge) return@mapNotNull null
            val freshness = 1.0 - boundedAge.toDouble() / maximumAge
            WeightedSignal(signal, signal.reliability * freshness)
        }.filter { it.weight > 0.0 }
        if (accepted.isEmpty()) return null

        val families = accepted.groupBy { it.signal.family.independenceGroup() }.mapValues { (family, members) ->
            val rawWeight = members.sumOf(WeightedSignal::weight)
            val familyCap = if (family == EvidenceIndependenceGroup.GNSS) 0.35 else 1.0
            FamilyEvidence(
                indoorProbability = members.sumOf { it.signal.indoorProbability * it.weight } / rawWeight,
                weight = rawWeight.coerceAtMost(familyCap),
                newestTimestampNanos = members.maxOf { it.signal.timestampNanos },
            )
        }
        val totalWeight = families.values.sumOf(FamilyEvidence::weight)
        if (totalWeight <= 0.0) return null
        val indoorProbability = families.values.sumOf { it.indoorProbability * it.weight } / totalWeight
        return EnvironmentEvidence(
            timestampNanos = minOf(
                frameTimestampNanos,
                families.values.maxOf(FamilyEvidence::newestTimestampNanos),
            ),
            indoorProbability = indoorProbability,
            outdoorProbability = 1.0 - indoorProbability,
            independentSignalCount = families.size,
            hasPrimaryVisualSignal = EvidenceIndependenceGroup.VISUAL in families,
        )
    }

    private data class WeightedSignal(val signal: EnvironmentSignal, val weight: Double)
    private data class FamilyEvidence(
        val indoorProbability: Double,
        val weight: Double,
        val newestTimestampNanos: Long,
    )

    private enum class EvidenceIndependenceGroup { VISUAL, GNSS }

    private fun EnvironmentSignalFamily.independenceGroup(): EvidenceIndependenceGroup = when (this) {
        EnvironmentSignalFamily.CAMERA, EnvironmentSignalFamily.VLM_CAMERA -> {
            EvidenceIndependenceGroup.VISUAL
        }
        EnvironmentSignalFamily.GNSS -> EvidenceIndependenceGroup.GNSS
    }
}
