<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Validation evidence

This ledger records what was actually inspected or executed for the initial
public baseline on 2026-08-21. A build, unit test, cross-target compilation, or
synthetic demonstration is not presented as physical-device, production-model,
accessibility, safety, or performance validation.

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
- Android Lint, strict dependency verification, 31 host tests, 28 Rokid tests,
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
built, but the proprietary FMOD Unity runtime package is not redistributed.
Unity-to-FMOD playback, listening-based localization, open-ear output,
TalkBack, JAWS/NVDA, physical haptics, metric depth on Rokid, real YOLOE/Depth
Anything inference, sustained thermal behavior, and a physical end-to-end
glasses-to-host-to-cue run were not validated. A Poco install attempt remained
blocked by `INSTALL_FAILED_USER_RESTRICTED`; no device security control was
bypassed.

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
| Android | 55 JVM tests, Android Lint, strict dependency verification, and both debug APK assemblies passed | no instrumentation or final inter-device transport |
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
`android-host`, 28 for `rokid-client`, and one cross-language protocol-vector
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
./scripts/rokid-control --serial "$ROKID_SERIAL" capture-start
./scripts/rokid-install --serial "$ROKID_SERIAL" --no-build --grant-microphone
./scripts/rokid-control --serial "$ROKID_SERIAL" stream-test
./scripts/rokid-control --serial "$ROKID_SERIAL" stop
```

The camera/IMU/microphone test APK was 7,020,604 bytes with SHA-256
`eaf6f805476c93399a298ea2a3423f17038d98b2fdd62977fe5e0fc59e0a45f3`.

## Accessibility evidence

Android host source and lint cover labeled controls, logical focus, live-region
status, non-color state text, TalkBack-aware speech suppression, and a bounded
text fallback when spoken cue delivery is unavailable. The non-display glasses
client has audio/haptic output seams and an inspectable nonvisual renderer.

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
