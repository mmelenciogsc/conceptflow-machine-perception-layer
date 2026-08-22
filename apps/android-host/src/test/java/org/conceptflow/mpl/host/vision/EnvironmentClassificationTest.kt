// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentClassificationTest {
    private val classifier = BviSemanticSceneClassifier()

    @Test
    fun semanticSceneClassifierUsesOnlyStrongFixedVocabularyContext() {
        val indoor = classifier.classify(
            1L,
            100L,
            listOf(
                SceneSemanticDetection("room_number_sign", 0.95),
                SceneSemanticDetection("elevator_door", 0.90),
            ),
        )!!
        val outdoor = classifier.classify(
            2L,
            200L,
            listOf(
                SceneSemanticDetection("crosswalk", 0.95),
                SceneSemanticDetection("traffic_light", 0.90),
            ),
        )!!
        val weakDoor = classifier.classify(3L, 300L, listOf(SceneSemanticDetection("door", 0.90)))!!

        assertTrue(indoor.indoorProbability > 0.88)
        assertTrue(outdoor.outdoorProbability > 0.88)
        assertTrue(weakDoor.indoorProbability < 0.60)
        assertNull(classifier.classify(4L, 400L, listOf(SceneSemanticDetection("person", 0.99))))
    }

    @Test
    fun repeatedInstancesOfOneClassCannotOverpowerContradictoryContext() {
        val detections = List(20) { SceneSemanticDetection("car", 0.95) } +
            SceneSemanticDetection("room_number_sign", 0.95)
        val signal = classifier.classify(1L, 100L, detections)!!

        assertTrue(signal.indoorProbability > signal.outdoorProbability)
    }

    @Test
    fun gnssProvidesOnlyBoundedOutdoorSupport() {
        val strong = GnssOutdoorEvidenceInterpreter.interpret(
            GnssQualitySample(100L, 16, 9, 35.0, 5.0, 1_000_000_000L),
        )!!

        assertTrue(strong.outdoorProbability > 0.90)
        assertTrue(strong.reliability <= 0.35)
        assertNull(
            GnssOutdoorEvidenceInterpreter.interpret(
                GnssQualitySample(100L, 2, 0, 8.0, null, null),
            ),
        )
    }

    @Test
    fun fusionCountsSignalFamiliesNotIndividualCameraClassifiers() {
        val fusion = EnvironmentEvidenceFusion()
        val evidence = fusion.fuse(
            1_000_000_000L,
            listOf(
                signal("camera-scene", EnvironmentSignalFamily.CAMERA, 990_000_000L, 0.9, 0.8, 1L),
                signal("camera-semantic", EnvironmentSignalFamily.CAMERA, 990_000_001L, 0.8, 0.7, 1L),
                signal("android-gnss", EnvironmentSignalFamily.GNSS, 900_000_000L, 0.1, 0.35),
            ),
        )!!

        assertEquals(2, evidence.independentSignalCount)
        assertTrue(evidence.hasPrimaryVisualSignal)
        assertTrue(evidence.indoorProbability > 0.5)
    }

    @Test
    fun fusionRejectsStaleAndFutureSignals() {
        val fusion = EnvironmentEvidenceFusion(
            EnvironmentEvidenceFusionConfig(
                cameraMaximumAgeNanos = 100L,
                gnssMaximumAgeNanos = 200L,
                maximumFutureSkewNanos = 10L,
            ),
        )

        assertNull(
            fusion.fuse(
                1_000L,
                listOf(signal("old-camera", EnvironmentSignalFamily.CAMERA, 899L, 0.9, 1.0, 1L)),
            ),
        )
        assertNull(
            fusion.fuse(
                1_000L,
                listOf(signal("future-camera", EnvironmentSignalFamily.CAMERA, 1_011L, 0.9, 1.0, 1L)),
            ),
        )
    }

    @Test
    fun evidenceBufferRejectsDuplicateAndOutOfOrderUpdates() {
        val buffer = EnvironmentEvidenceBuffer()

        assertTrue(buffer.update(signal("camera-scene", EnvironmentSignalFamily.CAMERA, 100L, 0.9, 1.0, 1L)))
        assertFalse(buffer.update(signal("camera-scene", EnvironmentSignalFamily.CAMERA, 100L, 0.1, 1.0, 1L)))
        assertFalse(buffer.update(signal("camera-scene", EnvironmentSignalFamily.CAMERA, 99L, 0.1, 1.0, 1L)))
        assertEquals(0.9, buffer.snapshot().single().indoorProbability, 1e-9)
    }

    @Test
    fun cameraEvidenceRequiresAnOriginatingFrame() {
        val failure = runCatching {
            EnvironmentSignal(
                "camera-scene",
                EnvironmentSignalFamily.CAMERA,
                100L,
                0.9,
                0.1,
                1.0,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    private fun signal(
        sourceId: String,
        family: EnvironmentSignalFamily,
        timestamp: Long,
        indoor: Double,
        reliability: Double,
        frameId: Long? = null,
    ) = EnvironmentSignal(
        sourceId,
        family,
        timestamp,
        indoor,
        1.0 - indoor,
        reliability,
        frameId,
    )
}
