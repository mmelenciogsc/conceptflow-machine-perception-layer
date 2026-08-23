<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Architecture

CONCEPTFlow Machine Perception Layer (MPL) is a protocol-first reference
architecture for supplemental environmental awareness. It accepts bounded image
frames, routes them to an eligible processor, correlates typed results to their
source frames, and schedules inspectable audio, speech, or haptic cues.

It is not an autonomous safety authority, navigation system, medical device, or
replacement for a mobility aid, trained judgment, or an emergency system.

## Map. Morph. Move.

The perception reference enforces three pure boundaries:

```text
Map:   frames + metric geometry + semantic depth + uncertainty -> SpatialMap
Morph: SpatialMap -> GeometryCue + AuditoryIconCue + HapticCue + SceneDescriptionRequest
Move:  motion + relative motion + cue history + freshness -> activation/suppression/priority/expiry
```

`packages/perception-core` contains the hardware-independent implementation.
Render adapters remain outside these stages: exact FMOD commands, Android
vibrator planning, Rokid output, and Windows diagnostics cannot redefine body
geometry or confidence. Immediate Tier 0 geometry never waits for Tier 1
segmentation or Tier 2 description.

The body field lives in BODY; the listener lives in HEAD; observations originate
in SENSOR; mapped geometry lives in a short-lived WORLD. See
[coordinate frames](COORDINATE_FRAMES.md) and the
[Sound Bubble specification](SOUND_BUBBLE_SPEC.md).

## System view

```text
Rokid client            Android or Windows host             Processing service
------------------      -------------------------------     ----------------------
Camera2 / sensors  -->  validate, bound, route          --> gRPC v1 control/frame
cue output         <--  correlate, reject stale, schedule <-- bounded worker pool
     |                         |                                  |
standard Android APIs    local policy and consent          mock / future model worker
```

The public repository keeps the three trust transitions explicit:

1. A capture producer creates a `FramePayload` with an ephemeral session,
   stream identity, monotonic frame identity, bounded content, and optional
   digest, pose, and intrinsics.
2. A host validates and routes the frame. A service admits work to a bounded
   queue and returns a `PerceptionResult` carrying the original correlation
   fields and provenance.
3. The host rejects unknown, mismatched, stale, or out-of-order results before
   scheduling a `PerceptionCue`. A renderer exposes only assistive output, never
   raw frame bytes.

The canonical contract is
[`perception.proto`](../packages/shared-protocol/proto/conceptflow/mpl/v1/perception.proto).
Its `PerceptionService` has exactly three typed unary methods: `Negotiate`,
`ProcessFrame`, and `Health`. The same schema now defines transport-neutral
lease and sensor-stream envelopes, with a tested Rokid packetizer and Android
host ingress. WebRTC signaling, authentication, and the physical data-channel
adapter remain unimplemented; there is no fictitious WebRTC RPC in this
repository. See [PROTOCOL.md](PROTOCOL.md).

## Repository layers

| Layer | Current responsibility | Principal source |
| --- | --- | --- |
| Shared protocol | v1 lease, sensor envelope, frame, result, cue, capability, QoS, error, provenance, and health messages | `packages/shared-protocol/proto/conceptflow/mpl/v1/perception.proto` |
| Python host runtime | validation, stream ordering, route policy, correlation, cue scheduling, rendering, and local latency summaries | `packages/host-runtime/src/conceptflow_mpl_host/` |
| Python cluster service | configuration, capability discovery, bounded admission, timeout/cancellation, worker health, TLS binding, and redacted logs | `services/cuda-cluster/src/conceptflow_mpl_cluster/` |
| Android protocol | Java-lite protobuf and gRPC bindings generated from the canonical schema | `packages/android-protocol/` |
| Android host | capability detection, preprocessing/routing, session and correlation state, cue scheduling, platform feedback, and transport abstractions | `apps/android-host/` |
| Rokid client | Standalone Android Camera2 capture, SensorManager pose, bounded frames, inspectable cue rendering, and direct ADB deployment | `apps/rokid-client/` |
| Windows relay | cross-platform .NET Core, gRPC client, bounded consented submission, WPF shell, headless demo, and QUICK example adapters | `apps/desktop-relay/` |
| Native worker primitives | C++20 worker selection, health transitions, bounded admission, cancellation state, demo, and tests | `services/cuda-cluster/native/` |

## Python execution path

`FramePreprocessor.validate` checks required identities, positive frame and
timestamp values, dimensions, encoding, payload size, raw stride, and optional
SHA-256. `FrameSequenceValidator.validate` then enforces monotonic frame IDs and
capture timestamps per session/stream.

`choose_route` is a pure policy. It compares local and cluster availability and
estimated latency with a budget; privacy-sensitive work stays local or fails
closed. `HostPipeline.process` registers the request before dispatch and cancels
that registration when transport fails.

`WorkerPool.submit` uses an `asyncio.Queue` with a configured capacity. A full
queue returns a typed overload error. Deadlines cancel processing tasks; caller
cancellation is forwarded; repeated worker failures cross a threshold and
remove that worker from selection. `PerceptionService.Health` reports pool and
worker state.

`ResultCorrelator.accept` consumes a pending request once and checks session,
stream, frame, capture timestamp, age, and delivery ordering. `CueScheduler`
applies TTL, priority, preemption, bounded capacity, deduplication cooldown,
verbosity, cancellation, and supersession. `InspectableCueRenderer.render`
emits a compact JSON record with `assistive_only=true` and no image bytes.
`HostPipeline` schedules every result cue in protocol order and renders only a
cue that the scheduler dispatches as active. `PipelineOutcome.schedules` and
`rendered_cues` expose the ordered records; the singular `schedule` and
`rendered` fields retain the one-cue API and expose the latest respective value.

## Android execution paths

The glasses app uses `Camera2FrameSource` and `SensorManagerPoseSource`, with
`SyntheticFrameSource` and `SyntheticPoseSource` available for deterministic
tests. `InspectableCueRenderer` rejects invalid, expired, duplicate, and older
cues before invoking bounded stereo and haptic outputs. It is installed as a
standalone APK through the 5-pin ADB cable and contains no Rokid companion-SDK
or client-secret integration. A bounded development adapter negotiates and
submits one physical frame through canonical gRPC over an authorized ADB
reverse loopback tunnel; release/non-loopback transport remains TLS-only.
`CueTransport` remains transport-neutral. The project-owned direct Android
data plane now uses two mutually authenticated TLS lanes for realtime/control
and camera traffic; its bounded physical validation is recorded separately.

The host app includes `AndroidCapabilityDetector`, `GlassesStreamIngress`, `BoundedFramePreprocessor`,
`RoutingPolicy`, `BoundedFrameQueue`, `SessionStateMachine`, `ResultCorrelator`,
and `CueScheduler`. `GrpcPerceptionTransport.secure` is implemented. The
synthetic diagnostic still uses `InProcessHostTransport` and
`InProcessCueDispatchTransport`, while the explicit debug live-test path starts
the project-owned mutual-TLS listener and app-process QNN executor. Two bounded
physical Rokid-to-Poco runs exercised that live path; it is not yet a production
background service and forced reconnect/adverse-network behavior remains
unvalidated.

## Windows execution path

`ConceptFlow.Mpl.DesktopRelay.Core` targets `net8.0` and owns endpoint policy,
ephemeral identity, content limits, bounded queues, reconnects, deadlines,
cancellation, result correlation, safe status, and gRPC transport.
`RelaySession.SubmitAsync` requires consent before constructing a frame.

The WPF shell targets `net8.0-windows`, defaults capture to off, exposes stock
keyboard-operable controls and UI Automation metadata, and consumes one-shot
approval after every submission. Its current capture choice is a fixed
synthetic one-pixel PNG; it does not read the screen, clipboard, camera, files,
or accessibility tree. The headless demo is also synthetic and refuses to run
without `--consent-synthetic-demo`.

## Native and CUDA boundary

The Python service owns gRPC, request validation, timeouts, and the running
worker pool. Compressed input is fully decoded under explicit resource bounds
immediately before Python worker admission; Android and .NET perform structural
preflight only. The C++20 component is separate. `WorkerRegistry` selects by
health, model support, device capability, memory, load, and latency tier;
`BoundedRequestQueue` exposes reject-newest/drop-oldest admission and observable
cancellation states.

`CONCEPTFLOW_ENABLE_CUDA=ON` makes CMake require a CUDA compiler and toolkit and
defines `CONCEPTFLOW_CUDA_BUILD_ENABLED`. It does not add a CUDA kernel, bind the
native scheduler to Python, load model weights, or execute inference.

## Security and privacy invariants

- Development and test may use plaintext only on loopback. Production
  configuration rejects insecure binding and requires certificate and key file
  paths.
- Frame, message, dimension, queue, retry, deadline, and result-age bounds are
  explicit at the applicable layer.
- Runtime identities and nonces are ephemeral. The repository implements no
  frame archive, frame logging, analytics upload, or retention store.
- Structured logging recursively redacts credential-like fields, nonces, keys,
  and image payload fields. The Windows status layer also removes sensitive
  shapes and endpoint details.
- Accessibility failures are release-blocking. Audio or color is never intended
  to be the only state channel.

See [PRIVACY_ARCHITECTURE.md](PRIVACY_ARCHITECTURE.md) and
[THREAT_MODEL.md](THREAT_MODEL.md) for trust-boundary analysis.

## Verified baseline and future work

The verified baseline covers the Python synthetic gRPC slice, both Android
debug builds and JVM tests, the .NET solution and headless demo on Ubuntu, and
the strict native build/test/demo including CUDA-aware compilation. Exact
evidence is recorded in [`VALIDATION.md`](../VALIDATION.md).

Still requiring hardware work are the project-owned phone-to-glasses transport,
sustained non-display Style Camera2 and audio/haptic/physical-control validation,
Windows UI and JAWS/NVDA
manual acceptance, real CUDA kernels and model execution, WebRTC media plane,
physical latency characterization, and safety/usability validation.
“Near-real-time” and “zero-touch” are experience goals, not claims of zero
physical latency or safety guarantees.

## Design-change rules

Any architectural change must preserve the v1 compatibility policy or publish
a new major version; keep queues and resources bounded; propagate cancellation;
reject stale or mismatched output; retain explicit consent where capture or
export requires it; avoid logging or persisting frames by default; preserve
keyboard and assistive-technology semantics; and record new evidence without
converting a planned feature into a verified claim.
