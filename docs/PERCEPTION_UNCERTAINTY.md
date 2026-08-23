<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Perception uncertainty

Every 3D observation carries source provenance, confidence, an explicit
uncertainty state, and a monotonic timestamp. A numeric uncertainty is present
only when supported by calibration evidence. The reference distinguishes:

| Tier | Source | Output policy |
| --- | --- | --- |
| Metric | verified calibrated depth/stereo/ranging | metric clearance, within calibration bounds |
| Native metric monocular | pinned official metric-head contract | scalar metres; model error remains explicitly unquantified until validated |
| Calibrated monocular | model estimate validated for the active environment | conservative bands; retain uncertainty |
| Synthetic | deterministic fixtures only | tests/lab, never evidence about hardware |

`DepthProvider`, `MetricGeometryProvider`, `PoseProvider`,
`OpenVocabularySegmenter`, `ObjectTracker`, and `SceneDescriber` keep model and
sensor choices outside the pure Map/Morph/Move layers. Metric geometry does not
wait for recognition.

Camera intrinsics preserve their protocol provenance as either calibrated or
derived. That provenance, and parameter standard deviations only when the
producer actually supplies them, enter the host calibration fingerprint.
Missing standard deviations remain an explicit absence; they are never treated
as measured zero uncertainty. Native scalar metric output does not depend on
intrinsics, while camera-ray projection and guided camera calibration do.

The external Depth Anything V2 configuration deliberately exposes separate
indoor and outdoor metric profiles:

- `depth-anything/Depth-Anything-V2-Metric-Indoor-Large-hf`
- `depth-anything/Depth-Anything-V2-Metric-Outdoor-Large-hf`

No weights or inference runtime are bundled. An adapter must select a profile
explicitly, verify its license and checksum, calibrate it against the actual
camera/environment, and propagate observed uncertainty. The code refuses an
unsecured non-loopback endpoint. It never upgrades monocular estimates to
sensor-grade truth.

The CUDA/backend boundary above retains the Large profiles. The power- and
thermal-constrained Android Node separately declares the official
`Depth-Anything-V2-Metric-Hypersim-Small` indoor and
`Depth-Anything-V2-Metric-VKITTI-Small` outdoor profiles at static 336×336,
392×392, and 518×518 inputs for QNN HTP deployment. Their upstream metric head
returns metres bounded by its configured 20 m indoor or 80 m outdoor maximum;
that contract is not a per-pixel accuracy guarantee. `DepthProfileSelector` requires multiple fresh
independent signals, probability margin, quorum, and hold time; ambiguity keeps
the existing profile.
`TwoAnchorMetricDepthCalibrator` uses dimension-stable semantic observations at
exact 0.6096 m and 2.4384 m guided anchors. Extrapolated output is marked and
penalized. This does not make monocular depth equivalent to measured depth.
The calibrator can infer whether a relative model is direct or inverse from
those ordered anchors; it still rejects missing or degenerate anchor spans.
Reduced-input accuracy and sustained behavior remain experimental as described in
[Android depth-resolution experiments](ANDROID_DEPTH_VARIANTS.md).

Geometry older than 200 ms and semantics older than 350 ms are rejected in the
reference. Those defaults are experimental contextual TTLs, not safety limits.
Delay-injection tests at 50, 100, and 200 ms pass; 400 and 800 ms observations
are rejected rather than spatialized as current reality.
