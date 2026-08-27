// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.sqrt
import org.conceptflow.mpl.v1.CoordinateFrame
import org.conceptflow.mpl.v1.ImuReading

enum class HeadCameraExtrinsicProvenance {
    CAMERA2_SENSOR_COORDINATES,
    GUIDED_HAND_EYE,
}

/** A verified rotation mapping camera-frame vectors into the rigid glasses/head sensor frame. */
data class VerifiedHeadCameraExtrinsic(
    val headFromCameraRotation: UnitQuaternion,
    val verificationFingerprint: String,
    val provenance: HeadCameraExtrinsicProvenance = HeadCameraExtrinsicProvenance.GUIDED_HAND_EYE,
    val headFromCameraTranslationMeters: MetricVector3? = null,
    val rotationUncertaintyDegrees: Double? = null,
    val translationUncertaintyMeters: Double? = null,
) {
    init {
        require(SHA256.matches(verificationFingerprint))
        require(rotationUncertaintyDegrees == null ||
            rotationUncertaintyDegrees.isFinite() && rotationUncertaintyDegrees >= 0.0)
        require((headFromCameraTranslationMeters == null) == (translationUncertaintyMeters == null))
        require(translationUncertaintyMeters == null ||
            translationUncertaintyMeters.isFinite() && translationUncertaintyMeters >= 0.0)
    }

    private companion object {
        val SHA256 = Regex("[a-f0-9]{64}")
    }
}

data class HeadPoseObservation(
    val hostMonotonicTimestampNanos: Long,
    val worldFromHead: UnitQuaternion,
    val orientationAccuracy: Int,
) {
    init {
        require(hostMonotonicTimestampNanos >= 0L)
        require(orientationAccuracy in 1..3)
    }
}

data class HeadPoseIngressResult(
    val accepted: Boolean,
    val reason: String,
    val temporalTrackCount: Int,
    val cameraPose: TimestampedPose? = null,
)

/** Converts only normalized HEAD orientation; accelerometer and pose translation are intentionally unused. */
object LiveImuPoseMapper {
    fun map(reading: ImuReading, hostMonotonicTimestampNanos: Long): HeadPoseObservation? {
        if (!reading.hasPose() || reading.pose.referenceFrame != CoordinateFrame.COORDINATE_FRAME_HEAD) return null
        if (reading.orientationAccuracy !in 1..3 || hostMonotonicTimestampNanos < 0L) return null
        val source = reading.pose.rotation
        val components = doubleArrayOf(source.w, source.x, source.y, source.z)
        if (components.any { !it.isFinite() }) return null
        val magnitude = sqrt(components.sumOf { it * it })
        if (!magnitude.isFinite() || magnitude <= 0.0 || abs(magnitude - 1.0) > MAXIMUM_NORM_ERROR) return null
        return HeadPoseObservation(
            hostMonotonicTimestampNanos,
            UnitQuaternion(
                components[0] / magnitude,
                components[1] / magnitude,
                components[2] / magnitude,
                components[3] / magnitude,
            ),
            reading.orientationAccuracy,
        )
    }

    private const val MAXIMUM_NORM_ERROR = 0.02
}

/** Small monotonic pose history used only for capture-time orientation correlation. */
class BoundedHeadPoseBuffer(
    private val capacity: Int = 256,
    private val retentionNanos: Long = 3_000_000_000L,
    private val maximumCorrelationAgeNanos: Long = 150_000_000L,
) {
    private val samples = ArrayDeque<HeadPoseObservation>()

    init {
        require(capacity in 2..2_048)
        require(retentionNanos in 150_000_000L..10_000_000_000L)
        require(maximumCorrelationAgeNanos in 1_000_000L..retentionNanos)
    }

    @Synchronized
    fun add(sample: HeadPoseObservation): Boolean {
        if (samples.peekLast()?.hostMonotonicTimestampNanos?.let {
                sample.hostMonotonicTimestampNanos <= it
            } == true
        ) return false
        samples.addLast(sample)
        while (true) {
            val oldest = samples.peekFirst() ?: break
            if (samples.size <= capacity &&
                sample.hostMonotonicTimestampNanos - oldest.hostMonotonicTimestampNanos <= retentionNanos
            ) break
            samples.removeFirst()
        }
        return true
    }

    @Synchronized
    fun cameraPoseAt(
        targetTimestampNanos: Long,
        extrinsic: VerifiedHeadCameraExtrinsic,
    ): TimestampedPose? {
        require(targetTimestampNanos >= 0L)
        var before: HeadPoseObservation? = null
        var after: HeadPoseObservation? = null
        for (sample in samples) {
            if (sample.hostMonotonicTimestampNanos <= targetTimestampNanos) before = sample
            if (sample.hostMonotonicTimestampNanos >= targetTimestampNanos) {
                after = sample
                break
            }
        }
        val head = when {
            before != null && after != null && before !== after -> {
                if (targetTimestampNanos - before.hostMonotonicTimestampNanos > maximumCorrelationAgeNanos ||
                    after.hostMonotonicTimestampNanos - targetTimestampNanos > maximumCorrelationAgeNanos
                ) return null
                val span = after.hostMonotonicTimestampNanos - before.hostMonotonicTimestampNanos
                val fraction = (targetTimestampNanos - before.hostMonotonicTimestampNanos).toDouble() / span
                interpolate(before.worldFromHead, after.worldFromHead, fraction)
            }
            before != null && targetTimestampNanos - before.hostMonotonicTimestampNanos <= maximumCorrelationAgeNanos -> {
                before.worldFromHead
            }
            after != null && after.hostMonotonicTimestampNanos - targetTimestampNanos <= maximumCorrelationAgeNanos -> {
                after.worldFromHead
            }
            else -> return null
        }
        return TimestampedPose(
            targetTimestampNanos,
            multiply(head, extrinsic.headFromCameraRotation),
            positionEvidence = null,
        )
    }

    @Synchronized
    fun latestCameraPose(extrinsic: VerifiedHeadCameraExtrinsic): TimestampedPose? {
        val latest = samples.peekLast() ?: return null
        return TimestampedPose(
            latest.hostMonotonicTimestampNanos,
            multiply(latest.worldFromHead, extrinsic.headFromCameraRotation),
            positionEvidence = null,
        )
    }

    @Synchronized
    fun reset() = samples.clear()

    private fun interpolate(first: UnitQuaternion, second: UnitQuaternion, fraction: Double): UnitQuaternion {
        require(fraction in 0.0..1.0)
        val dot = first.w * second.w + first.x * second.x + first.y * second.y + first.z * second.z
        val sign = if (dot < 0.0) -1.0 else 1.0
        val w = first.w * (1.0 - fraction) + second.w * sign * fraction
        val x = first.x * (1.0 - fraction) + second.x * sign * fraction
        val y = first.y * (1.0 - fraction) + second.y * sign * fraction
        val z = first.z * (1.0 - fraction) + second.z * sign * fraction
        val magnitude = sqrt(w * w + x * x + y * y + z * z)
        return UnitQuaternion(w / magnitude, x / magnitude, y / magnitude, z / magnitude)
    }

    private fun multiply(first: UnitQuaternion, second: UnitQuaternion): UnitQuaternion {
        val w = first.w * second.w - first.x * second.x - first.y * second.y - first.z * second.z
        val x = first.w * second.x + first.x * second.w + first.y * second.z - first.z * second.y
        val y = first.w * second.y - first.x * second.z + first.y * second.w + first.z * second.x
        val z = first.w * second.z + first.x * second.y - first.y * second.x + first.z * second.w
        val magnitude = sqrt(w * w + x * x + y * y + z * z)
        return UnitQuaternion(w / magnitude, x / magnitude, y / magnitude, z / magnitude)
    }
}

enum class LiveMetricFusionReason {
    METRIC_TRACKS_READY,
    METRIC_TRACKS_READY_PROPAGATION_INTRINSICS_UNQUANTIFIED,
    CAMERA_METRIC_TRACKS_READY_PROPAGATION_INTRINSICS_MISSING,
    CAMERA_METRIC_TRACKS_READY_PROPAGATION_INTRINSICS_UNQUANTIFIED,
    CAMERA_METRIC_TRACKS_READY_PROPAGATION_EXTRINSIC_MISSING,
    CAMERA_METRIC_TRACKS_READY_PROPAGATION_POSE_MISSING_OR_STALE,
    PROFILE_BOUND_METRIC_SEMANTICS_MISSING,
    METRIC_DEPTH_INPUT_INVALID,
    INFERENCE_REJECTED,
}

data class LiveMetricFusionResult(
    val reason: LiveMetricFusionReason,
    val detailCode: String,
    val relativeTrackCount: Int,
    val metricTracks: List<MetricSemanticTrack>,
    val temporalTracks: List<TemporalMetricTrack>,
    val metricProvenance: MetricDepthProvenance? = null,
    val capturePose: TimestampedPose? = null,
) {
    val metricTrackCount: Int get() = metricTracks.size
    val propagatedTrackCount: Int get() = temporalTracks.count(TemporalMetricTrack::propagated)
    val cameraMetricOutputAvailable: Boolean get() = metricProvenance != null
}

/**
 * Bridges already-executed QNN products into the pure metric and temporal layers. Scalar metric
 * depth requires exact profile-bound semantics. Intrinsics add a camera ray/vector; head/world
 * propagation separately requires that vector, verified extrinsics, and capture-correlated pose.
 */
class LiveMetricTemporalFusion(
    private val calibrationProvider: MetricDepthCalibrationProvider,
    private val initialHeadCameraExtrinsic: VerifiedHeadCameraExtrinsic?,
    private val nativeMetricSemanticsProvider: NativeMetricDepthSemanticsProvider =
        OfficialDepthAnythingV2MetricSemanticsProvider,
    private val poseBuffer: BoundedHeadPoseBuffer = BoundedHeadPoseBuffer(),
    private val trackStore: TemporalMetricTrackStore = TemporalMetricTrackStore(),
    private val maximumSemanticAgeNanos: Long = 2_000_000_000L,
) {
    private var activeHeadCameraExtrinsic = initialHeadCameraExtrinsic
    init {
        require(maximumSemanticAgeNanos in 1_000_000L..5_000_000_000L)
    }

    @Synchronized
    fun acceptPose(sample: HeadPoseObservation): HeadPoseIngressResult {
        if (!poseBuffer.add(sample)) return HeadPoseIngressResult(false, "non_monotonic_pose", 0)
        val extrinsic = activeHeadCameraExtrinsic
            ?: return HeadPoseIngressResult(true, "head_camera_extrinsic_missing", 0)
        val pose = requireNotNull(poseBuffer.latestCameraPose(extrinsic))
        val update = trackStore.updatePose(pose)
        return HeadPoseIngressResult(update.accepted, update.reason, update.tracks.size, pose)
    }

    @Synchronized
    fun process(
        frame: VisionFrame,
        result: QnnLiveFrameResult,
        nowNanos: Long,
        frameHeadCameraExtrinsic: VerifiedHeadCameraExtrinsic? = null,
    ): LiveMetricFusionResult {
        require(nowNanos >= frame.captureMonotonicTimestampNanos)
        val relativeCount = result.inference.observations.size
        if (result.frameId != frame.frameId || result.inference.frameId != frame.frameId ||
            result.inference.depthProfileId != result.selectedDepthProfileId
        ) return unavailable(LiveMetricFusionReason.INFERENCE_REJECTED, "frame_or_profile_mismatch", relativeCount)
        if (frameHeadCameraExtrinsic != null) {
            val current = activeHeadCameraExtrinsic
            if (current != null && current.verificationFingerprint != frameHeadCameraExtrinsic.verificationFingerprint) {
                return unavailable(
                    LiveMetricFusionReason.INFERENCE_REJECTED,
                    "head_camera_extrinsic_changed_within_session",
                    relativeCount,
                )
            }
            activeHeadCameraExtrinsic = frameHeadCameraExtrinsic
        }
        val profile = MachineVisionModelProfiles.allProfiles.singleOrNull {
            it.id == result.selectedDepthProfileId && it.kind == MachineVisionModelKind.METRIC_DEPTH
        } ?: return unavailable(LiveMetricFusionReason.INFERENCE_REJECTED, "unknown_depth_profile", relativeCount)
        val intrinsics = frame.cameraIntrinsics
        val guidedCalibration = intrinsics?.let { cameraIntrinsics ->
            val expectedBinding = MetricDepthCalibrationBinding.forProfile(profile, cameraIntrinsics)
            calibrationProvider.resolve(profile, cameraIntrinsics)?.takeIf {
                it.binding == expectedBinding &&
                    it.provenance.kind == MetricDepthProvenanceKind.GUIDED_TWO_ANCHOR
            }
        }
        val calibration = guidedCalibration ?: nativeMetricSemanticsProvider.resolve(profile)
            ?: return unavailable(
            LiveMetricFusionReason.PROFILE_BOUND_METRIC_SEMANTICS_MISSING,
            "profile_bound_metric_semantics_missing",
            relativeCount,
        )
        if (calibration.provenance.kind == MetricDepthProvenanceKind.PINNED_OFFICIAL_NATIVE_METRIC &&
            result.inference.observations.any { observation ->
                observation.relativeDepthSamples.any { !calibration.acceptsRawDepthValue(it) }
            }
        ) return unavailable(
            LiveMetricFusionReason.METRIC_DEPTH_INPUT_INVALID,
            "native_metric_depth_out_of_range",
            relativeCount,
        )
        val perception = MachineVisionPipeline(
            MachineVisionInferenceAdapter { _, _ -> result.inference },
            calibration,
            maximumResultAgeNanos = maximumSemanticAgeNanos,
            calibrationBindingPolicy = CalibrationBindingPolicy.REQUIRE_BOUND,
        ).process(frame, profile, nowNanos)
        if (perception.reason != "processed") return unavailable(
            LiveMetricFusionReason.INFERENCE_REJECTED,
            perception.reason,
            relativeCount,
        )
        if (intrinsics == null) return cameraMetricOnly(
            LiveMetricFusionReason.CAMERA_METRIC_TRACKS_READY_PROPAGATION_INTRINSICS_MISSING,
            "scalar_metric_ready_camera_ray_intrinsics_missing",
            relativeCount,
            perception.tracks,
            calibration.provenance,
        )
        val intrinsicsUnquantified =
            intrinsics.source == CameraIntrinsicsSource.DERIVED && intrinsics.standardDeviation == null
        val extrinsic = activeHeadCameraExtrinsic ?: return cameraMetricOnly(
            LiveMetricFusionReason.CAMERA_METRIC_TRACKS_READY_PROPAGATION_EXTRINSIC_MISSING,
            "camera_metric_ready_head_camera_extrinsic_missing",
            relativeCount,
            perception.tracks,
            calibration.provenance,
        )
        val capturePose = poseBuffer.cameraPoseAt(frame.captureMonotonicTimestampNanos, extrinsic)
            ?: return cameraMetricOnly(
                LiveMetricFusionReason.CAMERA_METRIC_TRACKS_READY_PROPAGATION_POSE_MISSING_OR_STALE,
                "camera_metric_ready_capture_pose_missing_or_stale",
                relativeCount,
                perception.tracks,
                calibration.provenance,
            )
        val temporal = trackStore.updateKeyframe(frame, perception.tracks, capturePose)
        if (!temporal.accepted) return LiveMetricFusionResult(
            LiveMetricFusionReason.INFERENCE_REJECTED,
            temporal.reason,
            relativeCount,
            perception.tracks,
            emptyList(),
            calibration.provenance,
            capturePose,
        )
        return LiveMetricFusionResult(
            if (intrinsicsUnquantified) {
                LiveMetricFusionReason.METRIC_TRACKS_READY_PROPAGATION_INTRINSICS_UNQUANTIFIED
            } else {
                LiveMetricFusionReason.METRIC_TRACKS_READY
            },
            if (intrinsicsUnquantified) {
                "${temporal.reason}_derived_intrinsics_uncertainty_unreported"
            } else {
                temporal.reason
            },
            relativeCount,
            perception.tracks,
            temporal.tracks,
            calibration.provenance,
            capturePose,
        )
    }

    @Synchronized
    fun reset() {
        poseBuffer.reset()
        trackStore.reset()
        activeHeadCameraExtrinsic = initialHeadCameraExtrinsic
    }

    private fun unavailable(
        reason: LiveMetricFusionReason,
        detail: String,
        relativeCount: Int,
    ) = LiveMetricFusionResult(reason, detail, relativeCount, emptyList(), emptyList())

    private fun cameraMetricOnly(
        reason: LiveMetricFusionReason,
        detail: String,
        relativeCount: Int,
        tracks: List<MetricSemanticTrack>,
        provenance: MetricDepthProvenance,
    ) = LiveMetricFusionResult(reason, detail, relativeCount, tracks, emptyList(), provenance)
}
