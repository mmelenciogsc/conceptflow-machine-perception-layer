// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QnnLiveFrameExecutorTest {
    @Test
    fun `packed RGB frame bypasses JPEG decoding and preserves correlation`() {
        var decoderCalls = 0
        var observedGeneration = 0L
        val observed = mutableListOf<Pair<VisionFrame, RgbImage>>()
        val executor = QnnLiveFrameExecutor(
            QnnModelSessionFactory { profile -> FakeSession(profile, mutableListOf()) },
            JpegFrameDecoder {
                decoderCalls += 1
                error("raw frame must not enter JPEG decoder")
            },
            System::nanoTime,
            { generation, frame, image ->
                observedGeneration = generation
                observed += frame to image
            },
        )
        val raw = RawRgbFrame(1L, 10L, 1, 1, 3, byteArrayOf(10, 20, 30))
        val vision = VisionFrame(1L, 10L, 1, 1, synthetic = false)

        val result = requireNotNull(executor.process(raw, vision, 7L) {
            MachineVisionModelProfiles.depthIndoorBalanced
        })

        assertEquals(0, decoderCalls)
        assertEquals(7L, observedGeneration)
        assertEquals(1L, result.frameId)
        assertEquals(listOf(vision to RgbImage(1, 1, raw.rgb)), observed)
        executor.close()
    }

    @Test
    fun `packed I420 frame converts on the host and preserves correlation`() {
        var decoderCalls = 0
        var observed: RgbImage? = null
        val executor = QnnLiveFrameExecutor(
            QnnModelSessionFactory { profile -> FakeSession(profile, mutableListOf()) },
            JpegFrameDecoder {
                decoderCalls += 1
                error("I420 frame must not enter JPEG decoder")
            },
            System::nanoTime,
            { _, _, image -> observed = image },
        )
        val i420 = RawI420Frame(
            frameId = 1L,
            captureMonotonicTimestampNanos = 10L,
            width = 2,
            height = 2,
            rowStrideBytes = 2,
            i420 = byteArrayOf(82, 82, 82, 82, 90, 240.toByte()),
        )
        val vision = VisionFrame(1L, 10L, 2, 2, synthetic = false)

        val result = requireNotNull(executor.process(i420, vision) {
            MachineVisionModelProfiles.depthIndoorBalanced
        })

        assertEquals(0, decoderCalls)
        assertEquals(1L, result.frameId)
        val rgb = requireNotNull(observed).pixels
        rgb.indices.step(3).forEach { offset ->
            assertEquals(255, rgb[offset].toInt() and 0xff)
            assertEquals(1, rgb[offset + 1].toInt() and 0xff)
            assertEquals(0, rgb[offset + 2].toInt() and 0xff)
        }
        executor.close()
    }

    @Test
    fun `decoded JPEG source is observed as RGB without a second encoding step`() {
        var observerCalls = 0
        val decoded = RgbImage(1, 1, byteArrayOf(4, 5, 6))
        val encoded = encoded(1L)
        val executor = QnnLiveFrameExecutor(
            QnnModelSessionFactory { profile -> FakeSession(profile, mutableListOf()) },
            JpegFrameDecoder { decoded },
            System::nanoTime,
            { _, frame, image ->
                observerCalls += 1
                assertEquals(encoded.frameId, frame.frameId)
                assertEquals(decoded, image)
            },
        )

        requireNotNull(executor.process(encoded, visionFrame(encoded)) {
            MachineVisionModelProfiles.depthIndoorBalanced
        })

        assertEquals(1, observerCalls)
        executor.close()
    }

    @Test
    fun `opens and executes only YOLO plus selected balanced depth graph`() {
        val opened = mutableListOf<String>()
        val executed = mutableListOf<String>()
        val factory = QnnModelSessionFactory { profile ->
            opened += profile.id
            FakeSession(profile, executed)
        }
        val times = ArrayDeque(listOf(100L, 110L, 120L, 140L, 150L, 160L, 170L, 200L, 210L))
        val executor = QnnLiveFrameExecutor(
            factory,
            JpegFrameDecoder { RgbImage(1, 1, byteArrayOf(1, 2, 3)) },
            clockNanos = { times.removeFirst() },
        )

        val encoded = encoded(1)
        val result = requireNotNull(executor.process(
            encoded,
            visionFrame(encoded),
            selectDepthProfile = { MachineVisionModelProfiles.depthOutdoorBalanced },
        ))
        executor.close()

        assertEquals(
            listOf(MachineVisionModelProfiles.yoloe26sBvi.id, MachineVisionModelProfiles.depthOutdoorBalanced.id),
            opened,
        )
        assertEquals(opened, executed)
        assertEquals(MachineVisionModelProfiles.depthOutdoorBalanced.id, result.selectedDepthProfileId)
        assertEquals(10L, result.decodeLatencyNanos)
        assertEquals(10L, result.yoloPreprocessLatencyNanos)
        assertEquals(20L, result.segmentationLatencyNanos)
        assertEquals(10L, result.yoloPostprocessLatencyNanos)
        assertEquals(10L, result.modelSetupLatencyNanos)
        assertEquals(10L, result.depthPreprocessLatencyNanos)
        assertEquals(30L, result.depthLatencyNanos)
        assertEquals(10L, result.depthPostprocessLatencyNanos)
        assertEquals(110L, result.totalLatencyNanos)
        assertEquals(
            result.totalLatencyNanos,
            result.decodeLatencyNanos + result.yoloPreprocessLatencyNanos +
                result.segmentationLatencyNanos + result.yoloPostprocessLatencyNanos +
                result.modelSetupLatencyNanos + result.depthPreprocessLatencyNanos +
                result.depthLatencyNanos + result.depthPostprocessLatencyNanos,
        )
        assertEquals(LiveMetricCalibrationState.UNCALIBRATED_INTRINSICS_MISSING, result.calibrationState)
        assertEquals(392 * 392, result.finitePositiveDepthValues)
        assertTrue(result.inference.observations.isEmpty())
    }

    @Test
    fun `returns bounded stable masks with selected depth samples instead of tensors`() {
        val rows = FloatArray(300 * 38).also {
            it[0] = 160f
            it[1] = 160f
            it[2] = 480f
            it[3] = 480f
            it[4] = 0.9f
            it[5] = 0f
            it[6] = 8f
        }
        val prototypes = FloatArray(160 * 160 * 32).also {
            for (pixel in 0 until 160 * 160) it[pixel * 32] = 1f
        }
        val factory = QnnModelSessionFactory { profile ->
            object : QnnModelSession {
                override fun execute(inputFloat32: ByteArray): QnnExecutionResult =
                    if (profile.kind == MachineVisionModelKind.INSTANCE_SEGMENTATION) {
                        QnnExecutionResult(listOf(floatBytes(rows), floatBytes(prototypes)))
                    } else {
                        QnnExecutionResult(listOf(floatBytes(FloatArray(392 * 392) { 2f })))
                    }

                override fun close() = Unit
            }
        }
        val executor = QnnLiveFrameExecutor(
            factory,
            JpegFrameDecoder { RgbImage(640, 360, ByteArray(640 * 360 * 3) { 127 }) },
            System::nanoTime,
        )
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
        val first = EncodedJpegFrame(1, 1, 640, 360, jpeg)
        val second = EncodedJpegFrame(2, 2, 640, 360, jpeg)

        val firstResult = requireNotNull(executor.process(first, visionFrame(first)) {
            MachineVisionModelProfiles.depthIndoorBalanced
        })
        val secondResult = requireNotNull(executor.process(second, visionFrame(second)) {
            MachineVisionModelProfiles.depthIndoorBalanced
        })

        val firstObservation = firstResult.inference.observations.single()
        val secondObservation = secondResult.inference.observations.single()
        assertEquals("person", firstObservation.classId)
        assertEquals(firstObservation.trackId, secondObservation.trackId)
        assertTrue(firstObservation.relativeDepthSamples.size in 1..512)
        assertTrue(firstObservation.relativeDepthSamples.all { it == 2.0 })
        assertEquals(640, requireNotNull(firstObservation.maskGeometry).imageWidthPixels)
        executor.close()
    }

    @Test
    fun `executor reports derived intrinsics without upgrading them to calibrated`() {
        val factory = QnnModelSessionFactory { profile -> FakeSession(profile, mutableListOf()) }
        val executor = QnnLiveFrameExecutor(
            factory,
            JpegFrameDecoder { RgbImage(1, 1, byteArrayOf(1, 2, 3)) },
            System::nanoTime,
        )
        val encoded = encoded(1)
        val derived = visionFrame(encoded).copy(
            cameraIntrinsics = CameraIntrinsics(
                1,
                1,
                1.0,
                1.0,
                0.0,
                0.0,
                CameraIntrinsicsSource.DERIVED,
            ),
        )

        val result = requireNotNull(executor.process(encoded, derived) {
            MachineVisionModelProfiles.depthIndoorBalanced
        })

        assertEquals(LiveMetricCalibrationState.DERIVED_INTRINSICS_PRESENT, result.calibrationState)
        executor.close()
    }

    @Test
    fun `reference and low-power profiles cannot enter bounded live validation`() {
        val factory = QnnModelSessionFactory { profile -> FakeSession(profile, mutableListOf()) }
        val executor = QnnLiveFrameExecutor(
            factory,
            JpegFrameDecoder { RgbImage(1, 1, byteArrayOf(1, 2, 3)) },
            System::nanoTime,
        )
        val encoded = encoded(1)
        assertThrows(IllegalArgumentException::class.java) {
            executor.process(
                encoded,
                visionFrame(encoded),
                selectDepthProfile = { MachineVisionModelProfiles.depthIndoorReference },
            )
        }
        executor.close()
    }

    @Test
    fun `automatic selection runs segmentation before opening any depth session`() {
        val opened = mutableListOf<String>()
        val executed = mutableListOf<String>()
        val factory = QnnModelSessionFactory { profile ->
            opened += profile.id
            FakeSession(profile, executed)
        }
        val executor = QnnLiveFrameExecutor(
            factory,
            JpegFrameDecoder { RgbImage(1, 1, byteArrayOf(1, 2, 3)) },
            System::nanoTime,
        )
        val first = encoded(1)
        val pending = executor.process(first, visionFrame(first)) { null }

        assertEquals(null, pending)
        assertEquals(listOf(MachineVisionModelProfiles.yoloe26sBvi.id), opened)
        assertEquals(listOf(MachineVisionModelProfiles.yoloe26sBvi.id), executed)

        val second = encoded(2)
        requireNotNull(executor.process(second, visionFrame(second)) {
            MachineVisionModelProfiles.depthIndoorBalanced
        })
        assertEquals(MachineVisionModelProfiles.depthIndoorBalanced.id, opened.last())
        assertEquals(MachineVisionModelProfiles.depthIndoorBalanced.id, executed.last())
        executor.close()
    }

    @Test
    fun `non-finite model output fails closed`() {
        val factory = QnnModelSessionFactory { profile ->
            object : QnnModelSession {
                override fun execute(inputFloat32: ByteArray): QnnExecutionResult {
                    val outputs = if (profile.kind == MachineVisionModelKind.INSTANCE_SEGMENTATION) {
                        listOf(floatBytes(300 * 38, Float.NaN), floatBytes(160 * 160 * 32, 0.0f))
                    } else {
                        listOf(floatBytes(392 * 392, 1.0f))
                    }
                    return QnnExecutionResult(outputs)
                }

                override fun close() = Unit
            }
        }
        val executor = QnnLiveFrameExecutor(
            factory,
            JpegFrameDecoder { RgbImage(1, 1, byteArrayOf(1, 2, 3)) },
            System::nanoTime,
        )
        val encoded = encoded(1)
        assertThrows(IllegalArgumentException::class.java) {
            executor.process(
                encoded,
                visionFrame(encoded),
                selectDepthProfile = { MachineVisionModelProfiles.depthIndoorBalanced },
            )
        }
        executor.close()
    }

    @Test
    fun `invalid native metric tensor values fail closed before sampling`() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, 0.0f, 20.0001f).forEach { invalidDepth ->
            val factory = QnnModelSessionFactory { profile ->
                object : QnnModelSession {
                    override fun execute(inputFloat32: ByteArray): QnnExecutionResult =
                        if (profile.kind == MachineVisionModelKind.INSTANCE_SEGMENTATION) {
                            QnnExecutionResult(
                                listOf(
                                    floatBytes(300 * 38, 0.0f),
                                    floatBytes(160 * 160 * 32, 0.0f),
                                ),
                            )
                        } else {
                            QnnExecutionResult(listOf(floatBytes(392 * 392, invalidDepth)))
                        }

                    override fun close() = Unit
                }
            }
            val executor = QnnLiveFrameExecutor(
                factory,
                JpegFrameDecoder { RgbImage(1, 1, byteArrayOf(1, 2, 3)) },
                System::nanoTime,
            )
            val encoded = encoded(1)

            assertThrows(IllegalArgumentException::class.java) {
                executor.process(encoded, visionFrame(encoded)) {
                    MachineVisionModelProfiles.depthIndoorBalanced
                }
            }
            executor.close()
        }
    }

    @Test
    fun `tracking reset accepts restarted frame identifiers after reconnect`() {
        val factory = QnnModelSessionFactory { profile -> FakeSession(profile, mutableListOf()) }
        val executor = QnnLiveFrameExecutor(
            factory,
            JpegFrameDecoder { RgbImage(1, 1, byteArrayOf(1, 2, 3)) },
            System::nanoTime,
        )
        val encoded = encoded(1)
        requireNotNull(executor.process(encoded, visionFrame(encoded)) {
            MachineVisionModelProfiles.depthIndoorBalanced
        })

        executor.resetTracking()

        requireNotNull(executor.process(encoded, visionFrame(encoded)) {
            MachineVisionModelProfiles.depthIndoorBalanced
        })
        executor.close()
    }

    private class FakeSession(
        private val profile: MachineVisionModelProfile,
        private val executed: MutableList<String>,
    ) : QnnModelSession {
        override fun execute(inputFloat32: ByteArray): QnnExecutionResult {
            executed += profile.id
            return if (profile.kind == MachineVisionModelKind.INSTANCE_SEGMENTATION) {
                QnnExecutionResult(listOf(floatBytes(300 * 38, 0.0f), floatBytes(160 * 160 * 32, 0.0f)))
            } else {
                QnnExecutionResult(listOf(floatBytes(392 * 392, 1.0f)))
            }
        }

        override fun close() = Unit
    }

    private companion object {
        fun encoded(frameId: Long) = EncodedJpegFrame(
            frameId,
            frameId * 10,
            1,
            1,
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()),
        )

        fun visionFrame(frame: EncodedJpegFrame) = VisionFrame(
            frame.frameId,
            frame.captureMonotonicTimestampNanos,
            frame.width,
            frame.height,
            synthetic = false,
        )

        fun floatBytes(count: Int, value: Float): ByteArray {
            val bits = value.toRawBits()
            return ByteArray(count * 4) { byteIndex ->
                ((bits ushr ((byteIndex % 4) * 8)) and 0xff).toByte()
            }
        }

        fun floatBytes(values: FloatArray): ByteArray = ByteArray(values.size * 4).also { bytes ->
            values.forEachIndexed { index, value ->
                val bits = value.toRawBits()
                bytes[index * 4] = (bits and 0xff).toByte()
                bytes[index * 4 + 1] = ((bits ushr 8) and 0xff).toByte()
                bytes[index * 4 + 2] = ((bits ushr 16) and 0xff).toByte()
                bytes[index * 4 + 3] = ((bits ushr 24) and 0xff).toByte()
            }
        }
    }
}
