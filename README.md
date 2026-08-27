<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# CONCEPTFlow

Machine Intelligence. Human Architecture.

A suite of tools running in hardware you already own.

Android. Rokid. Windows.

Non-Visual Experience. Gamified. For spatial audio.

## Machine Perception Layer

It's just supplemental awareness.

Map. Morph. Move.

CONCEPTFlow Machine Perception Layer (MPL) is a protocol-first, multi-host
reference implementation for turning bounded visual context into correlated,
inspectable assistive cues. It connects a versioned protobuf/gRPC contract to a
Python host and synthetic worker service, Android host and glasses applications,
a .NET 8 Windows relay, and C++20 scheduling primitives with a CUDA-aware build
boundary.

MPL is supplemental environmental-awareness infrastructure. It is not an
autonomous safety authority, navigation system, medical device, emergency
system, or replacement for a mobility aid, trained judgment, or direct
observation. “Near-real-time” and “zero-touch” are design goals for selected,
intentionally activated workflows. They do not mean zero physical latency,
perfect perception, continuous availability, or any safety guarantee.

## What this release is—and is not

It is:

- a typed v1 contract for capability negotiation, bounded frame requests,
  leased camera/IMU/microphone envelopes, correlated results, multimodal cues,
  provenance, errors, and health;
- a deterministic CPU/no-device gRPC demonstration of reconnect, cancellation,
  timeout, stale rejection, error propagation, overload, recovery, and
  assistive-only rendering;
- reusable validation, routing, correlation, scheduling, redaction, consent,
  and accessibility-oriented components across Python, Kotlin, C#, and C++;
- a bounded, direct private-WLAN TLS 1.3 mutual-TLS transport from the Rokid
  client to the Poco host, with independent realtime/control and camera lanes,
  typed wearer-gesture intents, correlated Android Node commands, and command
  acknowledgements;
- buildable Android, .NET, native, and Python baselines with explicit hardware
  and transport boundaries.

It is not:

- a redistribution of trained perception weights, proprietary inference
  runtimes, or a production inference service;
- a production-ready glasses-to-phone transport or a WebRTC implementation;
- a dependency on Rokid companion apps, client secrets, vendor SDK AARs, or a
  redistribution of proprietary weights, camera captures, or private media;
- evidence of Windows/JAWS/NVDA, BVI usability, localization accuracy, or safety
  validation.

## Architecture

```text
Glasses capture / desktop request
             |
             v
bounded FramePayload --> host validation + route --> local or gRPC processor
                                                      |
                                                      v
assistive output <-- cue scheduler <-- correlated PerceptionResult
```

The contract keeps backend control, request/result, and health semantics in gRPC:
`Negotiate`, `ProcessFrame`, and `Health`. The current unary frame RPC is a
bounded integration baseline. Transport-neutral glasses stream leases, bounded
camera chunks, absolute IMU batches, microphone chunks, a Rokid packetizer, and
a Poco-side latest-wins ingress are implemented. The direct private-WLAN path
uses two TLS 1.3 mutual-TLS sockets, exact peer public-key pins, per-lane
ordering, and a single-use camera-lane ticket. Its public-certificate pairing
helper never exports the Android Keystore private keys. The live path grants
camera and IMU only by default. During an authenticated active session, a
separate accessible Android control can request one mic-only window capped at
ten seconds; PCM is latest-wins in memory and is neither logged nor persisted.
In transport v1 this is an authenticated sublease of the already active
camera/IMU session: the two-lane handshake still requires its camera lane, and
microphone capture cannot yet be requested as a standalone session or without
initializing the Android Node's live Machine Vision runtime.
Rokid activation/sleep gestures and the Android Node's accessible brand-playback
control use typed, replay-guarded, session-bound command round trips on that
same TLS lane. They do not use ADB Wi-Fi. Bluetooth/BLE wake and discovery are
not implemented yet; operating-system pairing alone is not application-level
authentication.
The code and local
tests pass, and two consecutive no-reinstall physical Rokid-to-Poco runs
completed on 2026-08-23 with HTP inference and authenticated shutdown. This is
bounded development evidence, not production deployment, representative model
accuracy, long-duration thermal, or adverse-network validation.

Before rendering, hosts validate identity, encoding, dimensions, bytes and
digest; apply route and privacy policy; bound in-flight work; enforce deadlines
and cancellation; and reject unknown, mismatched, stale, or out-of-order
results. Cue schedulers enforce TTL, priority, capacity, cooldown,
supersession/cancellation, verbosity, and available modalities.

The Android Machine Vision boundary uses fixed-vocabulary YOLOE-26S instance
segmentation for class semantics and mask identity, then runs exactly one
selected indoor/outdoor metric-depth graph: 392 balanced, 336 low-power, or
518 for sparse reference/calibration work. The Rokid source captures at about
3 FPS relaxed and up to 5 FPS after meaningful change; Android independently
admits semantic plus depth HTP work at 1 FPS stable, 3 FPS for material
motion/uncertainty, and 5 FPS only for urgent correction. Both ingress and
pending inference are latest-only and bounded.
High-rate pose/IMU updates can rotate existing short-lived static-world anchors;
translation additionally requires matching explicit VIO or external-tracking
origin evidence. Anchoring defaults off and requires explicit confirmed-static
per-instance evidence; class or geometry alone is insufficient, and later
dynamic/unknown evidence evicts a reused ID. Pose/IMU updates cannot create
depth, objects, translation, absolute scale, or observations of moving or newly
visible scene content. Between keyframes, bounded geometry/depth/IMU prediction
is labeled as uncorrected state: no optical flow or appearance encoder is wired,
and the implementation does not claim classic DeepSORT. New authenticated
sessions, reconnects, and stop transitions clear session-owned scheduler,
tracker, pose/extrinsic, profile, and previously published head state.
The bounded direct-live QNN test is narrower: it runs YOLOE first and exactly
one automatically or manually selected indoor/outdoor 392 graph, with no CPU,
336, or 518 fallback. The metric-depth graph's native scalar camera-frame depth
does not consume camera intrinsics. Accepted factory or narrow target-specific
`DERIVED` intrinsics add pixel-to-ray geometry; missing or contradictory
intrinsics suppress that spatial projection, not metric inference. Target
metric error and calibrated spatial/angular accuracy remain unquantified.

Read [Architecture](docs/ARCHITECTURE.md) and [Protocol](docs/PROTOCOL.md) for
the complete flow and source-level boundaries.

### Map. Morph. Move.

- **Map** fuses timestamped BODY, HEAD, SENSOR, and WORLD transforms with
  metric geometry, uncertainty, semantic mask/depth samples, and stable tracks
  into a short-lived local `SpatialMap`.
- **Morph** converts nearest body-surface contact and bounded surface extent
  into an Intrusion Anchor, a four-bank concentric Envelopment Field, a haptic
  transition, sparse auditory icons, and optional scene-description requests.
- **Move** applies relative-motion activation, stationary restraint, freshness,
  similarity, capacity, TTL, and priority so output follows relevant change
  without narrating everything.

The executable reference is in `packages/perception-core`; Unity/FMOD authoring
and deterministic scenes are in `labs/unity-fmod-perception-lab`. The Sound
Bubble is a calibrated body-surface offset field with an exact default radius of
`0.9144` m, not a head-centered sphere.

## Verified baseline versus planned work

| Area | Verified locally | Not yet verified or implemented |
| --- | --- | --- |
| Python | 223 tests; protocol regeneration; session-gated bounded image decode; synthetic gRPC reconnect, cancellation, timeout, correlation, stale rejection, worker error, fair bounded queue/backpressure, recovery, assistive-only cue, and headless Map/Morph/Move slices; three wheels/sdists built and isolated-imported | Real model worker and production serving |
| Android | Named Rokid Node and Android Node APKs; fixed 330-class YOLOE instance segmentation; fully prewarmed, persistent-change-gated Qwen3-VL-2B indoor/outdoor classification serialized with QNN HTP work; selected 392 balanced Depth Anything V2 Metric indoor/outdoor execution; 660 two-range known-dimension records with conservative per-observation correction; bounded class/mask/depth tracking between keyframes; timestamped IMU ingestion; Camera2-sourced rotation-only camera→rigid-head propagation; opt-in app-process QNN JNI path; strict phone-owned 5 GHz Android Wi-Fi Direct group with pinned mutual-TLS lanes; aggregate-only live status; physical QAIRT 2.48.40 QNN HTP V79 execution on the Poco | Empirical camera-intrinsic calibration, camera-to-head translation/anatomical alignment, representative task/depth/environment accuracy, reboot group reconstruction, sustained thermal profiling, manual TalkBack/BVI acceptance |
| Rokid | Nonvisual standard-Android APK for AI Glasses Style (Non-Display); direct ADB install/control; exact native 648×648 YUV acquisition with the validated darkness/blur/motion gate and 640×640 RGB output; bounded three-request 3 FPS relaxed/5 FPS meaningful-motion capture policy; bounded camera-only recovery with session-continuous frame IDs; observed 4032×3024 equal pixel/active/pre-correction arrays, CENTER_ONLY crop, NONE-only rotate-and-crop, and OFF-only OIS; narrow-fingerprint `DERIVED` intrinsics with request/result consistency guards; nominal 100 Hz fused head-IMU acquisition with duplicate suppression, ≤20 ms batches, and ≤1 s absolute refresh; explicit short microphone lease; strict Wi-Fi Direct client transport; ten-minute P2P and current exact-build private-LAN camera/IMU soaks with authenticated close and zero session transport-queue drops; no vendor SDK | Sustained physical 5 FPS motion-tier validation, forced physical Camera2 recovery, empirical camera calibration, Unity/FMOD listener interpolation, open-ear localization/listening tests, on-glasses haptics, reboot recovery, and sustained thermal/energy validation |
| Windows | .NET 8 Core, WPF, headless demo, and xUnit; restore/build including WPF cross-target, 156 tests including the shared wire vector, consent-gated demo on Ubuntu | Manual Windows execution, JAWS, NVDA, real capture and endpoint validation |
| Native/CUDA | Strict Release build, native test executable’s 15 cases, demo, sanitizers, CUDA-aware configure/build on CUDA 12.0 | CUDA kernel, model loading/inference, GPU correctness/performance |
| Spatial perception | Headless geometry → body-surface field → bounded manifold → four-bank weights → two-layer FMOD command → haptic slice; depth-associated semantic icon; similarity-gated scene request; Unity EditMode/PlayMode lab and authored FMOD project; scalar native-metric depth values physically produced from Rokid frames | Physical open-ear localization, Unity FMOD runtime listening, representative metric-depth accuracy, calibrated spatial/angular accuracy, target-user validation |

See [`VALIDATION.md`](VALIDATION.md) for the evidence ledger and exact caveats.

## Prerequisites

Use only what your selected target needs:

- Python 3.12 and a POSIX shell for the Python baseline;
- JDK 17 and Android SDK API 36 build components for Android;
- CMake 3.24+, Ninja, and a C++20 compiler for native code;
- .NET 8 SDK for the desktop relay; Windows 11 to run the WPF UI;
- ADB and an authorized target for physical Android/Rokid installation; and
- an NVIDIA driver/toolkit only for optional CUDA-aware configuration.

Clean bootstrap may require network access to the configured Python, Gradle,
NuGet, Google, and Maven package sources. No model download is required.

## Setup, build, and test

Python baseline:

```bash
./scripts/bootstrap
./scripts/lint
./scripts/test python
.venv/bin/python -m conceptflow_mpl_protocol.validation
./scripts/demo
./scripts/benchmark --iterations 100
./scripts/perception-demo
./scripts/perception-benchmark --iterations 100
./scripts/perception-calibration --json
./scripts/perception-training --list
./scripts/build python
.venv/bin/python scripts/repository/check_wheels.py
```

Platform targets after installing their prerequisites:

```bash
./scripts/test native
./scripts/test android
./scripts/test dotnet
```

Build all configured targets:

```bash
./scripts/build all
```

`make validate` covers the repository policy and Python release baseline; it
does not run Android, native, .NET, hardware, or manual accessibility checks.

## CPU/no-device synthetic demo

The primary demo starts an ephemeral loopback gRPC server, uses a deterministic
CPU mock, drives failure and recovery cases, processes 100 successful synthetic
frames, and emits JSON. It needs no camera, Android device, GPU, model, or
external endpoint.

```bash
./scripts/bootstrap
MPL_DEVICE=cpu ./scripts/demo
```

Success is an exit status of zero with `"ok":true`, serving health, nonzero
backpressure, and a rendered cue containing `"assistive_only":true`. All input
and output semantics are synthetic.

The perception-only companion requires neither a server nor device:

```bash
./scripts/perception-demo
./scripts/perception-benchmark --iterations 100
./scripts/perception-training --exercise left-vs-rear-left --answer rear-left
```

It exercises the three independent layers: metric geometry to synchronized
audio/haptic commands, depth-associated semantic track to auditory icon, and
scene state to an on-demand description request. Output is inspectable JSON or
text; it is not evidence of physical spatial accuracy.

## Physical Rokid and Poco deployment

The installed application labels are **Machine Perception Layer, Rokid Node**
and **Machine Perception Layer, Android Node**. Machine Vision is currently the
first Android Node sublayer; later sublayers remain separate additions.

Build both applications:

```bash
./gradlew --no-daemon :apps:rokid-client:testDebugUnitTest \
  :apps:android-host:testDebugUnitTest \
  :apps:rokid-client:assembleDebug \
  :apps:android-host:assembleDebug
adb devices -l
```

With explicit serials selected from that list, directly sideload the glasses
app without Hi Rokid, a client secret, or a proprietary SDK:

```bash
read -r -p "Rokid ADB serial: " ROKID_SERIAL
read -r -p "Poco ADB serial: " POCO_SERIAL
./scripts/rokid-install --serial "$ROKID_SERIAL" --no-build
./scripts/rokid-control --serial "$ROKID_SERIAL" status
adb -s "$POCO_SERIAL" install -r apps/android-host/build/outputs/apk/debug/android-host-debug.apk
adb -s "$POCO_SERIAL" shell am start -W \
  -n org.conceptflow.mpl.androidhost/org.conceptflow.mpl.host.MainActivity
```

Installation leaves the nonvisual glasses runtime stopped. Camera permission and
capture are separate, explicit development actions documented in
[direct Rokid development](docs/ROKID_INTEGRATION.md).

### Direct Rokid-to-Poco live session

The current ADB pairing helper requires debuggable builds on both devices; the
accessible Poco Start control itself is not debug-gated. Provision the
three accepted model libraries and the external QAIRT runtime on the Poco as
described in [Android private QNN runtime](docs/ANDROID_QNN_PRIVATE_RUNTIME.md),
and grant camera permission on the Rokid. `RECORD_AUDIO` is optional for the
ordinary camera/IMU session and must be granted separately before the Android
Node's explicit ten-second microphone control can succeed.

Pair the two installed apps over an operator-selected private or link-local
address. The helper initializes non-exportable Android Keystore identities,
exchanges only public certificates, pins mutual trust, and installs private
no-backup configuration. It does not start either endpoint:

```bash
./scripts/rokid-install --serial "$ROKID_SERIAL" --no-build --grant-camera
./scripts/android-live-link-pair \
  --rokid-serial "$ROKID_SERIAL" \
  --poco-serial "$POCO_SERIAL" \
  --poco-address "$POCO_PRIVATE_IP"
```

Arm the Rokid sensor-off rendezvous once from authorized development ADB. Then
open **Machine Perception Layer, Android Node** on the Poco, choose Automatic,
Indoor, or Outdoor as needed, and activate the accessible **Start 30-second
glasses camera and motion session** button. The standard button—not an exported
debug intent—is the capture authorization surface:

```bash
./scripts/rokid-control --serial "$ROKID_SERIAL" idle-enable
```

Cable-free cooldowns use Android wakeup alarms, with exact delivery selected
only when Android reports exact-alarm access, and each 15-second handshake has
a finite 17-second CPU wake lease. Doze can still throttle delivery, so the
Poco's 90-second listener window is bounded authorization rather than a timing
guarantee; see [Rokid integration](docs/ROKID_INTEGRATION.md).

To end early, activate the Poco's accessible **Stop glasses camera and motion
session** button. Use the following development command only when the Rokid
standby itself must be disabled:

```bash
./scripts/rokid-control --serial "$ROKID_SERIAL" idle-disable
```

The live test receives Rokid-gated camera frames at approximately 3 FPS relaxed
and up to 5 FPS after meaningful change, plus deduplicated, approximately
100 Hz source IMU samples in bounded batches. The Poco keeps only the latest
complete camera work and runs YOLOE plus exactly one selected 392
indoor/outdoor graph on QNN HTP at an independent 1 FPS stable, 3 FPS material,
or 5 FPS urgent admission tier. Non-keyframes refresh only explicitly labeled,
bounded maintained state; they are not new visual measurements. Status is
aggregate-only: counts, categorical state, bounded latency percentiles, and
clock uncertainty; it excludes payloads and endpoint/session/lease/frame/IMU
identifiers. On 2026-08-23, two consecutive runs without reinstalling either
app completed on QNN HTP with both processes alive, no crashes, zero
interruptions, and authenticated close. The indoor Hypersim 392 run received 80
frames, succeeded on 61/61 inference attempts, accepted 1,400/1,400 poses, and
measured 941.7 ms p95 end-to-end latency. The outdoor VKITTI 392 run received
81 frames, succeeded on 61/62 inference attempts, accepted 1,438/1,438 poses, and
measured 1,183.5 ms p95 end-to-end latency. Both produced 9,373,504 positive
depth values. See the evidence ledger for stage timings and remaining limits;
representative metric-depth accuracy and calibrated spatial/angular accuracy
are not claimed. These physical runs predate the current three-tier Android
admission scheduler; that scheduler has JVM/lint/APK-build validation but still
requires a new physical cadence, energy, and thermal run.

On 2026-08-27, the closed vocabulary was expanded and re-exported to exactly
330 BVI-oriented prompts. Automatic mode physically ran a separately
provisioned Qwen3-VL-2B-Instruct Q4_0 classifier through GenieX's HTP path,
obtained two consistent `INDOOR` results, selected Hypersim 392, and then ran
YOLOE plus the selected depth graph without a DSP crash. Cross-process HTP
execution is serialized through deadline-bounded QNN-priority arbitration.
QNN signals demand before waiting for up to 250 ms; new VLM admission is
limited to 25 ms and defers while QNN demand is present. An admitted VLM checks
for that demand every 20 ms and requests GenieX's cooperative stream stop.
This is not forced native-kernel preemption: the 8,000 ms coroutine timeout is
not a proven hard hold bound if proprietary native execution does not yield.
The revised arbitration has JVM/build validation and still needs a physical
Poco contention run. Android Node now performs a complete generated-image
VLM prewarm when the isolated service binds, retains the loaded wrapper, and
uses two consecutive sparse luma/histogram/layout changes rather than a timer to
request another classification. A stable physical scene produced no third VLM
run across hundreds of later frames and more than four minutes. In the measured
run, post-prewarm classifications required about 4.0 seconds of service time;
the VLM remains a sparse routing task, never a frame-rate stage. A separate
manual-indoor run with the VLM absent completed 47 of 48 inference attempts.
These runs validate execution and orchestration, not classification or metric
accuracy.

On a controlled non-display development unit, the optional bounded hardware
diagnostic is explicit and stops all inputs automatically:

```bash
./scripts/rokid-install --serial "$ROKID_SERIAL" --no-build \
  --grant-camera --grant-microphone
./scripts/rokid-control --serial "$ROKID_SERIAL" stream-test
```

It retains no image, IMU, or audio payload. It reports only stream and
packetization counts, IMU selection/suppression, and aggregate microphone
signal evidence. Microphone packet admission stops at the exact monotonic
two-second boundary and recorder shutdown is initiated by the matching timer;
camera and IMU continue to the eight-second lease boundary.

For the bounded physical development trace, start the deterministic service on
the Ubuntu loopback interface, then ask the directly sideloaded Rokid app for
one frame. ADB reverse is configured only for that selected, authorized device:

```bash
MPL_PROFILE=development MPL_BIND_HOST=127.0.0.1 MPL_BIND_PORT=50051 \
  MPL_INSECURE=true MPL_DEVICE=cuda MPL_ALLOW_CPU_FALLBACK=false \
  MPL_RUNNER_COUNT=2 .venv/bin/python -m conceptflow_mpl_cluster.server

./scripts/rokid-control --serial "$ROKID_SERIAL" physical-trace
```

The development worker returns an explicitly synthetic cue; `MPL_DEVICE=cuda`
selects workers named for discovered GPUs but does not run a CUDA kernel or a
trained model. The glasses send one bounded JPEG plus a timestamp-matched HEAD
pose. Microphone PCM never leaves the glasses: only local nonzero-signal
evidence gates dispatch. The app rejects mismatched results and stale cues,
enforces RPC and whole-run timeouts, then closes every input and transport.
Plaintext is permitted only in the debug build for literal loopback reached
through the host-authorized ADB tunnel; release configuration remains
cleartext-denying. This is a USB development path, not production network
authentication.

The attached non-display consumer device has reported `RG-glasses`, Android
12/API 32,
YodaOS Sprite assist service 0.3.5, and `com.rokid.cxrservice` v12 target 32;
direct ADB over its magnetic 5-pin cable is verified. The glasses app is a
standalone Android APK using standard platform APIs and no vendor SDK. A direct
glasses-to-Ubuntu development trace is implemented and physically exercised.
The separate direct Rokid-to-Poco data plane is also physically exercised by
the two consecutive no-reinstall private-WLAN/QNN HTP runs above, including
authenticated shutdown. See
[direct non-display Rokid development](docs/ROKID_INTEGRATION.md) and
[Android host](docs/ANDROID_HOST.md).

## Windows relay

On Windows 11 with .NET 8, from PowerShell:

```powershell
dotnet restore .\apps\desktop-relay\ConceptFlow.Mpl.DesktopRelay.sln --locked-mode
dotnet build .\apps\desktop-relay\ConceptFlow.Mpl.DesktopRelay.sln --configuration Release --no-restore
dotnet test .\apps\desktop-relay\tests\ConceptFlow.Mpl.DesktopRelay.Core.Tests\ConceptFlow.Mpl.DesktopRelay.Core.Tests.csproj --configuration Release --no-build
dotnet run --project .\apps\desktop-relay\src\ConceptFlow.Mpl.DesktopRelay.Wpf\ConceptFlow.Mpl.DesktopRelay.Wpf.csproj --configuration Release --no-build
```

For a consent-gated, no-network console demonstration:

```bash
dotnet run --project apps/desktop-relay/src/ConceptFlow.Mpl.DesktopRelay.Demo/ConceptFlow.Mpl.DesktopRelay.Demo.csproj \
  --configuration Release -- --consent-synthetic-demo
```

The WPF shell has not been manually validated on Windows, JAWS, or NVDA. Follow
[Windows relay](docs/WINDOWS_RELAY.md) and
[Accessibility](docs/ACCESSIBILITY.md) before release.

## Ubuntu and CUDA

The Python service defaults to a loopback development profile and can use an
explicit CPU fallback. Production configuration requires TLS and a registered
non-synthetic worker; the included server refuses production with its mock.

The native CPU path:

```bash
cmake -S services/cuda-cluster/native -B services/cuda-cluster/native/build-local \
  -G Ninja -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=ON \
  -DCONCEPTFLOW_ENABLE_CUDA=OFF
cmake --build services/cuda-cluster/native/build-local --parallel
ctest --test-dir services/cuda-cluster/native/build-local --output-on-failure
./services/cuda-cluster/native/build-local/conceptflow_native_demo
```

The optional CUDA-aware build:

```bash
nvidia-smi
nvcc --version
cmake -S services/cuda-cluster/native -B services/cuda-cluster/native/build-cuda \
  -G Ninja -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=ON \
  -DCONCEPTFLOW_ENABLE_CUDA=ON
cmake --build services/cuda-cluster/native/build-cuda --parallel
ctest --test-dir services/cuda-cluster/native/build-cuda --output-on-failure
```

That CUDA option verifies build-toolchain availability only. No CUDA kernel,
inference model, or weight is present. See
[CUDA cluster](docs/CUDA_CLUSTER.md) and
[Latency benchmarking](docs/LATENCY_BENCHMARKING.md).

## Privacy and accessibility

The default architecture is ephemeral: no frame logging, capture archive,
analytics upload, or retention store is implemented. Production Python requires
TLS; development plaintext is loopback-only. The direct Android link is TLS 1.3
mutual TLS over a private or link-local network. Its ordinary lease excludes
microphone; a separate exact-binding, explicit-intent request can grant at most
ten seconds while the parent session remains active.
Frames, messages, queues, deadlines, retries, pending correlation, cues, and
status histories are bounded. Python and .NET logs/status redact payload and
credential-shaped fields; Android direct-live status retains only aggregate
counts, categorical state, latency percentiles, and clock uncertainty.

Capture and export require explicit interaction in the current samples. The
optional Rokid key observer is non-consuming and defaults to observe-only; its
command gate requires separate opt-in after physical validation. Any future
zero-touch mode must remain visibly and accessibly activated, bounded, and
immediately stoppable. Review [Privacy architecture](docs/PRIVACY_ARCHITECTURE.md),
[Threat model](docs/THREAT_MODEL.md), and
[Accessibility](docs/ACCESSIBILITY.md).

## QUICK integration boundary

The .NET Core provides example interfaces for a consented QUICKGlance
context/snapshot producer, an explicitly selected bounded QUICKSnip region
producer, and a user-approved QUICKPub structured export consumer. No verified
QUICKGlance/QUICKSnip API was found, and the identified QUICKPub manual did not
define a verified transport API. The examples perform validation and envelope
creation only. Each product must remain independently operable. See
[QUICK integrations](docs/QUICK_INTEGRATIONS.md).

## Documentation map

- [Architecture](docs/ARCHITECTURE.md) — layers, flows, source symbols, and
  current/planned boundaries.
- [Protocol](docs/PROTOCOL.md) — v1 messages, RPCs, correlation, errors, and
  transport boundary.
- [Power-aware streaming](docs/POWER_AWARE_STREAMING.md) — lease/gate/bounded-RAM
  behavior, adaptive camera cadence, IMU gating, microphone consent, and current
  physical validation boundary.
- [Accessibility](docs/ACCESSIBILITY.md) — release gates and TalkBack,
  keyboard, JAWS, and NVDA procedures.
- [Accessible reality interaction](docs/ACCESSIBLE_REALITY_INTERACTION.md) —
  linear focused-object browsing, TalkBack ownership, Binder/Unity/FMOD
  boundaries, VQA, beacon, and physical-input validation limits.
- [Privacy architecture](docs/PRIVACY_ARCHITECTURE.md) — data inventory,
  minimization, consent, transport, logs, and retention.
- [Brand architecture](docs/BRAND_ARCHITECTURE.md) — canonical external source
  hashes, visual system, typography evidence, and rights boundary.
- [Direct Rokid development](docs/ROKID_INTEGRATION.md) — 5-pin cable, direct
  ADB sideload, standard Android APIs, permissions, and device diagnostics.
- [Rokid camera-to-head extrinsic](docs/ROKID_CAMERA_HEAD_EXTRINSIC.md) — sourced
  Camera2 rotation, transform direction, validation, and translation boundary.
- [Wi-Fi Direct transport](docs/WIFI_DIRECT_TRANSPORT.md) — phone-owned P2P
  group lifecycle, permissions, authenticated routing, and recovery policy.
- [YodaOS runtime resilience](docs/YODAOS_RUNTIME_RESILIENCE.md) — measured
  low-memory/radio failure chain, bounded-memory implementation, and safe
  recovery limits for the directly sideloaded Rokid Node.
- [Rokid diagnostic pull spool](docs/ROKID_PULL_SPOOL.md) — disabled-by-default
  JPG/WAV/JSON regression route retained for bounded A/B diagnosis.
- [Windows relay](docs/WINDOWS_RELAY.md) — .NET Core/WPF/headless operation and
  Windows acceptance boundary.
- [Android host](docs/ANDROID_HOST.md) — host policy, build, install, and real
  transport contract.
- [Android Machine Vision](docs/ANDROID_MACHINE_VISION.md) — fixed BVI
  vocabulary and instance semantics, three depth-resolution tiers, exact
  calibration/mask correlation, temporal anchor policy, and HTP boundary.
- [Environment classification](docs/ENVIRONMENT_CLASSIFICATION.md) —
  segmentation-first indoor/outdoor evidence fusion, privacy-minimized GNSS,
  hysteresis, manual overrides, and timestamped depth-profile routing.
- [Android depth-resolution experiments](docs/ANDROID_DEPTH_VARIANTS.md) —
  reproducible 336/392 exports, physical Poco HTP timings, exact tier routing,
  and remaining accuracy/thermal gates.
- [CUDA cluster](docs/CUDA_CLUSTER.md) — Python worker service, C++ primitives,
  CPU/sanitizer/CUDA-aware builds, and missing inference boundary.
- [Latency benchmarking](docs/LATENCY_BENCHMARKING.md) — honest percentile and
  physical end-to-end measurement rules.
- [Sound Bubble specification](docs/SOUND_BUBBLE_SPEC.md) and
  [coordinate frames](docs/COORDINATE_FRAMES.md) — exact body-surface field and
  BODY/HEAD/SENSOR/WORLD separation.
- [Spatial audio](docs/SPATIAL_AUDIO_ARCHITECTURE.md) and
  [virtual speaker array](docs/VIRTUAL_SPEAKER_ARRAY.md) — bounded anchor/field
  layers and four overlapping concentric banks.
- [Auditory icons](docs/AUDITORY_ICONS.md), [haptics](docs/HAPTIC_LANGUAGE.md),
  [motion gating](docs/MOTION_GATING.md), and
  [scene descriptions](docs/SCENE_DESCRIPTION_POLICY.md) — perceptual policy.
- [Perception uncertainty](docs/PERCEPTION_UNCERTAINTY.md),
  [Unity/FMOD lab](docs/UNITY_FMOD_LAB.md), and
  [research evidence](docs/RESEARCH_EVIDENCE.md) — evidence and executable lab.
- [YOLOE-26S boundary](docs/YOLOE_26S_INTEGRATION.md) and
  [perception third-party licensing](docs/THIRD_PARTY_LICENSING.md) — external
  model identity and licensing constraints.
- [Troubleshooting](docs/TROUBLESHOOTING.md) — deterministic diagnostics across
  Python, Android, device, .NET, and native targets.
- [Threat model](docs/THREAT_MODEL.md) — assets, trust boundaries, abuse cases,
  controls, and release priorities.
- [QUICK integrations](docs/QUICK_INTEGRATIONS.md) — optional adapter semantics
  without invented external APIs.

Repository policy is in [`CONTRIBUTING.md`](CONTRIBUTING.md), private security
reporting in [`SECURITY.md`](SECURITY.md), and third-party boundaries in
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) and
[`docs/THIRD_PARTY_LICENSING.md`](docs/THIRD_PARTY_LICENSING.md).

## License

Licensed under your choice of the MIT License or Apache License 2.0:
`MIT OR Apache-2.0`. See [`LICENSE`](LICENSE), [`LICENSE-MIT`](LICENSE-MIT), and
[`LICENSE-APACHE`](LICENSE-APACHE).
