// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricDepthCalibrationTest {
    private val calibrator = TwoAnchorMetricDepthCalibrator()

    @Test
    fun fitsExactTwoAndEightFootDepthAnchorsAndInterpolates() {
        val calibration = calibrator.calibrate(
            listOf(
                sample("door", ReferenceDistance.NEAR_TWO_FEET, 2.0),
                sample("traffic_cone", ReferenceDistance.FAR_EIGHT_FEET, 8.0),
            ),
            RelativeDepthRepresentation.DEPTH,
        )!!

        assertEquals(0.6096, calibration.estimate(2.0)!!.distanceMeters, 1e-9)
        assertEquals(2.4384, calibration.estimate(8.0)!!.distanceMeters, 1e-9)
        assertEquals(1.524, calibration.estimate(5.0)!!.distanceMeters, 1e-9)
        assertFalse(calibration.estimate(5.0)!!.extrapolated)
        assertTrue(calibration.estimate(10.0)!!.extrapolated)
    }

    @Test
    fun inverseDepthIsLinearizedBeforeFitting() {
        val calibration = calibrator.calibrate(
            listOf(
                sample("door", ReferenceDistance.NEAR_TWO_FEET, 2.0),
                sample("door", ReferenceDistance.FAR_EIGHT_FEET, 0.5),
            ),
            RelativeDepthRepresentation.INVERSE_DEPTH,
        )!!

        assertEquals(0.6096, calibration.estimate(2.0)!!.distanceMeters, 1e-9)
        assertEquals(2.4384, calibration.estimate(0.5)!!.distanceMeters, 1e-9)
    }

    @Test
    fun autoCalibrationSelectsObservedRelativeDepthDirection() {
        val direct = calibrator.calibrateAuto(
            listOf(
                sample("door", ReferenceDistance.NEAR_TWO_FEET, 2.0),
                sample("door", ReferenceDistance.FAR_EIGHT_FEET, 8.0),
            ),
        )!!
        val inverse = calibrator.calibrateAuto(
            listOf(
                sample("door", ReferenceDistance.NEAR_TWO_FEET, 8.0),
                sample("door", ReferenceDistance.FAR_EIGHT_FEET, 2.0),
            ),
        )!!

        assertEquals(RelativeDepthRepresentation.DEPTH, direct.representation)
        assertEquals(RelativeDepthRepresentation.INVERSE_DEPTH, inverse.representation)
        assertEquals(0.6096, inverse.estimate(8.0)!!.distanceMeters, 1e-9)
        assertEquals(2.4384, inverse.estimate(2.0)!!.distanceMeters, 1e-9)
    }

    @Test
    fun ignoresUnknownAndUnstableClassesAndRejectsMissingAnchor() {
        assertNull(
            calibrator.calibrate(
                listOf(
                    sample("wall", ReferenceDistance.NEAR_TWO_FEET, 2.0),
                    sample("unknown_class", ReferenceDistance.FAR_EIGHT_FEET, 8.0),
                ),
                RelativeDepthRepresentation.DEPTH,
            ),
        )
    }

    @Test
    fun pinholeEstimateCarriesClassPriorUncertainty() {
        val estimate = PinholeDimensionEstimator.estimate(
            MaskExtentObservation(
                classId = "chair",
                maskWidthPixels = 300,
                maskHeightPixels = 425,
                focalLengthXPixels = 1_000.0,
                focalLengthYPixels = 1_000.0,
                confidence = 0.9,
            ),
        )!!
        assertEquals(2.0, estimate.distanceMeters, 1e-9)
        assertTrue(estimate.uncertaintyMeters > 0.0)
        assertNull(
            PinholeDimensionEstimator.estimate(
                MaskExtentObservation("wall", 300, 300, 1_000.0, 1_000.0, 0.9),
            ),
        )
    }

    private fun sample(classId: String, distance: ReferenceDistance, value: Double) = GuidedCalibrationSample(
        classId = classId,
        referenceDistance = distance,
        relativeDepthValue = value,
        confidence = 0.95,
    )
}
