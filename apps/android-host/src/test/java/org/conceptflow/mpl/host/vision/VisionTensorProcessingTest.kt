// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionTensorProcessingTest {
    @Test
    fun yoloLetterboxIsAspectFitRgbFloat32WithCenteredPadding() {
        val image = RgbImage(
            2,
            1,
            byteArrayOf(
                255.toByte(), 0, 0,
                0, 255.toByte(), 0,
            ),
        )

        val tensor = VisionTensorPreprocessor.yolo640(image)

        assertEquals(listOf(1, 640, 640, 3), tensor.shape)
        assertEquals(320, tensor.transform.resizedHeight)
        assertEquals(160, tensor.transform.padTop)
        assertEquals(640 * 640 * 3 * 4, tensor.bytes.size)
        assertEquals(114f / 255f, tensor.valueAt(0, 0, 0), 0.001f)
        assertEquals(1f, tensor.valueAt(160, 0, 0), 0.001f)
        assertEquals(0f, tensor.valueAt(160, 0, 1), 0.001f)
    }

    @Test
    fun depthLetterboxUsesPinnedImageNetNormalizationAnd392Shape() {
        val image = RgbImage(1, 1, byteArrayOf(255.toByte(), 255.toByte(), 255.toByte()))

        val tensor = VisionTensorPreprocessor.metricDepth392(image)

        assertEquals(listOf(1, 392, 392, 3), tensor.shape)
        assertEquals((1.0 - 0.485) / 0.229, tensor.valueAt(0, 0, 0).toDouble(), 0.002)
        assertEquals((1.0 - 0.456) / 0.224, tensor.valueAt(0, 0, 1).toDouble(), 0.002)
        assertEquals((1.0 - 0.406) / 0.225, tensor.valueAt(0, 0, 2).toDouble(), 0.002)
    }

    @Test
    fun precomputedBilinearAxesPreserveLegacyTensorBytesExactly() {
        val image = RgbImage(7, 3, ByteArray(7 * 3 * 3) { index -> (index * 37 + 11).toByte() })

        val yolo = VisionTensorPreprocessor.yolo640(image)
        val depth = VisionTensorPreprocessor.metricDepth392(image)

        assertArrayEquals(
            legacyPrepare(image, 640, 640, intArrayOf(114, 114, 114)) { _, value -> value / 255.0 },
            yolo.bytes,
        )
        val mean = doubleArrayOf(0.485, 0.456, 0.406)
        val standardDeviation = doubleArrayOf(0.229, 0.224, 0.225)
        assertArrayEquals(
            legacyPrepare(image, 392, 392, intArrayOf(124, 116, 104)) { channel, value ->
                (value / 255.0 - mean[channel]) / standardDeviation[channel]
            },
            depth.bytes,
        )
    }

    @Test
    fun yoloPostprocessingRejectsOutOfVocabularyAndCorrelatesMaskToSource() {
        val rows = FloatArray(300 * 38)
        rows[0] = 160f
        rows[1] = 160f
        rows[2] = 480f
        rows[3] = 480f
        rows[4] = 0.9f
        rows[5] = 0f
        rows[6] = 8f
        val invalidOffset = 38
        rows[invalidOffset] = 100f
        rows[invalidOffset + 1] = 100f
        rows[invalidOffset + 2] = 200f
        rows[invalidOffset + 3] = 200f
        rows[invalidOffset + 4] = 0.99f
        rows[invalidOffset + 5] = 330f
        val prototypes = FloatArray(160 * 160 * 32)
        for (pixel in 0 until 160 * 160) prototypes[pixel * 32] = 1f
        val transform = LetterboxTransform(1_280, 720, 640, 640, 640, 360, 0, 140)

        val detections = YoloFixedVocabularyPostprocessor.process(
            float32(rows),
            float32(prototypes),
            transform,
        )

        assertEquals(1, detections.size)
        assertEquals("person", detections.single().classId)
        assertEquals(1_280, detections.single().geometry.imageWidthPixels)
        assertEquals(720, detections.single().geometry.imageHeightPixels)
        assertTrue(detections.single().maskFingerprint.matches(Regex("[a-f0-9]{64}")))
    }

    @Test
    fun trackerIsBoundedAndDepthSamplingUsesTheSameMaskIdentity() {
        val geometry = InstanceMaskGeometry(392, 392, 0, 0, 392, 392)
        val mask = PrototypeMask(
            160,
            160,
            ByteArray(160 * 160) { 1 },
            LetterboxTransform(392, 392, 640, 640, 640, 640, 0, 0),
        )
        val detection = YoloMaskDetection("door", 0.9, geometry, mask, "a".repeat(64))
        val tracker = BoundedYoloTracker(maximumTracks = 2)

        val first = tracker.update(1, listOf(detection)).single()
        val second = tracker.update(2, listOf(detection)).single()
        val samples = DepthMaskSampler.sample(
            float32(FloatArray(392 * 392) { 2f }),
            LetterboxTransform(392, 392, 392, 392, 392, 392, 0, 0),
            listOf(second),
            maximumSamplesPerObject = 32,
        )

        assertEquals(first.trackId, second.trackId)
        assertEquals(32, samples.getValue(first.trackId).size)
        assertTrue(samples.getValue(first.trackId).all { it == 2.0 })
    }

    @Test
    fun stagedAdapterRunsExactYoloThenSelected392GraphAndRejects518Fallback() {
        val frame = VisionFrame(1, 100, 4, 2, synthetic = false)
        val encoded = EncodedJpegFrame(1, 100, 4, 2, byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()))
        val openedProfiles = mutableListOf<String>()
        val rows = FloatArray(300 * 38).also {
            it[0] = 160f; it[1] = 160f; it[2] = 480f; it[3] = 480f
            it[4] = 0.9f; it[5] = 0f; it[6] = 8f
        }
        val prototypes = FloatArray(160 * 160 * 32).also {
            for (pixel in 0 until 160 * 160) it[pixel * 32] = 1f
        }
        val factory = QnnModelSessionFactory { profile ->
            openedProfiles += profile.id
            object : QnnModelSession {
                override fun execute(inputFloat32: ByteArray): QnnExecutionResult =
                    if (profile.kind == MachineVisionModelKind.INSTANCE_SEGMENTATION) {
                        QnnExecutionResult(listOf(float32(rows), float32(prototypes)))
                    } else {
                        QnnExecutionResult(listOf(float32(FloatArray(392 * 392) { 2f })))
                    }

                override fun close() = Unit
            }
        }
        val adapter = QnnStagedMachineVisionInferenceAdapter(
            EncodedJpegFrameSource { encoded },
            JpegFrameDecoder { RgbImage(4, 2, ByteArray(4 * 2 * 3) { 127 }) },
            factory,
            clockNanos = { 110 },
        )

        val segmented = adapter.segment(frame)
        val depth = adapter.inferDepth(frame, MachineVisionModelProfiles.depthIndoorBalanced, segmented.objects)

        assertEquals(1, segmented.objects.size)
        assertEquals(listOf(
            MachineVisionModelProfiles.yoloe26sBvi.id,
            MachineVisionModelProfiles.depthIndoorBalanced.id,
        ), openedProfiles)
        assertTrue(depth.relativeDepthSamplesByTrack.values.single().all { it == 2.0 })
        val failure = runCatching {
            adapter.inferDepth(frame, MachineVisionModelProfiles.depthIndoorReference, segmented.objects)
        }.exceptionOrNull() as QnnInferenceException
        assertEquals(QnnFailureCode.PROFILE_UNSUPPORTED, failure.failure.code)
        adapter.close()
    }

    private fun PreparedFloat32Tensor.valueAt(y: Int, x: Int, channel: Int): Float {
        val index = ((y * shape[2] + x) * 3 + channel) * 4
        val bits = (bytes[index].toInt() and 0xff) or
            ((bytes[index + 1].toInt() and 0xff) shl 8) or
            ((bytes[index + 2].toInt() and 0xff) shl 16) or
            ((bytes[index + 3].toInt() and 0xff) shl 24)
        return Float.fromBits(bits)
    }

    private fun float32(values: FloatArray): ByteArray = ByteArray(values.size * 4).also { bytes ->
        values.forEachIndexed { index, value ->
            val bits = value.toRawBits()
            bytes[index * 4] = (bits and 0xff).toByte()
            bytes[index * 4 + 1] = ((bits ushr 8) and 0xff).toByte()
            bytes[index * 4 + 2] = ((bits ushr 16) and 0xff).toByte()
            bytes[index * 4 + 3] = ((bits ushr 24) and 0xff).toByte()
        }
    }

    private fun legacyPrepare(
        image: RgbImage,
        targetWidth: Int,
        targetHeight: Int,
        fillRgb: IntArray,
        normalize: (channel: Int, value: Double) -> Double,
    ): ByteArray {
        val scale = min(targetWidth.toDouble() / image.width, targetHeight.toDouble() / image.height)
        val resizedWidth = max(1, (image.width * scale).roundToInt())
        val resizedHeight = max(1, (image.height * scale).roundToInt())
        val padLeft = (targetWidth - resizedWidth) / 2
        val padTop = (targetHeight - resizedHeight) / 2
        val output = ByteArray(targetWidth * targetHeight * 3 * 4)
        var destination = 0
        for (targetY in 0 until targetHeight) {
            for (targetX in 0 until targetWidth) {
                val inImage = targetX in padLeft until (padLeft + resizedWidth) &&
                    targetY in padTop until (padTop + resizedHeight)
                for (channel in 0..2) {
                    val rgb = if (inImage) {
                        legacyBilinearChannel(
                            image,
                            targetX - padLeft,
                            targetY - padTop,
                            resizedWidth,
                            resizedHeight,
                            channel,
                        )
                    } else {
                        fillRgb[channel].toDouble()
                    }
                    val bits = normalize(channel, rgb).toFloat().toRawBits()
                    output[destination++] = (bits and 0xff).toByte()
                    output[destination++] = ((bits ushr 8) and 0xff).toByte()
                    output[destination++] = ((bits ushr 16) and 0xff).toByte()
                    output[destination++] = ((bits ushr 24) and 0xff).toByte()
                }
            }
        }
        return output
    }

    private fun legacyBilinearChannel(
        image: RgbImage,
        resizedX: Int,
        resizedY: Int,
        resizedWidth: Int,
        resizedHeight: Int,
        channel: Int,
    ): Double {
        val sourceX = ((resizedX + 0.5) * image.width / resizedWidth - 0.5)
            .coerceIn(0.0, (image.width - 1).toDouble())
        val sourceY = ((resizedY + 0.5) * image.height / resizedHeight - 0.5)
            .coerceIn(0.0, (image.height - 1).toDouble())
        val x0 = floor(sourceX).toInt()
        val y0 = floor(sourceY).toInt()
        val x1 = min(x0 + 1, image.width - 1)
        val y1 = min(y0 + 1, image.height - 1)
        val wx = sourceX - x0
        val wy = sourceY - y0
        fun sample(x: Int, y: Int): Double =
            (image.pixels[(y * image.width + x) * 3 + channel].toInt() and 0xff).toDouble()
        return (sample(x0, y0) * (1.0 - wx) + sample(x1, y0) * wx) * (1.0 - wy) +
            (sample(x0, y1) * (1.0 - wx) + sample(x1, y1) * wx) * wy
    }
}
