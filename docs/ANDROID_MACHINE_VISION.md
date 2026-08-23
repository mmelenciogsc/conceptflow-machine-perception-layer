<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Android Node Machine Vision sublayer

The application label is **Machine Perception Layer, Android Node**. Machine
Vision is one sublayer of that node; audio, interaction, orchestration, and
other future sublayers are not collapsed into this package.

## Implemented boundary

The current Kotlin implementation provides a deterministic, model-neutral
semantic/depth fusion path:

1. `BviClassCatalog` defines a closed 40-class vocabulary. YOLOE-26S supplies
   instance-segmentation class semantics, confidence, track identity, and mask
   geometry only within that catalog. Runtime prompts and prompt-free discovery
   are rejected by design.
2. `MachineVisionModelProfiles` requires one fixed-vocabulary YOLOE-26S
   segmentation artifact plus the balanced 392×392 Depth Anything V2 Metric
   Small Hypersim (indoor) and VKITTI (outdoor) artifacts. The 336 low-power
   and legacy-named 518 reference pairs are optional slots.
3. `PrivateModelBundleVerifier` reports required and optional slots separately,
   requires a checksum for every present model, checks production artifacts
   against code-pinned trusted digests, and requires the exact baked vocabulary
   fingerprint for segmentation. Nothing is downloaded implicitly or packaged
   in the APK.
4. `EnvironmentAwareMachineVisionPipeline` runs fixed-vocabulary segmentation
   before depth, fuses timestamped visual and optional privacy-minimized GNSS
   quality evidence, and invokes only the exactly selected environment/tier
   graph.
   `DepthProfileSelector` changes profiles only after fresh, independent,
   repeated evidence and applies hold and expiry intervals.
5. `KnownDimensionVectorTable` contains exactly two records for every class:
   `0.6096` m (approximately two feet) and `2.4384` m (approximately eight
   feet). Each record includes representative length, width, height, angular
   extents, uncertainty, and calibration weight.
6. `TwoAnchorMetricDepthCalibrator` robustly fits an affine mapping for either
   relative depth or inverse depth. `BoundedMetricDepthCalibrationStore` keeps
   only explicitly bound calibrations, keyed by the complete depth-profile ID
   and SHA-256 camera-intrinsics fingerprint.
7. `MachineVisionPipeline` verifies frame correlation, result age, vocabulary
   fingerprint, exact profile identity, class membership, mask-associated depth,
   and camera geometry before producing metric semantic tracks.
8. `VisualKeyframeGate` admits observed frames at 3 FPS in the relaxed tier and
   at no more than 5 FPS when meaningful motion or uncertainty requests a
   keyframe. `TemporalMetricTrackStore` can propagate only already measured,
   short-lived anchors carrying explicit `CONFIRMED_STATIC_WORLD` evidence
   between those visual updates. Motion evidence defaults to `UNKNOWN`, which
   is not eligible for propagation.

At 80 records, an immutable process-local exact vector scan is lower overhead
than adding a database engine. The interface can later be backed by an ANN
store if the governed corpus grows, without changing record semantics.

## BVI vocabulary policy

The bounded list emphasizes people and mobility aids, door and room structure,
level changes and pedestrian infrastructure, concentrated obstacles, vehicles,
signals, and text-bearing regions. The list is not a declaration that every
instance has a fixed physical size. Variable or poorly constrained classes
have zero calibration weight. Immediate body-clearance geometry must not wait
for, or trust, semantic recognition.

The model export must bake `BviClassCatalog.prompts` and publish the resulting
SHA-256 vocabulary fingerprint beside the private artifact. The locally found
legacy ONNX export contains a different 330-class vocabulary and is therefore
not accepted by this verifier.

## Poco F7 Ultra acceleration

The inspected Poco reports Qualcomm `SM8750`, arm64, and an enabled vendor NPU
property. The intended accelerator is the QNN HTP backend. Legacy HTA is not
selected. `QualcommAcceleratorPlanner` reports HTP only after both the
project-owned QNN adapter and HTP backend initialize successfully; device-name
matching alone is insufficient. The present public APK contains neither a QNN
runtime nor proprietary Qualcomm binaries, so its truthful fallback is the
deterministic CPU/reference boundary.

QNN HTP deployment requires separately governed, static-shape artifacts. The
validated baseline is FP16: plain W8A8 damaged both mixed YOLO output semantics
and metric depth, while real-image-calibrated W8A16 did not materially improve
YOLO latency and remains experimental pending a representative BVI accuracy
suite. The local QAIRT/QNN installation is development tooling and is not
copied into this public repository. An opt-in JNI implementation now exists;
see [Android private QNN runtime adapter](ANDROID_QNN_PRIVATE_RUNTIME.md) for its
external build, private provisioning, tensor contracts, and validation limits.

## Depth profiles and calibration

Indoor and outdoor selection is evidence-driven rather than GPS- or
device-name-driven. The fixed-vocabulary instance-segmentation class result
supplies the primary camera evidence before depth executes. Optional GNSS
reception quality can reinforce outdoor evidence, but no coordinates are
retained, GNSS alone cannot select a profile, and weak reception never proves
an indoor setting. Ambiguous, stale, duplicate, or out-of-order evidence cannot
advance selection. A switch requires three qualifying samples by default, is
held for at least ten seconds to prevent model thrashing, and expires after 30
seconds without confirmation. Manual indoor/outdoor modes are immediate and
accessible.

Environment chooses the metric head; `DepthServiceTier` chooses the static
resolution. Balanced runtime uses 392×392. Thermal/battery pressure or a
configured budget below the balanced standalone median selects 336×336 when
that standalone median fits. Reference, calibration, and sparse ambiguity
requests select 518×518. Once a profile is selected, an unavailable artifact
fails closed rather than falling back to a different weight or resolution.
The routing budget uses measured standalone HTP client time only as a lower
bound. The final bounded direct runs below add full-path latency observations,
but not a representative workload or sustained-performance distribution.

The selected profile is carried unchanged into depth inference. The returned
profile ID must match it exactly and the routing environment must match the
environment classifier. The pinned official Hypersim and VKITTI metric heads
return metres directly: upstream applies a sigmoid and multiplies by `20 m`
indoors or `80 m` outdoors. The host therefore uses a profile-bound identity
semantics descriptor for these exact official model revisions. This is not a
glasses-camera calibration and it assigns no unmeasured accuracy or per-sample
error bound. Non-finite, non-positive, or above-contract values fail closed.
The current host ray projector is explicitly pinhole-only: live protocol
intrinsics must carry exactly five finite zero Brown-Conrady coefficients.
Missing, malformed, or nonzero distortion is rejected rather than silently
ignored; a bounded inverse-distortion implementation would be required before
admitting nonzero coefficients.
Missing metric semantics or the selected artifact produces no metric track;
another installed tier is not silently substituted. Missing intrinsics still
allows scalar metric depth, but prevents ray projection and a camera vector. A
track without correlated depth samples produces no metric track. For masks with
geometry, the depth-stage mask fingerprint must match the instance-segmentation
fingerprint for the same track and image.

That fail-closed rule governs metric semantic tracks from
`EnvironmentAwareMachineVisionPipeline`. The bounded direct-live
`QnnLiveFrameExecutor` executes and validates YOLOE and the selected 392 depth
tensor when intrinsics are missing and reports
`UNCALIBRATED_INTRINSICS_MISSING`. The downstream live fusion may still emit a
scalar native-metric estimate with explicit pinned provenance and unquantified
model error. Accepted intrinsics add a camera-frame ray and vector while
retaining `CALIBRATED` versus `DERIVED` provenance. Provenance and any actually
reported parameter standard deviations participate in the calibration
fingerprint; an absent protobuf uncertainty message remains unreported rather
than becoming zero uncertainty. A separately verified HEAD←CAMERA orientation
extrinsic and a capture-correlated pose are required only for head/world temporal propagation;
their absence is exposed as an aggregate status reason without discarding the
current scalar or camera-frame metric track.

Derived intrinsics with no reported parameter uncertainty can still support a
current-frame camera vector, but they cannot seed head/world temporal anchors.
The fusion result reports
`CAMERA_METRIC_TRACKS_READY_PROPAGATION_INTRINSICS_UNQUANTIFIED` until measured
intrinsics or derived intrinsics with reported uncertainty are supplied.

See [Indoor/outdoor classification and depth routing](ENVIRONMENT_CLASSIFICATION.md)
for the exact stages, thresholds, uncertainty behavior, clock boundary, and
current validation status.

The two-anchor calibration is intentionally fast: fitting is O(n) over a small
guided sample set and each estimate is O(1). It supports raw relative-depth and
inverse-depth outputs, and `calibrateAuto` selects the monotonic direction from
the observed near/far anchors instead of assuming vendor output semantics.
The calibration registry is immutable and bounded to 16 entries by default
(configurable within a fixed limit); duplicate profile/intrinsics bindings are
rejected. Pinhole class-dimension estimates may be fused only as a same-image
prior for dimension-stable classes. A disagreeing prior is rejected, and even
an agreeing prior cannot reduce calibrated-depth uncertainty. Neither source is
promoted to sensor-grade truth. Extrapolated estimates receive an uncertainty
penalty.

Native-metric estimates carry pinned model provenance and an explicit
`UNQUANTIFIED_MODEL_ERROR` state. They may be emitted for the current frame but
cannot become temporal anchors while uncertainty is unquantified. A separately
validated guided calibration can replace the identity semantics only when it
matches both the exact depth profile and camera-intrinsics fingerprint.
Consequently a guided binding made with a measured matrix cannot silently match
the same numeric matrix when it arrives with derived provenance.
Known-dimension priors are fused only when the primary calibration has
quantified uncertainty, so a class-size prior cannot manufacture confidence for
an otherwise unvalidated monocular estimate.

## Observed keyframes and pose propagation

The temporal layer does not synthesize visual observations. A first observed
frame may seed anchors; later observed frames are admitted at the relaxed 3 FPS
cadence or at the bounded 5 FPS cadence when meaningful motion or uncertainty
crosses the configured threshold. Non-monotonic frames are rejected. This
host-side observed-keyframe policy is distinct from the glasses Camera2 source
cadence, even though both currently use the same 3/5 FPS ceilings. The
direct-live executor does not add `VisualKeyframeGate`; its input cadence is the
glasses-side gate, and host backpressure retains only the latest complete frame
awaiting inference.

High-rate pose updates may rotate only existing anchors that came from a
successful visual keyframe and explicit static-world eligibility. A class label
or mask geometry alone never establishes that eligibility; person/mobility-aid
and vehicle groups remain visual-only even if upstream evidence incorrectly
labels an instance static. Every dynamic or unknown-motion observation also
remains visual-only.
If a later keyframe reuses an anchor ID but reports dynamic or unknown motion,
the old anchor is removed. Translation is applied only when both the anchor pose
and current pose carry explicit VIO or external-tracking position evidence with
the same source and coordinate-frame origin. Otherwise propagation is
orientation-only. Confidence decays with age, uncertainty grows, stale poses
produce no snapshot, anchors expire by TTL or confidence/uncertainty limits,
and explicit occlusion removes an anchor. Pose or IMU updates never create a
new object, create depth, refresh a moving object, or reveal an object that was
not in a visual keyframe; IMU alone cannot observe external scene change.

The physically tested 336/392 variants, tier policy, and reproducible
standalone HTP benchmark are documented in
[Android depth-resolution experiments](ANDROID_DEPTH_VARIANTS.md).

## Direct live QNN test boundary

The Android Node has a debuggable-build-only listener for the direct
Rokid-to-Poco path. It receives camera and IMU over independent private-WLAN TLS
1.3 mutual-TLS lanes. Pairing exchanges only public certificates; private keys
remain non-exportable in Android Keystore. The negotiated live lease accepts
camera and IMU only, so microphone data cannot enter this path.

The default run stops after 30 seconds or 150 received frames. Camera input is
gated on the glasses at 3 FPS relaxed and up to 5 FPS after meaningful change;
nominal 100 Hz IMU input is deduplicated into bounded batches with at most 20 ms
batch delay and an absolute refresh at least once per second. The Poco keeps at
most the latest frame awaiting inference.

For each admitted frame, `QnnLiveFrameExecutor` performs bounded JPEG decode and
preprocessing, executes fixed-vocabulary YOLOE first, and then executes exactly
one indoor or outdoor balanced 392 graph selected by automatic camera/GNSS
evidence or an explicit manual override. It has no CPU, 336, 518, or alternate
environment fallback. Missing private artifacts, QNN initialization failure,
invalid shapes, non-finite values, and execution errors return sanitized typed
failure codes.

Live status is aggregate-only: received/replaced frame counts, IMU batch/sample
counts, inference counts, categorical profile/calibration state, p50/p95/p99
latency aggregates, and p95 clock uncertainty. It excludes image bytes,
detections, labels, raw IMU, addresses, certificates, and
endpoint/session/lease/frame identifiers.

The transport and QNN boundary are implemented and covered by local JVM, lint,
and build validation. On 2026-08-23 it also completed two consecutive physical
runs without an app reinstall. Both used QNN HTP, left both app processes alive,
recorded no crash or interruption, and completed authenticated close with no
failure lane. Indoor Hypersim 392 received 80 frames, succeeded on 61/61 inference
attempts, emitted 9,373,504 positive depth outputs, accepted 1,400/1,400 poses,
and measured p95 latencies of 941.7 ms end-to-end, 586.1 ms
capture-to-receive, 84.1 ms segmentation, 70.2 ms depth, and 287.5 ms executor,
with 5.3 ms p95 clock uncertainty. Outdoor VKITTI 392 received 81 frames,
succeeded on 61/62 inference attempts, emitted 9,373,504 positive depth outputs,
accepted 1,438/1,438 poses, and measured corresponding p95 values of 1,183.5,
591.0, 107.8, 74.4, 373.2, and 5.8 ms.

Both runs reported
`profile_bound_native_metric_derived_intrinsics_present` with reason
`CAMERA_METRIC_TRACKS_READY_PROPAGATION_INTRINSICS_UNQUANTIFIED`. The target's
narrow fingerprint supplied
`[fx, fy, cx, cy, s] ≈ [904.7619, 904.7619, 960, 540, 0]` at 1920×1080 with
`DERIVED` provenance and unquantified parameter uncertainty. Native scalar
metric output does not consume this matrix and remains available if intrinsics
are absent; the matrix adds pixel-to-ray/current camera-vector geometry. It is
not verified factory calibration. Representative metric-depth accuracy,
empirical camera calibration, calibrated spatial/angular accuracy,
long-duration thermal/energy behavior, forced reconnect, and BVI usability
remain physically unvalidated.

The first physical attempt exposed partial-record handling: a 200 ms socket
timeout could occur after consuming only part of a length-prefixed record, and
the prior retry lost that offset. The stateful reader now resumes the same
prefix/payload across timeouts. Authenticated close is bounded on a dedicated
worker so the Android main thread does not wait for network shutdown.

## Local synthetic diagnostic

Build and install the APK, then activate **Run synthetic Machine Vision
diagnostic (V)**. It executes fixed-vocabulary validation, indoor-profile
selection, mask-depth association, and two-anchor metric calibration on
deterministic test data. Its synthetic door is explicitly marked
`CONFIRMED_STATIC_WORLD`; production observations default to `UNKNOWN`. The
accessible result explicitly says it is not live perception.

QAIRT 2.48.40 physically loaded and executed the generated FP16 model libraries
through QNN HTP V79 on the attached Poco F7 Ultra. The earlier standalone trace
proved graph compatibility and device execution; the final direct runs above
add app-process linker/SELinux/DSP-skel and live-dispatch evidence. A one-image
numerical smoke test was also performed against ONNX Runtime; none of these is
representative task accuracy, empirical camera calibration, sustained-thermal,
or BVI-usability validation. The public Android APK still contains no
proprietary QNN runtime; the exercised runtime and models were privately
provisioned into the debuggable app boundary.

The external preparation path is explicit and refuses to write generated
artifacts under the repository root. Run it from a separately governed Python
environment containing CPU PyTorch 2.8.0, torchvision 0.23.0, Ultralytics
8.4.90, ONNX 1.18, ONNX Runtime 1.22.1, onnxslim, and the dependencies required
by the pinned Depth Anything source. These optional AGPL/toolchain dependencies
are intentionally absent from the permissive repository lock file.
In the examples below, the `*_DIR` variables must resolve to separately
governed locations outside this repository.

```bash
./scripts/android-model-prepare export \
  --yoloe-checkpoint "$MODEL_DIR/yoloe-26s-seg.pt" \
  --depth-source "$DEPTH_SOURCE_DIR" \
  --depth-indoor-checkpoint "$MODEL_DIR/depth-indoor.pth" \
  --depth-outdoor-checkpoint "$MODEL_DIR/depth-outdoor.pth" \
  --output-dir "$BUILD_DIR/mpl-onnx" \
  --acknowledge-ultralytics-terms

./scripts/android-qnn-build \
  --qnn-sdk "$QNN_SDK_DIR" \
  --ndk "$ANDROID_NDK_DIR" \
  --onnx-dir "$BUILD_DIR/mpl-onnx" \
  --calibration-dir "$CALIBRATION_DIR" \
  --output-dir "$BUILD_DIR/mpl-qnn" \
  --precision fp16 \
  --acknowledge-ultralytics-terms
```

Once separately licensed model libraries have been generated, a debuggable
installation can be provisioned without putting weights in Git:

```bash
./scripts/android-model-install --serial "$POCO_SERIAL" \
  --yoloe "$QNN_MODEL_DIR/libyoloe_bvi40_fp16.so" \
  --depth-indoor-392 "$QNN_MODEL_DIR/libdepth_indoor_392_fp16.so" \
  --depth-outdoor-392 "$QNN_MODEL_DIR/libdepth_outdoor_392_fp16.so" \
  --depth-indoor-336 "$QNN_MODEL_DIR/libdepth_indoor_336_fp16.so" \
  --depth-outdoor-336 "$QNN_MODEL_DIR/libdepth_outdoor_336_fp16.so" \
  --depth-indoor-518 "$QNN_MODEL_DIR/libdepth_indoor_fp16.so" \
  --depth-outdoor-518 "$QNN_MODEL_DIR/libdepth_outdoor_fp16.so"
```

The helper rejects anything that is not an ELF64 little-endian AArch64 shared
object, checks explicit size bounds, verifies every transfer by SHA-256, adds
the fixed-vocabulary fingerprint, and performs no network download. The YOLOE
and 392 pair are required; the 336 and 518 pairs are optional but must be
provisioned as complete indoor/outdoor pairs. Legacy `--depth-indoor` and
`--depth-outdoor` flags remain aliases for the optional 518 filenames during
migration. Artifact verification is separate from QNN backend initialization
and inference.
