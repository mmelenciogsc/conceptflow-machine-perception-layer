// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class RgbImage(
    val width: Int,
    val height: Int,
    /** Interleaved RGB8, row-major. */
    val pixels: ByteArray,
) {
    init {
        require(width in 1..MAX_WIDTH && height in 1..MAX_HEIGHT)
        require(width.toLong() * height * CHANNELS <= MAX_RGB_BYTES)
        require(pixels.size.toLong() == width.toLong() * height * CHANNELS)
    }

    private companion object {
        const val CHANNELS = 3
        const val MAX_WIDTH = 7_680
        const val MAX_HEIGHT = 4_320
        const val MAX_RGB_BYTES = 96L * 1_024L * 1_024L
    }
}

data class LetterboxTransform(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val targetWidth: Int,
    val targetHeight: Int,
    val resizedWidth: Int,
    val resizedHeight: Int,
    val padLeft: Int,
    val padTop: Int,
) {
    init {
        require(sourceWidth > 0 && sourceHeight > 0 && targetWidth > 0 && targetHeight > 0)
        require(resizedWidth in 1..targetWidth && resizedHeight in 1..targetHeight)
        require(padLeft in 0..(targetWidth - resizedWidth))
        require(padTop in 0..(targetHeight - resizedHeight))
    }

    fun sourceX(targetX: Double): Double =
        ((targetX - padLeft) * sourceWidth / resizedWidth).coerceIn(0.0, sourceWidth.toDouble())

    fun sourceY(targetY: Double): Double =
        ((targetY - padTop) * sourceHeight / resizedHeight).coerceIn(0.0, sourceHeight.toDouble())

    fun targetX(sourceX: Double): Double = sourceX * resizedWidth / sourceWidth + padLeft
    fun targetY(sourceY: Double): Double = sourceY * resizedHeight / sourceHeight + padTop
}

data class PreparedFloat32Tensor(
    val shape: List<Int>,
    val bytes: ByteArray,
    val transform: LetterboxTransform,
) {
    init {
        require(shape.size in 3..4 && shape.all { it > 0 })
        require(shape.fold(1L) { total, dimension -> total * dimension } * 4L == bytes.size.toLong())
    }
}

object VisionTensorPreprocessor {
    private enum class Normalization { YOLO, METRIC_DEPTH }

    private data class BilinearAxis(
        val lowerOffsets: IntArray,
        val upperOffsets: IntArray,
        val upperWeights: DoubleArray,
    )

    private val depthMean = doubleArrayOf(0.485, 0.456, 0.406)
    private val depthStd = doubleArrayOf(0.229, 0.224, 0.225)

    fun yolo640(image: RgbImage): PreparedFloat32Tensor = prepare(
        image = image,
        targetWidth = 640,
        targetHeight = 640,
        fillRgb = intArrayOf(114, 114, 114),
        normalization = Normalization.YOLO,
    )

    fun metricDepth392(image: RgbImage): PreparedFloat32Tensor = prepare(
        image = image,
        targetWidth = 392,
        targetHeight = 392,
        fillRgb = intArrayOf(124, 116, 104),
        normalization = Normalization.METRIC_DEPTH,
    )

    private fun prepare(
        image: RgbImage,
        targetWidth: Int,
        targetHeight: Int,
        fillRgb: IntArray,
        normalization: Normalization,
    ): PreparedFloat32Tensor {
        val scale = min(targetWidth.toDouble() / image.width, targetHeight.toDouble() / image.height)
        val resizedWidth = max(1, (image.width * scale).roundToInt())
        val resizedHeight = max(1, (image.height * scale).roundToInt())
        val padLeft = (targetWidth - resizedWidth) / 2
        val padTop = (targetHeight - resizedHeight) / 2
        val transform = LetterboxTransform(
            image.width,
            image.height,
            targetWidth,
            targetHeight,
            resizedWidth,
            resizedHeight,
            padLeft,
            padTop,
        )
        val output = ByteArray(targetWidth * targetHeight * 3 * 4)
        val xAxis = bilinearAxis(image.width, resizedWidth, 3)
        val yAxis = bilinearAxis(image.height, resizedHeight, image.width * 3)
        val fillBits = IntArray(3) { channel ->
            normalize(normalization, channel, fillRgb[channel].toDouble()).toRawBits()
        }
        var destination = 0
        for (targetY in 0 until targetHeight) {
            val resizedY = targetY - padTop
            if (resizedY !in 0 until resizedHeight) {
                repeat(targetWidth) { destination = writePixel(output, destination, fillBits) }
                continue
            }
            repeat(padLeft) { destination = writePixel(output, destination, fillBits) }
            val y0 = yAxis.lowerOffsets[resizedY]
            val y1 = yAxis.upperOffsets[resizedY]
            val wy = yAxis.upperWeights[resizedY]
            val lowerYWeight = 1.0 - wy
            for (resizedX in 0 until resizedWidth) {
                val x0 = xAxis.lowerOffsets[resizedX]
                val x1 = xAxis.upperOffsets[resizedX]
                val wx = xAxis.upperWeights[resizedX]
                val lowerXWeight = 1.0 - wx
                val topLeft = y0 + x0
                val topRight = y0 + x1
                val bottomLeft = y1 + x0
                val bottomRight = y1 + x1
                for (channel in 0..2) {
                    val top = sample(image.pixels, topLeft + channel) * lowerXWeight +
                        sample(image.pixels, topRight + channel) * wx
                    val bottom = sample(image.pixels, bottomLeft + channel) * lowerXWeight +
                        sample(image.pixels, bottomRight + channel) * wx
                    val bits = normalize(
                        normalization,
                        channel,
                        top * lowerYWeight + bottom * wy,
                    ).toRawBits()
                    writeFloatBits(output, destination, bits)
                    destination += 4
                }
            }
            repeat(targetWidth - padLeft - resizedWidth) {
                destination = writePixel(output, destination, fillBits)
            }
        }
        check(destination == output.size)
        return PreparedFloat32Tensor(listOf(1, targetHeight, targetWidth, 3), output, transform)
    }

    private fun bilinearAxis(sourceSize: Int, resizedSize: Int, offsetScale: Int): BilinearAxis {
        val lower = IntArray(resizedSize)
        val upper = IntArray(resizedSize)
        val weight = DoubleArray(resizedSize)
        for (destination in 0 until resizedSize) {
            val source = ((destination + 0.5) * sourceSize / resizedSize - 0.5)
                .coerceIn(0.0, (sourceSize - 1).toDouble())
            val sourceLower = floor(source).toInt()
            lower[destination] = sourceLower * offsetScale
            upper[destination] = min(sourceLower + 1, sourceSize - 1) * offsetScale
            weight[destination] = source - sourceLower
        }
        return BilinearAxis(lower, upper, weight)
    }

    private fun normalize(normalization: Normalization, channel: Int, value: Double): Float =
        when (normalization) {
            Normalization.YOLO -> (value / 255.0).toFloat()
            Normalization.METRIC_DEPTH -> ((value / 255.0 - depthMean[channel]) / depthStd[channel]).toFloat()
        }

    private fun sample(pixels: ByteArray, offset: Int): Double =
        (pixels[offset].toInt() and 0xff).toDouble()

    private fun writePixel(output: ByteArray, start: Int, channelBits: IntArray): Int {
        var destination = start
        for (bits in channelBits) {
            writeFloatBits(output, destination, bits)
            destination += 4
        }
        return destination
    }

    private fun writeFloatBits(output: ByteArray, destination: Int, bits: Int) {
        output[destination] = (bits and 0xff).toByte()
        output[destination + 1] = ((bits ushr 8) and 0xff).toByte()
        output[destination + 2] = ((bits ushr 16) and 0xff).toByte()
        output[destination + 3] = ((bits ushr 24) and 0xff).toByte()
    }
}

object Float32TensorCodec {
    fun decodeLittleEndian(bytes: ByteArray, expectedElements: Int): FloatArray {
        require(expectedElements in 1..MAX_TENSOR_ELEMENTS)
        require(bytes.size == expectedElements * 4)
        return FloatArray(expectedElements) { index ->
            val offset = index * 4
            Float.fromBits(
                (bytes[offset].toInt() and 0xff) or
                    ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                    ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                    ((bytes[offset + 3].toInt() and 0xff) shl 24),
            )
        }
    }

    private const val MAX_TENSOR_ELEMENTS = 16 * 1_024 * 1_024
}

data class PrototypeMask(
    val width: Int,
    val height: Int,
    val foreground: ByteArray,
    val letterboxTransform: LetterboxTransform,
) {
    init {
        require(width in 1..640 && height in 1..640)
        require(foreground.size == width * height)
    }

    operator fun get(x: Int, y: Int): Boolean =
        x in 0 until width && y in 0 until height && foreground[y * width + x].toInt() != 0
}

data class YoloMaskDetection(
    val classId: String,
    val confidence: Double,
    val geometry: InstanceMaskGeometry,
    val mask: PrototypeMask,
    val maskFingerprint: String,
)

/**
 * Two-tier semantic admission. Proposal detections are retained only as private tracker evidence;
 * they must never reach focus, speech, Unity, or beacon consumers until the maintainer confirms
 * them. A strong observation can be published immediately.
 */
object YoloSemanticConfidencePolicy {
    const val PROPOSAL_CONFIDENCE = 0.45
    const val IMMEDIATE_PUBLICATION_CONFIDENCE = 0.55
    const val CONSISTENT_OBSERVATIONS_REQUIRED = 3
}

object YoloFixedVocabularyPostprocessor {
    private data class PrototypeCoordinates(
        val targetX: DoubleArray,
        val targetY: DoubleArray,
        val sourceX: DoubleArray,
        val sourceY: DoubleArray,
    )

    private const val DETECTION_COUNT = 300
    private const val DETECTION_COLUMNS = 38
    private const val PROTO_WIDTH = 160
    private const val PROTO_HEIGHT = 160
    private const val PROTO_CHANNELS = 32

    fun process(
        detectionsFloat32: ByteArray,
        prototypesFloat32: ByteArray,
        transform: LetterboxTransform,
        confidenceThreshold: Double = YoloSemanticConfidencePolicy.PROPOSAL_CONFIDENCE,
        maskThreshold: Double = 0.5,
        maximumObjects: Int = 64,
    ): List<YoloMaskDetection> {
        require(confidenceThreshold in 0.0..1.0 && maskThreshold in 0.0..1.0)
        require(maximumObjects in 1..64)
        val rows = Float32TensorCodec.decodeLittleEndian(detectionsFloat32, DETECTION_COUNT * DETECTION_COLUMNS)
        val prototypes = Float32TensorCodec.decodeLittleEndian(
            prototypesFloat32,
            PROTO_WIDTH * PROTO_HEIGHT * PROTO_CHANNELS,
        )
        require(rows.all(Float::isFinite) && prototypes.all(Float::isFinite)) {
            "YOLO graph returned a non-finite value"
        }
        val coordinates = prototypeCoordinates(transform)
        val fingerprintDigest = MessageDigest.getInstance("SHA-256")
        val rankedRows = (0 until DETECTION_COUNT)
            .filter { row -> rows[row * DETECTION_COLUMNS + 4] >= confidenceThreshold }
            .sortedByDescending { row -> rows[row * DETECTION_COLUMNS + 4] }
        val detections = ArrayList<YoloMaskDetection>(min(maximumObjects, rankedRows.size))
        for (row in rankedRows) {
            decodeRow(
                row,
                rows,
                prototypes,
                transform,
                confidenceThreshold,
                maskThreshold,
                coordinates,
                fingerprintDigest,
            )?.let { detection ->
                detections += detection
                if (detections.size == maximumObjects) return detections
            }
        }
        return detections
    }

    private fun decodeRow(
        row: Int,
        rows: FloatArray,
        prototypes: FloatArray,
        transform: LetterboxTransform,
        confidenceThreshold: Double,
        maskThreshold: Double,
        coordinates: PrototypeCoordinates,
        fingerprintDigest: MessageDigest,
    ): YoloMaskDetection? {
        val offset = row * DETECTION_COLUMNS
        val score = rows[offset + 4].toDouble()
        if (!score.isFinite() || score < confidenceThreshold || score > 1.0) return null
        val classValue = rows[offset + 5]
        val classIndex = classValue.roundToInt()
        if (!classValue.isFinite() || classIndex !in BviClassCatalog.bviClassesList.indices ||
            kotlin.math.abs(classValue - classIndex) > 0.01f
        ) return null

        val targetLeft = min(rows[offset], rows[offset + 2]).toDouble()
            .coerceIn(transform.padLeft.toDouble(), (transform.padLeft + transform.resizedWidth).toDouble())
        val targetTop = min(rows[offset + 1], rows[offset + 3]).toDouble()
            .coerceIn(transform.padTop.toDouble(), (transform.padTop + transform.resizedHeight).toDouble())
        val targetRight = max(rows[offset], rows[offset + 2]).toDouble()
            .coerceIn(transform.padLeft.toDouble(), (transform.padLeft + transform.resizedWidth).toDouble())
        val targetBottom = max(rows[offset + 1], rows[offset + 3]).toDouble()
            .coerceIn(transform.padTop.toDouble(), (transform.padTop + transform.resizedHeight).toDouble())
        if (targetRight - targetLeft < 1.0 || targetBottom - targetTop < 1.0) return null

        val sourceLeft = floor(transform.sourceX(targetLeft)).toInt().coerceIn(0, transform.sourceWidth - 1)
        val sourceTop = floor(transform.sourceY(targetTop)).toInt().coerceIn(0, transform.sourceHeight - 1)
        val sourceRight = transform.sourceX(targetRight).roundToInt().coerceIn(sourceLeft + 1, transform.sourceWidth)
        val sourceBottom = transform.sourceY(targetBottom).roundToInt().coerceIn(sourceTop + 1, transform.sourceHeight)

        val maskBytes = ByteArray(PROTO_WIDTH * PROTO_HEIGHT)
        var foregroundCount = 0
        var centroidXSum = 0.0
        var centroidYSum = 0.0
        for (protoY in 0 until PROTO_HEIGHT) {
            val targetY = coordinates.targetY[protoY]
            if (targetY < transform.padTop || targetY >= transform.padTop + transform.resizedHeight) continue
            val sourceY = coordinates.sourceY[protoY]
            if (sourceY < sourceTop || sourceY >= sourceBottom) continue
            for (protoX in 0 until PROTO_WIDTH) {
                val targetX = coordinates.targetX[protoX]
                if (targetX < transform.padLeft || targetX >= transform.padLeft + transform.resizedWidth) continue
                val sourceX = coordinates.sourceX[protoX]
                if (sourceX < sourceLeft || sourceX >= sourceRight) continue
                var logit = 0.0
                val protoOffset = (protoY * PROTO_WIDTH + protoX) * PROTO_CHANNELS
                for (channel in 0 until PROTO_CHANNELS) {
                    logit += rows[offset + 6 + channel] * prototypes[protoOffset + channel]
                }
                val probability = if (logit >= 0.0) 1.0 / (1.0 + exp(-logit)) else exp(logit) / (1.0 + exp(logit))
                if (probability >= maskThreshold) {
                    maskBytes[protoY * PROTO_WIDTH + protoX] = 1
                    foregroundCount += 1
                    centroidXSum += sourceX
                    centroidYSum += sourceY
                }
            }
        }
        if (foregroundCount == 0) return null
        val mask = PrototypeMask(PROTO_WIDTH, PROTO_HEIGHT, maskBytes, transform)
        val fingerprint = hexadecimal(fingerprintDigest.digest(maskBytes))
        return YoloMaskDetection(
            classId = BviClassCatalog.bviClassesList[classIndex].id,
            confidence = score,
            geometry = InstanceMaskGeometry(
                transform.sourceWidth,
                transform.sourceHeight,
                sourceLeft,
                sourceTop,
                sourceRight,
                sourceBottom,
                (centroidXSum / foregroundCount).coerceIn(sourceLeft.toDouble(), sourceRight - 1.0),
                (centroidYSum / foregroundCount).coerceIn(sourceTop.toDouble(), sourceBottom - 1.0),
                foregroundPixelCount = min(
                    (sourceRight - sourceLeft) * (sourceBottom - sourceTop),
                    max(1, (foregroundCount.toLong() * transform.sourceWidth * transform.sourceHeight /
                        (PROTO_WIDTH * PROTO_HEIGHT)).toInt()),
                ),
            ),
            mask = mask,
            maskFingerprint = fingerprint,
        )
    }

    private fun prototypeCoordinates(transform: LetterboxTransform): PrototypeCoordinates {
        val targetX = DoubleArray(PROTO_WIDTH) { index ->
            (index + 0.5) * transform.targetWidth / PROTO_WIDTH
        }
        val targetY = DoubleArray(PROTO_HEIGHT) { index ->
            (index + 0.5) * transform.targetHeight / PROTO_HEIGHT
        }
        return PrototypeCoordinates(
            targetX,
            targetY,
            DoubleArray(PROTO_WIDTH) { transform.sourceX(targetX[it]) },
            DoubleArray(PROTO_HEIGHT) { transform.sourceY(targetY[it]) },
        )
    }

    private fun hexadecimal(bytes: ByteArray): String {
        val characters = CharArray(bytes.size * 2)
        var destination = 0
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            characters[destination++] = HEX_DIGITS[value ushr 4]
            characters[destination++] = HEX_DIGITS[value and 0x0f]
        }
        return String(characters)
    }

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()
}

data class TrackedYoloMaskDetection(
    val trackId: String,
    val detection: YoloMaskDetection,
)

class BoundedYoloTracker(
    private val maximumTracks: Int = 128,
    private val maximumFrameGap: Long = 15,
    private val minimumIou: Double = 0.25,
) {
    private data class Track(var lastFrameId: Long, var detection: YoloMaskDetection)
    private val tracks = linkedMapOf<String, Track>()
    private var nextTrackNumber = 1L
    private var lastFrameId = 0L

    init {
        require(maximumTracks in 1..256 && maximumFrameGap in 1..300 && minimumIou in 0.0..1.0)
    }

    @Synchronized
    fun update(frameId: Long, detections: List<YoloMaskDetection>): List<TrackedYoloMaskDetection> {
        require(frameId > lastFrameId) { "tracker requires strictly increasing frame identifiers" }
        require(detections.size <= 64)
        lastFrameId = frameId
        tracks.entries.removeIf { frameId - it.value.lastFrameId > maximumFrameGap }
        val unusedTracks = tracks.keys.toMutableSet()
        val output = detections.map { detection ->
            val match = unusedTracks.asSequence()
                .filter { tracks.getValue(it).detection.classId == detection.classId }
                .map { it to iou(tracks.getValue(it).detection.geometry, detection.geometry) }
                .filter { it.second >= minimumIou }
                .maxByOrNull { it.second }
                ?.first
            val trackId = match ?: "qnn-${nextTrackNumber++.toString(16).padStart(8, '0')}"
            unusedTracks.remove(trackId)
            tracks[trackId] = Track(frameId, detection)
            TrackedYoloMaskDetection(trackId, detection)
        }
        while (tracks.size > maximumTracks) tracks.remove(tracks.keys.first())
        return output
    }

    @Synchronized
    fun reset() {
        tracks.clear()
        nextTrackNumber = 1L
        lastFrameId = 0L
    }

    private fun iou(first: InstanceMaskGeometry, second: InstanceMaskGeometry): Double {
        val left = max(first.leftPixels, second.leftPixels)
        val top = max(first.topPixels, second.topPixels)
        val right = min(first.rightExclusivePixels, second.rightExclusivePixels)
        val bottom = min(first.bottomExclusivePixels, second.bottomExclusivePixels)
        val intersection = max(0, right - left).toLong() * max(0, bottom - top)
        val union = first.widthPixels.toLong() * first.heightPixels +
            second.widthPixels.toLong() * second.heightPixels - intersection
        return if (union == 0L) 0.0 else intersection.toDouble() / union
    }
}

object DepthMaskSampler {
    fun sample(
        depthFloat32: ByteArray,
        transform: LetterboxTransform,
        detections: List<TrackedYoloMaskDetection>,
        maximumSamplesPerObject: Int = 512,
    ): Map<String, List<Double>> = sample(
        Float32TensorCodec.decodeLittleEndian(depthFloat32, 392 * 392),
        transform,
        detections,
        maximumSamplesPerObject,
    )

    fun sample(
        depth: FloatArray,
        transform: LetterboxTransform,
        detections: List<TrackedYoloMaskDetection>,
        maximumSamplesPerObject: Int = 512,
    ): Map<String, List<Double>> {
        require(transform.targetWidth == 392 && transform.targetHeight == 392)
        require(maximumSamplesPerObject in 1..4_096 && detections.size <= 64)
        require(depth.size == 392 * 392 && depth.all { it.isFinite() && it > 0.0f }) {
            "depth tensor must contain exactly 392 by 392 finite positive values"
        }
        return detections.associate { tracked ->
            val mask = tracked.detection.mask
            val candidates = ArrayList<Double>(maximumSamplesPerObject)
            val foregroundCount = mask.foreground.count { it.toInt() != 0 }
            val stride = max(1, foregroundCount / maximumSamplesPerObject)
            var accepted = 0
            var seen = 0
            for (maskY in 0 until mask.height) {
                for (maskX in 0 until mask.width) {
                    if (!mask[maskX, maskY]) continue
                    if (seen++ % stride != 0 || accepted >= maximumSamplesPerObject) continue
                    val sourceX = mask.letterboxTransform.sourceX(
                        (maskX + 0.5) * mask.letterboxTransform.targetWidth / mask.width,
                    )
                    val sourceY = mask.letterboxTransform.sourceY(
                        (maskY + 0.5) * mask.letterboxTransform.targetHeight / mask.height,
                    )
                    val depthX = floor(transform.targetX(sourceX)).toInt().coerceIn(0, 391)
                    val depthY = floor(transform.targetY(sourceY)).toInt().coerceIn(0, 391)
                    val value = depth[depthY * 392 + depthX].toDouble()
                    if (value.isFinite() && value > 0.0) {
                        candidates += value
                        accepted += 1
                    }
                }
            }
            require(candidates.isNotEmpty()) { "no positive finite depth samples for ${tracked.trackId}" }
            tracked.trackId to candidates
        }
    }
}
