<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Android Node Machine Vision sublayer

The application label is **Machine Perception Layer, Android Node**. Machine
Vision is one sublayer of that node; audio, interaction, orchestration, and
other future sublayers are not collapsed into this package.

## Implemented boundary

The current Kotlin implementation provides a deterministic, model-neutral
semantic/depth fusion path:

1. `BviClassCatalog` defines a closed 40-class vocabulary. Runtime prompts and
   prompt-free discovery are rejected by design.
2. `MachineVisionModelProfiles` requires one fixed-vocabulary YOLOE-26S
   segmentation artifact and the official Depth Anything V2 Metric Small
   Hypersim (indoor) and VKITTI (outdoor) artifacts.
3. `PrivateModelBundleVerifier` requires an artifact checksum for every model
   and the exact baked-vocabulary fingerprint for segmentation. Nothing is
   downloaded implicitly or packaged in the APK.
4. `DepthProfileSelector` changes indoor/outdoor profiles only after fresh,
   independent, repeated evidence and applies a minimum hold interval.
5. `KnownDimensionVectorTable` contains exactly two records for every class:
   `0.6096` m (approximately two feet) and `2.4384` m (approximately eight
   feet). Each record includes representative length, width, height, angular
   extents, uncertainty, and calibration weight.
6. `TwoAnchorMetricDepthCalibrator` robustly fits an affine mapping for either
   relative depth or inverse depth. It ignores unknown and dimension-unstable
   classes, flags extrapolation, and preserves uncertainty.
7. `MachineVisionPipeline` verifies frame correlation, result age, vocabulary
   fingerprint, profile identity, class membership, and mask-associated depth
   before producing metric semantic tracks.

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
device-name-driven. Ambiguous, stale, or single-signal evidence holds the
current profile. A switch requires three qualifying samples by default and is
held for at least ten seconds to prevent model thrashing.

The two-anchor calibration is intentionally fast: fitting is O(n) over a small
guided sample set and each estimate is O(1). It supports raw relative-depth and
inverse-depth outputs. Pinhole dimension estimates may contribute calibration
evidence for stable classes, but they are never promoted to sensor-grade
metric truth. Out-of-range estimates are marked extrapolated and receive an
uncertainty penalty.

## Local synthetic diagnostic

Build and install the APK, then activate **Run synthetic Machine Vision
diagnostic (V)**. It executes fixed-vocabulary validation, indoor-profile
selection, mask-depth association, and two-anchor metric calibration on
deterministic test data. The accessible result explicitly says it is not live
perception.

QAIRT 2.48.40 physically loaded and executed the three generated FP16 model
libraries through QNN HTP V79 on the attached Poco F7 Ultra. That proves graph
compatibility and device execution. A one-image numerical smoke test was also
performed against ONNX Runtime; it is not model-accuracy, calibration,
sustained-thermal, or BVI-usability validation. The public Android APK still
contains no proprietary QNN runtime and does not yet dispatch these models from
its process.

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
  --depth-indoor /private/path/libdepth_indoor_fp16.so \
  --depth-outdoor /private/path/libdepth_outdoor_fp16.so
```

The helper rejects anything that is not an ELF64 little-endian AArch64 shared
object, checks explicit size bounds, verifies every transfer by SHA-256, adds
the fixed-vocabulary fingerprint, and performs no network download. Artifact
verification is separate from QNN backend initialization and inference.
