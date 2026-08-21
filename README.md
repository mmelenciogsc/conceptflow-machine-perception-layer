<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# CONCEPTFlow: Machine Intelligence. Human Architecture.

## Machine Perception Layer — It’s just supplemental awareness.

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
  correlated results, multimodal cues, provenance, errors, and health;
- a deterministic CPU/no-device gRPC demonstration of reconnect, cancellation,
  timeout, stale rejection, error propagation, overload, recovery, and
  assistive-only rendering;
- reusable validation, routing, correlation, scheduling, redaction, consent,
  and accessibility-oriented components across Python, Kotlin, C#, and C++;
- buildable Android, .NET, native, and Python baselines with explicit hardware
  and transport boundaries.

It is not:

- a trained perception model, GPU inference engine, production service, or
  benchmark claim about physical hardware;
- a completed phone-to-glasses transport or WebRTC implementation;
- a dependency on Rokid companion apps, client secrets, vendor SDK AARs, or a
  redistribution of proprietary weights, camera captures, or private media;
- evidence of Windows/JAWS/NVDA, TalkBack, physical cue, or safety validation.

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

The contract keeps control, request/result, and health semantics in gRPC:
`Negotiate`, `ProcessFrame`, and `Health`. The current unary frame RPC is a
bounded integration baseline. WebRTC is documented only as a future media-plane
boundary and is not implemented.

Before rendering, hosts validate identity, encoding, dimensions, bytes and
digest; apply route and privacy policy; bound in-flight work; enforce deadlines
and cancellation; and reject unknown, mismatched, stale, or out-of-order
results. Cue schedulers enforce TTL, priority, capacity, cooldown,
supersession/cancellation, verbosity, and available modalities.

Read [Architecture](docs/ARCHITECTURE.md) and [Protocol](docs/PROTOCOL.md) for
the complete flow and source-level boundaries.

## Verified baseline versus planned work

| Area | Verified locally | Not yet verified or implemented |
| --- | --- | --- |
| Python | 145 tests; protocol regeneration; session-gated bounded image decode; synthetic gRPC reconnect, cancellation, timeout, correlation, stale rejection, worker error, fair bounded queue/backpressure, recovery, and assistive-only cue; three wheels/sdists built and isolated-imported | Real model worker, production serving, physical-device path |
| Android | Gradle 8.11.1 / AGP 8.10.1 / Kotlin 2.0.21; 49 JVM tests including the shared wire vector; Android Lint; both debug APKs built with JDK 17 and an installed SDK | Real inter-device transport, instrumentation, TalkBack and physical cue validation |
| Rokid | Standalone standard-Android APK; Camera2/SensorManager hardware adapters; inspectable cue rendering; serial-safe direct build/install/activity-start over the magnetic 5-pin cable | Foreground camera/audio/touchpad validation with the display awake; project-owned Rokid-to-Poco frame/cue transport |
| Windows | .NET 8 Core, WPF, headless demo, and xUnit; restore/build including WPF cross-target, 156 tests including the shared wire vector, consent-gated demo on Ubuntu | Manual Windows execution, JAWS, NVDA, real capture and endpoint validation |
| Native/CUDA | Strict Release build, native test executable’s 15 cases, demo, sanitizers, CUDA-aware configure/build on CUDA 12.0 | CUDA kernel, model loading/inference, GPU correctness/performance |

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

## Physical Rokid and Poco deployment

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
adb -s "$POCO_SERIAL" install -r apps/android-host/build/outputs/apk/debug/android-host-debug.apk
adb -s "$POCO_SERIAL" shell am start -W \
  -n org.conceptflow.mpl.androidhost/org.conceptflow.mpl.host.MainActivity
```

The attached consumer device has reported `RG-glasses`, Android 12/API 32,
YodaOS Sprite assist service 0.3.5, and `com.rokid.cxrservice` v12 target 32;
direct ADB over its magnetic 5-pin cable is verified. The glasses app is a
standalone Android APK using standard platform APIs and no vendor SDK. The two
apps currently exercise independent in-process flows; a real authenticated
Rokid/Poco transport is not yet implemented. See
[direct Rokid development](docs/ROKID_INTEGRATION.md) and
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
TLS; development plaintext is loopback-only. Frames, messages, queues,
deadlines, retries, pending correlation, cues, and status histories are bounded.
Python and .NET logs/status redact payload and credential-shaped fields.

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
- [CUDA cluster](docs/CUDA_CLUSTER.md) — Python worker service, C++ primitives,
  CPU/sanitizer/CUDA-aware builds, and missing inference boundary.
- [Latency benchmarking](docs/LATENCY_BENCHMARKING.md) — honest percentile and
  physical end-to-end measurement rules.
- [Troubleshooting](docs/TROUBLESHOOTING.md) — deterministic diagnostics across
  Python, Android, device, .NET, and native targets.
- [Threat model](docs/THREAT_MODEL.md) — assets, trust boundaries, abuse cases,
  controls, and release priorities.
- [QUICK integrations](docs/QUICK_INTEGRATIONS.md) — optional adapter semantics
  without invented external APIs.

Repository policy is in [`CONTRIBUTING.md`](CONTRIBUTING.md), private security
reporting in [`SECURITY.md`](SECURITY.md), and third-party boundaries in
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## License

Licensed under your choice of the MIT License or Apache License 2.0:
`MIT OR Apache-2.0`. See [`LICENSE`](LICENSE), [`LICENSE-MIT`](LICENSE-MIT), and
[`LICENSE-APACHE`](LICENSE-APACHE).
