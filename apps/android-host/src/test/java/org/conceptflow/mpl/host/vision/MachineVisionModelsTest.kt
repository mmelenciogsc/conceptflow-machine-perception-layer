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
    fun selectsHtpOnlyAfterProjectAdapterAndBackendInitialize() {
        val eligible = QualcommRuntimeEvidence(
            socManufacturer = "QTI",
            socModel = "SM8750",
            supportedAbis = listOf("arm64-v8a"),
            qnnAdapterInitialized = true,
            htpBackendInitialized = true,
            htpArchitecture = 79,
        )
        assertEquals(AcceleratorTarget.QNN_HTP, QualcommAcceleratorPlanner.select(eligible).target)
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
                val bytes = "fixture-${profile.id}".encodeToByteArray()
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
            File(directory, yolo.artifactFileName).writeText("replacement")
            File(directory, "${yolo.artifactFileName}.sha256").writeText(
                sha256("replacement".encodeToByteArray()),
            )
            File(directory, "${yolo.artifactFileName}.vocabulary.sha256").writeText("0".repeat(64))
            assertEquals("fixed_vocabulary_mismatch", verifier.inspect(directory, yolo).reason)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
