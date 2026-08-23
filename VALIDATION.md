<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Validation evidence

This ledger records what was actually inspected or executed for the initial
public baseline on 2026-08-21. A build, unit test, cross-target compilation, or
synthetic demonstration is not presented as physical-device, production-model,
accessibility, safety, or performance validation.

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
