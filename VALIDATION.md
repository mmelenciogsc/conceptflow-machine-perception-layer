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
built, but the proprietary FMOD Unity runtime package is not redistributed.
Unity-to-FMOD playback, listening-based localization, open-ear perceptual
quality, JAWS/NVDA, metric depth on Rokid, real YOLOE/Depth Anything inference,
and sustained thermal behavior were not validated. The bounded physical trace
and Poco dispatch evidence added below are hardware execution checks, not BVI
human-factors acceptance or production-model validation.

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

The exact final debug APK was directly installed on Rokid serial
`2001092545610702`
and the explicit eight-second stream diagnostic ran to completion. The final
run reported:

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

No physical WebRTC link was claimed: the current milestone validates
source-side leases/gates/packetization and Poco-side bounded ingestion, not
wireless signaling, pairing, encryption, throughput, energy use, or thermal
behavior. During the required local DEBUGGER pass, llama.cpp encountered a
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
| Android | 73 JVM tests, Android Lint, strict dependency verification, and both debug APK assemblies passed | no instrumentation or phone-to-glasses transport |
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

The build used JDK 17 and the installed Android SDK. Current test totals are 31
for `android-host`, 41 for `rokid-client`, and one cross-language protocol-vector
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
Transport-neutral data-channel envelopes plus a sender packetizer and host
ingress are implemented; WebRTC signaling, authentication, and a physical
data-channel adapter are not, so no WebRTC transmission is claimed.

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

The updated direct-sideload app selected the device's exact 1920×1080 JPEG
output, submitted bounded captures on a 200 ms schedule, and analyzed frames
only in memory. Deterministic JVM tests covered aspect-fit behavior, capture
size selection, unsigned luma, dark/blur classification, exposure-change
suppression, localized-motion sensitivity, 2/5 FPS cadence and hysteresis,
timestamp rollback, and reset. Android unit tests, Android Lint, and debug
assembly passed before installation.

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

- real phone-to-glasses or WebRTC transport;
- project-owned authenticated Rokid-to-Poco transport and its physical-device
  reconnect, cancellation, stale-result, and latency behavior;
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
