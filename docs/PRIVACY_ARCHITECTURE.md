<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Privacy architecture

MPL handles inputs that may reveal a person’s surroundings. The public baseline
therefore minimizes collection, keeps data ephemeral by default, bounds every
processing path, and makes capture or export an explicit user-controlled act.
These are architecture controls, not a certification or guarantee against all
privacy failures.

## Data inventory

| Data | Current use | Default persistence |
| --- | --- | --- |
| Image bytes | Bounded frame validation and synthetic/mock processing; physical Camera2 capture and one-shot development transport from the glasses app | None implemented |
| Pose and intrinsics | Optional frame context in the protocol; Android pose sampling | None implemented |
| Environment evidence | Ephemeral semantic probabilities and optional aggregate GNSS reception quality | In-memory only; selected manual mode only is persisted |
| Request/session/stream/frame IDs | Correlation, ordering, cancellation, and health/status | In-memory session state only |
| Device capability labels | Route and worker selection | In-memory state and redacted operational logs |
| Observations and cues | Assistive scheduling and rendering | In-memory queues; inspectable status history may display cue text |
| Timing samples | Local latency summaries | Process memory or explicitly redirected command output |

There is no repository implementation for a frame database, capture archive,
analytics uploader, background telemetry service, model-training ingestion, or
automatic publication. “No retention by default” does not mean bytes never
exist: a frame is held transiently in application, protobuf, gRPC, and worker
memory while a request is processed. Operating-system swap, crash dumps,
external observability agents, and screen readers are deployment concerns that
must be configured separately.

## Capture and consent

The non-display Rokid client has no launcher UI. Installation leaves its
nonvisual runtime stopped. On a controlled development unit, the direct-sideload
helper can grant only the declared camera and microphone permissions after
explicit `--grant-camera` and `--grant-microphone` operator actions.
`rokid-control capture-start`, the bounded eight-second `stream-test`, and the
one-shot `physical-trace` are separate authorized-ADB commands.
`Camera2FrameSource` uses `acquireLatestImage`, a two-image reader, a configured
byte limit, and adaptive physical requests near 2 FPS stable/up to 5 FPS after
material change. Stopping the nonvisual
activity unbinds the service and closes camera, IMU, and microphone resources.
`stream-test` uses a local eight-second stream lease, separately limits the
explicit microphone request to two seconds, computes only aggregate PCM
activity evidence, and never writes or logs captured samples. Lease renewal
cannot silently extend microphone consent. `physical-trace` sends one bounded JPEG and matched
HEAD pose; microphone samples never enter its wire message and only nonzero
local signal gates dispatch. This development control is not the intended
product consent interface.

The current Android host demo generates a synthetic frame after the user
connects and presses Process. Its activity does not receive real glasses frames
because inter-device transport is not implemented.

Automatic depth-profile routing treats camera semantics as primary evidence.
The Android host requests precise location only after the user explicitly
selects Automatic mode, samples GNSS in a bounded foreground burst, and stops
sampling when the activity stops or a manual profile is selected. The source
copies only monotonic fix time and horizontal accuracy from `Location`; it
never reads or stores latitude, longitude, altitude, speed, or bearing. GNSS
satellite count and aggregate carrier-to-noise are short-lived supporting
evidence, never sole scene truth. Microphone and Wi-Fi identifiers are excluded
from environment classification by default.

The Windows WPF relay starts with capture off. Its sole current choice is a
fixed synthetic one-pixel PNG. `RelaySession.SubmitAsync` calls
`ContentValidator.Validate`, which requires `ConsentGranted`; the WPF shell
consumes approval after one submission. The headless demo requires the explicit
`--consent-synthetic-demo` argument.

Future continuous or zero-touch behavior must have an unambiguous start/stop
state, screen-reader-readable host status, distinctive nonvisual glasses
feedback, revocable consent, bounded duration, and a local kill path. The
glasses' hardware recording indicator must remain active. Zero-touch cannot
mean covert capture.

## Minimization and bounds

- `FramePreprocessor`, Android `FrameValidator`, Android
  `BoundedFramePreprocessor`, and .NET `ContentValidator` reject malformed or
  oversized inputs before processing.
- Queue capacities, message sizes, dimensions, worker deadlines, retry counts,
  pending correlation entries, cue queues, cue TTLs, and status history are
  bounded in their respective implementations.
- `RoutingPolicy` and Python `choose_route` can keep sensitive work local or
  fail closed when policy has no eligible route.
- Identifiers are purpose-limited. Python negotiation and
  `EphemeralIdentityFactory` generate per-session values; they are not stable
  account or device identifiers.
- The public test/demo inputs are synthetic. No camera captures, datasets,
  private brand media, proprietary model weights, or vendor SDK binaries are
  included.

## Transport

Production Python configuration requires TLS and rejects insecure binding.
Plaintext development/test binding is accepted only on loopback. Android host
and Rokid release network-security configurations disable cleartext traffic,
and their secure gRPC factories use TLS. The Rokid debug variant has a narrow
cleartext exception for literal `localhost`/`127.0.0.1`, used only through an
authorized ADB reverse tunnel. The .NET endpoint policy requires HTTPS except
when a visible development setting explicitly allows loopback HTTP; it does not
bypass certificate validation.

TLS is necessary but not sufficient. A deployment must provision and rotate
certificates outside the repository, authenticate authorized clients and
workers, restrict network reachability, and decide whether mutual TLS or an
equivalent identity layer is required.

## Safe logging

Python `RedactedJsonFormatter` and `redact` remove fields whose names contain
authorization, credential, frame/image data, nonce, password, private key,
secret, or token markers. Arbitrary bytes are summarized by length. Service
events log identifiers, frame numbers, error classes, and state transitions—not
frame content.

The .NET `SafeStatus` and `StatusRedactor` remove common credential shapes,
sanitize correlation IDs, and reduce endpoints to a redacted origin. WPF status
history is bounded to a short text window. Operators must apply the same policy
to reverse proxies, mobile logs, crash reporting, GPU profilers, and future
device transports.

## Deployment requirements

Before processing non-synthetic content:

1. Define purpose, legal basis, operator, retention period, deletion behavior,
   and geographic routing.
2. Obtain accessible, revocable consent appropriate to the capture mode.
3. Use production TLS and an authenticated authorization layer; do not reuse
   development loopback settings.
4. Keep capture and inference on the minimum devices required. Disable external
   providers unless explicitly approved.
5. Configure logs and crash dumps to exclude payloads, credentials, exact
   device identifiers, and sensitive scene text.
6. Confirm queue/size/rate/deadline limits under adversarial load.
7. Verify shutdown, cancellation, app backgrounding, connection loss, and
   worker failure do not prolong capture or retain stale frames.
8. Run the accessibility acceptance procedures in
   [ACCESSIBILITY.md](ACCESSIBILITY.md).
9. Document any retention or export that differs from the repository default.

## Data-subject and incident handling

Because the baseline has no durable content store, deletion normally means
ending processing and clearing in-memory queues. Deployments that add storage,
telemetry, backups, or third-party models also acquire data-access, deletion,
retention, breach-response, and vendor-management obligations. Those additions
must be documented and threat-modeled before release.

Do not attach real captures, credentials, personal data, or device identifiers
to public issues. Follow [`SECURITY.md`](../SECURITY.md) for private reporting
and use synthetic or redacted reproduction material.

Related documents: [THREAT_MODEL.md](THREAT_MODEL.md),
[PROTOCOL.md](PROTOCOL.md), and [ACCESSIBILITY.md](ACCESSIBILITY.md).
