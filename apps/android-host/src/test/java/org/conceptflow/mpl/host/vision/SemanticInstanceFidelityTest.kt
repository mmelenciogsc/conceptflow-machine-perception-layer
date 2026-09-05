// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class SemanticInstanceFidelityTest {
    @Test
    fun matchesInstancesByClassAndGeometryWithoutReusingCandidates() {
        val reference = listOf(
            detection("chair", geometry(0, 0, 100, 100), 0.9),
            detection("table", geometry(200, 100, 400, 300), 0.8),
        )
        val candidate = listOf(
            detection("table", geometry(202, 102, 398, 298), 0.85),
            detection("chair", geometry(1, 1, 101, 101), 0.88),
        )

        val report = SemanticInstanceFidelity.compare(reference, candidate)

        assertEquals(2, report.matchedCount)
        assertEquals(1.0, report.precision, 0.0)
        assertEquals(1.0, report.recall, 0.0)
        assertEquals(0.94, report.meanMatchedIntersectionOverUnion, 0.05)
    }

    @Test
    fun classChangesAndExtraInstancesReduceRecallAndPrecision() {
        val reference = listOf(
            detection("chair", geometry(0, 0, 100, 100), 0.9),
            detection("table", geometry(200, 100, 400, 300), 0.8),
        )
        val candidate = listOf(
            detection("door", geometry(0, 0, 100, 100), 0.9),
            detection("table", geometry(202, 102, 398, 298), 0.85),
        )

        val report = SemanticInstanceFidelity.compare(reference, candidate)

        assertEquals(1, report.matchedCount)
        assertEquals(0.5, report.precision, 0.0)
        assertEquals(0.5, report.recall, 0.0)
    }

    private fun detection(classId: String, geometry: InstanceMaskGeometry, confidence: Double): YoloMaskDetection =
        YoloMaskDetection(
            classId = classId,
            confidence = confidence,
            geometry = geometry,
            mask = PrototypeMask(
                1,
                1,
                byteArrayOf(1),
                LetterboxTransform(640, 640, 640, 640, 640, 640, 0, 0),
            ),
            maskFingerprint = "a".repeat(64),
        )

    private fun geometry(left: Int, top: Int, right: Int, bottom: Int) = InstanceMaskGeometry(
        imageWidthPixels = 640,
        imageHeightPixels = 640,
        leftPixels = left,
        topPixels = top,
        rightExclusivePixels = right,
        bottomExclusivePixels = bottom,
    )
}
