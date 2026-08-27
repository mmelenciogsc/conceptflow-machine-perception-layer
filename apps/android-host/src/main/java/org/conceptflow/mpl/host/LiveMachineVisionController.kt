// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.conceptflow.mpl.host.core.ElapsedHostClock
import org.conceptflow.mpl.host.core.GlassesStreamIngress
import org.conceptflow.mpl.host.core.StreamIngressDisposition
import org.conceptflow.mpl.host.focus.BeaconHeadOrientation
import org.conceptflow.mpl.host.focus.DisabledSpatialFocusTouchAdmission
import org.conceptflow.mpl.host.focus.FocusedVqaCorrelation
import org.conceptflow.mpl.host.focus.FocusedVqaRejection
import org.conceptflow.mpl.host.focus.ReplaceableFocusedVqaGateway
import org.conceptflow.mpl.host.focus.SpatialFocusCommand
import org.conceptflow.mpl.host.focus.SpatialFocusDwell
import org.conceptflow.mpl.host.focus.SpatialFocusManager
import org.conceptflow.mpl.host.focus.SpatialFocusState
import org.conceptflow.mpl.host.focus.SpatialFocusTouchAdmission
import org.conceptflow.mpl.host.realtime.SensorTimeline
import org.conceptflow.mpl.host.realtime.AndroidPerceptionBridge
import org.conceptflow.mpl.host.realtime.PerceptionBus
import org.conceptflow.mpl.host.realtime.PerceptionValidityReason
import org.conceptflow.mpl.host.vision.AndroidJpegDecoder
import org.conceptflow.mpl.host.vision.CameraIntrinsics
import org.conceptflow.mpl.host.vision.CameraIntrinsicsSource
import org.conceptflow.mpl.host.vision.CameraIntrinsicsStandardDeviation
import org.conceptflow.mpl.host.vision.CameraLensDistortionModel
import org.conceptflow.mpl.host.vision.EncodedJpegFrame
import org.conceptflow.mpl.host.vision.EnvironmentDepthCoordinator
import org.conceptflow.mpl.host.vision.EnvironmentSelectionMode
import org.conceptflow.mpl.host.vision.AndroidLocalVlmEnvironmentClient
import org.conceptflow.mpl.host.vision.AndroidLocalVlmFocusedVqaGateway
import org.conceptflow.mpl.host.vision.AndroidFocusedVqaJpegEncoder
import org.conceptflow.mpl.host.vision.BoundedFocusedVqaFrameStore
import org.conceptflow.mpl.host.vision.StoredFocusedVqaFrameProvider
import org.conceptflow.mpl.host.vision.GnssQualitySample
import org.conceptflow.mpl.host.vision.HeadCameraExtrinsicProvenance
import org.conceptflow.mpl.host.vision.HtpLeaseAcquisition
import org.conceptflow.mpl.host.vision.HtpLeaseTelemetry
import org.conceptflow.mpl.host.vision.HtpLeaseWorkload
import org.conceptflow.mpl.host.vision.HtpExecutionLease
import org.conceptflow.mpl.host.vision.LiveMetricCalibrationState
import org.conceptflow.mpl.host.vision.LiveMetricFusionReason
import org.conceptflow.mpl.host.vision.LiveMetricFusionResult
import org.conceptflow.mpl.host.vision.LiveMetricTemporalFusion
import org.conceptflow.mpl.host.vision.LiveVlmHtpAdmissionGate
import org.conceptflow.mpl.host.vision.LiveVlmQnnAdmissionDecision
import org.conceptflow.mpl.host.vision.LocalVlmFocusedObjectCorrelation
import org.conceptflow.mpl.host.vision.LocalVlmFocusedObjectFailure
import org.conceptflow.mpl.host.vision.LocalVlmFocusedObjectOutcome
import org.conceptflow.mpl.host.vision.LiveImuPoseMapper
import org.conceptflow.mpl.host.vision.LightweightTrackMaintainer
import org.conceptflow.mpl.host.vision.LightweightTrackState
import org.conceptflow.mpl.host.vision.LiveSemanticDepthAdmissionPolicy
import org.conceptflow.mpl.host.vision.MachineVisionModelProfiles
import org.conceptflow.mpl.host.vision.MetricDepthCalibrationProvider
import org.conceptflow.mpl.host.vision.MetricDepthProvenanceKind
import org.conceptflow.mpl.host.vision.NativeQnnModelSessionFactory
import org.conceptflow.mpl.host.vision.QnnFailureCode
import org.conceptflow.mpl.host.vision.QnnInferenceException
import org.conceptflow.mpl.host.vision.QnnLiveFrameExecutor
import org.conceptflow.mpl.host.vision.QnnLiveFrameResult
import org.conceptflow.mpl.host.vision.QnnRuntimeBundle
import org.conceptflow.mpl.host.vision.RawRgbFrame
import org.conceptflow.mpl.host.vision.SemanticDepthCadenceTier
import org.conceptflow.mpl.host.vision.SemanticDepthRefreshReason
import org.conceptflow.mpl.host.vision.TrackEstimateValidity
import org.conceptflow.mpl.host.vision.VisionFrame
import org.conceptflow.mpl.host.vision.VerifiedHeadCameraExtrinsic
import org.conceptflow.mpl.transport.LiveLinkDisconnectReason
import org.conceptflow.mpl.transport.LiveLinkCloseEvidence
import org.conceptflow.mpl.transport.LiveLinkDiagnosticCode
import org.conceptflow.mpl.transport.LiveLinkEndpointRole
import org.conceptflow.mpl.transport.LiveLinkNetworkTopology
import org.conceptflow.mpl.transport.LiveLinkPrivateConfig
import org.conceptflow.mpl.transport.LIVE_LINK_DIAGNOSTIC_SCHEMA_VERSION
import org.conceptflow.mpl.transport.LiveLinkSession
import org.conceptflow.mpl.transport.LiveSessionBinding
import org.conceptflow.mpl.transport.LiveMicrophoneLeaseState
import org.conceptflow.mpl.transport.LiveSensorDelivery
import org.conceptflow.mpl.transport.MicrophoneRequestDispatch
import org.conceptflow.mpl.transport.PocoLiveLinkObserver
import org.conceptflow.mpl.transport.PocoLiveLinkServer
import org.conceptflow.mpl.transport.AndroidWifiDirectEndpointResolver
import org.conceptflow.mpl.transport.WifiDirectNodeRole
import org.conceptflow.mpl.transport.RokidNodeCommandDelivery
import org.conceptflow.mpl.transport.RokidNodeCommandDispatch
import org.conceptflow.mpl.v1.CameraIntrinsicsProvenance
import org.conceptflow.mpl.v1.CameraExtrinsicProvenance
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.ImageEncoding
import org.conceptflow.mpl.v1.LiveLinkTelemetry
import org.conceptflow.mpl.v1.RokidNodeCommandOperation

internal fun LocalVlmFocusedObjectFailure.toFocusRejection(): FocusedVqaRejection = when (this) {
    LocalVlmFocusedObjectFailure.BUSY -> FocusedVqaRejection.BUSY
    LocalVlmFocusedObjectFailure.INVALID_REQUEST -> FocusedVqaRejection.INVALID_REQUEST
    LocalVlmFocusedObjectFailure.STALE_OR_MISMATCHED -> FocusedVqaRejection.STALE_FRAME
    LocalVlmFocusedObjectFailure.DEFERRED_FOR_QNN,
    LocalVlmFocusedObjectFailure.INFERENCE_FAILED,
    LocalVlmFocusedObjectFailure.UNAVAILABLE,
    -> FocusedVqaRejection.UNAVAILABLE
}

internal fun SpatialFocusState.matchesFocusedVqaSource(
    correlation: LocalVlmFocusedObjectCorrelation,
): Boolean = target?.let { focused ->
    sessionGeneration == correlation.sessionGeneration &&
        snapshotId == correlation.snapshotId &&
        focusGeneration == correlation.focusGeneration &&
        focused.stableTrackId == correlation.stableTrackId &&
        focused.sourceFrameId == correlation.sourceFrameId &&
        focused.sourceCaptureTimestampNanos == correlation.sourceCaptureTimestampNanos
} == true

/** Maps only explicitly calibrated or derived protocol intrinsics without upgrading provenance. */
internal fun validatedCameraIntrinsics(frame: FramePayload): CameraIntrinsics? {
    if (!frame.hasIntrinsics()) return null
    val value = frame.intrinsics
    val source = when (value.provenance) {
        CameraIntrinsicsProvenance.CAMERA_INTRINSICS_PROVENANCE_CALIBRATED ->
            CameraIntrinsicsSource.CALIBRATED
        CameraIntrinsicsProvenance.CAMERA_INTRINSICS_PROVENANCE_DERIVED ->
            CameraIntrinsicsSource.DERIVED
        else -> return null
    }
    val valid = value.calibratedWidth == frame.image.width && value.calibratedHeight == frame.image.height &&
        value.focalXPixels.isFinite() && value.focalXPixels > 0.0 &&
        value.focalYPixels.isFinite() && value.focalYPixels > 0.0 &&
        value.principalXPixels.isFinite() && value.principalXPixels in 0.0..<frame.image.width.toDouble() &&
        value.principalYPixels.isFinite() && value.principalYPixels in 0.0..<frame.image.height.toDouble() &&
        value.distortionCoefficientsList.isSupportedZeroBrownConradyDistortion()
    if (!valid) return null

    val standardDeviation = if (value.hasUncertainty()) {
        val uncertainty = value.uncertainty
        val uncertaintyValid = uncertainty.focalXStddevPixels.isFinite() &&
            uncertainty.focalXStddevPixels >= 0.0 &&
            uncertainty.focalYStddevPixels.isFinite() && uncertainty.focalYStddevPixels >= 0.0 &&
            uncertainty.principalXStddevPixels.isFinite() && uncertainty.principalXStddevPixels >= 0.0 &&
            uncertainty.principalYStddevPixels.isFinite() && uncertainty.principalYStddevPixels >= 0.0
        if (!uncertaintyValid) return null
        CameraIntrinsicsStandardDeviation(
            uncertainty.focalXStddevPixels,
            uncertainty.focalYStddevPixels,
            uncertainty.principalXStddevPixels,
            uncertainty.principalYStddevPixels,
        )
    } else {
        null
    }
    return CameraIntrinsics(
        frame.image.width,
        frame.image.height,
        value.focalXPixels,
        value.focalYPixels,
        value.principalXPixels,
        value.principalYPixels,
        source,
        standardDeviation,
        CameraLensDistortionModel.BROWN_CONRADY_ZERO,
    )
}

/** Accepts only a typed, digest-bound camera-to-head rotation; unknown pose origins fail closed. */
internal fun validatedHeadCameraExtrinsic(frame: FramePayload): VerifiedHeadCameraExtrinsic? {
    if (!frame.hasIntrinsics() || !frame.intrinsics.hasHeadFromCameraExtrinsic()) return null
    val value = frame.intrinsics.headFromCameraExtrinsic
    val provenance = when (value.provenance) {
        CameraExtrinsicProvenance.CAMERA_EXTRINSIC_PROVENANCE_CAMERA2_SENSOR_COORDINATES ->
            HeadCameraExtrinsicProvenance.CAMERA2_SENSOR_COORDINATES
        CameraExtrinsicProvenance.CAMERA_EXTRINSIC_PROVENANCE_GUIDED_HAND_EYE ->
            HeadCameraExtrinsicProvenance.GUIDED_HAND_EYE
        else -> return null
    }
    if (!value.hasHeadFromCameraRotation() || value.verificationSha256.size() != SHA256_BYTES) return null
    val rotation = value.headFromCameraRotation
    val components = doubleArrayOf(rotation.w, rotation.x, rotation.y, rotation.z)
    if (components.any { !it.isFinite() }) return null
    val norm = kotlin.math.sqrt(components.sumOf { it * it })
    if (!norm.isFinite() || norm <= 0.0 || kotlin.math.abs(norm - 1.0) > MAXIMUM_QUATERNION_NORM_ERROR) {
        return null
    }
    val rotationUncertainty = if (value.hasRotationUncertaintyDegrees()) {
        value.rotationUncertaintyDegrees.takeIf { it.isFinite() && it >= 0.0 } ?: return null
    } else null
    val translation = if (value.translationAvailable) {
        if (!value.hasHeadFromCameraTranslationMeters() || !value.hasTranslationUncertaintyMeters()) return null
        val vector = value.headFromCameraTranslationMeters
        val uncertainty = value.translationUncertaintyMeters
        if (!listOf(vector.x, vector.y, vector.z, uncertainty).all(Double::isFinite) || uncertainty < 0.0) {
            return null
        }
        org.conceptflow.mpl.host.vision.MetricVector3(vector.x, vector.y, vector.z) to uncertainty
    } else {
        if (value.hasTranslationUncertaintyMeters()) return null
        null
    }
    return VerifiedHeadCameraExtrinsic(
        headFromCameraRotation = org.conceptflow.mpl.host.vision.UnitQuaternion(
            components[0] / norm,
            components[1] / norm,
            components[2] / norm,
            components[3] / norm,
        ),
        verificationFingerprint = value.verificationSha256.toByteArray()
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) },
        provenance = provenance,
        headFromCameraTranslationMeters = translation?.first,
        rotationUncertaintyDegrees = rotationUncertainty,
        translationUncertaintyMeters = translation?.second,
    )
}

/** The host pinhole projector must never silently discard a non-identity lens model. */
private fun List<Double>.isSupportedZeroBrownConradyDistortion(): Boolean =
    size == BROWN_CONRADY_PARAMETER_COUNT && all { it.isFinite() && it == 0.0 }

private const val BROWN_CONRADY_PARAMETER_COUNT = 5
private const val SHA256_BYTES = 32
private const val MAXIMUM_QUATERNION_NORM_ERROR = 0.02
private const val ACTIVE_SESSION_DURATION_SECONDS = 30
private const val RENDEZVOUS_TIMEOUT_SECONDS = 90L

enum class LiveMachineVisionPhase { IDLE, OPENING_QNN_HTP, LISTENING, STREAMING, COMPLETE, FAILED, STOPPED }

enum class LiveMachineVisionRunMode { BOUNDED_DIAGNOSTIC, PERSISTENT_NODE }

enum class LiveMicrophonePhase { IDLE, REQUESTING, ACTIVE, COMPLETE, REJECTED }

enum class LiveRokidNodeCommandPhase { IDLE, REQUESTED, ACCEPTED, REJECTED }

internal fun liveMicrophoneControlEnabled(
    phase: LiveMachineVisionPhase,
    microphonePhase: LiveMicrophonePhase,
): Boolean = phase == LiveMachineVisionPhase.STREAMING &&
    microphonePhase != LiveMicrophonePhase.REQUESTING &&
    microphonePhase != LiveMicrophonePhase.ACTIVE

internal fun accessibleLiveMachineVisionPhase(phase: LiveMachineVisionPhase): String = when (phase) {
    LiveMachineVisionPhase.IDLE -> "Stopped"
    LiveMachineVisionPhase.OPENING_QNN_HTP -> "Preparing models"
    LiveMachineVisionPhase.LISTENING -> "Waiting for glasses"
    LiveMachineVisionPhase.STREAMING -> "Capturing"
    LiveMachineVisionPhase.COMPLETE -> "Stopped after completion"
    LiveMachineVisionPhase.FAILED -> "Stopped after failure"
    LiveMachineVisionPhase.STOPPED -> "Stopped"
}

internal fun accessibleNodeCommandOperation(operation: RokidNodeCommandOperation): String = when (operation) {
    RokidNodeCommandOperation.ROKID_NODE_COMMAND_OPERATION_ACTIVATE_NODE -> "activate"
    RokidNodeCommandOperation.ROKID_NODE_COMMAND_OPERATION_SLEEP_NODE -> "sleep"
    RokidNodeCommandOperation.ROKID_NODE_COMMAND_OPERATION_PLAY_BRAND_SEQUENCE -> "play brand sequence"
    else -> "unknown"
}

data class LiveMachineVisionTestSpec(
    val environmentMode: EnvironmentSelectionMode = EnvironmentSelectionMode.AUTOMATIC,
    val durationSeconds: Int = 30,
    val maximumFrames: Int = 150,
    val runMode: LiveMachineVisionRunMode = LiveMachineVisionRunMode.BOUNDED_DIAGNOSTIC,
) {
    init {
        require(durationSeconds == ACTIVE_SESSION_DURATION_SECONDS) {
            "durationSeconds must preserve the fixed 30-second active capture bound"
        }
        require(maximumFrames in 1..1_500) { "maximumFrames is outside its bound" }
    }
}

internal sealed interface LiveModelFrameDisposition {
    data class AdmitForHtp(
        val frame: VisionFrame,
        val allowOpportunisticVlm: Boolean,
        val cadenceTier: SemanticDepthCadenceTier,
        val reason: SemanticDepthRefreshReason,
    ) : LiveModelFrameDisposition

    data class PredictionOnly(val reason: SemanticDepthRefreshReason) : LiveModelFrameDisposition
}

/** Pure controller boundary: only [LiveModelFrameDisposition.AdmitForHtp] may reach QNN. */
internal class LiveHtpFrameAdmissionGate(
    private val policy: LiveSemanticDepthAdmissionPolicy = LiveSemanticDepthAdmissionPolicy(),
) {
    fun evaluate(
        frame: VisionFrame,
        maintainedTracks: List<LightweightTrackState>,
        requestOpportunisticVlm: Boolean,
        vlmCapacityAvailable: Boolean,
        suppressCameraCadenceMotionForVlmBootstrap: Boolean = false,
    ): LiveModelFrameDisposition {
        val decision = policy.evaluate(
            frame,
            maintainedTracks,
            requestOpportunisticVlm,
            vlmCapacityAvailable,
            suppressCameraCadenceMotionForVlmBootstrap,
        )
        val admitted = decision.frame
        return if (admitted == null) {
            LiveModelFrameDisposition.PredictionOnly(decision.reason)
        } else {
            LiveModelFrameDisposition.AdmitForHtp(
                admitted,
                decision.opportunisticVlmAllowed,
                decision.cadenceTier,
                decision.reason,
            )
        }
    }

    fun reset() = policy.reset()
}

internal sealed interface PreparedHtpDispatchOutcome<out Prepared, out Result> {
    data class Completed<Prepared, Result>(
        val prepared: Prepared,
        val result: Result,
    ) : PreparedHtpDispatchOutcome<Prepared, Result>

    data class Refused(
        val refusal: HtpLeaseAcquisition.Refused,
    ) : PreparedHtpDispatchOutcome<Nothing, Nothing>
}

/** Preparation happens before HTP ownership; dispatch exceptions still release ownership. */
internal fun <Prepared, Result> prepareThenDispatchHtp(
    lease: HtpExecutionLease,
    timeoutMillis: Long,
    cancelled: () -> Boolean,
    prepare: () -> Prepared,
    dispatch: (Prepared) -> Result,
): PreparedHtpDispatchOutcome<Prepared, Result> {
    val prepared = prepare()
    return when (val acquisition = lease.tryAcquire(HtpLeaseWorkload.QNN, timeoutMillis, cancelled)) {
        is HtpLeaseAcquisition.Refused -> PreparedHtpDispatchOutcome.Refused(acquisition)
        is HtpLeaseAcquisition.Acquired -> acquisition.handle.use {
            PreparedHtpDispatchOutcome.Completed(prepared, dispatch(prepared))
        }
    }
}

data class LivePeerPressure(
    val samplesReceived: Long,
    val pendingCameraFrames: Int,
    val pendingImuBatches: Int,
    val pendingAudioBlocks: Int,
    val pendingTouchEvents: Int,
    val droppedCameraFrames: Long,
    val droppedImuBatches: Long,
    val droppedAudioBlocks: Long,
    val touchOverflowEvents: Long,
    val sentRealtimeMessages: Long,
    val sentCameraMessages: Long,
)

data class LiveMachineVisionStatus(
    val phase: LiveMachineVisionPhase,
    val selectedProfile: String,
    val framesReceived: Long,
    val cameraFramesRejectedStale: Long,
    val framesDroppedBeforeInference: Long,
    val imuBatchesReceived: Long,
    val imuSamplesReceived: Long,
    val imuPoseSamplesAccepted: Long,
    val imuPoseSamplesRejected: Long,
    val microphonePhase: LiveMicrophonePhase,
    val microphoneChunksReceived: Long,
    val microphoneBytesReceived: Long,
    val microphoneTimelineOverflow: Long,
    val touchEventsReceived: Long,
    val peerPressure: LivePeerPressure?,
    val nodeCommandPhase: LiveRokidNodeCommandPhase,
    val lastNodeCommandOperation: RokidNodeCommandOperation?,
    val lastNodeCommandFromGlassesGesture: Boolean,
    val inferenceAttempts: Long,
    val inferenceSuccesses: Long,
    val perceptionUnavailableFrames: Long,
    val perceptionResultsRejectedStale: Long,
    val lastPerceptionDiagnosticCode: String?,
    val currentDetectedInstances: Int,
    val totalDetectedInstances: Long,
    val lastFiniteYoloValues: Int,
    val lastFinitePositiveDepthValues: Int,
    val totalFiniteYoloValues: Long,
    val totalFinitePositiveDepthValues: Long,
    val currentRelativeTracks: Int,
    val currentMetricTracks: Int,
    val currentPropagatedTracks: Int,
    val lastMetricReason: String?,
    val environmentPendingFrames: Long,
    val linkInterruptions: Long,
    val calibrationState: LiveMetricCalibrationState?,
    val endToEndMs: LatencyPercentiles,
    val captureToReceiveMs: LatencyPercentiles,
    val decodeMs: LatencyPercentiles,
    val yoloPreprocessMs: LatencyPercentiles,
    val segmentationMs: LatencyPercentiles,
    val yoloPostprocessMs: LatencyPercentiles,
    val modelSetupMs: LatencyPercentiles,
    val depthPreprocessMs: LatencyPercentiles,
    val depthMs: LatencyPercentiles,
    val depthPostprocessMs: LatencyPercentiles,
    val executorTotalMs: LatencyPercentiles,
    val p95ClockUncertaintyMs: Double?,
    val closeEvidence: LiveLinkCloseEvidence,
    val lastLinkDiagnosticCode: String?,
    val lastFailureCode: String?,
) {
    fun accessibleSummary(): String = buildString {
        append("Live Machine Vision status: ").append(accessibleLiveMachineVisionPhase(phase)).append(". ")
        append("Link diagnostic schema: ").append(LIVE_LINK_DIAGNOSTIC_SCHEMA_VERSION).append(". ")
        append("Depth profile: ").append(selectedProfile).append(". ")
        append("Camera frames reconstructed: ").append(framesReceived)
        append("; rejected stale: ").append(cameraFramesRejectedStale)
        append("; inferred: ").append(inferenceSuccesses).append(" of ").append(inferenceAttempts)
        append("; perception unavailable: ").append(perceptionUnavailableFrames)
        append("; results rejected stale: ").append(perceptionResultsRejectedStale)
        append("; replaced: ").append(framesDroppedBeforeInference).append(". ")
        append("Detected instances: current ").append(currentDetectedInstances)
        append("; total ").append(totalDetectedInstances).append(". ")
        append("Finite outputs: last YOLO ").append(lastFiniteYoloValues)
        append("; last positive depth ").append(lastFinitePositiveDepthValues)
        append("; total YOLO ").append(totalFiniteYoloValues)
        append("; total positive depth ").append(totalFinitePositiveDepthValues).append(". ")
        append("Environment pending: ").append(environmentPendingFrames)
        append("; link interruptions: ").append(linkInterruptions).append(". ")
        append("IMU batches: ").append(imuBatchesReceived)
        append("; IMU samples: ").append(imuSamplesReceived)
        append("; pose accepted: ").append(imuPoseSamplesAccepted)
        append("; pose rejected: ").append(imuPoseSamplesRejected).append(". ")
        append("Microphone: ").append(microphonePhase.name.lowercase())
        append("; chunks received: ").append(microphoneChunksReceived)
        append("; bytes received: ").append(microphoneBytesReceived)
        append("; timeline overflow: ").append(microphoneTimelineOverflow).append(". ")
        append("Touch events received: ").append(touchEventsReceived).append(". ")
        peerPressure?.let {
            append("Rokid queue telemetry: samples ").append(it.samplesReceived)
            append("; pending camera ").append(it.pendingCameraFrames)
            append(", IMU ").append(it.pendingImuBatches)
            append(", audio ").append(it.pendingAudioBlocks)
            append(", touch ").append(it.pendingTouchEvents)
            append("; drops camera ").append(it.droppedCameraFrames)
            append(", IMU ").append(it.droppedImuBatches)
            append(", audio ").append(it.droppedAudioBlocks)
            append(", touch overflow ").append(it.touchOverflowEvents)
            append("; sent realtime messages ").append(it.sentRealtimeMessages)
            append(", camera messages ").append(it.sentCameraMessages).append(". ")
        }
        append("Rokid Node command: ").append(nodeCommandPhase.name.lowercase())
        lastNodeCommandOperation?.let {
            append("; operation ").append(accessibleNodeCommandOperation(it))
            append("; source ")
                .append(if (lastNodeCommandFromGlassesGesture) "glasses gesture" else "Android control")
        }
        append(". ")
        append("Perception tracks: relative ").append(currentRelativeTracks)
        append("; metric ").append(currentMetricTracks)
        append("; propagated ").append(currentPropagatedTracks).append(". ")
        append("Metric status: ").append(calibrationState?.name?.lowercase() ?: "not_observed").append(". ")
        lastMetricReason?.let { append("Metric reason: ").append(it).append(". ") }
        endToEndMs.p95?.let { append("End-to-end p95 milliseconds: ").append(format(it)).append(". ") }
        captureToReceiveMs.p95?.let {
            append("Capture-to-receive p95 milliseconds: ").append(format(it)).append(". ")
        }
        if (segmentationMs.p95 != null && depthMs.p95 != null) {
            append("Graph p95 milliseconds: segmentation ").append(format(segmentationMs.p95))
            append(", depth ").append(format(depthMs.p95)).append(". ")
        }
        if (decodeMs.p95 != null && yoloPreprocessMs.p95 != null && yoloPostprocessMs.p95 != null &&
            depthPreprocessMs.p95 != null && depthPostprocessMs.p95 != null && modelSetupMs.p95 != null &&
            executorTotalMs.p95 != null
        ) {
            append("Host stage p95 milliseconds: decode ").append(format(decodeMs.p95))
            append(", YOLO preprocess ").append(format(yoloPreprocessMs.p95))
            append(", YOLO postprocess ").append(format(yoloPostprocessMs.p95))
            append(", depth preprocess ").append(format(depthPreprocessMs.p95))
            append(", depth postprocess ").append(format(depthPostprocessMs.p95))
            append(", model setup ").append(format(modelSetupMs.p95))
            append(", executor total ").append(format(executorTotalMs.p95)).append(". ")
        }
        p95ClockUncertaintyMs?.let {
            append("Clock uncertainty p95 milliseconds: ").append(format(it)).append(". ")
        }
        append("Close evidence: host authenticated close seen ")
            .append(closeEvidence.hostAuthenticatedCloseSeen)
            .append("; host failure lane ")
            .append(closeEvidence.hostFailureLane.name.lowercase()).append(". ")
        lastLinkDiagnosticCode?.let {
            append(
                if (phase == LiveMachineVisionPhase.STREAMING) {
                    "Previous link interruption diagnostic: "
                } else {
                    "Link diagnostic: "
                },
            )
            append(it).append(". ")
        }
        lastPerceptionDiagnosticCode?.let { append("Perception diagnostic: ").append(it).append(". ") }
        lastFailureCode?.let { append("Failure code: ").append(it).append('.') }
    }

    private fun format(value: Double): String = java.lang.String.format(java.util.Locale.ROOT, "%.1f", value)
}

data class LatencyPercentiles(val p50: Double?, val p95: Double?, val p99: Double?)

enum class LiveQnnBackendEvidence {
    NOT_EXERCISED,
    INITIALIZING,
    HTP_EXECUTED,
    QNN_FAILED,
    LIVE_FAILED_BEFORE_QNN_EXECUTION,
}

internal fun liveQnnBackendEvidence(status: LiveMachineVisionStatus?): LiveQnnBackendEvidence =
    liveQnnBackendEvidence(status?.phase, status?.inferenceSuccesses ?: 0L, status?.lastFailureCode)

internal fun liveQnnBackendEvidence(
    phase: LiveMachineVisionPhase?,
    inferenceSuccesses: Long,
    lastFailureCode: String? = null,
): LiveQnnBackendEvidence = when {
    inferenceSuccesses > 0L -> LiveQnnBackendEvidence.HTP_EXECUTED
    phase == LiveMachineVisionPhase.FAILED && lastFailureCode?.startsWith("QNN_") == true ->
        LiveQnnBackendEvidence.QNN_FAILED
    phase == LiveMachineVisionPhase.FAILED -> LiveQnnBackendEvidence.LIVE_FAILED_BEFORE_QNN_EXECUTION
    phase == LiveMachineVisionPhase.OPENING_QNN_HTP ||
        phase == LiveMachineVisionPhase.LISTENING ||
        phase == LiveMachineVisionPhase.STREAMING -> LiveQnnBackendEvidence.INITIALIZING
    else -> LiveQnnBackendEvidence.NOT_EXERCISED
}

/** Allows at most one terminal UI/status publication for each controller run. */
internal class LiveTerminalPublicationGate {
    private val published = AtomicBoolean(false)

    fun reset() = published.set(false)

    fun publishOnce(block: () -> Unit): Boolean {
        if (!published.compareAndSet(false, true)) return false
        block()
        return true
    }
}

/** Aggregate-only bounded telemetry: no image, label, address, identity, or raw IMU data enters it. */
class LiveMachineVisionStatusAccumulator(selectedProfile: String) {
    private var phase = LiveMachineVisionPhase.IDLE
    private var selectedProfile = selectedProfile
    private var framesReceived = 0L
    private var cameraFramesRejectedStale = 0L
    private var framesDropped = 0L
    private var imuBatches = 0L
    private var imuSamples = 0L
    private var imuPosesAccepted = 0L
    private var imuPosesRejected = 0L
    private var microphonePhase = LiveMicrophonePhase.IDLE
    private var microphoneChunks = 0L
    private var microphoneBytes = 0L
    private var microphoneTimelineOverflow = 0L
    private var touchEvents = 0L
    private var peerPressure: LivePeerPressure? = null
    private var nodeCommandPhase = LiveRokidNodeCommandPhase.IDLE
    private var lastNodeCommandOperation: RokidNodeCommandOperation? = null
    private var lastNodeCommandFromGlassesGesture = false
    private var attempts = 0L
    private var successes = 0L
    private var perceptionUnavailableFrames = 0L
    private var perceptionResultsRejectedStale = 0L
    private var lastPerceptionDiagnostic: String? = null
    private var currentDetectedInstances = 0
    private var totalDetectedInstances = 0L
    private var lastFiniteYoloValues = 0
    private var lastFinitePositiveDepthValues = 0
    private var totalFiniteYoloValues = 0L
    private var totalFinitePositiveDepthValues = 0L
    private var currentRelativeTracks = 0
    private var currentMetricTracks = 0
    private var currentPropagatedTracks = 0
    private var lastMetricReason: String? = null
    private var environmentPending = 0L
    private var linkInterruptions = 0L
    private var calibrationState: LiveMetricCalibrationState? = null
    private var lastLinkDiagnostic: LiveLinkDiagnosticCode? = null
    private var closeEvidence = LiveLinkCloseEvidence()
    private var sessionIsReady = false
    private var lastDiagnosticOccurredDuringSession = false
    private var lastFailure: String? = null
    private val endToEndNanos = ArrayList<Long>()
    private val captureToReceiveNanos = ArrayList<Long>()
    private val decodeNanos = ArrayList<Long>()
    private val yoloPreprocessNanos = ArrayList<Long>()
    private val segmentationNanos = ArrayList<Long>()
    private val yoloPostprocessNanos = ArrayList<Long>()
    private val modelSetupNanos = ArrayList<Long>()
    private val depthPreprocessNanos = ArrayList<Long>()
    private val depthNanos = ArrayList<Long>()
    private val depthPostprocessNanos = ArrayList<Long>()
    private val executorTotalNanos = ArrayList<Long>()
    private val clockUncertaintyNanos = ArrayList<Long>()

    @Synchronized fun phase(value: LiveMachineVisionPhase) { phase = value }
    @Synchronized
    fun cameraReceived(admittedToTimeline: Boolean = true) {
        framesReceived = Math.addExact(framesReceived, 1)
        if (!admittedToTimeline) {
            cameraFramesRejectedStale = Math.addExact(cameraFramesRejectedStale, 1)
        }
    }
    @Synchronized fun cameraReplaced() { framesDropped = Math.addExact(framesDropped, 1) }
    @Synchronized fun inferenceStarted() { attempts = Math.addExact(attempts, 1) }
    @Synchronized fun environmentPending() { environmentPending = Math.addExact(environmentPending, 1) }
    @Synchronized
    fun perceptionUnavailable(code: String, frameSkipped: Boolean = false) {
        require(FAILURE_CODE.matches(code))
        lastPerceptionDiagnostic = code
        if (frameSkipped) perceptionUnavailableFrames = Math.addExact(perceptionUnavailableFrames, 1L)
    }
    @Synchronized
    fun perceptionResultRejectedStale() {
        perceptionResultsRejectedStale = Math.addExact(perceptionResultsRejectedStale, 1L)
        lastPerceptionDiagnostic = "PERCEPTION_RESULT_STALE"
    }
    @Synchronized
    fun sessionReady() {
        val diagnostic = lastLinkDiagnostic
        if (!sessionIsReady && !lastDiagnosticOccurredDuringSession &&
            diagnostic != null && diagnostic in TRANSIENT_PRE_SESSION_DIAGNOSTICS
        ) {
            lastLinkDiagnostic = null
        }
        closeEvidence = LiveLinkCloseEvidence()
        sessionIsReady = true
    }

    @Synchronized
    fun linkInterrupted() {
        linkInterruptions = Math.addExact(linkInterruptions, 1)
        sessionIsReady = false
        if (microphonePhase == LiveMicrophonePhase.REQUESTING ||
            microphonePhase == LiveMicrophonePhase.ACTIVE
        ) {
            microphonePhase = LiveMicrophonePhase.COMPLETE
        }
    }

    @Synchronized
    fun linkDiagnostic(code: LiveLinkDiagnosticCode) {
        lastLinkDiagnostic = code
        lastDiagnosticOccurredDuringSession = lastDiagnosticOccurredDuringSession || sessionIsReady
    }

    @Synchronized
    fun linkCloseEvidence(evidence: LiveLinkCloseEvidence) {
        closeEvidence = evidence
    }

    @Synchronized
    fun imuReceived(samples: Int, acceptedPoses: Int, rejectedPoses: Int, propagatedTracks: Int) {
        require(samples in 1..64)
        require(acceptedPoses >= 0 && rejectedPoses >= 0 && acceptedPoses + rejectedPoses == samples)
        require(propagatedTracks in 0..64)
        imuBatches = Math.addExact(imuBatches, 1)
        imuSamples = Math.addExact(imuSamples, samples.toLong())
        imuPosesAccepted = Math.addExact(imuPosesAccepted, acceptedPoses.toLong())
        imuPosesRejected = Math.addExact(imuPosesRejected, rejectedPoses.toLong())
        currentPropagatedTracks = propagatedTracks
    }

    @Synchronized
    fun microphoneState(value: LiveMicrophonePhase) {
        microphonePhase = value
    }

    @Synchronized
    fun microphoneReceived(bytes: Int, admittedToTimeline: Boolean) {
        require(bytes in 1..64 * 1_024)
        microphoneChunks = Math.addExact(microphoneChunks, 1L)
        microphoneBytes = Math.addExact(microphoneBytes, bytes.toLong())
        if (!admittedToTimeline) {
            microphoneTimelineOverflow = Math.addExact(microphoneTimelineOverflow, 1L)
        }
    }

    @Synchronized fun touchReceived(count: Int = 1) {
        require(count > 0)
        touchEvents = Math.addExact(touchEvents, count.toLong())
    }

    @Synchronized
    fun peerTelemetry(value: LiveLinkTelemetry) {
        val samples = Math.addExact(peerPressure?.samplesReceived ?: 0L, 1L)
        peerPressure = LivePeerPressure(
            samples,
            value.pendingCameraFrames,
            value.pendingImuBatches,
            value.pendingAudioBlocks,
            value.pendingTouchEvents,
            value.droppedCameraFrames,
            value.droppedImuBatches,
            value.droppedAudioBlocks,
            value.touchOverflowEvents,
            value.sentRealtimeMessages,
            value.sentCameraMessages,
        )
    }

    @Synchronized
    fun nodeCommandRequested(operation: RokidNodeCommandOperation, fromGlassesGesture: Boolean) {
        require(operation != RokidNodeCommandOperation.ROKID_NODE_COMMAND_OPERATION_UNSPECIFIED)
        nodeCommandPhase = LiveRokidNodeCommandPhase.REQUESTED
        lastNodeCommandOperation = operation
        lastNodeCommandFromGlassesGesture = fromGlassesGesture
    }

    @Synchronized
    fun nodeCommandResult(result: RokidNodeCommandDelivery) {
        nodeCommandPhase = if (result.acceptedForExecution) {
            LiveRokidNodeCommandPhase.ACCEPTED
        } else {
            LiveRokidNodeCommandPhase.REJECTED
        }
        lastNodeCommandOperation = result.operation
        lastNodeCommandFromGlassesGesture = result.originatingGestureId > 0L
    }

    @Synchronized
    fun inferenceSucceeded(
        endToEndNs: Long,
        captureToReceiveNs: Long,
        clockUncertaintyNs: Long,
        result: QnnLiveFrameResult,
        fusion: LiveMetricFusionResult,
    ) {
        require(endToEndNs >= 0 && captureToReceiveNs >= 0 && clockUncertaintyNs >= 0)
        successes = Math.addExact(successes, 1)
        selectedProfile = result.selectedDepthProfileId
        calibrationState = when (fusion.metricProvenance?.kind) {
            MetricDepthProvenanceKind.PINNED_OFFICIAL_NATIVE_METRIC -> when (result.calibrationState) {
                LiveMetricCalibrationState.CALIBRATED_INTRINSICS_PRESENT ->
                    LiveMetricCalibrationState.PROFILE_BOUND_NATIVE_METRIC_CALIBRATED_INTRINSICS_PRESENT
                LiveMetricCalibrationState.DERIVED_INTRINSICS_PRESENT ->
                    LiveMetricCalibrationState.PROFILE_BOUND_NATIVE_METRIC_DERIVED_INTRINSICS_PRESENT
                else -> LiveMetricCalibrationState.PROFILE_BOUND_NATIVE_METRIC_AVAILABLE
            }
            MetricDepthProvenanceKind.GUIDED_TWO_ANCHOR ->
                LiveMetricCalibrationState.PROFILE_BOUND_METRIC_TRACKS_AVAILABLE
            null -> result.calibrationState
        }
        currentDetectedInstances = result.segmentedObjectCount
        totalDetectedInstances = Math.addExact(totalDetectedInstances, result.segmentedObjectCount.toLong())
        lastFiniteYoloValues = result.finiteYoloValues
        lastFinitePositiveDepthValues = result.finitePositiveDepthValues
        totalFiniteYoloValues = Math.addExact(totalFiniteYoloValues, result.finiteYoloValues.toLong())
        totalFinitePositiveDepthValues = Math.addExact(
            totalFinitePositiveDepthValues,
            result.finitePositiveDepthValues.toLong(),
        )
        currentRelativeTracks = fusion.relativeTrackCount
        currentMetricTracks = fusion.metricTrackCount
        currentPropagatedTracks = fusion.propagatedTrackCount
        lastMetricReason = fusion.reason.name
        appendBounded(endToEndNanos, endToEndNs)
        appendBounded(captureToReceiveNanos, captureToReceiveNs)
        appendBounded(decodeNanos, result.decodeLatencyNanos)
        appendBounded(yoloPreprocessNanos, result.yoloPreprocessLatencyNanos)
        appendBounded(segmentationNanos, result.segmentationLatencyNanos)
        appendBounded(yoloPostprocessNanos, result.yoloPostprocessLatencyNanos)
        appendBounded(modelSetupNanos, result.modelSetupLatencyNanos)
        appendBounded(depthPreprocessNanos, result.depthPreprocessLatencyNanos)
        appendBounded(depthNanos, result.depthLatencyNanos)
        appendBounded(depthPostprocessNanos, result.depthPostprocessLatencyNanos)
        appendBounded(executorTotalNanos, result.totalLatencyNanos)
        appendBounded(clockUncertaintyNanos, clockUncertaintyNs)
    }

    @Synchronized
    fun failed(code: String) {
        require(FAILURE_CODE.matches(code))
        lastFailure = code
        phase = LiveMachineVisionPhase.FAILED
    }

    @Synchronized
    fun snapshot(): LiveMachineVisionStatus = LiveMachineVisionStatus(
        phase,
        selectedProfile,
        framesReceived,
        cameraFramesRejectedStale,
        framesDropped,
        imuBatches,
        imuSamples,
        imuPosesAccepted,
        imuPosesRejected,
        microphonePhase,
        microphoneChunks,
        microphoneBytes,
        microphoneTimelineOverflow,
        touchEvents,
        peerPressure,
        nodeCommandPhase,
        lastNodeCommandOperation,
        lastNodeCommandFromGlassesGesture,
        attempts,
        successes,
        perceptionUnavailableFrames,
        perceptionResultsRejectedStale,
        lastPerceptionDiagnostic,
        currentDetectedInstances,
        totalDetectedInstances,
        lastFiniteYoloValues,
        lastFinitePositiveDepthValues,
        totalFiniteYoloValues,
        totalFinitePositiveDepthValues,
        currentRelativeTracks,
        currentMetricTracks,
        currentPropagatedTracks,
        lastMetricReason,
        environmentPending,
        linkInterruptions,
        calibrationState,
        percentiles(endToEndNanos),
        percentiles(captureToReceiveNanos),
        percentiles(decodeNanos),
        percentiles(yoloPreprocessNanos),
        percentiles(segmentationNanos),
        percentiles(yoloPostprocessNanos),
        percentiles(modelSetupNanos),
        percentiles(depthPreprocessNanos),
        percentiles(depthNanos),
        percentiles(depthPostprocessNanos),
        percentiles(executorTotalNanos),
        percentileMillis(clockUncertaintyNanos.sorted(), 0.95),
        closeEvidence,
        lastLinkDiagnostic?.name,
        lastFailure,
    )

    private fun appendBounded(values: ArrayList<Long>, value: Long) {
        if (values.size == MAXIMUM_SAMPLES) values.removeAt(0)
        values += value
    }

    private fun percentiles(values: List<Long>): LatencyPercentiles {
        val sorted = values.sorted()
        return LatencyPercentiles(
            percentileMillis(sorted, 0.50),
            percentileMillis(sorted, 0.95),
            percentileMillis(sorted, 0.99),
        )
    }

    private fun percentileMillis(sorted: List<Long>, quantile: Double): Double? {
        if (sorted.isEmpty()) return null
        val index = kotlin.math.ceil(quantile * sorted.size).toInt().coerceIn(1, sorted.size) - 1
        return sorted[index] / 1_000_000.0
    }

    private companion object {
        val TRANSIENT_PRE_SESSION_DIAGNOSTICS = setOf(
            LiveLinkDiagnosticCode.NETWORK_IO,
            LiveLinkDiagnosticCode.SOCKET_TIMEOUT,
        )
        const val MAXIMUM_SAMPLES = 1_500
        val FAILURE_CODE = Regex("[A-Z][A-Z0-9_]{1,63}")
    }
}

/** Owns one bounded live test and closes QNN sessions plus both TLS lanes on every exit path. */
class LiveMachineVisionController(
    private val context: Context,
    calibrationProvider: MetricDepthCalibrationProvider = MetricDepthCalibrationProvider.none(),
    headCameraExtrinsic: VerifiedHeadCameraExtrinsic? = null,
    private val onNodeCommandResult: (RokidNodeCommandDelivery) -> Unit = {},
    private val perceptionBus: PerceptionBus = AndroidPerceptionBridge.runtimeBus,
    private val resultFreshnessGate: LivePerceptionResultFreshnessGate = LivePerceptionResultFreshnessGate(),
    private val onFocusState: (SpatialFocusState) -> Unit = {},
    private val focusTouchAdmission: SpatialFocusTouchAdmission = DisabledSpatialFocusTouchAdmission,
    private val onStatus: (LiveMachineVisionStatus) -> Unit,
) : AutoCloseable {
    private val active = AtomicBoolean(false)
    private val pendingFrame = AtomicReference<PendingLiveFrame?>(null)
    private val workerScheduled = AtomicBoolean(false)
    private val sessionGeneration = LiveSessionGeneration()
    private val startupGate = LiveStartupGate()
    private val terminalPublication = LiveTerminalPublicationGate()
    private var reconnectPolicy = LiveReconnectPolicy()
    private val sessionDeadlineGate = LiveSessionDeadlineGate()
    private val metricFusion = LiveMetricTemporalFusion(calibrationProvider, headCameraExtrinsic)
    private val trackMaintainer = LightweightTrackMaintainer(headFromCamera = headCameraExtrinsic)
    private val modelAdmission = LiveHtpFrameAdmissionGate()
    private val vlmHtpAdmission = LiveVlmHtpAdmissionGate()
    private val htpExecutionLease = org.conceptflow.mpl.host.vision.HtpExecutionLease(
        context,
        ::logHtpLeaseTelemetry,
    )
    private val sensorTimeline = SensorTimeline()
    private val focusedVqaRouter = ReplaceableFocusedVqaGateway()
    private val focusedVqaFrames = BoundedFocusedVqaFrameStore(ElapsedHostClock::nowNanos)
    private val spatialFocus = SpatialFocusManager(vqaGateway = focusedVqaRouter)
    private var executor: ScheduledExecutorService? = null
    private var startupExecutor: ExecutorService? = null
    private var startupFuture: Future<*>? = null
    private var server: PocoLiveLinkServer? = null
    private var qnn: QnnLiveFrameExecutor? = null
    private var environmentVlm: AndroidLocalVlmEnvironmentClient? = null
    private var focusedVqaGateway: AndroidLocalVlmFocusedVqaGateway? = null
    @Volatile private var cameraIngress: GlassesStreamIngress? = null
    @Volatile private var imuIngress: GlassesStreamIngress? = null
    @Volatile private var microphoneIngress: GlassesStreamIngress? = null
    @Volatile private var currentSessionBinding: LiveSessionBinding? = null
    private var status: LiveMachineVisionStatusAccumulator? = null
    private var maximumFrames = 0
    private var environmentCoordinator = EnvironmentDepthCoordinator()
    private var currentCameraFrameId = 0L
    private var currentCameraCaptureNs = 0L
    private var currentCameraUncertaintyNs = 0L
    private var currentIngressGeneration = 0L
    private var microphoneWindowGeneration = 0L
    private var scheduledDwellGeneration = 0L
    private var lastPublishedNs = 0L
    private var lastEnvironmentDecisionDiagnostic: String? = null
    @Volatile private var latestDepthProfileId = ""
    @Volatile private var automaticEnvironmentVlmBootstrapPending = false
    private var runMode = LiveMachineVisionRunMode.BOUNDED_DIAGNOSTIC

    @Synchronized
    fun start(spec: LiveMachineVisionTestSpec) {
        check(active.compareAndSet(false, true)) { "a live Machine Vision test is already active" }
        terminalPublication.reset()
        sessionDeadlineGate.reset()
        metricFusion.reset()
        trackMaintainer.reset()
        modelAdmission.reset()
        vlmHtpAdmission.reset()
        latestDepthProfileId = ""
        automaticEnvironmentVlmBootstrapPending = spec.environmentMode == EnvironmentSelectionMode.AUTOMATIC
        runMode = spec.runMode
        reconnectPolicy = LiveReconnectPolicy(persistent = spec.runMode == LiveMachineVisionRunMode.PERSISTENT_NODE)
        val startupToken = startupGate.begin()
        environmentCoordinator = EnvironmentDepthCoordinator().also { it.setMode(spec.environmentMode) }
        lastEnvironmentDecisionDiagnostic = null
        val initialProfile = when (spec.environmentMode) {
            EnvironmentSelectionMode.AUTOMATIC -> "automatic-pending"
            EnvironmentSelectionMode.FORCE_INDOOR -> MachineVisionModelProfiles.depthIndoorBalanced.id
            EnvironmentSelectionMode.FORCE_OUTDOOR -> MachineVisionModelProfiles.depthOutdoorBalanced.id
        }
        val accumulator = LiveMachineVisionStatusAccumulator(initialProfile).also {
            it.phase(LiveMachineVisionPhase.OPENING_QNN_HTP)
            status = it
            publish(force = true)
        }
        maximumFrames = spec.maximumFrames
        try {
            val scheduled = Executors.newScheduledThreadPool(2) { runnable ->
                Thread(runnable, "mpl-live-vision").apply { isDaemon = true }
            }.also { executor = it }
            val startup = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "mpl-live-startup").apply { isDaemon = true }
            }.also { startupExecutor = it }
            startupFuture = startup.submit {
                initializeLiveResources(spec, accumulator, scheduled, startupToken)
            }
        } catch (error: Throwable) {
            accumulator.failed(failureCode(error))
            closeResources()
            active.set(false)
            terminalPublication.publishOnce { publish(force = true) }
        }
    }

    /** Native QNN dlopen/session creation must never block Activity lifecycle or TalkBack input. */
    private fun initializeLiveResources(
        spec: LiveMachineVisionTestSpec,
        accumulator: LiveMachineVisionStatusAccumulator,
        scheduled: ScheduledExecutorService,
        startupToken: Long,
    ) {
        var openedQnn: QnnLiveFrameExecutor? = null
        var openedServer: PocoLiveLinkServer? = null
        var openedVlm: AndroidLocalVlmEnvironmentClient? = null
        try {
            val configuration = context.noBackupFilesDir.resolve("live-link/live-link.properties")
                .inputStream().buffered().use { LiveLinkPrivateConfig.parse(it, LiveLinkEndpointRole.POCO_HOST) }
            openedServer = PocoLiveLinkServer.fromConfig(
                configuration,
                acceptSequentialSessions = spec.runMode == LiveMachineVisionRunMode.PERSISTENT_NODE,
                endpointResolver = when (configuration.networkTopology) {
                    LiveLinkNetworkTopology.PRIVATE_LAN ->
                        org.conceptflow.mpl.transport.StaticLiveLinkEndpointResolver(configuration.address)
                    LiveLinkNetworkTopology.WIFI_DIRECT_REQUIRED ->
                        AndroidWifiDirectEndpointResolver(context, WifiDirectNodeRole.ANDROID_GROUP_OWNER)
                },
            )
            synchronized(this) {
                if (!active.get() || !startupGate.mayPublish(startupToken)) return
                qnn = openedQnn
                server = openedServer
                environmentVlm = openedVlm
                openedServer = null
            }
            accumulator.phase(LiveMachineVisionPhase.LISTENING)
            publish(force = true)
            requireNotNull(server).start(observer())
            if (!active.get() || !startupGate.mayPublish(startupToken)) return
            if (spec.runMode == LiveMachineVisionRunMode.BOUNDED_DIAGNOSTIC) {
                scheduled.schedule(
                    {
                        if (sessionDeadlineGate.expireRendezvousIfUnauthenticated()) {
                            fail("LIVE_RENDEZVOUS_TIMEOUT")
                        }
                    },
                    RENDEZVOUS_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                )
            }
            openedQnn = runCatching {
                val sessionFactory = NativeQnnModelSessionFactory(
                    QnnRuntimeBundle(context.filesDir.resolve("qnn-runtime")),
                    context.filesDir.resolve("models"),
                )
                QnnLiveFrameExecutor(
                    sessionFactory,
                    AndroidJpegDecoder(),
                    ElapsedHostClock::nowNanos,
                ) { generation, frame, image ->
                    if (active.get()) {
                        focusedVqaFrames.offer(generation, frame, image)
                    }
                }
            }.getOrElse { error ->
                accumulator.perceptionUnavailable(failureCode(error))
                publish(force = true)
                null
            }
            openedVlm = runCatching {
                AndroidLocalVlmEnvironmentClient(context, ElapsedHostClock::nowNanos).also { it.start() }
            }.getOrElse {
                if (spec.environmentMode == EnvironmentSelectionMode.AUTOMATIC) {
                    accumulator.perceptionUnavailable("LOCAL_VLM_UNAVAILABLE")
                    publish(force = true)
                }
                null
            }
            synchronized(this) {
                if (!active.get() || !startupGate.mayPublish(startupToken)) return
                qnn = openedQnn
                environmentVlm = openedVlm
                focusedVqaGateway = openedVlm?.let { client ->
                    AndroidLocalVlmFocusedVqaGateway(
                        client,
                        StoredFocusedVqaFrameProvider(
                            focusedVqaFrames,
                            AndroidFocusedVqaJpegEncoder(),
                        ),
                        ::handleFocusedVqaOutcome,
                    ).also(focusedVqaRouter::install)
                }
                openedQnn = null
                openedVlm = null
            }
        } catch (error: Throwable) {
            if (active.get() && startupGate.mayPublish(startupToken)) {
                accumulator.failed(failureCode(error))
                publish(force = true)
                finish(LiveMachineVisionPhase.FAILED)
            }
        } finally {
            runCatching { openedServer?.close() }
            runCatching { openedQnn?.close() }
            runCatching { openedVlm?.close() }
            if (startupGate.mayPublish(startupToken)) startupExecutor?.shutdown()
        }
    }

    @Synchronized fun snapshot(): LiveMachineVisionStatus? = status?.snapshot()
    fun updateGnss(sample: GnssQualitySample): Boolean = environmentCoordinator.updateGnss(sample)

    fun requestMicrophone(): MicrophoneRequestDispatch {
        val dispatch = server?.requestMicrophone() ?: MicrophoneRequestDispatch.NO_AUTHENTICATED_SESSION
        if (dispatch != MicrophoneRequestDispatch.REQUESTED) {
            status?.microphoneState(LiveMicrophonePhase.REJECTED)
            publish(force = true)
        }
        return dispatch
    }

    fun handleFocusCommand(command: SpatialFocusCommand): SpatialFocusState? {
        if (!active.get() || currentIngressGeneration <= 0L) return null
        val nowNanos = ElapsedHostClock.nowNanos()
        val head = perceptionBus.latestHeadSnapshot()?.state?.let {
            BeaconHeadOrientation(
                timestampNanos = it.timestampNs,
                accuracy = it.orientationAccuracy,
                w = it.w.toDouble(),
                x = it.x.toDouble(),
                y = it.y.toDouble(),
                z = it.z.toDouble(),
            )
        }
        return publishFocusTransition(spatialFocus.command(command, nowNanos, head).state)
    }

    fun playRokidBrandSequence(): RokidNodeCommandDispatch {
        val dispatch = server?.requestRokidCommand(
            RokidNodeCommandOperation.ROKID_NODE_COMMAND_OPERATION_PLAY_BRAND_SEQUENCE,
        ) ?: RokidNodeCommandDispatch.NO_AUTHENTICATED_SESSION
        if (dispatch == RokidNodeCommandDispatch.REQUESTED) {
            status?.nodeCommandRequested(
                RokidNodeCommandOperation.ROKID_NODE_COMMAND_OPERATION_PLAY_BRAND_SEQUENCE,
                fromGlassesGesture = false,
            )
            publish(force = true)
        }
        return dispatch
    }

    private fun observer(): PocoLiveLinkObserver = object : PocoLiveLinkObserver {
        override fun onSessionReady(session: LiveLinkSession) {
            acceptSession(session)
        }

        override fun onSensor(delivery: LiveSensorDelivery) {
            if (!active.get()) return
            try {
                when {
                    delivery.sensor.hasImuBatch() -> acceptImu(delivery)
                    delivery.sensor.hasCameraChunk() -> acceptCamera(delivery)
                    delivery.sensor.hasMicrophoneChunk() -> acceptMicrophone(delivery)
                    delivery.sensor.hasTouchEvent() -> acceptTouch(delivery)
                    else -> fail("LIVE_INVALID_SENSOR_PAYLOAD")
                }
            } catch (error: RuntimeException) {
                // Keep diagnostics content-free: never log the envelope, frame, sensor values,
                // session binding, peer address, or exception message. The stack identifies the
                // local invariant that failed without exposing captured user data.
                Log.e(
                    TAG,
                    "state=sensor_consumer_failure payload=${delivery.sensor.payloadCase} " +
                        "exception=${error.javaClass.simpleName}",
                    error,
                )
                throw error
            }
        }

        override fun onPeerTelemetry(telemetry: LiveLinkTelemetry) {
            if (!active.get()) return
            status?.peerTelemetry(telemetry)
        }

        override fun onMicrophoneLeaseState(state: LiveMicrophoneLeaseState, durationMillis: Int) {
            handleMicrophoneLeaseState(state, durationMillis)
        }

        override fun onRokidGesture(operation: org.conceptflow.mpl.v1.RokidGestureOperation) {
            val command = org.conceptflow.mpl.transport.RokidGestureCommandPolicy.commandFor(operation)
                ?: return
            status?.nodeCommandRequested(command, fromGlassesGesture = true)
            publish(force = true)
        }

        override fun onRokidNodeCommandResult(result: RokidNodeCommandDelivery) {
            if (!active.get()) return
            status?.nodeCommandResult(result)
            publish(force = true)
            onNodeCommandResult(result)
        }

        override fun onDiagnostic(code: LiveLinkDiagnosticCode) {
            if (!active.get()) return
            status?.linkDiagnostic(code)
            publish(force = true)
        }

        override fun onCloseEvidence(evidence: LiveLinkCloseEvidence) {
            if (!active.get()) return
            status?.linkCloseEvidence(evidence)
            publish(force = true)
        }

        override fun onDisconnected(reason: LiveLinkDisconnectReason) {
            if (!active.get() || reason == LiveLinkDisconnectReason.STOPPED) return
            when (reconnectPolicy.onDisconnect(reason)) {
                // The transport invokes disconnect and the next session-ready callback serially.
                // Reset synchronously so a delayed executor task cannot erase the replacement session.
                LiveReconnectDecision.RETRY -> recoverForReconnect(reason)
                LiveReconnectDecision.COMPLETE -> executor?.execute {
                    finish(LiveMachineVisionPhase.COMPLETE)
                }
                LiveReconnectDecision.FAIL_CLOSED -> executor?.execute { fail("LINK_${reason.name}") }
                LiveReconnectDecision.IGNORE -> Unit
            }
        }
    }

    @Synchronized
    private fun handleMicrophoneLeaseState(state: LiveMicrophoneLeaseState, durationMillis: Int) {
        if (!active.get()) return
        when (state) {
            LiveMicrophoneLeaseState.REQUESTED -> {
                microphoneWindowGeneration = Math.addExact(microphoneWindowGeneration, 1L)
                microphoneIngress = null
                status?.microphoneState(LiveMicrophonePhase.REQUESTING)
            }
            LiveMicrophoneLeaseState.ACTIVE -> {
                val binding = currentSessionBinding
                val generation = microphoneWindowGeneration
                microphoneIngress = binding?.let {
                    GlassesStreamIngress(it.sessionId, it.leaseId, true, ElapsedHostClock)
                }
                status?.microphoneState(LiveMicrophonePhase.ACTIVE)
                executor?.schedule(
                    { completeMicrophoneWindow(generation) },
                    durationMillis.toLong(),
                    TimeUnit.MILLISECONDS,
                )
            }
            LiveMicrophoneLeaseState.REJECTED,
            LiveMicrophoneLeaseState.COMPLETE,
            -> {
                microphoneWindowGeneration = Math.addExact(microphoneWindowGeneration, 1L)
                microphoneIngress = null
                status?.microphoneState(
                    if (state == LiveMicrophoneLeaseState.REJECTED) {
                        LiveMicrophonePhase.REJECTED
                    } else {
                        LiveMicrophonePhase.COMPLETE
                    },
                )
            }
        }
        publish(force = true)
    }

    @Synchronized
    private fun completeMicrophoneWindow(generation: Long) {
        if (!active.get() || microphoneWindowGeneration != generation) return
        microphoneIngress = null
        microphoneWindowGeneration = Math.addExact(microphoneWindowGeneration, 1L)
        status?.microphoneState(LiveMicrophonePhase.COMPLETE)
        publish(force = true)
    }

    @Synchronized
    private fun acceptSession(session: LiveLinkSession) {
        if (!active.get()) return
        if (runMode == LiveMachineVisionRunMode.BOUNDED_DIAGNOSTIC) {
            when (sessionDeadlineGate.onSessionReady()) {
                LiveSessionArrival.REJECT_AFTER_TIMEOUT -> return
                LiveSessionArrival.FIRST_AUTHENTICATED -> executor?.schedule(
                    { finish(LiveMachineVisionPhase.COMPLETE) },
                    ACTIVE_SESSION_DURATION_SECONDS.toLong(),
                    TimeUnit.SECONDS,
                )
                LiveSessionArrival.RECONNECT -> Unit
            }
        }
        status?.sessionReady()
        focusedVqaGateway?.reset()
        environmentVlm?.cancelOutstanding()
        metricFusion.reset()
        trackMaintainer.reset()
        modelAdmission.reset()
        vlmHtpAdmission.reset()
        latestDepthProfileId = ""
        automaticEnvironmentVlmBootstrapPending =
            environmentCoordinator.mode() == EnvironmentSelectionMode.AUTOMATIC
        sensorTimeline.reset()
        qnn?.resetTracking()
        currentIngressGeneration = sessionGeneration.advance()
        focusedVqaFrames.beginSession(currentIngressGeneration)
        val sessionNow = ElapsedHostClock.nowNanos()
        perceptionBus.beginSession(currentIngressGeneration, sessionNow)
        publishFocusTransition(
            spatialFocus.reset(currentIngressGeneration, sessionNow, perceptionBus.stats().latestRevision),
        )
        microphoneWindowGeneration = Math.addExact(microphoneWindowGeneration, 1L)
        currentSessionBinding = session.binding
        status?.microphoneState(LiveMicrophonePhase.IDLE)
        cameraIngress = GlassesStreamIngress(
            session.binding.sessionId, session.binding.leaseId, false, ElapsedHostClock,
        )
        imuIngress = GlassesStreamIngress(
            session.binding.sessionId, session.binding.leaseId, false, ElapsedHostClock,
        )
        microphoneIngress = null
        clearCameraCorrelation()
        status?.phase(LiveMachineVisionPhase.STREAMING)
        publish(force = true)
    }

    @Synchronized
    private fun recoverForReconnect(reason: LiveLinkDisconnectReason) {
        if (!active.get()) return
        if (reason.isUnexpectedInterruption()) status?.linkInterrupted()
        sessionGeneration.advance()
        currentIngressGeneration = 0L
        microphoneWindowGeneration = Math.addExact(microphoneWindowGeneration, 1L)
        pendingFrame.set(null)
        focusedVqaGateway?.reset()
        environmentVlm?.cancelOutstanding()
        focusedVqaFrames.reset()
        cameraIngress = null
        imuIngress = null
        microphoneIngress = null
        currentSessionBinding = null
        metricFusion.reset()
        trackMaintainer.reset()
        modelAdmission.reset()
        vlmHtpAdmission.reset()
        latestDepthProfileId = ""
        automaticEnvironmentVlmBootstrapPending =
            environmentCoordinator.mode() == EnvironmentSelectionMode.AUTOMATIC
        sensorTimeline.reset()
        val disconnectedNow = ElapsedHostClock.nowNanos()
        perceptionBus.invalidate(PerceptionValidityReason.DISCONNECTED, disconnectedNow)
        publishFocusTransition(
            spatialFocus.reset(0L, disconnectedNow, perceptionBus.stats().latestRevision),
        )
        qnn?.resetTracking()
        clearCameraCorrelation()
        status?.phase(LiveMachineVisionPhase.LISTENING)
        publish(force = true)
    }

    @Synchronized
    private fun acceptImu(delivery: LiveSensorDelivery) {
        if (!active.get()) return
        val ingress = imuIngress ?: return
        if (ingress.acceptAuthenticatedLane(delivery.sensor) == StreamIngressDisposition.IMU_READY) {
            var accepted = 0
            val timelineReadings = sensorTimeline.acceptImu(delivery)
            var rejected = delivery.sensor.imuBatch.samplesCount - timelineReadings.size
            var propagatedTracks = 0
            var maintainedTracks: List<LightweightTrackState>? = null
            val samples = delivery.sensor.imuBatch.samplesList
            timelineReadings.forEach { timed ->
                val pose = LiveImuPoseMapper.map(timed.reading, timed.hostPoseTimestampNs)
                if (pose == null) {
                    rejected += 1
                } else {
                    perceptionBus.publishHeadPose(pose)
                    val update = metricFusion.acceptPose(pose)
                    if (update.accepted) accepted += 1 else rejected += 1
                    val maintained = update.cameraPose?.let(trackMaintainer::updatePose)
                    if (maintained?.accepted == true) {
                        maintainedTracks = maintained.tracks
                        propagatedTracks = maintained.tracks.count {
                            it.coordinateValidity.headRelative == TrackEstimateValidity.ORIENTATION_PROPAGATED ||
                                it.coordinateValidity.headRelative ==
                                TrackEstimateValidity.TRANSLATION_EVIDENCE_PROPAGATED
                        }
                    } else {
                        propagatedTracks = update.temporalTrackCount
                    }
                }
            }
            maintainedTracks?.let { tracks ->
                val now = ElapsedHostClock.nowNanos()
                publishTrackedState(
                    sourceFrameId = tracks.maxOfOrNull(LightweightTrackState::sourceFrameId) ?: 0L,
                    sourceCaptureTimestampNs = tracks.maxOfOrNull {
                        it.sourceCaptureTimestampNanos
                    } ?: now,
                    publishedTimestampNs = now,
                    depthProfileId = latestDepthProfileId,
                    reason = "orientation_propagated_no_visual_correction",
                    tracks = tracks,
                )
            }
            status?.imuReceived(samples.size, accepted, rejected, propagatedTracks)
            publish()
        }
    }

    @Synchronized
    private fun acceptCamera(delivery: LiveSensorDelivery) {
        if (!active.get()) return
        val ingress = cameraIngress ?: return
        val generation = currentIngressGeneration
        val normalized = delivery.normalizedCameraCapture
        val disposition = ingress.acceptAuthenticatedLane(delivery.sensor)
        if (disposition in ACCEPTED_CAMERA_DISPOSITIONS &&
            delivery.sensor.cameraChunk.chunkIndex == 0 && normalized != null
        ) {
            currentCameraFrameId = delivery.sensor.cameraChunk.frameId
            currentCameraCaptureNs = normalized.hostMonotonicNs
            currentCameraUncertaintyNs = normalized.uncertaintyNs
        }
        if (disposition != StreamIngressDisposition.CAMERA_READY) return
        val frame = ingress.takeLatestCamera() ?: return
        val hasCorrelation = currentCameraFrameId == frame.frameId
        val captureNs = if (hasCorrelation) currentCameraCaptureNs else frame.captureMonotonicTimestampNs
        val uncertaintyNs = if (hasCorrelation) currentCameraUncertaintyNs else 0L
        val admittedToTimeline = sensorTimeline.acceptCamera(
            frame,
            captureNs,
            delivery.receiveMonotonicNs,
            uncertaintyNs,
        )
        status?.cameraReceived(admittedToTimeline)
        clearCameraCorrelation()
        if (!admittedToTimeline) {
            publish()
            return
        }
        val visionFrame = VisionFrame(
            frame.frameId,
            captureNs,
            frame.image.width,
            frame.image.height,
            synthetic = false,
            cameraIntrinsics = validatedCameraIntrinsics(frame),
        )
        val now = ElapsedHostClock.nowNanos()
        val maintainedTracks = trackMaintainer.snapshot(now)
        if (qnn == null) {
            status?.perceptionUnavailable("QNN_RUNTIME_NOT_READY", frameSkipped = true)
            publishMaintainedState(
                visionFrame,
                now,
                maintainedTracks,
                "bounded_prediction_no_visual_correction_qnn_not_ready",
            )
            publish()
            return
        }
        val automaticEnvironment = environmentCoordinator.mode() == EnvironmentSelectionMode.AUTOMATIC
        val vlmAvailable = automaticEnvironment && environmentVlm != null
        val vlmClient = environmentVlm
        vlmHtpAdmission.observe(vlmClient?.htpWorkState())
        val activeVlmWork = vlmHtpAdmission.hasActiveWork()
        val admission = modelAdmission.evaluate(
            visionFrame,
            maintainedTracks,
            requestOpportunisticVlm = vlmAvailable,
            vlmCapacityAvailable = vlmAvailable,
            suppressCameraCadenceMotionForVlmBootstrap =
                vlmAvailable && automaticEnvironmentVlmBootstrapPending && !activeVlmWork,
        )
        if (admission is LiveModelFrameDisposition.PredictionOnly) {
            publishMaintainedState(
                visionFrame,
                now,
                maintainedTracks,
                "bounded_prediction_no_visual_correction_${admission.reason.name.lowercase()}",
            )
            publish()
            return
        }
        admission as LiveModelFrameDisposition.AdmitForHtp
        check(admission.frame.frameId == visionFrame.frameId) { "model admission frame mismatch" }
        when (vlmHtpAdmission.decide(now, admission.reason)) {
            LiveVlmQnnAdmissionDecision.DEFER_QNN_FOR_VLM -> {
                publishMaintainedState(
                    visionFrame,
                    now,
                    maintainedTracks,
                    "bounded_prediction_no_visual_correction_vlm_in_flight",
                )
                publish()
                return
            }
            LiveVlmQnnAdmissionDecision.CANCEL_VLM_FOR_URGENT_QNN,
            LiveVlmQnnAdmissionDecision.CANCEL_VLM_AFTER_TIMEOUT,
            -> vlmClient?.cancelHtpWorkForQnn()
            LiveVlmQnnAdmissionDecision.RUN_QNN -> Unit
        }
        val pending = PendingLiveFrame(
            frame,
            visionFrame,
            captureNs,
            delivery.receiveMonotonicNs,
            uncertaintyNs,
            generation,
            admission.allowOpportunisticVlm,
            admission.reason,
        )
        if (pendingFrame.getAndSet(pending) != null) status?.cameraReplaced()
        scheduleDrain()
        publish()
    }

    @Synchronized
    private fun acceptMicrophone(delivery: LiveSensorDelivery) {
        if (!active.get()) return
        val ingress = microphoneIngress ?: return
        if (ingress.acceptAuthenticatedLane(delivery.sensor) != StreamIngressDisposition.MICROPHONE_READY) return
        val chunk = ingress.takeLatestMicrophone() ?: return
        status?.microphoneReceived(chunk.audioData.size(), sensorTimeline.acceptAudio(delivery))
        publish()
    }

    @Synchronized
    private fun acceptTouch(delivery: LiveSensorDelivery) {
        if (!active.get()) return
        val ingress = imuIngress ?: return
        if (ingress.acceptAuthenticatedLane(delivery.sensor) != StreamIngressDisposition.TOUCH_READY) return
        if (sensorTimeline.acceptTouch(delivery) == null) return
        val events = ingress.takeTouchEvents()
        val normalizedEvents = sensorTimeline.drainTouch()
        if (events.isEmpty() || events.size != normalizedEvents.size ||
            events.indices.any { events[it].eventId != normalizedEvents[it].event.eventId }
        ) {
            status?.linkDiagnostic(LiveLinkDiagnosticCode.SENSOR_TOUCH_OVERFLOW)
            publish(force = true)
            return
        }
        normalizedEvents.forEach { normalized ->
            if (!perceptionBus.publishTouch(normalized)) {
                status?.linkDiagnostic(LiveLinkDiagnosticCode.SENSOR_TOUCH_OVERFLOW)
            }
            focusTouchAdmission.commandFor(normalized)?.let(::handleFocusCommand)
        }
        status?.touchReceived(events.size)
        publish()
    }

    private fun scheduleDrain() {
        if (!workerScheduled.compareAndSet(false, true)) return
        executor?.execute {
            try {
                while (active.get()) {
                    val pending = pendingFrame.getAndSet(null) ?: break
                    processFrame(pending)
                    if (runMode == LiveMachineVisionRunMode.BOUNDED_DIAGNOSTIC &&
                        (status?.snapshot()?.inferenceAttempts ?: 0) >= maximumFrames
                    ) {
                        finish(LiveMachineVisionPhase.COMPLETE)
                        break
                    }
                }
            } finally {
                workerScheduled.set(false)
                if (active.get() && pendingFrame.get() != null) scheduleDrain()
            }
        }
    }

    private fun processFrame(pending: PendingLiveFrame) {
        if (!active.get() || !sessionGeneration.isCurrent(pending.generation)) return
        val activeQnn = qnn
        if (activeQnn == null) {
            status?.perceptionUnavailable("QNN_RUNTIME_NOT_READY", frameSkipped = true)
            val now = ElapsedHostClock.nowNanos()
            publishMaintainedState(
                pending.visionFrame,
                now,
                trackMaintainer.snapshot(now),
                "bounded_prediction_no_visual_correction_qnn_not_ready",
            )
            publish()
            return
        }
        val metadata = pending.frame
        try {
            // Recheck after queueing: a VLM offer may have started while this frame was pending.
            vlmHtpAdmission.observe(environmentVlm?.htpWorkState())
            when (vlmHtpAdmission.decide(ElapsedHostClock.nowNanos(), pending.refreshReason)) {
                LiveVlmQnnAdmissionDecision.DEFER_QNN_FOR_VLM -> {
                    val now = ElapsedHostClock.nowNanos()
                    publishMaintainedState(
                        pending.visionFrame,
                        now,
                        trackMaintainer.snapshot(now),
                        "bounded_prediction_no_visual_correction_vlm_in_flight",
                    )
                    publish()
                    return
                }
                LiveVlmQnnAdmissionDecision.CANCEL_VLM_FOR_URGENT_QNN,
                LiveVlmQnnAdmissionDecision.CANCEL_VLM_AFTER_TIMEOUT,
                -> environmentVlm?.cancelHtpWorkForQnn()
                LiveVlmQnnAdmissionDecision.RUN_QNN -> Unit
            }
            val visionFrame = pending.visionFrame
            val dispatch = prepareThenDispatchHtp(
                htpExecutionLease,
                QNN_HTP_LEASE_ACQUISITION_TIMEOUT_MILLIS,
                { !active.get() || !sessionGeneration.isCurrent(pending.generation) },
                prepare = { prepareQnnFrame(metadata, pending, visionFrame) },
                dispatch = { prepared ->
                    status?.inferenceStarted()
                    when (metadata.image.encoding) {
                        ImageEncoding.IMAGE_ENCODING_JPEG -> activeQnn.process(
                            requireNotNull(prepared.encoded), visionFrame, pending.generation,
                            prepared.selectDepthProfile,
                        )
                        ImageEncoding.IMAGE_ENCODING_RGB8 -> activeQnn.process(
                            requireNotNull(prepared.raw), visionFrame, pending.generation,
                            prepared.selectDepthProfile,
                        )
                        else -> throw IllegalArgumentException("unsupported live camera encoding")
                    }
                },
            )
            if (dispatch is PreparedHtpDispatchOutcome.Refused) {
                if (!active.get() || !sessionGeneration.isCurrent(pending.generation)) return
                status?.perceptionUnavailable(
                    "HTP_LEASE_${dispatch.refusal.reason.name}",
                    frameSkipped = true,
                )
                val now = ElapsedHostClock.nowNanos()
                publishMaintainedState(
                    pending.visionFrame,
                    now,
                    trackMaintainer.snapshot(now),
                    "bounded_prediction_no_visual_correction_htp_${dispatch.refusal.reason.name.lowercase()}",
                )
                publish()
                return
            }
            dispatch as PreparedHtpDispatchOutcome.Completed
            val prepared = dispatch.prepared
            val result = dispatch.result
            if (active.get() && sessionGeneration.isCurrent(pending.generation) &&
                prepared.automaticEnvironment && pending.opportunisticVlmAllowed
            ) {
                prepared.encoded?.let { environmentVlm?.offer(it) }
                prepared.raw?.let { environmentVlm?.offer(it) }
            }
            if (result == null) {
                status?.environmentPending()
                val environmentPendingNow = ElapsedHostClock.nowNanos()
                publishMaintainedState(
                    visionFrame,
                    environmentPendingNow,
                    trackMaintainer.snapshot(environmentPendingNow),
                    "model_keyframe_environment_pending_no_visual_correction",
                )
                publish()
                return
            }
            if (!sessionGeneration.isCurrent(pending.generation) || !active.get()) return
            val now = ElapsedHostClock.nowNanos()
            if (!resultFreshnessGate.accept(
                    pending.normalizedCaptureNs,
                    now,
                    pending.clockUncertaintyNs,
                )
            ) {
                status?.perceptionResultRejectedStale()
                publishMaintainedState(
                    visionFrame,
                    now,
                    trackMaintainer.snapshot(now),
                    "stale_model_result_rejected_no_visual_correction",
                )
                publish()
                return
            }
            val frameExtrinsic = validatedHeadCameraExtrinsic(metadata)
            val fusion = metricFusion.process(
                visionFrame,
                result,
                now,
                frameExtrinsic,
            )
            latestDepthProfileId = result.selectedDepthProfileId
            if (frameExtrinsic != null && !trackMaintainer.configureHeadCameraExtrinsic(frameExtrinsic)) {
                throw IllegalStateException("head camera extrinsic changed within session")
            }
            val maintained = if (fusion.cameraMetricOutputAvailable) {
                trackMaintainer.updateKeyframe(visionFrame, fusion.metricTracks, fusion.capturePose)
            } else {
                null
            }
            val maintainedTracks = maintained?.tracks ?: trackMaintainer.snapshot(now)
            publishTrackedState(
                sourceFrameId = visionFrame.frameId,
                sourceCaptureTimestampNs = visionFrame.captureMonotonicTimestampNanos,
                publishedTimestampNs = now,
                depthProfileId = result.selectedDepthProfileId,
                reason = maintained?.let { "model_keyframe_${it.reason}" }
                    ?: "model_keyframe_metric_unavailable_no_visual_correction",
                tracks = maintainedTracks,
                validity = PerceptionValidityReason.PERCEPTION_READY,
            )
            status?.inferenceSucceeded(
                (now - pending.normalizedCaptureNs).coerceAtLeast(0L),
                (pending.receiveNs - pending.normalizedCaptureNs).coerceAtLeast(0L),
                pending.clockUncertaintyNs,
                result,
                fusion,
            )
            publish()
        } catch (error: Throwable) {
            fail(failureCode(error))
        }
    }

    private fun prepareQnnFrame(
        metadata: FramePayload,
        pending: PendingLiveFrame,
        visionFrame: VisionFrame,
    ): PreparedLiveQnnFrame {
        val bytes = metadata.frameData.toByteArray()
        val encoded = if (metadata.image.encoding == ImageEncoding.IMAGE_ENCODING_JPEG) {
            EncodedJpegFrame(
                metadata.frameId, pending.normalizedCaptureNs,
                metadata.image.width, metadata.image.height, bytes,
            )
        } else null
        val raw = if (metadata.image.encoding == ImageEncoding.IMAGE_ENCODING_RGB8) {
            RawRgbFrame(
                metadata.frameId, pending.normalizedCaptureNs,
                metadata.image.width, metadata.image.height,
                metadata.image.rowStrideBytes, bytes,
            )
        } else null
        val automaticEnvironment = environmentCoordinator.mode() == EnvironmentSelectionMode.AUTOMATIC
        if (automaticEnvironment && pending.opportunisticVlmAllowed) {
            encoded?.let { environmentVlm?.observe(it) }
            raw?.let { environmentVlm?.observe(it) }
        }
        val vlmEnvironmentSignal = if (automaticEnvironment && pending.opportunisticVlmAllowed) {
            environmentVlm?.latestFor(visionFrame)
        } else null
        if (vlmEnvironmentSignal != null) automaticEnvironmentVlmBootstrapPending = false
        val selector: (List<org.conceptflow.mpl.host.vision.SceneSemanticDetection>) ->
            org.conceptflow.mpl.host.vision.MachineVisionModelProfile? = { detections ->
            val decision = environmentCoordinator.routeFrame(
                visionFrame, detections, vlmEnvironmentSignal,
                ElapsedHostClock.nowNanos(), bothProfilesAvailable = true,
            )
            val diagnostic = "reason=${decision.reason} profile=${decision.selectedProfile?.id ?: "none"}"
            if (diagnostic != lastEnvironmentDecisionDiagnostic) {
                lastEnvironmentDecisionDiagnostic = diagnostic
                Log.i(
                    TAG,
                    "state=environment_route $diagnostic confidence=${decision.confidence} " +
                        "vlmEvidence=${vlmEnvironmentSignal != null}",
                )
            }
            decision.selectedProfile
        }
        return PreparedLiveQnnFrame(encoded, raw, automaticEnvironment, selector)
    }

    private fun logHtpLeaseTelemetry(event: HtpLeaseTelemetry) {
        Log.i(
            TAG,
            "state=htp_lease workload=${event.workload.name.lowercase()} acquired=${event.acquired} " +
                "reason=${event.refusalReason?.name?.lowercase() ?: "released"} " +
                "waitMs=${event.waitNanos / 1_000_000L} holdMs=${event.holdNanos?.div(1_000_000L) ?: 0L}",
        )
    }

    private fun publishMaintainedState(
        triggerFrame: VisionFrame,
        nowNanos: Long,
        tracks: List<LightweightTrackState>,
        reason: String,
    ) {
        publishTrackedState(
            sourceFrameId = triggerFrame.frameId,
            sourceCaptureTimestampNs = triggerFrame.captureMonotonicTimestampNanos,
            publishedTimestampNs = nowNanos,
            depthProfileId = latestDepthProfileId,
            reason = reason,
            tracks = tracks,
        )
    }

    private fun publishTrackedState(
        sourceFrameId: Long,
        sourceCaptureTimestampNs: Long,
        publishedTimestampNs: Long,
        depthProfileId: String,
        reason: String,
        tracks: List<LightweightTrackState>,
        validity: PerceptionValidityReason = if (tracks.isEmpty()) {
            PerceptionValidityReason.SENSOR_STREAM_ACTIVE
        } else {
            PerceptionValidityReason.PERCEPTION_READY
        },
    ) {
        val world = perceptionBus.publishTrackedPerception(
            sourceFrameId,
            sourceCaptureTimestampNs,
            publishedTimestampNs,
            depthProfileId,
            reason,
            tracks,
            validity,
        )
        publishFocusTransition(
            spatialFocus.updateTracks(
                currentIngressGeneration,
                world.revision,
                publishedTimestampNs,
                tracks,
            ),
        )
    }

    @Synchronized
    private fun publishFocusTransition(state: SpatialFocusState): SpatialFocusState {
        val published = perceptionBus.publishFocus(state)
        onFocusState(published)
        if (published.dwell == SpatialFocusDwell.PENDING) {
            val expectedGeneration = published.focusGeneration
            if (scheduledDwellGeneration == expectedGeneration) return published
            scheduledDwellGeneration = expectedGeneration
            val delay = (published.dwellDeadlineTimestampNanos - ElapsedHostClock.nowNanos()).coerceAtLeast(0L)
            executor?.schedule(
                {
                    val current = spatialFocus.current()
                    if (current?.focusGeneration == expectedGeneration &&
                        current.dwell == SpatialFocusDwell.PENDING
                    ) {
                        publishFocusTransition(spatialFocus.advance(ElapsedHostClock.nowNanos()))
                    }
                    if (scheduledDwellGeneration == expectedGeneration) scheduledDwellGeneration = 0L
                },
                delay,
                TimeUnit.NANOSECONDS,
            )
        }
        return published
    }

    /** Accepts only the exact still-current focus request; late IPC answers are intentionally silent. */
    @Synchronized
    private fun handleFocusedVqaOutcome(outcome: LocalVlmFocusedObjectOutcome) {
        if (!active.get()) return
        val local = when (outcome) {
            is LocalVlmFocusedObjectOutcome.Answered -> outcome.value.correlation
            is LocalVlmFocusedObjectOutcome.Rejected -> outcome.correlation
        }
        val state = spatialFocus.current() ?: return
        val correlation = FocusedVqaCorrelation(
            requestId = local.focusRequestId,
            sessionGeneration = local.sessionGeneration,
            snapshotId = local.snapshotId,
            focusGeneration = local.focusGeneration,
            stableTrackId = local.stableTrackId,
            sourceFrameId = local.sourceFrameId,
        )
        if (!state.matchesFocusedVqaSource(local)) {
            if (spatialFocus.failVqa(correlation, FocusedVqaRejection.STALE_FRAME, ElapsedHostClock.nowNanos())) {
                spatialFocus.current()?.let(::publishFocusTransition)
            }
            return
        }
        val changed = when (outcome) {
            is LocalVlmFocusedObjectOutcome.Answered -> spatialFocus.completeVqa(
                correlation,
                outcome.value.answer,
                ElapsedHostClock.nowNanos(),
            )
            is LocalVlmFocusedObjectOutcome.Rejected -> spatialFocus.failVqa(
                correlation,
                outcome.reason.toFocusRejection(),
                ElapsedHostClock.nowNanos(),
            )
        }
        if (changed) spatialFocus.current()?.let(::publishFocusTransition)
    }

    private fun clearCameraCorrelation() {
        currentCameraFrameId = 0L
        currentCameraCaptureNs = 0L
        currentCameraUncertaintyNs = 0L
    }

    private fun fail(code: String) {
        status?.failed(code)
        finish(LiveMachineVisionPhase.FAILED)
    }

    @Synchronized
    private fun finish(phase: LiveMachineVisionPhase) {
        if (!active.getAndSet(false)) return
        status?.phase(phase)
        closeResources()
        terminalPublication.publishOnce { publish(force = true) }
    }

    @Synchronized
    private fun publish(force: Boolean = false) {
        val now = ElapsedHostClock.nowNanos()
        if (!force && lastPublishedNs != 0L && now - lastPublishedNs < STATUS_UPDATE_INTERVAL_NS) return
        lastPublishedNs = now
        status?.snapshot()?.let(onStatus)
    }

    private fun failureCode(error: Throwable): String = when (error) {
        is QnnInferenceException -> "QNN_${error.failure.code.name}"
        is SecurityException -> "AUTHENTICATION_FAILED"
        is java.net.SocketTimeoutException -> "LINK_TIMEOUT"
        is java.io.IOException -> "LINK_IO_FAILED"
        else -> "LIVE_TEST_FAILED"
    }

    @Synchronized
    private fun closeResources() {
        startupGate.cancel()
        pendingFrame.set(null)
        startupFuture?.cancel(true)
        startupFuture = null
        runCatching { server?.closeAsync {} }
        focusedVqaGateway?.let { gateway ->
            runCatching { gateway.close() }
            focusedVqaRouter.clear(gateway)
        }
        environmentVlm?.cancelOutstanding()
        focusedVqaFrames.reset()
        qnn?.resetTracking()
        runCatching { qnn?.close() }
        runCatching { environmentVlm?.close() }
        server = null
        qnn = null
        environmentVlm = null
        focusedVqaGateway = null
        cameraIngress = null
        imuIngress = null
        microphoneIngress = null
        microphoneWindowGeneration = Math.addExact(microphoneWindowGeneration, 1L)
        currentSessionBinding = null
        metricFusion.reset()
        trackMaintainer.reset()
        modelAdmission.reset()
        vlmHtpAdmission.reset()
        latestDepthProfileId = ""
        automaticEnvironmentVlmBootstrapPending = false
        sensorTimeline.reset()
        val stoppedNow = ElapsedHostClock.nowNanos()
        perceptionBus.invalidate(PerceptionValidityReason.STOPPED, stoppedNow)
        publishFocusTransition(spatialFocus.reset(0L, stoppedNow, perceptionBus.stats().latestRevision))
        currentIngressGeneration = 0L
        clearCameraCorrelation()
        executor?.shutdownNow()
        executor = null
        startupExecutor?.shutdownNow()
        startupExecutor = null
    }

    @Synchronized override fun close() = finish(LiveMachineVisionPhase.STOPPED)

    private data class PendingLiveFrame(
        val frame: FramePayload,
        val visionFrame: VisionFrame,
        val normalizedCaptureNs: Long,
        val receiveNs: Long,
        val clockUncertaintyNs: Long,
        val generation: Long,
        val opportunisticVlmAllowed: Boolean,
        val refreshReason: SemanticDepthRefreshReason,
    )

    private data class PreparedLiveQnnFrame(
        val encoded: EncodedJpegFrame?,
        val raw: RawRgbFrame?,
        val automaticEnvironment: Boolean,
        val selectDepthProfile: (List<org.conceptflow.mpl.host.vision.SceneSemanticDetection>) ->
            org.conceptflow.mpl.host.vision.MachineVisionModelProfile?,
    )

    private companion object {
        const val TAG = "ConceptFlowLiveVision"
        const val STATUS_UPDATE_INTERVAL_NS = 1_000_000_000L
        const val QNN_HTP_LEASE_ACQUISITION_TIMEOUT_MILLIS = 250L
        val ACCEPTED_CAMERA_DISPOSITIONS = setOf(
            StreamIngressDisposition.CAMERA_PARTIAL,
            StreamIngressDisposition.CAMERA_READY,
        )
    }
}

/** Rejects results whose worst-case clock-normalized age exceeds the game-facing state budget. */
class LivePerceptionResultFreshnessGate(
    private val maximumResultAgeNanos: Long = DEFAULT_MAXIMUM_RESULT_AGE_NANOS,
) {
    init {
        require(maximumResultAgeNanos in 100_000_000L..5_000_000_000L)
    }

    fun accept(captureNanos: Long, completedNanos: Long, clockUncertaintyNanos: Long): Boolean {
        if (captureNanos < 0L || completedNanos < captureNanos || clockUncertaintyNanos < 0L) return false
        val observedAge = completedNanos - captureNanos
        val worstCaseAge = if (Long.MAX_VALUE - observedAge < clockUncertaintyNanos) {
            Long.MAX_VALUE
        } else {
            observedAge + clockUncertaintyNanos
        }
        return worstCaseAge <= maximumResultAgeNanos
    }

    companion object {
        // Current physical p95 is about 1.04 seconds; 1.5 seconds permits measured steady-state
        // variance but rejects multi-second graph initialization/profile-switch results.
        const val DEFAULT_MAXIMUM_RESULT_AGE_NANOS = 1_500_000_000L
    }
}
