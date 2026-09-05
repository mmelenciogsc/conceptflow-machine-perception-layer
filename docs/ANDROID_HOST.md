<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Machine Perception Layer, Android Node

The Android Node is the phone-side policy and cue-dispatch reference. It detects
available platform capabilities, validates and queues frames, chooses a local
or remote route, tracks session and result state, schedules cues, and exposes
accessible feedback. Its current activity runs an in-process synthetic
vertical slice and controls an explicit persistent foreground node. The live path
uses the project-owned binary mutual-TLS Android transport to ingest bounded
in-memory camera, IMU, microphone, and touch messages. The old app-private
JSON/artifact route is disabled by default and retained only for diagnostics.
Physical 2026-08-23 through 2026-08-27
runs exercised this path, including sequential leases, an in-place-upgrade
network interruption, reconnection, and Poco display-off operation. Production
deployment and broad adverse-network behavior remain validation gates. A separate
direct Rokid-to-Ubuntu development trace also exercises the shared protobuf
contract.

Its first product sublayer is Machine Vision. The implemented pure-Kotlin core
has a fixed 330-class BVI vocabulary, dual indoor/outdoor Depth Anything profile
selection, segmentation-first timestamped environment routing, a 660-record
two-distance dimension-vector table, bounded relative-to-metric calibration,
fixed-vocabulary artifact verification, and truthful QNN HTP capability
planning for the Poco F7 Ultra. See
[Android Machine Vision](ANDROID_MACHINE_VISION.md) and
[Environment classification](ENVIRONMENT_CLASSIFICATION.md). No model runtime
or model weight is bundled. Privately provisioned external FP16 model libraries
have been executed through both standalone QNN tooling and the debug APK's
in-process QNN HTP adapter on the Poco. No proprietary runtime or model artifact
is stored in this repository or shipped by its default build.

## Build baseline

The Gradle wrapper is 8.11.1, Android Gradle Plugin is 8.10.1, Kotlin is 2.0.21,
Java target is 17, `compileSdk`/`targetSdk` are 36, and `minSdk` is 29. The
Android protocol module generates Java-lite protobuf and gRPC bindings from the
repository’s canonical v1 schema.

Prerequisites:

- JDK 17;
- an Android SDK with API 36 build components available;
- network access to the configured Gradle repositories on a clean machine; and
- `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or a local `local.properties` entry.

From the repository root:

```bash
./gradlew --no-daemon :packages:android-protocol:build \
  :apps:android-host:testDebugUnitTest \
  :apps:android-host:assembleDebug
```

The debug APK is produced at
`apps/android-host/build/outputs/apk/debug/android-host-debug.apk`.

## Runtime components

| Symbol | Responsibility |
| --- | --- |
| `AndroidCapabilityDetector.detect` | Camera, rotation vector, gyroscope, audio, haptic, validated-network, and metered-network discovery. |
| `BoundedFramePreprocessor.prepare` | Bounded structural PNG/JPEG preflight, descriptor dimensions, byte count, maximum size, and SHA-256 validation. This client-side check does not claim full decodability. |
| `RoutingPolicy.choose` | Prefer local capability; otherwise require a validated network and enforce a metered-network byte limit. |
| `BoundedFrameQueue.offer` | Bounded FIFO admission with explicit oldest-frame eviction. |
| `SessionStateMachine.dispatch` | Connect, negotiate, active, bounded exponential reconnect, cancel, closed, and failed states. |
| `ResultCorrelator.correlate` | Pending bound, cancellation, session/frame match, TTL, and ordering. |
| `CueScheduler.submit` | Confidence, freshness, modality, verbosity, cooldown, capacity, priority, cancellation, and supersession. |
| `GrpcPerceptionTransport.secure` | TLS gRPC unary negotiation and frame processing with deadlines and caller cancellation. |
| `GlassesStreamIngress.accept` | Lease/session validation, ordered camera chunk assembly with SHA-256, absolute IMU batch validation, explicit microphone authorization, and latest-unread camera retention. |
| `AndroidPrivateLanDiscoveryEndpointResolver` | Content-free private-WLAN announcement with an eight-second receive window and provisioned-address fallback; endpoint reachability never replaces exact-pin mutual TLS. |
| `SensorTimeline` | Bounded normalized camera/IMU/audio/touch ownership, modality-specific freshness, overflow counters, and timestamp-window association. |
| `PerceptionBus` / `AndroidPerceptionBridge` | Compact latest world state and ordered touch delivery to Unity without exposing raw camera/audio buffers or blocking Unity's main thread. |
| `DeterministicSensorReplay` | Bounded original/slowed/accelerated/stepwise delivery through the same transport-observer seam for sanitized offline fixtures. |
| `HostSpoolPullCoordinator` | Disabled-by-default diagnostic-only manifest/artifact reader retained for A/B regression; not the production hot path. |
| `Request 10-second glasses microphone` | Separate TalkBack- and keyboard-accessible control that is enabled only during an authenticated camera/IMU session; sends an exact-binding mic-only lease and feeds a bounded RAM-only Silero VAD/Whisper window. |
| `Play glasses brand sequence` | Sends a two-second-TTL command over the authenticated private-WLAN control lane and retains its correlated Rokid acknowledgement in TalkBack-readable status. |
| `AccessibilityAwareSpeechFeedback.speak` | Text-to-speech when available, suppressed when an accessibility service is enabled. |
| `MachineVisionPipeline.process` | Correlated, freshness-bounded, fixed-vocabulary semantic-mask/depth fusion using the selected environment profile. |
| `EnvironmentAwareMachineVisionPipeline.process` | Fixed-vocabulary segmentation, frame-correlated environment fusion, selected depth graph, and metric track fusion in enforced order. |
| `LiveSemanticDepthAdmissionPolicy` | Independent phone-side model admission: 1 FPS stable, 3 FPS for material motion, track staleness, or uncertainty, and 5 FPS only for urgent low-confidence, stale-depth, occlusion, or rapid-approach correction. |
| `LightweightTrackMaintainer` | Bounded class/mask-box/depth association, short-lived prediction, confidence decay, coordinate validity, and deterministic eviction between model keyframes. It has no live appearance encoder or optical-flow measurement and is not classic DeepSORT. |
| `AndroidGnssEnvironmentSource.startBurst` | Optional foreground-only, power-bounded reception-quality evidence without reading or retaining coordinates. |
| `TwoAnchorMetricDepthCalibrator.calibrate` | Robust 0.6096 m / 2.4384 m relative-depth calibration with uncertainty and extrapolation reporting. |
| `HtpExecutionLease.tryAcquire` | Deadline-bounded cross-process exclusion and QNN-demand signaling between QNN graphs and the isolated local VLM. New VLM work defers for QNN; admitted VLM work is asked to stop cooperatively. Proprietary native execution is not forcibly preempted. |
| `QualcommAcceleratorPlanner.select` | HTP only after QNN and HTP initialization; otherwise explicit reference/unavailable state. |

The current microphone control is separate from Start but not a standalone
transport session. It is disabled until v1 has an active authenticated
camera/IMU session and the live Machine Vision runtime is initialized, and it
remains disabled while a microphone request or window is active. A rejected
microphone request does not stop the existing camera or IMU streams.
The Android consumer immediately drains accepted PCM blocks from the 16-block
sensor timeline into a fixed-capacity ten-second window. Automatic scene
classification runs ambient profiling plus VAD without releasing transcript
content. An explicit user-query window may invoke the prewarmed, CPU-local
quantized `small.en` model after VAD. See
[Android on-device speech window](ANDROID_ON_DEVICE_SPEECH.md).
The control also remains disabled during native model prewarm and while a prior
window is being analyzed, preventing overlapping leases from replacing a
still-live result.
An authenticated glasses gesture can send the same intent in the reverse
direction. The host validates exact binding, explicit user origin, monotonic
intent ordering, and clock-normalized freshness before issuing the correlated
sublease. STOP immediately clears host microphone authorization, and late
in-flight audio is dropped without interrupting camera or IMU. This does not
remove or weaken the accessible phone control.

The live QNN lease acquisition deadline is 250 ms and isolated-VLM admission
is limited to 25 ms. An admitted prewarm or classification receives an
8.5-second cooperative completion window. Background prewarm and environment
classification defer ordinary depth/track aging, uncertainty, and
low-confidence refreshes, while direct motion, occlusion, or rapid-approach
evidence requests VLM cancellation and admits the newest QNN frame. An explicit
focused-object VQA request is different: it retains its bounded captured-frame
correlation through ordinary motion and semantic-track occlusion, but still
yields to rapid-approach evidence and the same hard completion window. Its
isolated-process HTP lease acquisition retries only QNN-priority/busy/timeout
refusals for at most 1.5 seconds; background classification remains fail-fast.
Focused generation is capped at eight tokens and accepted answers at 16 words.
Direct motion has precedence when it occurs at the same time as routine
staleness. Session replacement, reconnect, and stop synchronously
invalidate generation-scoped VLM work, clear cached scene evidence, and cancel
queued jobs without allowing a stale lazy job to clear a replacement owner.
Kernel file locks are process-death safe, while in-process handles are
idempotently released. Wait/hold telemetry contains no frame or scene content.

Camera transport defaults to packed I420. Protocol 1.5 can explicitly
negotiate independently decodable AVC Annex-B access units for controlled
hardware tests. Android Node rejects an encoding that differs from its lease,
requires SPS, PPS, and IDR in every AVC unit, selects a hardware decoder, copies
the decoded `YUV_420_888` planes into canonical I420 while honoring row/pixel
strides and crop bounds, and preserves the original frame correlation before
timeline/QNN admission. Decoder failure resets codec state and drops that frame;
it does not reinterpret compressed bytes as raw pixels. The first failure also
opens a one-way process-lifetime circuit breaker: only the active connection is
closed, the persistent listener stays up, and the next negotiated lease grants
I420. Repeated faults cannot cause codec oscillation. Current encoding, decode
failures, and fallback count are available through content-free runtime status.
The VLM checks QNN demand every 20 ms and requests the supported GenieX stream
stop. If a focused proprietary native call does not yield by its absolute
deadline plus a 250 ms cooperative grace period, Android Node terminates only
the isolated `:local_vlm` process; the foreground sensor/QNN process remains
alive and rebinds. The 8,000 ms coroutine timeout alone is not claimed as a
hard native bound.

The Qwen runtime is also a bounded memory lease, not a four-hour resident
allocation. Initial automatic classification prewarms it, while two agreeing
results establish the scene baseline. After 120 seconds without VLM work the
isolated process closes the wrapper and retires so proprietary native mappings
are fully reclaimed. Stable scenes do not rewarm it. A persistent material
scene change or an explicit focus/VQA interaction starts a new warm window.
Android memory-pressure callbacks request the same idle retirement, while
in-flight work completes or reaches its existing hard bound first. The binding
uses `BIND_WAIVE_PRIORITY`, making the optional semantic process reclaimable
before the foreground sensor backbone.

Rokid activation and sleep gestures are separate from microphone control. The
host validates each typed `RokidGestureIntent`, maps it to an ACTIVATE or SLEEP
command, sends that command back over the same authenticated control lane, and
requires an exact result correlation. Live status retains the latest
requested, accepted, or rejected operation and whether its source was a glasses
gesture or Android control, so frequent frame-status refreshes do not erase the
acknowledgement. Keyboard `B` invokes the same phone-originated brand-sequence
request as the accessible button. ADB Wi-Fi is not used by this operational
path.

`apps/android-host/src/main/res/xml/network_security_config.xml` disables
cleartext traffic. The secure transport accepts a host and port through
`GrpcEndpoint`; it does not bypass platform certificate validation.

## Current sample activity

The synthetic Connect/Process controls intentionally construct
`InProcessHostTransport`. Connect
negotiates a synthetic session, Process generates a real decodable one-pixel
JPEG fixture, and the synthetic result contains one directional obstacle cue.
`InProcessCueDispatchTransport` sends that cue to Android audio/haptic feedback
within the same app. The separate **Start persistent Android Node listener**
control starts a connected-device foreground service that owns the direct
two-lane private-WLAN mutual-TLS listener independently of the Activity,
receives sequential bounded glasses frame/IMU leases, and runs the privately
provisioned QNN adapter when its profile-selection prerequisites are satisfied.
It enables microphone and Rokid command operations only after authentication.
The live path does not instantiate `GrpcPerceptionTransport` and does not yet
return perception cues to the Rokid APK.

Rokid capture cadence and Poco model cadence are deliberately separate. The
glasses may send approximately 3 FPS while relaxed and up to 5 FPS after
meaningful change. Android ingress and the inference queue retain only the
latest complete frame. The phone admits segmentation plus the selected metric
depth graph at 1 FPS while state is stable, 3 FPS for material motion,
staleness, or uncertainty, and at most 5 FPS for urgent correction. Between
admitted keyframes, the host emits explicitly labeled bounded prediction and
orientation-propagated state from prior measurements; it does not claim a new
visual measurement.

The live path has no optical flow or wired appearance encoder and must not be
described as classic DeepSORT. Rokid IMU contributes orientation only: it does
not supply translation or absolute scale. WORLD output therefore requires
separate, matching position evidence; otherwise a track remains HEAD, CAMERA,
or 2D/scalar-depth state according to available evidence. Controller start
resets scheduling, tracking, pose/extrinsic, and depth-profile state.
Authenticated session replacement, reconnect, and stop repeat those resets;
session begin or invalidation also clears previously published head state.

This separation allows policy tests and APK inspection without forcing network,
QNN or sensor work onto Unity's main thread. The implemented private-WLAN data
plane is mutually authenticated TLS with explicit bounded framing; it is not a
WebRTC implementation and makes no physical-network zero-copy claim.

When app TTS is suppressed because an accessibility service is active, or is
unavailable/not ready, the bounded cue speech (falling back to its description)
is written to the dedicated polite `cue_status` live region. This automated
contract does not replace the manual TalkBack procedure.

## Install and inspect on a phone

With one authorized phone attached:

```bash
adb devices -l
read -r -p "Poco ADB serial: " POCO_SERIAL
adb -s "$POCO_SERIAL" install -r apps/android-host/build/outputs/apk/debug/android-host-debug.apk
adb -s "$POCO_SERIAL" shell am start -W \
  -n org.conceptflow.mpl.androidhost/org.conceptflow.mpl.host.MainActivity
```

The shell variable is local operator input, not a repository credential. If
more than one device is present, never omit `-s`.

Expected current behavior:

1. The app reports detected capabilities and an idle session.
2. Connect creates an in-process synthetic session.
3. Process synthetic frame produces an inspectable local cue.
4. Run synthetic Machine Vision diagnostic (or press `V`) exercises the
   bounded semantic/depth/calibration path without loading a model.
5. Automatic (`A`), Manual indoor (`I`), and Manual outdoor (`O`) expose the
   selected depth-routing mode. Automatic requests optional foreground precise
   location only after explicit activation; denial does not disable camera or
   manual classification.
6. Run synthetic environment diagnostic (or press `E`) exercises timestamped
   camera/GNSS evidence fusion and outdoor-profile routing with test data.
7. Start persistent Android Node listener (or press `L`) starts the foreground
   listener; individual glasses capture leases remain bounded;
   while authenticated, `M` requests microphone and `B` requests the on-glasses
   brand sequence.
8. Stop Android Node closes the listener and its current session.

The synthetic controls do not validate phone-to-glasses transport or the Python
service. The live control exercises the physical private-WLAN path when the
paired devices and private QNN artifacts are present.

The persistent node records only an enabled flag and the selected automatic or
forced depth-environment mode in private app preferences. Its connected-device
foreground service returns Android's sticky restart disposition and reconstructs
the controller from that state when Android redelivers a null restart intent.
The tested HyperOS build did not redeliver the sticky service within 60 seconds
after either a natural low-memory termination or a same-UID `SIGKILL`. A second,
small `:guardian` foreground process therefore holds only a Binder liveness
watch on Android Node. It owns no sensor, network, model, Unity, or FMOD state.
On Binder death it restarts the explicitly enabled foreground node, which reads
the durable mode and reconstructs all runtime resources. Explicit **Stop Android
Node** clears the state and stops both services, so the guardian never overrides
user intent. Camera, audio, IMU, touch, peer identity, and model output are not
stored in this restart record.

On 2026-08-22 the current debug APK was installed and launched on the attached
Poco F7 Ultra after the user approved Xiaomi's **Install via USB** prompt. The
hardware `P` shortcut ran the synthetic frame path. Android's vibrator service
recorded a completed 66 ms predefined `CLICK` attributed to
`org.conceptflow.mpl.androidhost`, and the audio service recorded the app's
accessibility-sonification track. A UI Automator inspection found named text for
all four controls and the cue/status output. This proves device dispatch and
machine-readable labels, not haptic directionality, audio quality, or manual
TalkBack/BVI usability.

## Transport integration contract

A real host integration should:

1. Obtain frames from a separately consented glasses/phone transport.
2. Run `BoundedFramePreprocessor.prepare` before queueing.
3. Use `RoutingPolicy.choose` with current network and local capability state.
4. Negotiate v1 through `GrpcPerceptionTransport.secure` for remote processing.
5. Register frames with `ResultCorrelator` before dispatch and cancel them on
   transport cancellation.
6. Reject error, stale, mismatched, and out-of-order results before scheduling.
7. Deliver accepted cues through a real bounded glasses transport while
   retaining text/state feedback on the host.

No plaintext non-loopback mode should be added to the public app. Development
against the Python loopback service requires an emulator/local bridge design
that preserves an explicit development boundary; the current manifest denies
cleartext globally.

## Verified status

On 2026-08-25, the focused Android transport, Android Node, and Rokid Node JVM
test tasks, both debug APK assemblies, both Android lint tasks, and
`git diff --check` passed. The current APKs were then replacement-installed on
the attached devices without clearing app-private state. TalkBack remained
enabled and all three private QNN artifact slots remained present.

With the Poco display awake, one physical lease delivered 82 frames, 780 IMU
batches, and 1,552 accepted pose samples to Android Node. Across three leases,
its accessible foreground-notification status reached 252 frames, 2,362 IMU
batches, and 4,749 accepted pose samples with zero rejected poses. With the Poco
display in Doze, the foreground service stayed active and the Rokid completed
another 88-frame lease, then authenticated the next lease. This validates the
current physical glasses-to-phone transport and one screen-off reconnect path;
it does not validate long-duration roaming, hostile networks, inference
accuracy, thermal endurance, audio localization, or BVI usability.

Real Python-service transport from the app, instrumentation tests, Android
hardware cue quality, and manual TalkBack task acceptance remain unverified.
See [`VALIDATION.md`](../VALIDATION.md) and [ACCESSIBILITY.md](ACCESSIBILITY.md).

## Troubleshooting

- If Gradle cannot find the SDK, set `ANDROID_HOME`/`ANDROID_SDK_ROOT` or add a
  local uncommitted `local.properties`.
- If installation selects the wrong device, rerun `adb devices -l` and pass an
  explicit serial.
- If an HTTP endpoint is rejected, use TLS; cleartext is intentionally disabled.
- If status speech is absent while TalkBack is active, this is expected: app
  TTS is suppressed to avoid competing speech, while visible/live-region text
  remains authoritative.
- If Process does not exercise the network, that is the current design of
  `MainActivity`, not proof of a transport failure.

See [ROKID_INTEGRATION.md](ROKID_INTEGRATION.md), [PROTOCOL.md](PROTOCOL.md), and
[TROUBLESHOOTING.md](TROUBLESHOOTING.md).
