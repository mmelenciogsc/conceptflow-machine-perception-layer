// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.hardware

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import java.io.Closeable
import java.nio.ByteBuffer
import org.conceptflow.mpl.transport.AvcAnnexBAccessUnit

/**
 * Thread-confined hardware AVC encoder for one independently decodable access unit per admitted
 * camera frame. It deliberately gives up inter-frame compression so the bounded latest-frame
 * transport may discard any pending frame without corrupting its successor.
 */
internal class HardwareAvcIntraFrameEncoder(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val bitRate: Int = 1_500_000,
) : Closeable {
    private val selection = selectEncoder(width, height, frameRate)
        ?: throw IllegalStateException("No hardware AVC byte-buffer encoder supports the camera contract")
    private val codec = MediaCodec.createByCodecName(selection.codecName)
    private val info = MediaCodec.BufferInfo()
    private var codecConfig = ByteArray(0)
    private var closed = false

    val codecName: String get() = selection.codecName

    init {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
        require(frameRate in 1..30 && bitRate > 0)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, selection.colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_LATENCY, 0)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (error: Throwable) {
            runCatching { codec.release() }
            throw error
        }
    }

    fun encode(i420: ByteArray, captureMonotonicNs: Long): ByteArray {
        check(!closed) { "AVC encoder is closed" }
        require(captureMonotonicNs > 0L)
        val expected = Math.addExact(Math.multiplyExact(width, height), width * height / 2)
        require(i420.size == expected) { "I420 input does not match AVC encoder dimensions" }
        val inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        check(inputIndex >= 0) { "AVC encoder input deadline exceeded" }
        val input = requireNotNull(codec.getInputBuffer(inputIndex)).apply { clear() }
        writeInput(input, i420, selection.layout, width, height)
        codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        val presentationUs = captureMonotonicNs / 1_000L
        codec.queueInputBuffer(inputIndex, 0, expected, presentationUs, 0)

        val deadline = SystemClock.elapsedRealtimeNanos() + OUTPUT_TIMEOUT_NS
        while (SystemClock.elapsedRealtimeNanos() < deadline) {
            val outputIndex = codec.dequeueOutputBuffer(info, DEQUEUE_SLICE_US)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> captureFormatConfig(codec.outputFormat)
                outputIndex >= 0 -> {
                    val bytes = copyOutput(outputIndex, info)
                    val flags = info.flags
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        if (bytes.isNotEmpty()) codecConfig = normalizeAnnexB(bytes)
                        continue
                    }
                    if (bytes.isEmpty() || info.presentationTimeUs != presentationUs) continue
                    check(flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) {
                        "AVC encoder emitted a dependent frame"
                    }
                    val normalized = normalizeAnnexB(bytes)
                    val candidate = if (AvcAnnexBAccessUnit.inspect(normalized).independentlyDecodable) {
                        normalized
                    } else {
                        AvcAnnexBAccessUnit.join(codecConfig, normalized)
                    }
                    AvcAnnexBAccessUnit.requireIndependent(candidate)
                    return candidate
                }
            }
        }
        throw IllegalStateException("AVC encoder output deadline exceeded")
    }

    private fun captureFormatConfig(format: MediaFormat) {
        val parts = listOf("csd-0", "csd-1").mapNotNull { key ->
            format.getByteBuffer(key)?.let(::copyRemaining)?.let(::normalizeAnnexB)
        }
        if (parts.isNotEmpty()) codecConfig = AvcAnnexBAccessUnit.join(*parts.toTypedArray())
    }

    private fun copyOutput(index: Int, bufferInfo: MediaCodec.BufferInfo): ByteArray {
        if (bufferInfo.size <= 0) return ByteArray(0)
        val buffer = requireNotNull(codec.getOutputBuffer(index)).duplicate()
        buffer.position(bufferInfo.offset)
        buffer.limit(bufferInfo.offset + bufferInfo.size)
        return copyRemaining(buffer)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        codecConfig.fill(0)
        codecConfig = ByteArray(0)
    }

    private data class Selection(
        val codecName: String,
        val colorFormat: Int,
        val layout: CodecInputLayout,
    )

    companion object {
        private const val INPUT_TIMEOUT_US = 100_000L
        private const val DEQUEUE_SLICE_US = 10_000L
        private const val OUTPUT_TIMEOUT_NS = 500_000_000L

        private fun selectEncoder(width: Int, height: Int, frameRate: Int): Selection? =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.asSequence()
                .filter { it.isEncoder && it.isHardwareAccelerated }
                .filter { codec -> codec.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
                .mapNotNull { codec ->
                    val capabilities = runCatching {
                        codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    }.getOrNull() ?: return@mapNotNull null
                    if (capabilities.videoCapabilities?.areSizeAndRateSupported(
                            width,
                            height,
                            frameRate.toDouble(),
                        ) != true
                    ) return@mapNotNull null
                    chooseColorFormat(capabilities.colorFormats)?.let { (format, layout) ->
                        Selection(codec.name, format, layout)
                    }
                }
                .sortedBy { if (it.codecName.startsWith("c2.qti.")) 0 else 1 }
                .firstOrNull()

        @Suppress("DEPRECATION")
        private fun chooseColorFormat(formats: IntArray): Pair<Int, CodecInputLayout>? = when {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar in formats ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar to CodecInputLayout.I420
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar in formats ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar to CodecInputLayout.NV12
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in formats ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible to CodecInputLayout.I420
            else -> null
        }

        private fun writeInput(
            target: ByteBuffer,
            i420: ByteArray,
            layout: CodecInputLayout,
            width: Int,
            height: Int,
        ) {
            when (layout) {
                CodecInputLayout.I420 -> target.put(i420)
                CodecInputLayout.NV12 -> {
                    val lumaBytes = width * height
                    val chromaBytes = lumaBytes / 4
                    target.put(i420, 0, lumaBytes)
                    repeat(chromaBytes) { index ->
                        target.put(i420[lumaBytes + index])
                        target.put(i420[lumaBytes + chromaBytes + index])
                    }
                }
            }
        }

        private fun copyRemaining(source: ByteBuffer): ByteArray = ByteArray(source.remaining()).also(source::get)

        /** Converts the common four-byte AVCC length prefix form when a codec does not emit Annex-B. */
        private fun normalizeAnnexB(bytes: ByteArray): ByteArray {
            if (AvcAnnexBAccessUnit.inspect(bytes).nalUnitTypes.isNotEmpty()) return bytes
            val units = ArrayList<ByteArray>()
            var offset = 0
            while (offset + 4 <= bytes.size) {
                val length = ((bytes[offset].toInt() and 0xff) shl 24) or
                    ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                    ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                    (bytes[offset + 3].toInt() and 0xff)
                if (length <= 0 || offset + 4L + length > bytes.size.toLong()) return bytes
                val unit = ByteArray(length + 4)
                unit[3] = 1
                bytes.copyInto(unit, 4, offset + 4, offset + 4 + length)
                units += unit
                offset += 4 + length
            }
            return if (offset == bytes.size && units.isNotEmpty()) {
                AvcAnnexBAccessUnit.join(*units.toTypedArray())
            } else bytes
        }
    }
}
