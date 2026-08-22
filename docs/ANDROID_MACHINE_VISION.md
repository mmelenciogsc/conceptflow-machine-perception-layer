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
   requires a checksum for every present model, and requires the exact baked
   vocabulary fingerprint for segmentation. Nothing is downloaded implicitly
   or packaged in the APK.
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
copied into this public repository.

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
bound: full camera-to-cue latency is unmeasured.

The selected profile is carried unchanged into depth inference. The returned
profile ID must match it exactly, the routing environment must match the
environment classifier, and calibration must resolve for that exact profile
and the current camera-intrinsics fingerprint. Missing intrinsics, calibration,
or the selected artifact causes a fail-closed result; another installed tier is
not silently substituted. A track without correlated depth samples produces no
metric track. For masks with geometry, the depth-stage mask fingerprint must
match the instance-segmentation fingerprint for the same track and image.

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

## Observed keyframes and pose propagation

The temporal layer does not synthesize visual observations. A first observed
frame may seed anchors; later observed frames are admitted at the relaxed 3 FPS
cadence or at the bounded 5 FPS cadence when meaningful motion or uncertainty
crosses the configured threshold. Non-monotonic frames are rejected. This
host-side observed-keyframe policy is distinct from the glasses Camera2 source
cadence, even though both currently use the same 3/5 FPS ceilings.

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

## Local synthetic diagnostic

Build and install the APK, then activate **Run synthetic Machine Vision
diagnostic (V)**. It executes fixed-vocabulary validation, indoor-profile
selection, mask-depth association, and two-anchor metric calibration on
deterministic test data. Its synthetic door is explicitly marked
`CONFIRMED_STATIC_WORLD`; production observations default to `UNKNOWN`. The
accessible result explicitly says it is not live perception.

QAIRT 2.48.40 physically loaded and executed the generated FP16 model libraries
through QNN HTP V79 on the attached Poco F7 Ultra. That proves standalone graph
compatibility and device execution. A one-image numerical smoke test was also
performed against ONNX Runtime; it is not task accuracy, calibration,
end-to-end latency, sustained-thermal, or BVI-usability validation. The public
Android APK still contains no proprietary QNN runtime and does not yet dispatch
these models from its process.

The external preparation path is explicit and refuses to write generated
artifacts under the repository root. Run it from a separately governed Python
environment containing CPU PyTorch 2.8.0, torchvision 0.23.0, Ultralytics
8.4.90, ONNX 1.18, ONNX Runtime 1.22.1, onnxslim, and the dependencies required
by the pinned Depth Anything source. These optional AGPL/toolchain dependencies
are intentionally absent from the permissive repository lock file.

```bash
./scripts/android-model-prepare export \
  --yoloe-checkpoint /private/models/yoloe-26s-seg.pt \
  --depth-source /private/src/Depth-Anything-V2 \
  --depth-indoor-checkpoint /private/models/depth_anything_v2_metric_hypersim_vits.pth \
  --depth-outdoor-checkpoint /private/models/depth_anything_v2_metric_vkitti_vits.pth \
  --output-dir /private/build/mpl-onnx \
  --acknowledge-ultralytics-terms

./scripts/android-qnn-build \
  --qnn-sdk /private/qairt \
  --ndk /private/android-ndk \
  --onnx-dir /private/build/mpl-onnx \
  --calibration-dir /private/build/calibration-real \
  --output-dir /private/build/mpl-qnn \
  --precision fp16 \
  --acknowledge-ultralytics-terms
```

Once separately licensed model libraries have been generated, a debuggable
installation can be provisioned without putting weights in Git:

```bash
./scripts/android-model-install --serial "$POCO_SERIAL" \
  --yoloe /private/path/libyoloe_bvi40_fp16.so \
  --depth-indoor-392 /private/path/libdepth_indoor_392_fp16.so \
  --depth-outdoor-392 /private/path/libdepth_outdoor_392_fp16.so \
  --depth-indoor-336 /private/path/libdepth_indoor_336_fp16.so \
  --depth-outdoor-336 /private/path/libdepth_outdoor_336_fp16.so \
  --depth-indoor-518 /private/path/libdepth_indoor_fp16.so \
  --depth-outdoor-518 /private/path/libdepth_outdoor_fp16.so
```

The helper rejects anything that is not an ELF64 little-endian AArch64 shared
object, checks explicit size bounds, verifies every transfer by SHA-256, adds
the fixed-vocabulary fingerprint, and performs no network download. The YOLOE
and 392 pair are required; the 336 and 518 pairs are optional but must be
provisioned as complete indoor/outdoor pairs. Legacy `--depth-indoor` and
`--depth-outdoor` flags remain aliases for the optional 518 filenames during
migration. Artifact verification is separate from QNN backend initialization
and inference.
