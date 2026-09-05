// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.SystemClock
import com.google.protobuf.ByteString
import com.google.protobuf.UnsafeByteOperations
import java.io.Closeable
import java.security.MessageDigest
import org.conceptflow.mpl.transport.AvcAnnexBAccessUnit
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.ImageEncoding

/** Persistent, thread-confined hardware decoder for negotiated AVC-intra camera transport. */
internal class HardwareAvcIntraFrameDecoder : Closeable {
    private var state: DecoderState? = null

    val codecName: String? get() = state?.codecName

    fun decode(frame: FramePayload): FramePayload {
        require(frame.image.encoding == ImageEncoding.IMAGE_ENCODING_AVC_ANNEX_B_INTRA)
        require(frame.image.mediaType == AvcAnnexBAccessUnit.MEDIA_TYPE)
        require(frame.image.rowStrideBytes == 0)
        val encoded = frame.frameData.toByteArray()
        val inspection = AvcAnnexBAccessUnit.requireIndependent(encoded)
        val active = ensureState(
            frame.image.width,
            frame.image.height,
            checkNotNull(inspection.sequenceParameterSet),
            checkNotNull(inspection.pictureParameterSet),
        )
        return try {
            val decoded = active.decode(encoded, frame.captureMonotonicTimestampNs)
            frame.toI420(decoded)
        } catch (error: Throwable) {
            reset()
            throw error
        }
    }

    fun reset() {
        state?.close()
        state = null
    }

    override fun close() = reset()

    private fun ensureState(width: Int, height: Int, sps: ByteArray, pps: ByteArray): DecoderState {
        state?.takeIf { it.width == width && it.height == height }?.let { return it }
        reset()
        return DecoderState.create(width, height, sps, pps).also { state = it }
    }

    private class DecoderState private constructor(
        val width: Int,
        val height: Int,
        val codecName: String,
        private val codec: MediaCodec,
    ) : Closeable {
        private val info = MediaCodec.BufferInfo()

        fun decode(accessUnit: ByteArray, captureNs: Long): ByteArray {
            val inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            check(inputIndex >= 0) { "AVC decoder input deadline exceeded" }
            val input = requireNotNull(codec.getInputBuffer(inputIndex)).apply { clear() }
            check(input.remaining() >= accessUnit.size) { "AVC access unit exceeds decoder input" }
            input.put(accessUnit)
            val presentationUs = captureNs / 1_000L
            codec.queueInputBuffer(inputIndex, 0, accessUnit.size, presentationUs, 0)
            val deadline = SystemClock.elapsedRealtimeNanos() + OUTPUT_TIMEOUT_NS
            while (SystemClock.elapsedRealtimeNanos() < deadline) {
                val outputIndex = codec.dequeueOutputBuffer(info, DEQUEUE_SLICE_US)
                if (outputIndex >= 0) {
                    val image = codec.getOutputImage(outputIndex)
                    val decoded = image?.use { copyI420(it, width, height) }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (decoded != null && info.presentationTimeUs == presentationUs) return decoded
                }
            }
            throw IllegalStateException("AVC decoder output deadline exceeded")
        }

        override fun close() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }

        companion object {
            fun create(width: Int, height: Int, sps: ByteArray, pps: ByteArray): DecoderState {
                require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
                val codecInfo = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.asSequence()
                    .filter { !it.isEncoder && it.isHardwareAccelerated }
                    .filter { codec -> codec.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
                    .filter { codec ->
                        runCatching {
                            codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                                .videoCapabilities?.areSizeAndRateSupported(width, height, 5.0) == true
                        }.getOrDefault(false)
                    }
                    .sortedBy { if (it.name.startsWith("c2.qti.")) 0 else 1 }
                    .firstOrNull()
                    ?: throw IllegalStateException("No hardware AVC decoder supports the camera contract")
                val codec = MediaCodec.createByCodecName(codecInfo.name)
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(sps))
                    setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(pps))
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    setInteger(MediaFormat.KEY_LATENCY, 0)
                }
                try {
                    codec.configure(format, null, null, 0)
                    codec.start()
                } catch (error: Throwable) {
                    runCatching { codec.release() }
                    throw error
                }
                return DecoderState(width, height, codecInfo.name, codec)
            }
        }
    }

    private companion object {
        const val INPUT_TIMEOUT_US = 100_000L
        const val DEQUEUE_SLICE_US = 10_000L
        const val OUTPUT_TIMEOUT_NS = 500_000_000L

        fun copyI420(image: Image, expectedWidth: Int, expectedHeight: Int): ByteArray {
            require(image.format == ImageFormat.YUV_420_888 && image.planes.size == 3)
            val crop = image.cropRect
            require(crop.width() == expectedWidth && crop.height() == expectedHeight)
            val lumaBytes = expectedWidth * expectedHeight
            val output = ByteArray(lumaBytes + lumaBytes / 2)
            copyPlane(image.planes[0], crop.left, crop.top, expectedWidth, expectedHeight, output, 0)
            copyPlane(
                image.planes[1], crop.left / 2, crop.top / 2,
                expectedWidth / 2, expectedHeight / 2, output, lumaBytes,
            )
            copyPlane(
                image.planes[2], crop.left / 2, crop.top / 2,
                expectedWidth / 2, expectedHeight / 2, output, lumaBytes + lumaBytes / 4,
            )
            return output
        }

        fun copyPlane(
            plane: Image.Plane,
            sourceLeft: Int,
            sourceTop: Int,
            width: Int,
            height: Int,
            target: ByteArray,
            targetOffset: Int,
        ) {
            val buffer = plane.buffer.duplicate()
            val origin = buffer.position()
            var destination = targetOffset
            repeat(height) { y ->
                val row = origin + (sourceTop + y) * plane.rowStride + sourceLeft * plane.pixelStride
                repeat(width) { x -> target[destination++] = buffer.get(row + x * plane.pixelStride) }
            }
        }

        fun FramePayload.toI420(bytes: ByteArray): FramePayload {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return toBuilder()
                .setStreamId("$streamId-decoded-i420")
                .setImage(
                    image.toBuilder()
                        .setRowStrideBytes(image.width)
                        .setEncoding(ImageEncoding.IMAGE_ENCODING_YUV420_I420)
                        .setMediaType("application/x-conceptflow-i420")
                        .setPayloadBytes(bytes.size.toLong())
                        .setSha256(ByteString.copyFrom(digest)),
                )
                .setFrameData(UnsafeByteOperations.unsafeWrap(bytes))
                .build()
        }
    }
}
