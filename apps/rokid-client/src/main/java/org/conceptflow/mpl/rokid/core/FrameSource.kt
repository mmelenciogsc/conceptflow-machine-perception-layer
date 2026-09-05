// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import com.google.protobuf.ByteString
import com.google.protobuf.UnsafeByteOperations
import org.conceptflow.mpl.v1.CameraIntrinsics
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.ImageDescriptor
import org.conceptflow.mpl.v1.ImageEncoding
import org.conceptflow.mpl.protocol.SyntheticImageFixtures
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class FrameLimits(
    val maxWidth: Int = 1_920,
    val maxHeight: Int = 1_080,
    val maxJpegBytes: Int = 2 * 1_024 * 1_024,
) {
    init {
        require(maxWidth in 1..4096)
        require(maxHeight in 1..4096)
        require(maxJpegBytes in 1..8 * 1024 * 1024)
    }
}

data class CaptureGateEvent(
    val inputDimensions: PixelDimensions,
    val outputDimensions: PixelDimensions,
    val emitted: Boolean,
    val dropReason: FrameDropReason?,
    val targetFramesPerSecond: Double,
    val meanLuma: Double,
    val darkFraction: Double,
    val laplacianVariance: Double,
    val motionScore: Double,
) {
    init {
        require(emitted == (dropReason == null))
        require(targetFramesPerSecond.isFinite() && targetFramesPerSecond > 0.0)
        require(meanLuma.isFinite() && meanLuma in 0.0..255.0)
        require(darkFraction.isFinite() && darkFraction in 0.0..1.0)
        require(laplacianVariance.isFinite() && laplacianVariance >= 0.0)
        require(motionScore.isFinite() && motionScore in 0.0..1.0)
    }
}

/**
 * Aggregate-only timing for one successfully analyzed camera image. The event
 * intentionally carries no frame bytes, identifiers, or wall-clock time.
 */
data class CaptureTimingEvent(
    val analyzedMonotonicTimestampNanos: Long,
    val emittedMonotonicTimestampNanos: Long?,
    val requestToImageLatencyNanos: Long?,
    val imageAcquisitionDurationNanos: Long,
    val processorDurationNanos: Long,
    val listenerPathDurationNanos: Long,
    /** Null when no RGB frame was emitted; otherwise true only for the packaged native path. */
    val nativeRgbConversion: Boolean? = null,
) {
    init {
        require(analyzedMonotonicTimestampNanos > 0L)
        require(
            emittedMonotonicTimestampNanos == null ||
                emittedMonotonicTimestampNanos >= analyzedMonotonicTimestampNanos,
        )
        require(requestToImageLatencyNanos == null || requestToImageLatencyNanos >= 0L)
        require(imageAcquisitionDurationNanos >= 0L)
        require(processorDurationNanos >= 0L)
        require(listenerPathDurationNanos >= 0L)
    }
}

/** Cumulative, payload-free counters for the bounded physical capture pipeline. */
data class CapturePipelineSnapshot(
    val requestsSubmitted: Long,
    val opportunitiesBackpressured: Long,
    val requestsSuperseded: Long,
    val imagesWithoutExactRequestMatch: Long,
    val captureFailures: Long,
    val lateCallbacks: Long,
    val outstandingRequests: Int,
    val maximumOutstandingRequests: Int,
) {
    init {
        require(requestsSubmitted >= 0L)
        require(opportunitiesBackpressured >= 0L)
        require(requestsSuperseded >= 0L)
        require(imagesWithoutExactRequestMatch >= 0L)
        require(captureFailures >= 0L)
        require(lateCallbacks >= 0L)
        require(outstandingRequests >= 0)
        require(maximumOutstandingRequests >= outstandingRequests)
    }
}

enum class CameraSourceDiagnosticDomain(val diagnosticLabel: String) {
    DEVICE_STATE_CALLBACK("device_state_callback"),
    CAMERA_ACCESS_EXCEPTION("camera_access_exception"),
    CAPTURE_CALLBACK("capture_callback"),
}

/** Payload-free Camera2 failure evidence suitable for aggregate diagnostics. */
data class CameraSourceDiagnostic(
    val domain: CameraSourceDiagnosticDomain,
    val numericCode: Int?,
    val symbolicCode: String,
    val recoverable: Boolean,
) {
    init {
        require(symbolicCode.length in 1..64 && symbolicCode.all { it == '_' || it.isDigit() || it in 'A'..'Z' })
    }
}

enum class CameraCalibrationCapabilityState(val diagnosticLabel: String) {
    VERIFIED_WITH_DISTORTION("verified_with_distortion"),
    VERIFIED_WITHOUT_DISTORTION("verified_without_distortion"),
    DERIVED_UNQUANTIFIED("derived_unquantified"),
    UNAVAILABLE("unavailable"),
    REJECTED("rejected"),
}

enum class FrameRejection {
    MISSING_IDENTITY,
    INVALID_DIMENSIONS,
    INVALID_ENCODING,
    SIZE_MISMATCH,
    OVERSIZE,
    NON_MONOTONIC_ID,
    NON_MONOTONIC_TIMESTAMP,
}

class FrameValidator(
    private val limits: FrameLimits,
    private val maxStreamHistories: Int = 1_024,
) {
    private val histories = LinkedHashMap<FrameStreamKey, FrameHighWater>()

    init {
        require(maxStreamHistories in 1..65_536)
    }

    @Synchronized
    fun validate(frame: FramePayload): FrameRejection? {
        if (frame.requestId.isBlank() || frame.sessionId.isBlank() || frame.streamId.isBlank()) {
            return FrameRejection.MISSING_IDENTITY
        }
        if (frame.image.width !in 1..limits.maxWidth || frame.image.height !in 1..limits.maxHeight) {
            return FrameRejection.INVALID_DIMENSIONS
        }
        if (frame.image.encoding != ImageEncoding.IMAGE_ENCODING_JPEG || frame.image.mediaType != "image/jpeg") {
            return FrameRejection.INVALID_ENCODING
        }
        if (frame.frameData.size().toLong() != frame.image.payloadBytes) {
            return FrameRejection.SIZE_MISMATCH
        }
        if (frame.frameData.size() > limits.maxJpegBytes) return FrameRejection.OVERSIZE
        val key = FrameStreamKey(frame.sessionId, frame.streamId)
        val previous = histories.remove(key)
        if (previous != null) histories[key] = previous
        if (frame.frameId == 0L || previous != null && frame.frameId <= previous.frameId) {
            return FrameRejection.NON_MONOTONIC_ID
        }
        if (previous != null && frame.captureMonotonicTimestampNs <= previous.timestampNanos) {
            return FrameRejection.NON_MONOTONIC_TIMESTAMP
        }
        histories.remove(key)
        histories[key] = FrameHighWater(frame.frameId, frame.captureMonotonicTimestampNs)
        while (histories.size > maxStreamHistories) histories.remove(histories.keys.first())
        return null
    }

    @Synchronized
    fun resetSession(sessionId: String): Int {
        val keys = histories.keys.filter { it.sessionId == sessionId }
        keys.forEach(histories::remove)
        return keys.size
    }

    @Synchronized
    fun reset() = histories.clear()

    @Synchronized
    fun historyCount(): Int = histories.size
}

private data class FrameStreamKey(val sessionId: String, val streamId: String)

private data class FrameHighWater(val frameId: Long, val timestampNanos: Long)

interface FrameSource : AutoCloseable {
    interface Listener {
        fun onFrame(frame: FramePayload)
        fun onCaptureGate(event: CaptureGateEvent) = Unit
        fun onCaptureSessionReady(readyMonotonicTimestampNanos: Long) = Unit
        fun onCameraCalibrationCapability(state: CameraCalibrationCapabilityState) = Unit
        fun onCaptureTiming(event: CaptureTimingEvent) = Unit
        fun onCapturePipelineSnapshot(snapshot: CapturePipelineSnapshot) = Unit
        /** A fresh source instance may be opened without ending the surrounding stream lease. */
        fun onRecoverableError(message: String) = onError(message)
        fun onRecoverableError(message: String, diagnostic: CameraSourceDiagnostic) =
            onRecoverableError(message)
        fun onError(message: String, diagnostic: CameraSourceDiagnostic) = onError(message)
        fun onError(message: String)
    }

    val isRunning: Boolean
    fun start(listener: Listener)
    fun stop()
    override fun close() = stop()
}

class FrameSourceStateController {
    private var activeSource: FrameSource? = null
    private var closingSource: FrameSource? = null

    @get:Synchronized
    val hasActiveSource: Boolean get() = activeSource != null || closingSource != null

    @Synchronized
    fun attach(source: FrameSource): Boolean {
        if (activeSource != null || closingSource != null) return false
        activeSource = source
        return true
    }

    @Synchronized
    fun isCurrent(source: FrameSource): Boolean = activeSource === source

    fun stopIfCurrent(source: FrameSource): Boolean {
        val detached = synchronized(this) {
            if (activeSource !== source) {
                false
            } else {
                activeSource = null
                closingSource = source
                true
            }
        }
        if (!detached) return false
        closeDetached(source)
        return true
    }

    fun stopCurrent(): Boolean {
        val source = synchronized(this) {
            activeSource?.also {
                activeSource = null
                closingSource = it
            }
        } ?: return false
        closeDetached(source)
        return true
    }

    private fun closeDetached(source: FrameSource) {
        try {
            source.close()
        } finally {
            synchronized(this) {
                if (closingSource === source) closingSource = null
            }
        }
    }
}

class MonotonicFrameSequence {
    private val nextFrameId = AtomicLong(0L)
    private val lastTimestamp = AtomicLong(-1L)

    fun nextId(): Long = nextFrameId.incrementAndGet()

    fun normalizeTimestamp(candidateNanos: Long): Long {
        while (true) {
            val previous = lastTimestamp.get()
            val normalized = maxOf(candidateNanos, previous + 1L)
            if (lastTimestamp.compareAndSet(previous, normalized)) return normalized
        }
    }
}

class SyntheticFrameSource(
    private val clock: MonotonicClock,
    private val wallClock: WallClock,
    private val limits: FrameLimits = FrameLimits(),
    private val sessionId: String = "synthetic-session",
    private val streamId: String = "synthetic-camera",
) : FrameSource {
    private val running = AtomicBoolean(false)
    private val sequence = MonotonicFrameSequence()
    private var listener: FrameSource.Listener? = null

    override val isRunning: Boolean get() = running.get()

    override fun start(listener: FrameSource.Listener) {
        check(running.compareAndSet(false, true)) { "Frame source is already running" }
        this.listener = listener
    }

    fun capture(jpeg: ByteArray = SYNTHETIC_JPEG): FramePayload {
        check(isRunning) { "Frame source is not running" }
        require(jpeg.isNotEmpty() && jpeg.size <= limits.maxJpegBytes) { "JPEG payload exceeds capture limits" }
        val frameId = sequence.nextId()
        val timestamp = sequence.normalizeTimestamp(clock.nowNanos())
        val payload = buildJpegFrame(
            requestId = "synthetic-$frameId",
            sessionId = sessionId,
            streamId = streamId,
            frameId = frameId,
            timestampNanos = timestamp,
            wallTimeMillis = wallClock.nowMillis(),
            width = 1,
            height = 1,
            bytes = jpeg,
            synthetic = true,
        )
        listener?.onFrame(payload)
        return payload
    }

    override fun stop() {
        running.set(false)
        listener = null
    }

    companion object {
        private val SYNTHETIC_JPEG = SyntheticImageFixtures.onePixelJpeg()
    }
}

internal fun buildJpegFrame(
    requestId: String,
    sessionId: String,
    streamId: String,
    frameId: Long,
    timestampNanos: Long,
    wallTimeMillis: Long,
    width: Int,
    height: Int,
    bytes: ByteArray,
    synthetic: Boolean,
    intrinsics: CameraIntrinsics? = null,
    takeOwnership: Boolean = false,
): FramePayload {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val descriptor = ImageDescriptor.newBuilder()
        .setWidth(width)
        .setHeight(height)
        .setEncoding(ImageEncoding.IMAGE_ENCODING_JPEG)
        .setMediaType("image/jpeg")
        .setPayloadBytes(bytes.size.toLong())
        .setSha256(ByteString.copyFrom(digest))
        .build()
    val payload = FramePayload.newBuilder()
        .setRequestId(requestId)
        .setSessionId(sessionId)
        .setStreamId(streamId)
        .setFrameId(frameId)
        .setCaptureMonotonicTimestampNs(timestampNanos)
        .setCaptureWallTime(protobufTimestamp(wallTimeMillis))
        .setImage(descriptor)
        .setFrameData(
            if (takeOwnership) {
                // The caller transfers exclusive ownership and must never mutate this array.
                UnsafeByteOperations.unsafeWrap(bytes)
            } else {
                ByteString.copyFrom(bytes)
            },
        )
        .setSynthetic(synthetic)
    intrinsics?.let(payload::setIntrinsics)
    return payload.build()
}

internal fun buildRgbFrame(
    requestId: String,
    sessionId: String,
    streamId: String,
    frameId: Long,
    timestampNanos: Long,
    wallTimeMillis: Long,
    width: Int,
    height: Int,
    bytes: ByteArray,
    synthetic: Boolean,
    intrinsics: CameraIntrinsics? = null,
    takeOwnership: Boolean = false,
): FramePayload {
    val rowStride = Math.multiplyExact(width, 3)
    require(bytes.size == Math.multiplyExact(rowStride, height)) { "RGB8 payload size does not match dimensions" }
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val descriptor = ImageDescriptor.newBuilder()
        .setWidth(width)
        .setHeight(height)
        .setRowStrideBytes(rowStride)
        .setEncoding(ImageEncoding.IMAGE_ENCODING_RGB8)
        .setMediaType("application/x-conceptflow-rgb8")
        .setPayloadBytes(bytes.size.toLong())
        .setSha256(ByteString.copyFrom(digest))
        .build()
    val payload = FramePayload.newBuilder()
        .setRequestId(requestId)
        .setSessionId(sessionId)
        .setStreamId(streamId)
        .setFrameId(frameId)
        .setCaptureMonotonicTimestampNs(timestampNanos)
        .setCaptureWallTime(protobufTimestamp(wallTimeMillis))
        .setImage(descriptor)
        .setFrameData(if (takeOwnership) UnsafeByteOperations.unsafeWrap(bytes) else ByteString.copyFrom(bytes))
        .setSynthetic(synthetic)
    intrinsics?.let(payload::setIntrinsics)
    return payload.build()
}

internal fun buildI420Frame(
    requestId: String,
    sessionId: String,
    streamId: String,
    frameId: Long,
    timestampNanos: Long,
    wallTimeMillis: Long,
    width: Int,
    height: Int,
    bytes: ByteArray,
    synthetic: Boolean,
    intrinsics: CameraIntrinsics? = null,
    takeOwnership: Boolean = false,
): FramePayload {
    require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
        "I420 dimensions must be positive and even"
    }
    val lumaBytes = Math.multiplyExact(width, height)
    val expectedBytes = Math.addExact(lumaBytes, lumaBytes / 2)
    require(bytes.size == expectedBytes) { "I420 payload size does not match dimensions" }
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val descriptor = ImageDescriptor.newBuilder()
        .setWidth(width)
        .setHeight(height)
        .setRowStrideBytes(width)
        .setEncoding(ImageEncoding.IMAGE_ENCODING_YUV420_I420)
        .setMediaType("application/x-conceptflow-i420")
        .setPayloadBytes(bytes.size.toLong())
        .setSha256(ByteString.copyFrom(digest))
        .build()
    val payload = FramePayload.newBuilder()
        .setRequestId(requestId)
        .setSessionId(sessionId)
        .setStreamId(streamId)
        .setFrameId(frameId)
        .setCaptureMonotonicTimestampNs(timestampNanos)
        .setCaptureWallTime(protobufTimestamp(wallTimeMillis))
        .setImage(descriptor)
        .setFrameData(if (takeOwnership) UnsafeByteOperations.unsafeWrap(bytes) else ByteString.copyFrom(bytes))
        .setSynthetic(synthetic)
    intrinsics?.let(payload::setIntrinsics)
    return payload.build()
}

internal fun buildAvcIntraFrame(
    requestId: String,
    sessionId: String,
    streamId: String,
    frameId: Long,
    timestampNanos: Long,
    wallTimeMillis: Long,
    width: Int,
    height: Int,
    bytes: ByteArray,
    synthetic: Boolean,
    intrinsics: CameraIntrinsics? = null,
    takeOwnership: Boolean = false,
): FramePayload {
    require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
    require(bytes.isNotEmpty() && bytes.size <= 2 * 1_024 * 1_024)
    org.conceptflow.mpl.transport.AvcAnnexBAccessUnit.requireIndependent(bytes)
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val descriptor = ImageDescriptor.newBuilder()
        .setWidth(width)
        .setHeight(height)
        .setRowStrideBytes(0)
        .setEncoding(ImageEncoding.IMAGE_ENCODING_AVC_ANNEX_B_INTRA)
        .setMediaType(org.conceptflow.mpl.transport.AvcAnnexBAccessUnit.MEDIA_TYPE)
        .setPayloadBytes(bytes.size.toLong())
        .setSha256(ByteString.copyFrom(digest))
        .build()
    val payload = FramePayload.newBuilder()
        .setRequestId(requestId)
        .setSessionId(sessionId)
        .setStreamId(streamId)
        .setFrameId(frameId)
        .setCaptureMonotonicTimestampNs(timestampNanos)
        .setCaptureWallTime(protobufTimestamp(wallTimeMillis))
        .setImage(descriptor)
        .setFrameData(if (takeOwnership) UnsafeByteOperations.unsafeWrap(bytes) else ByteString.copyFrom(bytes))
        .setSynthetic(synthetic)
    intrinsics?.let(payload::setIntrinsics)
    return payload.build()
}
