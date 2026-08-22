<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Machine Perception Layer, Android Node

The Android Node is the phone-side policy and cue-dispatch reference. It detects
available platform capabilities, validates and queues frames, chooses a local
or remote route, tracks session and result state, schedules cues, and exposes
accessible feedback. Its current activity runs an in-process synthetic
vertical slice; it does not yet exchange frames or cues with the Rokid app. The
host now also contains a bounded `GlassesStreamIngress` for the shared leased
sensor envelopes, but no physical phone-to-glasses network adapter is wired to
it. A separate direct Rokid-to-Ubuntu development trace exercises the shared
protobuf contract without changing this boundary.

Its first product sublayer is Machine Vision. The implemented pure-Kotlin core
has a fixed 40-class BVI vocabulary, dual indoor/outdoor Depth Anything profile
selection, an 80-record two-distance dimension-vector table, bounded relative-
to-metric calibration, fixed-vocabulary artifact verification, and truthful
QNN HTP capability planning for the Poco F7 Ultra. See
[Android Machine Vision](ANDROID_MACHINE_VISION.md). No model runtime or model
weight is bundled, and no real model inference is claimed.

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
| `AccessibilityAwareSpeechFeedback.speak` | Text-to-speech when available, suppressed when an accessibility service is enabled. |
| `MachineVisionPipeline.process` | Correlated, freshness-bounded, fixed-vocabulary semantic-mask/depth fusion using the selected environment profile. |
| `TwoAnchorMetricDepthCalibrator.calibrate` | Robust 0.6096 m / 2.4384 m relative-depth calibration with uncertainty and extrapolation reporting. |
| `QualcommAcceleratorPlanner.select` | HTP only after QNN and HTP initialization; otherwise explicit reference/unavailable state. |

`apps/android-host/src/main/res/xml/network_security_config.xml` disables
cleartext traffic. The secure transport accepts a host and port through
`GrpcEndpoint`; it does not bypass platform certificate validation.

## Current sample activity

`MainActivity` intentionally constructs `InProcessHostTransport`. Connect
negotiates a synthetic session, Process generates a real decodable one-pixel
JPEG fixture, and the synthetic result contains one directional obstacle cue.
`InProcessCueDispatchTransport` sends that cue to Android audio/haptic feedback
within the same app. The UI does not instantiate `GrpcPerceptionTransport`,
does not receive a physical glasses frame, and does not send a cue to the Rokid
APK.

This separation allows policy tests and APK inspection without pretending the
device bridge is complete. A production integration must wire an authenticated
WebRTC or equivalently bounded media adapter to `GlassesStreamIngress`, then wire concrete capture
and cue transports, preserve cancellation, and display whether work is local,
remote, degraded, or disconnected.

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
5. Cancel or Disconnect closes the current sample session.

This does not validate phone-to-glasses transport or the Python service.

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

The current validation record reports 129 JVM tests across the Android apps and
shared protocol module: 50 Android Node tests, 78 Rokid Node tests, and one
byte-exact Python/Java protocol vector. Both debug APK builds succeeded using
JDK 17 and an installed Android SDK. The host source includes the capability,
preprocessing, routing, session, correlation, scheduler, Machine Vision, gRPC,
and TalkBack-aware semantics described above.

Not verified: real phone-to-glasses transport, real Python-service transport
from the app activity, instrumentation tests, Android hardware cue quality, or
TalkBack acceptance. See [`VALIDATION.md`](../VALIDATION.md) and
[ACCESSIBILITY.md](ACCESSIBILITY.md).

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
