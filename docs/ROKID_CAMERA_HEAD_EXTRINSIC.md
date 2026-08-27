<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Rokid camera-to-head extrinsic

## Sourced target measurement

The non-display Rokid AI Glasses Style target (`RG-glasses`, Android 12) exposes
the following static Camera2 metadata for camera `0` on firmware build
`1.23.009-20260725-150201`:

```text
android.lens.poseReference   = PRIMARY_CAMERA
android.lens.poseRotation    = [0, 0, 0, 1]  (x, y, z, w)
android.lens.poseTranslation = [0, 0, 0] m
android.sensor.orientation   = 270 degrees
```

The observation was repeated over ADB on 2026-08-27 with:

```bash
adb -s "$ROKID_SERIAL" shell dumpsys media.camera
```

Android defines `LENS_POSE_ROTATION` as the rotation from the Android sensor
coordinate system into a camera-aligned coordinate system. The Rokid pose
producer uses that same rigid Android sensor frame as its glasses/head proxy.
The published transform is therefore the inverse:

```text
R_HEAD_FROM_CAMERA = inverse(R_CAMERA_FROM_ANDROID_SENSOR)
                   = quaternion [0, 0, 0, 1]
```

This is a source-backed **rotation-only** extrinsic. It is not a measurement of
the wearer's anatomical eye position, gaze, or torso frame, and Android reports
no numerical rotation uncertainty. `SENSOR_ORIENTATION=270` is a display/raster
orientation and is not substituted for the pose quaternion.

## Translation boundary

`PRIMARY_CAMERA` defines `LENS_POSE_TRANSLATION` relative to the main camera's
optical centre. Android specifies that the sole/main camera reports zero in this
case. Consequently `[0,0,0]` is not a camera-to-gyroscope or camera-to-head
translation measurement. Rokid Node publishes `translation_available=false`
and Android Node refuses to interpret the zero vector as physical translation.

The current world-relative path is consequently:

```text
camera direction --R_HEAD_FROM_CAMERA--> head direction
                 --R_WORLD_FROM_HEAD--> world-relative direction
```

It may rotate timestamp-correlated camera vectors and retain their scalar
depth. It must not claim full six-degree-of-freedom point anchoring, compensate
for head translation, or infer a camera-to-eye offset. A future guided
hand-eye/rig calibration can replace the rotation and add translation only when
it supplies a new evidence digest and measured uncertainty.

## Runtime contract

- Rokid Node reads the three Camera2 pose keys with each camera's static
  characteristics, normalizes and validates the quaternion, inverts it, and
  publishes `CameraHeadExtrinsic` with Camera2 provenance.
- `UNDEFINED`, `AUTOMOTIVE`, malformed, or non-unit metadata fails closed.
- A `PRIMARY_CAMERA` record never publishes translation. `GYROSCOPE` is the
  only Camera2 reference accepted by this adapter for translation.
- The metadata evidence is bound to a deterministic SHA-256 fingerprint. A
  fingerprint change within a live session is rejected.
- Android Node correlates the camera timestamp with its bounded head-pose
  history and composes `WORLD <- HEAD <- CAMERA`. Pose samples outside the
  150 ms correlation window are not used.
- Provisional `DERIVED` intrinsics remain explicitly unquantified. They permit
  rotation-only temporal propagation but not calibrated angular-accuracy claims.

## Evidence sources

- [AOSP camera metadata definitions](https://android.googlesource.com/platform/system/media/+/refs/heads/main/camera/docs/metadata_definitions.xml), specifically `android.lens.poseRotation`, `poseTranslation`, and `poseReference` (accessed 2026-08-27).
- [Android CameraCharacteristics lens pose reference](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#LENS_POSE_REFERENCE) (accessed 2026-08-27).
- Physical target `dumpsys media.camera` observation above.

Unit tests cover identity and non-identity quaternion inversion, gyroscope-only
translation, malformed/undefined rejection, host digest/provenance validation,
head-pose composition, and stale-pose rejection. Physical runtime evidence is
recorded in [`VALIDATION.md`](../VALIDATION.md); unit tests alone do not prove
the vendor's factory alignment tolerance.
