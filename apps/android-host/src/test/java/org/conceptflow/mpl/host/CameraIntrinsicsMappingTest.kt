// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host

import org.conceptflow.mpl.host.vision.CameraIntrinsicsSource
import org.conceptflow.mpl.host.vision.CameraLensDistortionModel
import org.conceptflow.mpl.v1.CameraIntrinsics
import org.conceptflow.mpl.v1.CameraIntrinsicsProvenance
import org.conceptflow.mpl.v1.CameraIntrinsicsUncertainty
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.ImageDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class CameraIntrinsicsMappingTest {
    @Test
    fun `derived protocol intrinsics remain derived and absence of uncertainty remains absent`() {
        val mapped = requireNotNull(
            validatedCameraIntrinsics(
                frame(CameraIntrinsicsProvenance.CAMERA_INTRINSICS_PROVENANCE_DERIVED),
            ),
        )

        assertEquals(CameraIntrinsicsSource.DERIVED, mapped.source)
        assertEquals(CameraLensDistortionModel.BROWN_CONRADY_ZERO, mapped.distortionModel)
        assertNull(mapped.standardDeviation)
    }

    @Test
    fun `calibrated and derived matrices cannot share a guided calibration fingerprint`() {
        val calibrated = requireNotNull(
            validatedCameraIntrinsics(
                frame(CameraIntrinsicsProvenance.CAMERA_INTRINSICS_PROVENANCE_CALIBRATED),
            ),
        )
        val derived = requireNotNull(
            validatedCameraIntrinsics(
                frame(CameraIntrinsicsProvenance.CAMERA_INTRINSICS_PROVENANCE_DERIVED),
            ),
        )

        assertNotEquals(calibrated.calibrationFingerprint, derived.calibrationFingerprint)
    }

    @Test
    fun `reported standard deviations are preserved but not synthesized`() {
        val mapped = requireNotNull(
            validatedCameraIntrinsics(
                frame(
                    CameraIntrinsicsProvenance.CAMERA_INTRINSICS_PROVENANCE_CALIBRATED,
                    CameraIntrinsicsUncertainty.newBuilder()
                        .setFocalXStddevPixels(1.0)
                        .setFocalYStddevPixels(1.1)
                        .setPrincipalXStddevPixels(0.5)
                        .setPrincipalYStddevPixels(0.6)
                        .build(),
                ),
            ),
        )

        assertEquals(1.0, requireNotNull(mapped.standardDeviation).focalLengthXPixels, 0.0)
    }

    @Test
    fun `nonzero Brown Conrady distortion is rejected instead of silently projected`() {
        assertNull(
            validatedCameraIntrinsics(
                frame(
                    CameraIntrinsicsProvenance.CAMERA_INTRINSICS_PROVENANCE_CALIBRATED,
                    distortion = listOf(0.1, -0.2, 0.003, -0.004, 0.05),
                ),
            ),
        )
    }

    @Test
    fun `missing or malformed distortion vector is rejected`() {
        assertNull(
            validatedCameraIntrinsics(
                frame(
                    CameraIntrinsicsProvenance.CAMERA_INTRINSICS_PROVENANCE_CALIBRATED,
                    distortion = emptyList(),
                ),
            ),
        )
        assertNull(
            validatedCameraIntrinsics(
                frame(
                    CameraIntrinsicsProvenance.CAMERA_INTRINSICS_PROVENANCE_CALIBRATED,
                    distortion = listOf(0.0, 0.0),
                ),
            ),
        )
    }

    @Test
    fun `five zero coefficients preserve the Rokid derived pinhole path`() {
        val mapped = validatedCameraIntrinsics(
            frame(CameraIntrinsicsProvenance.CAMERA_INTRINSICS_PROVENANCE_DERIVED),
        )

        assertNotNull(mapped)
        assertEquals(0.0, requireNotNull(mapped).vectorAtDepth(320.0, 180.0, 2.0).x, 0.0)
        assertEquals(CameraIntrinsicsSource.DERIVED, mapped.source)
    }

    private fun frame(
        provenance: CameraIntrinsicsProvenance,
        uncertainty: CameraIntrinsicsUncertainty? = null,
        distortion: List<Double> = List(5) { 0.0 },
    ): FramePayload {
        val intrinsicsBuilder = CameraIntrinsics.newBuilder()
            .setFocalXPixels(500.0)
            .setFocalYPixels(500.0)
            .setPrincipalXPixels(320.0)
            .setPrincipalYPixels(180.0)
            .setCalibratedWidth(640)
            .setCalibratedHeight(360)
            .setProvenance(provenance)
            .addAllDistortionCoefficients(distortion)
        uncertainty?.let { intrinsicsBuilder.setUncertainty(it) }
        val intrinsics = intrinsicsBuilder.build()
        return FramePayload.newBuilder()
            .setImage(ImageDescriptor.newBuilder().setWidth(640).setHeight(360))
            .setIntrinsics(intrinsics)
            .build()
    }
}
