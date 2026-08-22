// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

enum class MachineVisionModelKind {
    INSTANCE_SEGMENTATION,
    METRIC_DEPTH,
}

enum class DepthEnvironment {
    INDOOR,
    OUTDOOR,
}

enum class AcceleratorTarget {
    QNN_HTP,
    CPU_REFERENCE,
    UNAVAILABLE,
}

data class MachineVisionModelProfile(
    val id: String,
    val upstreamModelId: String,
    val artifactFileName: String,
    val kind: MachineVisionModelKind,
    val inputWidth: Int,
    val inputHeight: Int,
    val depthEnvironment: DepthEnvironment? = null,
    val fixedVocabularySha256: String? = null,
    val maximumArtifactBytes: Long = 512L * 1_024L * 1_024L,
) {
    init {
        require(id.isNotBlank() && upstreamModelId.isNotBlank())
        require(ARTIFACT_FILE.matches(artifactFileName))
        require(inputWidth in 64..2_048 && inputHeight in 64..2_048)
        require(maximumArtifactBytes in 1L..2L * 1_024L * 1_024L * 1_024L)
        require(kind == MachineVisionModelKind.METRIC_DEPTH || depthEnvironment == null)
        require(kind != MachineVisionModelKind.METRIC_DEPTH || depthEnvironment != null)
        fixedVocabularySha256?.let { require(SHA256.matches(it)) }
    }

    private companion object {
        val ARTIFACT_FILE = Regex("[a-z0-9][a-z0-9._-]{1,127}")
        val SHA256 = Regex("[a-f0-9]{64}")
    }
}

object MachineVisionModelProfiles {
    val fixedVocabularySha256: String = sha256(BviClassCatalog.prompts.joinToString("\n").encodeToByteArray())

    val yoloe26sBvi = MachineVisionModelProfile(
        id = "yoloe-26s-bvi-seg-qnn-htp",
        upstreamModelId = "Ultralytics/yoloe-26s-seg.pt",
        artifactFileName = "yoloe-26s-bvi-seg-qnn-htp.bin",
        kind = MachineVisionModelKind.INSTANCE_SEGMENTATION,
        inputWidth = 640,
        inputHeight = 640,
        fixedVocabularySha256 = fixedVocabularySha256,
        maximumArtifactBytes = 256L * 1_024L * 1_024L,
    )

    val depthIndoor = MachineVisionModelProfile(
        id = "depth-anything-v2-metric-indoor-small-qnn-htp",
        upstreamModelId = "depth-anything/Depth-Anything-V2-Metric-Indoor-Small-hf",
        artifactFileName = "depth-anything-v2-metric-indoor-small-qnn-htp.bin",
        kind = MachineVisionModelKind.METRIC_DEPTH,
        inputWidth = 518,
        inputHeight = 518,
        depthEnvironment = DepthEnvironment.INDOOR,
    )

    val depthOutdoor = MachineVisionModelProfile(
        id = "depth-anything-v2-metric-outdoor-small-qnn-htp",
        upstreamModelId = "depth-anything/Depth-Anything-V2-Metric-Outdoor-Small-hf",
        artifactFileName = "depth-anything-v2-metric-outdoor-small-qnn-htp.bin",
        kind = MachineVisionModelKind.METRIC_DEPTH,
        inputWidth = 518,
        inputHeight = 518,
        depthEnvironment = DepthEnvironment.OUTDOOR,
    )

    val requiredProfiles = listOf(yoloe26sBvi, depthIndoor, depthOutdoor)

    fun depth(environment: DepthEnvironment): MachineVisionModelProfile = when (environment) {
        DepthEnvironment.INDOOR -> depthIndoor
        DepthEnvironment.OUTDOOR -> depthOutdoor
    }
}

data class ModelArtifactCheck(
    val profile: MachineVisionModelProfile,
    val available: Boolean,
    val reason: String,
    val sha256: String? = null,
    val sizeBytes: Long = 0L,
)

data class ModelBundleStatus(val artifacts: List<ModelArtifactCheck>) {
    val allRequiredAvailable: Boolean = artifacts.isNotEmpty() && artifacts.all(ModelArtifactCheck::available)
}

/**
 * Verifies externally provisioned model artifacts in app-private storage.
 * Every binary needs a sibling `.sha256` file; repository builds never fetch
 * or package model weights.
 */
class PrivateModelBundleVerifier {
    fun inspect(directory: File): ModelBundleStatus = ModelBundleStatus(
        MachineVisionModelProfiles.requiredProfiles.map { inspect(directory, it) },
    )

    fun inspect(directory: File, profile: MachineVisionModelProfile): ModelArtifactCheck {
        val artifact = File(directory, profile.artifactFileName)
        val sidecar = File(directory, "${profile.artifactFileName}.sha256")
        if (!artifact.isFile || !sidecar.isFile) {
            return ModelArtifactCheck(profile, false, "artifact_or_checksum_missing")
        }
        val size = artifact.length()
        if (size !in 1L..profile.maximumArtifactBytes) {
            return ModelArtifactCheck(profile, false, "artifact_size_invalid", sizeBytes = size)
        }
        val declared = readSha256(sidecar)
        if (declared == null || !SHA256.matches(declared)) {
            return ModelArtifactCheck(profile, false, "checksum_sidecar_invalid", sizeBytes = size)
        }
        val actual = sha256(artifact)
        if (!MessageDigest.isEqual(declared.encodeToByteArray(), actual.encodeToByteArray())) {
            return ModelArtifactCheck(profile, false, "checksum_mismatch", actual, size)
        }
        profile.fixedVocabularySha256?.let { expectedVocabulary ->
            val vocabularySidecar = File(directory, "${profile.artifactFileName}.vocabulary.sha256")
            val declaredVocabulary = readSha256(vocabularySidecar)
            if (declaredVocabulary == null || !SHA256.matches(declaredVocabulary)) {
                return ModelArtifactCheck(profile, false, "vocabulary_checksum_missing_or_invalid", actual, size)
            }
            if (!MessageDigest.isEqual(
                    expectedVocabulary.encodeToByteArray(),
                    declaredVocabulary.encodeToByteArray(),
                )
            ) {
                return ModelArtifactCheck(profile, false, "fixed_vocabulary_mismatch", actual, size)
            }
        }
        return ModelArtifactCheck(profile, true, "verified", actual, size)
    }

    private companion object {
        val SHA256 = Regex("[a-f0-9]{64}")

        fun readSha256(file: File): String? {
            if (!file.isFile || file.length() !in 64L..128L) return null
            return runCatching { file.readText().trim().lowercase() }
                .getOrNull()
                ?.takeIf(SHA256::matches)
        }
    }
}

data class QualcommRuntimeEvidence(
    val socManufacturer: String,
    val socModel: String,
    val supportedAbis: List<String>,
    val qnnAdapterInitialized: Boolean,
    val htpBackendInitialized: Boolean,
    val htpArchitecture: Int? = null,
    val cpuReferenceBackendAvailable: Boolean = true,
)

data class AcceleratorPlan(
    val target: AcceleratorTarget,
    val reason: String,
    val requiresStaticShapes: Boolean,
    val requiresQuantizedModel: Boolean,
)

/** HTP is the Snapdragon 8 Elite NPU path. HTA is intentionally not selected. */
object QualcommAcceleratorPlanner {
    fun select(evidence: QualcommRuntimeEvidence): AcceleratorPlan {
        val isQualcomm = evidence.socManufacturer.equals("QTI", ignoreCase = true) ||
            evidence.socManufacturer.contains("Qualcomm", ignoreCase = true)
        val isArm64 = evidence.supportedAbis.any { it == "arm64-v8a" }
        if (isQualcomm && isArm64 && evidence.qnnAdapterInitialized && evidence.htpBackendInitialized) {
            return AcceleratorPlan(
                AcceleratorTarget.QNN_HTP,
                "QNN HTP initialized on ${evidence.socModel.ifBlank { "Qualcomm SoC" }}",
                requiresStaticShapes = true,
                requiresQuantizedModel = true,
            )
        }
        if (evidence.cpuReferenceBackendAvailable) {
            val reason = when {
                !isQualcomm -> "non-Qualcomm SoC"
                !isArm64 -> "arm64 ABI unavailable"
                !evidence.qnnAdapterInitialized -> "QNN adapter unavailable"
                else -> "QNN HTP backend unavailable"
            }
            return AcceleratorPlan(
                AcceleratorTarget.CPU_REFERENCE,
                reason,
                requiresStaticShapes = false,
                requiresQuantizedModel = false,
            )
        }
        return AcceleratorPlan(
            AcceleratorTarget.UNAVAILABLE,
            "no verified inference backend",
            requiresStaticShapes = false,
            requiresQuantizedModel = false,
        )
    }
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
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }
