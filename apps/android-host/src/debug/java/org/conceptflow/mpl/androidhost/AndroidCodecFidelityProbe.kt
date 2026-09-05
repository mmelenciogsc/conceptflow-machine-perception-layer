// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.androidhost

import android.content.Context
import android.os.SystemClock
import com.google.protobuf.ByteString
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.conceptflow.mpl.host.vision.CodecFidelityGate
import org.conceptflow.mpl.host.vision.FloatTensorFidelity
import org.conceptflow.mpl.host.vision.HardwareAvcIntraFrameDecoder
import org.conceptflow.mpl.host.vision.I420RgbConverter
import org.conceptflow.mpl.host.vision.MachineVisionModelProfile
import org.conceptflow.mpl.host.vision.MachineVisionModelProfiles
import org.conceptflow.mpl.host.vision.NamedTensorFidelity
import org.conceptflow.mpl.host.vision.NativeQnnModelSessionFactory
import org.conceptflow.mpl.host.vision.QnnModelSessionFactory
import org.conceptflow.mpl.host.vision.QnnRuntimeBundle
import org.conceptflow.mpl.host.vision.RawI420Frame
import org.conceptflow.mpl.host.vision.SemanticInstanceFidelity
import org.conceptflow.mpl.host.vision.SemanticInstanceFidelityReport
import org.conceptflow.mpl.host.vision.VisionTensorPreprocessor
import org.conceptflow.mpl.host.vision.YoloFixedVocabularyPostprocessor
import org.conceptflow.mpl.transport.AvcAnnexBAccessUnit
import org.conceptflow.mpl.transport.DeterministicI420Fixture
import org.conceptflow.mpl.transport.I420Fidelity
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.ImageDescriptor
import org.conceptflow.mpl.v1.ImageEncoding

/** Debug-only cross-device hardware codec and real-QNN fidelity probe. */
internal object AndroidCodecFidelityProbe {
    private val running = AtomicBoolean(false)
    private val currentStatus = AtomicReference("codec_fidelity_idle")
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mpl-codec-fidelity").apply { isDaemon = true }
    }

    fun start(context: Context): String {
        if (!running.compareAndSet(false, true)) return "codec_fidelity_busy"
        val inputFile = inputFile(context)
        val referenceFile = referenceFile(context)
        if (!inputFile.isFile || inputFile.length() !in 1L..MAXIMUM_AVC_BYTES) {
            referenceFile.delete()
            running.set(false)
            currentStatus.set("codec_fidelity_failed_InvalidInput")
            return currentStatus.get()
        }
        val externalReference = referenceFile.exists()
        if (externalReference && referenceFile.length() != EXPECTED_I420_BYTES.toLong()) {
            inputFile.delete()
            referenceFile.delete()
            running.set(false)
            currentStatus.set("codec_fidelity_failed_InvalidReference")
            return currentStatus.get()
        }
        val encoded = runCatching { inputFile.readBytes() }.getOrElse { error ->
            inputFile.delete()
            referenceFile.delete()
            running.set(false)
            currentStatus.set("codec_fidelity_failed_${error.javaClass.simpleName}")
            return currentStatus.get()
        }
        val reference = runCatching {
            if (externalReference) referenceFile.readBytes() else DeterministicI420Fixture.create()
        }.getOrElse { error ->
            encoded.fill(0)
            inputFile.delete()
            referenceFile.delete()
            running.set(false)
            currentStatus.set("codec_fidelity_failed_${error.javaClass.simpleName}")
            return currentStatus.get()
        }
        inputFile.delete()
        referenceFile.delete()
        currentStatus.set("codec_fidelity_running")
        val applicationContext = context.applicationContext
        worker.execute {
            currentStatus.set(runCatching {
                execute(applicationContext, encoded, reference, externalReference)
            }.fold(
                onSuccess = { it },
                onFailure = { "codec_fidelity_failed_${it.javaClass.simpleName}" },
            ))
            encoded.fill(0)
            reference.fill(0)
            running.set(false)
        }
        return "codec_fidelity_queued"
    }

    fun status(): String = currentStatus.get()

    fun clear(context: Context): String {
        if (running.get()) return "codec_fidelity_clear_busy"
        val files = listOf(inputFile(context), referenceFile(context))
        val deleted = files.all { !it.exists() || it.delete() }
        files.first().parentFile?.takeIf { it.isDirectory && it.list()?.isEmpty() == true }?.delete()
        currentStatus.set("codec_fidelity_idle")
        return if (deleted) "codec_fidelity_cleared" else "codec_fidelity_clear_failed"
    }

    fun inputFile(context: Context): File = context.noBackupFilesDir
        .resolve(DIRECTORY_NAME)
        .resolve(INPUT_FILE_NAME)

    fun referenceFile(context: Context): File = context.noBackupFilesDir
        .resolve(DIRECTORY_NAME)
        .resolve(REFERENCE_FILE_NAME)

    private fun execute(
        context: Context,
        encoded: ByteArray,
        reference: ByteArray,
        requireSemanticFixture: Boolean,
    ): String {
        AvcAnnexBAccessUnit.requireIndependent(encoded)
        val captureNs = SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)
        val frame = encodedFrame(encoded, captureNs)
        val decodedResult = HardwareAvcIntraFrameDecoder().use { decoder ->
            val bytes = decoder.decode(frame).frameData.toByteArray()
            bytes to (decoder.codecName ?: "unknown")
        }
        val decoded = decodedResult.first
        val decoderName = decodedResult.second
        val pixels = I420Fidelity.compare(
            reference,
            decoded,
            DeterministicI420Fixture.WIDTH,
            DeterministicI420Fixture.HEIGHT,
        )
        val referenceRgb = toRgb(reference, captureNs)
        val decodedRgb = toRgb(decoded, captureNs)
        val referenceYolo = VisionTensorPreprocessor.yolo640(referenceRgb)
        val decodedYolo = VisionTensorPreprocessor.yolo640(decodedRgb)
        val referenceDepth = VisionTensorPreprocessor.metricDepth392(referenceRgb).bytes
        val decodedDepth = VisionTensorPreprocessor.metricDepth392(decodedRgb).bytes
        val factory = NativeQnnModelSessionFactory(
            QnnRuntimeBundle(context.filesDir.resolve("qnn-runtime")),
            context.filesDir.resolve("models"),
        )
        val modelComparisons = ArrayList<ModelComparison>()
        val yoloComparison = compareYolo(
            factory,
            MachineVisionModelProfiles.yoloe26sBvi,
            referenceYolo,
            decodedYolo,
            listOf("yolo_detection", "yolo_prototypes"),
        )
        modelComparisons += yoloComparison.outputs
        modelComparisons += compareModel(
            factory,
            MachineVisionModelProfiles.depthIndoorBalanced,
            referenceDepth,
            decodedDepth,
            listOf("depth_indoor"),
        )
        modelComparisons += compareModel(
            factory,
            MachineVisionModelProfiles.depthOutdoorBalanced,
            referenceDepth,
            decodedDepth,
            listOf("depth_outdoor"),
        )
        val outputs = modelComparisons.map { it.codec }
        val repeatability = modelComparisons.map { it.repeatability }
        // YOLO's 300 post-NMS rows are an unordered set. Small pixel changes may reorder
        // equivalent rows, making element-wise tensor cosine invalid as a semantic gate. For an
        // external representative fixture, postprocessed class/box precision, recall, and IoU
        // are authoritative for that tensor; prototypes and both depth outputs remain numerically
        // gated. The raw YOLO tensor metrics stay in the report for diagnosis.
        val gatedOutputs = if (requireSemanticFixture) {
            outputs.filterNot { it.name == "yolo_detection" }
        } else {
            outputs
        }
        val decision = CodecFidelityGate.evaluate(
            pixels,
            gatedOutputs,
            repeatability,
            semanticInstances = yoloComparison.semantic.takeIf { requireSemanticFixture },
        )
        val worstCosine = outputs.minOf { it.report.cosineSimilarity }
        val worstNrmse = outputs.maxOf { it.report.normalizedRootMeanSquareError }
        val repeatWorstCosine = repeatability.minOf { it.report.cosineSimilarity }
        val repeatWorstNrmse = repeatability.maxOf { it.report.normalizedRootMeanSquareError }
        val gateWorstCosine = gatedOutputs.minOf { it.report.cosineSimilarity }
        val gateWorstNrmse = gatedOutputs.maxOf { it.report.normalizedRootMeanSquareError }
        val nonFiniteMismatches = outputs.sumOf { it.report.nonFiniteMismatchCount }
        return buildString {
            append(if (decision.passed) "codec_fidelity_passed" else "codec_fidelity_rejected")
            append(" decoder_").append(sanitize(decoderName))
            append(" reference_sha256_").append(sha256(reference))
            append(" luma_psnr_db_").append(format(pixels.luma.peakSignalToNoiseRatioDb))
            append(" chroma_psnr_db_").append(format(pixels.chroma.peakSignalToNoiseRatioDb))
            append(" pixel_mae_").append(format(pixels.overall.meanAbsoluteError))
            append(" model_worst_cosine_").append(format(worstCosine))
            append(" model_worst_nrmse_").append(format(worstNrmse))
            append(" gate_worst_cosine_").append(format(gateWorstCosine))
            append(" gate_worst_nrmse_").append(format(gateWorstNrmse))
            append(" model_nonfinite_mismatch_").append(nonFiniteMismatches)
            append(" repeat_worst_cosine_").append(format(repeatWorstCosine))
            append(" repeat_worst_nrmse_").append(format(repeatWorstNrmse))
            append(" semantic_reference_").append(yoloComparison.semantic.referenceCount)
            append(" semantic_candidate_").append(yoloComparison.semantic.candidateCount)
            append(" semantic_matched_").append(yoloComparison.semantic.matchedCount)
            append(" semantic_precision_").append(format(yoloComparison.semantic.precision))
            append(" semantic_recall_").append(format(yoloComparison.semantic.recall))
            append(" semantic_mean_iou_")
                .append(format(yoloComparison.semantic.meanMatchedIntersectionOverUnion))
            outputs.forEach { output ->
                append(' ').append(output.name).append("_cosine_")
                    .append(format(output.report.cosineSimilarity))
                append(' ').append(output.name).append("_nrmse_")
                    .append(format(output.report.normalizedRootMeanSquareError))
                append(' ').append(output.name).append("_mae_")
                    .append(format(output.report.meanAbsoluteError))
            }
            if (decision.failures.isNotEmpty()) append(" failures_").append(decision.failures.joinToString("-"))
        }
    }

    private fun compareYolo(
        factory: QnnModelSessionFactory,
        profile: MachineVisionModelProfile,
        referenceInput: org.conceptflow.mpl.host.vision.PreparedFloat32Tensor,
        candidateInput: org.conceptflow.mpl.host.vision.PreparedFloat32Tensor,
        outputNames: List<String>,
    ): YoloComparison = factory.open(profile).use { session ->
        val firstReferenceOutputs = session.execute(referenceInput.bytes).outputs
        val secondReferenceOutputs = session.execute(referenceInput.bytes).outputs
        val candidateOutputs = session.execute(candidateInput.bytes).outputs
        check(
            firstReferenceOutputs.size == outputNames.size &&
                secondReferenceOutputs.size == outputNames.size &&
                candidateOutputs.size == outputNames.size,
        )
        val comparisons = outputNames.indices.map { index ->
            ModelComparison(
                repeatability = NamedTensorFidelity(
                    outputNames[index],
                    FloatTensorFidelity.compare(firstReferenceOutputs[index], secondReferenceOutputs[index]),
                ),
                codec = NamedTensorFidelity(
                    outputNames[index],
                    FloatTensorFidelity.compare(secondReferenceOutputs[index], candidateOutputs[index]),
                ),
            )
        }
        val referenceInstances = YoloFixedVocabularyPostprocessor.process(
            secondReferenceOutputs[0],
            secondReferenceOutputs[1],
            referenceInput.transform,
            maximumObjects = MAXIMUM_SEMANTIC_FIXTURE_INSTANCES,
        )
        val candidateInstances = YoloFixedVocabularyPostprocessor.process(
            candidateOutputs[0],
            candidateOutputs[1],
            candidateInput.transform,
            maximumObjects = MAXIMUM_SEMANTIC_FIXTURE_INSTANCES,
        )
        YoloComparison(
            outputs = comparisons,
            semantic = SemanticInstanceFidelity.compare(referenceInstances, candidateInstances),
        )
    }

    private fun compareModel(
        factory: QnnModelSessionFactory,
        profile: MachineVisionModelProfile,
        referenceInput: ByteArray,
        candidateInput: ByteArray,
        outputNames: List<String>,
    ): List<ModelComparison> = factory.open(profile).use { session ->
        val firstReferenceOutputs = session.execute(referenceInput).outputs
        val secondReferenceOutputs = session.execute(referenceInput).outputs
        val candidateOutputs = session.execute(candidateInput).outputs
        check(
            firstReferenceOutputs.size == outputNames.size &&
                secondReferenceOutputs.size == outputNames.size &&
                candidateOutputs.size == outputNames.size,
        )
        outputNames.indices.map { index ->
            ModelComparison(
                repeatability = NamedTensorFidelity(
                    outputNames[index],
                    FloatTensorFidelity.compare(firstReferenceOutputs[index], secondReferenceOutputs[index]),
                ),
                codec = NamedTensorFidelity(
                    outputNames[index],
                    FloatTensorFidelity.compare(secondReferenceOutputs[index], candidateOutputs[index]),
                ),
            )
        }
    }

    private data class ModelComparison(
        val repeatability: NamedTensorFidelity,
        val codec: NamedTensorFidelity,
    )

    private data class YoloComparison(
        val outputs: List<ModelComparison>,
        val semantic: SemanticInstanceFidelityReport,
    )

    private fun toRgb(i420: ByteArray, timestampNs: Long) = I420RgbConverter.convert(
        RawI420Frame(
            frameId = 1L,
            captureMonotonicTimestampNanos = timestampNs,
            width = DeterministicI420Fixture.WIDTH,
            height = DeterministicI420Fixture.HEIGHT,
            rowStrideBytes = DeterministicI420Fixture.WIDTH,
            i420 = i420,
        ),
    )

    private fun encodedFrame(encoded: ByteArray, timestampNs: Long): FramePayload = FramePayload.newBuilder()
        .setRequestId("codec-fidelity")
        .setSessionId("synthetic")
        .setStreamId("synthetic-avc")
        .setFrameId(1L)
        .setCaptureMonotonicTimestampNs(timestampNs)
        .setImage(
            ImageDescriptor.newBuilder()
                .setWidth(DeterministicI420Fixture.WIDTH)
                .setHeight(DeterministicI420Fixture.HEIGHT)
                .setRowStrideBytes(0)
                .setEncoding(ImageEncoding.IMAGE_ENCODING_AVC_ANNEX_B_INTRA)
                .setMediaType(AvcAnnexBAccessUnit.MEDIA_TYPE)
                .setPayloadBytes(encoded.size.toLong())
                .setSha256(ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(encoded))),
        )
        .setFrameData(ByteString.copyFrom(encoded))
        .setSynthetic(true)
        .build()

    private fun format(value: Double): String = if (value.isInfinite()) {
        "inf"
    } else {
        String.format(Locale.ROOT, "%.6f", value)
    }

    private fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private const val DIRECTORY_NAME = "codec-fidelity"
    private const val INPUT_FILE_NAME = "fixture.avc"
    private const val REFERENCE_FILE_NAME = "reference.i420"
    private const val EXPECTED_I420_BYTES = 640 * 640 * 3 / 2
    private const val MAXIMUM_AVC_BYTES = 4L * 1_024L * 1_024L
    private const val MAXIMUM_SEMANTIC_FIXTURE_INSTANCES = 16
}
