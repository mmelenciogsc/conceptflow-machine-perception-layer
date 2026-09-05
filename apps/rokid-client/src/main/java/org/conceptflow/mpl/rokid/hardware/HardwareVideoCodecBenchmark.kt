// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.hardware

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.SystemClock
import java.nio.ByteBuffer
import kotlin.math.ceil

enum class CodecInputLayout {
    I420,
    NV12,
}

data class HardwareCodecBenchmarkResult(
    val mediaType: String,
    val codecName: String?,
    val hardwareAccelerated: Boolean,
    val inputLayout: CodecInputLayout?,
    val inputFrames: Int,
    val outputFrames: Int,
    val encodedBytes: Long,
    val keyFrames: Int,
    val elapsedNanos: Long,
    val encodeLatencyP50Nanos: Long?,
    val encodeLatencyP95Nanos: Long?,
    val failure: String?,
) {
    val succeeded: Boolean
        get() = failure == null && hardwareAccelerated && outputFrames == inputFrames && encodedBytes > 0L
}

/**
 * Bounded, content-free MediaCodec proof using the exact 640x640 post-gate pixel layout.
 *
 * This is deliberately not on the production camera path. It establishes actual hardware
 * encoder initialization, accepted byte-buffer layout, output size and encode latency before a
 * stateful codec is permitted to alter the independently decodable I420 transport contract.
 */
class HardwareVideoCodecBenchmark(
    private val width: Int = 640,
    private val height: Int = 640,
    private val frameRate: Int = 5,
    private val frameCount: Int = 30,
) {
    init {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
        require(frameRate in 1..30)
        require(frameCount in 1..120)
    }

    fun runAll(): List<HardwareCodecBenchmarkResult> = listOf(
        run(MediaFormat.MIMETYPE_VIDEO_AVC, 1_500_000),
        run(MediaFormat.MIMETYPE_VIDEO_HEVC, 1_000_000),
    )

    private fun run(mediaType: String, bitRate: Int): HardwareCodecBenchmarkResult {
        val candidate = findCandidate(mediaType) ?: return failed(mediaType, "no_hardware_encoder")
        val (codecInfo, colorFormat, inputLayout) = candidate
        val sourceI420 = syntheticI420(width, height)
        val encoder = runCatching { MediaCodec.createByCodecName(codecInfo.name) }.getOrElse {
            return failed(mediaType, "create_failed", codecInfo.name, codecInfo.isHardwareAccelerated)
        }
        val queuedAt = HashMap<Long, Long>(frameCount)
        val latencies = ArrayList<Long>(frameCount)
        val info = MediaCodec.BufferInfo()
        var inputFrames = 0
        var outputFrames = 0
        var encodedBytes = 0L
        var keyFrames = 0
        var inputEnded = false
        var outputEnded = false
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val frameIntervalNanos = 1_000_000_000L / frameRate
        return try {
            val format = MediaFormat.createVideoFormat(mediaType, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_LATENCY, 0)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val deadline = Math.addExact(startedAt, MAXIMUM_RUN_NANOS)
            while (!outputEnded && SystemClock.elapsedRealtimeNanos() < deadline) {
                val now = SystemClock.elapsedRealtimeNanos()
                val nextInputDue = Math.addExact(startedAt, inputFrames.toLong() * frameIntervalNanos)
                if (!inputEnded && now >= nextInputDue) {
                    val inputIndex = encoder.dequeueInputBuffer(0L)
                    if (inputIndex >= 0) {
                        val input = requireNotNull(encoder.getInputBuffer(inputIndex)).apply { clear() }
                        if (inputFrames < frameCount) {
                            putFrame(input, sourceI420, inputLayout, inputFrames)
                            val presentationMicros = inputFrames.toLong() * 1_000_000L / frameRate
                            queuedAt[presentationMicros] = SystemClock.elapsedRealtimeNanos()
                            encoder.queueInputBuffer(
                                inputIndex,
                                0,
                                sourceI420.size,
                                presentationMicros,
                                0,
                            )
                            inputFrames += 1
                        } else {
                            encoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                frameCount.toLong() * 1_000_000L / frameRate,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        }
                    }
                }
                var outputIndex = encoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_MICROS)
                while (outputIndex >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        encodedBytes = Math.addExact(encodedBytes, info.size.toLong())
                        outputFrames += 1
                        if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) keyFrames += 1
                        queuedAt.remove(info.presentationTimeUs)?.let { queued ->
                            latencies += SystemClock.elapsedRealtimeNanos() - queued
                        }
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnded = true
                    encoder.releaseOutputBuffer(outputIndex, false)
                    outputIndex = encoder.dequeueOutputBuffer(info, 0L)
                }
            }
            val failure = when {
                !outputEnded -> "deadline"
                inputFrames != frameCount -> "incomplete_input"
                outputFrames != frameCount -> "incomplete_output"
                encodedBytes == 0L -> "empty_output"
                else -> null
            }
            HardwareCodecBenchmarkResult(
                mediaType,
                codecInfo.name,
                codecInfo.isHardwareAccelerated,
                inputLayout,
                inputFrames,
                outputFrames,
                encodedBytes,
                keyFrames,
                SystemClock.elapsedRealtimeNanos() - startedAt,
                percentile(latencies, 0.50),
                percentile(latencies, 0.95),
                failure,
            )
        } catch (error: Throwable) {
            failed(
                mediaType,
                error.javaClass.simpleName.lowercase(),
                codecInfo.name,
                codecInfo.isHardwareAccelerated,
                inputLayout,
                inputFrames,
            )
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
    }

    private fun findCandidate(
        mediaType: String,
    ): Triple<MediaCodecInfo, Int, CodecInputLayout>? = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        .codecInfos
        .asSequence()
        .filter { it.isEncoder && it.isHardwareAccelerated }
        .filter { info -> info.supportedTypes.any { it.equals(mediaType, ignoreCase = true) } }
        .mapNotNull { info ->
            val capabilities = runCatching { info.getCapabilitiesForType(mediaType) }.getOrNull()
                ?: return@mapNotNull null
            if (capabilities.videoCapabilities?.areSizeAndRateSupported(
                    width,
                    height,
                    frameRate.toDouble(),
                ) != true
            ) return@mapNotNull null
            chooseColorFormat(capabilities.colorFormats)?.let { (format, layout) ->
                Triple(info, format, layout)
            }
        }
        .sortedBy { (info) -> if (info.name.startsWith("c2.qti.")) 0 else 1 }
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

    private fun putFrame(target: ByteBuffer, i420: ByteArray, layout: CodecInputLayout, frameIndex: Int) {
        val movingLuma = (frameIndex * 7).toByte()
        i420[frameIndex % (width * height)] = movingLuma
        when (layout) {
            CodecInputLayout.I420 -> target.put(i420)
            CodecInputLayout.NV12 -> {
                val lumaBytes = width * height
                val chromaBytes = lumaBytes / 4
                target.put(i420, 0, lumaBytes)
                for (index in 0 until chromaBytes) {
                    target.put(i420[lumaBytes + index])
                    target.put(i420[lumaBytes + chromaBytes + index])
                }
            }
        }
    }

    private fun syntheticI420(width: Int, height: Int): ByteArray {
        val lumaBytes = width * height
        return ByteArray(lumaBytes + lumaBytes / 2).also { bytes ->
            for (index in 0 until lumaBytes) bytes[index] = (32 + index % 192).toByte()
            bytes.fill(128.toByte(), lumaBytes)
        }
    }

    private fun percentile(values: List<Long>, quantile: Double): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val index = ceil(quantile * sorted.size).toInt().coerceIn(1, sorted.size) - 1
        return sorted[index]
    }

    private fun failed(
        mediaType: String,
        reason: String,
        codecName: String? = null,
        hardwareAccelerated: Boolean = false,
        inputLayout: CodecInputLayout? = null,
        inputFrames: Int = 0,
    ) = HardwareCodecBenchmarkResult(
        mediaType,
        codecName,
        hardwareAccelerated,
        inputLayout,
        inputFrames,
        0,
        0L,
        0,
        0L,
        null,
        null,
        reason,
    )

    companion object {
        private const val DEQUEUE_TIMEOUT_MICROS = 10_000L
        private const val MAXIMUM_RUN_NANOS = 15_000_000_000L
    }
}
