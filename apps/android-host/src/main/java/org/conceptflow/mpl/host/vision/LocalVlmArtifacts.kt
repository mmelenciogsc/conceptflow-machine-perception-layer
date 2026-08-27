// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

data class LocalVlmArtifactSpec(
    val fileName: String,
    val exactBytes: Long,
    val sha256: String,
) {
    init {
        require(FILE_NAME.matches(fileName))
        require(exactBytes > 0L)
        require(SHA256.matches(sha256))
    }

    private companion object {
        val FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{1,127}")
        val SHA256 = Regex("[a-f0-9]{64}")
    }
}
data class LocalVlmArtifactCheck(
    val available: Boolean,
    val reason: String,
) {
    init {
        require(reason.matches(Regex("[a-z0-9_]{2,64}")))
    }
}

/** Verifies external model artifacts before any native runtime receives their paths. */
class LocalVlmArtifactVerifier(
    private val specifications: List<LocalVlmArtifactSpec> = DEFAULT_SPECIFICATIONS,
) {
    init {
        require(specifications.isNotEmpty())
        require(specifications.map(LocalVlmArtifactSpec::fileName).distinct().size == specifications.size)
    }

    fun inspect(directory: File): LocalVlmArtifactCheck {
        if (!directory.isDirectory) return failure("model_directory_missing")
        val canonicalDirectory = runCatching { directory.canonicalFile }.getOrNull()
            ?: return failure("model_directory_invalid")
        for (specification in specifications) {
            val artifact = File(canonicalDirectory, specification.fileName)
            val canonicalArtifact = runCatching { artifact.canonicalFile }.getOrNull()
                ?: return failure("artifact_path_invalid")
            if (canonicalArtifact.parentFile != canonicalDirectory) return failure("artifact_path_invalid")
            if (!canonicalArtifact.isFile) return failure("artifact_missing")
            if (canonicalArtifact.length() != specification.exactBytes) return failure("artifact_size_mismatch")
            if (!hasGgufMagic(canonicalArtifact)) return failure("artifact_format_invalid")
            if (sha256(canonicalArtifact) != specification.sha256) return failure("artifact_digest_mismatch")
        }
        return LocalVlmArtifactCheck(true, "artifacts_verified")
    }

    private fun hasGgufMagic(file: File): Boolean = runCatching {
        FileInputStream(file).use { stream ->
            val magic = ByteArray(4)
            stream.read(magic) == magic.size && magic.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))
        }
    }.getOrDefault(false)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun failure(reason: String) = LocalVlmArtifactCheck(false, reason)

    companion object {
        val DEFAULT_SPECIFICATIONS = listOf(
            LocalVlmArtifactSpec(
                LocalVlmModelProfile.MODEL_FILE,
                LocalVlmModelProfile.MODEL_BYTES,
                LocalVlmModelProfile.MODEL_SHA256,
            ),
            LocalVlmArtifactSpec(
                LocalVlmModelProfile.PROJECTOR_FILE,
                LocalVlmModelProfile.PROJECTOR_BYTES,
                LocalVlmModelProfile.PROJECTOR_SHA256,
            ),
        )
    }
}
