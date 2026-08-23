// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.hardware

import org.conceptflow.mpl.rokid.core.PixelDimensions
import org.conceptflow.mpl.v1.CameraIntrinsicsProvenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCalibrationMathTest {
    @Test
    fun centeredVerticalCropMapsFourByThreeCalibrationToSixteenByNineOutput() {
        val scaled = accepted(
            metadata(
                intrinsics = listOf(2_400.0, 2_400.0, 2_000.0, 1_500.0, 0.0),
                width = 4_000.0,
                height = 3_000.0,
            ),
            PixelDimensions(1_920, 1_080),
        )

        assertEquals(1_152.0, scaled.focalXPixels, EPSILON)
        assertEquals(1_152.0, scaled.focalYPixels, EPSILON)
        assertEquals(960.0, scaled.principalXPixels, EPSILON)
        assertEquals(540.0, scaled.principalYPixels, EPSILON)
        assertEquals(PixelDimensions(1_920, 1_080), scaled.dimensions)
    }

    @Test
    fun centeredHorizontalCropUsesCalibrationArrayRelativeCoordinates() {
        val scaled = accepted(
            metadata(
                intrinsics = listOf(2_000.0, 2_000.0, 2_000.0, 1_000.0, 0.0),
                width = 4_000.0,
                height = 2_000.0,
            ),
            PixelDimensions(800, 600),
        )

        assertEquals(600.0, scaled.focalXPixels, EPSILON)
        assertEquals(600.0, scaled.focalYPixels, EPSILON)
        assertEquals(400.0, scaled.principalXPixels, EPSILON)
        assertEquals(300.0, scaled.principalYPixels, EPSILON)
    }

    @Test
    fun aspectFitOutputUsesActualDimensionsWithoutInventingLetterboxPixels() {
        val scaled = accepted(
            metadata(
                intrinsics = listOf(2_000.0, 2_000.0, 2_000.0, 1_500.0, 0.0),
                width = 4_000.0,
                height = 3_000.0,
            ),
            PixelDimensions(1_440, 1_080),
        )

        assertEquals(720.0, scaled.focalXPixels, EPSILON)
        assertEquals(720.0, scaled.focalYPixels, EPSILON)
        assertEquals(720.0, scaled.principalXPixels, EPSILON)
        assertEquals(540.0, scaled.principalYPixels, EPSILON)
        assertEquals(PixelDimensions(1_440, 1_080), scaled.dimensions)
    }

    @Test
    fun zeroPrincipalPointWithoutPhysicalMetadataIsRejected() {
        val result = scaleCameraCalibration(
            metadata(
                intrinsics = listOf(2_000.0, 2_000.0, 0.0, 0.0, 0.0),
                width = 4_000.0,
                height = 3_000.0,
            ),
            PixelDimensions(1_920, 1_080),
        )

        assertRejected(result, CameraCalibrationRejection.MISSING_PHYSICAL_METADATA)
    }

    @Test
    fun rokidPhysicalMetadataDerivesCenteredSixteenByNineIntrinsics() {
        val scaled = accepted(rokidMetadata(), PixelDimensions(1_920, 1_080))

        assertEquals(904.7619047619, scaled.focalXPixels, EPSILON)
        assertEquals(904.7619047619, scaled.focalYPixels, EPSILON)
        assertEquals(960.0, scaled.principalXPixels, EPSILON)
        assertEquals(540.0, scaled.principalYPixels, EPSILON)
        assertEquals(
            CameraIntrinsicsProvenance.CAMERA_INTRINSICS_PROVENANCE_DERIVED,
            scaled.provenance,
        )
    }

    @Test
    fun correlatedCaptureCropControlsMapping() {
        val scaled = accepted(
            rokidMetadata(),
            PixelDimensions(1_920, 1_080),
            CameraCalibrationCrop(left = 336.0, top = 500.0, width = 3_360.0, height = 1_890.0),
        )

        assertEquals(1_085.7142857143, scaled.focalXPixels, EPSILON)
        assertEquals(1_085.7142857143, scaled.focalYPixels, EPSILON)
        assertEquals(960.0, scaled.principalXPixels, EPSILON)
        assertEquals(578.2857142857, scaled.principalYPixels, EPSILON)
    }

    @Test
    fun inconsistentPhysicalMetadataIsRejected() {
        val result = scaleCameraCalibration(
            rokidMetadata().copy(
                physicalFallback = rokidMetadata().physicalFallback!!.copy(pixelArrayWidth = 4_000.0),
            ),
            PixelDimensions(1_920, 1_080),
        )

        assertRejected(result, CameraCalibrationRejection.INCONSISTENT_PHYSICAL_METADATA)
    }

    @Test
    fun principalPointOutsideSelectedCenterCropIsRejected() {
        val result = scaleCameraCalibration(
            metadata(
                intrinsics = listOf(2_000.0, 2_000.0, 2_000.0, 100.0, 0.0),
                width = 4_000.0,
                height = 3_000.0,
            ),
            PixelDimensions(1_920, 1_080),
        )

        assertRejected(result, CameraCalibrationRejection.PRINCIPAL_POINT_OUTSIDE_OUTPUT_CROP)
    }

    @Test
    fun nonZeroSkewIsRejectedBecauseCurrentProtocolCannotRepresentIt() {
        val result = scaleCameraCalibration(
            metadata(
                intrinsics = listOf(2_000.0, 2_000.0, 2_000.0, 1_500.0, 0.25),
                width = 4_000.0,
                height = 3_000.0,
            ),
            PixelDimensions(1_920, 1_080),
        )

        assertRejected(result, CameraCalibrationRejection.UNSUPPORTED_SKEW)
    }

    @Test
    fun documentedFiveCoefficientDistortionIsPreserved() {
        val distortion = listOf(0.1, -0.2, 0.003, -0.004, 0.05)
        val scaled = accepted(
            metadata(
                intrinsics = listOf(2_000.0, 2_000.0, 2_000.0, 1_500.0, 0.0),
                distortion = distortion,
                width = 4_000.0,
                height = 3_000.0,
            ),
            PixelDimensions(1_920, 1_080),
        )

        assertEquals(distortion, scaled.distortionCoefficients)
    }

    @Test
    fun malformedOrNonFiniteDistortionIsOmittedWithoutFabrication() {
        val wrongCount = accepted(
            metadata(
                intrinsics = listOf(2_000.0, 2_000.0, 2_000.0, 1_500.0, 0.0),
                distortion = listOf(0.1, 0.2),
                width = 4_000.0,
                height = 3_000.0,
            ),
            PixelDimensions(1_920, 1_080),
        )
        val nonFinite = accepted(
            metadata(
                intrinsics = listOf(2_000.0, 2_000.0, 2_000.0, 1_500.0, 0.0),
                distortion = listOf(0.1, 0.2, Double.NaN, 0.4, 0.5),
                width = 4_000.0,
                height = 3_000.0,
            ),
            PixelDimensions(1_920, 1_080),
        )

        assertTrue(wrongCount.distortionCoefficients.isEmpty())
        assertTrue(nonFinite.distortionCoefficients.isEmpty())
    }

    @Test
    fun nonFiniteIntrinsicCalibrationIsRejected() {
        val result = scaleCameraCalibration(
            metadata(
                intrinsics = listOf(Double.NaN, 2_000.0, 2_000.0, 1_500.0, 0.0),
                width = 4_000.0,
                height = 3_000.0,
            ),
            PixelDimensions(1_920, 1_080),
        )

        assertRejected(result, CameraCalibrationRejection.MALFORMED_INTRINSICS)
    }

    @Test
    fun protocolPacketCarriesScaledCalibrationAndNoInventedUncertainty() {
        val packet = metadata(
            intrinsics = listOf(2_400.0, 2_400.0, 2_000.0, 1_500.0, 0.0),
            distortion = listOf(0.1, -0.2, 0.003, -0.004, 0.05),
            width = 4_000.0,
            height = 3_000.0,
        ).toProtocolCameraIntrinsics(PixelDimensions(1_920, 1_080))!!

        assertEquals(1_152.0, packet.focalXPixels, EPSILON)
        assertEquals(1_152.0, packet.focalYPixels, EPSILON)
        assertEquals(960.0, packet.principalXPixels, EPSILON)
        assertEquals(540.0, packet.principalYPixels, EPSILON)
        assertEquals(1_920, packet.calibratedWidth)
        assertEquals(1_080, packet.calibratedHeight)
        assertEquals(5, packet.distortionCoefficientsCount)
        assertEquals(
            CameraIntrinsicsProvenance.CAMERA_INTRINSICS_PROVENANCE_CALIBRATED,
            packet.provenance,
        )
        assertTrue(!packet.hasUncertainty())
    }

    @Test
    fun derivedProtocolPacketIsExplicitlyDerivedAndDoesNotInventStandardDeviation() {
        val packet = rokidMetadata().toProtocolCameraIntrinsics(PixelDimensions(1_920, 1_080))!!

        assertEquals(
            CameraIntrinsicsProvenance.CAMERA_INTRINSICS_PROVENANCE_DERIVED,
            packet.provenance,
        )
        assertTrue(!packet.hasUncertainty())
    }

    @Test
    fun rokidPhysicalFallbackRequiresTheCompleteMetadataFingerprint() {
        val metadata = rokidMetadata()

        assertTrue(
            matchesRokidCamera2PhysicalDerivationFingerprint(
                metadata.intrinsicCalibration,
                metadata.distortionCoefficients,
                metadata.coordinateSpace,
                metadata.coordinateSpaceLeftInPixelArray,
                metadata.coordinateSpaceTopInPixelArray,
                metadata.physicalFallback!!,
                sensorOrientationDegrees = 270,
                centerOnlyCropping = true,
            ),
        )
        assertTrue(
            !matchesRokidCamera2PhysicalDerivationFingerprint(
                metadata.intrinsicCalibration,
                metadata.distortionCoefficients,
                metadata.coordinateSpace,
                metadata.coordinateSpaceLeftInPixelArray,
                metadata.coordinateSpaceTopInPixelArray,
                metadata.physicalFallback,
                sensorOrientationDegrees = 90,
                centerOnlyCropping = true,
            ),
        )
        assertTrue(
            !matchesRokidCamera2PhysicalDerivationFingerprint(
                metadata.intrinsicCalibration,
                listOf(0.01, 0.0, 0.0, 0.0, 0.0),
                metadata.coordinateSpace,
                metadata.coordinateSpaceLeftInPixelArray,
                metadata.coordinateSpaceTopInPixelArray,
                metadata.physicalFallback,
                sensorOrientationDegrees = 270,
                centerOnlyCropping = true,
            ),
        )
        assertTrue(
            !matchesRokidCamera2PhysicalDerivationFingerprint(
                metadata.intrinsicCalibration,
                metadata.distortionCoefficients,
                metadata.coordinateSpace,
                metadata.coordinateSpaceLeftInPixelArray,
                metadata.coordinateSpaceTopInPixelArray,
                metadata.physicalFallback,
                sensorOrientationDegrees = 270,
                centerOnlyCropping = false, // Camera2 FREEFORM.
            ),
        )
    }

    private fun metadata(
        intrinsics: List<Double>,
        distortion: List<Double>? = null,
        width: Double,
        height: Double,
    ) = Camera2CalibrationMetadata(
        intrinsicCalibration = intrinsics,
        distortionCoefficients = distortion,
        coordinateSpace = CameraCalibrationCoordinateSpace(width, height),
    )

    private fun accepted(
        metadata: Camera2CalibrationMetadata,
        output: PixelDimensions,
        crop: CameraCalibrationCrop? = null,
    ): ScaledCameraCalibration {
        val result = scaleCameraCalibration(metadata, output, crop)
        assertTrue("Expected accepted calibration but got $result", result is CameraCalibrationScaleResult.Accepted)
        return (result as CameraCalibrationScaleResult.Accepted).calibration
    }

    private fun rokidMetadata() = Camera2CalibrationMetadata(
        intrinsicCalibration = listOf(1_900.0, 1_900.0, 0.0, 0.0, 0.0),
        distortionCoefficients = List(5) { 0.0 },
        coordinateSpace = CameraCalibrationCoordinateSpace(4_032.0, 3_024.0),
        physicalFallback = CameraPhysicalIntrinsicsMetadata(
            focalLengthMillimeters = 1.9,
            sensorPhysicalWidthMillimeters = 4.032,
            sensorPhysicalHeightMillimeters = 3.024,
            pixelArrayWidth = 4_032.0,
            pixelArrayHeight = 3_024.0,
            evidence = CameraPhysicalIntrinsicsEvidence.ROKID_CAMERA2_METADATA_FINGERPRINT,
        ),
    )

    private fun assertRejected(
        result: CameraCalibrationScaleResult,
        expected: CameraCalibrationRejection,
    ) {
        assertTrue("Expected rejected calibration but got $result", result is CameraCalibrationScaleResult.Rejected)
        assertEquals(expected, (result as CameraCalibrationScaleResult.Rejected).reason)
    }

    private companion object {
        const val EPSILON = 1e-9
    }
}
