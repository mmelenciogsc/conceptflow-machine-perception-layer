<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Validation evidence

This ledger records what was actually inspected or executed for the initial
public baseline on 2026-08-21. A build, unit test, cross-target compilation, or
synthetic demonstration is not presented as physical-device, production-model,
accessibility, safety, or performance validation.

## Environment discovery

The release host was Ubuntu 24.04 with kernel 7.0.0-29-generic. The relevant
tools found were Git 2.43.0, authenticated GitHub CLI 2.45.0, OpenJDK/Javac
17.0.19, Android SDK API 36 components, Gradle wrapper 8.11.1, Python 3.12.3,
.NET SDK 8.0.424 in a temporary tool directory, CMake 3.28.3, Ninja 1.11.1,
CUDA compiler 12.0.140, ADB 1.0.41, Node 22.22.3, pnpm 11.5.1, ImageMagick
6.9.12-98, and `file` 5.45.

`nvidia-smi` reported an unavailable device handle for one GPU and identified
an RTX 2080 Ti with driver 580.173.02. No service, driver, GPU, or device state
was reset to work around that machine condition. CUDA validation below is
toolchain-aware compilation only.

## Release validation summary

| Lane | Executed result | Boundary |
| --- | --- | --- |
| Repository | format, policy, secret, config-example, shell, Ruff, and MyPy gates passed | `actionlint` was unavailable; workflows received parser and repository-policy checks |
| Python | 145 tests passed; protocol generation valid; all three source/wheel packages built and isolated-imported | no production worker or non-loopback deployment |
| Synthetic slice | real loopback gRPC demo passed reconnect, cancellation, timeout, stale rejection, worker error, overload, recovery, and cue rendering | deterministic CPU mock and synthetic frames only |
| Android | 49 JVM tests, Android Lint, strict dependency verification, and both debug APK assemblies passed | no instrumentation or final inter-device transport |
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

The build used JDK 17 and the installed Android SDK. Test totals were 26 for
`android-host`, 23 for `rokid-client`, and one cross-language protocol-vector
test in `android-protocol`.

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
WebRTC is intentionally only a documented future media-plane boundary; no
WebRTC implementation is claimed.

## Rokid and Poco evidence

The attached consumer glasses reported manufacturer `Rokid`, model `RG-glasses`,
Android 12/API 32, YodaOS Sprite assist service 0.3.5, and CXR service package
`com.rokid.cxrservice` version 12. Direct ADB over the magnetic 5-pin data cable
was verified. The current debug Rokid APK was installed directly, camera
permission was explicitly granted for development, and `am start -W` accepted
its activity; its process remained live. The 480x640 built-in display reported
`OFF`, so Android kept the activity sleeping and the key/camera test did not
execute. The system Sprite assist service still held camera 0. This proves the
direct build/install/start boundary, not foreground rendering, frame capture,
touchpad input, or cue output.

The Poco F7 Ultra rejected the debug host APK through ADB with
`INSTALL_FAILED_USER_RESTRICTED`. No device-security setting was bypassed or
left modified. The host APK itself builds and tests successfully, but phone
installation remains a user-confirmation/device-policy gate.

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
```

The installed debug APK was 13,408,278 bytes with SHA-256
`a46272478ebdb0beb30f3bc666cae9b22cca14fd6455bf846d3000411db1113f`.

## Accessibility evidence

Android source and lint cover labeled controls, logical focus, live-region
status, non-color state text, TalkBack-aware speech suppression, and a bounded
text fallback when spoken cue delivery is unavailable. Glasses-side essentials
have audio/haptic output seams and an inspectable nonvisual renderer.

The Windows WPF shell uses standard controls, keyboard navigation, accessible
names/help text, live status, predictable focus restoration, and optional
speech output. Core behavior is independently available through textual status
and the headless demo.

Manual TalkBack, Windows UI Automation, high-contrast/scaling, JAWS, NVDA,
physical earcon/haptic output, and BVI human-factors acceptance were not
performed. Those checks remain release-blocking for any supported-device or
assistive-use claim beyond this engineering baseline.

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

- real phone-to-glasses or WebRTC transport;
- project-owned authenticated Rokid-to-Poco transport and its physical-device
  reconnect, cancellation, stale-result, and latency behavior;
- a registered production worker, model weights, real CUDA kernel/inference,
  and multi-GPU correctness, failover, load, thermal, and performance tests;
- physical glass-to-cue latency and long-duration reconnect/roaming tests;
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
