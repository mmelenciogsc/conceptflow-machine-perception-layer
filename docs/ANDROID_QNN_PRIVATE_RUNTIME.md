<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Android private QNN runtime adapter

The Android host has an opt-in arm64 JNI adapter for three digest-pinned accepted
graphs: YOLOE BVI40 640×640, Depth Anything V2 Metric Hypersim 392×392, and
Depth Anything V2 Metric VKITTI 392×392. Normal builds remain vendor-free. The
adapter is compiled only when `QNN_SDK_ROOT` (or Gradle property `qnnSdkRoot`)
points to an external QAIRT SDK.

The JNI boundary accepts and returns little-endian FP32 NHWC tensors. The
accepted generated `*_net.json` metadata records data type decimal `534`
(`0x0216`, `QNN_DATATYPE_FLOAT_16`) for `images`, `output0`, `output1`, and
`depth_meters`. JNI therefore validates those exact graph schemas and performs
bounded FP32↔FP16 conversion around `graphExecute`. This explains why
`qnn-net-run` fixtures and result files are FP32 even though graph tensors and
internal precision are FP16. A type, name, rank, dimension, byte-count, or
non-finite-input mismatch fails closed.

## External build and private provisioning

No Qualcomm header, sample, runtime, model, or DSP skel is copied into this
repository. Build the JNI adapter against an installed SDK:

```bash
scripts/android-qnn-jni-build \
  --qnn-sdk "$QNN_SDK_DIR" \
  --android-sdk "$ANDROID_SDK_ROOT"
adb -s "$POCO_SERIAL" install -r \
  apps/android-host/build/outputs/apk/debug/android-host-debug.apk
```

Provision only the three accepted model libraries:

```bash
scripts/android-model-install \
  --serial "$POCO_SERIAL" \
  --yoloe "$QNN_MODEL_DIR/libyoloe_bvi40.so" \
  --depth-indoor-392 "$QNN_MODEL_DIR/libdepth_indoor_392_fp16.so" \
  --depth-outdoor-392 "$QNN_MODEL_DIR/libdepth_outdoor_392_fp16.so"
```

Provision the exact QAIRT 2.48 runtime separately:

```bash
scripts/android-qnn-private-provision \
  --serial "$POCO_SERIAL" \
  --qnn-sdk "$QNN_SDK_DIR"
```

The `*_DIR` variables above must point to separately governed locations outside
the repository. Both provisioning paths compute hashes before transfer and
verify the bytes again inside the debuggable app's private directory. A
`.sha256` sidecar alone is not trusted. Accepted model and runtime digests are
pinned in source and the provisioning helpers; this public guide intentionally
does not reproduce private model digests. The model installer exposes
`--allow-untrusted-development` only for explicit local experiments; the
production verifier still rejects those artifacts unless its separately named
development option is enabled by a caller.

Provisioning does not grant redistribution rights. The operator remains
responsible for the Qualcomm runtime license, model/checkpoint licenses, and
Ultralytics terms. No private runtime, model library, checkpoint, or calibration
input belongs in the APK, repository, logs, or public validation artifacts.

## Initialization and failure behavior

The JNI adapter dynamically loads absolute app-private QAIRT and model paths.
Before loading the private V79 stub, it explicitly loads the platform-provided
`libcdsprpc.so` by SONAME. The manifest declares that vendor public library as
optional so Android 12+ exposes it to the application linker namespace when the
device advertises it, without making the base application uninstallable on
non-Qualcomm devices. The adapter retains that platform handle until all
dependent QNN handles have been released. `libcdsprpc.so` is never copied,
provisioned, redistributed, or packaged by this project.

It reports readiness only after provider selection, HTP backend creation,
device creation, context creation, model composition, schema validation, and
graph finalization all succeed. Sessions serialize execution and clean graph,
context, device, backend, and dynamic-library state in dependency order.

There is no CPU fallback and no 336/518 fallback in this adapter. Missing or
untrusted artifacts, an absent public `libcdsprpc.so`, an absent JNI library, a
linker/SELinux denial, an unsupported graph contract, or an HTP error returns a
typed `QnnFailureCode`.

`QnnStagedMachineVisionInferenceAdapter` obtains the exact JPEG by frame ID,
validates frame metadata, decodes with bounded dimensions, applies deterministic
aspect-fit letterboxing, and runs segmentation before the selected 392 depth
graph. YOLO output is constrained to the baked BVI40 vocabulary and exact
`300×38` plus `160×160×32` shapes. Mask fingerprints are carried into bounded
depth sampling and a 128-entry IoU tracker.

The live path converts decoded ARGB pixels to RGB in bounded 32-row stripes,
precomputes each resize axis once per tensor, and reuses the decoded YOLO arrays
for finite-value validation and postprocessing. A reference test compares both
optimized 640 and 392 tensors byte-for-byte with the original half-pixel
bilinear and normalization formulas. Aggregate-only telemetry reports p50/p95/
p99 decode, separate YOLO/depth preprocessing and postprocessing, graph
execution, model setup/routing, and executor-total durations. It also reports
current/total detected-instance counts and validated finite-output counts, but
contains no image, label, mask, identity, address, raw tensor, or raw sensor
data.

`QnnLiveFrameExecutor` is the narrower direct-live test adapter. It decodes and
preprocesses the incoming JPEG, executes YOLOE first, asks the automatic/manual
environment coordinator for one profile, and executes exactly the selected
indoor or outdoor 392 graph. It validates finite YOLO values and validates depth
against the pinned native metric contract: finite, positive, and at most `20 m`
for Hypersim or `80 m` for VKITTI. Validation still occurs when no detections
survive. It never falls back to CPU, 336, 518, or the opposite environment
graph.

If the Rokid frame lacks accepted calibrated/derived intrinsics,
`QnnLiveFrameExecutor` records `UNCALIBRATED_INTRINSICS_MISSING` after tensor
validation without disabling the exact official native-metric scalar output.
Downstream fusion preserves that scalar metre estimate, labels model error
unquantified, and makes no physical-accuracy claim. Accepted intrinsics are
required for a camera ray/vector. Aggregate status retains whether they were
`CALIBRATED` or `DERIVED`, and the host does not infer parameter uncertainty
when the optional uncertainty message is absent.
HEAD←CAMERA extrinsics and
capture-correlated pose separately gate head/world propagation. Real
target-camera intrinsics and metric accuracy remain physical validation gates.

## Validation boundary

Standalone `qnn-net-run` on the Poco F7 Ultra has physically executed the
accepted models through HTP. Recent post-first standalone medians were 111.8585
ms for indoor 392 (101.513–115.706 ms) and 107.677 ms for outdoor 392
(98.78–110.892 ms). These are not app-process or end-to-end latency claims.

The JNI shared object was cross-compiled and then exercised inside the normal
Android 16 application process. An initial probe exposed that the V79 stub's
public `libcdsprpc.so` dependency was not visible in the application namespace;
the optional manifest declaration plus explicit platform-library preload fixed
that gate. Two later bounded physical runs loaded the privately staged models,
resolved the DSP path, and executed both the indoor and outdoor 392 profiles on
QNN HTP. This validates the debug-only integration path, not production
deployment readiness, representative accuracy, or sustained thermal behavior.
