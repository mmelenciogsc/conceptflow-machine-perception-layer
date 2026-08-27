// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.math.max

enum class QnnFailureCode(val nativeCode: Int) {
    JNI_ADAPTER_UNAVAILABLE(1),
    INVALID_ARGUMENT(2),
    ARTIFACT_UNTRUSTED(3),
    RUNTIME_LOAD_FAILED(4),
    PROVIDER_UNAVAILABLE(5),
    BACKEND_INITIALIZATION_FAILED(6),
    DEVICE_INITIALIZATION_FAILED(7),
    CONTEXT_INITIALIZATION_FAILED(8),
    MODEL_COMPOSE_FAILED(9),
    GRAPH_FINALIZE_FAILED(10),
    TENSOR_SCHEMA_MISMATCH(11),
    GRAPH_EXECUTION_FAILED(12),
    SESSION_CLOSED(13),
    PROFILE_UNSUPPORTED(14),
    FRAME_UNAVAILABLE(15),
    FRAME_CORRELATION_FAILED(16),
    OUTPUT_INVALID(17),
    INTERNAL_ERROR(18),
    ;

    companion object {
        fun fromNative(code: Int): QnnFailureCode = entries.firstOrNull { it.nativeCode == code } ?: INTERNAL_ERROR
    }
}

data class QnnFailure(
    val code: QnnFailureCode,
    val detail: String,
) {
    init {
        require(detail.isNotBlank() && detail.length <= 1_024)
    }
}

class QnnInferenceException(val failure: QnnFailure) : IllegalStateException(
    "${failure.code.name}: ${failure.detail}",
)

data class EncodedJpegFrame(
    val frameId: Long,
    val captureMonotonicTimestampNanos: Long,
    val width: Int,
    val height: Int,
    val jpeg: ByteArray,
) {
    init {
        require(frameId > 0 && captureMonotonicTimestampNanos >= 0)
        require(width in 1..7_680 && height in 1..4_320)
        require(jpeg.size in 4..(16 * 1_024 * 1_024))
    }

    fun matches(frame: VisionFrame): Boolean = frameId == frame.frameId &&
        captureMonotonicTimestampNanos == frame.captureMonotonicTimestampNanos &&
        width == frame.width && height == frame.height
}

data class RawRgbFrame(
    val frameId: Long,
    val captureMonotonicTimestampNanos: Long,
    val width: Int,
    val height: Int,
    val rowStrideBytes: Int,
    val rgb: ByteArray,
) {
    init {
        require(frameId > 0 && captureMonotonicTimestampNanos >= 0)
        require(width in 1..7_680 && height in 1..4_320)
        require(rowStrideBytes == width * 3) { "packed RGB8 is required" }
        require(rgb.size.toLong() == rowStrideBytes.toLong() * height)
    }

    fun matches(frame: VisionFrame): Boolean = frameId == frame.frameId &&
        captureMonotonicTimestampNanos == frame.captureMonotonicTimestampNanos &&
        width == frame.width && height == frame.height
}

fun interface EncodedJpegFrameSource {
    /** The source must return only the exact requested frame; null means it has expired. */
    fun take(frameId: Long): EncodedJpegFrame?
}

data class QnnRuntimeBundle(
    val directory: File,
    val allowUntrustedDevelopmentArtifacts: Boolean = false,
) {
    fun verify(): QnnFailure? {
        if (!directory.isDirectory) return QnnFailure(QnnFailureCode.RUNTIME_LOAD_FAILED, "runtime directory missing")
        TRUSTED_FILES.forEach { (name, trustedDigest) ->
            val file = File(directory, name)
            if (!file.isFile || file.length() !in 1L..MAX_RUNTIME_FILE_BYTES) {
                return QnnFailure(QnnFailureCode.RUNTIME_LOAD_FAILED, "$name missing or invalid size")
            }
            val digest = sha256(file)
            if (!allowUntrustedDevelopmentArtifacts && digest != trustedDigest) {
                return QnnFailure(QnnFailureCode.ARTIFACT_UNTRUSTED, "$name digest is not the QAIRT 2.48 pin")
            }
        }
        return null
    }

    companion object {
        val TRUSTED_FILES: Map<String, String> = linkedMapOf(
            "libQnnHtp.so" to "6e6da5284060ca4369bb6fd3c6f2b661a9cebabb2cf4cf695cd77f558082265f",
            "libQnnHtpPrepare.so" to "cd31f94ae79ad402312e75262297d1e1073f85927d95f7905357a10b1d32a263",
            "libQnnHtpV79Stub.so" to "b3b4cca02e1f2cb5778ef9f18850814358d62a0b90123b25bb2806731a6af8b3",
            "libQnnSystem.so" to "4cd077547a939b131c4733391545d64bdc4f9d0c86de78793335ddd18348789e",
            "libQnnHtpV79Skel.so" to "974583f0cdc3d42de7a0633c9b987b160ad033a4cd53be30f9b5480b7e5a3213",
        )
        private const val MAX_RUNTIME_FILE_BYTES = 256L * 1_024L * 1_024L
    }
}

data class QnnExecutionResult(val outputs: List<ByteArray>)

interface QnnModelSession : AutoCloseable {
    fun execute(inputFloat32: ByteArray): QnnExecutionResult
}

fun interface QnnModelSessionFactory {
    fun open(profile: MachineVisionModelProfile): QnnModelSession
}

/**
 * JNI-backed HTP-only session factory. It never substitutes CPU or a different input resolution.
 * Vendor runtime/model files are verified from app-private storage before JNI sees their paths.
 */
class NativeQnnModelSessionFactory(
    private val runtimeBundle: QnnRuntimeBundle,
    private val modelDirectory: File,
    private val modelVerifier: PrivateModelBundleVerifier = PrivateModelBundleVerifier(),
) : QnnModelSessionFactory {
    override fun open(profile: MachineVisionModelProfile): QnnModelSession {
        runtimeBundle.verify()?.let { throw QnnInferenceException(it) }
        val check = modelVerifier.inspect(modelDirectory, profile)
        if (!check.available) {
            throw QnnInferenceException(QnnFailure(QnnFailureCode.ARTIFACT_UNTRUSTED, check.reason))
        }
        if (profile != MachineVisionModelProfiles.yoloe26sBvi &&
            profile != MachineVisionModelProfiles.depthIndoorBalanced &&
            profile != MachineVisionModelProfiles.depthOutdoorBalanced
        ) {
            throw QnnInferenceException(
                QnnFailure(QnnFailureCode.PROFILE_UNSUPPORTED, "only the pinned 640/392 graphs are enabled"),
            )
        }
        if (!QnnNativeBridge.available) {
            throw QnnInferenceException(
                QnnFailure(QnnFailureCode.JNI_ADAPTER_UNAVAILABLE, QnnNativeBridge.loadFailure),
            )
        }
        val kind = if (profile.kind == MachineVisionModelKind.INSTANCE_SEGMENTATION) 0 else 1
        val handle = QnnNativeBridge.open(
            runtimeBundle.directory.absolutePath,
            File(modelDirectory, profile.artifactFileName).absolutePath,
            kind,
        )
        if (handle <= 0) throw QnnNativeBridge.lastException()
        return NativeSession(handle, expectedInputBytes(profile), if (kind == 0) YOLO_OUTPUT_BYTES else DEPTH_OUTPUT_BYTES)
    }

    private class NativeSession(
        private var handle: Long,
        private val inputBytes: Int,
        private val expectedOutputBytes: IntArray,
    ) : QnnModelSession {
        @Synchronized
        override fun execute(inputFloat32: ByteArray): QnnExecutionResult {
            if (handle == 0L) throw QnnInferenceException(QnnFailure(QnnFailureCode.SESSION_CLOSED, "session closed"))
            if (inputFloat32.size != inputBytes) {
                throw QnnInferenceException(QnnFailure(QnnFailureCode.INVALID_ARGUMENT, "input byte count mismatch"))
            }
            val outputs = QnnNativeBridge.execute(handle, inputFloat32) ?: throw QnnNativeBridge.lastException()
            if (outputs.size != expectedOutputBytes.size ||
                outputs.indices.any { outputs[it].size != expectedOutputBytes[it] }
            ) {
                throw QnnInferenceException(QnnFailure(QnnFailureCode.OUTPUT_INVALID, "native output byte count mismatch"))
            }
            return QnnExecutionResult(outputs.toList())
        }

        @Synchronized
        override fun close() {
            val current = handle
            handle = 0L
            if (current != 0L) QnnNativeBridge.close(current)
        }
    }

    private fun expectedInputBytes(profile: MachineVisionModelProfile): Int =
        Math.multiplyExact(Math.multiplyExact(profile.inputWidth, profile.inputHeight), 12)

    private companion object {
        val YOLO_OUTPUT_BYTES = intArrayOf(300 * 38 * 4, 160 * 160 * 32 * 4)
        val DEPTH_OUTPUT_BYTES = intArrayOf(392 * 392 * 4)
    }
}

private object QnnNativeBridge {
    private val loadResult = runCatching { System.loadLibrary("conceptflow_qnn_jni") }
    val available: Boolean get() = loadResult.isSuccess
    val loadFailure: String get() = loadResult.exceptionOrNull()?.javaClass?.simpleName ?: "JNI adapter absent"

    external fun open(runtimeDirectory: String, modelPath: String, modelKind: Int): Long
    external fun execute(handle: Long, inputFloat32: ByteArray): Array<ByteArray>?
    external fun close(handle: Long)
    private external fun lastErrorCode(): Int
    private external fun lastErrorMessage(): String

    fun lastException(): QnnInferenceException = QnnInferenceException(
        QnnFailure(
            QnnFailureCode.fromNative(runCatching { lastErrorCode() }.getOrDefault(QnnFailureCode.INTERNAL_ERROR.nativeCode)),
            runCatching { lastErrorMessage() }.getOrDefault("native QNN call failed").take(1_024).ifBlank {
                "native QNN call failed"
            },
        ),
    )
}

/** Executes YOLO then exactly the selected 392 indoor/outdoor graph for the correlated JPEG frame. */
class QnnStagedMachineVisionInferenceAdapter(
    private val frameSource: EncodedJpegFrameSource,
    private val decoder: JpegFrameDecoder,
    private val sessionFactory: QnnModelSessionFactory,
    private val clockNanos: () -> Long = System::nanoTime,
    private val tracker: BoundedYoloTracker = BoundedYoloTracker(),
) : StagedMachineVisionInferenceAdapter, AutoCloseable {
    private data class SegmentationCache(
        val frame: VisionFrame,
        val image: RgbImage,
        val tracked: List<TrackedYoloMaskDetection>,
    )

    private val lock = Any()
    private var segmentationSession: QnnModelSession? = null
    private val depthSessions = mutableMapOf<String, QnnModelSession>()
    private var cache: SegmentationCache? = null
    private var closed = false

    override fun segment(frame: VisionFrame): SegmentationStageResult = synchronized(lock) {
        ensureOpen()
        val encoded = frameSource.take(frame.frameId) ?: fail(QnnFailureCode.FRAME_UNAVAILABLE, "JPEG frame unavailable")
        if (!encoded.matches(frame)) fail(QnnFailureCode.FRAME_CORRELATION_FAILED, "JPEG metadata does not match VisionFrame")
        val image = runCatching { decoder.decode(encoded.jpeg) }.getOrElse {
            fail(QnnFailureCode.INVALID_ARGUMENT, "JPEG decode failed: ${it.javaClass.simpleName}")
        }
        if (image.width != frame.width || image.height != frame.height) {
            fail(QnnFailureCode.FRAME_CORRELATION_FAILED, "decoded dimensions do not match VisionFrame")
        }
        val prepared = runCatching { VisionTensorPreprocessor.yolo640(image) }.getOrElse {
            fail(QnnFailureCode.INVALID_ARGUMENT, "YOLO preprocessing failed: ${it.javaClass.simpleName}")
        }
        val session = segmentationSession ?: sessionFactory.open(MachineVisionModelProfiles.yoloe26sBvi)
            .also { segmentationSession = it }
        val outputs = session.execute(prepared.bytes).outputs
        if (outputs.size != 2) fail(QnnFailureCode.OUTPUT_INVALID, "YOLO graph must return two tensors")
        val detections = runCatching {
            YoloFixedVocabularyPostprocessor.process(outputs[0], outputs[1], prepared.transform)
        }.getOrElse { fail(QnnFailureCode.OUTPUT_INVALID, "YOLO output invalid: ${it.javaClass.simpleName}") }
        val tracked = runCatching { tracker.update(frame.frameId, detections) }.getOrElse {
            fail(QnnFailureCode.FRAME_CORRELATION_FAILED, "tracking failed: ${it.javaClass.simpleName}")
        }
        cache = SegmentationCache(frame, image, tracked)
        SegmentationStageResult(
            frame.frameId,
            max(frame.captureMonotonicTimestampNanos, clockNanos()),
            MachineVisionModelProfiles.fixedVocabularySha256,
            tracked.map {
                SegmentedObject(
                    it.trackId,
                    it.detection.classId,
                    it.detection.confidence,
                    it.detection.geometry,
                    it.detection.maskFingerprint,
                )
            },
        )
    }

    override fun inferDepth(
        frame: VisionFrame,
        depthProfile: MachineVisionModelProfile,
        segmentedObjects: List<SegmentedObject>,
    ): DepthStageResult = synchronized(lock) {
        ensureOpen()
        if (depthProfile != MachineVisionModelProfiles.depthIndoorBalanced &&
            depthProfile != MachineVisionModelProfiles.depthOutdoorBalanced
        ) fail(QnnFailureCode.PROFILE_UNSUPPORTED, "no QNN fallback to 336/518 is permitted")
        val cached = cache?.takeIf { it.frame.frameId == frame.frameId && it.frame == frame }
            ?: fail(QnnFailureCode.FRAME_CORRELATION_FAILED, "segmentation cache does not match depth frame")
        val byTrack = cached.tracked.associateBy(TrackedYoloMaskDetection::trackId)
        if (segmentedObjects.any { objectResult ->
                val detection = byTrack[objectResult.trackId]?.detection
                detection == null || detection.maskFingerprint != objectResult.maskFingerprint
            }
        ) fail(QnnFailureCode.FRAME_CORRELATION_FAILED, "segmentation object/mask mismatch")
        val requested = segmentedObjects.mapNotNull { byTrack[it.trackId] }
        if (requested.isEmpty()) {
            return@synchronized DepthStageResult(
                frame.frameId,
                max(frame.captureMonotonicTimestampNanos, clockNanos()),
                depthProfile.id,
                emptyMap(),
            )
        }
        val prepared = runCatching { VisionTensorPreprocessor.metricDepth392(cached.image) }.getOrElse {
            fail(QnnFailureCode.INVALID_ARGUMENT, "depth preprocessing failed: ${it.javaClass.simpleName}")
        }
        val session = depthSessions[depthProfile.id] ?: sessionFactory.open(depthProfile).also {
            depthSessions[depthProfile.id] = it
        }
        val outputs = session.execute(prepared.bytes).outputs
        if (outputs.size != 1) fail(QnnFailureCode.OUTPUT_INVALID, "depth graph must return one tensor")
        val samples = runCatching {
            DepthMaskSampler.sample(outputs.single(), prepared.transform, requested)
        }.getOrElse { fail(QnnFailureCode.OUTPUT_INVALID, "depth output invalid: ${it.javaClass.simpleName}") }
        DepthStageResult(
            frame.frameId,
            max(frame.captureMonotonicTimestampNanos, clockNanos()),
            depthProfile.id,
            samples,
            requested.associate { it.trackId to it.detection.maskFingerprint },
        )
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        segmentationSession?.close()
        depthSessions.values.forEach(QnnModelSession::close)
        segmentationSession = null
        depthSessions.clear()
        cache = null
    }

    private fun ensureOpen() {
        if (closed) fail(QnnFailureCode.SESSION_CLOSED, "adapter closed")
    }

    private fun fail(code: QnnFailureCode, detail: String): Nothing =
        throw QnnInferenceException(QnnFailure(code, detail))
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(64 * 1_024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
