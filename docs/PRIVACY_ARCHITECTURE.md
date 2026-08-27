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
| Image bytes | Bounded frame validation and synthetic/mock processing; physical Camera2 capture; acknowledged Rokid-to-Poco pull spool and transient QNN input | App-private no-backup spool until authenticated acknowledgement, new-session purge, or quota eviction |
| Pose and intrinsics | Optional frame context; Android pose sampling; gated IMU batches and optional validated Camera2 calibration metadata | Bounded app-private JSON manifest until acknowledgement, new-session purge, or quota eviction |
| On-demand microphone PCM | Explicit, maximum-ten-second Rokid microphone sublease | App-private WAV chunks until acknowledgement, new-session purge, or quota eviction |
| Environment evidence | Ephemeral semantic probabilities and optional aggregate GNSS reception quality | In-memory only; selected manual mode only is persisted |
| Request/session/stream/frame IDs | Correlation, ordering, cancellation, and health/status; ephemeral live session, lease, nonce, lane-ticket, and sequence state | In-memory session state only |
| Device capability labels | Route and worker selection | In-memory state and redacted operational logs |
| Observations and cues | Assistive scheduling and rendering | In-memory queues; inspectable status history may display cue text |
| Timing samples | Local latency summaries; live clock-offset/uncertainty evidence and aggregate p50/p95/p99 stage timing | Process memory or explicitly redirected command output |

There is no repository implementation for a durable frame database, capture archive,
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
one-shot `physical-trace` are separate authorized-ADB commands. The debug-only
direct live test is another explicit action: it runs for at most 30 seconds,
negotiates camera and IMU only, and can be stopped from either device. A
separate accessible Android control may request one mic-only window of at most
ten seconds after that session is mutually authenticated. The request is bound
to the exact active session/lease and must set explicit microphone intent;
missing permission or any mismatch rejects it without affecting camera/IMU.
Pairing, Poco listener startup, and Rokid client startup are separate steps;
pairing alone cannot begin capture.
Wearer gesture intents and Android Node commands contain only an ephemeral
session/lease binding, monotonic IDs/timestamps, an allowlisted operation, and
authorization/result flags. They contain no raw touch coordinates, key-event
history, stable user identifier, camera content, microphone content, or
location. They travel only inside the existing mutually authenticated TLS
control lane. ADB Wi-Fi and Bluetooth pairing are not treated as runtime
authorization.
`Camera2FrameSource` uses `acquireLatestImage`, a two-image reader, a configured
byte limit, and adaptive physical requests near 3 FPS relaxed/up to 5 FPS after
material change. Stopping the nonvisual activity unbinds the service and closes
camera, IMU, and microphone resources.
`stream-test` uses a local eight-second stream lease, separately limits the
explicit microphone request to two seconds, computes only aggregate PCM
activity evidence, and never writes or logs captured samples. Lease renewal
cannot silently extend microphone consent. `physical-trace` sends one bounded
JPEG and matched HEAD pose; microphone samples never enter its wire message and
only nonzero local signal gates dispatch. This development control is not the
intended product consent interface.

Live microphone PCM is never logged. Gate-admitted samples are written as
bounded app-private WAV chunks solely for the authenticated Android pull path;
they are excluded from Android backup and deleted after acknowledgement,
new-session purge, or quota eviction. Android host status contains only
aggregate chunk and byte counts. Per-chunk admission enforces the monotonic microphone deadline;
a dedicated deadline task closes the recorder, with the 20 ms controller poll
as a fallback. The Rokid emits short local start/stop earcon hooks; these are
status indicators, not content recording.

The pull spool is capped at 512 manifest records and 64 MiB of artifacts. It
uses atomic JSON/index replacement, coalesces IMU-only durability updates to at
most 10 Hz, validates relative paths and SHA-256 before host delivery, and does
not expose a content provider, shared-storage path, analytics upload, or backup
surface. See [Rokid pull spool](ROKID_PULL_SPOOL.md).

Glasses gesture control transmits only a typed START/STOP operation, ephemeral
session/lease binding, connection-local monotonic intent ID, monotonic
timestamp, and bounded duration. It contains no audio. STOP closes the local
recorder before network acknowledgement and immediately revokes host admission;
already-buffered audio received afterward is discarded. Intent metadata remains
in memory for replay/freshness checks and is neither logged nor persisted.

The Android host retains a synthetic diagnostic, and it now also implements a
debug-only direct live listener. Authenticated camera chunks are reassembled
latest-first and passed transiently to the bounded QNN executor; authenticated
IMU batches contribute aggregate counts. The direct mutual-TLS transport
completed two bounded physical Rokid-to-Poco runs on 2026-08-23 with camera and
IMU enabled and microphone excluded. Those runs exercised app-process QNN HTP
inference and carried target-fingerprint `DERIVED` camera intrinsics through to
aggregate host status. They establish bounded integration behavior, not
representative metric-depth accuracy, empirical camera calibration,
adverse-network recovery, or sustained thermal behavior; the exact aggregate
evidence is retained in `VALIDATION.md`.

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
- Repository fixtures are synthetic. Hardware diagnostics and the direct live
  path can process explicitly initiated real camera/IMU input, but no capture,
  dataset, private media, proprietary model weight, or vendor SDK binary is
  included in the repository.

## Transport

Production Python configuration requires TLS and rejects insecure binding.
Plaintext development/test binding is accepted only on loopback. Android host
and Rokid release network-security configurations disable cleartext traffic,
and their secure gRPC factories use TLS. The Rokid debug variant has a narrow
cleartext exception for literal `localhost`/`127.0.0.1`, used only through an
authorized ADB reverse tunnel. The .NET endpoint policy requires HTTPS except
when a visible development setting explicitly allows loopback HTTP; it does not
bypass certificate validation.

The direct Android transport separately requires TLS 1.3 mutual authentication
on both its realtime/control and camera sockets. Each app keeps its private key
non-exportable in Android Keystore. The debug-only pairing helper exchanges only
public certificates, pins the exact peer public key, and writes bounded
role-specific configuration to private no-backup storage without printing it.
The second lane must present a short-lived, single-use ticket issued over the
authenticated first lane. Only private or link-local address literals are
accepted.

TLS is necessary but not sufficient. A production deployment must add an
approved enrollment, certificate rotation/revocation, recovery, and device
decommissioning lifecycle; restrict network reachability; and independently
verify consent and endpoint integrity. The current pairing workflow is a
development procedure, not that lifecycle.

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

The direct Rokid live log contains categorical state and aggregate counts only.
The Poco live status contains bounded counts, p50/p95/p99 latency aggregates,
and p95 clock uncertainty. Neither includes frames, labels, raw IMU samples,
addresses, certificates, or endpoint/session/lease/frame identifiers. This is
local in-memory/UI/log status; no telemetry uploader is implemented.

The optional Rokid AccessibilityService is a broad-trust platform component
even though its manifest requests no screen content or touch exploration. It
does not log text, windows, arbitrary keys, device names, or descriptors. Its
local validation log is limited to candidate keycode/action/flag/scan metadata,
an ephemeral input-device ID, numeric vendor/product/source fields, a bounded
counter, and the allowlist result. Command dispatch defaults off and requires a
separate app-private opt-in after physical validation. Provisioning preserves
other enabled accessibility services and verifies that this service is bound,
not merely listed as enabled.

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
