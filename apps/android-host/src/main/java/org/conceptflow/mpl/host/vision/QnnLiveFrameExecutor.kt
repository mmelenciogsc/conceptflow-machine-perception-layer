// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import kotlin.math.max

enum class LiveMetricCalibrationState {
    CALIBRATED_INTRINSICS_PRESENT,
    DERIVED_INTRINSICS_PRESENT,
    UNCALIBRATED_INTRINSICS_MISSING,
    PROFILE_BOUND_METRIC_TRACKS_AVAILABLE,
    PROFILE_BOUND_NATIVE_METRIC_AVAILABLE,
    PROFILE_BOUND_NATIVE_METRIC_CALIBRATED_INTRINSICS_PRESENT,
    PROFILE_BOUND_NATIVE_METRIC_DERIVED_INTRINSICS_PRESENT,
}

data class QnnLiveFrameResult(
    val frameId: Long,
    val selectedDepthProfileId: String,
    val segmentedObjectCount: Int,
    val finiteYoloValues: Int,
    val finitePositiveDepthValues: Int,
    val decodeLatencyNanos: Long,
    val yoloPreprocessLatencyNanos: Long,
    val segmentationLatencyNanos: Long,
    val yoloPostprocessLatencyNanos: Long,
    val modelSetupLatencyNanos: Long,
    val depthPreprocessLatencyNanos: Long,
    val depthLatencyNanos: Long,
    val depthPostprocessLatencyNanos: Long,
    val totalLatencyNanos: Long,
    val calibrationState: LiveMetricCalibrationState,
    val inference: MachineVisionInference,
) {
    init {
        require(segmentedObjectCount in 0..64)
        require(finiteYoloValues == YOLO_FINITE_VALUE_COUNT)
        require(finitePositiveDepthValues == DEPTH_FINITE_VALUE_COUNT)
        require(inference.frameId == frameId)
        require(inference.depthProfileId == selectedDepthProfileId)
        require(inference.observations.size == segmentedObjectCount)
        val timingsAreNonNegative = decodeLatencyNanos >= 0L && yoloPreprocessLatencyNanos >= 0L &&
            segmentationLatencyNanos >= 0L && yoloPostprocessLatencyNanos >= 0L &&
            modelSetupLatencyNanos >= 0L && depthPreprocessLatencyNanos >= 0L &&
            depthLatencyNanos >= 0L && depthPostprocessLatencyNanos >= 0L && totalLatencyNanos >= 0L
        require(timingsAreNonNegative) { "live QNN timing values must be non-negative" }
    }

    companion object {
        const val YOLO_FINITE_VALUE_COUNT = 300 * 38 + 160 * 160 * 32
        const val DEPTH_FINITE_VALUE_COUNT = 392 * 392
    }
}

/**
 * Bounded live-test executor that opens exactly the pinned YOLOE-26S fixed-vocabulary graph and one selected 392 metric-depth
 * graph. Depth executes even when segmentation finds no eligible objects, so HTP validation cannot
 * accidentally become a YOLO-only test.
 */
class QnnLiveFrameExecutor(
    private val sessionFactory: QnnModelSessionFactory,
    private val decoder: JpegFrameDecoder,
    private val clockNanos: () -> Long,
) : AutoCloseable {
    private val segmentationSession: QnnModelSession
    private val tracker = BoundedYoloTracker()
    private var depthSession: QnnModelSession? = null
    private var activeDepthProfile: MachineVisionModelProfile? = null
    private var closed = false

    init {
        segmentationSession = sessionFactory.open(MachineVisionModelProfiles.yoloe26sBvi)
    }

    @Synchronized
    fun process(
        frame: EncodedJpegFrame,
        visionFrame: VisionFrame,
        selectDepthProfile: (List<SceneSemanticDetection>) -> MachineVisionModelProfile?,
    ): QnnLiveFrameResult? {
        check(!closed) { "live QNN frame executor is closed" }
        require(frame.matches(visionFrame)) { "encoded and vision frame metadata do not correlate" }
        val frameStarted = clockNanos()
        val image = decoder.decode(frame.jpeg)
        val decodeCompleted = clockNanos()
        require(image.width == frame.width && image.height == frame.height) {
            "decoded JPEG dimensions do not match frame metadata"
        }
        return processDecoded(
            frame.frameId,
            visionFrame,
            image,
            frameStarted,
            decodeCompleted,
            selectDepthProfile,
        )
    }

    @Synchronized
    fun process(
        frame: RawRgbFrame,
        visionFrame: VisionFrame,
        selectDepthProfile: (List<SceneSemanticDetection>) -> MachineVisionModelProfile?,
    ): QnnLiveFrameResult? {
        check(!closed) { "live QNN frame executor is closed" }
        require(frame.matches(visionFrame)) { "RGB and vision frame metadata do not correlate" }
        val frameStarted = clockNanos()
        val image = RgbImage(frame.width, frame.height, frame.rgb)
        val decodeCompleted = clockNanos()
        return processDecoded(
            frame.frameId,
            visionFrame,
            image,
            frameStarted,
            decodeCompleted,
            selectDepthProfile,
        )
    }

    private fun processDecoded(
        frameId: Long,
        visionFrame: VisionFrame,
        image: RgbImage,
        frameStarted: Long,
        decodeCompleted: Long,
        selectDepthProfile: (List<SceneSemanticDetection>) -> MachineVisionModelProfile?,
    ): QnnLiveFrameResult? {

        val segmentationInput = VisionTensorPreprocessor.yolo640(image)
        val segmentationPreprocessingCompleted = clockNanos()
        val segmentationOutputs = segmentationSession.execute(segmentationInput.bytes).outputs
        val segmentationCompleted = clockNanos()
        require(segmentationOutputs.size == 2) { "YOLO graph returned an unexpected tensor count" }
        val detections = YoloFixedVocabularyPostprocessor.process(
            segmentationOutputs[0],
            segmentationOutputs[1],
            segmentationInput.transform,
        )
        val trackedDetections = tracker.update(frameId, detections)
        val segmentationPostprocessingCompleted = clockNanos()

        val selectedDepthProfile = selectDepthProfile(
            trackedDetections.map { SceneSemanticDetection(it.detection.classId, it.detection.confidence) },
        ) ?: return null
        require(selectedDepthProfile == MachineVisionModelProfiles.depthIndoorBalanced ||
            selectedDepthProfile == MachineVisionModelProfiles.depthOutdoorBalanced
        ) { "live validation permits only a selected indoor/outdoor 392 graph" }
        if (activeDepthProfile != selectedDepthProfile) {
            depthSession?.close()
            depthSession = null
            activeDepthProfile = null
            depthSession = sessionFactory.open(selectedDepthProfile)
            activeDepthProfile = selectedDepthProfile
        }
        val modelSetupCompleted = clockNanos()

        val depthInput = VisionTensorPreprocessor.metricDepth392(image)
        val depthPreprocessingCompleted = clockNanos()
        val depthOutputs = requireNotNull(depthSession).execute(depthInput.bytes).outputs
        val depthCompleted = clockNanos()
        require(depthOutputs.size == 1) { "depth graph returned an unexpected tensor count" }
        val depth = Float32TensorCodec.decodeLittleEndian(depthOutputs.single(), 392 * 392)
        val nativeMetricSemantics = requireNotNull(
            OfficialDepthAnythingV2MetricSemanticsProvider.resolve(selectedDepthProfile),
        ) { "selected depth profile has no pinned native metric output contract" }
        require(depth.all { nativeMetricSemantics.acceptsRawDepthValue(it.toDouble()) }) {
            "metric-depth graph returned a non-finite, non-positive, or out-of-range value"
        }
        val depthSamples = DepthMaskSampler.sample(depth, depthInput.transform, trackedDetections)
        val frameCompleted = clockNanos()
        val inference = MachineVisionInference(
            frameId = frameId,
            completedMonotonicTimestampNanos = max(visionFrame.captureMonotonicTimestampNanos, frameCompleted),
            fixedVocabularySha256 = MachineVisionModelProfiles.fixedVocabularySha256,
            depthProfileId = selectedDepthProfile.id,
            observations = trackedDetections.map { tracked ->
                val detection = tracked.detection
                SemanticMaskObservation(
                    trackId = tracked.trackId,
                    classId = detection.classId,
                    confidence = detection.confidence,
                    relativeDepthSamples = depthSamples.getValue(tracked.trackId),
                    maskGeometry = detection.geometry,
                    temporalMotionEvidence = TemporalMotionEvidence.UNKNOWN,
                )
            },
        )

        return QnnLiveFrameResult(
            frameId = frameId,
            selectedDepthProfileId = selectedDepthProfile.id,
            segmentedObjectCount = trackedDetections.size,
            finiteYoloValues = QnnLiveFrameResult.YOLO_FINITE_VALUE_COUNT,
            finitePositiveDepthValues = depth.size,
            decodeLatencyNanos = elapsed(frameStarted, decodeCompleted),
            yoloPreprocessLatencyNanos = elapsed(decodeCompleted, segmentationPreprocessingCompleted),
            segmentationLatencyNanos = elapsed(segmentationPreprocessingCompleted, segmentationCompleted),
            yoloPostprocessLatencyNanos = elapsed(segmentationCompleted, segmentationPostprocessingCompleted),
            modelSetupLatencyNanos = elapsed(segmentationPostprocessingCompleted, modelSetupCompleted),
            depthPreprocessLatencyNanos = elapsed(modelSetupCompleted, depthPreprocessingCompleted),
            depthLatencyNanos = elapsed(depthPreprocessingCompleted, depthCompleted),
            depthPostprocessLatencyNanos = elapsed(depthCompleted, frameCompleted),
            totalLatencyNanos = elapsed(frameStarted, frameCompleted),
            calibrationState = when (visionFrame.cameraIntrinsics?.source) {
                CameraIntrinsicsSource.CALIBRATED -> LiveMetricCalibrationState.CALIBRATED_INTRINSICS_PRESENT
                CameraIntrinsicsSource.DERIVED -> LiveMetricCalibrationState.DERIVED_INTRINSICS_PRESENT
                null -> LiveMetricCalibrationState.UNCALIBRATED_INTRINSICS_MISSING
            },
            inference = inference,
        )
    }

    @Synchronized
    fun resetTracking() = tracker.reset()

    private fun elapsed(start: Long, end: Long): Long = max(0L, end - start)

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { depthSession?.close() }
        segmentationSession.close()
        depthSession = null
        activeDepthProfile = null
        tracker.reset()
    }

}
