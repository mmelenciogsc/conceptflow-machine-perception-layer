// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

data class FocusedVqaFrameKey(
    val sessionGeneration: Long,
    val frameId: Long,
    val captureMonotonicTimestampNanos: Long,
) {
    init {
        require(sessionGeneration > 0L && frameId > 0L && captureMonotonicTimestampNanos >= 0L)
    }
}

data class FocusedVqaFrameStoreStats(
    val frameCount: Int,
    val rgbBytes: Int,
    val framesOffered: Long,
    val framesEvicted: Long,
    val exactHits: Long,
    val misses: Long,
    val resets: Long,
)

/**
 * Short-lived, process-memory-only source for explicit focused VQA.
 *
 * Every retained frame is aspect-preserved RGB8 with a longest edge of at most 640 pixels. The
 * hard upper bound is [MAXIMUM_FRAME_COUNT] * [MAXIMUM_RGB_BYTES_PER_FRAME] = 9,830,400 bytes.
 * Evicted/reset byte arrays are overwritten before references are released.
 */
class BoundedFocusedVqaFrameStore(
    private val clockNanos: () -> Long,
    private val maximumFrameCount: Int = MAXIMUM_FRAME_COUNT,
    private val frameTtlNanos: Long = FRAME_TTL_NANOS,
) {
    private val frames = LinkedHashMap<FocusedVqaFrameKey, StoredFocusedVqaFrame>()
    private var rgbBytes = 0
    private var framesOffered = 0L
    private var framesEvicted = 0L
    private var exactHits = 0L
    private var misses = 0L
    private var resets = 0L
    private var activeSessionGeneration = 0L

    init {
        require(maximumFrameCount in 1..MAXIMUM_FRAME_COUNT)
        require(frameTtlNanos in 1L..FRAME_TTL_NANOS)
    }

    @Synchronized
    fun beginSession(sessionGeneration: Long) {
        require(sessionGeneration > 0L)
        resetLocked()
        activeSessionGeneration = sessionGeneration
    }

    @Synchronized
    fun offer(sessionGeneration: Long, frame: VisionFrame, image: RgbImage): Boolean {
        require(frame.frameId > 0L && !frame.synthetic)
        require(frame.width == image.width && frame.height == image.height)
        if (sessionGeneration != activeSessionGeneration) return false
        val now = clockNanos()
        if (now < frame.captureMonotonicTimestampNanos ||
            now - frame.captureMonotonicTimestampNanos > frameTtlNanos
        ) return false
        evictExpired(now)
        val scaled = resizeWithinBudget(image)
        check(scaled.pixels.size <= MAXIMUM_RGB_BYTES_PER_FRAME)
        val key = FocusedVqaFrameKey(sessionGeneration, frame.frameId, frame.captureMonotonicTimestampNanos)
        removeAndWipe(key)
        while (frames.size >= maximumFrameCount) {
            removeAndWipe(frames.entries.first().key)
        }
        val stored = StoredFocusedVqaFrame(
            key,
            frame.width,
            frame.height,
            scaled.width,
            scaled.height,
            scaled.pixels,
            Math.addExact(frame.captureMonotonicTimestampNanos, frameTtlNanos),
        )
        frames[key] = stored
        rgbBytes = Math.addExact(rgbBytes, stored.rgb.size)
        framesOffered += 1L
        return true
    }

    @Synchronized
    fun exact(key: FocusedVqaFrameKey): FocusedVqaSourceFrame? {
        val now = clockNanos()
        evictExpired(now)
        val stored = frames[key].takeIf { key.sessionGeneration == activeSessionGeneration }
        if (stored == null) {
            misses += 1L
            return null
        }
        exactHits += 1L
        return FocusedVqaSourceFrame(
            stored.key,
            stored.sourceWidth,
            stored.sourceHeight,
            RgbImage(stored.width, stored.height, stored.rgb.copyOf()),
        )
    }

    @Synchronized
    fun reset() {
        resetLocked()
        activeSessionGeneration = 0L
    }

    private fun resetLocked() {
        frames.values.forEach { it.rgb.fill(0) }
        frames.clear()
        rgbBytes = 0
        resets += 1L
    }

    @Synchronized
    fun stats(): FocusedVqaFrameStoreStats = FocusedVqaFrameStoreStats(
        frames.size,
        rgbBytes,
        framesOffered,
        framesEvicted,
        exactHits,
        misses,
        resets,
    )

    private fun evictExpired(now: Long) {
        val expired = frames.values.filter { now >= it.validUntilNanos }.map { it.key }
        expired.forEach(::removeAndWipe)
    }

    private fun removeAndWipe(key: FocusedVqaFrameKey) {
        val removed = frames.remove(key) ?: return
        rgbBytes -= removed.rgb.size
        removed.rgb.fill(0)
        framesEvicted += 1L
    }

    private fun resizeWithinBudget(image: RgbImage): RgbImage {
        val largest = max(image.width, image.height)
        if (largest <= MAXIMUM_STORED_EDGE_PIXELS) {
            return RgbImage(image.width, image.height, image.pixels.copyOf())
        }
        val scale = MAXIMUM_STORED_EDGE_PIXELS.toDouble() / largest
        val width = (image.width * scale).roundToInt().coerceIn(1, MAXIMUM_STORED_EDGE_PIXELS)
        val height = (image.height * scale).roundToInt().coerceIn(1, MAXIMUM_STORED_EDGE_PIXELS)
        val output = ByteArray(Math.multiplyExact(Math.multiplyExact(width, height), RGB_CHANNELS))
        var destination = 0
        for (y in 0 until height) {
            val sourceY = ((y + 0.5) * image.height / height).toInt().coerceIn(0, image.height - 1)
            for (x in 0 until width) {
                val sourceX = ((x + 0.5) * image.width / width).toInt().coerceIn(0, image.width - 1)
                val source = (sourceY * image.width + sourceX) * RGB_CHANNELS
                output[destination++] = image.pixels[source]
                output[destination++] = image.pixels[source + 1]
                output[destination++] = image.pixels[source + 2]
            }
        }
        return RgbImage(width, height, output)
    }

    private data class StoredFocusedVqaFrame(
        val key: FocusedVqaFrameKey,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val width: Int,
        val height: Int,
        val rgb: ByteArray,
        val validUntilNanos: Long,
    )

    companion object {
        const val MAXIMUM_STORED_EDGE_PIXELS = 640
        const val MAXIMUM_RGB_BYTES_PER_FRAME = 640 * 640 * 3
        const val MAXIMUM_FRAME_COUNT = 8
        const val MAXIMUM_TOTAL_RGB_BYTES = MAXIMUM_FRAME_COUNT * MAXIMUM_RGB_BYTES_PER_FRAME
        const val FRAME_TTL_NANOS = 1_750_000_000L
        private const val RGB_CHANNELS = 3
    }
}

data class FocusedVqaSourceFrame(
    val key: FocusedVqaFrameKey,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val image: RgbImage,
)

fun interface FocusedVqaJpegEncoder {
    fun encode(image: RgbImage): ByteArray
}

fun interface FocusedVqaFrameProvider {
    /** Called only by the explicit-VQA worker, never from camera admission. */
    fun prepare(request: org.conceptflow.mpl.host.focus.FocusedVqaRequest): EncodedJpegFrame?
}

class StoredFocusedVqaFrameProvider(
    private val store: BoundedFocusedVqaFrameStore,
    private val encoder: FocusedVqaJpegEncoder,
    private val contextMarginFraction: Double = DEFAULT_CONTEXT_MARGIN_FRACTION,
) : FocusedVqaFrameProvider {
    init {
        require(contextMarginFraction in 0.0..1.0)
    }

    override fun prepare(request: org.conceptflow.mpl.host.focus.FocusedVqaRequest): EncodedJpegFrame? {
        val source = store.exact(
            FocusedVqaFrameKey(
                request.correlation.sessionGeneration,
                request.correlation.sourceFrameId,
                request.sourceCaptureTimestampNanos,
            ),
        ) ?: return null
        val selected = request.imageGeometry?.let { geometry ->
            cropWithContext(source, geometry)
        } ?: source.image
        return try {
            val jpeg = encoder.encode(selected)
            val encoded = runCatching {
                EncodedJpegFrame(
                    source.key.frameId,
                    source.key.captureMonotonicTimestampNanos,
                    selected.width,
                    selected.height,
                    jpeg,
                )
            }.getOrNull()
            if (encoded == null) jpeg.fill(0)
            encoded
        } finally {
            selected.pixels.fill(0)
        }
    }

    private fun cropWithContext(source: FocusedVqaSourceFrame, geometry: InstanceMaskGeometry): RgbImage {
        if (geometry.imageWidthPixels != source.sourceWidth ||
            geometry.imageHeightPixels != source.sourceHeight
        ) return source.image
        val margin = max(geometry.widthPixels, geometry.heightPixels) * contextMarginFraction
        val left = floor((geometry.leftPixels - margin).coerceAtLeast(0.0) * source.image.width / source.sourceWidth)
            .toInt().coerceIn(0, source.image.width - 1)
        val top = floor((geometry.topPixels - margin).coerceAtLeast(0.0) * source.image.height / source.sourceHeight)
            .toInt().coerceIn(0, source.image.height - 1)
        val right = ceil(
            (geometry.rightExclusivePixels + margin).coerceAtMost(source.sourceWidth.toDouble()) *
                source.image.width / source.sourceWidth,
        ).toInt().coerceIn(left + 1, source.image.width)
        val bottom = ceil(
            (geometry.bottomExclusivePixels + margin).coerceAtMost(source.sourceHeight.toDouble()) *
                source.image.height / source.sourceHeight,
        ).toInt().coerceIn(top + 1, source.image.height)
        val width = right - left
        val height = bottom - top
        val output = ByteArray(Math.multiplyExact(Math.multiplyExact(width, height), 3))
        for (row in 0 until height) {
            val sourceOffset = ((top + row) * source.image.width + left) * 3
            source.image.pixels.copyInto(output, row * width * 3, sourceOffset, sourceOffset + width * 3)
        }
        source.image.pixels.fill(0)
        return RgbImage(width, height, output)
    }

    private companion object {
        const val DEFAULT_CONTEXT_MARGIN_FRACTION = 0.25
    }
}

/** Android encoder used exclusively by the bounded explicit-VQA worker. */
class AndroidFocusedVqaJpegEncoder(
    private val quality: Int = JPEG_QUALITY,
) : FocusedVqaJpegEncoder {
    init {
        require(quality in 1..100)
    }

    override fun encode(image: RgbImage): ByteArray {
        val pixels = IntArray(image.width * image.height)
        var source = 0
        for (index in pixels.indices) {
            val red = image.pixels[source].toInt() and 0xff
            val green = image.pixels[source + 1].toInt() and 0xff
            val blue = image.pixels[source + 2].toInt() and 0xff
            pixels[index] = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
            source += 3
        }
        val bitmap = Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream((image.width * image.height / 2).coerceAtLeast(1_024)).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
            pixels.fill(0)
            image.pixels.fill(0)
        }
    }

    private companion object {
        const val JPEG_QUALITY = 82
    }
}
