<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Android depth-resolution experiments

The Android Node uses the 392×392 FP16 Depth Anything V2 Metric Small profiles
as its balanced runtime default. The 336×336 pair is the low-power/degraded
tier. The 518×518 pair remains a reference/calibration tier and may be selected
only by an explicit reference or calibration request, or by a sparse ambiguity
probe. Environment selection chooses Hypersim indoor versus VKITTI outdoor
weights; the service tier chooses resolution.

## Reproducible path

Only patch-aligned static inputs `336`, `392`, and `518` are accepted. For
smaller inputs, the exporter bakes the upstream DINO positional-embedding
interpolation into a constant. This is necessary because leaving the bicubic
position resize in the graph caused HTP graph preparation to fail. On the
tested frame, baked and original ONNX outputs differed by at most
`0.0000763 m`; this is an export-equivalence check, not an accuracy result.

```bash
./scripts/android-model-prepare export-depth-variants \
  --depth-source /private/src/Depth-Anything-V2 \
  --depth-indoor-checkpoint /private/models/depth_anything_v2_metric_hypersim_vits.pth \
  --depth-outdoor-checkpoint /private/models/depth_anything_v2_metric_vkitti_vits.pth \
  --output-dir /private/build/depth-variants \
  --sizes 336 392

./scripts/android-qnn-depth-build \
  --qnn-sdk /private/qairt \
  --ndk /private/android-ndk \
  --onnx-dir /private/build/depth-variants \
  --output-dir /private/build/qnn-depth-variants \
  --precision fp16

for size in 336 392; do
  ./scripts/android-model-prepare calibration-images \
    --profile depth --size "$size" \
    --images /private/non-personal/calibration-images \
    --output-dir "/private/build/calibration-depth-variants/depth-$size" \
    --limit 8 --device-runs 15
done

./scripts/android-qnn-depth-benchmark \
  --serial "$ANDROID_SERIAL" \
  --qnn-sdk /private/qairt \
  --model-dir /private/build/qnn-depth-variants \
  --input-root /private/build/calibration-depth-variants \
  --output-dir /private/results/depth-variants
```

Generated ONNX graphs, QNN libraries, SDK binaries, inputs, outputs, and model
weights remain outside Git. The benchmark helper stages a unique device
directory and removes it on exit. It includes `libQnnHtpPrepare.so`; omitting
that host preparation library makes runtime-composed model libraries fail
before HTP execution.

## Poco F7 Ultra result, 2026-08-22

The attached Poco reported Qualcomm SM8750 and HTP V79. QAIRT 2.48.40 executed
15 inferences for each profile using the same non-personal COCO fixture and six
HVX threads. Post-first client times were:

| Metric model | Input | Median | Observed range |
| --- | ---: | ---: | ---: |
| Hypersim indoor Small FP16 | 336×336 | 84.44 ms | 73.40–89.06 ms |
| Hypersim indoor Small FP16 | 392×392 | 108.44 ms | 103.33–114.64 ms |
| VKITTI outdoor Small FP16 | 336×336 | 87.34 ms | 81.72–89.38 ms |
| VKITTI outdoor Small FP16 | 392×392 | 111.43 ms | 100.21–114.66 ms |
| Hypersim indoor Small FP16 reference | 518×518 | 262.66 ms | previously measured 250.22–278.27 ms |
| VKITTI outdoor Small FP16 reference | 518×518 | 269.76 ms | previously measured 243.52–276.51 ms |

These are hardened-repeat, post-first standalone `qnn-net-run` client times;
every host-to-device transfer was verified by SHA-256. The 518 figures are
from the prior standalone run. Full camera-to-cue pipeline latency has not
been measured. These short runs are not sustained thermal or energy evidence.

On the one-image conversion smoke test, QNN-versus-ONNX mean relative
difference ranged from 1.44% to 3.97%, with Pearson correlation above 0.998.
After bilinear resizing to compare against the 518 ONNX output, 392 preserved
the reference better than 336: Pearson `0.9501` indoor / `0.9821` outdoor at
392, versus `0.9061` / `0.9359` at 336. This proxy cannot establish monocular
depth accuracy.

The runtime policy is therefore:

- 392 FP16 is the balanced runtime default and the candidate for representative
  task-accuracy, sustained thermal, and energy validation.
- 336 FP16 is selected for the low-power/degraded tier or when configured
  latency budget or thermal/battery pressure excludes the balanced tier.
- 518 FP16 is reserved for explicit reference/calibration work and sparse
  ambiguity probes; it is not a continuous runtime fallback.
- The router treats each measured standalone median as a necessary lower bound
  for a configurable end-to-end latency budget. If the chosen static artifact
  is unavailable, it fails closed instead of substituting another graph.

Routing is exact: the environment classifier selects Hypersim or VKITTI, then
the service/pressure/budget policy selects one resolution. A balanced request
may degrade to 336 only before artifact selection. Explicit reference,
calibration, or sparse-ambiguity work selects 518, and reference work is
rejected under device pressure. After selection, the depth result must return
the same profile ID and calibration must exist for that profile plus the active
camera-intrinsics fingerprint. Availability of a different tier is not a
fallback.

No representative task-accuracy claim is made for any resolution, and the
standalone medians must not be reported as end-to-end pipeline latency.

## Qualcomm generic relative-depth comparison

Qualcomm AI Hub release 0.60.0 publishes a separately governed Depth Anything
V2 Small package at 518×518. Its model is generic relative depth, not the
Hypersim/VKITTI metric head. The downloaded QAIRT 2.45 DLC reported a cache
selection incompatibility under local QAIRT 2.48.40, then runtime-composed and
executed on HTP. The float DLC measured a 134.58 ms post-first median over 25
runs. Its W8A16 DLC measured 703.36 ms and is rejected on this runtime. A raw
local FP16 reconversion of the vendor ONNX measured 1,194.41 ms and is also
rejected. These local results do not reproduce Qualcomm's published profile
and must not be generalized to other SDK/device combinations.

The float DLC tracked its ONNX reference closely on one image (0.72% mean
relative difference, Pearson 0.99995), but it has no absolute scale. The
Android `TwoAnchorMetricDepthCalibrator.calibrateAuto` path can select direct
or inverse monotonic representation from actual 0.6096 m and 2.4384 m guided
samples. No such controlled physical capture was performed in this pass, so
absolute-distance accuracy is unvalidated and the generic model is not an
accepted runtime profile.
