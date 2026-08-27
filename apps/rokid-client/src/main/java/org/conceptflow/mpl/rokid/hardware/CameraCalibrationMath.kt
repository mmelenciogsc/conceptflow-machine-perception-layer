// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.hardware

import com.google.protobuf.ByteString
import java.security.MessageDigest
import org.conceptflow.mpl.rokid.core.PixelDimensions
import org.conceptflow.mpl.rokid.core.SquareAspectFillTransform
import org.conceptflow.mpl.v1.CameraExtrinsicProvenance
import org.conceptflow.mpl.v1.CameraHeadExtrinsic
import org.conceptflow.mpl.v1.CameraIntrinsics
import org.conceptflow.mpl.v1.CameraIntrinsicsProvenance
import org.conceptflow.mpl.v1.Quaternion
import org.conceptflow.mpl.v1.Vector3
import kotlin.math.abs
import kotlin.math.sqrt

internal data class CameraCalibrationCoordinateSpace(
    val width: Double,
    val height: Double,
)

/** Rectangle in the calibration coordinate space, with right/bottom exclusive. */
internal data class CameraCalibrationCrop(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
)

/**
 * Physical metadata used only for an explicitly validated device metadata
 * fingerprint. Android does not define an all-zero principal point as a
 * generic "unknown" sentinel.
 */
internal data class CameraPhysicalIntrinsicsMetadata(
    val focalLengthMillimeters: Double,
    val sensorPhysicalWidthMillimeters: Double,
    val sensorPhysicalHeightMillimeters: Double,
    val pixelArrayWidth: Double,
    val pixelArrayHeight: Double,
    val evidence: CameraPhysicalIntrinsicsEvidence,
)

internal enum class CameraPhysicalIntrinsicsEvidence {
    ROKID_CAMERA2_METADATA_FINGERPRINT,
}

internal enum class Camera2PoseReference {
    PRIMARY_CAMERA,
    GYROSCOPE,
    UNDEFINED,
    AUTOMOTIVE,
}

/** Camera2 pose fields retain their documented camera-from-Android-sensor direction. */
internal data class Camera2PoseMetadata(
    val cameraFromSensorQuaternionXyzw: List<Double>,
    val cameraOpticalCenterInSensorMeters: List<Double>,
    val reference: Camera2PoseReference,
)

/**
 * Camera2 calibration metadata in pre-correction active-array coordinates.
 * Android defines that rectangle's own top-left as (0,0), independently of
 * its offset within the full physical pixel array.
 */
internal data class Camera2CalibrationMetadata(
    val intrinsicCalibration: List<Double>,
    val distortionCoefficients: List<Double>?,
    val coordinateSpace: CameraCalibrationCoordinateSpace,
    val coordinateSpaceLeftInPixelArray: Double = 0.0,
    val coordinateSpaceTopInPixelArray: Double = 0.0,
    val physicalFallback: CameraPhysicalIntrinsicsMetadata? = null,
    val captureContract: CameraCalibrationCaptureContract? = null,
    val pose: Camera2PoseMetadata? = null,
)

internal data class CameraCalibrationCaptureContract(
    val cropRegion: CameraCalibrationCrop,
    val focalLengthMillimeters: Double?,
    val requestCropRegion: Boolean,
    val verifyCropRegion: Boolean,
    val requestFocalLength: Boolean,
    val verifyFocalLength: Boolean,
    val requestUnitZoom: Boolean,
    val verifyUnitZoom: Boolean,
    val requestRotateAndCropNone: Boolean,
    val verifyRotateAndCropNone: Boolean,
    val requestDistortionCorrectionOff: Boolean,
    val verifyDistortionCorrectionOff: Boolean,
    val requestVideoStabilizationOff: Boolean,
    val verifyVideoStabilizationOff: Boolean,
    val requestOpticalStabilizationOff: Boolean,
    val verifyOpticalStabilizationOff: Boolean,
)

internal data class ScaledCameraCalibration(
    val focalXPixels: Double,
    val focalYPixels: Double,
    val principalXPixels: Double,
    val principalYPixels: Double,
    val distortionCoefficients: List<Double>,
    val dimensions: PixelDimensions,
    val provenance: CameraIntrinsicsProvenance,
)

internal enum class CameraCalibrationRejection {
    INVALID_COORDINATE_SPACE,
    INVALID_CROP_REGION,
    MALFORMED_INTRINSICS,
    INVALID_FOCAL_LENGTH,
    INVALID_PRINCIPAL_POINT,
    UNSUPPORTED_SKEW,
    PRINCIPAL_POINT_OUTSIDE_ACTIVE_ARRAY,
    PRINCIPAL_POINT_OUTSIDE_OUTPUT_CROP,
    MISSING_PHYSICAL_METADATA,
    INCONSISTENT_PHYSICAL_METADATA,
}

internal sealed interface CameraCalibrationScaleResult {
    data class Accepted(val calibration: ScaledCameraCalibration) : CameraCalibrationScaleResult

    data class Rejected(val reason: CameraCalibrationRejection) : CameraCalibrationScaleResult
}

/**
 * Maps Camera2 intrinsics to an output stream. A safely correlated
 * SCALER_CROP_REGION is used as the base region; otherwise Camera2's
 * deterministic centered stream crop is used. Any remaining aspect mismatch
 * is modeled as a centered crop followed by pixel-axis scaling.
 *
 * A valid factory calibration stays CALIBRATED. Physical derivation is allowed
 * only after a caller validates the complete Rokid Camera2 metadata fingerprint;
 * malformed or contradictory metadata is rejected rather than guessed.
 */
internal fun scaleCameraCalibration(
    metadata: Camera2CalibrationMetadata,
    output: PixelDimensions,
    correlatedCaptureCrop: CameraCalibrationCrop? = null,
    correlatedFocalLengthMillimeters: Double? = null,
): CameraCalibrationScaleResult {
    val coordinates = metadata.coordinateSpace
    if (!coordinates.width.isFinite() || !coordinates.height.isFinite() ||
        coordinates.width <= 0.0 || coordinates.height <= 0.0
    ) {
        return CameraCalibrationScaleResult.Rejected(CameraCalibrationRejection.INVALID_COORDINATE_SPACE)
    }

    val normalizedCaptureCrop = correlatedCaptureCrop?.let {
        it.copy(
            left = it.left - metadata.coordinateSpaceLeftInPixelArray,
            top = it.top - metadata.coordinateSpaceTopInPixelArray,
        )
    }
    val baseCrop = normalizedCaptureCrop ?: CameraCalibrationCrop(
        left = 0.0,
        top = 0.0,
        width = coordinates.width,
        height = coordinates.height,
    )
    if (!baseCrop.isValidInside(coordinates)) {
        return CameraCalibrationScaleResult.Rejected(CameraCalibrationRejection.INVALID_CROP_REGION)
    }
    val streamCrop = baseCrop.centerCropTo(output)

    val values = metadata.intrinsicCalibration
    val factory = when {
        values.isEmpty() -> null
        values.size != CAMERA2_INTRINSIC_PARAMETER_COUNT || values.any { !it.isFinite() } ->
            return CameraCalibrationScaleResult.Rejected(CameraCalibrationRejection.MALFORMED_INTRINSICS)
        values[0] <= 0.0 || values[1] <= 0.0 ->
            return CameraCalibrationScaleResult.Rejected(CameraCalibrationRejection.INVALID_FOCAL_LENGTH)
        values[4] != 0.0 ->
            return CameraCalibrationScaleResult.Rejected(CameraCalibrationRejection.UNSUPPORTED_SKEW)
        values[2] == 0.0 && values[3] == 0.0 -> null
        values[2] <= 0.0 || values[2] >= coordinates.width ||
            values[3] <= 0.0 || values[3] >= coordinates.height ->
            return CameraCalibrationScaleResult.Rejected(
                CameraCalibrationRejection.PRINCIPAL_POINT_OUTSIDE_ACTIVE_ARRAY,
            )
        else -> FactoryIntrinsics(values[0], values[1], values[2], values[3])
    }

    val source = if (factory != null) {
        IntrinsicsSource(
            focalX = factory.focalX,
            focalY = factory.focalY,
            principalX = factory.principalX,
            principalY = factory.principalY,
            provenance = CameraIntrinsicsProvenance.CAMERA_INTRINSICS_PROVENANCE_CALIBRATED,
        )
    } else {
        val physical = metadata.physicalFallback
            ?: return CameraCalibrationScaleResult.Rejected(
                CameraCalibrationRejection.MISSING_PHYSICAL_METADATA,
            )
        derivePhysicalIntrinsics(
            physical,
            coordinates,
            metadata.coordinateSpaceLeftInPixelArray,
            metadata.coordinateSpaceTopInPixelArray,
            correlatedFocalLengthMillimeters,
        )
            ?: return CameraCalibrationScaleResult.Rejected(
                CameraCalibrationRejection.INCONSISTENT_PHYSICAL_METADATA,
            )
    }

    if (!streamCrop.strictlyContains(source.principalX, source.principalY)) {
        return CameraCalibrationScaleResult.Rejected(
            CameraCalibrationRejection.PRINCIPAL_POINT_OUTSIDE_OUTPUT_CROP,
        )
    }
    val scaleX = output.width / streamCrop.width
    val scaleY = output.height / streamCrop.height
    val focalX = source.focalX * scaleX
    val focalY = source.focalY * scaleY
    val principalX = (source.principalX - streamCrop.left) * scaleX
    val principalY = (source.principalY - streamCrop.top) * scaleY
    if (!listOf(focalX, focalY, principalX, principalY).all(Double::isFinite) ||
        focalX <= 0.0 || focalY <= 0.0 ||
        principalX <= 0.0 || principalX >= output.width ||
        principalY <= 0.0 || principalY >= output.height
    ) {
        return CameraCalibrationScaleResult.Rejected(CameraCalibrationRejection.INVALID_PRINCIPAL_POINT)
    }
    return CameraCalibrationScaleResult.Accepted(
        ScaledCameraCalibration(
            focalXPixels = focalX,
            focalYPixels = focalY,
            principalXPixels = principalX,
            principalYPixels = principalY,
            distortionCoefficients = metadata.distortionCoefficients.supportedCamera2Distortion(),
            dimensions = output,
            provenance = source.provenance,
        ),
    )
}

internal fun Camera2CalibrationMetadata.toProtocolCameraIntrinsics(
    output: PixelDimensions,
    correlatedCaptureCrop: CameraCalibrationCrop? = null,
    correlatedFocalLengthMillimeters: Double? = null,
): CameraIntrinsics? {
    val scaled = (scaleCameraCalibration(
        this,
        output,
        correlatedCaptureCrop,
        correlatedFocalLengthMillimeters,
    ) as? CameraCalibrationScaleResult.Accepted)?.calibration ?: return null
    val builder = CameraIntrinsics.newBuilder()
        .setFocalXPixels(scaled.focalXPixels)
        .setFocalYPixels(scaled.focalYPixels)
        .setPrincipalXPixels(scaled.principalXPixels)
        .setPrincipalYPixels(scaled.principalYPixels)
        .addAllDistortionCoefficients(scaled.distortionCoefficients)
        .setCalibratedWidth(scaled.dimensions.width)
        .setCalibratedHeight(scaled.dimensions.height)
        .setProvenance(scaled.provenance)
    resolveHeadFromCameraExtrinsic(pose)?.let(builder::setHeadFromCameraExtrinsic)
    // DERIVED has no statistically measured residual distribution yet. Leave
    // the stddev message absent instead of encoding a heuristic percentage.
    return builder.build()
}

internal fun transformCameraIntrinsicsForSquareOutput(
    source: CameraIntrinsics,
    transform: SquareAspectFillTransform,
): CameraIntrinsics {
    require(
        source.calibratedWidth == transform.sourceWidth &&
            source.calibratedHeight == transform.sourceHeight,
    ) { "camera intrinsics dimensions must match the captured image" }
    return source.toBuilder()
        .setFocalXPixels(source.focalXPixels * transform.scaleX)
        .setFocalYPixels(source.focalYPixels * transform.scaleY)
        .setPrincipalXPixels(source.principalXPixels * transform.scaleX - transform.cropLeft)
        .setPrincipalYPixels(source.principalYPixels * transform.scaleY - transform.cropTop)
        .setCalibratedWidth(transform.outputSize)
        .setCalibratedHeight(transform.outputSize)
        .build()
}

/**
 * Converts Camera2's CAMERA <- ANDROID_SENSOR quaternion into HEAD <- CAMERA.
 *
 * The Rokid pose producer deliberately uses the Android sensor coordinate axes as its rigid
 * glasses/head proxy. Camera2 PRIMARY_CAMERA supplies a usable orientation in those axes, but its
 * zero translation is camera-relative and therefore remains unavailable. GYROSCOPE is the only
 * Camera2 origin for which this adapter may publish translation.
 */
internal fun resolveHeadFromCameraExtrinsic(pose: Camera2PoseMetadata?): CameraHeadExtrinsic? {
    pose ?: return null
    if (pose.reference == Camera2PoseReference.UNDEFINED || pose.reference == Camera2PoseReference.AUTOMOTIVE) {
        return null
    }
    val source = pose.cameraFromSensorQuaternionXyzw
    if (source.size != 4 || source.any { !it.isFinite() }) return null
    val norm = sqrt(source.sumOf { it * it })
    if (!norm.isFinite() || norm <= 0.0 || abs(norm - 1.0) > MAXIMUM_POSE_QUATERNION_NORM_ERROR) return null
    val x = source[0] / norm
    val y = source[1] / norm
    val z = source[2] / norm
    val w = source[3] / norm
    val translationAvailable = pose.reference == Camera2PoseReference.GYROSCOPE &&
        pose.cameraOpticalCenterInSensorMeters.size == 3 &&
        pose.cameraOpticalCenterInSensorMeters.all(Double::isFinite)
    val canonicalEvidence = buildString {
        append("camera2-head-extrinsic-v1|")
        append(pose.reference.name)
        append('|')
        source.forEach { append(java.lang.Double.toHexString(it)).append(',') }
        append('|')
        append(translationAvailable)
        if (translationAvailable) {
            append('|')
            pose.cameraOpticalCenterInSensorMeters.forEach {
                append(java.lang.Double.toHexString(it)).append(',')
            }
        }
    }.encodeToByteArray()
    return CameraHeadExtrinsic.newBuilder()
        // Inverse of CAMERA <- SENSOR; the current rigid HEAD proxy uses SENSOR axes.
        .setHeadFromCameraRotation(
            Quaternion.newBuilder().setX(-x).setY(-y).setZ(-z).setW(w),
        )
        .setTranslationAvailable(translationAvailable)
        .setProvenance(CameraExtrinsicProvenance.CAMERA_EXTRINSIC_PROVENANCE_CAMERA2_SENSOR_COORDINATES)
        .setVerificationSha256(ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(canonicalEvidence)))
        .apply {
            if (translationAvailable) {
                setHeadFromCameraTranslationMeters(
                    Vector3.newBuilder()
                        .setX(pose.cameraOpticalCenterInSensorMeters[0])
                        .setY(pose.cameraOpticalCenterInSensorMeters[1])
                        .setZ(pose.cameraOpticalCenterInSensorMeters[2]),
                )
            }
        }
        .build()
}

/**
 * Evidence gate for the physically observed Rokid metadata. This deliberately
 * keys on the complete metadata shape, not a camera ID or product name.
 */
internal fun matchesRokidCamera2PhysicalDerivationFingerprint(
    intrinsicCalibration: List<Double>,
    distortionCoefficients: List<Double>?,
    coordinateSpace: CameraCalibrationCoordinateSpace,
    coordinateSpaceLeftInPixelArray: Double,
    coordinateSpaceTopInPixelArray: Double,
    physical: CameraPhysicalIntrinsicsMetadata,
    sensorOrientationDegrees: Int?,
    centerOnlyCropping: Boolean,
): Boolean {
    if (sensorOrientationDegrees != 270 || !centerOnlyCropping ||
        physical.evidence != CameraPhysicalIntrinsicsEvidence.ROKID_CAMERA2_METADATA_FINGERPRINT ||
        intrinsicCalibration.size != CAMERA2_INTRINSIC_PARAMETER_COUNT ||
        intrinsicCalibration.any { !it.isFinite() } ||
        distortionCoefficients == null ||
        distortionCoefficients.size != CAMERA2_DISTORTION_PARAMETER_COUNT ||
        distortionCoefficients.any { !it.isFinite() || it != 0.0 } ||
        intrinsicCalibration[2] != 0.0 || intrinsicCalibration[3] != 0.0 ||
        intrinsicCalibration[4] != 0.0 ||
        coordinateSpace != CameraCalibrationCoordinateSpace(4_032.0, 3_024.0) ||
        coordinateSpaceLeftInPixelArray != 0.0 || coordinateSpaceTopInPixelArray != 0.0 ||
        physical.pixelArrayWidth != 4_032.0 || physical.pixelArrayHeight != 3_024.0 ||
        abs(physical.sensorPhysicalWidthMillimeters - 4.032) > PHYSICAL_SIZE_TOLERANCE_MM ||
        abs(physical.sensorPhysicalHeightMillimeters - 3.024) > PHYSICAL_SIZE_TOLERANCE_MM ||
        abs(physical.focalLengthMillimeters - 1.9) > FOCAL_LENGTH_TOLERANCE_MM
    ) return false
    val derivedFocalX = physical.focalLengthMillimeters * physical.pixelArrayWidth /
        physical.sensorPhysicalWidthMillimeters
    val derivedFocalY = physical.focalLengthMillimeters * physical.pixelArrayHeight /
        physical.sensorPhysicalHeightMillimeters
    return abs(intrinsicCalibration[0] - derivedFocalX) <= INTRINSIC_FOCAL_TOLERANCE_PIXELS &&
        abs(intrinsicCalibration[1] - derivedFocalY) <= INTRINSIC_FOCAL_TOLERANCE_PIXELS
}

private data class FactoryIntrinsics(
    val focalX: Double,
    val focalY: Double,
    val principalX: Double,
    val principalY: Double,
)

private data class IntrinsicsSource(
    val focalX: Double,
    val focalY: Double,
    val principalX: Double,
    val principalY: Double,
    val provenance: CameraIntrinsicsProvenance,
)

private fun derivePhysicalIntrinsics(
    physical: CameraPhysicalIntrinsicsMetadata,
    coordinates: CameraCalibrationCoordinateSpace,
    coordinateSpaceLeftInPixelArray: Double,
    coordinateSpaceTopInPixelArray: Double,
    correlatedFocalLengthMillimeters: Double?,
): IntrinsicsSource? {
    val focalLength = correlatedFocalLengthMillimeters ?: physical.focalLengthMillimeters
    val values = listOf(
        focalLength,
        physical.sensorPhysicalWidthMillimeters,
        physical.sensorPhysicalHeightMillimeters,
        physical.pixelArrayWidth,
        physical.pixelArrayHeight,
        coordinateSpaceLeftInPixelArray,
        coordinateSpaceTopInPixelArray,
    )
    if (values.any { !it.isFinite() } ||
        physical.evidence != CameraPhysicalIntrinsicsEvidence.ROKID_CAMERA2_METADATA_FINGERPRINT ||
        focalLength <= 0.0 || physical.sensorPhysicalWidthMillimeters <= 0.0 ||
        physical.sensorPhysicalHeightMillimeters <= 0.0 ||
        physical.pixelArrayWidth <= 0.0 || physical.pixelArrayHeight <= 0.0 ||
        coordinateSpaceLeftInPixelArray < 0.0 ||
        coordinateSpaceTopInPixelArray < 0.0 ||
        coordinateSpaceLeftInPixelArray + coordinates.width > physical.pixelArrayWidth ||
        coordinateSpaceTopInPixelArray + coordinates.height > physical.pixelArrayHeight
    ) return null
    return IntrinsicsSource(
        focalX = focalLength * physical.pixelArrayWidth / physical.sensorPhysicalWidthMillimeters,
        focalY = focalLength * physical.pixelArrayHeight / physical.sensorPhysicalHeightMillimeters,
        principalX = physical.pixelArrayWidth / 2.0 - coordinateSpaceLeftInPixelArray,
        principalY = physical.pixelArrayHeight / 2.0 - coordinateSpaceTopInPixelArray,
        provenance = CameraIntrinsicsProvenance.CAMERA_INTRINSICS_PROVENANCE_DERIVED,
    )
}

private fun CameraCalibrationCrop.isValidInside(coordinates: CameraCalibrationCoordinateSpace): Boolean =
    listOf(left, top, width, height).all(Double::isFinite) &&
        left >= 0.0 && top >= 0.0 && width > 0.0 && height > 0.0 &&
        left + width <= coordinates.width && top + height <= coordinates.height

private fun CameraCalibrationCrop.centerCropTo(output: PixelDimensions): CameraCalibrationCrop {
    val outputAspect = output.width.toDouble() / output.height
    val cropAspect = width / height
    return if (cropAspect > outputAspect) {
        val croppedWidth = height * outputAspect
        copy(left = left + (width - croppedWidth) / 2.0, width = croppedWidth)
    } else {
        val croppedHeight = width / outputAspect
        copy(top = top + (height - croppedHeight) / 2.0, height = croppedHeight)
    }
}

private fun CameraCalibrationCrop.strictlyContains(x: Double, y: Double): Boolean =
    x > left && x < left + width && y > top && y < top + height

private fun List<Double>?.supportedCamera2Distortion(): List<Double> =
    this?.takeIf {
        it.size == CAMERA2_DISTORTION_PARAMETER_COUNT && it.all(Double::isFinite)
    }?.toList().orEmpty()

private const val CAMERA2_INTRINSIC_PARAMETER_COUNT = 5
private const val CAMERA2_DISTORTION_PARAMETER_COUNT = 5
private const val PHYSICAL_SIZE_TOLERANCE_MM = 0.001
private const val FOCAL_LENGTH_TOLERANCE_MM = 0.001
private const val INTRINSIC_FOCAL_TOLERANCE_PIXELS = 5.0
private const val MAXIMUM_POSE_QUATERNION_NORM_ERROR = 0.02
