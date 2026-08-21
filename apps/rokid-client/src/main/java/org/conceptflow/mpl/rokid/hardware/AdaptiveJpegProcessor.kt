// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.hardware

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.conceptflow.mpl.rokid.core.AdaptiveFrameDecision
import org.conceptflow.mpl.rokid.core.AdaptiveFrameGate
import org.conceptflow.mpl.rokid.core.FrameLimits
import org.conceptflow.mpl.rokid.core.LumaFrame
import org.conceptflow.mpl.rokid.core.PixelDimensions
import org.conceptflow.mpl.rokid.core.aspectFit
import java.io.ByteArrayOutputStream

internal data class ProcessedCameraFrame(
    val jpeg: ByteArray?,
    val inputDimensions: PixelDimensions,
    val dimensions: PixelDimensions,
    val decision: AdaptiveFrameDecision,
)

/**
 * Performs bounded, in-memory capture gating. It never crops or stretches an
 * image: a non-native input is aspect-fitted inside the configured gate.
 */
internal class AdaptiveJpegProcessor(
    private val limits: FrameLimits,
    private val frameGate: AdaptiveFrameGate = AdaptiveFrameGate(),
    private val analysisGate: PixelDimensions = PixelDimensions(160, 90),
    private val outputQuality: Int = 88,
) {
    init {
        require(outputQuality in 50..100)
    }

    fun process(jpeg: ByteArray, timestampNanos: Long): ProcessedCameraFrame? {
        if (jpeg.isEmpty() || jpeg.size > MAX_SOURCE_JPEG_BYTES) return null
        val sourceDimensions = decodeDimensions(jpeg) ?: return null
        val outputGate = PixelDimensions(limits.maxWidth, limits.maxHeight)
        val outputDimensions = aspectFit(sourceDimensions, outputGate)
        val needsEncoding = outputDimensions != sourceDimensions || jpeg.size > limits.maxJpegBytes
        val decodeTarget = if (needsEncoding) outputDimensions else aspectFit(sourceDimensions, analysisGate)
        val decoded = decodeSampled(jpeg, sourceDimensions, decodeTarget) ?: return null
        var outputBitmap: Bitmap? = null
        var analysisBitmap: Bitmap? = null
        try {
            val prepared = if (needsEncoding &&
                (decoded.width != outputDimensions.width || decoded.height != outputDimensions.height)
            ) {
                Bitmap.createScaledBitmap(decoded, outputDimensions.width, outputDimensions.height, true)
                    .also { outputBitmap = it }
            } else {
                decoded
            }
            val analysisDimensions = aspectFit(
                PixelDimensions(prepared.width, prepared.height),
                analysisGate,
            )
            val analysis = if (prepared.width == analysisDimensions.width &&
                prepared.height == analysisDimensions.height
            ) {
                prepared
            } else {
                Bitmap.createScaledBitmap(prepared, analysisDimensions.width, analysisDimensions.height, true)
                    .also { analysisBitmap = it }
            }
            val decision = frameGate.evaluate(timestampNanos, analysis.toLumaFrame())
            if (!decision.emit) {
                return ProcessedCameraFrame(null, sourceDimensions, outputDimensions, decision)
            }
            val output = if (!needsEncoding) {
                jpeg
            } else {
                encodeWithinLimit(prepared, limits.maxJpegBytes) ?: return null
            }
            return ProcessedCameraFrame(output, sourceDimensions, outputDimensions, decision)
        } finally {
            if (analysisBitmap !== decoded && analysisBitmap !== outputBitmap) analysisBitmap?.recycle()
            if (outputBitmap !== decoded) outputBitmap?.recycle()
            decoded.recycle()
        }
    }

    fun reset() = frameGate.reset()

    private fun decodeDimensions(jpeg: ByteArray): PixelDimensions? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return runCatching { PixelDimensions(options.outWidth, options.outHeight) }.getOrNull()
    }

    private fun decodeSampled(
        jpeg: ByteArray,
        source: PixelDimensions,
        target: PixelDimensions,
    ): Bitmap? {
        var sampleSize = 1
        while (source.width / (sampleSize * 2) >= target.width &&
            source.height / (sampleSize * 2) >= target.height
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
    }

    private fun Bitmap.toLumaFrame(): LumaFrame {
        val colors = IntArray(width * height)
        getPixels(colors, 0, width, 0, 0, width, height)
        val luma = ByteArray(colors.size)
        for (index in colors.indices) {
            val color = colors[index]
            val red = color shr 16 and 0xFF
            val green = color shr 8 and 0xFF
            val blue = color and 0xFF
            luma[index] = ((77 * red + 150 * green + 29 * blue + 128) shr 8).toByte()
        }
        return LumaFrame(width, height, luma)
    }

    private fun encodeWithinLimit(bitmap: Bitmap, maximumBytes: Int): ByteArray? {
        var quality = outputQuality
        while (quality >= MINIMUM_OUTPUT_QUALITY) {
            val buffer = ByteArrayOutputStream(minOf(maximumBytes, INITIAL_OUTPUT_CAPACITY))
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, buffer)) return null
            if (buffer.size() <= maximumBytes) return buffer.toByteArray()
            quality -= QUALITY_STEP
        }
        return null
    }

    companion object {
        private const val MAX_SOURCE_JPEG_BYTES = 20 * 1_024 * 1_024
        private const val INITIAL_OUTPUT_CAPACITY = 512 * 1_024
        private const val MINIMUM_OUTPUT_QUALITY = 58
        private const val QUALITY_STEP = 10
    }
}
