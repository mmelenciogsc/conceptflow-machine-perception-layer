// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import android.graphics.BitmapFactory

fun interface JpegFrameDecoder {
    fun decode(jpeg: ByteArray): RgbImage
}

/** Bounded Android JPEG decoder. Pixel normalization and resizing remain pure Kotlin and testable. */
class AndroidJpegDecoder : JpegFrameDecoder {
    override fun decode(jpeg: ByteArray): RgbImage {
        require(jpeg.size in 4..MAX_JPEG_BYTES)
        require(jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte()) { "not a JPEG frame" }
        val bounds = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        require(bounds.outWidth in 1..MAX_WIDTH && bounds.outHeight in 1..MAX_HEIGHT)
        require(bounds.outWidth.toLong() * bounds.outHeight <= MAX_PIXELS)
        val bitmap = requireNotNull(
            BitmapFactory.decodeByteArray(
                jpeg,
                0,
                jpeg.size,
                BitmapFactory.Options().also { it.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 },
            ),
        ) { "JPEG decode failed" }
        try {
            require(bitmap.width == bounds.outWidth && bitmap.height == bounds.outHeight)
            val rgb = ByteArray(bitmap.width * bitmap.height * 3)
            val stripeRows = minOf(PIXEL_STRIPE_ROWS, bitmap.height)
            val argbStripe = IntArray(bitmap.width * stripeRows)
            var top = 0
            var rgbOffset = 0
            while (top < bitmap.height) {
                val rows = minOf(stripeRows, bitmap.height - top)
                val pixelCount = bitmap.width * rows
                bitmap.getPixels(argbStripe, 0, bitmap.width, 0, top, bitmap.width, rows)
                var index = 0
                while (index < pixelCount) {
                    val pixel = argbStripe[index]
                    rgb[rgbOffset++] = ((pixel ushr 16) and 0xff).toByte()
                    rgb[rgbOffset++] = ((pixel ushr 8) and 0xff).toByte()
                    rgb[rgbOffset++] = (pixel and 0xff).toByte()
                    index += 1
                }
                top += rows
            }
            check(rgbOffset == rgb.size)
            return RgbImage(bitmap.width, bitmap.height, rgb)
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val MAX_JPEG_BYTES = 16 * 1_024 * 1_024
        const val MAX_WIDTH = 7_680
        const val MAX_HEIGHT = 4_320
        const val MAX_PIXELS = 33_177_600L
        const val PIXEL_STRIPE_ROWS = 32
    }
}
