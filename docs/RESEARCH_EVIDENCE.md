<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Research evidence ledger

Access date for every web source below: **2026-08-22**. HTTP availability and
the cited content were checked during implementation. A reachable page is not
proof of perceptual effectiveness, licensing permission, or device support.

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
| [Depth Anything V2 Hypersim metric Small](https://huggingface.co/depth-anything/Depth-Anything-V2-Metric-Hypersim-Small) | Official indoor checkpoint revision `3bc65d4e14a6786a61acec16453c50e12bf5f338`; card declares Apache-2.0 | Pin revision/checksum, use the 20 m metric head, and retain uncertainty |
| [Depth Anything V2 VKITTI metric Small](https://huggingface.co/depth-anything/Depth-Anything-V2-Metric-VKITTI-Small) | Official outdoor checkpoint revision `c725b8589bdf6ab04072cab74c0467830db80d6d`; card declares Apache-2.0 | Pin revision/checksum, use the 80 m metric head, and retain uncertainty |
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
  QNN HTP V79 with six HVX threads. This is backend/model compatibility
  evidence, not proof that the Android APK has integrated the proprietary
  runtime or that perception is accurate in use.
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
