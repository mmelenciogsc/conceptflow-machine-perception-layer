<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Protocol

The canonical Machine Perception Layer contract is
[`packages/shared-protocol/proto/conceptflow/mpl/v1/perception.proto`](../packages/shared-protocol/proto/conceptflow/mpl/v1/perception.proto).
The package name is `conceptflow.mpl.v1`; generated options select
`org.conceptflow.mpl.v1` for Java and `ConceptFlow.Mpl.V1` for C#.

The schema is designed for typed negotiation, bounded request/response
processing, correlation, cancellation-aware clients, and inspectable assistive
cues. It does not define autonomous control or safety decisions.

## Service surface

`PerceptionService` contains exactly three unary RPCs.

| RPC | Request | Response | Purpose |
| --- | --- | --- | --- |
| `Negotiate` | `NegotiateRequest` | `NegotiateResponse` | Select a compatible v1 version, issue an ephemeral identity, and bound capabilities and QoS. |
| `ProcessFrame` | `FramePayload` | `PerceptionResult` | Process one bounded, correlated frame and return observations, cues, provenance, or a typed error. |
| `Health` | `HealthRequest` | `HealthResponse` | Report service status, protocol version, queue state, and optionally worker state. |

`Health` is this protocol’s typed health method; it is not a claim that the
standard `grpc.health.v1` service is registered.

The schema includes transport-neutral lease and sensor-stream messages plus an
implemented direct Android live-link envelope. The Rokid and Poco endpoints use
two private-WLAN TLS 1.3 mutual-TLS sockets: one realtime/control lane and one
camera lane. This transport, its pairing/configuration boundary, and its state
machines are locally tested. Two bounded physical Rokid-to-Poco runs completed
on 2026-08-23 over the private-WLAN path with camera and IMU enabled,
microphone excluded, and authenticated close. Their measured stage latency is
recorded in `VALIDATION.md`; forced reconnect, adverse-network throughput,
energy, and sustained thermal behavior remain pending. WebRTC signaling and a
WebRTC data-channel adapter are not implemented.

The backend baseline separately places image bytes directly in unary
`ProcessFrame` messages for deterministic integration and testing. A future
WebRTC adapter may carry the same bounded sensor messages, but it is not the
current direct Android transport.

## Glasses stream lease and live wire envelopes

`StreamLeaseRequest` expresses open, renew, or close intent and the requested
camera, IMU, and microphone streams. A microphone request is separately
explicit. `StreamLeaseGrant` returns bounded parameters; duration is expressed
as a relative interval so a receiver never compares unrelated device monotonic
clock epochs. The general schema can represent microphone data, but the current
direct Rokid-to-Poco lease accepts exactly camera and IMU, sets
`user_requested_microphone=false`, and rejects microphone payloads on both
send and receive.

`SensorStreamEnvelope` carries exactly one of:

- a `CameraFrameChunk`, with 16 KiB used by the current sender and a complete
  metadata-only `FramePayload` on chunk zero;
- an `ImuBatch` of absolute pose, angular velocity, and linear acceleration
  readings rather than loss-sensitive deltas;
- a bounded PCM `MicrophoneChunk` for other explicitly authorized transport
  profiles; the direct Android live link never admits one;
- a lease grant or a typed error.

On the implemented direct link, lease requests, grants, and typed control
errors use `LiveLinkControl`; the corresponding `SensorStreamEnvelope`
alternatives remain transport-neutral schema options and are not its wire
control path.

`LiveLinkEnvelope` is the canonical record on the implemented TLS framing
layer. It wraps either `LiveLinkControl` or `SensorStreamEnvelope` and binds the
record to the active session, lease, lane, strictly increasing per-lane
sequence, and sender monotonic timestamp. The realtime/control lane performs
hello, lease negotiation, clock probes, periodic clock resynchronization,
keepalive, and IMU delivery. It issues a short-lived, single-use camera-lane
ticket bound to the fresh 32-byte connection nonce, session, and lease. The
camera lane must redeem that ticket before carrying camera chunks.

Both lanes are reliable and ordered TCP/TLS streams. The host enforces lane,
envelope, frame, batch, and sample order; bounds allocation before assembly;
verifies the completed camera payload SHA-256; drops an incomplete older frame
when a newer frame begins; and retains only the latest unread complete frame.
An NTP-style monotonic clock estimate normalizes glasses capture timestamps
into the host clock domain and carries uncertainty evidence; raw monotonic
values from different devices are never compared directly. Lease deadlines,
liveness timeouts, disconnect classification, and bounded reconnect policy
remain independent of the payload schema.

## Version negotiation

Clients send one or more `ProtocolVersion` values, a non-empty
`client_instance_id`, an optional ephemeral identity, capabilities, and desired
QoS. The Python `PerceptionService.Negotiate` accepts only the server’s major
version and selects bounded minor/patch values. An incompatible or missing
version produces `ERROR_CODE_UNSUPPORTED_VERSION` in the response.

The current server version is `1.0.0`. Compatibility within v1 must remain
protobuf-safe: do not reuse field numbers, change existing field meaning, or
turn optional/default behavior into a breaking requirement. Breaking semantics
require a new package major version.

The server issues a random session ID, a 16-byte nonce, and an expiry ten
minutes in the future. Consumers must treat these as ephemeral correlation
material, not authentication credentials or durable user identity.

## Frame contract

`FramePayload` binds payload bytes to:

- `request_id`, `session_id`, `stream_id`, and monotonic `frame_id`;
- monotonic capture time and optional wall time;
- `ImageDescriptor` dimensions, stride, encoding, media type, byte length, and
  optional SHA-256;
- optional camera intrinsics and pose with a named coordinate frame;
- a processing deadline; and
- a `synthetic` provenance marker.

The Python `FramePreprocessor.validate` enforces identity, positive IDs and
timestamps, dimensions, encoding, byte limits, payload size, raw-image stride,
bounded compressed-image structure, and SHA-256 when supplied. At the final
Python service boundary, `BoundedImageDecoder.validate` then fully decodes PNG
and JPEG payloads under explicit dimension, pixel, and decoded-byte limits
before `WorkerPool.submit`. `FrameSequenceValidator.validate` enforces
increasing frame IDs and capture timestamps independently for each
session/stream.

Android producers intentionally narrow this further. `Camera2FrameSource`
produces bounded JPEG frames, while `FrameValidator` and
`BoundedFramePreprocessor` require JPEG metadata and size consistency. These
Android checks are structural preflight, not a decodability guarantee; the
authoritative service decode remains mandatory. A future adapter must negotiate
or translate formats; it must not silently relabel encoded content.

## Result and correlation contract

`PerceptionResult` repeats `request_id`, `session_id`, `stream_id`, `frame_id`,
and capture timestamp. Hosts must match all fields to a pending request, enforce
an age limit, consume each pending entry once, and reject results older than the
latest delivered frame. Python uses `ResultCorrelator.accept`; Android and .NET
provide corresponding `ResultCorrelator` implementations.

Unknown, duplicate, cancelled, mismatched, stale, and out-of-order results do
not reach cue rendering. A transport success is therefore not equivalent to an
accepted result.

`Provenance` records the component, worker, model and artifact identifiers,
processing interval, source result IDs, and whether processing is synthetic.
The included deterministic worker always labels its output synthetic and is not
a production model.

## Cue contract

`PerceptionCue` contains frame identity, creation time, TTL, category,
description, confidence, priority, spatial fields, direction, urgency, optional
earcon/speech/haptic modalities, cancellation, supersession, and provenance.

The contract carries desired cue semantics; a client remains responsible for
TTL checks, deduplication, modality availability, priority, interruption,
bounded scheduling, and accessible fallback. The Python renderer emits
`assistive_only=true`. No cue is a safety guarantee or authority to act.

## Errors

`ErrorStatus` uses a typed code, redacted human-readable message, retryability,
optional retry delay, and correlation ID.

| Code | Intended condition |
| --- | --- |
| `INVALID_ARGUMENT` | Missing identity, malformed metadata, digest mismatch, or invalid content. |
| `UNSUPPORTED_VERSION` | No compatible protocol major version. |
| `OVERSIZE` | Message, dimensions, or frame content exceeds a configured bound. |
| `OVERLOADED` | A bounded queue rejected admission. |
| `CANCELLED` | Work was cancelled before completion. |
| `DEADLINE_EXCEEDED` | Processing exceeded its effective deadline. |
| `WORKER_UNAVAILABLE` | No healthy eligible worker completed the request. |
| `STALE` | A frame or result is too old or out of order. |
| `INTERNAL` | A contained implementation failure without a more specific code. |

Retries must honor `retryable`, `retry_after_ms`, the user’s current consent,
the current session, and a bounded attempt policy. Retrying must not replay an
expired frame or silently renew capture approval.

## Transport security and bounds

The Python server applies gRPC send/receive size limits. Production
`ClusterConfig` rejects plaintext and requires TLS certificate and private-key
paths; development/test plaintext is restricted to loopback. The Android
`GrpcPerceptionTransport.secure` uses transport security. The .NET
`EndpointPolicy` accepts HTTPS by default and permits HTTP only for explicit
loopback development.

For the direct Android link, each device creates a non-exportable identity in
Android Keystore. The debug-only pairing helper exchanges public certificates,
installs role-specific configuration in each app's private no-backup directory,
and pins the exact peer public key. Both sockets require TLS 1.3 and mutual
authentication. Private or link-local address validation, distinct ports,
bounded framing, one active connection attempt, a single-use camera ticket,
and fail-closed session/lease/lane checks narrow the test boundary. The pairing
helper does not start capture or either endpoint. Certificate rotation and a
production enrollment/revocation lifecycle are not implemented.

TLS protects the channel but does not establish user consent, authorize a
model, validate cue meaning, or prevent a compromised endpoint from producing
bad results. Those controls remain separate.

## Generation and validation

Python generated files are checked in. From the repository root:

```bash
./scripts/bootstrap
./scripts/generate
.venv/bin/python -m conceptflow_mpl_protocol.validation
```

`conceptflow_mpl_protocol.validation` checks the package, exact RPC surface,
complete cue field order, byte type of `FramePayload.frame_data`, and a clean
deterministic regeneration. Android and .NET generate language bindings from
the same schema during their builds. A checked-in deterministic wire vector is
parsed and byte-exactly reserialized by Python, Java-lite, and C# tests; the
Python gRPC boundary also accepts the exact PNG/JPEG fixtures shipped by the
desktop and Android clients. Review schema and all generated diffs together.

Focused Android live-link validation is available through:

```bash
./gradlew --no-daemon --rerun-tasks \
  :packages:android-live-transport:testDebugUnitTest \
  :apps:android-host:testDebugUnitTest \
  :apps:rokid-client:testDebugUnitTest
```

These JVM tests validate framing, mutual-TLS pinning, tickets, sequences,
leases, liveness, clock normalization, bounded queues, reassembly, capture
gates, QNN routing contracts, and debug command authorization. They do not
constitute a physical wireless or app-process QNN test.

## Wire-level integration checklist

Before connecting a new producer or consumer:

1. Negotiate v1 and retain only the returned ephemeral session.
2. Enforce negotiated encodings, dimensions, bytes, in-flight work, deadlines,
   frame dropping, and cue count.
3. Use monotonic timestamps for age decisions; wall time is descriptive only.
4. Propagate cancellation through transport and worker boundaries.
5. Match every result to the full correlation tuple before rendering.
6. Drop expired, duplicate, superseded, and disallowed cues locally.
7. Keep frame data out of logs, traces, crash reports, and status UI.
8. Test overload and reconnection with synthetic content before hardware data.
9. For the direct Android link, pair public certificates before starting the
   Poco listener, start the listener before the Rokid client, and never add a
   microphone stream to the granted live lease.

Related documents: [ARCHITECTURE.md](ARCHITECTURE.md),
[PRIVACY_ARCHITECTURE.md](PRIVACY_ARCHITECTURE.md), and
[LATENCY_BENCHMARKING.md](LATENCY_BENCHMARKING.md).
