<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Research evidence ledger

Access date for the main evidence table below: **2026-08-22**. Later focused
sections state their own access dates. HTTP availability and the cited content
were checked during implementation. A reachable page is not proof of
perceptual effectiveness, licensing permission, or device support.

| Evidence | Verified point | Engineering consequence |
| --- | --- | --- |
| [Unity `Collider.ClosestPoint`](https://docs.unity3d.com/6000.3/Documentation/ScriptReference/Collider.ClosestPoint.html) | Unity 6 surface-point query is available | Use collider surfaces instead of transform origins |
| [Unity `Physics.OverlapSphereNonAlloc`](https://docs.unity3d.com/6000.3/Documentation/ScriptReference/Physics.OverlapSphereNonAlloc.html) | Bounded nonallocating overlap API exists | Fixed-capacity lab broadphase |
| [FMOD Unity documentation](https://www.fmod.com/docs/2.03/unity/welcome.html) and [spatial-audio guide](https://www.fmod.com/docs/2.03/unity/user-guide.html#spatial-audio) | Installed documentation family matches FMOD 2.03 | Pin authoring validation to 2.03.14; isolate renderer |
| [Android haptics guidance](https://developer.android.com/develop/ui/views/haptics/haptics-apis) and [`Vibrator`](https://developer.android.com/reference/android/os/Vibrator) | Capability queries and primitive/predefined/fallback APIs differ by API level | Query capabilities; never assume actuator topology |
| [Ultralytics YOLOE](https://docs.ultralytics.com/models/yoloe/) | Current docs include `yoloe-26s-seg.pt` | Use exact identity only behind a model-neutral adapter |
| [Ultralytics LICENSE](https://raw.githubusercontent.com/ultralytics/ultralytics/main/LICENSE) | Inspected source declares AGPL-3.0 | Do not vendor into permissively licensed core |
| [Depth Anything V2 indoor metric Large](https://huggingface.co/depth-anything/Depth-Anything-V2-Metric-Indoor-Large-hf) | Hypersim-tuned metric checkpoint exists | Separate explicit indoor profile; preserve uncertainty |
| [Depth Anything V2 outdoor metric Large](https://huggingface.co/depth-anything/Depth-Anything-V2-Metric-Outdoor-Large-hf) | Virtual KITTI 2-tuned metric checkpoint exists | Separate explicit outdoor profile; no auto-selection |
| [ONNX Runtime QNN Execution Provider](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html) | QNN supports Android and HTP is the NPU backend; supported precision is model/device dependent | Target HTP only after runtime initialization; use the physically validated static FP16 baseline and fail closed on unsupported nodes |
| [ExecuTorch Qualcomm backend](https://docs.pytorch.org/executorch/stable/android-qualcomm.html) | Qualcomm AI Engine Direct delegates to Snapdragon accelerators and documents HTP as the default backend | Treat QNN/HTP as the Poco deployment target, not legacy HTA or device-name inference |
| [Depth Anything V2 metric implementation](https://github.com/DepthAnything/Depth-Anything-V2/blob/a561b849ebae10a6f5ef49e26c83cbbcd36c71bf/metric_depth/depth_anything_v2/dpt.py) | Pinned source multiplies the metric head's sigmoid output by configured `max_depth`; inspected 2026-08-23 | Treat exact pinned outputs as bounded native metre semantics, not generic relative depth |
| [Depth Anything V2 Hypersim metric Small](https://huggingface.co/depth-anything/Depth-Anything-V2-Metric-Hypersim-Small/tree/3bc65d4e14a6786a61acec16453c50e12bf5f338) | Official indoor checkpoint revision `3bc65d4e14a6786a61acec16453c50e12bf5f338`; card declares Apache-2.0; inspected 2026-08-23 | Pin revision/checksum and use the 20 m output contract; record model error as unquantified until representative validation |
| [Depth Anything V2 VKITTI metric Small](https://huggingface.co/depth-anything/Depth-Anything-V2-Metric-VKITTI-Small/tree/c725b8589bdf6ab04072cab74c0467830db80d6d) | Official outdoor checkpoint revision `c725b8589bdf6ab04072cab74c0467830db80d6d`; card declares Apache-2.0; inspected 2026-08-23 | Pin revision/checksum and use the 80 m output contract; record model error as unquantified until representative validation |
| [Qualcomm AI Hub Depth Anything V2](https://aihub.qualcomm.com/models/depth_anything_v2) and [release package](https://huggingface.co/qualcomm/Depth-Anything-V2) | Release 0.60.0 provides a 518×518 generic relative-depth Small wrapper and device-profiled ONNX/QNN assets; custom weights/shapes require a separately authenticated compilation path | Evaluate externally without committing artifacts; do not substitute its relative output for the metric indoor/outdoor heads without guided calibration |
| [Rokid AI Glasses Style](https://global.rokid.com/products/rokid-ai-glasses-style) | Product is explicitly non-display | No visual wearer UI; direct Android/ADB path stays canonical |
| [Camera2 capture sessions and requests](https://developer.android.com/media/camera/camera2/capture-sessions-requests) | A configured session submits capture requests to target surfaces | Keep the JPEG `ImageReader` bounded and schedule capture requests independently of inference |
| [`Bitmap.createScaledBitmap`](https://developer.android.com/reference/android/graphics/Bitmap#createScaledBitmap(android.graphics.Bitmap,int,int,boolean)) | Android exposes an explicit width/height scaling operation | Compute one aspect-fit scale first; never force a source into 16:9 |
| [Android motion sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion) | Game rotation excludes geomagnetic north; rotation vectors and gyroscope/linear acceleration have distinct semantics | Prefer game rotation for relative head orientation and carry angular velocity/linear acceleration separately |
| [`SensorManager.registerListener`](https://developer.android.com/reference/android/hardware/SensorManager#registerListener(android.hardware.SensorEventListener,android.hardware.Sensor,int,int)) | Sampling period and report latency are requested parameters rather than guaranteed delivery | Request unbatched 10 ms samples and measure observed rate/gaps |
| [Optimize location for battery](https://developer.android.com/develop/sensors-and-location/location/battery) | Update frequency, accuracy, latency, and duration materially affect power | Use a bounded foreground GNSS burst rather than continuous location updates |
| [`GnssStatus`](https://developer.android.com/reference/android/location/GnssStatus) | Android exposes satellite count, carrier-to-noise density, and used-in-fix state | Derive bounded reception-quality evidence without retaining coordinates |
| [`Location.getElapsedRealtimeNanos`](https://developer.android.com/reference/android/location/Location#getElapsedRealtimeNanos()) | Location fixes carry monotonic elapsed-realtime timestamps | Compute freshness in the host monotonic domain rather than wall-clock time |
| [Android location permissions](https://developer.android.com/develop/sensors-and-location/location/permissions) | Precise location is separately user-controlled and background access is distinct | Request foreground fine location only on explicit Automatic-mode activation; declare no background permission |
| [Android Wi-Fi scanning restrictions](https://developer.android.com/develop/connectivity/wifi/wifi-scan) | Wi-Fi scans are permission- and throttling-sensitive | Exclude SSID/BSSID and active Wi-Fi scanning from the default classifier |
| [Human navigation for visually impaired people: systematic review](https://pmc.ncbi.nlm.nih.gov/articles/PMC11991376/) | 2025 review describes heterogeneous assistive navigation technologies and evaluation limits | Treat audio/haptics as supplemental; evaluate with users |
| [Visual-to-auditory sensory substitution learning](https://pmc.ncbi.nlm.nih.gov/articles/PMC12783664/) | 2025 study reports learned mappings and flexibility rather than universal intuitiveness | Keep vocabulary small, trainable, and configurable |

## Rokid camera geometry evidence

Sources in this section were accessed **2026-08-23**.

| Evidence | Verified point | Engineering consequence |
| --- | --- | --- |
| [Rokid AI Glasses Style product specifications](https://global.rokid.com/products/rokid-ai-glasses-style) | Rokid lists a 12 MP Sony IMX681 camera, 3024×4032 image acquisition, 109° field of view, and f/2.25 aperture. The page does not state the FOV axis, focal length, optical center, distortion coefficients, or calibration accuracy. | Use this as product identity and nominal optics evidence only. The 109° claim cannot define a camera matrix or validate Camera2 calibration. |
| [Official Rokid Glasses SDK demo](https://github.com/RokidSuuport/glass3_sdk_demo/tree/16380658cbf265af069895648119106bab3e5b04) | The complete public tree at commit `16380658cbf265af069895648119106bab3e5b04` was searched for camera calibration and intrinsic-matrix material on 2026-08-23. Its camera-sharing examples do not publish a Style factory intrinsic matrix, distortion residuals, or camera-to-head extrinsic. This scoped negative result does not prove that unpublished vendor calibration is unavailable. | Do not invent a Rokid SDK calibration API or treat a camera-sharing sample as calibration evidence. Use standard Camera2 metadata from the physical target, retain provenance, and require empirical target calibration for a `CALIBRATED` claim. |
| [Android `LENS_INTRINSIC_CALIBRATION`](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#LENS_INTRINSIC_CALIBRATION), [`LENS_DISTORTION`](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#LENS_DISTORTION), and [`SENSOR_INFO_PHYSICAL_SIZE`](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#SENSOR_INFO_PHYSICAL_SIZE) | Android defines the intrinsic vector as `[fx, fy, cx, cy, s]` in pre-correction active-array pixels, defines distortion as a five-coefficient Brown-Conrady mapping, and defines physical size over the full pixel array. Both calibration keys are optional. | Interpret values literally in their declared coordinate frame. Zero distortion is an identity model, not evidence that real optics are distortion-free; `(cx, cy)=(0,0)` is not documented as an unknown-value sentinel. |
| [Android `SCALER_CROP_REGION`](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#SCALER_CROP_REGION) and [`CONTROL_ZOOM_RATIO`](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_ZOOM_RATIO) | Each non-RAW stream is additionally center-cropped to its aspect ratio and scaled. The HAL may adjust the crop, reports the final crop in the capture result, and treats crop coordinates as post-zoom when zoom-ratio control is used. | Derive output intrinsics from the capture result, not static characteristics alone. Pin zoom to 1.0 and request the full array for the simple mapping, or model the reported zoom and crop explicitly. |
| [Android `DISTORTION_CORRECTION_MODE`](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#DISTORTION_CORRECTION_MODE), [`SENSOR_INFO_ACTIVE_ARRAY_SIZE`](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE), and [`SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE`](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE) | With correction off, crop metadata uses the pre-correction array; with correction on, it uses the post-correction active array. Processed JPEG/YUV buffers receive the selected correction. | Prefer a supported OFF mode when forwarding the vendor distortion model. FAST/HIGH_QUALITY output needs the post-correction mapping and cannot be represented by merely scaling the pre-correction matrix. |
| [Android `SENSOR_ORIENTATION`](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#SENSOR_ORIENTATION) and [`SCALER_ROTATE_AND_CROP`](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#SCALER_ROTATE_AND_CROP) | Sensor orientation is the rotation needed for upright display; it does not itself rotate the camera matrix. Rotate-and-crop occurs after digital zoom and before per-stream cropping, and the capture result reports the concrete applied mode. | Keep processed frames in sensor orientation or rotate their intrinsics with their pixels. Pin rotate-and-crop to NONE where supported, or consume its capture result before publishing intrinsics. |
| [OpenCV camera-calibration tutorial](https://docs.opencv.org/4.x/dc/dbb/tutorial_py_calibration.html) | Intrinsics and distortion are recovered from corresponding known 3D target points and observed 2D image points across multiple views. | Calibrate the actual 1920×1080 capture path and retain residual/error evidence before claiming calibrated intrinsics or quantified spatial/angular accuracy. |

The Android reference semantics above were cross-checked against the current
[AOSP camera metadata definitions](https://android.googlesource.com/platform/system/media/+/refs/heads/main/camera/docs/metadata_definitions.xml).

The sanitized target observation was equal 4032×3024 pixel, active, and
pre-correction active arrays; 4.032×3.024 mm physical size; one 1.9 mm focal
length; sensor orientation 270°; intrinsic vector approximately
`[1900, 1900, 0, 0, 0]`; and five zero distortion coefficients. It also
reported CENTER_ONLY cropping, rotate-and-crop availability limited to NONE,
and OIS availability limited to OFF. Those capabilities rule out freeform
off-center crop, camera-framework rotate-and-crop, and OIS on this target, but
do not by themselves prove the requested/result crop, zoom ratio, distortion
mode, or video-stabilization mode for a capture. Android defines physical size
over the full pixel array, so the observed numbers imply a nominal 1.0 µm
pitch and `1.9 mm / 0.001 mm = 1900 px` focal scale. This agreement checks
units only; it does not recover the optical center or establish calibration
quality. It implies nominal full-array FOVs of 93.39° horizontal, 77.02°
vertical, and 105.97° diagonal. Rokid's public 109° value does not name an axis
or capture mode, so it cannot resolve the difference. The public 3024×4032
ordering and Camera2's 4032×3024 ordering are consistent with a rotated raster,
but that does not establish the camera-to-head pose.

For a **conditional** full-width, centered 16:9 mapping, use the observed
4032×3024 pre-correction array and additionally assume distortion correction is
OFF, zoom is 1.0, the final crop result is the full array, and video
stabilization introduces no extra crop. The observed rotate-and-crop mode is
already limited to NONE. Camera2 then uses source rectangle
`(left=0, top=378, width=4032, height=2268)` and uniform scale
`1920/4032 = 1080/2268 = 10/21`. Assuming—not measuring—the optical center at
`(2016, 1512)` gives the provisional output matrix parameters:

```text
[fx, fy, cx, cy, s] = [904.76, 904.76, 960, 540, 0] pixels
nominal FOV          = 93.39° horizontal, 61.66° vertical, 101.20° diagonal
```

The literal observed principal point instead maps to `(0, -180)` after that
crop. Android does not define zero as "use the image center," so replacing it
with `(960, 540)` is a device-specific assumption rather than recovery of
factory calibration. Treating the literal vector as calibrated must fail
closed. If the provisional centered model is used before empirical camera
calibration, mark it `DERIVED`, not `CALIBRATED`; it may support
uncertainty-marked camera-frame ray/vector directions, but not a claim of
calibrated spatial or angular accuracy. The metadata provide no statistical
error sample, so percentage envelopes are engineering bounds rather than
measured standard deviations.

The official Depth Anything V2 metric checkpoints linked in the main evidence
table infer scalar camera-frame metric depth from image input without consuming
camera intrinsics. Missing calibrated intrinsics therefore does not disable
their native scalar metric-depth output. It does prevent calibrated projection
of a pixel and depth into a 3D camera-frame ray/vector. Metric model error on
this Rokid capture path remains unquantified, regardless of whether a
provisional `DERIVED` matrix is attached.

Before claiming calibrated intrinsics or quantified spatial/angular accuracy,
the requested geometry and matching `TotalCaptureResult` must agree. The
current capture path requests, wherever the advertised request keys support
them, the full crop, sole focal length, unit zoom, rotate-and-crop NONE,
distortion correction OFF, video stabilization OFF, and OIS OFF. It
timestamp-correlates every available matching result field to the image; a
contradictory correlated result suppresses that frame's intrinsics.
When no correlated result is available, only the narrow target-fingerprint
path retains the static centered derivation, marked `DERIVED` with no numeric
uncertainty claim. This behavior is implemented and deterministically tested.
The final physical runs reported
`profile_bound_native_metric_derived_intrinsics_present`, confirming that the
derived-intrinsics path reached the host; their aggregate evidence does not
turn the provisional matrix into measured factory calibration or quantify its
error.

Empirically calibrate the emitted 1920×1080 JPEG path with a checkerboard or
ChArUco target and bind the measured matrix, distortion, resolution, modes, and
residual uncertainty together before claiming calibrated 3D spatial accuracy.
Separately measure the physical `HEAD <- CAMERA` rotation and translation.
Native scalar metric depth remains available with unquantified model error;
that is narrower than a calibrated pixel-to-ray or spatial/angular claim.

## Local evidence

- Unity 6000.3.22f1 compiled and ran EditMode and PlayMode tests in batch mode.
- FMOD Studio 2.03.14 executed the authoring validator and built Desktop/Mobile
  banks from procedural tones. This proves project structure, not localization.
- Android API compilation/unit tests exercised capability planning; no haptic
  waveform was physically evaluated in this pass.
- Both local NVIDIA GPUs were visible after power cycle, but no model inference
  or CUDA performance measurement is inferred from enumeration.
- The attached Poco identified as model `24122RKC7G`, Qualcomm `SM8750`, arm64,
  Android API 36, with the vendor NPU property enabled. QAIRT 2.48.40 then
  physically executed fixed-shape YOLOE and both Depth Anything graphs through
  QNN HTP V79 with six HVX threads. Later private provisioning physically
  exercised those graphs through the Android app-process live path. This is
  backend/runtime integration evidence, not proof that perception is accurate
  in use.
- Two consecutive no-reinstall Rokid-to-Poco runs on 2026-08-23 used QNN HTP,
  kept both app processes alive, recorded no crashes or interruptions, and
  completed authenticated close with no failure lane. Indoor Hypersim 392
  received 80 frames, succeeded on 61/61 inference attempts, produced 9,373,504
  positive depth outputs, accepted 1,400/1,400 poses, and measured 941.7 ms p95
  end-to-end latency with 5.3 ms p95 clock uncertainty. Outdoor VKITTI 392
  received 81 frames, succeeded on 61/62 inference attempts, produced 9,373,504
  positive depth outputs, accepted 1,438/1,438 poses, and measured 1,183.5 ms
  p95 end-to-end latency with 5.8 ms p95 clock uncertainty. Full stage timing
  is retained in `VALIDATION.md`; no identifier, private address, or private
  artifact hash is recorded here.
- The same Poco reported location enabled with GPS, network, and passive
  providers and a light sensor; no barometer feature was declared. These are
  capability observations only. The environment router uses optional GNSS
  reception quality, not stored coordinates, and does not use the light sensor
  as scene truth.
- Static 336×336 and 392×392 metric Small variants required baking the DINO
  positional interpolation before QNN conversion. Both then executed on HTP
  V79. Post-first medians were 84.51/86.19 ms (indoor/outdoor 336) and
  108.18/106.36 ms (392), while 518 remains the reference pending
  representative accuracy testing. See `ANDROID_DEPTH_VARIANTS.md`.
- The Qualcomm AI Hub 0.60.0 generic relative-depth float DLC executed through
  HTP after runtime composition at 134.58 ms median on this QAIRT 2.48.40
  setup, but emitted a cache-selection mismatch from its QAIRT 2.45 build.
  W8A16 and a raw local ONNX reconversion were much slower. These results do
  not reproduce or invalidate Qualcomm's separately published measurements.
- Live Rokid characteristics exposed exact 1920×1080 JPEG and game-rotation,
  gyroscope, and linear-acceleration rates compatible with a 10 ms request. A
  bounded direct-app run observed 98.7 orientation samples/s. A preview-only
  Camera2 3A warm-up followed by a JPEG-only session corrected the vendor
  HAL's minimum-exposure still-capture behavior; a lit-scene run analyzed 28
  exact-size frames and emitted 18 after gating. Sustained throughput and
  perceptual threshold calibration remain empirical.

## Unresolved hypotheses

Reliable elevation/front-back discrimination on the Rokid open-ear output,
comfort across spectral profiles, minimum useful spatial update rate, monocular
metric accuracy on the glasses camera, torso-heading estimation, and real-world
auditory-load thresholds all require controlled target-user/device evaluation.
