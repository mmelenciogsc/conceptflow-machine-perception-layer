// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.hardware

import android.graphics.ImageDecoder
import android.os.SystemClock
import com.google.protobuf.ByteString
import com.google.protobuf.UnsafeByteOperations
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.conceptflow.mpl.rokid.core.CameraFrameTransformer
import org.conceptflow.mpl.rokid.core.CameraTransformSnapshot
import org.conceptflow.mpl.rokid.core.CameraTransformLatencySnapshot
import org.conceptflow.mpl.rokid.core.SquareAspectFillTransform
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.ImageEncoding

/**
 * Single-worker, latest-frame post-gate transform. Camera callbacks only replace one RAM slot;
 * decoding/scaling/cropping and RGB extraction never run on the Camera2 callback thread.
 */
class BoundedRgbCameraTransformer : CameraFrameTransformer {
    private data class Work(
        val frame: FramePayload,
        val onReady: (FramePayload) -> Unit,
        val onFailure: () -> Unit,
    )

    private val pending = AtomicReference<Work?>()
    private val workerScheduled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "mpl-camera-transform").apply { isDaemon = true }
    }
    private val accepted = AtomicLong()
    private val completed = AtomicLong()
    private val replaced = AtomicLong()
    private val failed = AtomicLong()
    private val timings = CameraTransformTimings()
    // Accessed only by the single transform worker. Keeping one scanline avoids a 1.6 MiB
    // full-frame IntArray allocation for every admitted camera frame.
    private val pixelRow = IntArray(OUTPUT_SIZE)

    override fun offer(frame: FramePayload, onReady: (FramePayload) -> Unit, onFailure: () -> Unit): Boolean {
        if (closed.get()) return false
        accepted.incrementAndGet()
        if (pending.getAndSet(Work(frame, onReady, onFailure)) != null) replaced.incrementAndGet()
        schedule()
        return true
    }

    override fun snapshot(): CameraTransformSnapshot = CameraTransformSnapshot(
        accepted.get(),
        completed.get(),
        replaced.get(),
        failed.get(),
        timings.snapshot(),
    )

    private fun schedule() {
        if (!workerScheduled.compareAndSet(false, true)) return
        executor.execute {
            try {
                while (!closed.get()) {
                    val work = pending.getAndSet(null) ?: break
                    val startedNs = SystemClock.elapsedRealtimeNanos()
                    val transformed = runCatching { transform(work.frame) }.getOrNull()
                    timings.record(SystemClock.elapsedRealtimeNanos() - startedNs)
                    if (transformed == null) {
                        failed.incrementAndGet()
                        work.onFailure()
                    } else {
                        completed.incrementAndGet()
                        work.onReady(transformed)
                    }
                }
            } finally {
                workerScheduled.set(false)
                if (!closed.get() && pending.get() != null) schedule()
            }
        }
    }

    private fun transform(frame: FramePayload): FramePayload {
        require(frame.image.encoding == ImageEncoding.IMAGE_ENCODING_JPEG)
        require(!frame.frameData.isEmpty && frame.frameData.size() <= MAXIMUM_SOURCE_JPEG_BYTES)
        val transform = SquareAspectFillTransform.centered(
            frame.image.width,
            frame.image.height,
            OUTPUT_SIZE,
        )
        val decoded = ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(frame.frameData.asReadOnlyByteBuffer()),
        ) { decoder, info, _ ->
            require(info.size.width == frame.image.width && info.size.height == frame.image.height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(transform.scaledWidth, transform.scaledHeight)
        }
        try {
            require(decoded.width == transform.scaledWidth && decoded.height == transform.scaledHeight)
            val rgb = ByteArray(OUTPUT_RGB_BYTES)
            var offset = 0
            repeat(OUTPUT_SIZE) { row ->
                decoded.getPixels(
                    pixelRow,
                    0,
                    OUTPUT_SIZE,
                    transform.cropLeft,
                    transform.cropTop + row,
                    OUTPUT_SIZE,
                    1,
                )
                for (pixel in pixelRow) {
                    rgb[offset++] = (pixel shr 16 and 0xff).toByte()
                    rgb[offset++] = (pixel shr 8 and 0xff).toByte()
                    rgb[offset++] = (pixel and 0xff).toByte()
                }
            }
            val descriptor = frame.image.toBuilder()
                .setWidth(OUTPUT_SIZE)
                .setHeight(OUTPUT_SIZE)
                .setRowStrideBytes(OUTPUT_ROW_BYTES)
                .setEncoding(ImageEncoding.IMAGE_ENCODING_RGB8)
                .setMediaType(RGB_MEDIA_TYPE)
                .setPayloadBytes(rgb.size.toLong())
                .setSha256(ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(rgb)))
                .build()
            // This array is freshly allocated for this immutable payload and is never reused.
            // Unsafe wrapping removes a second 1.2 MiB copy without weakening buffer ownership.
            val immutableRgb = UnsafeByteOperations.unsafeWrap(rgb)
            return frame.toBuilder()
                .setImage(descriptor)
                .setFrameData(immutableRgb)
                .apply {
                    if (frame.hasIntrinsics()) {
                        intrinsics = transformCameraIntrinsicsForSquareOutput(frame.intrinsics, transform)
                    }
                }
                .build()
        } finally {
            decoded.recycle()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending.set(null)
        executor.shutdownNow()
    }

    private companion object {
        const val OUTPUT_SIZE = 640
        const val OUTPUT_ROW_BYTES = OUTPUT_SIZE * 3
        const val OUTPUT_RGB_BYTES = OUTPUT_SIZE * OUTPUT_ROW_BYTES
        const val MAXIMUM_SOURCE_JPEG_BYTES = 20 * 1_024 * 1_024
        const val RGB_MEDIA_TYPE = "application/x-conceptflow-rgb8"
    }
}

private class CameraTransformTimings(private val capacity: Int = 256) {
    private val values = LongArray(capacity)
    private var count = 0
    private var cursor = 0
    private var total = 0L
    private var maximum = 0L

    @Synchronized
    fun record(nanos: Long) {
        if (nanos < 0L) return
        values[cursor] = nanos
        cursor = (cursor + 1) % capacity
        count = minOf(count + 1, capacity)
        total = Math.addExact(total, 1L)
        maximum = maxOf(maximum, nanos)
    }

    @Synchronized
    fun snapshot(): CameraTransformLatencySnapshot {
        if (count == 0) return CameraTransformLatencySnapshot()
        val sorted = values.copyOf(count).sortedArray()
        return CameraTransformLatencySnapshot(
            samples = total,
            p50Nanos = sorted[nearestRank(sorted.size, 0.50)],
            p95Nanos = sorted[nearestRank(sorted.size, 0.95)],
            p99Nanos = sorted[nearestRank(sorted.size, 0.99)],
            maximumNanos = maximum,
        )
    }

    private fun nearestRank(size: Int, percentile: Double): Int =
        (kotlin.math.ceil(percentile * size).toInt().coerceIn(1, size) - 1)
}
