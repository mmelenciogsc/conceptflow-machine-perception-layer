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

enum class QnnNumericProfile {
    FLOAT16,
    W8A16_EXPERIMENTAL,
}

data class MachineVisionModelProfile(
    val id: String,
    val upstreamModelId: String,
    val artifactFileName: String,
    val kind: MachineVisionModelKind,
    val inputWidth: Int,
    val inputHeight: Int,
    val numericProfile: QnnNumericProfile,
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
        id = "yoloe-26s-bvi40-seg-qnn-htp-fp16",
        upstreamModelId = "ultralytics/yoloe-26s-seg.pt",
        artifactFileName = "libyoloe_bvi40_fp16.so",
        kind = MachineVisionModelKind.INSTANCE_SEGMENTATION,
        inputWidth = 640,
        inputHeight = 640,
        numericProfile = QnnNumericProfile.FLOAT16,
        fixedVocabularySha256 = fixedVocabularySha256,
        maximumArtifactBytes = 256L * 1_024L * 1_024L,
    )

    val depthIndoor = MachineVisionModelProfile(
        id = "depth-anything-v2-metric-hypersim-small-qnn-htp-fp16",
        upstreamModelId = "depth-anything/Depth-Anything-V2-Metric-Hypersim-Small",
        artifactFileName = "libdepth_indoor_fp16.so",
        kind = MachineVisionModelKind.METRIC_DEPTH,
        inputWidth = 518,
        inputHeight = 518,
        numericProfile = QnnNumericProfile.FLOAT16,
        depthEnvironment = DepthEnvironment.INDOOR,
    )

    val depthOutdoor = MachineVisionModelProfile(
        id = "depth-anything-v2-metric-vkitti-small-qnn-htp-fp16",
        upstreamModelId = "depth-anything/Depth-Anything-V2-Metric-VKITTI-Small",
        artifactFileName = "libdepth_outdoor_fp16.so",
        kind = MachineVisionModelKind.METRIC_DEPTH,
        inputWidth = 518,
        inputHeight = 518,
        numericProfile = QnnNumericProfile.FLOAT16,
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
        if (!isAarch64SharedObject(artifact)) {
            return ModelArtifactCheck(profile, false, "artifact_format_invalid", sizeBytes = size)
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

        fun isAarch64SharedObject(file: File): Boolean {
            if (file.length() < ELF_HEADER_BYTES) return false
            val header = ByteArray(ELF_HEADER_BYTES)
            val count = FileInputStream(file).use { it.read(header) }
            return count == ELF_HEADER_BYTES &&
                header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
                header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte() &&
                header[4] == ELF_CLASS_64 && header[5] == ELF_DATA_LITTLE_ENDIAN &&
                (header[18].toInt() and 0xff) == ELF_MACHINE_AARCH64 && header[19] == 0.toByte()
        }

        const val ELF_HEADER_BYTES = 20
        const val ELF_CLASS_64: Byte = 2
        const val ELF_DATA_LITTLE_ENDIAN: Byte = 1
        const val ELF_MACHINE_AARCH64 = 183
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
    val numericProfile: QnnNumericProfile?,
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
                numericProfile = QnnNumericProfile.FLOAT16,
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
                numericProfile = null,
            )
        }
        return AcceleratorPlan(
            AcceleratorTarget.UNAVAILABLE,
            "no verified inference backend",
            requiresStaticShapes = false,
            numericProfile = null,
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
