<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Indoor/outdoor classification and depth routing

The Android Node chooses between the Depth Anything V2 Metric Small Hypersim
(indoor) and VKITTI (outdoor) profiles for each timestamped frame. It does not
infer the setting from GPS alone, and it never interprets weak or absent GNSS
reception as proof that the user is indoors.

## Executable order

`EnvironmentAwareMachineVisionPipeline` enforces this sequence:

1. Run fixed-vocabulary semantic segmentation on the current camera frame.
2. Convert only known BVI class IDs into conservative indoor/outdoor evidence.
3. Fuse the frame-correlated camera evidence with recent optional GNSS quality
   evidence.
4. Apply quorum, repeated-evidence, hold-time, and expiry rules.
5. Run only the selected indoor or outdoor depth graph.
6. Associate its depth samples with the same semantic tracks and frame ID,
   then perform the existing relative-to-metric calibration.

The segmentation stage is independent of depth selection, so it cannot depend
on the very profile it is deciding. Every stage carries a monotonic timestamp
and frame ID. Results with the wrong frame, future completion time, excessive
age, wrong model profile, or wrong vocabulary fingerprint fail closed.

The public APK implements this orchestration, deterministic test adapters, and
an opt-in JNI adapter for privately provisioned QNN artifacts. It does not
bundle the proprietary QNN runtime, YOLOE weights, or either Depth Anything
model library. Two bounded physical debug runs exercised the in-process adapter
with the indoor and outdoor 392 profiles; representative classification and
depth accuracy remain unvalidated.

## Evidence policy

The default camera classifier uses scene-specific members of the fixed BVI
vocabulary. Examples include room-number signs and elevator doors on the
indoor side, and crosswalks, pedestrian signals, curbs, potholes, trees, buses,
and bollards on the outdoor side. Ambiguous classes contribute little or no
evidence. Multiple detections of the same class collapse to the strongest one,
preventing a crowded mask from manufacturing confidence.

Camera evidence expires after 2 seconds. Optional GNSS quality evidence expires
after 15 seconds and is capped at `0.35` reliability. It contains satellite
counts, aggregate carrier-to-noise, horizontal-accuracy metadata, and monotonic
fix age. The sampler deliberately never reads or retains latitude, longitude,
altitude, speed, or bearing. No SSID, BSSID, microphone signal, or image is
stored by this classifier.

Signal quorum counts independent families, not the number of classifiers. A
camera plus GNSS sample counts as two families; two camera classifiers still
count as one. Exceptionally strong visual evidence can classify without GNSS.
GPS/GNSS evidence by itself cannot select a depth profile.

## State and switching

Automatic routing exposes `INDOOR`, `OUTDOOR`, `TRANSITION`, and `UNKNOWN`.
Defaults are:

- enter probability: `0.72` with at least `0.18` margin;
- strong visual-only threshold: `0.88`;
- three distinct, increasing-timestamp confirmations;
- minimum profile hold: 10 seconds; and
- maximum unconfirmed profile reuse: 30 seconds.

Duplicate and out-of-order samples cannot advance the confirmation counter.
Changing modes clears buffered evidence, so returning to Automatic cannot reuse
samples observed before the override.
During a short transition, the selected profile is held with increased
uncertainty. When no current profile exists, depth inference waits instead of
guessing. A held profile expires after the reuse limit if fresh evidence never
reconfirms it.

The router can recommend a bounded shadow comparison when both graphs are
available and the scene is genuinely transitional: at most two comparisons
per transition, separated by at least five seconds. The comparator uses valid
sample coverage, temporal consistency, calibration residual, and uncertainty.
It is a diagnostic/profile-quality signal, not a replacement definition of
whether the environment is indoors or outdoors.

## User control and accessibility

The Android activity provides three explicit modes:

- Automatic (`A`): camera evidence is primary; the app requests precise
  location only after the user explicitly selects this mode and only for a
  bounded foreground GNSS-quality burst.
- Manual indoor (`I`): immediately selects Hypersim and stops GNSS sampling.
- Manual outdoor (`O`): immediately selects VKITTI and stops GNSS sampling.

Mode buttons expose selected/not-selected state through Android accessibility
semantics. Status is a polite live region. The mode preference is stored in the
app's private preferences; scene evidence, coordinates, and images are not.
The synthetic diagnostic (`E`) validates the routing state machine without
claiming live classification.

## Clock boundary

Rokid and phone monotonic clocks do not share an epoch. A production transport
must normalize glasses capture time into the Android host timeline during
session synchronization before calling this pipeline. Comparing raw monotonic
values from the two devices would be invalid.

## Current validation boundary

JVM tests cover semantic scoring, repeated-class suppression, bounded GNSS
support, independent-family fusion, stale/future/duplicate rejection, manual
override, hysteresis, profile expiry, shadow-comparison limits, and the complete
segmentation-to-selected-depth call order. The physical UI validation procedure
exercises the mode controls and synthetic route on the Poco; it is not
model-accuracy or indoor/outdoor field validation.
