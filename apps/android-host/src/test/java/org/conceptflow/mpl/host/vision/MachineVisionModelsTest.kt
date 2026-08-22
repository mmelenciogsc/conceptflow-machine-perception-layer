// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MachineVisionModelsTest {
    @Test
    fun profilesUseThePinnedSmallDepthSourcesAndValidatedFp16Libraries() {
        assertEquals(
            "depth-anything/Depth-Anything-V2-Metric-Hypersim-Small",
            MachineVisionModelProfiles.depthIndoor.upstreamModelId,
        )
        assertEquals(
            "depth-anything/Depth-Anything-V2-Metric-VKITTI-Small",
            MachineVisionModelProfiles.depthOutdoor.upstreamModelId,
        )
        MachineVisionModelProfiles.requiredProfiles.forEach { profile ->
            assertEquals(QnnNumericProfile.FLOAT16, profile.numericProfile)
            assertTrue(profile.artifactFileName.startsWith("lib"))
            assertTrue(profile.artifactFileName.endsWith(".so"))
        }
    }

    @Test
    fun selectsHtpOnlyAfterProjectAdapterAndBackendInitialize() {
        val eligible = QualcommRuntimeEvidence(
            socManufacturer = "QTI",
            socModel = "SM8750",
            supportedAbis = listOf("arm64-v8a"),
            qnnAdapterInitialized = true,
            htpBackendInitialized = true,
            htpArchitecture = 79,
        )
        val htpPlan = QualcommAcceleratorPlanner.select(eligible)
        assertEquals(AcceleratorTarget.QNN_HTP, htpPlan.target)
        assertEquals(QnnNumericProfile.FLOAT16, htpPlan.numericProfile)
        assertEquals(
            AcceleratorTarget.CPU_REFERENCE,
            QualcommAcceleratorPlanner.select(eligible.copy(qnnAdapterInitialized = false)).target,
        )
        assertEquals(
            AcceleratorTarget.UNAVAILABLE,
            QualcommAcceleratorPlanner.select(
                eligible.copy(qnnAdapterInitialized = false, cpuReferenceBackendAvailable = false),
            ).target,
        )
    }

    @Test
    fun bundleRequiresBothDepthProfilesAndFixedVocabularyProof() {
        val directory = Files.createTempDirectory("conceptflow-models-").toFile()
        try {
            MachineVisionModelProfiles.requiredProfiles.forEach { profile ->
                val bytes = aarch64ElfFixture(profile.id)
                File(directory, profile.artifactFileName).writeBytes(bytes)
                File(directory, "${profile.artifactFileName}.sha256").writeText(sha256(bytes))
                profile.fixedVocabularySha256?.let {
                    File(directory, "${profile.artifactFileName}.vocabulary.sha256").writeText(it)
                }
            }
            val verifier = PrivateModelBundleVerifier()
            assertTrue(verifier.inspect(directory).allRequiredAvailable)

            File(directory, MachineVisionModelProfiles.depthOutdoor.artifactFileName).delete()
            assertFalse(verifier.inspect(directory).allRequiredAvailable)

            val yolo = MachineVisionModelProfiles.yoloe26sBvi
            val replacement = aarch64ElfFixture("replacement")
            File(directory, yolo.artifactFileName).writeBytes(replacement)
            File(directory, "${yolo.artifactFileName}.sha256").writeText(
                sha256(replacement),
            )
            File(directory, "${yolo.artifactFileName}.vocabulary.sha256").writeText("0".repeat(64))
            assertEquals("fixed_vocabulary_mismatch", verifier.inspect(directory, yolo).reason)

            val depth = MachineVisionModelProfiles.depthIndoor
            File(directory, depth.artifactFileName).writeText("not an ELF library")
            File(directory, "${depth.artifactFileName}.sha256").writeText(
                sha256("not an ELF library".encodeToByteArray()),
            )
            assertEquals("artifact_format_invalid", verifier.inspect(directory, depth).reason)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun aarch64ElfFixture(marker: String): ByteArray = ByteArray(64).also { bytes ->
        bytes[0] = 0x7f
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 2
        bytes[5] = 1
        bytes[18] = 0xb7.toByte()
        marker.encodeToByteArray().take(32).forEachIndexed { index, value -> bytes[24 + index] = value }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
