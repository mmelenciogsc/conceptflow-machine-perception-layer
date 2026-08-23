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
    fun productionVerifierRejectsSelfSuppliedChecksumForUntrustedBytes() {
        val directory = Files.createTempDirectory("conceptflow-untrusted-model-").toFile()
        try {
            val profile = MachineVisionModelProfiles.depthIndoorBalanced
            val bytes = aarch64ElfFixture("self-supplied")
            File(directory, profile.artifactFileName).writeBytes(bytes)
            File(directory, "${profile.artifactFileName}.sha256").writeText(sha256(bytes))

            val check = PrivateModelBundleVerifier().inspect(directory, profile)

            assertFalse(check.available)
            assertEquals("artifact_digest_not_trusted", check.reason)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun catalogPinsEnvironmentWeightsAndThreeStaticResolutionTiers() {
        assertEquals(
            "depth-anything/Depth-Anything-V2-Metric-Hypersim-Small",
            MachineVisionModelProfiles.depthIndoor.upstreamModelId,
        )
        assertEquals(
            "depth-anything/Depth-Anything-V2-Metric-VKITTI-Small",
            MachineVisionModelProfiles.depthOutdoor.upstreamModelId,
        )
        val depthProfiles = MachineVisionModelProfiles.allProfiles.filter {
            it.kind == MachineVisionModelKind.METRIC_DEPTH
        }
        assertEquals(6, depthProfiles.size)
        assertEquals(setOf(336, 392, 518), depthProfiles.map { it.inputWidth }.toSet())
        depthProfiles.forEach { profile ->
            assertEquals(QnnNumericProfile.FLOAT16, profile.numericProfile)
            assertTrue(profile.artifactFileName.startsWith("lib"))
            assertTrue(profile.artifactFileName.endsWith(".so"))
        }
        assertEquals(392, MachineVisionModelProfiles.depthIndoor.inputWidth)
        assertEquals(392, MachineVisionModelProfiles.depthOutdoor.inputWidth)
        assertEquals(
            MachineVisionModelProfiles.depthIndoorLowPower,
            MachineVisionModelProfiles.resolveDepth(
                DepthEnvironment.INDOOR,
                DepthResolutionTier.LOW_POWER,
            ),
        )
        assertEquals(108.44, MachineVisionModelProfiles.depthIndoor.measuredStandaloneMedianMillis!!, 0.0)
        assertEquals(111.43, MachineVisionModelProfiles.depthOutdoor.measuredStandaloneMedianMillis!!, 0.0)
        assertEquals(
            "libdepth_indoor_fp16.so",
            MachineVisionModelProfiles.depthIndoorReference.artifactFileName,
        )
        assertEquals(
            "libdepth_outdoor_fp16.so",
            MachineVisionModelProfiles.depthOutdoorReference.artifactFileName,
        )
        assertEquals(3, MachineVisionModelProfiles.requiredProfiles.size)
        assertEquals(4, MachineVisionModelProfiles.optionalProfiles.size)
    }

    @Test
    fun routingUsesEnvironmentForWeightsAndServiceTierForResolution() {
        val available = MachineVisionModelProfiles.allProfiles.mapTo(mutableSetOf()) { it.id }

        val balanced = DepthModelRoutingPolicy.select(
            DepthModelRoutingRequest(DepthEnvironment.OUTDOOR, maximumEndToEndLatencyMillis = 140.0),
            available,
        )
        assertEquals(MachineVisionModelProfiles.depthOutdoorBalanced, balanced.profile)
        assertEquals("balanced_runtime_default", balanced.reason)

        val lowPower = DepthModelRoutingPolicy.select(
            DepthModelRoutingRequest(
                DepthEnvironment.INDOOR,
                serviceTier = DepthServiceTier.LOW_POWER,
                maximumEndToEndLatencyMillis = 100.0,
            ),
            available,
        )
        assertEquals(MachineVisionModelProfiles.depthIndoorLowPower, lowPower.profile)

        val calibration = DepthModelRoutingPolicy.select(
            DepthModelRoutingRequest(
                DepthEnvironment.INDOOR,
                serviceTier = DepthServiceTier.CALIBRATION,
                maximumEndToEndLatencyMillis = 300.0,
            ),
            available,
        )
        assertEquals(MachineVisionModelProfiles.depthIndoorReference, calibration.profile)
    }

    @Test
    fun routingDegradesBalancedForBudgetOrPressureButNotAfterArtifactSelection() {
        val allAvailable = MachineVisionModelProfiles.allProfiles.mapTo(mutableSetOf()) { it.id }
        val forBudget = DepthModelRoutingPolicy.select(
            DepthModelRoutingRequest(DepthEnvironment.INDOOR, maximumEndToEndLatencyMillis = 100.0),
            allAvailable,
        )
        assertEquals(MachineVisionModelProfiles.depthIndoorLowPower, forBudget.profile)
        assertEquals("latency_budget_low_power", forBudget.reason)

        val forPressure = DepthModelRoutingPolicy.select(
            DepthModelRoutingRequest(
                DepthEnvironment.OUTDOOR,
                maximumEndToEndLatencyMillis = 120.0,
                thermalPressure = DevicePressure.ELEVATED,
            ),
            allAvailable,
        )
        assertEquals(MachineVisionModelProfiles.depthOutdoorLowPower, forPressure.profile)
        assertEquals("device_pressure_low_power", forPressure.reason)

        val onlyReferenceAvailable = setOf(MachineVisionModelProfiles.depthIndoorReference.id)
        val unavailableLowPower = DepthModelRoutingPolicy.select(
            DepthModelRoutingRequest(DepthEnvironment.INDOOR, maximumEndToEndLatencyMillis = 100.0),
            onlyReferenceAvailable,
        )
        assertFalse(unavailableLowPower.canRun)
        assertEquals("selected_artifact_unavailable", unavailableLowPower.reason)
    }

    @Test
    fun referenceRequestsAreSparseExplicitAndFailClosedWhenUnsafe() {
        val available = MachineVisionModelProfiles.allProfiles.mapTo(mutableSetOf()) { it.id }
        val ambiguityReference = DepthModelRoutingPolicy.select(
            DepthModelRoutingRequest(
                DepthEnvironment.OUTDOOR,
                maximumEndToEndLatencyMillis = 300.0,
                sparseAmbiguityReferenceRequested = true,
            ),
            available,
        )
        assertEquals(MachineVisionModelProfiles.depthOutdoorReference, ambiguityReference.profile)

        val pressureBlocked = DepthModelRoutingPolicy.select(
            DepthModelRoutingRequest(
                DepthEnvironment.OUTDOOR,
                serviceTier = DepthServiceTier.REFERENCE,
                maximumEndToEndLatencyMillis = 300.0,
                batteryPressure = DevicePressure.CRITICAL,
            ),
            available,
        )
        assertFalse(pressureBlocked.canRun)
        assertEquals("reference_disallowed_under_device_pressure", pressureBlocked.reason)

        val impossibleBudget = DepthModelRoutingPolicy.select(
            DepthModelRoutingRequest(DepthEnvironment.INDOOR, maximumEndToEndLatencyMillis = 80.0),
            available,
        )
        assertFalse(impossibleBudget.canRun)
        assertEquals("latency_budget_below_measured_standalone_median", impossibleBudget.reason)
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
    fun bundleRequiresBalancedRuntimeSlotsAndReportsOptionalSlotsSeparately() {
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
            val verifier = PrivateModelBundleVerifier(allowUntrustedDevelopmentArtifacts = true)
            val requiredOnly = verifier.inspect(directory)
            assertTrue(requiredOnly.allRequiredAvailable)
            assertEquals(3, requiredOnly.requiredArtifacts.size)
            assertEquals(4, requiredOnly.optionalArtifacts.size)
            assertTrue(requiredOnly.optionalArtifacts.none { it.available })

            val reference = MachineVisionModelProfiles.depthIndoorReference
            val referenceBytes = aarch64ElfFixture(reference.id)
            File(directory, reference.artifactFileName).writeBytes(referenceBytes)
            File(directory, "${reference.artifactFileName}.sha256").writeText(sha256(referenceBytes))
            assertTrue(verifier.inspect(directory).optionalArtifacts.single { it.profile == reference }.available)

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
