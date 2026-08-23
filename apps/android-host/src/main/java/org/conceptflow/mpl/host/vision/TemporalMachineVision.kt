// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import java.util.LinkedHashMap
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

data class MetricVector3(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite())
    }

    val lengthMeters: Double
        get() = sqrt(x * x + y * y + z * z)

    operator fun plus(other: MetricVector3) = MetricVector3(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: MetricVector3) = MetricVector3(x - other.x, y - other.y, z - other.z)
}

enum class CameraIntrinsicsSource {
    CALIBRATED,
    DERIVED,
}

/** Only identity distortion is admitted until bounded inverse distortion is implemented. */
enum class CameraLensDistortionModel {
    BROWN_CONRADY_ZERO,
}

data class CameraIntrinsicsStandardDeviation(
    val focalLengthXPixels: Double,
    val focalLengthYPixels: Double,
    val principalPointXPixels: Double,
    val principalPointYPixels: Double,
) {
    init {
        require(focalLengthXPixels.isFinite() && focalLengthXPixels >= 0.0)
        require(focalLengthYPixels.isFinite() && focalLengthYPixels >= 0.0)
        require(principalPointXPixels.isFinite() && principalPointXPixels >= 0.0)
        require(principalPointYPixels.isFinite() && principalPointYPixels >= 0.0)
    }
}

data class CameraIntrinsics(
    val imageWidthPixels: Int,
    val imageHeightPixels: Int,
    val focalLengthXPixels: Double,
    val focalLengthYPixels: Double,
    val principalPointXPixels: Double,
    val principalPointYPixels: Double,
    val source: CameraIntrinsicsSource = CameraIntrinsicsSource.CALIBRATED,
    val standardDeviation: CameraIntrinsicsStandardDeviation? = null,
    val distortionModel: CameraLensDistortionModel = CameraLensDistortionModel.BROWN_CONRADY_ZERO,
) {
    val calibrationFingerprint: String
        get() {
            val canonical = listOf(
                imageWidthPixels.toString(),
                imageHeightPixels.toString(),
                java.lang.Double.toHexString(focalLengthXPixels),
                java.lang.Double.toHexString(focalLengthYPixels),
                java.lang.Double.toHexString(principalPointXPixels),
                java.lang.Double.toHexString(principalPointYPixels),
                source.name,
                distortionModel.name,
                standardDeviation?.let {
                    listOf(
                        java.lang.Double.toHexString(it.focalLengthXPixels),
                        java.lang.Double.toHexString(it.focalLengthYPixels),
                        java.lang.Double.toHexString(it.principalPointXPixels),
                        java.lang.Double.toHexString(it.principalPointYPixels),
                    ).joinToString(",")
                } ?: "uncertainty-unreported",
            ).joinToString("|")
            return MessageDigest.getInstance("SHA-256")
                .digest(canonical.encodeToByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }

    init {
        require(imageWidthPixels in 1..7_680 && imageHeightPixels in 1..4_320)
        require(focalLengthXPixels.isFinite() && focalLengthXPixels > 0.0)
        require(focalLengthYPixels.isFinite() && focalLengthYPixels > 0.0)
        require(principalPointXPixels.isFinite() && principalPointXPixels in 0.0..<imageWidthPixels.toDouble())
        require(principalPointYPixels.isFinite() && principalPointYPixels in 0.0..<imageHeightPixels.toDouble())
    }

    fun vectorAtDepth(pixelX: Double, pixelY: Double, depthMeters: Double): MetricVector3 {
        require(pixelX.isFinite() && pixelX in 0.0..<imageWidthPixels.toDouble())
        require(pixelY.isFinite() && pixelY in 0.0..<imageHeightPixels.toDouble())
        require(depthMeters.isFinite() && depthMeters > 0.0)
        return MetricVector3(
            x = (pixelX - principalPointXPixels) * depthMeters / focalLengthXPixels,
            y = (pixelY - principalPointYPixels) * depthMeters / focalLengthYPixels,
            z = depthMeters,
        )
    }
}

data class InstanceMaskGeometry(
    val imageWidthPixels: Int,
    val imageHeightPixels: Int,
    val leftPixels: Int,
    val topPixels: Int,
    val rightExclusivePixels: Int,
    val bottomExclusivePixels: Int,
    val centroidXPixels: Double = (leftPixels + rightExclusivePixels - 1) / 2.0,
    val centroidYPixels: Double = (topPixels + bottomExclusivePixels - 1) / 2.0,
    val foregroundPixelCount: Int = (rightExclusivePixels - leftPixels) * (bottomExclusivePixels - topPixels),
) {
    val widthPixels: Int = rightExclusivePixels - leftPixels
    val heightPixels: Int = bottomExclusivePixels - topPixels

    init {
        require(imageWidthPixels in 1..7_680 && imageHeightPixels in 1..4_320)
        require(leftPixels in 0 until imageWidthPixels && topPixels in 0 until imageHeightPixels)
        require(rightExclusivePixels in (leftPixels + 1)..imageWidthPixels)
        require(bottomExclusivePixels in (topPixels + 1)..imageHeightPixels)
        require(centroidXPixels.isFinite() && centroidXPixels >= leftPixels && centroidXPixels < rightExclusivePixels)
        require(centroidYPixels.isFinite() && centroidYPixels >= topPixels && centroidYPixels < bottomExclusivePixels)
        require(foregroundPixelCount in 1..(widthPixels * heightPixels))
    }

    fun matches(frame: VisionFrame): Boolean =
        imageWidthPixels == frame.width && imageHeightPixels == frame.height
}

data class VisualKeyframeSignal(
    val motionScore: Double,
    val uncertaintyScore: Double,
) {
    init {
        require(motionScore.isFinite() && motionScore in 0.0..1.0)
        require(uncertaintyScore.isFinite() && uncertaintyScore in 0.0..1.0)
    }
}

data class VisualKeyframeDecision(
    val acceptedFrame: VisionFrame?,
    val reason: String,
) {
    val accepted: Boolean
        get() = acceptedFrame != null
}

/** Admits only observed frames at a stable 3 FPS or forced, bounded 5 FPS cadence. */
class VisualKeyframeGate(
    stableFramesPerSecond: Int = 3,
    forcedFramesPerSecond: Int = 5,
    private val motionForceThreshold: Double = 0.65,
    private val uncertaintyForceThreshold: Double = 0.65,
) {
    private val stableIntervalNanos: Long
    private val forcedIntervalNanos: Long
    private var lastObservedFrameId: Long? = null
    private var lastObservedTimestampNanos: Long? = null
    private var lastAcceptedTimestampNanos: Long? = null

    init {
        require(stableFramesPerSecond in 1..5)
        require(forcedFramesPerSecond in stableFramesPerSecond..5)
        require(motionForceThreshold.isFinite() && motionForceThreshold in 0.0..1.0)
        require(uncertaintyForceThreshold.isFinite() && uncertaintyForceThreshold in 0.0..1.0)
        stableIntervalNanos = NANOS_PER_SECOND / stableFramesPerSecond
        forcedIntervalNanos = NANOS_PER_SECOND / forcedFramesPerSecond
    }

    @Synchronized
    fun evaluate(frame: VisionFrame, signal: VisualKeyframeSignal): VisualKeyframeDecision {
        val priorFrameId = lastObservedFrameId
        val priorTimestamp = lastObservedTimestampNanos
        if (priorFrameId != null &&
            (frame.frameId <= priorFrameId || frame.captureMonotonicTimestampNanos <= requireNotNull(priorTimestamp))
        ) {
            return VisualKeyframeDecision(null, "non_monotonic_frame")
        }
        lastObservedFrameId = frame.frameId
        lastObservedTimestampNanos = frame.captureMonotonicTimestampNanos

        val previousAcceptance = lastAcceptedTimestampNanos
        if (previousAcceptance == null) {
            lastAcceptedTimestampNanos = frame.captureMonotonicTimestampNanos
            return VisualKeyframeDecision(frame, "initial_keyframe")
        }
        val forced = signal.motionScore >= motionForceThreshold ||
            signal.uncertaintyScore >= uncertaintyForceThreshold
        val elapsedNanos = frame.captureMonotonicTimestampNanos - previousAcceptance
        val tooSoon = if (forced) {
            elapsedNanos < forcedIntervalNanos
        } else {
            elapsedNanos + STABLE_CADENCE_TOLERANCE_NANOS < stableIntervalNanos
        }
        if (tooSoon) {
            return VisualKeyframeDecision(null, if (forced) "five_fps_cap" else "three_fps_cadence")
        }
        lastAcceptedTimestampNanos = frame.captureMonotonicTimestampNanos
        return VisualKeyframeDecision(frame, if (forced) "forced_keyframe" else "stable_keyframe")
    }

    @Synchronized
    fun reset() {
        lastObservedFrameId = null
        lastObservedTimestampNanos = null
        lastAcceptedTimestampNanos = null
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val STABLE_CADENCE_TOLERANCE_NANOS = 1_000_000L
    }
}

data class UnitQuaternion(
    val w: Double,
    val x: Double,
    val y: Double,
    val z: Double,
) {
    init {
        require(w.isFinite() && x.isFinite() && y.isFinite() && z.isFinite())
        val magnitude = sqrt(w * w + x * x + y * y + z * z)
        require(abs(magnitude - 1.0) <= NORMALIZATION_TOLERANCE)
    }

    fun inverse(): UnitQuaternion = UnitQuaternion(w, -x, -y, -z)

    fun rotate(vector: MetricVector3): MetricVector3 {
        val dot = x * vector.x + y * vector.y + z * vector.z
        val crossX = y * vector.z - z * vector.y
        val crossY = z * vector.x - x * vector.z
        val crossZ = x * vector.y - y * vector.x
        return MetricVector3(
            2.0 * dot * x + (w * w - x * x - y * y - z * z) * vector.x + 2.0 * w * crossX,
            2.0 * dot * y + (w * w - x * x - y * y - z * z) * vector.y + 2.0 * w * crossY,
            2.0 * dot * z + (w * w - x * x - y * y - z * z) * vector.z + 2.0 * w * crossZ,
        )
    }

    companion object {
        val IDENTITY = UnitQuaternion(1.0, 0.0, 0.0, 0.0)
        private const val NORMALIZATION_TOLERANCE = 1e-6
    }
}

enum class PositionEvidenceSource {
    VIO,
    EXTERNAL_TRACKING,
}

data class PositionEvidence(
    val positionWorldMeters: MetricVector3,
    val uncertaintyMeters: Double,
    val source: PositionEvidenceSource,
    val coordinateFrameId: String,
) {
    init {
        require(uncertaintyMeters.isFinite() && uncertaintyMeters >= 0.0)
        require(COORDINATE_FRAME_ID.matches(coordinateFrameId))
    }

    private companion object {
        val COORDINATE_FRAME_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")
    }
}

/** Orientation maps camera coordinates into a static world coordinate system. */
data class TimestampedPose(
    val monotonicTimestampNanos: Long,
    val worldFromCamera: UnitQuaternion,
    val positionEvidence: PositionEvidence? = null,
) {
    init {
        require(monotonicTimestampNanos >= 0L)
    }
}

data class TemporalMetricTrack(
    val stableTrackId: String,
    val classId: String,
    val sourceFrameId: Long,
    val sourceCaptureMonotonicTimestampNanos: Long,
    val sourceInferenceMonotonicTimestampNanos: Long,
    val outputMonotonicTimestampNanos: Long,
    val cameraVectorMeters: MetricVector3,
    val distanceMeters: Double,
    val uncertaintyMeters: Double,
    val confidence: Double,
    val propagated: Boolean,
    val translationApplied: Boolean,
)

data class TemporalTrackUpdate(
    val accepted: Boolean,
    val reason: String,
    val tracks: List<TemporalMetricTrack>,
)

/**
 * Maintains only explicitly confirmed static-world IDs delivered by successful
 * visual keyframes. Pose ticks transform those measured anchors; they never
 * create observations.
 */
class TemporalMetricTrackStore(
    private val capacity: Int = 64,
    private val trackTtlNanos: Long = 1_500_000_000L,
    private val maximumPoseAgeNanos: Long = 150_000_000L,
    private val minimumConfidence: Double = 0.20,
    private val maximumUncertaintyMeters: Double = 3.0,
    private val confidenceDecayPerSecond: Double = 0.35,
    private val orientationOnlyUncertaintyMetersPerSecond: Double = 0.20,
) {
    private val anchors = LinkedHashMap<String, Anchor>()
    private var latestPose: TimestampedPose? = null
    private var lastKeyframeId: Long? = null
    private var lastKeyframeTimestampNanos: Long? = null

    init {
        require(capacity in 1..256)
        require(trackTtlNanos in 1_000_000L..10_000_000_000L)
        require(maximumPoseAgeNanos in 1_000_000L..trackTtlNanos)
        require(minimumConfidence.isFinite() && minimumConfidence in 0.0..1.0)
        require(maximumUncertaintyMeters.isFinite() && maximumUncertaintyMeters > 0.0)
        require(confidenceDecayPerSecond.isFinite() && confidenceDecayPerSecond >= 0.0)
        require(orientationOnlyUncertaintyMetersPerSecond.isFinite() &&
            orientationOnlyUncertaintyMetersPerSecond >= 0.0)
    }

    @Synchronized
    fun updateKeyframe(
        frame: VisionFrame,
        measuredTracks: List<MetricSemanticTrack>,
        pose: TimestampedPose,
    ): TemporalTrackUpdate {
        val previousId = lastKeyframeId
        val previousTimestamp = lastKeyframeTimestampNanos
        if (previousId != null &&
            (frame.frameId <= previousId || frame.captureMonotonicTimestampNanos <= requireNotNull(previousTimestamp))
        ) {
            val currentTimestamp = latestPose?.monotonicTimestampNanos
            return TemporalTrackUpdate(
                false,
                "non_monotonic_keyframe",
                currentTimestamp?.let(::snapshotInternal) ?: emptyList(),
            )
        }
        if (absDifference(frame.captureMonotonicTimestampNanos, pose.monotonicTimestampNanos) > maximumPoseAgeNanos) {
            return TemporalTrackUpdate(false, "pose_too_far_from_keyframe", snapshotAtLatestPose(pose.monotonicTimestampNanos))
        }
        val currentPose = latestPose
        require(measuredTracks.map(MetricSemanticTrack::trackId).toSet().size == measuredTracks.size)
        require(measuredTracks.all {
            it.frameId == frame.frameId &&
                it.sourceCaptureMonotonicTimestampNanos == frame.captureMonotonicTimestampNanos
        })

        lastKeyframeId = frame.frameId
        lastKeyframeTimestampNanos = frame.captureMonotonicTimestampNanos
        if (currentPose == null || pose.monotonicTimestampNanos >= currentPose.monotonicTimestampNanos) {
            latestPose = pose
        }
        measuredTracks.forEach { track ->
            if (!isTemporalAnchorEligible(track)) {
                anchors.remove(track.trackId)
                return@forEach
            }
            val vector = track.cameraVectorMeters ?: run {
                anchors.remove(track.trackId)
                return@forEach
            }
            val sourceUncertainty = track.representativeDistance.uncertaintyMeters
            if (track.confidence < minimumConfidence || sourceUncertainty == null ||
                sourceUncertainty > maximumUncertaintyMeters
            ) {
                anchors.remove(track.trackId)
                return@forEach
            }
            val worldVector = pose.worldFromCamera.rotate(vector)
            anchors[track.trackId] = Anchor(track, pose, worldVector)
        }
        enforceCapacity()
        return TemporalTrackUpdate(
            true,
            "keyframe_updated",
            snapshotInternal(latestPose?.monotonicTimestampNanos ?: pose.monotonicTimestampNanos),
        )
    }

    @Synchronized
    fun updatePose(pose: TimestampedPose): TemporalTrackUpdate {
        val current = latestPose
        if (current != null && pose.monotonicTimestampNanos <= current.monotonicTimestampNanos) {
            return TemporalTrackUpdate(false, "non_monotonic_pose", snapshotInternal(current.monotonicTimestampNanos))
        }
        latestPose = pose
        return TemporalTrackUpdate(true, "pose_updated", snapshotInternal(pose.monotonicTimestampNanos))
    }

    @Synchronized
    fun snapshot(nowNanos: Long): List<TemporalMetricTrack> {
        require(nowNanos >= 0L)
        val pose = latestPose ?: return emptyList()
        if (nowNanos < pose.monotonicTimestampNanos ||
            nowNanos - pose.monotonicTimestampNanos > maximumPoseAgeNanos
        ) {
            return emptyList()
        }
        return snapshotInternal(nowNanos)
    }

    @Synchronized
    fun markOccluded(stableTrackId: String): Boolean = anchors.remove(stableTrackId) != null

    @Synchronized
    fun remove(stableTrackId: String): Boolean = anchors.remove(stableTrackId) != null

    @Synchronized
    fun reset() {
        anchors.clear()
        latestPose = null
        lastKeyframeId = null
        lastKeyframeTimestampNanos = null
    }

    private fun snapshotAtLatestPose(nowNanos: Long): List<TemporalMetricTrack> {
        val pose = latestPose ?: return emptyList()
        return if (nowNanos >= pose.monotonicTimestampNanos &&
            nowNanos - pose.monotonicTimestampNanos <= maximumPoseAgeNanos
        ) {
            snapshotInternal(nowNanos)
        } else {
            emptyList()
        }
    }

    private fun snapshotInternal(nowNanos: Long): List<TemporalMetricTrack> {
        val pose = latestPose ?: return emptyList()
        val expired = mutableListOf<String>()
        val outputs = anchors.values.mapNotNull { anchor ->
            val signedAgeNanos = nowNanos - anchor.track.sourceCaptureMonotonicTimestampNanos
            if (signedAgeNanos > trackTtlNanos) {
                expired += anchor.track.trackId
                return@mapNotNull null
            }
            val ageNanos = signedAgeNanos.coerceAtLeast(0L)
            val ageSeconds = ageNanos / NANOS_PER_SECOND_DOUBLE
            val confidence = anchor.track.confidence * exp(-confidenceDecayPerSecond * ageSeconds)
            val referencePosition = anchor.referencePose.positionEvidence
            val currentPosition = pose.positionEvidence
            val translationApplied = referencePosition != null && currentPosition != null &&
                referencePosition.source == currentPosition.source &&
                referencePosition.coordinateFrameId == currentPosition.coordinateFrameId
            val worldRelativeVector = if (translationApplied) {
                requireNotNull(referencePosition).positionWorldMeters + anchor.worldVector -
                    requireNotNull(currentPosition).positionWorldMeters
            } else {
                anchor.worldVector
            }
            val cameraVector = pose.worldFromCamera.inverse().rotate(worldRelativeVector)
            val positionUncertainty = if (translationApplied) {
                requireNotNull(referencePosition).uncertaintyMeters + requireNotNull(currentPosition).uncertaintyMeters
            } else {
                orientationOnlyUncertaintyMetersPerSecond * ageSeconds
            }
            val uncertainty = requireNotNull(anchor.track.representativeDistance.uncertaintyMeters) +
                positionUncertainty
            if (confidence < minimumConfidence || uncertainty > maximumUncertaintyMeters) {
                expired += anchor.track.trackId
                return@mapNotNull null
            }
            TemporalMetricTrack(
                stableTrackId = anchor.track.trackId,
                classId = anchor.track.classId,
                sourceFrameId = anchor.track.frameId,
                sourceCaptureMonotonicTimestampNanos = anchor.track.sourceCaptureMonotonicTimestampNanos,
                sourceInferenceMonotonicTimestampNanos = anchor.track.sourceInferenceMonotonicTimestampNanos,
                outputMonotonicTimestampNanos = pose.monotonicTimestampNanos,
                cameraVectorMeters = cameraVector,
                distanceMeters = cameraVector.lengthMeters,
                uncertaintyMeters = uncertainty,
                confidence = confidence,
                propagated = pose.monotonicTimestampNanos > anchor.referencePose.monotonicTimestampNanos,
                translationApplied = translationApplied,
            )
        }.sortedBy(TemporalMetricTrack::stableTrackId)
        expired.forEach(anchors::remove)
        return outputs
    }

    private fun enforceCapacity() {
        while (anchors.size > capacity) {
            val oldest = anchors.values.minWithOrNull(
                compareBy<Anchor> { it.track.sourceCaptureMonotonicTimestampNanos }
                    .thenBy { it.track.trackId },
            ) ?: return
            anchors.remove(oldest.track.trackId)
        }
    }

    private fun isTemporalAnchorEligible(track: MetricSemanticTrack): Boolean {
        if (track.temporalMotionEvidence != TemporalMotionEvidence.CONFIRMED_STATIC_WORLD) return false
        return when (BviClassCatalog.find(track.classId)?.group) {
            BviSemanticGroup.PERSON_OR_MOBILITY_AID, BviSemanticGroup.VEHICLE, null -> false
            else -> true
        }
    }

    private data class Anchor(
        val track: MetricSemanticTrack,
        val referencePose: TimestampedPose,
        val worldVector: MetricVector3,
    )

    private companion object {
        const val NANOS_PER_SECOND_DOUBLE = 1_000_000_000.0

        fun absDifference(first: Long, second: Long): Long =
            if (first >= second) first - second else second - first
    }
}

data class TemporalEnvironmentAwareResult(
    val keyframeDecision: VisualKeyframeDecision,
    val stagedResult: EnvironmentAwareMachineVisionResult?,
    val temporalTracks: List<TemporalMetricTrack>,
    val reason: String,
)

/** Connects admitted real frames and successful staged inference to the temporal store. */
class TemporalEnvironmentAwareMachineVisionPipeline(
    private val stagedPipeline: EnvironmentAwareMachineVisionPipeline,
    private val keyframeGate: VisualKeyframeGate = VisualKeyframeGate(),
    private val trackStore: TemporalMetricTrackStore = TemporalMetricTrackStore(),
) {
    fun processFrame(
        frame: VisionFrame,
        pose: TimestampedPose,
        signal: VisualKeyframeSignal,
        nowNanos: Long,
        bothDepthProfilesAvailable: Boolean,
        dedicatedVisualSignal: EnvironmentSignal? = null,
    ): TemporalEnvironmentAwareResult = processFrameInternal(frame, pose, signal, nowNanos) {
        stagedPipeline.process(
            frame,
            nowNanos,
            bothDepthProfilesAvailable,
            dedicatedVisualSignal,
        )
    }

    fun processFrame(
        frame: VisionFrame,
        pose: TimestampedPose,
        signal: VisualKeyframeSignal,
        nowNanos: Long,
        depthRoutingRequest: DepthModelRoutingRequest,
        availableDepthProfileIds: Set<String>,
        dedicatedVisualSignal: EnvironmentSignal? = null,
    ): TemporalEnvironmentAwareResult = processFrameInternal(frame, pose, signal, nowNanos) {
        stagedPipeline.process(
            frame,
            nowNanos,
            depthRoutingRequest,
            availableDepthProfileIds,
            dedicatedVisualSignal,
        )
    }

    private fun processFrameInternal(
        frame: VisionFrame,
        pose: TimestampedPose,
        signal: VisualKeyframeSignal,
        nowNanos: Long,
        runStagedPipeline: () -> EnvironmentAwareMachineVisionResult,
    ): TemporalEnvironmentAwareResult {
        val poseUpdate = trackStore.updatePose(pose)
        val decision = keyframeGate.evaluate(frame, signal)
        if (!decision.accepted) {
            return TemporalEnvironmentAwareResult(decision, null, poseUpdate.tracks, decision.reason)
        }
        val staged = runStagedPipeline()
        val perception = staged.perception
        if (staged.reason != "processed" || perception == null) {
            return TemporalEnvironmentAwareResult(decision, staged, poseUpdate.tracks, staged.reason)
        }
        val update = trackStore.updateKeyframe(frame, perception.tracks, pose)
        return TemporalEnvironmentAwareResult(decision, staged, update.tracks, update.reason)
    }

    fun updatePose(pose: TimestampedPose): TemporalTrackUpdate = trackStore.updatePose(pose)

    fun markOccluded(stableTrackId: String): Boolean = trackStore.markOccluded(stableTrackId)

    fun remove(stableTrackId: String): Boolean = trackStore.remove(stableTrackId)

    fun reset() {
        keyframeGate.reset()
        trackStore.reset()
    }
}
