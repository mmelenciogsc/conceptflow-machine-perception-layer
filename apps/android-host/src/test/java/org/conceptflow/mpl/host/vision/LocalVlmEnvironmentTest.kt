// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalVlmEnvironmentTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun parserAcceptsOnlyOneGrammarLabel() {
        assertEquals(LocalVlmEnvironmentLabel.INDOOR, LocalVlmEnvironmentOutputParser.parse(" INDOOR\n"))
        assertEquals(LocalVlmEnvironmentLabel.TRANSITION, LocalVlmEnvironmentOutputParser.parse("transition"))
        assertNull(LocalVlmEnvironmentOutputParser.parse("INDOOR because walls are visible"))
        assertNull(LocalVlmEnvironmentOutputParser.parse("INDOOR OUTDOOR"))
        assertNull(LocalVlmEnvironmentOutputParser.parse(""))
    }

    @Test
    fun resultProducesTypedVlmEvidenceAndUnknownFailsClosed() {
        val indoor = result(1L, 10L, 20L, LocalVlmEnvironmentLabel.INDOOR).toEnvironmentSignal()!!
        val outdoor = result(2L, 30L, 40L, LocalVlmEnvironmentLabel.OUTDOOR).toEnvironmentSignal()!!

        assertEquals(EnvironmentSignalFamily.VLM_CAMERA, indoor.family)
        assertTrue(indoor.indoorProbability > 0.88)
        assertTrue(outdoor.outdoorProbability > 0.88)
        assertNull(result(3L, 50L, 60L, LocalVlmEnvironmentLabel.UNKNOWN).toEnvironmentSignal())
    }

    @Test
    fun cadenceBootstrapsConfirmsThenWaitsForMeaningfulSceneChange() {
        val gate = LocalVlmCadenceGate(
            bootstrapIntervalNanos = 10L,
            minimumChangeIntervalNanos = 100L,
            initialFailureBackoffNanos = 20L,
            maximumFailureBackoffNanos = 80L,
        )

        assertTrue(gate.needsClassification(significantSceneChange = false))
        assertTrue(gate.tryStart(0L, significantSceneChange = false))
        assertFalse(gate.tryStart(1L, significantSceneChange = false))
        gate.complete(LocalVlmEnvironmentLabel.INDOOR, 2L)
        assertFalse(gate.tryStart(11L, significantSceneChange = false))
        assertTrue(gate.tryStart(12L, significantSceneChange = false))
        gate.complete(LocalVlmEnvironmentLabel.INDOOR, 13L)
        assertTrue(gate.isStable())
        assertFalse(gate.needsClassification(significantSceneChange = false))
        assertTrue(gate.needsClassification(significantSceneChange = true))
        assertFalse(gate.tryStart(1_000L, significantSceneChange = false))
        assertTrue(gate.tryStart(113L, significantSceneChange = true))
    }

    @Test
    fun cadenceAppliesBoundedFailureBackoffAndTransitionLosesStability() {
        val gate = LocalVlmCadenceGate(
            bootstrapIntervalNanos = 10L,
            minimumChangeIntervalNanos = 100L,
            initialFailureBackoffNanos = 20L,
            maximumFailureBackoffNanos = 40L,
        )

        assertTrue(gate.tryStart(0L, significantSceneChange = false))
        gate.fail(1L)
        assertFalse(gate.tryStart(20L, significantSceneChange = false))
        assertTrue(gate.tryStart(21L, significantSceneChange = false))
        gate.fail(22L)
        assertFalse(gate.tryStart(61L, significantSceneChange = false))
        assertTrue(gate.tryStart(62L, significantSceneChange = false))
        gate.complete(LocalVlmEnvironmentLabel.TRANSITION, 63L)
        assertFalse(gate.isStable())
    }

    @Test
    fun qnnContentionDefersWithoutBecomingModelFailureAndSessionResetClearsEvidence() {
        val gate = LocalVlmCadenceGate(
            bootstrapIntervalNanos = 10L,
            minimumChangeIntervalNanos = 100L,
            initialFailureBackoffNanos = 20L,
            maximumFailureBackoffNanos = 80L,
        )
        assertTrue(gate.tryStart(0L, significantSceneChange = false))
        gate.complete(LocalVlmEnvironmentLabel.INDOOR, 1L)
        assertTrue(gate.tryStart(11L, significantSceneChange = false))
        gate.complete(LocalVlmEnvironmentLabel.INDOOR, 12L)
        assertTrue(gate.isStable())

        assertTrue(gate.tryStart(112L, significantSceneChange = true))
        gate.defer(113L, retryAfterNanos = 5L)
        assertTrue(gate.isStable())
        assertFalse(gate.tryStart(117L, significantSceneChange = true))
        assertTrue(gate.tryStart(118L, significantSceneChange = true))

        gate.reset()
        assertFalse(gate.isStable())
        assertTrue(gate.tryStart(0L, significantSceneChange = false))
    }

    @Test
    fun confirmedEnvironmentPersistsUntilAChangedSceneIsConfirmed() {
        val gate = LocalVlmCadenceGate(
            bootstrapIntervalNanos = 10L,
            minimumChangeIntervalNanos = 100L,
            initialFailureBackoffNanos = 20L,
            maximumFailureBackoffNanos = 80L,
        )
        assertTrue(gate.tryStart(0L, false))
        gate.complete(LocalVlmEnvironmentLabel.INDOOR, 1L)
        assertTrue(gate.tryStart(11L, false))
        gate.complete(LocalVlmEnvironmentLabel.INDOOR, 12L)
        assertEquals(LocalVlmEnvironmentLabel.INDOOR, gate.confirmedLabel())

        gate.invalidateForSceneChange(112L)
        assertTrue(gate.tryStart(112L, true))
        gate.complete(LocalVlmEnvironmentLabel.OUTDOOR, 113L)
        assertEquals(LocalVlmEnvironmentLabel.INDOOR, gate.confirmedLabel())
        assertTrue(gate.tryStart(123L, true))
        gate.complete(LocalVlmEnvironmentLabel.OUTDOOR, 124L)
        assertEquals(LocalVlmEnvironmentLabel.OUTDOOR, gate.confirmedLabel())
    }

    @Test
    fun sceneGateIgnoresTransientFramesAndTriggersOnPersistentLightingChange() {
        val gate = LocalVlmSceneChangeGate(requiredChangedFrames = 4)
        val baseline = descriptor(List(256) { 0.30 })
        val bright = descriptor(List(256) { 0.72 })
        gate.markClassified(baseline)

        assertFalse(gate.observe(bright).significantChange)
        assertTrue(gate.observe(baseline).baselineMatched)
        assertFalse(gate.observe(bright).significantChange)
        assertFalse(gate.observe(bright).significantChange)
        assertFalse(gate.observe(bright).significantChange)
        assertTrue(gate.observe(bright).significantChange)
    }

    @Test
    fun cameraMovementAtStableIlluminationDoesNotRequestReclassification() {
        val gate = LocalVlmSceneChangeGate(requiredChangedFrames = 1)
        val first = List(256) { index -> if (index % 16 < 8) 0.2 else 0.8 }
        val second = List(256) { index -> if (index / 16 < 8) 0.2 else 0.8 }
        gate.markClassified(descriptor(first))

        val decision = gate.observe(descriptor(second))

        assertFalse(decision.significantChange)
        assertTrue(decision.baselineMatched)
    }

    @Test
    fun histogramChangeWithoutContrastChangeDoesNotInvalidateStableEnvironment() {
        val gate = LocalVlmSceneChangeGate(requiredChangedFrames = 1)
        val first = List(256) { index -> if (index % 2 == 0) 0.30 else 0.70 }
        val second = List(256) { index -> if (index % 4 == 0) 0.15358984 else 0.61547005 }
        val firstDescriptor = descriptor(first)
        val secondDescriptor = descriptor(second)
        gate.markClassified(firstDescriptor)

        assertTrue(gate.compare(firstDescriptor, secondDescriptor).normalizedChangeScore < 1.0)
        assertTrue(gate.observe(secondDescriptor).baselineMatched)
        assertFalse(gate.observe(secondDescriptor).significantChange)
    }

    @Test
    fun distributionAndContrastChangeCanRequestReclassificationWithoutMeanShift() {
        val gate = LocalVlmSceneChangeGate(requiredChangedFrames = 1)
        val uniform = descriptor(List(256) { 0.50 })
        val highContrast = descriptor(List(256) { index -> if (index % 2 == 0) 0.20 else 0.80 })
        gate.markClassified(uniform)

        val comparison = gate.compare(uniform, highContrast)

        assertTrue(comparison.materiallyDifferent)
        assertTrue(comparison.normalizedChangeScore >= 1.0)
        assertTrue(gate.observe(highContrast).significantChange)
    }

    @Test
    fun sceneComparisonCanRejectAResponseWhoseSourceSceneHasAlreadyChanged() {
        val gate = LocalVlmSceneChangeGate(requiredChangedFrames = 2)
        val requestScene = descriptor(List(256) { 0.22 })
        val currentScene = descriptor(List(256) { 0.74 })

        val comparison = gate.compare(requestScene, currentScene)

        assertTrue(comparison.materiallyDifferent)
        assertTrue(comparison.normalizedChangeScore >= 1.0)
        assertFalse(gate.compare(requestScene, requestScene).materiallyDifferent)
    }

    @Test
    fun sparseRgbDescriptorHonorsStrideAndBrightness() {
        val dark = ByteArray(4 * 2 * 3) { 16 }
        val bright = ByteArray(4 * 2 * 3) { 220.toByte() }

        val darkDescriptor = LocalVlmSceneDescriptorExtractor.fromRgb(dark, 2, 2, 12)
        val brightDescriptor = LocalVlmSceneDescriptorExtractor.fromRgb(bright, 2, 2, 12)

        assertTrue(brightDescriptor.meanLuma > darkDescriptor.meanLuma + 0.5)
    }

    @Test
    fun artifactVerifierRejectsSizeDigestAndFormatChanges() {
        val directory = temporaryFolder.newFolder("vlm")
        val bytes = "GGUFfixture".encodeToByteArray()
        val file = File(directory, "model.gguf").apply { writeBytes(bytes) }
        val specification = LocalVlmArtifactSpec("model.gguf", bytes.size.toLong(), sha256(bytes))
        val verifier = LocalVlmArtifactVerifier(listOf(specification))

        assertEquals(LocalVlmArtifactCheck(true, "artifacts_verified"), verifier.inspect(directory))
        file.writeBytes("BAD!fixture".encodeToByteArray())
        assertEquals("artifact_format_invalid", verifier.inspect(directory).reason)
        file.writeBytes("GGUFchanged".encodeToByteArray())
        assertEquals("artifact_digest_mismatch", verifier.inspect(directory).reason)
    }

    @Test
    fun fusionTreatsVlmAndSegmentationAsOneVisualEvidenceFamily() {
        val evidence = EnvironmentEvidenceFusion().fuse(
            1_000L,
            listOf(
                signal("semantic", EnvironmentSignalFamily.CAMERA, 999L, 0.80, 0.70, 4L),
                signal("vlm", EnvironmentSignalFamily.VLM_CAMERA, 998L, 0.95, 0.85, 3L),
            ),
        )!!

        assertEquals(1, evidence.independentSignalCount)
        assertTrue(evidence.hasPrimaryVisualSignal)
        assertTrue(evidence.indoorProbability > 0.85)
    }

    @Test
    fun vlmEvidenceHasLongerButStillBoundedFreshness() {
        val fusion = EnvironmentEvidenceFusion(
            EnvironmentEvidenceFusionConfig(
                cameraMaximumAgeNanos = 10L,
                vlmCameraMaximumAgeNanos = 100L,
                gnssMaximumAgeNanos = 50L,
            ),
        )

        assertTrue(
            fusion.fuse(
                100L,
                listOf(signal("vlm", EnvironmentSignalFamily.VLM_CAMERA, 1L, 0.95, 0.85, 1L)),
            ) != null,
        )
        assertNull(
            fusion.fuse(
                102L,
                listOf(signal("vlm", EnvironmentSignalFamily.VLM_CAMERA, 1L, 0.95, 0.85, 1L)),
            ),
        )
    }

    private fun result(
        frameId: Long,
        captureNanos: Long,
        completedNanos: Long,
        label: LocalVlmEnvironmentLabel,
    ) = LocalVlmEnvironmentResult(
        frameId,
        frameId,
        captureNanos,
        completedNanos,
        label,
        LocalVlmModelProfile.MODEL_ID,
        LocalVlmModelProfile.RUNTIME_ID,
        LocalVlmModelProfile.COMPUTE_UNIT,
    )

    private fun signal(
        sourceId: String,
        family: EnvironmentSignalFamily,
        timestamp: Long,
        indoor: Double,
        reliability: Double,
        frameId: Long,
    ) = EnvironmentSignal(
        sourceId,
        family,
        timestamp,
        indoor,
        1.0 - indoor,
        reliability,
        frameId,
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun descriptor(samples: List<Double>) =
        LocalVlmSceneDescriptorExtractor.fromLumaSamples(samples)
}
