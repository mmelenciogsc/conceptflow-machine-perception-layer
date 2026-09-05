// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.hardware

import java.nio.ByteBuffer
import org.conceptflow.mpl.rokid.core.AdaptiveFrameDecision
import org.conceptflow.mpl.rokid.core.AdaptiveFrameGate
import org.conceptflow.mpl.rokid.core.LumaFrame
import org.conceptflow.mpl.rokid.core.PixelDimensions
import org.conceptflow.mpl.rokid.core.SquareAspectFillTransform
import org.conceptflow.mpl.rokid.core.aspectFit

internal interface Yuv420Plane {
    val rowStride: Int
    val pixelStride: Int
    val byteCount: Int
    fun unsignedAt(x: Int, y: Int): Int
}

internal data class ByteArrayYuv420Plane(
    val bytes: ByteArray,
    override val rowStride: Int,
    override val pixelStride: Int,
) : Yuv420Plane {
    init {
        require(rowStride > 0 && pixelStride > 0)
    }

    override val byteCount: Int get() = bytes.size

    override fun unsignedAt(x: Int, y: Int): Int = bytes[y * rowStride + x * pixelStride].toInt() and 0xff
}

internal class ByteBufferYuv420Plane(
    buffer: ByteBuffer,
    override val rowStride: Int,
    override val pixelStride: Int,
) : Yuv420Plane {
    private val source = buffer.asReadOnlyBuffer()
    private val origin = source.position()
    internal val nativeBuffer: ByteBuffer get() = source
    internal val nativeOffset: Int get() = origin

    init {
        require(rowStride > 0 && pixelStride > 0)
    }

    override val byteCount: Int = source.remaining()

    override fun unsignedAt(x: Int, y: Int): Int =
        source.get(origin + y * rowStride + x * pixelStride).toInt() and 0xff
}

internal data class Yuv420Frame(
    val dimensions: PixelDimensions,
    val y: Yuv420Plane,
    val u: Yuv420Plane,
    val v: Yuv420Plane,
) {
    init {
        requireYuvPlaneCoverage(y, dimensions.width, dimensions.height)
        val chromaWidth = (dimensions.width + 1) / 2
        val chromaHeight = (dimensions.height + 1) / 2
        requireYuvPlaneCoverage(u, chromaWidth, chromaHeight)
        requireYuvPlaneCoverage(v, chromaWidth, chromaHeight)
    }
}

internal data class ProcessedYuvCameraFrame(
    val rgb8: ByteArray?,
    val i420: ByteArray?,
    val inputDimensions: PixelDimensions,
    val outputDimensions: PixelDimensions,
    val decision: AdaptiveFrameDecision,
    val rgbConversionBackend: RgbConversionBackend?,
    val i420NativeConversion: Boolean?,
) {
    init {
        require((rgb8 == null) == (rgbConversionBackend == null))
        require((i420 == null) == (i420NativeConversion == null))
        require(rgb8 == null || i420 == null)
        require(!decision.emit || rgb8 != null || i420 != null)
    }
}

internal enum class RgbConversionBackend {
    KOTLIN_REFERENCE,
    NATIVE_INTEGER,
}

enum class CameraTransferPixelFormat {
    RGB8,
    I420,
    AVC_INTRA,
}

/** Applies the protected gate in capture order, then converts only admitted frames. */
internal class AdaptiveYuv420Processor(
    private val frameGate: AdaptiveFrameGate = AdaptiveFrameGate(),
    private val analysisGate: PixelDimensions = PixelDimensions(160, 90),
    private val outputSize: Int = 640,
    private val outputFormat: CameraTransferPixelFormat = CameraTransferPixelFormat.RGB8,
) {
    private var cachedPlans: ProcessorSamplingPlans? = null

    init {
        require(outputSize in 1..MAXIMUM_RGB_OUTPUT_SIZE)
    }

    fun process(frame: Yuv420Frame, timestampNanos: Long): ProcessedYuvCameraFrame {
        val analysisDimensions = aspectFit(frame.dimensions, analysisGate)
        val transform = SquareAspectFillTransform.centered(
            frame.dimensions.width,
            frame.dimensions.height,
            outputSize,
        )
        val plans = cachedPlans
            ?.takeIf {
                it.sourceDimensions == frame.dimensions &&
                    it.analysis.outputDimensions == analysisDimensions &&
                    it.output.transform == transform
            }
            ?: ProcessorSamplingPlans(
                sourceDimensions = frame.dimensions,
                analysis = PlaneSamplingPlan.scaled(frame.dimensions, analysisDimensions),
                output = Yuv420RgbSamplingPlan.aspectFill(frame.dimensions, transform),
                i420Output = Yuv420I420SamplingPlan.aspectFill(frame.dimensions, transform),
            ).also { cachedPlans = it }
        val decision = frameGate.evaluate(
            timestampNanos,
            Yuv420RgbConverter.toLumaFrame(frame, plans.analysis),
        )
        val convertedRgb = if (decision.emit && outputFormat == CameraTransferPixelFormat.RGB8) {
            NativeYuv420RgbConverter.tryConvert(frame, plans.output)
                ?.let { RgbConversionResult(it, RgbConversionBackend.NATIVE_INTEGER) }
                ?: RgbConversionResult(
                    Yuv420RgbConverter.toRgb8(frame, plans.output),
                    RgbConversionBackend.KOTLIN_REFERENCE,
                )
        } else null
        val convertedI420 = if (decision.emit && outputFormat != CameraTransferPixelFormat.RGB8) {
            NativeYuv420I420Converter.tryConvert(frame, plans.i420Output)
                ?.let { I420ConversionResult(it, native = true) }
                ?: I420ConversionResult(
                    Yuv420I420Converter.toI420(frame, plans.i420Output),
                    native = false,
                )
        } else null
        return ProcessedYuvCameraFrame(
            rgb8 = convertedRgb?.bytes,
            i420 = convertedI420?.bytes,
            inputDimensions = frame.dimensions,
            outputDimensions = PixelDimensions(outputSize, outputSize),
            decision = decision,
            rgbConversionBackend = convertedRgb?.backend,
            i420NativeConversion = convertedI420?.native,
        )
    }

    fun reset() = frameGate.reset()

    private companion object {
        const val MAXIMUM_RGB_OUTPUT_SIZE = 2_048
    }
}

private data class RgbConversionResult(
    val bytes: ByteArray,
    val backend: RgbConversionBackend,
)

private data class I420ConversionResult(
    val bytes: ByteArray,
    val native: Boolean,
)

/** Preferred zero-copy native path for direct Camera2 planes; JVM/non-direct inputs use Kotlin. */
internal object NativeYuv420RgbConverter {
    private val loadResult = runCatching { System.loadLibrary("conceptflow_yuv_jni") }
    internal val available: Boolean get() = loadResult.isSuccess

    fun tryConvert(frame: Yuv420Frame, plan: Yuv420RgbSamplingPlan): ByteArray? {
        if (!available || plan.luma.sourceDimensions != frame.dimensions) return null
        val y = frame.y as? ByteBufferYuv420Plane ?: return null
        val u = frame.u as? ByteBufferYuv420Plane ?: return null
        val v = frame.v as? ByteBufferYuv420Plane ?: return null
        if (!y.nativeBuffer.isDirect || !u.nativeBuffer.isDirect || !v.nativeBuffer.isDirect) return null
        val output = plan.luma.outputDimensions
        require(output.width == output.height)
        val bytes = ByteArray(Math.multiplyExact(Math.multiplyExact(output.width, output.height), 3))
        val transform = plan.transform
        val converted = try {
            convert(
                y.nativeBuffer,
                y.nativeOffset,
                y.rowStride,
                y.pixelStride,
                u.nativeBuffer,
                u.nativeOffset,
                u.rowStride,
                u.pixelStride,
                v.nativeBuffer,
                v.nativeOffset,
                v.rowStride,
                v.pixelStride,
                frame.dimensions.width,
                frame.dimensions.height,
                output.width,
                transform.scaledWidth,
                transform.scaledHeight,
                transform.cropLeft,
                transform.cropTop,
                bytes,
            )
        } catch (_: LinkageError) {
            false
        }
        return bytes.takeIf { converted }
    }

    private external fun convert(
        yBuffer: ByteBuffer,
        yOffset: Int,
        yRowStride: Int,
        yPixelStride: Int,
        uBuffer: ByteBuffer,
        uOffset: Int,
        uRowStride: Int,
        uPixelStride: Int,
        vBuffer: ByteBuffer,
        vOffset: Int,
        vRowStride: Int,
        vPixelStride: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        outputSize: Int,
        scaledWidth: Int,
        scaledHeight: Int,
        cropLeft: Int,
        cropTop: Int,
        output: ByteArray,
    ): Boolean
}

internal object NativeYuv420I420Converter {
    private val loadResult = runCatching { System.loadLibrary("conceptflow_yuv_jni") }
    internal val available: Boolean get() = loadResult.isSuccess

    fun tryConvert(frame: Yuv420Frame, plan: Yuv420I420SamplingPlan): ByteArray? {
        if (!available || plan.luma.sourceDimensions != frame.dimensions) return null
        val y = frame.y as? ByteBufferYuv420Plane ?: return null
        val u = frame.u as? ByteBufferYuv420Plane ?: return null
        val v = frame.v as? ByteBufferYuv420Plane ?: return null
        if (!y.nativeBuffer.isDirect || !u.nativeBuffer.isDirect || !v.nativeBuffer.isDirect) return null
        val output = plan.luma.outputDimensions
        require(output.width == output.height && output.width % 2 == 0)
        val lumaBytes = Math.multiplyExact(output.width, output.height)
        val bytes = ByteArray(Math.addExact(lumaBytes, lumaBytes / 2))
        val transform = plan.transform
        val converted = try {
            convert(
                y.nativeBuffer,
                y.nativeOffset,
                y.rowStride,
                y.pixelStride,
                u.nativeBuffer,
                u.nativeOffset,
                u.rowStride,
                u.pixelStride,
                v.nativeBuffer,
                v.nativeOffset,
                v.rowStride,
                v.pixelStride,
                frame.dimensions.width,
                frame.dimensions.height,
                output.width,
                transform.scaledWidth,
                transform.scaledHeight,
                transform.cropLeft,
                transform.cropTop,
                bytes,
            )
        } catch (_: LinkageError) {
            false
        }
        return bytes.takeIf { converted }
    }

    private external fun convert(
        yBuffer: ByteBuffer,
        yOffset: Int,
        yRowStride: Int,
        yPixelStride: Int,
        uBuffer: ByteBuffer,
        uOffset: Int,
        uRowStride: Int,
        uPixelStride: Int,
        vBuffer: ByteBuffer,
        vOffset: Int,
        vRowStride: Int,
        vPixelStride: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        outputSize: Int,
        scaledWidth: Int,
        scaledHeight: Int,
        cropLeft: Int,
        cropTop: Int,
        output: ByteArray,
    ): Boolean
}

private data class ProcessorSamplingPlans(
    val sourceDimensions: PixelDimensions,
    val analysis: PlaneSamplingPlan,
    val output: Yuv420RgbSamplingPlan,
    val i420Output: Yuv420I420SamplingPlan,
)

internal data class BilinearAxisPlan(
    val lower: IntArray,
    val upper: IntArray,
    val upperWeight: IntArray,
) {
    init {
        require(lower.isNotEmpty() && lower.size == upper.size && lower.size == upperWeight.size)
    }

    companion object {
        fun create(
            outputSize: Int,
            scaledSize: Int,
            cropOffset: Int,
            sourceSize: Int,
        ): BilinearAxisPlan {
            require(outputSize > 0 && scaledSize >= outputSize && sourceSize > 0)
            require(cropOffset >= 0 && cropOffset + outputSize <= scaledSize)
            val lower = IntArray(outputSize)
            val upper = IntArray(outputSize)
            val upperWeight = IntArray(outputSize)
            repeat(outputSize) { outputIndex ->
                val coordinate = sampleCoordinate(
                    outputIndex + cropOffset,
                    scaledSize,
                    sourceSize,
                )
                val first = (coordinate shr FIXED_SHIFT).toInt()
                lower[outputIndex] = first
                upper[outputIndex] = minOf(first + 1, sourceSize - 1)
                upperWeight[outputIndex] = (coordinate and FIXED_MASK).toInt()
            }
            return BilinearAxisPlan(lower, upper, upperWeight)
        }
    }
}

internal data class PlaneSamplingPlan(
    val sourceDimensions: PixelDimensions,
    val outputDimensions: PixelDimensions,
    val x: BilinearAxisPlan,
    val y: BilinearAxisPlan,
) {
    internal val rows = BilinearPlaneRowCache(outputDimensions.width)

    companion object {
        fun scaled(
            source: PixelDimensions,
            output: PixelDimensions,
        ): PlaneSamplingPlan = PlaneSamplingPlan(
            sourceDimensions = source,
            outputDimensions = output,
            x = BilinearAxisPlan.create(output.width, output.width, 0, source.width),
            y = BilinearAxisPlan.create(output.height, output.height, 0, source.height),
        )

        fun aspectFill(
            source: PixelDimensions,
            transform: SquareAspectFillTransform,
        ): PlaneSamplingPlan {
            require(transform.sourceWidth == source.width && transform.sourceHeight == source.height)
            val output = PixelDimensions(transform.outputSize, transform.outputSize)
            return PlaneSamplingPlan(
                sourceDimensions = source,
                outputDimensions = output,
                x = BilinearAxisPlan.create(
                    output.width,
                    transform.scaledWidth,
                    transform.cropLeft,
                    source.width,
                ),
                y = BilinearAxisPlan.create(
                    output.height,
                    transform.scaledHeight,
                    transform.cropTop,
                    source.height,
                ),
            )
        }
    }
}

internal data class Yuv420RgbSamplingPlan(
    val transform: SquareAspectFillTransform,
    val luma: PlaneSamplingPlan,
    val chroma: PlaneSamplingPlan,
) {
    internal val uRows = BilinearPlaneRowCache(luma.outputDimensions.width)
    internal val vRows = BilinearPlaneRowCache(luma.outputDimensions.width)

    companion object {
        fun aspectFill(
            source: PixelDimensions,
            transform: SquareAspectFillTransform,
        ): Yuv420RgbSamplingPlan {
            val luma = PlaneSamplingPlan.aspectFill(source, transform)
            val chromaSource = PixelDimensions((source.width + 1) / 2, (source.height + 1) / 2)
            val output = luma.outputDimensions
            val chroma = PlaneSamplingPlan(
                sourceDimensions = chromaSource,
                outputDimensions = output,
                x = BilinearAxisPlan.create(
                    output.width,
                    transform.scaledWidth,
                    transform.cropLeft,
                    chromaSource.width,
                ),
                y = BilinearAxisPlan.create(
                    output.height,
                    transform.scaledHeight,
                    transform.cropTop,
                    chromaSource.height,
                ),
            )
            return Yuv420RgbSamplingPlan(transform, luma, chroma)
        }
    }
}

internal data class Yuv420I420SamplingPlan(
    val transform: SquareAspectFillTransform,
    val luma: PlaneSamplingPlan,
    val chroma: PlaneSamplingPlan,
) {
    companion object {
        fun aspectFill(
            source: PixelDimensions,
            transform: SquareAspectFillTransform,
        ): Yuv420I420SamplingPlan {
            require(transform.outputSize % 2 == 0)
            require(transform.scaledWidth % 2 == 0 && transform.scaledHeight % 2 == 0)
            require(transform.cropLeft % 2 == 0 && transform.cropTop % 2 == 0)
            val luma = PlaneSamplingPlan.aspectFill(source, transform)
            val chromaSource = PixelDimensions((source.width + 1) / 2, (source.height + 1) / 2)
            val chromaOutput = PixelDimensions(transform.outputSize / 2, transform.outputSize / 2)
            val chroma = PlaneSamplingPlan(
                sourceDimensions = chromaSource,
                outputDimensions = chromaOutput,
                x = BilinearAxisPlan.create(
                    chromaOutput.width,
                    transform.scaledWidth / 2,
                    transform.cropLeft / 2,
                    chromaSource.width,
                ),
                y = BilinearAxisPlan.create(
                    chromaOutput.height,
                    transform.scaledHeight / 2,
                    transform.cropTop / 2,
                    chromaSource.height,
                ),
            )
            return Yuv420I420SamplingPlan(transform, luma, chroma)
        }
    }
}

/** Packs a resized frame as tightly packed Y, U, V planes without RGB conversion. */
internal object Yuv420I420Converter {
    fun toI420(frame: Yuv420Frame, plan: Yuv420I420SamplingPlan): ByteArray {
        require(plan.luma.sourceDimensions == frame.dimensions)
        val lumaBytes = Math.multiplyExact(
            plan.luma.outputDimensions.width,
            plan.luma.outputDimensions.height,
        )
        val chromaBytes = Math.multiplyExact(
            plan.chroma.outputDimensions.width,
            plan.chroma.outputDimensions.height,
        )
        val output = ByteArray(Math.addExact(lumaBytes, Math.multiplyExact(chromaBytes, 2)))
        copyPlane(frame.y, plan.luma, output, 0)
        copyPlane(frame.u, plan.chroma, output, lumaBytes)
        copyPlane(frame.v, plan.chroma, output, lumaBytes + chromaBytes)
        return output
    }

    private fun copyPlane(
        source: Yuv420Plane,
        plan: PlaneSamplingPlan,
        output: ByteArray,
        outputOffset: Int,
    ) {
        plan.rows.reset()
        var offset = outputOffset
        repeat(plan.outputDimensions.height) { outputY ->
            val lowerY = plan.y.lower[outputY]
            val upperY = plan.y.upper[outputY]
            val yWeight = plan.y.upperWeight[outputY]
            plan.rows.prepare(source, plan.x, lowerY, upperY)
            val lower = plan.rows.lowerValues
            val upper = plan.rows.upperValues
            repeat(plan.outputDimensions.width) { outputX ->
                output[offset++] = interpolatePlaneSample(lower[outputX], upper[outputX], yWeight).toByte()
            }
        }
    }
}

/** Pure, deterministic YUV_420_888 sampling with cached coordinates and explicit strides. */
internal object Yuv420RgbConverter {
    fun toLumaFrame(frame: Yuv420Frame, output: PixelDimensions): LumaFrame {
        return toLumaFrame(frame, PlaneSamplingPlan.scaled(frame.dimensions, output))
    }

    fun toLumaFrame(frame: Yuv420Frame, plan: PlaneSamplingPlan): LumaFrame {
        require(plan.sourceDimensions == frame.dimensions)
        val output = plan.outputDimensions
        val luma = ByteArray(Math.multiplyExact(output.width, output.height))
        plan.rows.reset()
        var offset = 0
        repeat(output.height) { outputY ->
            val y0 = plan.y.lower[outputY]
            val y1 = plan.y.upper[outputY]
            val yWeight = plan.y.upperWeight[outputY]
            plan.rows.prepare(frame.y, plan.x, y0, y1)
            val lowerRow = plan.rows.lowerValues
            val upperRow = plan.rows.upperValues
            repeat(output.width) { outputX ->
                val sampledY = interpolateRows(lowerRow[outputX], upperRow[outputX], yWeight)
                luma[offset++] = limitedRangeLuma(sampledY).toByte()
            }
        }
        return LumaFrame(output.width, output.height, luma)
    }

    fun toRgb8(frame: Yuv420Frame, transform: SquareAspectFillTransform): ByteArray {
        return toRgb8(frame, Yuv420RgbSamplingPlan.aspectFill(frame.dimensions, transform))
    }

    fun toRgb8(frame: Yuv420Frame, plan: Yuv420RgbSamplingPlan): ByteArray {
        require(plan.luma.sourceDimensions == frame.dimensions)
        val output = plan.luma.outputDimensions
        val rgb = ByteArray(Math.multiplyExact(Math.multiplyExact(output.width, output.height), RGB_CHANNELS))
        plan.luma.rows.reset()
        plan.uRows.reset()
        plan.vRows.reset()
        var offset = 0
        repeat(output.height) { outputY ->
            val lumaY0 = plan.luma.y.lower[outputY]
            val lumaY1 = plan.luma.y.upper[outputY]
            val lumaYWeight = plan.luma.y.upperWeight[outputY]
            val chromaY0 = plan.chroma.y.lower[outputY]
            val chromaY1 = plan.chroma.y.upper[outputY]
            val chromaYWeight = plan.chroma.y.upperWeight[outputY]
            plan.luma.rows.prepare(frame.y, plan.luma.x, lumaY0, lumaY1)
            plan.uRows.prepare(frame.u, plan.chroma.x, chromaY0, chromaY1)
            plan.vRows.prepare(frame.v, plan.chroma.x, chromaY0, chromaY1)
            val lowerY = plan.luma.rows.lowerValues
            val upperY = plan.luma.rows.upperValues
            val lowerU = plan.uRows.lowerValues
            val upperU = plan.uRows.upperValues
            val lowerV = plan.vRows.lowerValues
            val upperV = plan.vRows.upperValues
            repeat(output.width) { outputX ->
                val packed = limitedRangeBt601Rgb(
                    interpolateRows(lowerY[outputX], upperY[outputX], lumaYWeight),
                    interpolateRows(lowerU[outputX], upperU[outputX], chromaYWeight),
                    interpolateRows(lowerV[outputX], upperV[outputX], chromaYWeight),
                )
                rgb[offset++] = (packed shr 16 and 0xff).toByte()
                rgb[offset++] = (packed shr 8 and 0xff).toByte()
                rgb[offset++] = (packed and 0xff).toByte()
            }
        }
        return rgb
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun interpolateRows(lower: Int, upper: Int, upperWeight: Int): Int =
        (
            (
                lower.toLong() * (FIXED_ONE_INT - upperWeight) +
                    upper.toLong() * upperWeight + FIXED_ROUND
                ) shr (FIXED_SHIFT * 2)
            ).toInt()

    @Suppress("NOTHING_TO_INLINE")
    private inline fun limitedRangeLuma(y: Int): Int =
        ((298 * (y - 16).coerceAtLeast(0) + 128) shr 8).coerceIn(0, 255)

    @Suppress("NOTHING_TO_INLINE")
    private inline fun limitedRangeBt601Rgb(y: Int, u: Int, v: Int): Int {
        val luminance = (y - 16).coerceAtLeast(0)
        val blueDifference = u - 128
        val redDifference = v - 128
        val red = ((298 * luminance + 409 * redDifference + 128) shr 8).coerceIn(0, 255)
        val green = (
            (298 * luminance - 100 * blueDifference - 208 * redDifference + 128) shr 8
        ).coerceIn(0, 255)
        val blue = ((298 * luminance + 516 * blueDifference + 128) shr 8).coerceIn(0, 255)
        return red shl 16 or (green shl 8) or blue
    }

    private const val RGB_CHANNELS = 3
}

@Suppress("NOTHING_TO_INLINE")
private inline fun interpolatePlaneSample(lower: Int, upper: Int, upperWeight: Int): Int =
    (
        (
            lower.toLong() * (FIXED_ONE_INT - upperWeight) +
                upper.toLong() * upperWeight + FIXED_ROUND
            ) shr (FIXED_SHIFT * 2)
        ).toInt()

/** Fixed two-row cache; output storage is allocated once with its immutable sampling plan. */
internal class BilinearPlaneRowCache(outputWidth: Int) {
    private var firstSourceRow = -1
    private var secondSourceRow = -1
    private val firstValues = IntArray(outputWidth)
    private val secondValues = IntArray(outputWidth)
    lateinit var lowerValues: IntArray
        private set
    lateinit var upperValues: IntArray
        private set

    fun reset() {
        firstSourceRow = -1
        secondSourceRow = -1
    }

    fun prepare(
        plane: Yuv420Plane,
        x: BilinearAxisPlan,
        lowerSourceRow: Int,
        upperSourceRow: Int,
    ) {
        val lower = rowFor(lowerSourceRow) ?: if (upperSourceRow == firstSourceRow) {
            fillHorizontalRow(plane, x, lowerSourceRow, secondValues)
            secondSourceRow = lowerSourceRow
            secondValues
        } else {
            fillHorizontalRow(plane, x, lowerSourceRow, firstValues)
            firstSourceRow = lowerSourceRow
            firstValues
        }
        val upper = if (upperSourceRow == lowerSourceRow) {
            lower
        } else {
            rowFor(upperSourceRow) ?: if (lower === firstValues) {
                fillHorizontalRow(plane, x, upperSourceRow, secondValues)
                secondSourceRow = upperSourceRow
                secondValues
            } else {
                fillHorizontalRow(plane, x, upperSourceRow, firstValues)
                firstSourceRow = upperSourceRow
                firstValues
            }
        }
        lowerValues = lower
        upperValues = upper
    }

    private fun rowFor(sourceRow: Int): IntArray? = when (sourceRow) {
        firstSourceRow -> firstValues
        secondSourceRow -> secondValues
        else -> null
    }

    private fun fillHorizontalRow(
        plane: Yuv420Plane,
        x: BilinearAxisPlan,
        sourceRow: Int,
        destination: IntArray,
    ) {
        repeat(destination.size) { outputX ->
            val upperWeight = x.upperWeight[outputX]
            destination[outputX] =
                plane.unsignedAt(x.lower[outputX], sourceRow) * (FIXED_ONE_INT - upperWeight) +
                plane.unsignedAt(x.upper[outputX], sourceRow) * upperWeight
        }
    }
}

private fun sampleCoordinate(outputIndex: Int, outputSize: Int, sourceSize: Int): Long {
    require(outputIndex in 0 until outputSize && sourceSize > 0)
    val centered = ((2L * outputIndex + 1L) * sourceSize * FIXED_ONE) /
        (2L * outputSize) - FIXED_HALF
    return centered.coerceIn(0L, (sourceSize - 1L) * FIXED_ONE)
}

private const val FIXED_SHIFT = 16
private const val FIXED_ONE_INT = 1 shl FIXED_SHIFT
private const val FIXED_ONE = 1L shl FIXED_SHIFT
private const val FIXED_HALF = FIXED_ONE / 2L
private const val FIXED_MASK = FIXED_ONE - 1L
private const val FIXED_ROUND = 1L shl (FIXED_SHIFT * 2 - 1)

internal fun minimumYuvPlaneBytes(
    width: Int,
    height: Int,
    rowStride: Int,
    pixelStride: Int,
): Int {
    require(width > 0 && height > 0 && rowStride > 0 && pixelStride > 0)
    val lastByte = Math.addExact(
        Math.multiplyExact(height - 1, rowStride),
        Math.multiplyExact(width - 1, pixelStride),
    )
    return Math.addExact(lastByte, 1)
}

private fun requireYuvPlaneCoverage(plane: Yuv420Plane, width: Int, height: Int) {
    require(width > 0 && height > 0)
    require(plane.rowStride >= Math.addExact(Math.multiplyExact(width - 1, plane.pixelStride), 1))
    require(plane.byteCount >= minimumYuvPlaneBytes(width, height, plane.rowStride, plane.pixelStride))
}

internal inline fun <T : AutoCloseable, R> processAndCloseCameraImage(
    image: T,
    process: (T) -> R,
): R = image.use(process)
