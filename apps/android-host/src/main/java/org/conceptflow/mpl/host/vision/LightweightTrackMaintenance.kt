// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import java.util.LinkedHashMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Explicit validity of each independently consumable spatial representation. */
enum class TrackEstimateValidity {
    UNAVAILABLE,
    OBSERVED,
    MOTION_PREDICTED,
    ORIENTATION_PROPAGATED,
    TRANSLATION_EVIDENCE_PROPAGATED,
}

data class TrackCoordinateValidity(
    val image2d: TrackEstimateValidity,
    val cameraRelative: TrackEstimateValidity,
    val headRelative: TrackEstimateValidity,
    val localWorld: TrackEstimateValidity,
)

/** Small covariance summary; null metric terms mean the uncertainty is unquantified. */
data class LightweightTrackCovariance(
    val imageXxPixelsSquared: Double,
    val imageXyPixelsSquared: Double,
    val imageYyPixelsSquared: Double,
    val depthVarianceMetersSquared: Double?,
    val headOrientationVarianceRadiansSquared: Double?,
    val localWorldVarianceMetersSquared: Double?,
) {
    init {
        require(imageXxPixelsSquared.isFinite() && imageXxPixelsSquared >= 0.0)
        require(imageXyPixelsSquared.isFinite())
        require(imageYyPixelsSquared.isFinite() && imageYyPixelsSquared >= 0.0)
        require(abs(imageXyPixelsSquared) <= sqrt(imageXxPixelsSquared * imageYyPixelsSquared) + 1e-9)
        require(depthVarianceMetersSquared == null ||
            depthVarianceMetersSquared.isFinite() && depthVarianceMetersSquared >= 0.0)
        require(headOrientationVarianceRadiansSquared == null ||
            headOrientationVarianceRadiansSquared.isFinite() && headOrientationVarianceRadiansSquared >= 0.0)
        require(localWorldVarianceMetersSquared == null ||
            localWorldVarianceMetersSquared.isFinite() && localWorldVarianceMetersSquared >= 0.0)
    }
}

data class LightweightTrackState(
    val stableTrackId: String,
    val sourceTrackId: String,
    val classId: String,
    val sourceFrameId: Long,
    val sourceCaptureTimestampNanos: Long,
    val sourceInferenceTimestampNanos: Long,
    val outputTimestampNanos: Long,
    val expiresAtTimestampNanos: Long,
    val confidence: Double,
    val coordinateValidity: TrackCoordinateValidity,
    val imageGeometry: InstanceMaskGeometry?,
    val cameraRelativeVectorMeters: MetricVector3?,
    val headRelativeVectorMeters: MetricVector3?,
    val localWorldPositionMeters: MetricVector3?,
    val metricDepth: MetricDepthEstimate?,
    val depthFresh: Boolean,
    val centroidVelocityXPixelsPerSecond: Double,
    val centroidVelocityYPixelsPerSecond: Double,
    /** Positive means closing distance; null means metric motion is unavailable. */
    val approachVelocityMetersPerSecond: Double?,
    val missedKeyframes: Int,
    val headCameraTranslationApplied: Boolean,
    val covariance: LightweightTrackCovariance,
    /** False means private temporal evidence; downstream user-facing surfaces must ignore it. */
    val confirmedForPublication: Boolean = true,
) {
    init {
        require(stableTrackId.isNotBlank() && sourceTrackId.isNotBlank() && classId.isNotBlank())
        require(sourceFrameId > 0L && sourceCaptureTimestampNanos >= 0L)
        require(sourceInferenceTimestampNanos >= sourceCaptureTimestampNanos)
        require(outputTimestampNanos >= sourceCaptureTimestampNanos)
        require(expiresAtTimestampNanos > sourceCaptureTimestampNanos)
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(centroidVelocityXPixelsPerSecond.isFinite())
        require(centroidVelocityYPixelsPerSecond.isFinite())
        require(approachVelocityMetersPerSecond == null || approachVelocityMetersPerSecond.isFinite())
        require(missedKeyframes >= 0)
        require((imageGeometry != null) == (coordinateValidity.image2d != TrackEstimateValidity.UNAVAILABLE))
        require((cameraRelativeVectorMeters != null) ==
            (coordinateValidity.cameraRelative != TrackEstimateValidity.UNAVAILABLE))
        require((headRelativeVectorMeters != null) ==
            (coordinateValidity.headRelative != TrackEstimateValidity.UNAVAILABLE))
        require((localWorldPositionMeters != null) ==
            (coordinateValidity.localWorld != TrackEstimateValidity.UNAVAILABLE))
        require(!headCameraTranslationApplied || headRelativeVectorMeters != null)
    }
}

data class LightweightTrackUpdate(
    val accepted: Boolean,
    val reason: String,
    val tracks: List<LightweightTrackState>,
    /** Existing keyframe tracker ID to stable maintenance ID. */
    val sourceToStableTrackIds: Map<String, String>,
    val evictedTrackIds: List<String>,
)

fun interface TrackClassCompatibility {
    fun compatible(previousClassId: String, observedClassId: String): Boolean

    companion object {
        val EXACT = TrackClassCompatibility { previous, observed -> previous == observed }
    }
}

/** Optional hook only. Without a supplied appearance model this maintainer is not classic DeepSORT. */
fun interface TrackAppearanceSimilarity {
    fun similarity(previous: FloatArray, observed: FloatArray): Double
}

/**
 * Bounded online maintenance after [BoundedYoloTracker]. It re-associates short source-ID breaks
 * from class, predicted mask-box geometry, depth consistency, and an optional appearance hook.
 * Static orientation propagation is delegated to [TemporalMetricTrackStore].
 */
class LightweightTrackMaintainer(
    private val capacity: Int = 64,
    private val trackTtlNanos: Long = 1_500_000_000L,
    private val minimumPostInferenceHoldNanos: Long = 1_250_000_000L,
    private val maximumDepthAgeNanos: Long = 500_000_000L,
    private val maximumMissedKeyframes: Int = 6,
    private val minimumAssociationScore: Double = 0.38,
    private val confidenceDecayPerSecond: Double = 0.45,
    private val immediateConfirmationConfidence: Double =
        YoloSemanticConfidencePolicy.IMMEDIATE_PUBLICATION_CONFIDENCE,
    private val consistentObservationsRequired: Int =
        YoloSemanticConfidencePolicy.CONSISTENT_OBSERVATIONS_REQUIRED,
    private val confirmationWindowNanos: Long = 2_000_000_000L,
    private val maximumTentativeMissedKeyframes: Int = 1,
    private val classCompatibility: TrackClassCompatibility = TrackClassCompatibility.EXACT,
    private val appearanceSimilarity: TrackAppearanceSimilarity? = null,
    headFromCamera: VerifiedHeadCameraExtrinsic? = null,
    private val temporalStore: TemporalMetricTrackStore = TemporalMetricTrackStore(
        capacity = capacity,
        trackTtlNanos = trackTtlNanos,
    ),
) {
    private data class Record(
        val stableTrackId: String,
        var sourceTrackId: String,
        var classId: String,
        var sourceFrameId: Long,
        var sourceCaptureNanos: Long,
        var sourceInferenceNanos: Long,
        var confidence: Double,
        var geometry: InstanceMaskGeometry?,
        var depth: MetricDepthEstimate?,
        var cameraVectorMeters: MetricVector3?,
        var velocityX: Double,
        var velocityY: Double,
        var approachVelocity: Double?,
        var imageVariance: Double,
        var depthVariance: Double?,
        var appearance: FloatArray?,
        var missedKeyframes: Int,
        var consistentObservations: Int,
        var confirmedForPublication: Boolean,
        val classVotes: MutableMap<String, Double>,
    )

    private data class AssociationCandidate(val trackId: String, val score: Double)

    private val initialHeadFromCamera = headFromCamera
    private val records = LinkedHashMap<String, Record>()
    private var nextStableTrackNumber = 1L
    private var lastFrameId = 0L
    private var lastCaptureTimestampNanos = -1L
    private var latestPose: TimestampedPose? = null
    private var activeHeadFromCamera = initialHeadFromCamera

    init {
        require(capacity in 1..128)
        require(trackTtlNanos in 100_000_000L..10_000_000_000L)
        require(minimumPostInferenceHoldNanos in 0L..5_000_000_000L)
        require(maximumDepthAgeNanos in 10_000_000L..trackTtlNanos)
        require(maximumMissedKeyframes in 1..30)
        require(minimumAssociationScore.isFinite() && minimumAssociationScore in 0.0..1.0)
        require(confidenceDecayPerSecond.isFinite() && confidenceDecayPerSecond >= 0.0)
        require(immediateConfirmationConfidence.isFinite() && immediateConfirmationConfidence in 0.0..1.0)
        require(consistentObservationsRequired in 2..10)
        require(confirmationWindowNanos in 100_000_000L..5_000_000_000L)
        require(maximumTentativeMissedKeyframes in 0..maximumMissedKeyframes)
    }

    @Synchronized
    fun updateKeyframe(
        frame: VisionFrame,
        measuredTracks: List<MetricSemanticTrack>,
        capturePose: TimestampedPose? = null,
        appearanceBySourceTrackId: Map<String, FloatArray> = emptyMap(),
    ): LightweightTrackUpdate {
        require(measuredTracks.size <= MAXIMUM_OBSERVATIONS)
        require(measuredTracks.map(MetricSemanticTrack::trackId).toSet().size == measuredTracks.size)
        require(measuredTracks.all {
            it.frameId == frame.frameId &&
                it.trackId.isNotBlank() && it.trackId.length <= 128 &&
                it.classId.isNotBlank() && it.classId.length <= 64 &&
                it.confidence.isFinite() && it.confidence in 0.0..1.0 &&
                it.sourceCaptureMonotonicTimestampNanos == frame.captureMonotonicTimestampNanos &&
                it.maskGeometry?.matches(frame) != false
        })
        require(appearanceBySourceTrackId.keys.all { key -> measuredTracks.any { it.trackId == key } })
        appearanceBySourceTrackId.values.forEach(::requireValidAppearance)
        if (frame.frameId <= lastFrameId || frame.captureMonotonicTimestampNanos <= lastCaptureTimestampNanos) {
            return LightweightTrackUpdate(
                false,
                "non_monotonic_keyframe",
                snapshotInternal(maxOf(lastCaptureTimestampNanos, 0L), emptySet(), emptyList()),
                emptyMap(),
                emptyList(),
            )
        }
        lastFrameId = frame.frameId
        lastCaptureTimestampNanos = frame.captureMonotonicTimestampNanos
        val evicted = pruneExpired(frame.captureMonotonicTimestampNanos).toMutableList()
        val availableTrackIds = records.keys.toMutableSet()
        val associations = linkedMapOf<String, String>()
        val observedStableIds = mutableSetOf<String>()

        val orderedMeasurements = measuredTracks.sortedWith(
            compareByDescending<MetricSemanticTrack>(MetricSemanticTrack::confidence)
                .thenBy(MetricSemanticTrack::trackId),
        )
        for (measurement in orderedMeasurements) {
            val appearance = appearanceBySourceTrackId[measurement.trackId]
            val candidate = availableTrackIds.asSequence()
                .mapNotNull { stableId ->
                    score(records.getValue(stableId), measurement, appearance, frame.captureMonotonicTimestampNanos)
                        ?.let { AssociationCandidate(stableId, it) }
                }
                .filter { it.score >= minimumAssociationScore }
                .sortedWith(compareByDescending<AssociationCandidate>(AssociationCandidate::score)
                    .thenBy(AssociationCandidate::trackId))
                .firstOrNull()
            val stableId = candidate?.trackId ?: nextStableTrackId()
            val prior = records[stableId]
            records[stableId] = if (prior == null) {
                createRecord(stableId, measurement, appearance)
            } else {
                updateRecord(prior, measurement, appearance)
            }
            availableTrackIds.remove(stableId)
            observedStableIds += stableId
            associations[measurement.trackId] = stableId
        }
        records.values.filter { it.stableTrackId !in observedStableIds }.forEach { it.missedKeyframes += 1 }
        evicted += pruneMissed()
        evicted += enforceCapacity()
        evicted.distinct().forEach(temporalStore::remove)

        val survivingAssociations = associations.filterValues { it in records }
        val remappedMetricTracks = measuredTracks.mapNotNull { track ->
            survivingAssociations[track.trackId]?.let { stableId -> track.copy(trackId = stableId) }
        }
        val temporalUpdate = capturePose?.let { temporalStore.updateKeyframe(frame, remappedMetricTracks, it) }
        if (temporalUpdate?.accepted == true &&
            (latestPose == null || capturePose.monotonicTimestampNanos >= requireNotNull(latestPose).monotonicTimestampNanos)
        ) {
            latestPose = capturePose
        }
        val temporal = temporalUpdate?.tracks.orEmpty()
        val outputTimestamp = maxOf(
            frame.captureMonotonicTimestampNanos,
            measuredTracks.maxOfOrNull(MetricSemanticTrack::sourceInferenceMonotonicTimestampNanos)
                ?: frame.captureMonotonicTimestampNanos,
            latestPose?.monotonicTimestampNanos ?: 0L,
        )
        val expiredAfterInference = pruneExpired(outputTimestamp)
        expiredAfterInference.forEach(temporalStore::remove)
        evicted += expiredAfterInference
        val finalAssociations = survivingAssociations.filterValues { it in records }
        return LightweightTrackUpdate(
            true,
            temporalUpdate?.takeUnless(TemporalTrackUpdate::accepted)?.let {
                "keyframe_updated_${it.reason}"
            } ?: "keyframe_updated",
            snapshotInternal(outputTimestamp, observedStableIds, temporal),
            finalAssociations.toSortedMap(),
            evicted.distinct().sorted(),
        )
    }

    @Synchronized
    fun updatePose(pose: TimestampedPose): LightweightTrackUpdate {
        val update = temporalStore.updatePose(pose)
        if (update.accepted) latestPose = pose
        val evicted = pruneExpired(pose.monotonicTimestampNanos)
        evicted.forEach(temporalStore::remove)
        return LightweightTrackUpdate(
            update.accepted,
            update.reason,
            snapshotInternal(pose.monotonicTimestampNanos, emptySet(), update.tracks),
            emptyMap(),
            evicted.sorted(),
        )
    }

    @Synchronized
    fun snapshot(nowNanos: Long): List<LightweightTrackState> {
        require(nowNanos >= 0L)
        val evicted = pruneExpired(nowNanos)
        evicted.forEach(temporalStore::remove)
        return snapshotInternal(nowNanos, emptySet(), temporalStore.snapshot(nowNanos))
    }

    /** Accepts one verified rigid calibration per session; conflicting calibration fails closed. */
    @Synchronized
    fun configureHeadCameraExtrinsic(extrinsic: VerifiedHeadCameraExtrinsic): Boolean {
        val current = activeHeadFromCamera
        if (current != null && current.verificationFingerprint != extrinsic.verificationFingerprint) return false
        activeHeadFromCamera = extrinsic
        return true
    }

    @Synchronized
    fun reset() {
        records.clear()
        temporalStore.reset()
        nextStableTrackNumber = 1L
        lastFrameId = 0L
        lastCaptureTimestampNanos = -1L
        latestPose = null
        activeHeadFromCamera = initialHeadFromCamera
    }

    private fun createRecord(
        stableId: String,
        measurement: MetricSemanticTrack,
        appearance: FloatArray?,
    ): Record = Record(
        stableId,
        measurement.trackId,
        measurement.classId,
        measurement.frameId,
        measurement.sourceCaptureMonotonicTimestampNanos,
        measurement.sourceInferenceMonotonicTimestampNanos,
        measurement.confidence,
        measurement.maskGeometry,
        measurement.representativeDistance,
        measurement.cameraVectorMeters,
        0.0,
        0.0,
        null,
        INITIAL_IMAGE_VARIANCE,
        measurement.representativeDistance.uncertaintyMeters?.let { it * it },
        appearance?.copyOf(),
        0,
        1,
        measurement.confidence >= immediateConfirmationConfidence,
        linkedMapOf(measurement.classId to measurement.confidence * INITIAL_CLASS_VOTE_MULTIPLIER),
    )

    private fun updateRecord(
        record: Record,
        measurement: MetricSemanticTrack,
        appearance: FloatArray?,
    ): Record {
        val elapsedSeconds = (measurement.sourceCaptureMonotonicTimestampNanos - record.sourceCaptureNanos) /
            NANOS_PER_SECOND
        val observationGapNanos =
            (measurement.sourceCaptureMonotonicTimestampNanos - record.sourceCaptureNanos).coerceAtLeast(0L)
        val priorMissedKeyframes = record.missedKeyframes
        val priorGeometry = record.geometry
        val observedGeometry = measurement.maskGeometry
        if (priorGeometry != null && observedGeometry != null && elapsedSeconds > 0.0) {
            val predicted = predictGeometry(record, measurement.sourceCaptureMonotonicTimestampNanos)
            val rawVelocityX = (observedGeometry.centroidXPixels - priorGeometry.centroidXPixels) / elapsedSeconds
            val rawVelocityY = (observedGeometry.centroidYPixels - priorGeometry.centroidYPixels) / elapsedSeconds
            record.velocityX = MOTION_SMOOTHING * rawVelocityX + (1.0 - MOTION_SMOOTHING) * record.velocityX
            record.velocityY = MOTION_SMOOTHING * rawVelocityY + (1.0 - MOTION_SMOOTHING) * record.velocityY
            val residual = hypot(
                observedGeometry.centroidXPixels - predicted.centroidXPixels,
                observedGeometry.centroidYPixels - predicted.centroidYPixels,
            )
            record.imageVariance = max(INITIAL_IMAGE_VARIANCE, residual * residual)
        }
        val priorDepth = record.depth
        val observedDepth = measurement.representativeDistance
        record.approachVelocity = if (priorDepth != null && elapsedSeconds > 0.0) {
            (priorDepth.distanceMeters - observedDepth.distanceMeters) / elapsedSeconds
        } else {
            null
        }
        val depthResidual = priorDepth?.let { it.distanceMeters - observedDepth.distanceMeters }
        record.depthVariance = observedDepth.uncertaintyMeters?.let { uncertainty ->
            uncertainty * uncertainty + (depthResidual?.let { it * it * 0.25 } ?: 0.0)
        }
        record.sourceTrackId = measurement.trackId
        updateStableClass(record, measurement)
        record.sourceFrameId = measurement.frameId
        record.sourceCaptureNanos = measurement.sourceCaptureMonotonicTimestampNanos
        record.sourceInferenceNanos = measurement.sourceInferenceMonotonicTimestampNanos
        record.confidence = CONFIDENCE_OBSERVATION_WEIGHT * measurement.confidence +
            (1.0 - CONFIDENCE_OBSERVATION_WEIGHT) * record.confidence
        record.geometry = observedGeometry
        record.depth = observedDepth
        record.cameraVectorMeters = measurement.cameraVectorMeters
        record.appearance = appearance?.copyOf()
        if (!record.confirmedForPublication) {
            record.consistentObservations = if (
                observationGapNanos <= confirmationWindowNanos &&
                priorMissedKeyframes <= maximumTentativeMissedKeyframes
            ) {
                record.consistentObservations + 1
            } else {
                1
            }
            record.confirmedForPublication =
                measurement.confidence >= immediateConfirmationConfidence ||
                record.consistentObservations >= consistentObservationsRequired
        }
        record.missedKeyframes = 0
        return record
    }

    private fun updateStableClass(record: Record, measurement: MetricSemanticTrack) {
        record.classVotes.replaceAll { _, score -> score * CLASS_VOTE_DECAY }
        record.classVotes.entries.removeIf { it.value < MINIMUM_CLASS_VOTE }
        record.classVotes[measurement.classId] =
            (record.classVotes[measurement.classId] ?: 0.0) + measurement.confidence
        while (record.classVotes.size > MAXIMUM_CLASS_VOTES) {
            val victim = record.classVotes.entries.minWithOrNull(
                compareBy<Map.Entry<String, Double>>(Map.Entry<String, Double>::value)
                    .thenBy(Map.Entry<String, Double>::key),
            ) ?: break
            record.classVotes.remove(victim.key)
        }
        record.classId = record.classVotes.entries.maxWithOrNull(
            compareBy<Map.Entry<String, Double>>(Map.Entry<String, Double>::value)
                .thenByDescending(Map.Entry<String, Double>::key),
        )?.key ?: measurement.classId
    }

    private fun score(
        record: Record,
        measurement: MetricSemanticTrack,
        observedAppearance: FloatArray?,
        timestampNanos: Long,
    ): Double? {
        val sameClass = classCompatibility.compatible(record.classId, measurement.classId)
        val sourceMatch = record.sourceTrackId == measurement.trackId
        var hasIdentityEvidence = sourceMatch
        var weightedScore = if (sourceMatch) 0.20 else 0.0
        var totalWeight = if (sourceMatch) 0.20 else 0.0

        var strongCrossClassGeometry = false
        val predicted = record.geometry?.let { predictGeometry(record, timestampNanos) }
        val observed = measurement.maskGeometry
        if (predicted != null && observed != null) {
            if (predicted.imageWidthPixels != observed.imageWidthPixels ||
                predicted.imageHeightPixels != observed.imageHeightPixels
            ) return null
            val overlap = iou(predicted, observed)
            val diagonal = hypot(observed.imageWidthPixels.toDouble(), observed.imageHeightPixels.toDouble())
            val centroidDistance = hypot(
                predicted.centroidXPixels - observed.centroidXPixels,
                predicted.centroidYPixels - observed.centroidYPixels,
            )
            val centroidScore = (1.0 - centroidDistance / (MAXIMUM_CENTROID_DISTANCE_FRACTION * diagonal))
                .coerceIn(0.0, 1.0)
            if (overlap < MINIMUM_GEOMETRY_IOU && centroidScore == 0.0 && !sourceMatch) return null
            val maskAreaScore = min(predicted.foregroundPixelCount, observed.foregroundPixelCount).toDouble() /
                max(predicted.foregroundPixelCount, observed.foregroundPixelCount)
            strongCrossClassGeometry = overlap >= MINIMUM_CROSS_CLASS_IOU &&
                centroidScore >= MINIMUM_CROSS_CLASS_CENTROID_SCORE
            weightedScore += GEOMETRY_WEIGHT * (0.55 * overlap + 0.30 * centroidScore + 0.15 * maskAreaScore)
            totalWeight += GEOMETRY_WEIGHT
            hasIdentityEvidence = true
        }
        if (!sameClass && !strongCrossClassGeometry) return null

        val priorDepth = record.depth
        val observedDepth = measurement.representativeDistance
        if (priorDepth != null) {
            val difference = abs(priorDepth.distanceMeters - observedDepth.distanceMeters)
            val tolerance = max(
                MINIMUM_DEPTH_TOLERANCE_METERS,
                max(
                    3.0 * ((priorDepth.uncertaintyMeters ?: 0.0) +
                        (observedDepth.uncertaintyMeters ?: 0.0)),
                    min(priorDepth.distanceMeters, observedDepth.distanceMeters) * DEPTH_TOLERANCE_FRACTION,
                ),
            )
            // An upstream ID is evidence, not permission to bridge an implausible metric jump.
            if (difference > tolerance * MAXIMUM_DEPTH_TOLERANCE_MULTIPLIER) return null
            if (difference > tolerance && !sourceMatch) return null
            weightedScore += DEPTH_WEIGHT * exp(-difference / tolerance)
            totalWeight += DEPTH_WEIGHT
        }

        val appearanceHook = appearanceSimilarity
        val priorAppearance = record.appearance
        if (appearanceHook != null && priorAppearance != null && observedAppearance != null) {
            val similarity = appearanceHook.similarity(priorAppearance.copyOf(), observedAppearance.copyOf())
            if (!similarity.isFinite() || similarity !in 0.0..1.0) return null
            weightedScore += APPEARANCE_WEIGHT * similarity
            totalWeight += APPEARANCE_WEIGHT
            hasIdentityEvidence = true
        }
        if (!hasIdentityEvidence || totalWeight <= 0.0) return null
        val normalized = weightedScore / totalWeight
        return if (sameClass) normalized else normalized * CROSS_CLASS_ASSOCIATION_PENALTY
    }

    private fun snapshotInternal(
        nowNanos: Long,
        observedStableIds: Set<String>,
        temporalTracks: List<TemporalMetricTrack>,
    ): List<LightweightTrackState> {
        val temporalById = temporalTracks.associateBy(TemporalMetricTrack::stableTrackId)
        val pose = latestPose
        return records.values.map { record ->
            val ageNanos = (nowNanos - record.sourceCaptureNanos).coerceAtLeast(0L)
            val ageSeconds = ageNanos / NANOS_PER_SECOND
            val confidence = (record.confidence * exp(-confidenceDecayPerSecond * ageSeconds))
                .coerceIn(0.0, 1.0)
            val observed = record.stableTrackId in observedStableIds
            val predictedGeometry = record.geometry?.let {
                if (observed) it else predictGeometry(record, nowNanos)
            }
            val temporal = temporalById[record.stableTrackId]
            val cameraVector = temporal?.cameraVectorMeters ?: record.cameraVectorMeters
            val headVector = cameraVector?.let(::cameraToHead)
            val cameraValidity = when {
                cameraVector == null -> TrackEstimateValidity.UNAVAILABLE
                temporal?.translationApplied == true -> TrackEstimateValidity.TRANSLATION_EVIDENCE_PROPAGATED
                temporal?.propagated == true -> TrackEstimateValidity.ORIENTATION_PROPAGATED
                else -> TrackEstimateValidity.OBSERVED
            }
            val headValidity = when {
                headVector == null -> TrackEstimateValidity.UNAVAILABLE
                temporal?.translationApplied == true -> TrackEstimateValidity.TRANSLATION_EVIDENCE_PROPAGATED
                temporal?.propagated == true -> TrackEstimateValidity.ORIENTATION_PROPAGATED
                else -> TrackEstimateValidity.OBSERVED
            }
            val positionEvidence = pose?.positionEvidence
            val localWorld = if (temporal?.translationApplied == true && positionEvidence != null) {
                positionEvidence.positionWorldMeters + pose.worldFromCamera.rotate(temporal.cameraVectorMeters)
            } else {
                null
            }
            val imageGrowth = if (observed) 0.0 else {
                PREDICTION_VARIANCE_PIXELS_SQUARED_PER_SECOND * ageSeconds +
                    record.missedKeyframes * MISSED_KEYFRAME_VARIANCE_PIXELS_SQUARED
            }
            val orientationVariance = if (temporal?.propagated == true) {
                val radians = ORIENTATION_UNCERTAINTY_RADIANS_PER_SECOND * ageSeconds
                radians * radians
            } else {
                null
            }
            val localWorldVariance = if (localWorld != null && positionEvidence != null) {
                positionEvidence.uncertaintyMeters * positionEvidence.uncertaintyMeters +
                    (record.depthVariance ?: 0.0)
            } else {
                null
            }
            LightweightTrackState(
                stableTrackId = record.stableTrackId,
                sourceTrackId = record.sourceTrackId,
                classId = record.classId,
                sourceFrameId = record.sourceFrameId,
                sourceCaptureTimestampNanos = record.sourceCaptureNanos,
                sourceInferenceTimestampNanos = record.sourceInferenceNanos,
                outputTimestampNanos = maxOf(nowNanos, record.sourceInferenceNanos),
                expiresAtTimestampNanos = expiryTimestampNanos(record),
                confidence = confidence,
                coordinateValidity = TrackCoordinateValidity(
                    image2d = if (predictedGeometry == null) {
                        TrackEstimateValidity.UNAVAILABLE
                    } else if (observed) {
                        TrackEstimateValidity.OBSERVED
                    } else {
                        TrackEstimateValidity.MOTION_PREDICTED
                    },
                    cameraRelative = cameraValidity,
                    headRelative = headValidity,
                    localWorld = if (localWorld == null) {
                        TrackEstimateValidity.UNAVAILABLE
                    } else {
                        TrackEstimateValidity.TRANSLATION_EVIDENCE_PROPAGATED
                    },
                ),
                imageGeometry = predictedGeometry,
                cameraRelativeVectorMeters = cameraVector,
                headRelativeVectorMeters = headVector,
                localWorldPositionMeters = localWorld,
                metricDepth = record.depth,
                depthFresh = ageNanos <= maximumDepthAgeNanos,
                centroidVelocityXPixelsPerSecond = record.velocityX,
                centroidVelocityYPixelsPerSecond = record.velocityY,
                approachVelocityMetersPerSecond = record.approachVelocity,
                missedKeyframes = record.missedKeyframes,
                headCameraTranslationApplied = headVector != null &&
                    activeHeadFromCamera?.headFromCameraTranslationMeters != null,
                covariance = LightweightTrackCovariance(
                    imageXxPixelsSquared = record.imageVariance + imageGrowth,
                    imageXyPixelsSquared = 0.0,
                    imageYyPixelsSquared = record.imageVariance + imageGrowth,
                    depthVarianceMetersSquared = record.depthVariance,
                    headOrientationVarianceRadiansSquared = orientationVariance,
                    localWorldVarianceMetersSquared = localWorldVariance,
                ),
                confirmedForPublication = record.confirmedForPublication,
            )
        }.sortedBy(LightweightTrackState::stableTrackId)
    }

    private fun cameraToHead(cameraVector: MetricVector3): MetricVector3? {
        val extrinsic = activeHeadFromCamera ?: return null
        val rotated = extrinsic.headFromCameraRotation.rotate(cameraVector)
        return extrinsic.headFromCameraTranslationMeters?.let(rotated::plus) ?: rotated
    }

    private fun predictGeometry(record: Record, timestampNanos: Long): InstanceMaskGeometry {
        val geometry = requireNotNull(record.geometry)
        val seconds = ((timestampNanos - record.sourceCaptureNanos).coerceAtLeast(0L) / NANOS_PER_SECOND)
            .coerceAtMost(MAXIMUM_PREDICTION_SECONDS)
        val desiredLeft = geometry.leftPixels + record.velocityX * seconds
        val desiredTop = geometry.topPixels + record.velocityY * seconds
        val left = desiredLeft.roundToInt().coerceIn(0, geometry.imageWidthPixels - geometry.widthPixels)
        val top = desiredTop.roundToInt().coerceIn(0, geometry.imageHeightPixels - geometry.heightPixels)
        return InstanceMaskGeometry(
            geometry.imageWidthPixels,
            geometry.imageHeightPixels,
            left,
            top,
            left + geometry.widthPixels,
            top + geometry.heightPixels,
            (geometry.centroidXPixels + (left - geometry.leftPixels)).coerceIn(
                left.toDouble(),
                left + geometry.widthPixels - 1.0,
            ),
            (geometry.centroidYPixels + (top - geometry.topPixels)).coerceIn(
                top.toDouble(),
                top + geometry.heightPixels - 1.0,
            ),
            geometry.foregroundPixelCount,
        )
    }

    private fun pruneExpired(nowNanos: Long): List<String> {
        val expired = records.values.filter {
            // A result that already exceeded the capture-age admission limit must never be
            // revived by the post-inference continuity window. For an admitted result, however,
            // preserve a bounded period after inference so transport/model latency cannot consume
            // the entire user-facing dwell interval before the track is first published.
            it.sourceInferenceNanos > saturatingAdd(it.sourceCaptureNanos, trackTtlNanos) ||
                nowNanos > expiryTimestampNanos(it)
        }.map(Record::stableTrackId).sorted()
        expired.forEach(records::remove)
        return expired
    }

    private fun expiryTimestampNanos(record: Record): Long = maxOf(
        saturatingAdd(record.sourceCaptureNanos, trackTtlNanos),
        saturatingAdd(record.sourceInferenceNanos, minimumPostInferenceHoldNanos),
    )

    private fun pruneMissed(): List<String> {
        val expired = records.values.filter {
            it.missedKeyframes > if (it.confirmedForPublication) {
                maximumMissedKeyframes
            } else {
                maximumTentativeMissedKeyframes
            }
        }
            .map(Record::stableTrackId).sorted()
        expired.forEach(records::remove)
        return expired
    }

    private fun enforceCapacity(): List<String> {
        val evicted = mutableListOf<String>()
        while (records.size > capacity) {
            val victim = records.values.minWithOrNull(
                compareBy<Record> { if (it.missedKeyframes > 0) 0 else 1 }
                    .thenBy(Record::confidence)
                    .thenBy(Record::sourceCaptureNanos)
                    .thenBy(Record::stableTrackId),
            ) ?: break
            records.remove(victim.stableTrackId)
            evicted += victim.stableTrackId
        }
        return evicted
    }

    private fun nextStableTrackId(): String =
        "maint-${nextStableTrackNumber++.toString(16).padStart(8, '0')}"

    private fun iou(first: InstanceMaskGeometry, second: InstanceMaskGeometry): Double {
        val left = max(first.leftPixels, second.leftPixels)
        val top = max(first.topPixels, second.topPixels)
        val right = min(first.rightExclusivePixels, second.rightExclusivePixels)
        val bottom = min(first.bottomExclusivePixels, second.bottomExclusivePixels)
        val intersection = max(0, right - left).toLong() * max(0, bottom - top)
        val union = first.widthPixels.toLong() * first.heightPixels +
            second.widthPixels.toLong() * second.heightPixels - intersection
        return if (union <= 0L) 0.0 else intersection.toDouble() / union
    }

    private fun requireValidAppearance(descriptor: FloatArray) {
        require(descriptor.size in 1..MAXIMUM_APPEARANCE_DIMENSIONS)
        require(descriptor.all(Float::isFinite))
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private companion object {
        const val MAXIMUM_OBSERVATIONS = 64
        const val MAXIMUM_APPEARANCE_DIMENSIONS = 1_024
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val MAXIMUM_PREDICTION_SECONDS = 1.0
        const val MOTION_SMOOTHING = 0.65
        const val INITIAL_IMAGE_VARIANCE = 4.0
        const val PREDICTION_VARIANCE_PIXELS_SQUARED_PER_SECOND = 36.0
        const val MISSED_KEYFRAME_VARIANCE_PIXELS_SQUARED = 16.0
        const val ORIENTATION_UNCERTAINTY_RADIANS_PER_SECOND = 0.035
        const val MINIMUM_GEOMETRY_IOU = 0.03
        const val MINIMUM_CROSS_CLASS_IOU = 0.35
        const val MINIMUM_CROSS_CLASS_CENTROID_SCORE = 0.65
        const val MAXIMUM_CENTROID_DISTANCE_FRACTION = 0.30
        const val MINIMUM_DEPTH_TOLERANCE_METERS = 0.75
        const val DEPTH_TOLERANCE_FRACTION = 0.40
        const val MAXIMUM_DEPTH_TOLERANCE_MULTIPLIER = 2.0
        const val GEOMETRY_WEIGHT = 0.55
        const val DEPTH_WEIGHT = 0.25
        const val APPEARANCE_WEIGHT = 0.20
        const val CROSS_CLASS_ASSOCIATION_PENALTY = 0.82
        const val CONFIDENCE_OBSERVATION_WEIGHT = 0.65
        const val CLASS_VOTE_DECAY = 0.85
        const val INITIAL_CLASS_VOTE_MULTIPLIER = 2.0
        const val MINIMUM_CLASS_VOTE = 0.01
        const val MAXIMUM_CLASS_VOTES = 8
    }
}

data class SemanticDepthRefreshSignal(
    val visual: VisualKeyframeSignal,
    val minimumTrackConfidence: Double = 1.0,
    val oldestTrackAgeNanos: Long = 0L,
    val occludedTrackCount: Int = 0,
    val maximumApproachVelocityMetersPerSecond: Double = 0.0,
    val depthAgeNanos: Long = 0L,
    val vlmRequested: Boolean = false,
    val vlmIdleCapacityAvailable: Boolean = false,
) {
    init {
        require(minimumTrackConfidence.isFinite() && minimumTrackConfidence in 0.0..1.0)
        require(oldestTrackAgeNanos >= 0L && depthAgeNanos >= 0L)
        require(occludedTrackCount in 0..128)
        require(maximumApproachVelocityMetersPerSecond.isFinite() &&
            maximumApproachVelocityMetersPerSecond >= 0.0)
    }
}

enum class SemanticDepthRefreshReason {
    INITIAL,
    STABLE_CADENCE,
    MOTION,
    UNCERTAINTY,
    LOW_CONFIDENCE,
    TRACK_STALE,
    OCCLUSION,
    RAPID_APPROACH,
    DEPTH_STALE,
    DEFERRED_BY_CADENCE,
    PENDING_FRAME_STALE,
    NO_PENDING_FRAME,
}

data class SemanticDepthScheduleDecision(
    val frame: VisionFrame?,
    val reason: SemanticDepthRefreshReason,
    val cadenceTier: SemanticDepthCadenceTier,
    val forced: Boolean,
    val opportunisticVlmAllowed: Boolean,
    val replacedPendingFrames: Long,
) {
    val runSemanticAndDepth: Boolean get() = frame != null
}

enum class SemanticDepthCadenceTier {
    STABLE,
    MATERIAL,
    URGENT,
}

/**
 * Single-slot scheduler: semantics/depth default to 1 FPS, rise to 3 FPS for material motion or
 * uncertainty, and reach 5 FPS only for evidence that a current track needs urgent correction.
 */
class LatestOnlySemanticDepthScheduler(
    stableFramesPerSecond: Int = 1,
    materialMotionFramesPerSecond: Int = 3,
    urgentFramesPerSecond: Int = 5,
    private val motionThreshold: Double = 0.65,
    private val uncertaintyThreshold: Double = 0.65,
    private val lowConfidenceThreshold: Double = 0.45,
    private val trackStaleNanos: Long = 750_000_000L,
    private val depthStaleNanos: Long = 500_000_000L,
    private val rapidApproachMetersPerSecond: Double = 0.75,
    private val maximumPendingAgeNanos: Long = 750_000_000L,
) {
    private data class Pending(val frame: VisionFrame, val signal: SemanticDepthRefreshSignal)

    private val stableIntervalNanos: Long
    private val materialMotionIntervalNanos: Long
    private val urgentIntervalNanos: Long
    private var pending: Pending? = null
    private var lastOfferedFrameId = 0L
    private var lastOfferedCaptureNanos = -1L
    private var lastScheduledCaptureNanos: Long? = null
    private var replacedPendingFrames = 0L

    init {
        require(stableFramesPerSecond in 1..3)
        require(materialMotionFramesPerSecond in stableFramesPerSecond..3)
        require(urgentFramesPerSecond in materialMotionFramesPerSecond..5)
        require(motionThreshold in 0.0..1.0 && uncertaintyThreshold in 0.0..1.0)
        require(lowConfidenceThreshold in 0.0..1.0)
        require(trackStaleNanos > 0L && depthStaleNanos > 0L && maximumPendingAgeNanos > 0L)
        require(rapidApproachMetersPerSecond.isFinite() && rapidApproachMetersPerSecond > 0.0)
        stableIntervalNanos = 1_000_000_000L / stableFramesPerSecond
        materialMotionIntervalNanos = 1_000_000_000L / materialMotionFramesPerSecond
        urgentIntervalNanos = 1_000_000_000L / urgentFramesPerSecond
    }

    @Synchronized
    fun offer(frame: VisionFrame, signal: SemanticDepthRefreshSignal): Boolean {
        if (frame.frameId <= lastOfferedFrameId || frame.captureMonotonicTimestampNanos <= lastOfferedCaptureNanos) {
            return false
        }
        if (pending != null) replacedPendingFrames += 1L
        pending = Pending(frame, signal)
        lastOfferedFrameId = frame.frameId
        lastOfferedCaptureNanos = frame.captureMonotonicTimestampNanos
        return true
    }

    @Synchronized
    fun takeLatest(nowNanos: Long): SemanticDepthScheduleDecision {
        require(nowNanos >= 0L)
        val candidate = pending ?: return decision(null, SemanticDepthRefreshReason.NO_PENDING_FRAME)
        if (candidate.frame.captureMonotonicTimestampNanos > nowNanos) {
            return decision(null, SemanticDepthRefreshReason.DEFERRED_BY_CADENCE)
        }
        if (nowNanos - candidate.frame.captureMonotonicTimestampNanos > maximumPendingAgeNanos) {
            pending = null
            return decision(null, SemanticDepthRefreshReason.PENDING_FRAME_STALE)
        }
        val refreshReason = forcedReason(candidate.signal)
        val tier = cadenceTier(refreshReason)
        val forced = tier != SemanticDepthCadenceTier.STABLE
        val previous = lastScheduledCaptureNanos
        val requiredInterval = when (tier) {
            SemanticDepthCadenceTier.STABLE -> stableIntervalNanos
            SemanticDepthCadenceTier.MATERIAL -> materialMotionIntervalNanos
            SemanticDepthCadenceTier.URGENT -> urgentIntervalNanos
        }
        if (previous != null && candidate.frame.captureMonotonicTimestampNanos - previous < requiredInterval) {
            return decision(null, SemanticDepthRefreshReason.DEFERRED_BY_CADENCE, tier)
        }
        pending = null
        lastScheduledCaptureNanos = candidate.frame.captureMonotonicTimestampNanos
        val reason = when {
            previous == null -> SemanticDepthRefreshReason.INITIAL
            refreshReason != null -> refreshReason
            else -> SemanticDepthRefreshReason.STABLE_CADENCE
        }
        val vlmAllowed = candidate.signal.vlmRequested && candidate.signal.vlmIdleCapacityAvailable && !forced
        return SemanticDepthScheduleDecision(
            candidate.frame,
            reason,
            tier,
            forced,
            vlmAllowed,
            replacedPendingFrames,
        )
    }

    @Synchronized
    fun reset() {
        pending = null
        lastOfferedFrameId = 0L
        lastOfferedCaptureNanos = -1L
        lastScheduledCaptureNanos = null
        replacedPendingFrames = 0L
    }

    private fun forcedReason(signal: SemanticDepthRefreshSignal): SemanticDepthRefreshReason? = when {
        signal.maximumApproachVelocityMetersPerSecond >= rapidApproachMetersPerSecond ->
            SemanticDepthRefreshReason.RAPID_APPROACH
        signal.occludedTrackCount > 0 -> SemanticDepthRefreshReason.OCCLUSION
        // Direct camera-motion evidence must outrank routine age/confidence maintenance so the
        // VLM arbitration layer cannot mistake real movement for a non-interrupting stale refresh.
        signal.visual.motionScore >= motionThreshold -> SemanticDepthRefreshReason.MOTION
        signal.depthAgeNanos >= depthStaleNanos -> SemanticDepthRefreshReason.DEPTH_STALE
        signal.minimumTrackConfidence <= lowConfidenceThreshold -> SemanticDepthRefreshReason.LOW_CONFIDENCE
        signal.oldestTrackAgeNanos >= trackStaleNanos -> SemanticDepthRefreshReason.TRACK_STALE
        signal.visual.uncertaintyScore >= uncertaintyThreshold -> SemanticDepthRefreshReason.UNCERTAINTY
        else -> null
    }

    private fun cadenceTier(reason: SemanticDepthRefreshReason?): SemanticDepthCadenceTier = when (reason) {
        SemanticDepthRefreshReason.RAPID_APPROACH,
        SemanticDepthRefreshReason.OCCLUSION,
        SemanticDepthRefreshReason.DEPTH_STALE,
        SemanticDepthRefreshReason.LOW_CONFIDENCE,
        -> SemanticDepthCadenceTier.URGENT
        SemanticDepthRefreshReason.TRACK_STALE,
        SemanticDepthRefreshReason.MOTION,
        SemanticDepthRefreshReason.UNCERTAINTY,
        -> SemanticDepthCadenceTier.MATERIAL
        else -> SemanticDepthCadenceTier.STABLE
    }

    private fun decision(
        frame: VisionFrame?,
        reason: SemanticDepthRefreshReason,
        tier: SemanticDepthCadenceTier = SemanticDepthCadenceTier.STABLE,
    ) = SemanticDepthScheduleDecision(
        frame,
        reason,
        tier,
        tier != SemanticDepthCadenceTier.STABLE,
        false,
        replacedPendingFrames,
    )
}

/**
 * Converts the already power-gated Rokid camera cadence plus maintained-track health into model
 * admission. The cadence hint is not optical flow and is never described as visual correction.
 */
class LiveSemanticDepthAdmissionPolicy(
    private val scheduler: LatestOnlySemanticDepthScheduler = LatestOnlySemanticDepthScheduler(),
    private val materialCameraIntervalNanos: Long = 275_000_000L,
) {
    private var previousCameraCaptureNanos: Long? = null

    init {
        require(materialCameraIntervalNanos in 200_000_000L..333_333_333L)
    }

    @Synchronized
    fun evaluate(
        frame: VisionFrame,
        maintainedTracks: List<LightweightTrackState>,
        requestOpportunisticVlm: Boolean,
        vlmCapacityAvailable: Boolean,
        suppressCameraCadenceMotionForVlmBootstrap: Boolean = false,
    ): SemanticDepthScheduleDecision {
        val previousCapture = previousCameraCaptureNanos
        val cadenceIndicatesMaterialMotion = previousCapture != null &&
            frame.captureMonotonicTimestampNanos > previousCapture &&
            frame.captureMonotonicTimestampNanos - previousCapture <= materialCameraIntervalNanos
        if (previousCapture == null || frame.captureMonotonicTimestampNanos > previousCapture) {
            previousCameraCaptureNanos = frame.captureMonotonicTimestampNanos
        }
        val now = frame.captureMonotonicTimestampNanos
        val signal = SemanticDepthRefreshSignal(
            visual = VisualKeyframeSignal(
                // During unresolved automatic-environment bootstrap, retain a restrained 1 FPS
                // opportunity for the asynchronous VLM. Track-health urgency still wins below.
                motionScore = if (
                    cadenceIndicatesMaterialMotion && !suppressCameraCadenceMotionForVlmBootstrap
                ) 1.0 else 0.0,
                uncertaintyScore = normalizedTrackUncertainty(maintainedTracks),
            ),
            minimumTrackConfidence = maintainedTracks.minOfOrNull(LightweightTrackState::confidence) ?: 1.0,
            oldestTrackAgeNanos = maintainedTracks.maxOfOrNull {
                (now - it.sourceCaptureTimestampNanos).coerceAtLeast(0L)
            } ?: 0L,
            occludedTrackCount = maintainedTracks.count { it.missedKeyframes > 0 },
            maximumApproachVelocityMetersPerSecond = maintainedTracks.maxOfOrNull {
                (it.approachVelocityMetersPerSecond ?: 0.0).coerceAtLeast(0.0)
            } ?: 0.0,
            depthAgeNanos = maintainedTracks.filter { it.metricDepth != null }.maxOfOrNull {
                (now - it.sourceCaptureTimestampNanos).coerceAtLeast(0L)
            } ?: 0L,
            vlmRequested = requestOpportunisticVlm,
            vlmIdleCapacityAvailable = vlmCapacityAvailable,
        )
        scheduler.offer(frame, signal)
        return scheduler.takeLatest(now)
    }

    @Synchronized
    fun reset() {
        previousCameraCaptureNanos = null
        scheduler.reset()
    }

    private fun normalizedTrackUncertainty(tracks: List<LightweightTrackState>): Double =
        tracks.maxOfOrNull { track ->
            val diagonal = track.imageGeometry?.let {
                hypot(it.imageWidthPixels.toDouble(), it.imageHeightPixels.toDouble())
            } ?: return@maxOfOrNull 0.0
            if (diagonal == 0.0) 0.0 else {
                sqrt(max(track.covariance.imageXxPixelsSquared, track.covariance.imageYyPixelsSquared)) /
                    diagonal
            }
        }?.coerceIn(0.0, 1.0) ?: 0.0
}
