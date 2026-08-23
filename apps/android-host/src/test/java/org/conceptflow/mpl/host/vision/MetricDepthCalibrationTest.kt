// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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
        assertTrue(requireNotNull(estimate.uncertaintyMeters) > 0.0)
        assertNull(
            PinholeDimensionEstimator.estimate(
                MaskExtentObservation("wall", 300, 300, 1_000.0, 1_000.0, 0.9),
            ),
        )
    }

    @Test
    fun pinnedOfficialMetricProfilesPreserveMetresAndExposeUnquantifiedError() {
        val indoor = requireNotNull(
            OfficialDepthAnythingV2MetricSemanticsProvider.resolve(
                MachineVisionModelProfiles.depthIndoorBalanced,
            ),
        )
        val outdoor = requireNotNull(
            OfficialDepthAnythingV2MetricSemanticsProvider.resolve(
                MachineVisionModelProfiles.depthOutdoorBalanced,
            ),
        )

        val indoorEstimate = requireNotNull(indoor.estimate(2.75))
        assertEquals(2.75, indoorEstimate.distanceMeters, 0.0)
        assertNull(indoorEstimate.uncertaintyMeters)
        assertEquals(MetricDepthUncertaintyBasis.UNQUANTIFIED_MODEL_ERROR, indoorEstimate.uncertaintyBasis)
        assertEquals(MetricDepthProvenanceKind.PINNED_OFFICIAL_NATIVE_METRIC, indoor.provenance.kind)
        assertEquals(20.0, indoor.provenance.declaredMaximumDepthMeters!!, 0.0)
        assertNull(indoor.estimate(20.000_001))
        assertEquals(79.0, requireNotNull(outdoor.estimate(79.0)).distanceMeters, 0.0)
        assertNull(outdoor.estimate(80.000_001))
        assertNull(indoor.estimate(Double.NaN))
        assertNull(indoor.estimate(Double.POSITIVE_INFINITY))
        assertNull(indoor.estimate(0.0))
    }

    @Test
    fun nativeProviderRejectsUnknownOrAlteredProfiles() {
        val intrinsics = CameraIntrinsics(640, 360, 500.0, 500.0, 320.0, 180.0)
        val canonical = MachineVisionModelProfiles.depthIndoorBalanced
        val altered = canonical.copy(id = "unverified-native-metric-profile")

        assertNull(OfficialDepthAnythingV2MetricSemanticsProvider.resolve(altered))
        val official = requireNotNull(OfficialDepthAnythingV2MetricSemanticsProvider.resolve(canonical))
        assertThrows(IllegalArgumentException::class.java) {
            official.copy(
                binding = MetricDepthCalibrationBinding.forProfile(canonical, intrinsics),
            )
        }
    }

    private fun sample(classId: String, distance: ReferenceDistance, value: Double) = GuidedCalibrationSample(
        classId = classId,
        referenceDistance = distance,
        relativeDepthValue = value,
        confidence = 0.95,
    )
}
