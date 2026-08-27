<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Validation evidence

This ledger records what was actually inspected or executed for the initial
public baseline on 2026-08-21. A build, unit test, cross-target compilation, or
synthetic demonstration is not presented as physical-device, production-model,
accessibility, safety, or performance validation.

## Rokid camera/head rotation and change-gated VLM — 2026-08-27

The connected non-display Style target reported Camera2 camera `0` pose
reference `PRIMARY_CAMERA`, pose quaternion `[0,0,0,1]` in `(x,y,z,w)` order,
translation `[0,0,0]`, and sensor orientation 270 degrees. Current AOSP
metadata semantics establish that the quaternion maps Android sensor axes to
camera-aligned axes, while the `PRIMARY_CAMERA` zero translation is relative to
the main camera itself. Rokid Node now publishes the inverse identity as a
rotation-only camera-to-rigid-head-proxy extrinsic with typed Camera2 provenance
and a SHA-256 evidence binding. It explicitly publishes translation unavailable.
See `docs/ROKID_CAMERA_HEAD_EXTRINSIC.md` for the source and transform boundary.

Both updated APKs were built and replacement-installed. TalkBack remained the
enabled accessibility service with touch exploration enabled. The isolated
Qwen3-VL service physically completed a full image-plus-token prewarm before
accepting camera classification. The following two startup classifications were
both `INDOOR`:

| Frame | Service inference | Capture-to-result |
| ---: | ---: | ---: |
| 1 | 4,055 ms | 5,069 ms |
| 17 | 3,980 ms | 4,594 ms |

No periodic third classification occurred through an 8-minute 49-second
stable-scene live run that observed and queued 1,562 additional camera frames.
This
physically verifies removal of the old timer-driven 60-second refresh for the
observed stable scene. Deterministic tests separately verify two-frame
persistent lighting/layout-change admission, one-frame flash rejection, and
request/result continuity rejection.

A subsequent controlled physical transition kept the data cable connected,
moved the glasses from the settled indoor view to a bright exterior-facing
window view, held that view, and then returned the glasses indoors. The warm
VLM produced agreeing `OUTDOOR` results for frames 537 and 562 in 4,464 and
4,510 ms of service time (5,460 and 5,561 ms capture-to-result). The router
recorded `environment_evidence_pending` and then changed from Hypersim to
VKITTI with `profile_switched`, confidence `0.94`, and `vlmEvidence=true` at
08:30:48.487. A later held portion produced another agreeing `OUTDOOR` pair.
Returning the camera indoors produced agreeing `INDOOR` results for frames 753
and 778; further physical repositioning reopened the gate, so the held outdoor
profile did not immediately switch back before the stream ended. Intermediate
`UNKNOWN`/`TRANSITION` results while the camera was being moved were rejected
from stable routing rather than treated as indoor or outdoor proof.

This validates physical change admission, warm inference, two-label
confirmation, and indoor-to-outdoor depth-profile switching. It is one
operator-guided scene and does not establish classifier accuracy. The sensor
session later encountered Wi-Fi Direct `BUSY` while rediscovering the retained
group and ended with a socket timeout; that occurred after the verified profile
switch and is retained as separate transport evidence, not hidden as a clean
close.

That extended run observed 52,192 raw IMU samples and queued 29,730 samples
after the unchanged IMU gate, with zero camera, IMU, audio, or touch queue drops,
zero link disconnects, zero microphone captures, and an authenticated clean
close. A separate bounded direct-camera diagnostic logged the first emitted
1920×1080 frame with Camera2 provenance, quaternion
`[-0.0,-0.0,-0.0,1.0]`, and `translation_available=false`.

A 30-second direct Rokid-to-Poco QNN-enabled run reconstructed 77 frames,
executed 33 of 42 admitted YOLOE-plus-indoor-depth attempts, accepted
1,614/1,614 IMU samples, recorded zero link interruptions and zero microphone
bytes, and completed an authenticated close. The host repeatedly reached
`METRIC_TRACKS_READY_PROPAGATION_INTRINSICS_UNQUANTIFIED`. That state is emitted
only after the frame extrinsic and a capture-correlated head pose are accepted;
it retains the explicit warning that the derived camera intrinsics have no
measured uncertainty. The scene produced no current semantic tracks, so this
run correctly reported zero propagated objects rather than manufacturing a
positive result. Its p95 measurements were 1,003.3 ms end-to-end, 778.9 ms
capture-to-receive, 60.7 ms segmentation, 27.8 ms depth, 219.4 ms executor, and
4.4 ms clock uncertainty. These are one bounded run, not sustained thermal or
accuracy claims.

The final applicable validation passed 188 Android-host, 215 Rokid-client, and
123 live-transport JVM tests; all three Android lint tasks passed. Shared
protocol descriptor validation, 12 Python protocol tests, and repository policy
validation also passed.

## Direct Rokid-to-Poco live-link and QNN boundary — 2026-08-23

The current source implements a debug-only direct private-WLAN transport from
the Rokid client to the Poco host. Independent realtime/control and camera TCP
sockets require TLS 1.3 mutual authentication against exact public-key pins.
The first lane negotiates a bounded camera+IMU lease, performs monotonic clock
synchronization and keepalive, and issues the second lane a short-lived,
single-use ticket bound to the fresh connection nonce, session, and lease.
Per-lane sequencing, bounded protobuf framing, camera chunk reassembly/digest,
latest-only host admission, lease expiry, cancellation, liveness, and reconnect
limits fail closed. The live lease structurally excludes microphone.

The source-side camera gate requests approximately 3 FPS while relaxed and no
more than 5 FPS after meaningful image change. The source samples fused head
orientation at a nominal 100 Hz; the transmission gate suppresses near
duplicates, batches for at most 20 ms, and emits an absolute refresh at least
once per second. Earlier local-only diagnostics characterized the source rates;
the final private-WLAN runs below additionally record end-to-end pose delivery.

The host's bounded live executor decodes each latest admitted JPEG, executes
fixed-vocabulary YOLOE first, and invokes exactly one automatically or manually
selected 392 indoor/outdoor depth graph through the opt-in QNN HTP adapter. It
has no CPU, 336, 518, or opposite-environment fallback. The JNI boundary is FP32
NHWC at Kotlin/native entry and exit, validates the graph's FP16 schema, and
performs bounded FP32-to-FP16 and FP16-to-FP32 conversion around execution.

Target inspection established equal 4032×3024 pixel, active, and pre-correction
arrays; 4.032×3.024 mm physical size; one 1.9 mm focal length; a 270-degree
sensor orientation; CENTER_ONLY cropping; NONE-only rotate-and-crop; OFF-only
OIS; approximately `[1900, 1900, 0, 0, 0]` intrinsics; and zero distortion
coefficients. Android does not define a zero principal point as an unknown
sentinel. The client therefore accepts a centered metadata derivation only for
that complete metadata fingerprint, labels it `DERIVED`, and leaves numeric
intrinsics uncertainty absent rather than inventing a standard deviation.

For each capture, the client requests the full crop, sole focal length, unit
zoom, rotate-and-crop NONE, distortion correction OFF, video stabilization OFF,
and OIS OFF wherever the advertised request keys support them. Available result
fields are timestamp-correlated to the image and must agree; a contradictory
correlated result suppresses that frame's intrinsics. An absent result retains
only the documented, unquantified static `DERIVED` fallback. These branches are
deterministically tested. The aggregate physical-run status shows that the
derived path reached the host, but it does not make the provisional matrix an
empirically validated calibration or prove every capture-result field.

The Depth Anything V2 Metric graph can still produce native scalar
camera-frame metric depth without intrinsics, with model error unquantified on
this target. Exact pixel-to-ray/3D vector projection requires intrinsics;
calibrated spatial/angular accuracy requires empirical calibration of the
actual 1920×1080 capture path. The official Rokid product/SDK material and
Android Camera2 semantics reviewed on 2026-08-23 publish no Style factory
matrix, distortion residual, or `HEAD <- CAMERA` extrinsic. Checkerboard or
ChArUco calibration and a separate mounting/extrinsic measurement remain
required for `CALIBRATED` spatial/angular claims; source links and access dates
are retained in `docs/RESEARCH_EVIDENCE.md`.

### Final consecutive physical runs — 2026-08-23

Two consecutive, bounded Rokid-to-Poco runs completed without reinstalling
either app between runs. Both used the private-WLAN mutual-TLS path and executed
the app-process QNN adapter on HTP. Both Android processes remained alive, no
crash was observed, and both runs reported zero interruptions.

| Selected 392 profile | Frames received | Inference succeeded/attempted | Positive depth outputs | Poses accepted/received | p95 end-to-end | p95 capture-to-receive | p95 segmentation | p95 depth | p95 executor | p95 clock uncertainty | Authenticated close / failure lane |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Indoor Hypersim | 80 | 61/61 | 9,373,504 | 1,400/1,400 | 941.7 ms | 586.1 ms | 84.1 ms | 70.2 ms | 287.5 ms | 5.3 ms | true / none |
| Outdoor VKITTI | 81 | 61/62 | 9,373,504 | 1,438/1,438 | 1,183.5 ms | 591.0 ms | 107.8 ms | 74.4 ms | 373.2 ms | 5.8 ms | true / none |

Both runs reported metric status
`profile_bound_native_metric_derived_intrinsics_present` and reason
`CAMERA_METRIC_TRACKS_READY_PROPAGATION_INTRINSICS_UNQUANTIFIED`. The native
metric scalar values came from the pinned metric heads and do not depend on
camera intrinsics. The target-fingerprint derivation supplied the provisional
1920×1080 matrix `[fx, fy, cx, cy, s] ≈ [904.7619, 904.7619, 960, 540, 0]`
with `DERIVED` provenance and no quantified parameter uncertainty. Its presence
therefore enabled current-frame camera-ray/vector geometry but did not convert
the run into empirical camera calibration or quantified spatial/angular
validation.

The first physical attempt exposed a transport-framing defect: a length-prefixed
record could be read only partially before the 200 ms socket timeout, while the
old stateless retry discarded the consumed bytes and interpreted the remaining
payload as a new prefix. The reader now preserves its prefix/payload offset and
resumes the same record after a timeout. Authenticated close also runs as a
bounded operation on a dedicated worker instead of blocking the Android main
thread. The two final runs verified the resulting happy-path framing and
authenticated shutdown; their zero-interruption traces do not exercise forced
reconnect or adverse-network recovery.

The latest local Android validation passed 85 live-transport JVM tests, 150
Android-host JVM tests, 131 Rokid-client JVM tests, and one shared-protocol JVM
test with zero failures, errors, or skips. Android Lint passed for all three
modules, both debug APKs assembled, and the preceding forced validation also
assembled both minified release APKs. Focused regressions cover
late connection-attempt resource ownership, lease-expiry precedence over
cross-lane I/O fallout, non-debug live-command denial, and sanitized host
failure-code retention. Protocol descriptor validation and nine focused Python
protocol tests also passed.

The forced Android validation command was:

```bash
./gradlew --no-daemon --rerun-tasks \
  :apps:android-host:testDebugUnitTest \
  :apps:rokid-client:testDebugUnitTest \
  :packages:android-live-transport:testDebugUnitTest \
  :apps:android-host:lintDebug \
  :apps:rokid-client:lintDebug \
  :packages:android-live-transport:lintDebug \
  :apps:android-host:assembleRelease \
  :apps:rokid-client:assembleRelease
```

The source/build/unit evidence above complements the two bounded physical runs.
Those runs validate public-certificate pairing, two-lane wireless transfer,
Android app-process QNN linker/SELinux/DSP-skel execution, HTP dispatch, bounded
latency reporting, and authenticated happy-path shutdown for the exercised
configuration. Forced reconnect/cancellation and adverse-network recovery,
live automatic/manual transition behavior, representative target-camera
metric-depth accuracy, empirical camera calibration, calibrated spatial/angular
output, long-duration throughput, energy, thermals, and BVI usability remain
physically unvalidated.
No private key, runtime/model binary, captured payload, private address, or
device identifier was added to the repository or this evidence.

## Android environment classification and depth routing — 2026-08-22

At this validation checkpoint, the Android Node enforced fixed-vocabulary
YOLOE instance segmentation, including class semantics, before indoor/outdoor
selection and invoked only the selected 518×518 Depth Anything V2 Metric Small
Hypersim or VKITTI graph. The current 392/336/518 tier policy documented in the
2026-08-23 section below supersedes that resolution choice. Camera evidence was
primary. Optional
GNSS evidence is limited to satellite counts, aggregate carrier-to-noise,
horizontal-accuracy metadata, and monotonic fix age; GPS coordinates are never
read or retained, missing reception never proves indoors, and GNSS alone cannot
select a profile. Automatic routing rejects stale, future, duplicate, and
out-of-order evidence, requires three confirmations by default, holds a profile
for at least 10 seconds, and expires it after 30 seconds without confirmation.
Accessible manual indoor/outdoor overrides remain immediate.

The focused Android host suite passed all 74 JVM tests with no skips, including
the staged segmentation-to-selected-depth vertical slice, evidence-family
fusion, hysteresis, expiry, manual override, bounded shadow comparison, and
failure paths. Android Lint, debug assembly, and minified release/R8 assembly
passed. Repository-wide validation passed 207 Python tests, native CTest, both
Android APK test/build paths, and all 156 .NET tests. Repository policy, secret
scan, configuration checks, Ruff, MyPy, protocol regeneration/diff, and the
locked Python dependency audit passed; the audit reported no known
vulnerabilities. All five cited official Android location/GNSS/Wi-Fi pages
returned HTTP 200 on the validation date.

The debug APK was installed in place on the attached Poco F7 Ultra. UI
Automation exposed the app title, available location-provider capability,
environment live region, and four named environment controls. Hardware-key
activation of Manual indoor and Manual outdoor reported the corresponding
metric-depth profile. The synthetic environment diagnostic reported that
timestamped camera semantics plus synthetic GNSS quality selected the outdoor
profile and explicitly identified itself as test data. Activating Automatic
opened Android's location permission dialog; canceling it left fine and coarse
location permissions denied, and the app reported that camera classification
and manual selection remained available. No location permission was granted,
no coordinates or live GNSS sample were retrieved, and no real scene/model
accuracy, TalkBack behavior, energy use, or profile-switch quality was inferred.

## Android metric-depth resolution and relative-depth comparison — 2026-08-22

Official Depth Anything V2 Metric Small Hypersim and VKITTI checkpoints were
exported at static `336×336` and `392×392` resolution from the already pinned
Apache-2.0 source revision. The export bakes the upstream DINO bicubic
positional interpolation as a constant for each supported shape. Against the
original runtime interpolation on the same non-personal image, maximum
absolute ONNX differences were no more than `0.00000382 m` at 336 and
`0.0000763 m` at 392. The generated ONNX graphs, QNN libraries, calibration
inputs, outputs, SDK binaries, and checkpoints remained outside Git.

QAIRT 2.48.40 converted and built all four FP16 graphs. Two complete physical
runs on the Poco F7 Ultra executed 15 samples per graph through QNN HTP V79
with six HVX threads. The first run's post-first medians were `84.51 ms`
indoor/336, `108.18 ms` indoor/392, `86.19 ms` outdoor/336, and `106.36 ms`
outdoor/392. A hardened repeat that SHA-256-verified every host-to-device
transfer measured `84.44 ms`, `108.44 ms`, `87.34 ms`, and `111.43 ms`
respectively. The benchmark helper used a unique bounded staging directory and
removed it automatically. Three earlier task-owned Poco staging directories
were also explicitly removed after their host-side evidence was retained.

One-image QNN-versus-ONNX conversion checks produced mean relative differences
of `1.44%` indoor/336, `2.00%` indoor/392, `3.41%` outdoor/336, and `3.97%`
outdoor/392, all with Pearson correlation above `0.998`. Resized lower-resolution
ONNX output tracked the 518 reference more closely at 392 than 336, but this is
not representative task accuracy or physical metric calibration. At the time
of that experiment, the 518 FP16 profiles remained the current reference slots,
392 was an unadopted balanced candidate, and 336 was a degraded/low-latency
candidate. The later runtime-policy implementation supersedes that selection:
392 is now balanced, 336 is low-power/degraded, and 518 is sparse
reference/calibration. The missing representative accuracy suite, guided
0.6096 m/2.4384 m capture, and sustained thermal/energy measurements remain
missing.

Qualcomm AI Hub release 0.60.0's generic Depth Anything V2 Small package was
also evaluated without committing its separately governed artifacts. Its
float 518 DLC reported a QAIRT 2.45 cache-selection mismatch under QAIRT 2.48,
then runtime-composed and executed on HTP at a `134.58 ms` post-first median
over 25 runs. Its W8A16 DLC measured `703.36 ms`, and a local FP16 reconversion
measured `1194.41 ms`; both were rejected for this runtime. The float result
tracked its ONNX output on one image but remains relative depth. The Android
calibrator now detects direct versus inverse monotonic representation, but no
controlled two-distance physical capture was performed, so this model was not
adopted.

Final validation passed 207 Python tests, native CTest, 52 Android Node JVM
tests, 78 Rokid Node JVM tests, the shared Android protocol test, Android Lint,
strict Gradle dependency verification, both debug APK builds, and the minified
Android Node release/R8 build. The Windows relay built warning-free with the
Windows-capable .NET 8 SDK and all 156 tests passed. Formatting, repository
policy, configuration, license, secret, Ruff, MyPy, workflow-YAML, protocol
generation, diff-whitespace, and Python dependency-audit checks passed. The
dependency audit found no known vulnerability in non-editable installed
distributions.

## Current Machine Vision correlation and temporal policy — 2026-08-23

The current Kotlin boundary routes one exact environment/resolution profile:
392 balanced, 336 low-power/degraded, or 518 for explicit reference,
calibration, and sparse ambiguity work. It fails closed if the selected artifact
is unavailable, and a depth-stage response must repeat the selected profile ID.
Real frames also require calibration from an immutable bounded registry keyed
by that complete profile ID and a SHA-256 fingerprint of the active camera
intrinsics. Instance-mask depth is accepted only for eligible fixed-vocabulary
tracks, and a mask fingerprint emitted by segmentation must match the
depth-stage fingerprint for the same image/track.

Pinhole class-dimension estimates are same-image priors, not independent metric
truth. Outliers are rejected, and an agreeing prior is explicitly prevented
from reducing calibrated-depth uncertainty. The observed-keyframe gate admits
relaxed frames at 3 FPS and permits at most 5 FPS for meaningful motion or
uncertainty. Between successful visual keyframes, pose updates can transform
only existing anchors carrying explicit confirmed-static-world evidence;
eligibility defaults to unknown/non-propagatable, and a class allowlist or mask
geometry is never sufficient. Person/mobility-aid and vehicle groups do not
become anchors even if upstream marks them static; dynamic and unknown-motion
tracks are also rejected. A later dynamic or unknown observation with a reused
ID removes its former static anchor. Translation requires explicit
position evidence at both ends with the same VIO/external-tracking source and
coordinate origin; otherwise only orientation is applied. Confidence decays,
uncertainty grows, and a stale pose produces no propagated snapshot. TTL,
confidence/uncertainty bounds, explicit occlusion, and bounded capacity remove
tracks. Pose/IMU ticks cannot create depth or new objects and cannot observe
moving or newly visible objects.

This is an implemented and deterministic source boundary. The later opt-in
in-process QNN adapter and debug-only live test were physically exercised in
the two final runs recorded in the current checkpoint above. Representative
metric/task accuracy, sustained thermals/energy, and BVI usability remain
unvalidated.

The 2026-08-23 Android Node debug APK was installed on the attached Poco and
its keyboard-accessible `V` diagnostic was exercised. UI Automator exposed the
expected accessible text: the deterministic door track was calibrated to
1.54 metres, the exact 392-pixel balanced profile was selected, and the stable
track was propagated with an orientation-only pose tick. The result explicitly
identified itself as synthetic test data rather than live inference. The
current source now explicitly marks only that synthetic door as confirmed
static; production observations default to unknown/non-propagatable. This
debugger change was not reinstalled or physically re-exercised in this pass.

The matching Rokid Node debug APK was installed directly over ADB and two
eight-second, aggregate-only stream diagnostics passed. The repeat run observed
13 usable 1920×1080 JPEG frames, 781 nonzero IMU samples at 98.7 Hz with a
17.7 ms maximum gap, and 16 microphone chunks before the two-second microphone
lease expired. The process and service stopped afterward. Although the capture
policy requested 3 FPS relaxed and up to 5 FPS during motion, only 13 frames
were analyzed during the complete 8.44-second cold-start session, including
camera warm-up and session creation. This run validates acquisition and gating,
but did not establish a sustained 3 FPS physical camera rate; steady-state
capture timing was measured and the bounded path was repaired in the follow-up
below.
No raw frame, PCM, or IMU payload was persisted or logged.

A follow-up timing build separated the bottleneck: with the serialized path,
request-to-image latency was 433.8 ms p50, versus 2.4 ms image acquisition,
40.2 ms processing, and 6.5 ms listener/packetization work. The repaired source
therefore uses one monotonic opportunity timer and at most three outstanding
Camera2 requests; a missed opportunity is counted and discarded rather than
queued for replay. Request tags and sensor timestamps preserve exact image
association, and late callbacks are run-scoped.

The bounded pipeline's physical run passed with 26 analyzed frames over a
5.5595-second first-to-last active span (`4.497 FPS`) and 23 emitted frames over
5.5452 seconds (`3.967 FPS`). It submitted 29 requests and reached the configured
three-request ceiling without backpressure, supersession, unmatched images,
capture failures, or late callbacks. Request-to-image latency was 425.4 ms p50,
445.2 ms p95, and 541.5 ms maximum; processor time was 33.9 ms p50 and 50.9 ms
p95; listener/packetization time was 6.1 ms p50 and 10.1 ms p95. The same run
observed 781 nonzero IMU samples at 98.7 Hz with a 17.7 ms maximum gap and 16
microphone chunks before expiry. The 8.448-second cold-start result validates a
bounded approximately 3–5 FPS acquisition/gating slice, not sustained thermal,
wireless, in-app QNN inference, or end-to-end perception performance.

A final physical run with no material-motion samples exercised the relaxed
tier after lifecycle hardening: 18 frames were analyzed at 3.058 FPS and 17
were emitted at 2.885 FPS over their first-to-last active spans. It submitted
20 requests, reached two outstanding requests, and recorded zero backpressure,
supersession, unmatched images, capture failures, or late callbacks. Terminal
telemetry reported zero outstanding requests, and the app and runtime service
stopped.

The final installed debug APKs both passed APK Signature Scheme v2 verification.
Android Node SHA-256 was
`8210c4bfc257c7dd68c4233b37689632a19295f2a740622ccd61e428d5058ff3`;
Rokid Node SHA-256 was
`e4598e5d6726a0a38bfd5b1feef3c45bcc6ccea9ffd33cc0a0ccdf21ad093a60`.

## Android Node Machine Vision foundation — 2026-08-22

The Android and Rokid APK labels were changed to **Machine Perception Layer,
Android Node** and **Machine Perception Layer, Rokid Node**. `aapt2 dump
badging` confirmed both packaged labels. Both APKs were installed through
serial-qualified ADB on the selected Poco and Rokid targets; their identifiers
are intentionally omitted. The Rokid install remained inert after installation.

The Android Node gained a closed 40-class BVI vocabulary; 80 immutable
dimension-vector records at exact 0.6096 m and 2.4384 m anchors; indoor/outdoor
Depth Anything profile hysteresis; a two-anchor relative/inverse-depth metric
calibrator; mask-associated semantic/depth fusion; checksummed private artifact
slots; an exact baked-vocabulary fingerprint; and a planner that selects QNN
HTP only after the project adapter and HTP backend actually initialize. It does
not select legacy HTA. No model, QNN binary, or proprietary SDK artifact was
added to the repository or APK.

Strict Android dependency verification, Android Lint, debug assembly, and
release/R8 assembly passed. JVM tests passed with 50 Android Node tests, 78
Rokid Node tests, and
one shared-protocol test, with no failures or skips. On the physical Poco, the
keyboard `V` path produced an accessible result: `door` calibrated to 1.52 m
from deterministic data. UI Automation exposed the 40-class count, zero of
three integrity-checked private artifact slots, and `CPU reference mode; QNN adapter
unavailable`. This is an executable synthetic diagnostic, not model inference.

The private-model provisioning helper was exercised with three small
repository-safe stand-ins. Its first physical run exposed an ADB shell quoting
defect that left sidecars under temporary names; the app verifier failed closed.
Upload and atomic rename were separated, file/directory modes were restricted,
and the rerun passed byte-for-byte SHA-256 checks for all three transfers. All
stand-ins and sidecars were then removed from the phone, and the app was
restarted to confirm zero of three artifacts. Real weights were never copied.

At that foundation checkpoint, YOLOE-26S execution, Depth Anything execution,
QNN conversion/load, HTP/NPU execution, inference accuracy/FPS, metric
accuracy, thermal behavior, and BVI usability had not been validated. The
subsequent physical model-toolchain pass below supersedes only the stated graph
conversion and standalone HTP-execution limitations.

## Poco QNN HTP model-toolchain validation — 2026-08-22

The exact 40 prompts in `config/machine-vision/bvi_classes.txt` were applied to
the caller-supplied `yoloe-26s-seg.pt` checkpoint with Ultralytics 8.4.90. The
resulting static ONNX contract has input `1×3×640×640`, detection output
`1×300×38`, mask-prototype output `1×32×160×160`, and vocabulary fingerprint
`2ca8ebc9d1b7914e1dfd1d288e517e78e1b24be75ad04cd6bc0df3e0455aca44`.
The original checkpoint, generated checkpoint, ONNX graph, calibration images,
QNN sources/libraries, and Qualcomm runtime all remained outside Git.

The official Depth Anything V2 source was pinned to
`a561b849ebae10a6f5ef49e26c83cbbcd36c71bf`. The official Small checkpoints
were pinned and checksum-verified without publishing the private artifact
digests:

- Hypersim indoor revision `3bc65d4e14a6786a61acec16453c50e12bf5f338`,
  20 m metric head; and
- VKITTI outdoor revision `c725b8589bdf6ab04072cab74c0467830db80d6d`,
  80 m metric head.

Static `1×3×518×518` ONNX exports passed ONNX validation and CPU execution.
PyTorch-to-ONNX maximum absolute differences on deterministic synthetic input
were `0.000004292` m indoor and `0.000015736` m outdoor.

QAIRT 2.48.40 converted and built all three FP16 graphs, and `qnn-net-run`
physically executed them through QNN HTP V79 on the Poco F7 Ultra using six HVX
threads. Client-side inference measurements, including the first warm-up run,
were:

| Graph | Runs | Median | Post-first observed range | First run |
| --- | ---: | ---: | ---: | ---: |
| YOLOE-26S BVI40 segmentation FP16 | 25 | 80.065 ms | 73.824–84.117 ms | 135.989 ms |
| Depth Anything V2 Hypersim Small FP16 | 15 | 262.657 ms | 250.223–278.268 ms | 456.937 ms |
| Depth Anything V2 VKITTI Small FP16 | 15 | 269.760 ms | 243.522–276.508 ms | 447.588 ms |

On one real-image conversion-fidelity smoke sample, indoor depth differed from
ONNX Runtime by mean absolute `0.02337` m / mean relative `1.895%`; outdoor by
mean absolute `0.33195` m / mean relative `4.495%`. A higher-signal YOLO image
produced 31/31 class-aware IoU≥0.5 matches above 0.05 confidence for FP16, mean
matched IoU `0.9886`, with mask-prototype mean absolute difference `0.009675`
after accounting for QNN NHWC output layout. These are conversion checks on
individual images, not task accuracy, metric-world accuracy, or BVI evidence.

Plain W8A8 was physically rejected: the mixed YOLO detection tensor lost
usable confidence/class semantics, and both depth graphs exceeded 50% mean
relative error on the smoke input. Real-image-calibrated W8A16 retained YOLO
detection semantics and reduced depth median runtime to 247.258 ms indoor and
242.518 ms outdoor, but YOLO remained 79.122 ms and its prototype deviation
increased to `0.049879`. It is therefore experimental rather than the accepted
baseline. During this short sequence, battery temperature remained 36.5 °C and
reported NSP zones rose from approximately 39.9–40.7 °C to 42.6–43.0 °C; this
is not a thermal-soak result.

At that checkpoint, the public source provided deterministic external
export/calibration and QNN build helpers, and the app-side verifier required an
ELF64 little-endian AArch64 library plus matching SHA-256 sidecars. The Android
APK did not package Qualcomm binaries or initialize QNN in-process. Standalone
model execution is validated; app integration, representative BVI accuracy,
sustained thermals, energy consumption, and end-to-end Rokid-to-cue behavior
remain open gates.

The rebuilt debug APK was installed on the Poco and the three accepted FP16
model libraries were atomically provisioned into app-private storage with
byte-for-byte SHA-256 verification. Android UI Automation then exposed the
truthful nonvisual status `Integrity-checked private artifact slots: 3 of 3`
and `CPU reference mode; QNN adapter unavailable`. The private artifacts remain
on the user-owned test phone for the next in-process adapter milestone; they
are absent from the repository and APK. The installed debug APK SHA-256 was
`4b1fb3a2168cecb0ca00d9e9190f38c19441ce53a86d4ba1bec6256a705863cc`.

Post-change validation passed 205 Python tests, native CTest, 51 Android Node
JVM tests, 78 Rokid Node JVM tests, the shared Android protocol test, both debug
APK builds, Android Lint, the minified Android Node release build, and all 156
.NET tests with a warning-free Release build. The first release build exposed
R8 warnings for gRPC's optional legacy OkHttp and desktop JNDI references;
narrow Android-only warning rules were added for those exact unavailable
classes and the release build then passed.

## Map. Morph. Move. extension — 2026-08-22

The extension was validated on the same Ubuntu host after a full power cycle.
`nvidia-smi` then reported both expected devices with driver 580.173.02: an RTX
2080 Ti with 11,264 MiB and an RTX 4060 Ti with 16,380 MiB. CUDA-aware
compilation below did not execute a kernel or model on either GPU. The local
coder, vision, Whisper, and Qdrant endpoints were live, but model output was
used only for bounded review passes—not as test evidence.

The executable additions passed these local gates:

- repository policy, secret scanning, safe configuration checks, Ruff, MyPy,
  formatting, shell parsing, and diff whitespace;
- 197 Python tests, deterministic protobuf validation, all three source/wheel
  builds, byte-identical legal notices, isolated wheel imports, and the
  synthetic gRPC and perception demonstrations;
- Android Lint, strict dependency verification, 31 host tests, 41 Rokid tests,
  one protocol-vector test, and both debug APK assemblies;
- .NET 8.0.424 warning-free Release cross-build including WPF and all 156 tests;
- native CPU and CUDA-aware Release builds and their CTest target using NVCC
  12.0.140;
- Unity 6000.3.22f1 headless EditMode 6/6 and PlayMode 1/1 tests; and
- FMOD Studio 2.03.14 project validation plus Desktop and Mobile bank builds.
  The deterministic FMOD inspection reported two events, bounded voice counts,
  limiter presence, and two Resonance sources.

The 100-iteration headless `end_to_end_headless_map_morph_move` benchmark
measured p50 2.920153 ms, p95 3.119432 ms, and p99 3.801060 ms, with a separately
traced peak of 34,308 bytes. This run starts with synthetic geometry and ends at
inspectable render commands. It excludes capture, depth inference, networking,
FMOD output buffering, Android vibration actuation, and human perception.

The FMOD project and generated procedural inputs were physically authored and
built, but the proprietary FMOD Unity runtime package is not redistributed. At
that checkpoint, Unity-to-FMOD playback, listening-based localization,
open-ear perceptual quality, JAWS/NVDA, metric depth on Rokid, real
YOLOE/Depth Anything inference, and sustained thermal behavior were not
validated. The final direct runs at the top of this ledger supersede the
metric-depth/model-execution limitation only; they are hardware execution
checks, not BVI human-factors acceptance or production-model validation.

During the post-reboot physical-trace pass, FMOD Studio validation and Desktop/
Mobile bank builds passed again. A fresh Unity headless run could not start
because the local Unity licensing client reported no valid Editor license; it
produced no test result. The 6/6 EditMode and 1/1 PlayMode results above are from
the immediately preceding implementation pass on the same branch, not this
post-reboot rerun.

## Power-aware Rokid stream lease — 2026-08-22

The v1 schema was additively extended with transport-neutral stream lease,
camera chunk, absolute IMU batch, microphone chunk, and sensor envelope
messages. Generated Python output was regenerated, protocol validation passed,
and both Android applications compiled against the same Java-lite schema. The
existing `PerceptionService` RPC surface remains unchanged.

The final Android run passed 115 JVM tests (36 host, 78 Rokid client, and one
shared-protocol test) with no skips or failures. Focused tests exercised
single-owner lease expiry, owner-checked renewal and close, explicit and
independently expiring microphone consent, quaternion
sign equivalence, invalid/out-of-order IMU rejection, one-second absolute IMU
refresh, maximum 20 ms batching, bounded 16 KiB camera packetization, protobuf
round trips, host-side ordered reassembly and SHA-256 verification, partial
frame replacement/expiry, latest-unread camera behavior, IMU batch ordering,
and microphone authorization. Android Lint and both debug APK builds passed.

The exact final debug APK was directly installed on the selected Rokid target,
whose identifier is intentionally omitted, and the explicit eight-second
stream diagnostic ran to completion. The final run reported:

- 12 source-gate emissions at exactly 1920×1080 / 12,932,054 transient JPEG
  bytes; 13 analyzed frames, no dark or blur rejection, one cadence rejection,
  maximum motion score 0.073, and eight motion-tier decisions. Eleven frames
  reached packetization before lease closure, producing 726 bounded chunks and
  11,855,622 payload bytes;
- 783 raw IMU observations at 98.8 Hz with 12.7 ms maximum source gap; the
  transmission gate selected 512, suppressed 267 consecutive near-duplicates,
  and admitted 493 samples in 218 bounded batches before lease closure; and
- 16 physically recorded microphone chunks / 65,536 transient bytes, of which
  14 chunks / 57,344 bytes passed packetization before the explicit two-second
  microphone sub-lease expired. Recorder shutdown was initiated by the
  two-second timer; after the blocking close returned, the log reported
  `stream=microphone status=lease_expired` while the camera and IMU lease
  continued.

The diagnostic reported `pass`, retained/logged no raw payload, and left the
package process and private runtime service stopped. Total diagnostic duration
was 8,424 ms including Camera2 shutdown; it is not an end-to-end network
latency measurement. The APK SHA-256 was
`e02082055e94cd21df0e86c5a9ccd3eb05754eb2675154a01c9a8b7839b5181e`.
The matching Android host debug APK was reinstalled successfully on the Poco;
that confirms installability, not a physical glasses-to-phone stream.

The final repository-wide local cycle also passed 199 Python tests, 156 .NET
tests with zero build warnings or errors, native CTest, three Python
wheel/source builds plus isolated imports and the synthetic demo, repository
format and policy checks, Ruff, MyPy, configuration validation, dependency
verification, license guards, and secret scanning.

No physical WebRTC link was claimed: that checkpoint validated source-side
leases/gates/packetization and Poco-side bounded ingestion, not wireless
signaling, pairing, encryption, throughput, energy use, or thermal behavior.
The later direct mutual-TLS implementation is recorded at the top of this
ledger. During the required local DEBUGGER pass, llama.cpp encountered a
CUDA synchronization failure and the RTX 4060 Ti subsequently disappeared
from `nvidia-smi`; only the RTX 2080 Ti remained visible. No CUDA result after
that failure is counted as validation.

## Environment discovery

The release host was Ubuntu 24.04 with kernel 7.0.0-29-generic. The relevant
tools found were Git 2.43.0, authenticated GitHub CLI 2.45.0, OpenJDK/Javac
17.0.19, Android SDK API 36 components, Gradle wrapper 8.11.1, Python 3.12.3,
.NET SDK 8.0.424 in a temporary tool directory, CMake 3.28.3, Ninja 1.11.1,
CUDA compiler 12.0.140, ADB 1.0.41, Node 22.22.3, pnpm 11.5.1, ImageMagick
6.9.12-98, and `file` 5.45.

Initial inspection reported an unavailable device handle for one GPU. After the
user shut down, power-cycled, and rebooted the host, `nvidia-smi` reported both
the RTX 2080 Ti (11,264 MiB) and RTX 4060 Ti (16,380 MiB) with driver
580.173.02. CUDA validation below is toolchain-aware compilation only; the
physical trace's deterministic worker did not execute a CUDA kernel or model.

## Release validation summary

| Lane | Executed result | Boundary |
| --- | --- | --- |
| Repository | format, policy, secret, config-example, shell, Ruff, and MyPy gates passed | `actionlint` was unavailable; workflows received parser and repository-policy checks |
| Python | 145 tests passed; protocol generation valid; all three source/wheel packages built and isolated-imported | no production worker or non-loopback deployment |
| Synthetic slice | real loopback gRPC demo passed reconnect, cancellation, timeout, stale rejection, worker error, overload, recovery, and cue rendering | deterministic CPU mock and synthetic frames only |
| Android | Initial baseline: 73 JVM tests, Android Lint, strict dependency verification, and both debug APK assemblies passed; the current live-link totals are recorded above | no instrumentation or physical Rokid-to-Poco transport at this checkpoint |
| .NET | locked restore, formatter, warning-free Release build including WPF cross-target, 156 tests, and consent-gated demo passed | WPF was not executed on Windows |
| Native | strict Release build, 15-case test executable, JSON-lines demo, ASan/UBSan build/tests, and CUDA-aware build/tests passed | no CUDA kernel or model inference exists |
| Dependencies | Python and .NET vulnerability queries found no known vulnerability in resolved third-party packages | local editable Python distributions were correctly excluded from the index audit |

## Commands executed

Repository and Python baseline:

```bash
./scripts/format --check
./scripts/lint
python -m pytest -q
python -m conceptflow_mpl_protocol.validation
./scripts/build python
python scripts/repository/check_wheels.py
python -m pip_audit --skip-editable
./scripts/demo
./scripts/benchmark --iterations 100
```

A separate clean Python 3.12 virtual environment installed
`requirements.lock` with `pip --require-hashes`, installed the three local
projects in editable mode, and passed all 145 tests. Wheel checking built each
source distribution and wheel, installed artifacts into isolated environments,
imported every public package, checked dependency consistency, and ran the
combined synthetic demo.

Android baseline:

```bash
./gradlew --no-daemon --dependency-verification strict \
  lintDebug testDebugUnitTest assembleDebug
```

The build used JDK 17 and the installed Android SDK. Those initial totals were
31 tests for `android-host`, 41 for `rokid-client`, and one cross-language
protocol-vector test in `android-protocol`; the current live-link totals are
recorded at the top of this ledger.

.NET baseline:

```bash
dotnet format apps/desktop-relay/ConceptFlow.Mpl.DesktopRelay.sln \
  --verify-no-changes
dotnet restore apps/desktop-relay/ConceptFlow.Mpl.DesktopRelay.sln \
  --locked-mode
dotnet build apps/desktop-relay/ConceptFlow.Mpl.DesktopRelay.sln \
  --configuration Release --no-restore
dotnet test apps/desktop-relay/ConceptFlow.Mpl.DesktopRelay.sln \
  --configuration Release --no-build
dotnet run --project \
  apps/desktop-relay/src/ConceptFlow.Mpl.DesktopRelay.Demo/ConceptFlow.Mpl.DesktopRelay.Demo.csproj \
  --configuration Release --no-build -- --consent-synthetic-demo
dotnet list apps/desktop-relay/ConceptFlow.Mpl.DesktopRelay.sln package \
  --vulnerable --include-transitive
```

The build completed with zero warnings and zero errors. The demo submitted only
the documented fixed one-pixel synthetic image to an in-process transport and
reported one bounded shutdown sequence.

Native baseline:

```bash
cmake -S services/cuda-cluster/native -B <temporary-release-build> -G Ninja \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=ON \
  -DCONCEPTFLOW_ENABLE_CUDA=OFF
cmake --build <temporary-release-build> --parallel
ctest --test-dir <temporary-release-build> --output-on-failure

cmake -S services/cuda-cluster/native -B <temporary-sanitized-build> -G Ninja \
  -DCMAKE_BUILD_TYPE=Debug -DBUILD_TESTING=ON \
  -DCONCEPTFLOW_ENABLE_CUDA=OFF \
  -DCMAKE_CXX_FLAGS="-fsanitize=address,undefined -fno-omit-frame-pointer" \
  -DCMAKE_EXE_LINKER_FLAGS="-fsanitize=address,undefined"
cmake --build <temporary-sanitized-build> --parallel
ctest --test-dir <temporary-sanitized-build> --output-on-failure

cmake -S services/cuda-cluster/native -B <temporary-cuda-build> -G Ninja \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=ON \
  -DCONCEPTFLOW_ENABLE_CUDA=ON
cmake --build <temporary-cuda-build> --parallel
ctest --test-dir <temporary-cuda-build> --output-on-failure
```

CMake found `/usr/bin/nvcc` 12.0.140. The CUDA-aware option proves toolkit and
compiler discovery plus compilation of the current C++ targets; it does not
launch a kernel or establish GPU correctness.

## Executable vertical slice

The loopback demonstration uses the canonical v1 protobuf/gRPC service. It
negotiates an ephemeral session, validates and correlates bounded synthetic
frames, routes them through a deterministic CPU worker, converts observations
to prioritized assistive cues, and emits an inspectable renderer event. The run
returned serving health, all expected fault/recovery events, 19 explicit
backpressure results, and `assistive_only=true`.

The service performs cheap structural validation first, then session/rate/
in-flight admission, then full Pillow decoding for PNG/JPEG before worker
dispatch. Tests cover malformed compressed data, decoded resource bounds, and
rejection of unknown sessions before decode work. The fair queue uses
round-robin session lanes and bounded reject-newest admission.

The 100-sample local synthetic benchmark reported p50/p95/p99 for frame
validation, protobuf serialization, and protobuf deserialization. Demo output
also exposes preprocessing, inference-RPC, and cue-scheduling stage timings.
These machine-local synthetic numbers are not physical glass-to-cue latency.

## Protocol interoperability

`packages/shared-protocol/proto/conceptflow/mpl/v1/perception.proto` is the
authoritative schema. Generated Python output is deterministically checked.
Android Java-lite and .NET C# generate from the same schema during their builds.

`tests/fixtures/protocol_vectors.properties` contains deterministic, nonpersonal
`FramePayload` and `PerceptionResult` wire vectors. Python, Java-lite, and C#
tests parse and byte-exactly reserialize those vectors. Android and .NET client
image fixtures also pass the real Python gRPC decoding boundary. Unknown fields
retain the behavior of the respective Protobuf runtimes.

The implemented gRPC service is `Negotiate`, `ProcessFrame`, and `Health`.
Transport-neutral sensor messages, `LiveLinkEnvelope`, and the direct Android
two-lane TLS 1.3 mutual-TLS transport are implemented and locally tested.
WebRTC signaling and a WebRTC data-channel adapter are not implemented, and no
physical WebRTC transmission is claimed. The separate direct TLS transport was
physically exercised in the two final runs recorded above.

## Rokid and Poco evidence

The attached non-display consumer glasses reported manufacturer `Rokid`, model
`RG-glasses`,
Android 12/API 32, YodaOS Sprite assist service 0.3.5, and CXR service package
`com.rokid.cxrservice` version 12. Direct ADB over the magnetic 5-pin data cable
was verified. An earlier visual activity-based debug APK was the wrong
application model and was replaced. Android's reported 480×640 display object
in state `OFF` is an internal compatibility surface, not a physical display.

The replacement APK was rebuilt and installed. Its explicit, protected,
nonvisual command activity bound a private runtime service and kept Android's
logical compatibility surface awake without a launcher or wearer-facing UI.
Device logs recorded `state=capturing` followed by monotonic frame IDs 1 through
5, while CameraService identified `org.conceptflow.mpl.rokidclient` as camera
0's active client. The explicit stop command left both process and runtime
service stopped. No vendor service was disabled. A temporary app-op diagnostic
was restored to the original foreground mode.

A subsequent exact-APK test ran camera, IMU, and microphone input concurrently
for a bounded eight-second interval. The final repeat reported `result=pass`, 9
camera frames (2,175,819 transient JPEG bytes), 1,170 IMU samples, and 65
microphone chunks (266,240 transient PCM bytes). Aggregate signal inspection
found nonzero data in all 1,170 IMU samples, plus 126,204 nonzero microphone
samples and peak absolute PCM amplitude 358. No image, IMU, or audio payload was
written or logged. After automatic shutdown, the package process and runtime
service were stopped, camera ownership was released, and no package audio
recorder was reported. This verifies standard Android acquisition on this unit;
it does not validate array beam selection, acoustic fidelity, long-duration
thermal behavior, or physical audio/haptic output.

A separate physical-input pass captured only Linux input events and no camera,
microphone, or personal content. The top-right physical button near the lens
reported `KEY_MENU` on `qpnp_pon`. The right-arm capacitive controller
`ROKID,PSOC-TP-R` was wear-gated: isolated off-head taps/swipes produced no
events, while worn trials reported a `KEY_DASHBOARD` preamble followed by
`KEY_PROG1` for single/long press, `KEY_YELLOW` for double tap,
`KEY_VOLUMEUP` for a swipe toward the lenses, and `KEY_VOLUMEDOWN` for a swipe
toward the ear. A repeated swipe could report multiple volume steps. These are
raw firmware/input mappings; application-level interception and system-key
consumption remain to be tested.

During that pass, direct USB-C attachment repeatedly failed before enumeration
with kernel `-71` (`EPROTO`) despite correct packaged Android udev coverage,
`plugdev` membership, udev/ADB refresh, disabled autosuspend, compatibility
enumeration, a host-controller rebind, a Hi Rokid ADB-setting cycle, and a
supported glasses restart. The same magnetic data cable connected through a
data-capable USB-A adapter enumerated as `18d1:4ee7` at 480 Mbit/s and kept an
authorized ADB session after host settings were restored. No custom udev rule
was added. Temporary on-device event logs were removed, the glasses stay-awake
setting was restored to `0`, and host USB autosuspend/legacy-order settings were
restored to `2`/`N`.

The Poco F7 Ultra initially rejected the debug host APK with
`INSTALL_FAILED_USER_RESTRICTED`. After the user approved Xiaomi's named
**Install via USB** confirmation, the same package installed and launched. A
hardware `P` key event exercised the in-process synthetic path. Android logs
showed an accessibility-sonification `AudioTrack`; `dumpsys vibrator_manager`
recorded a finished 66 ms predefined `CLICK` for
`org.conceptflow.mpl.androidhost`. UI Automator exposed named text for Connect,
Process, Cancel, Disconnect, capabilities, state, and cue output. This validates
Poco output dispatch and inspectable semantics, not a glasses-to-Poco data
plane, directional haptics, audio quality, or manual TalkBack acceptance.

On the directly sideloaded Rokid app, a rebuilt one-shot physical trace used an
authorized per-device ADB reverse tunnel to the Python service bound only to
`127.0.0.1:50051`. The final run reported one 247,909-byte transient JPEG, 141
IMU samples with nonzero signal, a timestamp-matched HEAD pose, and eight
16-kHz mono microphone chunks (32,768 transient bytes, 14,562 nonzero samples,
peak absolute amplitude 521). It negotiated an ephemeral v1 session, received
one strictly correlated synthetic cue from the deterministic worker, and
reported `rendered=1 audio=1 haptic=0`, 798 ms from gRPC dispatch to input and
transport cleanup and 1,692 ms from input start to that cleanup. The service
then retained its binding for a bounded 1.5 seconds so the 120 ms static
`AudioTrack` was not released immediately. Raw microphone audio was not
transmitted; no frame, PCM, or sensor payload was logged or written. The glasses
expose no vibrator service, so the haptic adapter correctly returned unavailable.

The service configuration used `MPL_DEVICE=cuda`, disabled CPU fallback, and
created workers named for both discovered GPU devices. The bundled worker is
still deterministic Python logic: this run did not execute a CUDA kernel,
trained model, Depth Anything, or YOLOE, and the timing is a single engineering
sample rather than a benchmark distribution. Audio dispatch was observed in
Android logs; audibility, binaural position, and wearer perception were not
measured.

The canonical development route is now the standalone standard-Android app and
direct ADB sideload. `./scripts/rokid-install --serial ... --inspect-only`
verified the current authorized target properties without changing the device.
The repository has no Hi Rokid runtime dependency, Rokid client secret, vendor
Maven repository, or vendor SDK adapter. Official Android and Rokid cable
sources plus pinned community implementation references are recorded in
[`docs/ROKID_INTEGRATION.md`](docs/ROKID_INTEGRATION.md).

The direct-sideload pass executed these command forms with locally selected,
unpublished serials:

```bash
./scripts/rokid-install --serial "$ROKID_SERIAL" --inspect-only
./scripts/rokid-install --serial "$POCO_SERIAL" --inspect-only  # refused as non-Rokid
./scripts/rokid-install --inspect-only                           # refused as ambiguous
./scripts/rokid-install --serial "$ROKID_SERIAL" --no-build --grant-camera
./scripts/rokid-control --serial "$ROKID_SERIAL" capture-start
./scripts/rokid-install --serial "$ROKID_SERIAL" --no-build --grant-microphone
./scripts/rokid-control --serial "$ROKID_SERIAL" stream-test
./scripts/rokid-control --serial "$ROKID_SERIAL" physical-trace
./scripts/rokid-control --serial "$ROKID_SERIAL" stop
```

The camera/IMU/microphone test APK was 7,020,604 bytes with SHA-256
`eaf6f805476c93399a298ea2a3423f17038d98b2fdd62977fe5e0fc59e0a45f3`.
The final physical-trace debug APK was 14,857,132 bytes with SHA-256
`a7b906ec2bce55e89626a312fe87233c634556e09bce2be002ee52b745518665`;
APK Signature Scheme v2 verification passed. The release build was also
inspected and contained only a cleartext-denying base network policy.

### Adaptive Rokid capture and IMU gate — 2026-08-22

The 2026-08-22 direct-sideload app selected the device's exact 1920×1080 JPEG
output, submitted bounded captures on a 200 ms motion-tier schedule, and
analyzed frames only in memory. At that checkpoint, deterministic JVM tests covered
aspect-fit behavior, capture-size selection, unsigned luma, dark/blur
classification, exposure-change suppression, localized-motion sensitivity,
2/5 FPS cadence and hysteresis, timestamp rollback, and reset. The later
3 FPS relaxed default and its physical validation are recorded in the
2026-08-23 section above.

The first physical run exposed that scheduling the next request after capture
completion analyzed only 12 frames in 8.779 seconds. All 12 were correctly
classified dark. After moving scheduling to request-submission time, a second
bounded run analyzed 35 frames in 8.798 seconds; all 35 were still classified
dark, so no image left the gate and the overall three-stream diagnostic
truthfully reported failure. This demonstrates the dark gate and a roughly
5 FPS acquisition ceiling after camera startup, not valid lit-scene output or
a sustained performance benchmark.

The same second run delivered 782 game-rotation snapshots, measured 98.8 Hz
between source timestamps, and observed a 10.1 ms maximum gap. Each emitted
snapshot contains quaternion orientation plus the latest three-axis gyroscope
and gravity-compensated linear-acceleration vectors, individual vector
timestamps, sensor accuracy, and a monotonic sequence ID. This validates local
glasses acquisition only. The repository does not yet continuously transport
those samples into Unity/FMOD; the frame RPC carries only a timestamp-matched
HEAD pose and must not be used as the real-time listener-update path.

A post-cadence-build run produced 36 exact-size Camera2 buffers and 781
orientation snapshots in 8.796 seconds; the IMU again measured 98.8 Hz with a
10.1 ms maximum gap. The unchanged dark view again caused all 36 camera frames
to be rejected. `./scripts/lint` passed repository policy, secret scanning,
configuration validation, Ruff, and mypy. `./scripts/test all` passed 197
Python tests, the native test target, and all 88 Android JVM tests plus both
debug APK builds. Its final WPF solution step could not run because this Ubuntu
installation's .NET 8.0.130 SDK lacks the Windows Desktop SDK targets. The
cross-platform desktop-relay core was therefore run separately: all 156 xUnit
tests passed. This toolchain limitation is not recorded as a WPF pass.

The final debug APK installed for that run is 14,857,132 bytes with SHA-256
`4d2ca1525f50d49aeabf2dabdbc65767b0b5502cdc8d4f0c7e7478934cf9d24d`;
APK Signature Scheme v2 verification passed. The release APK also assembled;
it remains unsigned, as expected without release signing material.

A subsequent lit-scene diagnostic established that isolated still requests
left this vendor HAL at its minimum reported exposure (63,220 ns) and
sensitivity (ISO 50), despite an `AE_STATE_CONVERGED` result. The brightest of
36 analyzed frames had mean luma 12.2/255, so lowering the darkness threshold
would have concealed the capture defect. An exploratory simultaneous YUV plus
JPEG session produced YUV preview frames but stalled every JPEG request. The
implemented repair therefore runs a bounded 640×480 preview-only session for
one second of standard Camera2 3A activity, closes it, and creates a separate
JPEG-only session without closing the camera device.

The final directly sideloaded build passed the eight-second aggregate-only
hardware test: 28 exact 1920×1080 JPEG frames were analyzed, 18 were emitted,
10 were cadence-limited, no frame was rejected as dark or blurry, maximum mean
luma was 110.3, minimum dark-pixel fraction was 0.039, maximum focus score was
1497.9, and maximum motion score was 0.138. The motion tier was active for 12
analyzed samples. Concurrently, 781 orientation samples measured 98.7 Hz with
a 17.7 ms maximum gap, and 64 microphone chunks contained 119,739 nonzero
samples with peak absolute amplitude 979. These are aggregate diagnostics;
the app did not persist or log image, PCM, or raw IMU payloads. It stopped its
process and service after the bounded run.

The updated Android suite contains 31 host tests, 58 Rokid tests, and one
protocol-vector test (90 total). Focused Rokid unit tests, Android Lint, and
debug assembly passed. The installed debug APK is 14,857,132 bytes with
SHA-256 `4fc48dcb0dc1482a645c894f75e33ee5adac4abe35ffc38462b209f23bae840e`;
APK Signature Scheme v2 verification passed.

## Accessibility evidence

Android host source and lint cover labeled controls, logical focus, live-region
status, non-color state text, TalkBack-aware speech suppression, and a bounded
text fallback when spoken cue delivery is unavailable. The non-display glasses
client has audio/haptic output seams and an inspectable nonvisual renderer.

The Windows WPF shell uses standard controls, keyboard navigation, accessible
names/help text, live status, predictable focus restoration, and optional
speech output. Core behavior is independently available through textual status
and the headless demo.

Poco UI semantics were inspected with UI Automator while TalkBack was enabled,
and physical Android audio/haptic dispatch was observed in system logs. Manual
TalkBack task completion, Windows UI Automation, high-contrast/scaling, JAWS,
NVDA, perceived glasses audio localization, and BVI human-factors acceptance
were not performed. Those checks remain release-blocking for any
supported-device or assistive-use claim beyond this engineering baseline.

## Privacy, security, and packaging evidence

- plaintext gRPC is restricted to loopback development/test configuration;
  production configuration requires TLS and refuses the bundled synthetic
  worker;
- identifiers are ephemeral and format-bounded; messages, frames, queues,
  sessions, histories, rates, timeouts, and shutdown waits are bounded;
- normal logs redact credential-, path-, endpoint-, and image-shaped content;
  raw frames, screens, speech, and accessibility content are not logged or
  retained by default;
- QUICK adapters require explicit capture or publication approval and remain
  optional;
- repository policy rejects secrets, signing material, model weights, capture
  files, unsafe production examples, generated build output, private source
  paths, and unfinished core markers;
- complete MIT and Apache-2.0 license texts and exact direct dependency notices
  are included in the repository and Python distribution roots; and
- Python and .NET dependency vulnerability queries reported no known issues in
  resolved third-party packages at validation time.

## Brand evidence

Three external source assets were visually inspected. Only source-relative
names and hashes are recorded; no source media was copied because public
redistribution rights were not established.

| Evidence | SHA-256 | Selection |
| --- | --- | --- |
| `CONCEPTFlow_banner_work_20260817_001806/final/CONCEPTFlow_Banner_Master_NoLogos_2560x900.png` | `37d1eb260d1e9f60ee141e9484d68c27405e2c87a902a20c2b15b21ea38f4fe4` | final no-logo banner |
| `CONCEPTFlow_banner_work_20260817_001806/final/CONCEPTFlow_Banner_BespokeB2B_2560x900.png` | `513e3d7aa95b6b1d407981c3d60322a8a52bb440548eb041290e35fa70471a50` | final with-logos banner |
| `CONCEPTFlow_banner_work_20260817_001806/raw/conceptflow-master-logo.svg` | `5d46c42fe6375a0a57150505dd5b8616b7278a9d83e263088a71ed1f8b47ad15` | strongest avatar candidate; no separate finalized avatar was found |

See [`docs/BRAND_ARCHITECTURE.md`](docs/BRAND_ARCHITECTURE.md) for the visual
rationale and accessibility-qualified design tokens.

## CI coverage

The CI workflow uses read-only default token permissions and immutable action
commit pins. It checks repository policy, formatting, secret detection,
dependency audits, protocol generation/interoperability, Python tests and
packages, the synthetic vertical slice, Android lint/tests/APKs, .NET
restore/build/tests/demo, and CPU native tests. A separate manual/self-hosted
workflow exposes CUDA-aware and physical-device gates without pretending a
GitHub-hosted runner has NVIDIA or Rokid hardware.

Workflow YAML parsed successfully and repository policy verified all action
pins. `actionlint` itself was not installed on the release host. Hosted CI
status is recorded only after the repository is published and the workflows
actually execute.

## Unvalidated release gates

- long-duration and adverse-network execution of the authenticated
  Rokid-to-Poco transport, including forced reconnect, cancellation, roaming,
  and failure recovery;
- WebRTC signaling or a WebRTC data-channel adapter;
- a registered production worker, model weights, real CUDA kernel/inference,
  and multi-GPU correctness, failover, load, thermal, and performance tests;
- production-model glass-to-cue latency distributions and long-duration
  reconnect/roaming tests;
- manual TalkBack, Windows 11, UI Automation, JAWS, NVDA, and BVI usability;
- authenticated deployment, certificate rotation, Android/Windows signing,
  installer/package distribution, external penetration testing, and a
  production security review.

Passing this baseline provides no navigation, obstacle-avoidance, completeness,
or safety guarantee. See [`docs/ACCESSIBILITY.md`](docs/ACCESSIBILITY.md),
[`docs/PRIVACY_ARCHITECTURE.md`](docs/PRIVACY_ARCHITECTURE.md),
[`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md), and
[`docs/LATENCY_BENCHMARKING.md`](docs/LATENCY_BENCHMARKING.md) before extending
the system toward physical or production use.

## RV203 two-finger input boundary — 2026-08-26

- The official Rokid Bare Metal receiver/sample was inspected, then its action
  names and ordered-broadcast assumption were tested on the attached
  non-display RV203 rather than transferred from the sample's display target.
- A simultaneous Linux `getevent` and application `logcat` run correlated five
  broad two-finger holds with `KEY_PROG2`/scan 149 and five
  `ACTION_SETTINGS_KEY` deliveries. Every application delivery was
  non-ordered; none was aborted or consumed.
- Fingertip-only attempts were frequently classified by the firmware as
  `KEY_PROG1` plus ordered `ACTION_AI_START`. CONCEPTFlow leaves that built-in
  Talk-to-AI path untouched.
- The OEM receiver queried `settings_shortcuts` and reported it disabled for
  each two-finger-hold trial, then performed no shortcut action. This is a
  current-device precondition, not a public API guarantee. Enabling that Hi
  Rokid setting would reintroduce an OEM collision that a sideloaded app cannot
  suppress because the broadcast is non-ordered.
- Rokid Node now models the accepted gesture as one semantic
  `TWO_FINGER_LONG_PRESS`/`TRIGGERED` event. Focused packetizer, hub, protocol,
  and Android-ingress JVM tests passed. The custom event remains command-free
  and the older gesture-command gate remains observe-only pending an enforceable
  Shortcuts-disabled policy and a complete live Rokid-to-Poco event trace.

## Persistent Rokid-to-Poco node validation — 2026-08-25

- The operator confirmed physical forward-swipe plus quick one-finger
  double-tap activation and local Rokid Node audio output.
- Focused Android transport/host/Rokid JVM tests, both debug APK builds, both
  Android lint tasks, and `git diff --check` passed.
- Replacement installation preserved the Rokid private prerecorded voice set,
  pairing state, accessibility observer, and gesture-command gate. Poco
  replacement installation preserved TalkBack and all three private QNN
  artifact slots.
- A representative authenticated lease produced 82 Rokid camera frames and 780
  selected IMU batches. Android Node independently reported all 82 frames, all
  780 batches, and 1,552 accepted pose samples with zero pose rejection.
- Android Node's cumulative accessible status reached 252 frames, 2,362 IMU
  batches, and 4,749 accepted pose samples across three bounded leases.
- While the Poco display remained in Doze, its connected-device foreground
  service stayed active. Rokid completed an 88-frame/796-batch lease and
  authenticated the next lease. A deliberate Android APK replacement caused a
  bounded network interruption; the clients subsequently reconnected.
- No foreground-start rejection, camera denial, or sensor-source failure was
  observed after the Rokid foreground transition repair. Raw frames, PCM, and
  individual IMU values were neither logged nor retained for this validation.
- QNN inference did not run in this specific automatic-environment trace because
  environment selection remained pending. This result validates transport and
  pose ingestion, not model accuracy, depth calibration, localization, battery
  endurance, thermal behavior, or BVI usability.

## In-memory sensor-backbone refactor — 2026-08-25

The pre-change sensing contract was frozen in machine-readable and human-readable
form before the handoff changed. Camera still acquires exact 1920×1080 hardware
JPEG, runs the existing 160×90 darkness/blur/motion/cadence gate, then performs
the existing aspect-preserving 640-high centered 640×640 crop. The production
handoff now publishes packed RGB8 from one processing slot plus one
latest-pending slot. IMU, microphone and raw touch preserve their previous
selection and authorization semantics. The legacy JPG/WAV/JSON route is an
explicit disabled-by-default diagnostic.

A current-APK standalone Rokid regression produced 18 analyzed camera samples,
16 gate-admitted frames, 797 IMU samples at 98.7 Hz, and 16 separately
authorized microphone chunks / 65,536 bytes. The microphone lease ended after
two seconds. Camera measured 3.082 analyzed FPS and 2.727 emitted FPS in the
active interval, with request-to-image p50/p95 443.0/622.8 ms, processor
p50/p95 41.1/56.8 ms, zero darkness/blur rejection, zero capture failures, and
zero outstanding requests at shutdown. Nine late Camera2 result callbacks were
observed after image/request retirement; the run closed all resources, but that
HAL callback ordering remains a diagnostic rather than being reported as zero.

A corrected 30-second production-link run reconstructed 76 camera frames and
received 762 IMU batches / 1,463 samples. One camera frame was rejected by the
host freshness gate, 54 correlated frames completed QNN execution and 21 were
deliberately replaced by newer pending work. No transport queue drops or
disconnect occurred. HTP graph p95 was 89.6 ms for YOLOE-26S segmentation and
71.0 ms for outdoor Depth Anything V2 Metric 392; executor p95 was 310.1 ms.
Capture-to-receive p95 was 687.2 ms, aggregate end-to-end p95 1,030.6 ms, and
clock uncertainty p95 3.4 ms. These are measurements of that scene and build,
not guaranteed budgets or accuracy evidence.

The matching legacy-spool run created 80 camera and 692 IMU records. It wrote
6,843,275 artifact bytes, 35,406,382 JSON-manifest bytes and 46,751,870
recovery-state bytes in 30 seconds. Android pulled 793 poses, 717 already stale;
no camera completed before shutdown, and 47 camera plus 302 IMU records remained
backlogged. The comparison found and repaired a pre-session recovery-index race.
Captured diagnostic files were removed after aggregate metrics were retained.

Validation after the repair passed 220 Python tests, native CTest, all Android
JVM tests and both generic debug APK assemblies. The opt-in QNN JNI build also
passed and preserved a separately named QNN-enabled APK. FMOD Studio 2.03.14
reopened, validated and rebuilt both project platforms; its CLI reported two
events, two anchor voices, six field voices and limiter presence. The Unity
6000.3.22f1 headless rerun could not start because the machine currently has no
valid editor entitlement, so prior Unity evidence was not upgraded. The
cross-platform desktop-relay core passed all 156 tests; the WPF solution remains
unbuildable on this Ubuntu SDK because WindowsDesktop targets are absent.

The latest exact-hash-verified Rokid APK is installed with the production RAM
handoff selected and its runtime stopped. The corresponding Poco APK and final
current-build 10-minute soak remain pending because the Poco was not ADB-visible
at this checkpoint. No VLM artifact or hand-tracking runtime/model was found, so
neither is represented as implemented or accelerator-validated.

The Poco subsequently became ADB-visible and the exact QNN-enabled APK was
installed and hash-verified. A full 600-second outdoor-profile soak reached its
time limit and completed authenticated request/write/drain/ack shutdown. Rokid
observed and queued 1,781 camera frames with no camera-queue drop, and observed
59,200 IMU samples, queueing 27,156 selected samples in 14,641 batches with no
IMU-queue drop. Android executed 696 successful correlated inferences in that
session. Graph p95 was 82.9 ms segmentation and 71.3 ms depth; executor p95 was
287.3 ms; capture-to-receive p95 was 748.8 ms; end-to-end p95 was 1,039.8 ms;
clock uncertainty p95 was 5.1 ms. Twelve memory samples were non-monotonic:
Poco PSS ranged 544,586–562,871 KiB and ended 554,122 KiB; Rokid PSS ranged
39,191–71,677 KiB. Poco battery temperature rose from 33.5 to 34.7 C. These are
aggregate engineering observations, not energy, thermal-throttling, accuracy or
BVI-usability conclusions.

That soak also showed that the 750 ms camera-ingress freshness threshold sat on
the physical capture/transform/transport latency distribution and rejected too
many useful frames. The host-only threshold was changed to a measured bounded
1.20 seconds; the separate 1.50-second post-inference freshness gate remains.
On the next exact-APK 30-second run, Android reconstructed 76 frames, rejected
zero at ingress, attempted 57 inferences, completed 56, rejected one result that
became stale after inference, and deliberately replaced 19 pending frames.
Authenticated close and zero transport queue drops still passed. Normal remote
completion was also separated from unexpected network/timeout interruption
accounting; this presentation-only correction has deterministic unit coverage.

With that final accounting build, a clean outdoor run reconstructed 79 frames,
rejected none at ingress, completed 61 of 62 inference attempts and reported
zero link interruptions after authenticated close. A separately restarted
indoor run selected the Hypersim 392 HTP graph, reconstructed 75 frames,
rejected none at ingress, completed 58 of 59 inference attempts, accepted all
1,242 poses and again reported zero clean-close interruptions. Indoor graph p95
was 91.2 ms segmentation and 72.7 ms depth; executor p95 was 298.2 ms.

A deliberate Android Node process stop eight seconds into a bounded Rokid lease
then listener restart exercised peer recovery. Rokid reported two authenticated
sessions, five bounded connection attempts while the host was absent, two
producer starts, 39 camera frames and 821 selected IMU samples with zero
camera/IMU queue drops, then reached its original deadline and authenticated
close. The replacement Android process recorded one genuine link interruption;
normal completion did not add another.

## Android Wi-Fi Direct data-plane validation — 2026-08-26

Strict `wifi_direct_required` provisioning was installed on the attached Poco
F7 Ultra (Android 16/API 36) and non-display Rokid AI Glasses Style (Android
12/API 32). Both devices advertised `android.hardware.wifi.direct`. Android Node
created the group, was reported by both frameworks as group owner, and Rokid
Node joined as a client. Both `p2p0` interfaces were up and the negotiated radio
frequency was 5180 MHz. The dynamic group-owner address—not the compatibility
address in schema 1—was supplied to the existing pinned mutual-TLS lanes. No
address, peer identifier, group name, passphrase, certificate, frame, audio
content, or IMU value was retained in this evidence.

The first join exercised Android's system-owned connection confirmation. The
applications do not and cannot bypass that security boundary. The established
OS group then survived replacement installation and routine service stops.
With the group retained, two measured Rokid application reconnects reached an
authenticated streaming state in 2,320 ms and 2,144 ms respectively, without a
new dialog. Stopping Android Node preserved the group on both peers; after the
listener and then Rokid Node restarted, streaming resumed on the retained group.

A complete 600-second P2P soak reached its time limit and completed the
authenticated request/write/drain/ack shutdown. Rokid admitted and queued 1,753
camera frames and observed 59,206 IMU samples, selecting 31,327 samples in
16,572 batches. The completed session reported zero camera, IMU, microphone,
and touch-queue drops. Android reconstructed 1,753 camera frames and received
16,571 IMU batches / 31,325 samples; it accepted 31,302 poses and rejected 23
through the existing validation gate. A ten-second on-demand microphone lease
delivered 74 chunks / 303,104 bytes with zero audio transport drops and no PCM
persistence. The bounded Android audio timeline evicted 58 old chunks after
receipt because no downstream audio consumer drained the diagnostic run; this
is observable retention backpressure, not loss on the P2P wire.

The same run measured camera request-to-image p50/p95/p99 at 450.4/483.1/489.7
ms, camera acquisition p95 at 2.7 ms, gate/resize p50/p95/p99 at
38.6/43.5/44.4 ms, and listener p95 at 3.7 ms. These measurements describe this
build and scene only. QNN inference was not exercised because the currently
installed generic debug build contains no ready private QNN runtime. No physical
touch gesture was generated during this P2P soak; the existing typed touch path
and its prior hardware evidence remain unchanged. TalkBack remained enabled on
the Poco before and after installation and validation.

The final rebuilt APKs were then replacement-installed, reprovisioned in strict
Wi-Fi Direct mode, and verified byte-for-byte against the installed packages.
An incomplete pre-session socket timed out before the bounded Rokid run began;
the persistent Android listener contained that failed attempt and retained the
OS P2P group. A subsequent bounded run reached authenticated streaming in
4,074 ms without re-pairing or a new confirmation dialog. It delivered 81
camera frames and 841 IMU batches / 1,620 selected samples with zero transport
queue drops, then completed authenticated request/write/drain/ack shutdown.
Both devices still reported the P2P group active afterward, and TalkBack
remained enabled. This validates routine retained-group application recovery;
device-reboot group reconstruction remains a separate, unexecuted test.

Finally, Rokid Node was armed in its persistent sensor-off rendezvous mode and
an active session was subjected to a controlled Rokid Wi-Fi radio off/on cycle.
The existing session reported network loss, the P2P client rediscovered the
Android-owned group, and mutual-TLS streaming resumed automatically 9,707 ms
after the radio was re-enabled. No re-provisioning or new system confirmation
was required. The run was then stopped through the normal idle-disable path;
Rokid capture and its foreground service were inactive, while Android Node
remained available for the next session.

## BVI330 automatic HTP vision pipeline — 2026-08-27

The canonical local BVI source produced an ordered 330-class closed vocabulary
and a 330-row catalog. `KnownDimensionVectorTable` generated exactly 660
immutable records: one 0.6096 m and one 2.4384 m angular/dimension anchor for
every class. Of those classes, 170 have explicit nonzero calibration weight;
family-default rows remain zero-weight and cannot manufacture metric evidence.
The vocabulary SHA-256 is
`f4d5aee2124ee9a65f337337004062b15273939ff0ce7f96740fc3cb28d6a9a6`.

The externally governed YOLOE-26S segmentation checkpoint was exported with
those exact prompts. The ONNX graph retained input `1×3×640×640`, detection
output `1×300×38`, and prototype output `1×32×160×160`; its SHA-256 is
`3f3dac0b5708c6641247eb582101fc48e2d109aa028669a00d662f21b2a4cff0`.
QAIRT 2.48.40 produced the AArch64 FP16 library whose pinned SHA-256 is
`4631e169dd0a335e48f9f3bb039be414810450ae232a1d1d1212bd457f954fd7`.
Weights, SDK binaries, and generated private model artifacts were not added to
Git.

No official Qwen2.5-VL 2B model exists, so automatic routing uses the real
Qwen3-VL-2B-Instruct Q4_0 identity instead of fabricating one. The pinned GGUF
and projector were provisioned privately. Device logs showed GenieX 0.4.0
loading its V79 HTP/CDSP backend. Concurrent GenieX and QNN dispatch initially
failed the DSP; a fair process-local permit plus app-private kernel file lock
now serializes graph execution across Android Node's main and `:local_vlm`
processes. A coroutine thread-migration bug in the first lock revision was
found physically and repaired by replacing the thread-affine lock with a
semaphore.

The final automatic 30-second Rokid-to-Poco run classified the scene twice as
`INDOOR` (about 6.8 seconds cold and 4.2 seconds warm), selected the 392×392
Hypersim profile, and completed 16 of 24 full YOLOE-plus-depth attempts without
a DSP or application crash. It reconstructed 79 frames, deliberately replaced
55 stale pending frames, accepted 1,576 of 1,576 IMU pose samples, and reported
zero Rokid camera/IMU transport-queue drops. Graph p95 was 84.6 ms segmentation
and 33.5 ms depth; executor p95 was 324.1 ms. End-to-end p95 was 1,351.4 ms,
including 814.9 ms capture-to-receive. One completed result crossed the bounded
freshness deadline and was rejected.

A separate clean forced-indoor run did not bind the VLM and completed 47 of 48
inference attempts from 75 frames, with 27 deliberate latest-frame
replacements, 1,528 accepted IMU samples, and zero Rokid transport-queue drops.
Its graph p95 was 147.9 ms segmentation and 75.8 ms depth; executor p95 was
435.6 ms. This run followed several HTP tests and is not a cold-device thermal
benchmark.

Automated validation covers ordered catalog loading, the 660-record invariant,
cross-thread HTP lease exclusion, delayed sparse VLM evidence, two-confirmation
selection, persistent two-frame scene-change admission with no periodic stable
refresh, 90-second profile reuse,
two/eight-foot angular-envelope admission, bounded native-metric correction,
outlier rejection, and the stable named-output QNN ABI. The Android JVM suite
passed 181 tests; the focused Python model/JNI contract suites passed 14 tests.

This evidence validates execution and orchestration, not YOLO task accuracy,
indoor/outdoor accuracy, monocular metric accuracy, or safe mobility. Current
camera intrinsics remain `DERIVED` with unreported parameter uncertainty. A
Camera2-sourced HEAD←CAMERA rotation is now installed for the rigid Android
sensor/head proxy, allowing explicitly unquantified rotation-only propagation;
physical translation and anatomical alignment remain unavailable. Reboot-time
Wi-Fi Direct group reconstruction remains explicitly deferred by the user.

## YodaOS runtime-pressure and radio recovery — 2026-08-27

Read-only system logs isolated the failure previously presented as repeated
socket timeouts. Android's low-memory killer terminated a vendor payment helper;
concurrent dead binder calls then terminated the persistent Sprite assist
service. On restart, that service loaded its private Wi-Fi-disabled preference
and asked Android to disable Wi-Fi, which removed the P2P interface. Rokid Node
remained alive and was unable to reconnect because no radio data plane existed.

The updated resolver was replacement-installed on both devices. During a
forced Rokid Wi-Fi off/on cycle it emitted one `WAITING_FOR_RADIO` transition,
made zero failing P2P calls during the observed disabled interval, retained the
Rokid Node process, detected restoration, re-formed the phone-owned group,
reauthenticated mutual TLS, and resumed streaming as session 2. This validates
application recovery after platform radio restoration; it does not claim that
an unprivileged app can override a YodaOS or user decision to keep Wi-Fi off.

The same build removed full-resolution post-gate decode and redundant crop,
pixel-array, protobuf-frame, and per-chunk payload copies. A 10-minute bounded
run spanning the forced outage completed with 1,703 queued camera frames,
26,282 queued selected IMU samples, one camera drop, zero IMU drops, 19 bounded
latest-worker replacements, and authenticated close. That run preceded the
last per-chunk-copy removal. After that final change, a separate 30-second run
observed 81 camera frames, queued 80, queued 1,277 IMU samples, had one camera
drop and zero IMU drops, and closed with authenticated drain/ack. Sampled Rokid
RSS ranged from 88,724 to 121,212 KiB; `oom_score_adj` was 0 while active; no
matching large-object-GC message appeared in the bounded final run. Camera
transform p50/p95/p99 was 105.9/117.2/143.3 ms. These results are device/run
measurements, not indefinite uptime, battery, or cross-firmware guarantees.

A later exact-build 30-second diagnostic received 18 frames and 401 IMU samples
before the glasses process disappeared. `ApplicationExitInfo` identified the
cause as `LOW_MEMORY`/`TOO_MANY_EMPTY_PROCS`; no application exception was
recorded. Android Node's `FRAMING_TRUNCATED_RECORD` was therefore a secondary
mid-record disconnect. The one-shot command is intentionally nonpersistent, so
it did not authorize same-boot reconstruction.

The production path was then validated separately. Android Node was placed in
automatic listen mode and Rokid Node was armed with `idle-enable`. PID 14757 was
terminated with a same-UID signal, without force-stopping the package. YodaOS
started replacement PID 15078 in about 1.3 seconds; the accessibility service
requested the visible same-boot broker; the runtime reported streaming about
4 seconds after termination. Host counters advanced from 133 to 201 camera
frames and from 2,146 to 3,237 IMU samples. This proves same-boot process and
transport reconstruction for the persisted production path; it does not prove
that LMKD can never select the process or that reboot reconstruction is already
validated.

## Native 648 camera and bounded HTP pipeline soak — 2026-08-27

The exact rebuilt Rokid APK SHA-256 was
`76e9aaefdff98e76d76642742ed6488b0e3b55368b69754ed4d89cc024b50d6c`
(10,384,743 bytes). The exact QNN-enabled Android APK SHA-256 was
`e3f057a5c6d7adc32ffde104ac641a788a78f2b94c4ce808a01fd5981db5d3f6`
(105,003,492 bytes). Both replacement installs succeeded and retained private
app data; TalkBack remained enabled on the Poco.

The automatic physical run used the private-LAN configuration only to bypass a
stale OS-owned P2P group during this validation. It ran from 15:20:10 through
15:30:11 and reached the exact 600-second application deadline. Rokid observed
and queued 1,759 camera frames with 33,421 bounded chunks and no camera drop. It
observed 59,088 IMU samples and queued 29,388 selected samples in 15,902 batches
with no IMU drop. Microphone remained correctly disabled and no raw frame, IMU,
or audio content was logged or persisted. Authenticated request/write/drain/ack
closure completed.

The native 648×648 YUV path sustained approximately 2.93 relaxed FPS. Its
request-to-image p50/p95/p99 was 349.4/392.9/445.0 ms; direct image acquisition
p95 was 1.62 ms; native gate/resize p50/p95/p99 was 54.9/67.3/74.9 ms; and the
listener p95 was 11.1 ms. Camera request backpressure, supersession, unmatched
images, capture failures, and late callbacks were all zero. Rokid PSS samples
were about 55–61 MiB and did not show monotonic growth. The 5 FPS motion tier
was not physically induced in this run.

Android reconstructed all 1,759 frames and rejected none as stale at ingress.
It completed 1,362 of 1,367 QNN attempts, deliberately replaced 43 pending
frames, and rejected one completed stale result. All 29,388 normalized IMU
poses were accepted. End-to-end p95 was 935.7 ms; capture-to-receive p95 was
307.5 ms; segmentation graph p95 was 110.6 ms; depth graph p95 was 39.6 ms;
executor p95 was 373.4 ms; and clock uncertainty p95 was 5.0 ms. Poco PSS
samples remained about 528–545 MiB after model initialization. Device logs
proved QAIRT 2.48.40 loading `libQnnHtpV79Skel.so` through CDSP and GenieX
loading its V79 Hexagon backend in the isolated VLM process.

Qwen3-VL completed its full image-plus-token prewarm and then classified two
startup frames as `INDOOR`; the two-confirmation policy selected the Hypersim
392 Depth Anything V2 Metric graph. Cross-process HTP leases did not overlap.
The current 8.5-second VLM completion window is cooperative, not a hard native
kernel-preemption guarantee. Direct motion, occlusion, or rapid approach has
precedence over routine stale-depth/track maintenance and requests VLM
cancellation; deterministic mixed-signal and lazy-job-cancellation tests cover
those races.

Two subsequent exact-build 30-second sessions each delivered 85 camera frames,
ended with authenticated closure, and emitted no dead-handler warning. The
Camera2 recovery path now preserves the authenticated session and IMU/mic/touch
producers, uses a controller-owned frame sequence across camera replacements,
bounds restart and thread-join waits, and records unambiguous Camera2 error
domain/code/symbol telemetry. Deterministic tests prove frame 158 is followed
by 159 and accepted by host ingress. Because the physical HAL did not fail
during these final runs, an actual on-device camera-only restart was not
observed and is not claimed.

After testing, both nodes were stopped and their private configuration was
restored to strict `wifi_direct_required`. Reboot-time P2P reconstruction remains
deferred as previously requested. Current camera-to-head translation and
factory-calibration uncertainty remain unverified, so physical status did not
claim translation-based world anchoring or calibrated spatial accuracy.

A final audit then found that the disabled diagnostic spool still accepted only
the former JPEG camera payload. It now validates the production 640×640 packed
RGB8 descriptor, stride, byte count, and hash before encoding a bounded JPEG;
the stored descriptor is rewritten as JPEG with the resulting byte count and
hash. Malformed inputs fail categorically and are counted rather than silently
persisted. All 242 Rokid JVM tests, Rokid lint, and Rokid debug assembly passed
after this repair. The rebuilt and replacement-installed Rokid APK is
`d1c185a43c30ce5e2891312f7933a9df98bf354fbddfe72fc20ae5cb50c4d32a`
(10,401,127 bytes). This post-soak change affects only the explicitly enabled
diagnostic persistence route; the 600-second RAM-streaming evidence above
belongs to the preceding exact soak artifact and was not relabelled.
