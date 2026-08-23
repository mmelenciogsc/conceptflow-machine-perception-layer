// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host

import android.content.Context
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
import org.conceptflow.mpl.host.vision.AndroidJpegDecoder
import org.conceptflow.mpl.host.vision.CameraIntrinsics
import org.conceptflow.mpl.host.vision.CameraIntrinsicsSource
import org.conceptflow.mpl.host.vision.CameraIntrinsicsStandardDeviation
import org.conceptflow.mpl.host.vision.CameraLensDistortionModel
import org.conceptflow.mpl.host.vision.EncodedJpegFrame
import org.conceptflow.mpl.host.vision.EnvironmentDepthCoordinator
import org.conceptflow.mpl.host.vision.EnvironmentSelectionMode
import org.conceptflow.mpl.host.vision.GnssQualitySample
import org.conceptflow.mpl.host.vision.LiveMetricCalibrationState
import org.conceptflow.mpl.host.vision.LiveMetricFusionReason
import org.conceptflow.mpl.host.vision.LiveMetricFusionResult
import org.conceptflow.mpl.host.vision.LiveMetricTemporalFusion
import org.conceptflow.mpl.host.vision.LiveImuPoseMapper
import org.conceptflow.mpl.host.vision.MachineVisionModelProfiles
import org.conceptflow.mpl.host.vision.MetricDepthCalibrationProvider
import org.conceptflow.mpl.host.vision.MetricDepthProvenanceKind
import org.conceptflow.mpl.host.vision.NativeQnnModelSessionFactory
import org.conceptflow.mpl.host.vision.QnnFailureCode
import org.conceptflow.mpl.host.vision.QnnInferenceException
import org.conceptflow.mpl.host.vision.QnnLiveFrameExecutor
import org.conceptflow.mpl.host.vision.QnnLiveFrameResult
import org.conceptflow.mpl.host.vision.QnnRuntimeBundle
import org.conceptflow.mpl.host.vision.VisionFrame
import org.conceptflow.mpl.host.vision.VerifiedHeadCameraExtrinsic
import org.conceptflow.mpl.transport.LiveLinkDisconnectReason
import org.conceptflow.mpl.transport.LiveLinkCloseEvidence
import org.conceptflow.mpl.transport.LiveLinkDiagnosticCode
import org.conceptflow.mpl.transport.LiveLinkEndpointRole
import org.conceptflow.mpl.transport.LiveLinkPrivateConfig
import org.conceptflow.mpl.transport.LIVE_LINK_DIAGNOSTIC_SCHEMA_VERSION
import org.conceptflow.mpl.transport.LiveLinkSession
import org.conceptflow.mpl.transport.LiveSensorDelivery
import org.conceptflow.mpl.transport.PocoLiveLinkObserver
import org.conceptflow.mpl.transport.PocoLiveLinkServer
import org.conceptflow.mpl.v1.CameraIntrinsicsProvenance
import org.conceptflow.mpl.v1.FramePayload

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

/** The host pinhole projector must never silently discard a non-identity lens model. */
private fun List<Double>.isSupportedZeroBrownConradyDistortion(): Boolean =
    size == BROWN_CONRADY_PARAMETER_COUNT && all { it.isFinite() && it == 0.0 }

private const val BROWN_CONRADY_PARAMETER_COUNT = 5

enum class LiveMachineVisionPhase { IDLE, OPENING_QNN_HTP, LISTENING, STREAMING, COMPLETE, FAILED, STOPPED }

data class LiveMachineVisionTestSpec(
    val environmentMode: EnvironmentSelectionMode = EnvironmentSelectionMode.AUTOMATIC,
    val durationSeconds: Int = 30,
    val maximumFrames: Int = 150,
) {
    init {
        require(durationSeconds in 5..300) { "durationSeconds is outside its bound" }
        require(maximumFrames in 1..1_500) { "maximumFrames is outside its bound" }
    }
}

data class LiveMachineVisionStatus(
    val phase: LiveMachineVisionPhase,
    val selectedProfile: String,
    val framesReceived: Long,
    val framesDroppedBeforeInference: Long,
    val imuBatchesReceived: Long,
    val imuSamplesReceived: Long,
    val imuPoseSamplesAccepted: Long,
    val imuPoseSamplesRejected: Long,
    val inferenceAttempts: Long,
    val inferenceSuccesses: Long,
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
        append("Live Machine Vision phase: ").append(phase.name.lowercase()).append(". ")
        append("Link diagnostic schema: ").append(LIVE_LINK_DIAGNOSTIC_SCHEMA_VERSION).append(". ")
        append("Depth profile: ").append(selectedProfile).append(". ")
        append("Frames received: ").append(framesReceived)
        append("; inferred: ").append(inferenceSuccesses).append(" of ").append(inferenceAttempts)
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
        lastLinkDiagnosticCode?.let { append("Link diagnostic: ").append(it).append(". ") }
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
    private var framesDropped = 0L
    private var imuBatches = 0L
    private var imuSamples = 0L
    private var imuPosesAccepted = 0L
    private var imuPosesRejected = 0L
    private var attempts = 0L
    private var successes = 0L
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
    @Synchronized fun cameraReceived() { framesReceived = Math.addExact(framesReceived, 1) }
    @Synchronized fun cameraReplaced() { framesDropped = Math.addExact(framesDropped, 1) }
    @Synchronized fun inferenceStarted() { attempts = Math.addExact(attempts, 1) }
    @Synchronized fun environmentPending() { environmentPending = Math.addExact(environmentPending, 1) }
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
        framesDropped,
        imuBatches,
        imuSamples,
        imuPosesAccepted,
        imuPosesRejected,
        attempts,
        successes,
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
    private val onStatus: (LiveMachineVisionStatus) -> Unit,
) : AutoCloseable {
    private val active = AtomicBoolean(false)
    private val pendingFrame = AtomicReference<PendingLiveFrame?>(null)
    private val workerScheduled = AtomicBoolean(false)
    private val sessionGeneration = LiveSessionGeneration()
    private val startupGate = LiveStartupGate()
    private val terminalPublication = LiveTerminalPublicationGate()
    private val reconnectPolicy = LiveReconnectPolicy()
    private val metricFusion = LiveMetricTemporalFusion(calibrationProvider, headCameraExtrinsic)
    private var executor: ScheduledExecutorService? = null
    private var startupExecutor: ExecutorService? = null
    private var startupFuture: Future<*>? = null
    private var server: PocoLiveLinkServer? = null
    private var qnn: QnnLiveFrameExecutor? = null
    @Volatile private var cameraIngress: GlassesStreamIngress? = null
    @Volatile private var imuIngress: GlassesStreamIngress? = null
    private var status: LiveMachineVisionStatusAccumulator? = null
    private var maximumFrames = 0
    private var environmentCoordinator = EnvironmentDepthCoordinator()
    private var currentCameraFrameId = 0L
    private var currentCameraCaptureNs = 0L
    private var currentCameraUncertaintyNs = 0L
    private var currentIngressGeneration = 0L
    private var lastPublishedNs = 0L

    @Synchronized
    fun start(spec: LiveMachineVisionTestSpec) {
        check(active.compareAndSet(false, true)) { "a live Machine Vision test is already active" }
        terminalPublication.reset()
        val startupToken = startupGate.begin()
        environmentCoordinator = EnvironmentDepthCoordinator().also { it.setMode(spec.environmentMode) }
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
        try {
            val sessionFactory = NativeQnnModelSessionFactory(
                QnnRuntimeBundle(context.filesDir.resolve("qnn-runtime")),
                context.filesDir.resolve("models"),
            )
            openedQnn = QnnLiveFrameExecutor(sessionFactory, AndroidJpegDecoder(), ElapsedHostClock::nowNanos)
            if (!active.get() || !startupGate.mayPublish(startupToken)) return
            val configuration = context.noBackupFilesDir.resolve("live-link/live-link.properties")
                .inputStream().buffered().use { LiveLinkPrivateConfig.parse(it, LiveLinkEndpointRole.POCO_HOST) }
            openedServer = PocoLiveLinkServer.fromConfig(configuration)
            synchronized(this) {
                if (!active.get() || !startupGate.mayPublish(startupToken)) return
                qnn = openedQnn
                server = openedServer
                openedQnn = null
                openedServer = null
            }
            accumulator.phase(LiveMachineVisionPhase.LISTENING)
            publish(force = true)
            requireNotNull(server).start(observer())
            if (!active.get() || !startupGate.mayPublish(startupToken)) return
            scheduled.schedule(
                { finish(LiveMachineVisionPhase.COMPLETE) },
                spec.durationSeconds.toLong(),
                TimeUnit.SECONDS,
            )
        } catch (error: Throwable) {
            if (active.get() && startupGate.mayPublish(startupToken)) {
                accumulator.failed(failureCode(error))
                publish(force = true)
                finish(LiveMachineVisionPhase.FAILED)
            }
        } finally {
            runCatching { openedServer?.close() }
            runCatching { openedQnn?.close() }
            if (startupGate.mayPublish(startupToken)) startupExecutor?.shutdown()
        }
    }

    @Synchronized fun snapshot(): LiveMachineVisionStatus? = status?.snapshot()
    fun updateGnss(sample: GnssQualitySample): Boolean = environmentCoordinator.updateGnss(sample)

    private fun observer(): PocoLiveLinkObserver = object : PocoLiveLinkObserver {
        override fun onSessionReady(session: LiveLinkSession) {
            acceptSession(session)
        }

        override fun onSensor(delivery: LiveSensorDelivery) {
            if (!active.get()) return
            when {
                delivery.sensor.hasImuBatch() -> acceptImu(delivery)
                delivery.sensor.hasCameraChunk() -> acceptCamera(delivery)
                else -> fail("LIVE_INVALID_SENSOR_PAYLOAD")
            }
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
                LiveReconnectDecision.RETRY -> recoverForReconnect()
                LiveReconnectDecision.COMPLETE -> executor?.execute {
                    finish(LiveMachineVisionPhase.COMPLETE)
                }
                LiveReconnectDecision.FAIL_CLOSED -> executor?.execute { fail("LINK_${reason.name}") }
                LiveReconnectDecision.IGNORE -> Unit
            }
        }
    }

    @Synchronized
    private fun acceptSession(session: LiveLinkSession) {
        if (!active.get()) return
        status?.sessionReady()
        metricFusion.reset()
        qnn?.resetTracking()
        currentIngressGeneration = sessionGeneration.advance()
        cameraIngress = GlassesStreamIngress(
            session.binding.sessionId, session.binding.leaseId, false, ElapsedHostClock,
        )
        imuIngress = GlassesStreamIngress(
            session.binding.sessionId, session.binding.leaseId, false, ElapsedHostClock,
        )
        clearCameraCorrelation()
        status?.phase(LiveMachineVisionPhase.STREAMING)
        publish(force = true)
    }

    @Synchronized
    private fun recoverForReconnect() {
        if (!active.get()) return
        status?.linkInterrupted()
        sessionGeneration.advance()
        currentIngressGeneration = 0L
        pendingFrame.set(null)
        cameraIngress = null
        imuIngress = null
        metricFusion.reset()
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
            var rejected = 0
            var propagatedTracks = 0
            val samples = delivery.sensor.imuBatch.samplesList
            val timings = delivery.normalizedImuSamples.associateBy { it.sampleIndex }
            samples.forEachIndexed { index, reading ->
                val timestamp = timings[index]?.poseTimestamp?.hostMonotonicNs
                val pose = timestamp?.let { LiveImuPoseMapper.map(reading, it) }
                if (pose == null) {
                    rejected += 1
                } else {
                    val update = metricFusion.acceptPose(pose)
                    if (update.accepted) accepted += 1 else rejected += 1
                    propagatedTracks = update.temporalTrackCount
                }
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
        status?.cameraReceived()
        val hasCorrelation = currentCameraFrameId == frame.frameId
        val pending = PendingLiveFrame(
            frame,
            if (hasCorrelation) currentCameraCaptureNs else frame.captureMonotonicTimestampNs,
            delivery.receiveMonotonicNs,
            if (hasCorrelation) currentCameraUncertaintyNs else 0L,
            generation,
        )
        clearCameraCorrelation()
        if (pendingFrame.getAndSet(pending) != null) status?.cameraReplaced()
        scheduleDrain()
        publish()
    }

    private fun scheduleDrain() {
        if (!workerScheduled.compareAndSet(false, true)) return
        executor?.execute {
            try {
                while (active.get()) {
                    val pending = pendingFrame.getAndSet(null) ?: break
                    processFrame(pending)
                    if ((status?.snapshot()?.inferenceAttempts ?: 0) >= maximumFrames) {
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
        status?.inferenceStarted()
        val metadata = pending.frame
        val encoded = EncodedJpegFrame(
            metadata.frameId,
            pending.normalizedCaptureNs,
            metadata.image.width,
            metadata.image.height,
            metadata.frameData.toByteArray(),
        )
        try {
            val visionFrame = VisionFrame(
                encoded.frameId,
                encoded.captureMonotonicTimestampNanos,
                encoded.width,
                encoded.height,
                synthetic = false,
                cameraIntrinsics = validatedCameraIntrinsics(metadata),
            )
            val result = requireNotNull(qnn).process(encoded, visionFrame) { detections ->
                environmentCoordinator.routeFrame(
                    visionFrame,
                    detections,
                    nowNanos = ElapsedHostClock.nowNanos(),
                    bothProfilesAvailable = true,
                ).selectedProfile
            }
            if (result == null) {
                status?.environmentPending()
                publish()
                return
            }
            if (!sessionGeneration.isCurrent(pending.generation) || !active.get()) return
            val now = ElapsedHostClock.nowNanos()
            val fusion = metricFusion.process(visionFrame, result, now)
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
        qnn?.resetTracking()
        runCatching { qnn?.close() }
        server = null
        qnn = null
        cameraIngress = null
        imuIngress = null
        metricFusion.reset()
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
        val normalizedCaptureNs: Long,
        val receiveNs: Long,
        val clockUncertaintyNs: Long,
        val generation: Long,
    )

    private companion object {
        const val STATUS_UPDATE_INTERVAL_NS = 1_000_000_000L
        val ACCEPTED_CAMERA_DISPOSITIONS = setOf(
            StreamIngressDisposition.CAMERA_PARTIAL,
            StreamIngressDisposition.CAMERA_READY,
        )
    }
}
