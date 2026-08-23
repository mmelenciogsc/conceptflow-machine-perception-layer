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
  client to the Poco host, with independent realtime/control and camera lanes;
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
camera and IMU only; microphone is structurally rejected. The code and local
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
518 for sparse reference/calibration work. Observed visual keyframes are gated
at 3 FPS relaxed and at no more than 5 FPS for meaningful motion or uncertainty.
High-rate pose/IMU updates can rotate existing short-lived static-world anchors;
translation additionally requires matching explicit VIO or external-tracking
origin evidence. Anchoring defaults off and requires explicit confirmed-static
per-instance evidence; class or geometry alone is insufficient, and later
dynamic/unknown evidence evicts a reused ID. Pose/IMU updates cannot create
depth, objects, or observations of moving or newly visible scene content.
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
| Python | 214 tests; protocol regeneration; session-gated bounded image decode; synthetic gRPC reconnect, cancellation, timeout, correlation, stale rejection, worker error, fair bounded queue/backpressure, recovery, assistive-only cue, and headless Map/Morph/Move slices; three wheels/sdists built and isolated-imported | Real model worker and production serving |
| Android | Named Rokid Node and Android Node APKs; fixed-vocabulary YOLOE instance-segmentation semantics; timestamped camera/GNSS environment fusion; exact 392 balanced, 336 low-power, and 518 sparse reference/calibration routing; profile-plus-intrinsics calibration binding; mask-fingerprint correlation; bounded temporal anchor propagation; opt-in app-process QNN JNI/live-test path; direct two-lane mutual-TLS listener; aggregate-only live status; external YOLOE-26S and Hypersim/VKITTI Small graphs physically executed standalone through QAIRT 2.48.40 QNN HTP V79 on the Poco; two consecutive no-reinstall physical app-process HTP/private-WLAN runs with bounded latency and authenticated close; Android Lint, JVM tests, and APK builds | Empirical 1920×1080 camera calibration and calibrated spatial/angular accuracy; representative task/metric-depth accuracy; long-duration, forced-reconnect, adverse-network, and sustained thermal profiling; manual TalkBack/BVI acceptance |
| Rokid | Nonvisual standard-Android APK for AI Glasses Style (Non-Display); direct ADB install/control; aspect-preserving 1920×1080 gate; bounded three-request 3 FPS relaxed/5 FPS meaningful-motion capture policy; dark/blur gate; a physical run measured 4.497 analyzed FPS and 3.967 emitted FPS; observed 4032×3024 equal pixel/active/pre-correction arrays, CENTER_ONLY crop, NONE-only rotate-and-crop, and OFF-only OIS; narrow-fingerprint `DERIVED` intrinsics with request/result consistency guards; nominal 100 Hz fused head-IMU acquisition with duplicate suppression, ≤20 ms batches, and ≤1 s absolute refresh; explicit short microphone lease for the separate local diagnostic; bounded ADB-reverse gRPC development trace; two consecutive bounded direct private-WLAN TLS 1.3 mutual-TLS camera+IMU runs with microphone excluded, QNN HTP execution, and authenticated close; no vendor SDK | Empirical camera calibration, Unity/FMOD listener interpolation, open-ear localization/listening tests, on-glasses haptics, forced reconnect/adverse-network recovery, and sustained thermal/energy validation |
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

### Debug-only direct Rokid-to-Poco live test

The direct live test requires debuggable builds on both devices. Provision the
three accepted model libraries and the external QAIRT runtime on the Poco as
described in [Android private QNN runtime](docs/ANDROID_QNN_PRIVATE_RUNTIME.md),
and grant only camera permission on the Rokid. Microphone permission is neither
needed nor used by this path.

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

Start the Poco listener before the glasses client. The default test is bounded
to 30 seconds and 150 received frames; `environment` may be `automatic`,
`indoor`, or `outdoor`:

```bash
adb -s "$POCO_SERIAL" shell am start --user 0 -W \
  -n org.conceptflow.mpl.androidhost/org.conceptflow.mpl.host.MainActivity \
  -a org.conceptflow.mpl.host.action.START_LIVE_TEST \
  --es environment automatic --ei duration_seconds 30 --ei maximum_frames 150
./scripts/rokid-control --serial "$ROKID_SERIAL" live-link-start
```

Stop the glasses producer first, then the listener, when ending early:

```bash
./scripts/rokid-control --serial "$ROKID_SERIAL" live-link-stop
adb -s "$POCO_SERIAL" shell am start --user 0 -W \
  -n org.conceptflow.mpl.androidhost/org.conceptflow.mpl.host.MainActivity \
  -a org.conceptflow.mpl.host.action.STOP_LIVE_TEST
```

The live test sends only gated 3/5 FPS camera frames and deduplicated,
approximately 100 Hz source IMU samples in bounded batches. The Poco runs YOLOE
and exactly one selected 392 indoor/outdoor graph on QNN HTP. Status is
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
are not claimed.

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
mutual TLS over a private or link-local network and never grants microphone.
Frames, messages, queues, deadlines, retries, pending correlation, cues, and
status histories are bounded. Python and .NET logs/status redact payload and
credential-shaped fields; Android direct-live status retains only aggregate
counts, categorical state, latency percentiles, and clock uncertainty.

Capture and export require explicit interaction in the current samples. Any
future zero-touch mode must remain visibly and accessibly activated, bounded,
and immediately stoppable. Review [Privacy architecture](docs/PRIVACY_ARCHITECTURE.md),
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
- [Power-aware streaming](docs/POWER_AWARE_STREAMING.md) — subscribe-then-push
  leases, adaptive camera cadence, IMU gating, microphone consent, and current
  physical validation boundary.
- [Accessibility](docs/ACCESSIBILITY.md) — release gates and TalkBack,
  keyboard, JAWS, and NVDA procedures.
- [Privacy architecture](docs/PRIVACY_ARCHITECTURE.md) — data inventory,
  minimization, consent, transport, logs, and retention.
- [Brand architecture](docs/BRAND_ARCHITECTURE.md) — canonical external source
  hashes, visual system, typography evidence, and rights boundary.
- [Direct Rokid development](docs/ROKID_INTEGRATION.md) — 5-pin cable, direct
  ADB sideload, standard Android APIs, permissions, and device diagnostics.
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
