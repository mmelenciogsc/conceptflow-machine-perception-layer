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
| [ONNX Runtime QNN Execution Provider](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html) | QNN supports Android; HTP is the NPU backend and quantized fixed-shape models are the intended path | Target HTP only after runtime initialization; prepare static quantized artifacts and fail closed on unsupported nodes |
| [ExecuTorch Qualcomm backend](https://docs.pytorch.org/executorch/stable/android-qualcomm.html) | Qualcomm AI Engine Direct delegates to Snapdragon accelerators and documents HTP as the default backend | Treat QNN/HTP as the Poco deployment target, not legacy HTA or device-name inference |
| [Depth Anything V2 indoor metric Small](https://huggingface.co/depth-anything/Depth-Anything-V2-Metric-Indoor-Small-hf) | A smaller indoor-tuned metric profile exists; checked API license metadata was undeclared | Keep external and blocked from distribution pending explicit terms |
| [Depth Anything V2 outdoor metric Small](https://huggingface.co/depth-anything/Depth-Anything-V2-Metric-Outdoor-Small-hf) | A smaller outdoor-tuned metric profile exists; checked card reports Apache-2.0 | Keep separately checksummed and governed for Android conversion |
| [Rokid AI Glasses Style](https://global.rokid.com/products/rokid-ai-glasses-style) | Product is explicitly non-display | No visual wearer UI; direct Android/ADB path stays canonical |
| [Camera2 capture sessions and requests](https://developer.android.com/media/camera/camera2/capture-sessions-requests) | A configured session submits capture requests to target surfaces | Keep the JPEG `ImageReader` bounded and schedule capture requests independently of inference |
| [`Bitmap.createScaledBitmap`](https://developer.android.com/reference/android/graphics/Bitmap#createScaledBitmap(android.graphics.Bitmap,int,int,boolean)) | Android exposes an explicit width/height scaling operation | Compute one aspect-fit scale first; never force a source into 16:9 |
| [Android motion sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion) | Game rotation excludes geomagnetic north; rotation vectors and gyroscope/linear acceleration have distinct semantics | Prefer game rotation for relative head orientation and carry angular velocity/linear acceleration separately |
| [`SensorManager.registerListener`](https://developer.android.com/reference/android/hardware/SensorManager#registerListener(android.hardware.SensorEventListener,android.hardware.Sensor,int,int)) | Sampling period and report latency are requested parameters rather than guaranteed delivery | Request unbatched 10 ms samples and measure observed rate/gaps |
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
  Android API 36, with the vendor NPU property enabled. That supports HTP
  eligibility, not proof that this app initialized QNN or ran a model.
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
