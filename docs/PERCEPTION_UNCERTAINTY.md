<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Perception uncertainty

Every 3D observation carries a source, confidence, uncertainty in metres, and
monotonic timestamp. The reference distinguishes:

| Tier | Source | Output policy |
| --- | --- | --- |
| Metric | verified calibrated depth/stereo/ranging | metric clearance, within calibration bounds |
| Calibrated monocular | model estimate validated for the active environment | conservative bands; retain uncertainty |
| Synthetic | deterministic fixtures only | tests/lab, never evidence about hardware |

`DepthProvider`, `MetricGeometryProvider`, `PoseProvider`,
`OpenVocabularySegmenter`, `ObjectTracker`, and `SceneDescriber` keep model and
sensor choices outside the pure Map/Morph/Move layers. Metric geometry does not
wait for recognition.

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
`Depth-Anything-V2-Metric-VKITTI-Small` outdoor profiles at fixed 518×518 input
for QNN HTP deployment. `DepthProfileSelector` requires multiple fresh
independent signals, probability margin, quorum, and hold time; ambiguity keeps
the existing profile.
`TwoAnchorMetricDepthCalibrator` uses dimension-stable semantic observations at
exact 0.6096 m and 2.4384 m guided anchors. Extrapolated output is marked and
penalized. This does not make monocular depth equivalent to measured depth.

Geometry older than 200 ms and semantics older than 350 ms are rejected in the
reference. Those defaults are experimental contextual TTLs, not safety limits.
Delay-injection tests at 50, 100, and 200 ms pass; 400 and 800 ms observations
are rejected rather than spatialized as current reality.
