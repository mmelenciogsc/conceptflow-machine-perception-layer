// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

data class StreamDiagnosticSnapshot(
    val durationMillis: Long,
    val cameraCaptureSessionReady: Boolean,
    val cameraCaptureSessionStartupMillis: Double,
    val cameraFrames: Long,
    val cameraBytes: Long,
    val cameraFramesAnalyzed: Long,
    val cameraFramesDroppedDark: Long,
    val cameraFramesDroppedBlurry: Long,
    val cameraFramesDroppedCadence: Long,
    val cameraMotionTierSamples: Long,
    val cameraMaximumMeanLuma: Double,
    val cameraMinimumDarkFraction: Double,
    val cameraMaximumLaplacianVariance: Double,
    val cameraMaximumMotionScore: Double,
    val cameraTimingSamples: Long,
    val cameraTimingRetainedSamples: Int,
    val cameraTimingRejectedEvents: Long,
    val cameraRequestTimingSamples: Long,
    val cameraRequestTimingRetainedSamples: Int,
    val cameraAnalyzedActiveMillis: Double,
    val cameraAnalyzedObservedFramesPerSecond: Double,
    val cameraEmittedTimingSamples: Long,
    val cameraEmittedActiveMillis: Double,
    val cameraEmittedObservedFramesPerSecond: Double,
    val cameraRequestToImageP50Millis: Double,
    val cameraRequestToImageP95Millis: Double,
    val cameraRequestToImageMaximumMillis: Double,
    val cameraImageAcquisitionP50Millis: Double,
    val cameraImageAcquisitionP95Millis: Double,
    val cameraImageAcquisitionMaximumMillis: Double,
    val cameraProcessorP50Millis: Double,
    val cameraProcessorP95Millis: Double,
    val cameraProcessorMaximumMillis: Double,
    val cameraRgbConversions: Long,
    val cameraNativeRgbConversions: Long,
    val cameraListenerPathP50Millis: Double,
    val cameraListenerPathP95Millis: Double,
    val cameraListenerPathMaximumMillis: Double,
    val cameraRequestsSubmitted: Long,
    val cameraOpportunitiesBackpressured: Long,
    val cameraRequestsSuperseded: Long,
    val cameraImagesWithoutExactRequestMatch: Long,
    val cameraCaptureFailures: Long,
    val cameraLateCallbacks: Long,
    val cameraOutstandingRequests: Int,
    val cameraMaximumOutstandingRequests: Int,
    val imuSamples: Long,
    val imuSignalSamples: Long,
    val imuObservedSamplesPerSecond: Double,
    val imuMaximumGapMillis: Double,
    val microphoneChunks: Long,
    val microphoneBytes: Long,
    val microphoneNonZeroSamples: Long,
    val microphonePeakAbsolute: Int,
) {
    val passed: Boolean
        get() = cameraFrames > 0L && imuSamples > 0L && microphoneChunks > 0L
}

class StreamDiagnosticSession(private val startedMonotonicNs: Long) {
    private var accepting = true
    private var cameraCaptureSessionReadyTimestampNanos: Long? = null
    private var cameraFrames = 0L
    private var cameraBytes = 0L
    private var cameraFramesAnalyzed = 0L
    private var cameraFramesDroppedDark = 0L
    private var cameraFramesDroppedBlurry = 0L
    private var cameraFramesDroppedCadence = 0L
    private var cameraMotionTierSamples = 0L
    private var cameraMaximumMeanLuma = 0.0
    private var cameraMinimumDarkFraction = 1.0
    private var cameraMaximumLaplacianVariance = 0.0
    private var cameraMaximumMotionScore = 0.0
    private var cameraTimingRejectedEvents = 0L
    private val analyzedTimeline = MonotonicRateTracker()
    private val emittedTimeline = MonotonicRateTracker()
    private val requestToImageTimings = BoundedTimingWindow()
    private val imageAcquisitionTimings = BoundedTimingWindow()
    private val processorTimings = BoundedTimingWindow()
    private var cameraRgbConversions = 0L
    private var cameraNativeRgbConversions = 0L
    private val listenerPathTimings = BoundedTimingWindow()
    private var capturePipelineSnapshot = EMPTY_CAPTURE_PIPELINE_SNAPSHOT
    private var imuSamples = 0L
    private var imuSignalSamples = 0L
    private var firstImuTimestampNanos = 0L
    private var lastImuTimestampNanos = 0L
    private var maximumImuGapNanos = 0L
    private var timedImuSamples = 0L
    private var microphoneChunks = 0L
    private var microphoneBytes = 0L
    private var microphoneNonZeroSamples = 0L
    private var microphonePeakAbsolute = 0
    private var finalSnapshot: StreamDiagnosticSnapshot? = null

    init {
        require(startedMonotonicNs >= 0L)
    }

    @Synchronized
    fun recordCameraFrame(payloadBytes: Int): Boolean {
        require(payloadBytes > 0)
        if (!accepting) return false
        cameraFrames += 1L
        cameraBytes += payloadBytes
        return cameraFrames == 1L
    }

    @Synchronized
    fun recordCaptureGate(event: CaptureGateEvent) {
        if (!accepting) return
        cameraFramesAnalyzed += 1L
        if (event.targetFramesPerSecond >= 5.0) cameraMotionTierSamples += 1L
        cameraMaximumMeanLuma = maxOf(cameraMaximumMeanLuma, event.meanLuma)
        cameraMinimumDarkFraction = minOf(cameraMinimumDarkFraction, event.darkFraction)
        cameraMaximumLaplacianVariance = maxOf(cameraMaximumLaplacianVariance, event.laplacianVariance)
        cameraMaximumMotionScore = maxOf(cameraMaximumMotionScore, event.motionScore)
        when (event.dropReason) {
            FrameDropReason.DARK -> cameraFramesDroppedDark += 1L
            FrameDropReason.BLURRY -> cameraFramesDroppedBlurry += 1L
            FrameDropReason.CADENCE_SIMILAR -> cameraFramesDroppedCadence += 1L
            null -> Unit
        }
    }

    @Synchronized
    fun recordCameraCaptureSessionReady(readyMonotonicTimestampNanos: Long): Boolean {
        if (!accepting) return false
        if (readyMonotonicTimestampNanos < startedMonotonicNs ||
            cameraCaptureSessionReadyTimestampNanos != null
        ) {
            cameraTimingRejectedEvents += 1L
            return false
        }
        cameraCaptureSessionReadyTimestampNanos = readyMonotonicTimestampNanos
        return true
    }

    @Synchronized
    fun recordCaptureTiming(event: CaptureTimingEvent): Boolean {
        if (!accepting) return false
        val emittedTimestamp = event.emittedMonotonicTimestampNanos
        if (event.analyzedMonotonicTimestampNanos < startedMonotonicNs ||
            !analyzedTimeline.accepts(event.analyzedMonotonicTimestampNanos) ||
            emittedTimestamp != null && !emittedTimeline.accepts(emittedTimestamp)
        ) {
            cameraTimingRejectedEvents += 1L
            return false
        }
        check(analyzedTimeline.record(event.analyzedMonotonicTimestampNanos))
        if (emittedTimestamp != null) check(emittedTimeline.record(emittedTimestamp))
        event.requestToImageLatencyNanos?.let { check(requestToImageTimings.record(it)) }
        check(imageAcquisitionTimings.record(event.imageAcquisitionDurationNanos))
        check(processorTimings.record(event.processorDurationNanos))
        event.nativeRgbConversion?.let { native ->
            cameraRgbConversions += 1L
            if (native) cameraNativeRgbConversions += 1L
        }
        check(listenerPathTimings.record(event.listenerPathDurationNanos))
        return true
    }

    @Synchronized
    fun recordCapturePipelineSnapshot(snapshot: CapturePipelineSnapshot): Boolean {
        if (!accepting) return false
        val previous = capturePipelineSnapshot
        if (snapshot.requestsSubmitted < previous.requestsSubmitted ||
            snapshot.opportunitiesBackpressured < previous.opportunitiesBackpressured ||
            snapshot.requestsSuperseded < previous.requestsSuperseded ||
            snapshot.imagesWithoutExactRequestMatch < previous.imagesWithoutExactRequestMatch ||
            snapshot.captureFailures < previous.captureFailures ||
            snapshot.lateCallbacks < previous.lateCallbacks ||
            snapshot.maximumOutstandingRequests < previous.maximumOutstandingRequests
        ) {
            cameraTimingRejectedEvents += 1L
            return false
        }
        capturePipelineSnapshot = snapshot
        return true
    }

    @Synchronized
    fun recordImuSample(hasNonZeroSignal: Boolean, timestampNanos: Long = 0L): Boolean {
        require(timestampNanos >= 0L)
        if (!accepting) return false
        imuSamples += 1L
        if (hasNonZeroSignal) imuSignalSamples += 1L
        if (timestampNanos > 0L) {
            if (firstImuTimestampNanos == 0L) {
                firstImuTimestampNanos = timestampNanos
                lastImuTimestampNanos = timestampNanos
                timedImuSamples = 1L
            } else if (timestampNanos > lastImuTimestampNanos) {
                timedImuSamples += 1L
                maximumImuGapNanos = maxOf(maximumImuGapNanos, timestampNanos - lastImuTimestampNanos)
                lastImuTimestampNanos = timestampNanos
            }
        }
        return imuSamples == 1L
    }

    @Synchronized
    fun recordMicrophoneChunk(pcm16LittleEndian: ByteArray): Boolean {
        require(pcm16LittleEndian.isNotEmpty())
        if (!accepting) return false
        microphoneChunks += 1L
        microphoneBytes += pcm16LittleEndian.size
        for (offset in 0 until pcm16LittleEndian.size - 1 step 2) {
            val low = pcm16LittleEndian[offset].toInt() and 0xff
            val high = pcm16LittleEndian[offset + 1].toInt()
            val sample = ((high shl 8) or low).toShort().toInt()
            if (sample != 0) microphoneNonZeroSamples += 1L
            microphonePeakAbsolute = maxOf(microphonePeakAbsolute, kotlin.math.abs(sample))
        }
        return microphoneChunks == 1L
    }

    @Synchronized
    fun finish(finishedMonotonicNs: Long): StreamDiagnosticSnapshot {
        finalSnapshot?.let { return it }
        accepting = false
        val analyzedRate = analyzedTimeline.snapshot()
        val emittedRate = emittedTimeline.snapshot()
        val requestToImage = requestToImageTimings.snapshot()
        val imageAcquisition = imageAcquisitionTimings.snapshot()
        val processor = processorTimings.snapshot()
        val listenerPath = listenerPathTimings.snapshot()
        val captureSessionReadyTimestamp = cameraCaptureSessionReadyTimestampNanos
        return StreamDiagnosticSnapshot(
            durationMillis = ((finishedMonotonicNs - startedMonotonicNs).coerceAtLeast(0L)) / 1_000_000L,
            cameraCaptureSessionReady = captureSessionReadyTimestamp != null,
            cameraCaptureSessionStartupMillis = captureSessionReadyTimestamp
                ?.let { (it - startedMonotonicNs).coerceAtLeast(0L).toMilliseconds() }
                ?: 0.0,
            cameraFrames = cameraFrames,
            cameraBytes = cameraBytes,
            cameraFramesAnalyzed = cameraFramesAnalyzed,
            cameraFramesDroppedDark = cameraFramesDroppedDark,
            cameraFramesDroppedBlurry = cameraFramesDroppedBlurry,
            cameraFramesDroppedCadence = cameraFramesDroppedCadence,
            cameraMotionTierSamples = cameraMotionTierSamples,
            cameraMaximumMeanLuma = cameraMaximumMeanLuma,
            cameraMinimumDarkFraction = if (cameraFramesAnalyzed == 0L) 0.0 else cameraMinimumDarkFraction,
            cameraMaximumLaplacianVariance = cameraMaximumLaplacianVariance,
            cameraMaximumMotionScore = cameraMaximumMotionScore,
            cameraTimingSamples = analyzedRate.sampleCount,
            cameraTimingRetainedSamples = processor.retainedSampleCount,
            cameraTimingRejectedEvents = cameraTimingRejectedEvents,
            cameraRequestTimingSamples = requestToImage.totalSampleCount,
            cameraRequestTimingRetainedSamples = requestToImage.retainedSampleCount,
            cameraAnalyzedActiveMillis = analyzedRate.activeDurationNanos.toMilliseconds(),
            cameraAnalyzedObservedFramesPerSecond = analyzedRate.observedSamplesPerSecond,
            cameraEmittedTimingSamples = emittedRate.sampleCount,
            cameraEmittedActiveMillis = emittedRate.activeDurationNanos.toMilliseconds(),
            cameraEmittedObservedFramesPerSecond = emittedRate.observedSamplesPerSecond,
            cameraRequestToImageP50Millis = requestToImage.p50Nanos.toMilliseconds(),
            cameraRequestToImageP95Millis = requestToImage.p95Nanos.toMilliseconds(),
            cameraRequestToImageMaximumMillis = requestToImage.maximumNanos.toMilliseconds(),
            cameraImageAcquisitionP50Millis = imageAcquisition.p50Nanos.toMilliseconds(),
            cameraImageAcquisitionP95Millis = imageAcquisition.p95Nanos.toMilliseconds(),
            cameraImageAcquisitionMaximumMillis = imageAcquisition.maximumNanos.toMilliseconds(),
            cameraProcessorP50Millis = processor.p50Nanos.toMilliseconds(),
            cameraProcessorP95Millis = processor.p95Nanos.toMilliseconds(),
            cameraProcessorMaximumMillis = processor.maximumNanos.toMilliseconds(),
            cameraRgbConversions = cameraRgbConversions,
            cameraNativeRgbConversions = cameraNativeRgbConversions,
            cameraListenerPathP50Millis = listenerPath.p50Nanos.toMilliseconds(),
            cameraListenerPathP95Millis = listenerPath.p95Nanos.toMilliseconds(),
            cameraListenerPathMaximumMillis = listenerPath.maximumNanos.toMilliseconds(),
            cameraRequestsSubmitted = capturePipelineSnapshot.requestsSubmitted,
            cameraOpportunitiesBackpressured = capturePipelineSnapshot.opportunitiesBackpressured,
            cameraRequestsSuperseded = capturePipelineSnapshot.requestsSuperseded,
            cameraImagesWithoutExactRequestMatch = capturePipelineSnapshot.imagesWithoutExactRequestMatch,
            cameraCaptureFailures = capturePipelineSnapshot.captureFailures,
            cameraLateCallbacks = capturePipelineSnapshot.lateCallbacks,
            cameraOutstandingRequests = capturePipelineSnapshot.outstandingRequests,
            cameraMaximumOutstandingRequests = capturePipelineSnapshot.maximumOutstandingRequests,
            imuSamples = imuSamples,
            imuSignalSamples = imuSignalSamples,
            imuObservedSamplesPerSecond = observedImuSamplesPerSecond(),
            imuMaximumGapMillis = maximumImuGapNanos / 1_000_000.0,
            microphoneChunks = microphoneChunks,
            microphoneBytes = microphoneBytes,
            microphoneNonZeroSamples = microphoneNonZeroSamples,
            microphonePeakAbsolute = microphonePeakAbsolute,
        ).also { finalSnapshot = it }
    }

    private fun observedImuSamplesPerSecond(): Double {
        if (timedImuSamples < 2L || lastImuTimestampNanos <= firstImuTimestampNanos) return 0.0
        return (timedImuSamples - 1L) * 1_000_000_000.0 / (lastImuTimestampNanos - firstImuTimestampNanos)
    }
}

internal data class MonotonicRateSnapshot(
    val sampleCount: Long,
    val activeDurationNanos: Long,
    val observedSamplesPerSecond: Double,
)

internal class MonotonicRateTracker {
    private var sampleCount = 0L
    private var firstTimestampNanos = 0L
    private var lastTimestampNanos = 0L

    fun accepts(timestampNanos: Long): Boolean =
        timestampNanos > 0L && (sampleCount == 0L || timestampNanos > lastTimestampNanos)

    fun record(timestampNanos: Long): Boolean {
        if (!accepts(timestampNanos)) return false
        if (sampleCount == 0L) firstTimestampNanos = timestampNanos
        lastTimestampNanos = timestampNanos
        sampleCount += 1L
        return true
    }

    fun snapshot(): MonotonicRateSnapshot {
        val duration = if (sampleCount < 2L) 0L else lastTimestampNanos - firstTimestampNanos
        val rate = if (duration <= 0L) {
            0.0
        } else {
            (sampleCount - 1L) * NANOS_PER_SECOND.toDouble() / duration
        }
        return MonotonicRateSnapshot(sampleCount, duration, rate)
    }

    fun reset() {
        sampleCount = 0L
        firstTimestampNanos = 0L
        lastTimestampNanos = 0L
    }
}

internal data class BoundedTimingSnapshot(
    val totalSampleCount: Long,
    val retainedSampleCount: Int,
    val p50Nanos: Long,
    val p95Nanos: Long,
    val maximumNanos: Long,
)

internal class BoundedTimingWindow(private val capacity: Int = CAPTURE_TIMING_WINDOW_CAPACITY) {
    private val samples = LongArray(capacity)
    private var retainedSampleCount = 0
    private var nextIndex = 0
    private var totalSampleCount = 0L
    private var maximumNanos = 0L

    init {
        require(capacity in 1..MAX_CAPTURE_TIMING_WINDOW_CAPACITY)
    }

    fun record(durationNanos: Long): Boolean {
        if (durationNanos < 0L) return false
        samples[nextIndex] = durationNanos
        nextIndex = (nextIndex + 1) % capacity
        retainedSampleCount = minOf(capacity, retainedSampleCount + 1)
        totalSampleCount += 1L
        maximumNanos = maxOf(maximumNanos, durationNanos)
        return true
    }

    fun snapshot(): BoundedTimingSnapshot {
        if (retainedSampleCount == 0) return BoundedTimingSnapshot(0L, 0, 0L, 0L, 0L)
        val sorted = samples.copyOf(retainedSampleCount).sortedArray()
        return BoundedTimingSnapshot(
            totalSampleCount = totalSampleCount,
            retainedSampleCount = retainedSampleCount,
            p50Nanos = sorted.nearestRank(0.50),
            p95Nanos = sorted.nearestRank(0.95),
            maximumNanos = maximumNanos,
        )
    }

    fun reset() {
        samples.fill(0L)
        retainedSampleCount = 0
        nextIndex = 0
        totalSampleCount = 0L
        maximumNanos = 0L
    }
}

private fun LongArray.nearestRank(percentile: Double): Long {
    require(isNotEmpty())
    require(percentile > 0.0 && percentile <= 1.0)
    val rank = kotlin.math.ceil(percentile * size).toInt().coerceIn(1, size)
    return this[rank - 1]
}

private fun Long.toMilliseconds(): Double = this / NANOS_PER_MILLISECOND.toDouble()

private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val NANOS_PER_SECOND = 1_000_000_000L
private const val CAPTURE_TIMING_WINDOW_CAPACITY = 64
private const val MAX_CAPTURE_TIMING_WINDOW_CAPACITY = 1_024
private val EMPTY_CAPTURE_PIPELINE_SNAPSHOT = CapturePipelineSnapshot(
    requestsSubmitted = 0L,
    opportunitiesBackpressured = 0L,
    requestsSuperseded = 0L,
    imagesWithoutExactRequestMatch = 0L,
    captureFailures = 0L,
    lateCallbacks = 0L,
    outstandingRequests = 0,
    maximumOutstandingRequests = 0,
)
